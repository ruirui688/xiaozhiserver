package xiaozhi.modules.knowledge.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.knowledge.dao.*;
import xiaozhi.modules.knowledge.dto.*;
import xiaozhi.modules.knowledge.entity.*;

class KnowledgeEntryServiceTest {
    KnowledgeBaseService bases = mock(KnowledgeBaseService.class);
    KnowledgeResourcePermissionService permissions = mock(KnowledgeResourcePermissionService.class);
    DocumentDao docs = mock(DocumentDao.class);
    KnowledgeChunkDao chunks = mock(KnowledgeChunkDao.class);
    KnowledgeEntryRevisionDao revisions = mock(KnowledgeEntryRevisionDao.class);
    KnowledgeBaseDao baseDao = mock(KnowledgeBaseDao.class);
    KnowledgeFilesService files = mock(KnowledgeFilesService.class);
    KnowledgeEntryService service = new KnowledgeEntryService(bases, permissions, docs, chunks, revisions, baseDao, files);
    DocumentEntity doc;
    KnowledgeChunkEntity entry;
    KnowledgeBaseDTO base;

    @BeforeEach void setup() {
        base = new KnowledgeBaseDTO(); base.setDatasetId("dataset"); base.setWorkspaceId("workspace");
        when(bases.getByDatasetId("dataset")).thenReturn(base);
        when(permissions.canWrite(base)).thenReturn(true);
        when(baseDao.selectOne(any())).thenReturn(new KnowledgeBaseEntity());
        doc = new DocumentEntity(); doc.setId("doc-row"); doc.setDatasetId("dataset"); doc.setDocumentId("document");
        doc.setWorkspaceId("workspace"); doc.setRun("DONE"); doc.setSourceType("local_knowledge");
        doc.setContentVersion(0L); doc.setChunkCount(1); doc.setTokenCount(10L);
        when(docs.selectOne(any())).thenReturn(doc);
        entry = new KnowledgeChunkEntity(); entry.setId("entry"); entry.setChunkId("chunk");
        entry.setDatasetId("dataset"); entry.setDocumentId("document"); entry.setWorkspaceId("workspace");
        entry.setContent("售价25"); entry.setEditVersion(0L); entry.setEnabled(1); entry.setTokenCount(10L);
        when(chunks.selectOne(any())).thenReturn(entry);
        when(chunks.update(isNull(), any())).thenReturn(1);
        when(revisions.selectCount(any())).thenReturn(0L);
    }

    KnowledgeEntryRequest request() {
        KnowledgeEntryRequest r = new KnowledgeEntryRequest(); r.setContent("售价28"); r.setExpectedVersion(0L); return r;
    }

    @Test void updatePreservesOriginalThenRecordsRevisionAndMarksIndexDirty() {
        var result = service.update("dataset", "document", "entry", request());
        assertEquals("售价28", result.get("content")); assertEquals(1L, result.get("editVersion"));
        assertEquals("pending", result.get("indexStatus"));
        ArgumentCaptor<KnowledgeEntryRevisionEntity> history = ArgumentCaptor.forClass(KnowledgeEntryRevisionEntity.class);
        verify(revisions, times(2)).insert(history.capture());
        assertEquals("售价25", history.getAllValues().get(0).getContent());
        assertEquals("售价28", history.getAllValues().get(1).getContent());
        verify(docs).update(isNull(), argThat(w -> w.getSqlSet().contains("index_dirty")));
        verify(baseDao).updateStatsAfterChange(eq("dataset"), eq(0), eq(0L), anyLong());
    }

    @Test void staleVersionCannotWrite() {
        var r = request(); r.setExpectedVersion(5L);
        assertThrows(RenException.class, () -> service.update("dataset", "document", "entry", r));
        verify(chunks, never()).update(isNull(), any()); verify(revisions, never()).insert(any(KnowledgeEntryRevisionEntity.class));
    }

    @Test void missingVersionCannotWrite() {
        var r = request(); r.setExpectedVersion(null);
        assertThrows(RenException.class, () -> service.update("dataset", "document", "entry", r));
    }

    @Test void rejectedWorkspaceCannotReadOrWrite() {
        doThrow(new RenException("forbidden")).when(permissions).requireWrite(base);
        assertThrows(RenException.class, () -> service.create("dataset", "document", request()));
        verifyNoInteractions(chunks, revisions);
        doThrow(new RenException("forbidden")).when(permissions).requireRead(base);
        assertThrows(RenException.class, () -> service.list("dataset", "document", 1, 10, null));
    }

    @Test void dynamicSourceAndProcessingDocumentsRejectEdits() {
        doc.setKnowledgeSourceId("source");
        assertThrows(RenException.class, () -> service.update("dataset", "document", "entry", request()));
        doc.setKnowledgeSourceId(null); doc.setRun("RUNNING");
        assertThrows(RenException.class, () -> service.create("dataset", "document", request()));
        verify(chunks, never()).insert(any(KnowledgeChunkEntity.class));
    }

    @Test void foreignDocumentAndEntryRejectEdits() {
        when(docs.selectOne(any())).thenReturn(null);
        assertThrows(RenException.class, () -> service.update("dataset", "other", "entry", request()));
        when(docs.selectOne(any())).thenReturn(doc); when(chunks.selectOne(any())).thenReturn(null);
        assertThrows(RenException.class, () -> service.update("dataset", "document", "other", request()));
        verify(chunks, never()).update(isNull(), any());
    }

    @Test void qaDerivesCanonicalContent() {
        var r = request(); r.setContent("旧正文"); r.setQuestion("价格？"); r.setAnswer("28元");
        var result = service.update("dataset", "document", "entry", r);
        assertEquals("问题：价格？\n答案：28元", result.get("content"));
    }

    @Test void blankContentAndIncompleteQaRejected() {
        var r = request(); r.setContent("  ");
        assertThrows(RenException.class, () -> service.update("dataset", "document", "entry", r));
        r.setQuestion("价格？");
        assertThrows(RenException.class, () -> service.update("dataset", "document", "entry", r));
    }

    @Test void disableAndRestoreAreVersioned() {
        var r = request(); r.setEnabled(0);
        assertEquals(0, service.update("dataset", "document", "entry", r).get("enabled"));
        KnowledgeEntryRevisionEntity old = new KnowledgeEntryRevisionEntity(); old.setContent("售价25"); old.setEnabled(1);
        when(revisions.selectOne(any())).thenReturn(old);
        var restore = new KnowledgeEntryRequest.Restore(); restore.setRevisionId("revision"); restore.setExpectedVersion(1L);
        var restored = service.restore("dataset", "document", "entry", restore);
        assertEquals("售价25", restored.get("content")); assertEquals(1, restored.get("enabled")); assertEquals(2L, restored.get("editVersion"));
    }

    @Test void foreignRevisionCannotRestore() {
        var request = new KnowledgeEntryRequest.Restore(); request.setRevisionId("foreign"); request.setExpectedVersion(0L);
        assertThrows(RenException.class, () -> service.restore("dataset", "document", "entry", request));
        verify(revisions).selectOne(argThat(q -> q.getCustomSqlSegment().contains("dataset_id")
                && q.getCustomSqlSegment().contains("document_id") && q.getCustomSqlSegment().contains("entry_id")));
        verify(chunks, never()).update(isNull(), any());
    }

    @Test void manualCreatePreservesScopeAndInitialRevision() {
        when(chunks.selectList(any())).thenReturn(List.of());
        var result = service.create("dataset", "document", request());
        assertEquals(0L, result.get("editVersion"));
        verify(chunks).insert(argThat((KnowledgeChunkEntity e) -> "workspace".equals(e.getWorkspaceId()) && "document".equals(e.getDocumentId())));
        verify(revisions).insert(any(KnowledgeEntryRevisionEntity.class));
    }

    @Test void cleanupLocksDocumentsBeforeDeletingAllHistoryAndChunks() {
        var cleanup = new KnowledgeEntryCleanupService(docs, chunks, revisions, baseDao);
        cleanup.delete("dataset", List.of("document"));
        var ordered = inOrder(docs, revisions, chunks);
        ordered.verify(docs).selectList(argThat(q -> q.getCustomSqlSegment().contains("FOR UPDATE")));
        ordered.verify(revisions).delete(argThat(q -> q.getCustomSqlSegment().contains("document_id")));
        ordered.verify(chunks).delete(argThat(q -> q.getCustomSqlSegment().contains("dataset_id")));
    }
}
