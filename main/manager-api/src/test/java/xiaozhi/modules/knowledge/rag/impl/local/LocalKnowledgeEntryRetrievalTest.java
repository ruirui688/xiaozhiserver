package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xiaozhi.modules.knowledge.dao.DocumentDao;
import xiaozhi.modules.knowledge.dao.KnowledgeChunkDao;
import xiaozhi.modules.knowledge.dto.document.RetrievalDTO;
import xiaozhi.modules.knowledge.entity.DocumentEntity;
import xiaozhi.modules.knowledge.entity.KnowledgeChunkEntity;

class LocalKnowledgeEntryRetrievalTest {
    @TempDir Path directory;

    private KnowledgeChunkEntity chunk(String text, int enabled) {
        var chunk = new KnowledgeChunkEntity();
        chunk.setId("row"); chunk.setChunkId("entry");
        chunk.setDatasetId("dataset"); chunk.setDocumentId("document");
        chunk.setContent(text); chunk.setEnabled(enabled);
        return chunk;
    }

    @Test
    void editsDisableRestoreAndIndexRecreationReadCurrentCanonicalContent() {
        var chunks = mock(KnowledgeChunkDao.class);
        var documents = mock(DocumentDao.class);
        var doc = new DocumentEntity();
        doc.setId("document-row"); doc.setDatasetId("dataset"); doc.setDocumentId("document");
        doc.setName("商品"); doc.setEnabled(1); doc.setRun("DONE"); doc.setContentVersion(0L);
        var current = new AtomicReference<>(chunk("商品：可乐\n售价：25元", 1));
        when(documents.selectList(any())).thenAnswer(call -> List.of(doc));
        when(chunks.selectIndexableChunks(anyList(), anyList())).thenAnswer(call ->
                current.get().getEnabled() == 1 ? List.of(current.get()) : List.of());
        when(chunks.selectList(any())).thenAnswer(call -> List.of(current.get()));
        when(chunks.selectRetrievalCandidates(anyList(), any(), anyList(), nullable(String.class), anyInt()))
                .thenAnswer(call -> current.get().getEnabled() == 1 ? List.of(current.get()) : List.of());
        var index = new LocalKnowledgeLuceneIndex(directory.resolve("index"));
        var engine = new LocalKnowledgeRetrievalEngine(chunks, documents, index);
        var request = new RetrievalDTO.TestReq();
        request.setDatasetIds(List.of("dataset")); request.setQuestion("可乐售价");
        var options = new LocalKnowledgeRetrievalEngine.Options(3, 5, 20, 0);
        assertTrue(engine.retrieve(request, options).getChunks().get(0).getContent().contains("25元"));

        current.set(chunk("商品：可乐\n售价：28元", 1)); doc.setContentVersion(1L); doc.setIndexDirty(1);
        assertTrue(engine.retrieve(request, options).getChunks().get(0).getContent().contains("28元"));
        current.set(chunk("商品：可乐\n售价：28元", 0)); doc.setContentVersion(2L);
        assertTrue(engine.retrieve(request, options).getChunks().isEmpty());
        assertTrue(index.staleDocuments(List.of(doc)).isEmpty(), "disabled document needs an empty version marker");
        current.set(chunk("商品：可乐\n售价：25元", 1)); doc.setContentVersion(3L);
        var rebuilt = new LocalKnowledgeRetrievalEngine(chunks, documents,
                new LocalKnowledgeLuceneIndex(directory.resolve("fresh-index")));
        assertTrue(rebuilt.retrieve(request, options).getChunks().get(0).getContent().contains("25元"));
        // A second process with its old index must also observe the DB revision.
        assertTrue(engine.retrieve(request, options).getChunks().get(0).getContent().contains("25元"));
    }

    @Test
    void brokenIndexFallsBackToEnabledCanonicalRows() {
        var chunks = mock(KnowledgeChunkDao.class);
        var documents = mock(DocumentDao.class);
        var index = mock(LocalKnowledgeLuceneIndex.class);
        when(index.staleDocuments(anyList())).thenThrow(new IllegalStateException("disk unavailable"));
        var doc = new DocumentEntity();
        doc.setDocumentId("document"); doc.setDatasetId("dataset"); doc.setEnabled(1); doc.setRun("DONE");
        when(documents.selectList(any())).thenReturn(List.of(doc));
        when(chunks.selectRetrievalCandidates(anyList(), any(), anyList(), nullable(String.class), anyInt()))
                .thenReturn(List.of(chunk("商品：可乐\n售价：28元", 1)));
        var request = new RetrievalDTO.TestReq();
        request.setDatasetIds(List.of("dataset")); request.setQuestion("可乐售价");
        var result = new LocalKnowledgeRetrievalEngine(chunks, documents, index).retrieve(request,
                new LocalKnowledgeRetrievalEngine.Options(3, 5, 20, 0));
        assertEquals(1, result.getChunks().size());
        assertTrue(result.getChunks().get(0).getContent().contains("28元"));
    }

    @Test
    void indexNeverPublishesDisabledRows() {
        var index = new LocalKnowledgeLuceneIndex(directory.resolve("disabled-index"));
        index.replaceDocument("dataset", "document", "商品", List.of(chunk("可乐售价25元", 0)), 2L);
        assertTrue(index.search(List.of("可乐"), List.of("dataset"), null, 10).isEmpty());
    }

    @Test
    void concurrentEditAfterIndexCheckFallsBackInsteadOfReturningOldPrice() {
        var chunks = mock(KnowledgeChunkDao.class);
        var documents = mock(DocumentDao.class);
        var index = mock(LocalKnowledgeLuceneIndex.class);
        var doc = new DocumentEntity();
        doc.setDatasetId("dataset"); doc.setDocumentId("document"); doc.setEnabled(1); doc.setRun("DONE");
        when(documents.selectList(any())).thenReturn(List.of(doc));
        when(index.staleDocuments(anyList())).thenReturn(List.of());
        when(index.search(anyList(), anyList(), any(), anyInt())).thenReturn(List.of(
                new LocalKnowledgeLuceneIndex.SearchHit(chunk("可乐售价25元", 1), "商品", 1F)));
        when(chunks.selectList(any())).thenReturn(List.of(chunk("可乐售价28元", 1)));
        when(chunks.selectRetrievalCandidates(anyList(), any(), anyList(), nullable(String.class), anyInt()))
                .thenReturn(List.of(chunk("可乐售价28元", 1)));
        var request = new RetrievalDTO.TestReq();
        request.setDatasetIds(List.of("dataset")); request.setQuestion("可乐售价");
        var result = new LocalKnowledgeRetrievalEngine(chunks, documents, index).retrieve(request,
                new LocalKnowledgeRetrievalEngine.Options(3, 5, 20, 0));
        assertEquals(1, result.getChunks().size());
        assertEquals("可乐售价28元", result.getChunks().get(0).getContent());
    }

    @Test
    void sameDocumentIdsInDifferentDatasetsKeepTheirOwnEnabledState() {
        var chunks = mock(KnowledgeChunkDao.class);
        var documents = mock(DocumentDao.class);
        var index = mock(LocalKnowledgeLuceneIndex.class);
        var disabled = new DocumentEntity();
        disabled.setDatasetId("dataset"); disabled.setDocumentId("document"); disabled.setEnabled(0); disabled.setRun("DONE");
        var enabled = new DocumentEntity();
        enabled.setDatasetId("other"); enabled.setDocumentId("document"); enabled.setEnabled(1); enabled.setRun("DONE");
        when(documents.selectList(any())).thenReturn(List.of(disabled, enabled));
        when(index.staleDocuments(anyList())).thenThrow(new IllegalStateException("disk unavailable"));
        var other = chunk("可乐售价28元", 1); other.setDatasetId("other"); other.setId("other-row");
        when(chunks.selectRetrievalCandidates(anyList(), any(), anyList(), nullable(String.class), anyInt()))
                .thenReturn(List.of(chunk("可乐售价25元", 1), other));
        var request = new RetrievalDTO.TestReq();
        request.setDatasetIds(List.of("dataset", "other")); request.setQuestion("可乐售价");
        var result = new LocalKnowledgeRetrievalEngine(chunks, documents, index).retrieve(request,
                new LocalKnowledgeRetrievalEngine.Options(3, 5, 20, 0));
        assertEquals(1, result.getChunks().size());
        assertEquals("可乐售价28元", result.getChunks().get(0).getContent());
    }
}
