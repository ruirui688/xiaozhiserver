package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import xiaozhi.modules.knowledge.dao.DocumentDao;
import xiaozhi.modules.knowledge.dao.KnowledgeChunkDao;
import xiaozhi.modules.knowledge.entity.DocumentEntity;
import xiaozhi.modules.knowledge.rag.impl.LocalKnowledgeAdapter;
import xiaozhi.common.exception.RenException;

class LocalKnowledgeAdapterContractTest {
    @Test
    void reportsSynchronousDocumentParsing() {
        assertTrue(new LocalKnowledgeAdapter().isDocumentParsingSynchronous());
    }

    @Test
    void acceptsParseOnlyWhenUploadAlreadyProducedChunks() {
        LocalKnowledgeAdapter adapter = new LocalKnowledgeAdapter();
        DocumentDao documentDao = mock(DocumentDao.class);
        KnowledgeChunkDao chunkDao = mock(KnowledgeChunkDao.class);
        when(documentDao.selectOne(any())).thenReturn(new DocumentEntity());
        when(chunkDao.selectCount(any())).thenReturn(2L);
        ReflectionTestUtils.setField(adapter, "documentDao", documentDao);
        ReflectionTestUtils.setField(adapter, "chunkDao", chunkDao);

        assertTrue(adapter.parseDocuments("dataset-1", List.of("document-1")));
    }

    @Test
    void rejectsParseWhenUploadDidNotProduceChunks() {
        LocalKnowledgeAdapter adapter = new LocalKnowledgeAdapter();
        DocumentDao documentDao = mock(DocumentDao.class);
        KnowledgeChunkDao chunkDao = mock(KnowledgeChunkDao.class);
        when(documentDao.selectOne(any())).thenReturn(new DocumentEntity());
        when(chunkDao.selectCount(any())).thenReturn(0L);
        ReflectionTestUtils.setField(adapter, "documentDao", documentDao);
        ReflectionTestUtils.setField(adapter, "chunkDao", chunkDao);

        RenException error = assertThrows(RenException.class,
                () -> adapter.parseDocuments("dataset-1", List.of("document-1")));
        assertTrue(error.getMessage().contains("请重新上传原文件"));
    }
}
