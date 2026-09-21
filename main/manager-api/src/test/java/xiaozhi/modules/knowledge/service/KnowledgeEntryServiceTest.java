package xiaozhi.modules.knowledge.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xiaozhi.modules.knowledge.controller.KnowledgeEntryController;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.knowledge.dao.*;
import xiaozhi.modules.knowledge.dto.*;
import xiaozhi.modules.knowledge.entity.*;

class KnowledgeEntryServiceTest {
    KnowledgeBaseService bases = mock(KnowledgeBaseService.class);
    KnowledgeResourcePermissionService permissions = mock(KnowledgeResourcePermissionService.class);
    DocumentDao docs = mock(DocumentDao.class);
    KnowledgeChunkDao chunks = mock(KnowledgeChunkDao.class);
    KnowledgeBaseDao baseDao = mock(KnowledgeBaseDao.class);
    KnowledgeFilesService files = mock(KnowledgeFilesService.class);
    KnowledgeEntryService service = new KnowledgeEntryService(bases, permissions, docs, chunks, baseDao, files);
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
    }

    KnowledgeEntryRequest request() {
        KnowledgeEntryRequest r = new KnowledgeEntryRequest(); r.setContent("售价28"); r.setExpectedVersion(0L); return r;
    }

    @Test void updateCurrentContentAndMarkIndexDirtyWithoutInsertingSnapshots() {
        var result = service.update("dataset", "document", "entry", request());
        assertEquals("售价28", result.get("content")); assertEquals(1L, result.get("editVersion"));
        assertEquals("pending", result.get("indexStatus"));
        verify(chunks, never()).insert(any(KnowledgeChunkEntity.class));
        verify(docs).update(isNull(), argThat(w -> w.getSqlSet().contains("index_dirty")));
        verify(baseDao).updateStatsAfterChange(eq("dataset"), eq(0), eq(0L), anyLong());
    }

    @Test void staleVersionCannotWrite() {
        var r = request(); r.setExpectedVersion(5L);
        assertThrows(RenException.class, () -> service.update("dataset", "document", "entry", r));
        verify(chunks, never()).update(isNull(), any());
    }

    @Test void missingVersionCannotWrite() {
        var r = request(); r.setExpectedVersion(null);
        assertThrows(RenException.class, () -> service.update("dataset", "document", "entry", r));
    }

    @Test void rejectedWorkspaceCannotReadOrWrite() {
        doThrow(new RenException("forbidden")).when(permissions).requireWrite(base);
        assertThrows(RenException.class, () -> service.create("dataset", "document", request()));
        verifyNoInteractions(chunks);
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

    @Test void repeatedEditsUpdateSameRowAndRetainOptimisticConcurrency() {
        var r = request(); r.setEnabled(0);
        assertEquals(0, service.update("dataset", "document", "entry", r).get("enabled"));
        r.setEnabled(1); r.setContent("售价30"); r.setExpectedVersion(1L);
        var updated = service.update("dataset", "document", "entry", r);
        assertEquals("售价30", updated.get("content")); assertEquals(1, updated.get("enabled")); assertEquals(2L, updated.get("editVersion"));
        assertEquals("entry", updated.get("id"));
        verify(chunks, times(2)).update(isNull(), any());
        verify(chunks, never()).insert(any(KnowledgeChunkEntity.class));
        assertThrows(RenException.class, () -> service.update("dataset", "document", "entry", r));
    }

    @Test void historyEndpointsAndDependenciesAreAbsent() {
        for (Class<?> type : List.of(KnowledgeEntryController.class, KnowledgeEntryService.class, KnowledgeEntryCleanupService.class)) {
            assertTrue(Arrays.stream(type.getDeclaredMethods()).noneMatch(m ->
                    Set.of("history", "restore", "snapshot").contains(m.getName())));
            assertTrue(Arrays.stream(type.getDeclaredFields()).noneMatch(f -> f.getType().getSimpleName().contains("Revision")));
        }
        assertEquals(0, KnowledgeEntryRequest.class.getDeclaredClasses().length);
    }

    @Test void manualCreatePreservesScopeAndInitialConcurrencyVersion() {
        when(chunks.selectList(any())).thenReturn(List.of());
        var result = service.create("dataset", "document", request());
        assertEquals(0L, result.get("editVersion"));
        verify(chunks).insert(argThat((KnowledgeChunkEntity e) -> "workspace".equals(e.getWorkspaceId()) && "document".equals(e.getDocumentId())));
    }

    @Test void cleanupLocksDocumentsBeforeDeletingChunks() {
        var cleanup = new KnowledgeEntryCleanupService(docs, chunks, baseDao);
        cleanup.delete("dataset", List.of("document"));
        var ordered = inOrder(baseDao, docs, chunks);
        ordered.verify(baseDao).selectOne(argThat(q -> q.getCustomSqlSegment().contains("FOR UPDATE")));
        ordered.verify(docs).selectList(argThat(q -> q.getCustomSqlSegment().contains("FOR UPDATE")));
        ordered.verify(chunks).delete(argThat(q -> q.getCustomSqlSegment().contains("dataset_id")));
    }
}
