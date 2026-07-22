package xiaozhi.modules.knowledge.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.knowledge.dao.DocumentDao;
import xiaozhi.modules.knowledge.dto.KnowledgeBaseDTO;
import xiaozhi.modules.knowledge.dto.KnowledgeFilesDTO;
import xiaozhi.modules.knowledge.dto.document.DocumentDTO;
import xiaozhi.modules.knowledge.entity.DocumentEntity;
import xiaozhi.modules.knowledge.rag.KnowledgeBaseAdapter;
import xiaozhi.modules.knowledge.rag.KnowledgeBaseAdapterFactory;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.knowledge.service.KnowledgeFilesService;

class KnowledgeFilesServiceImplTest {
    private static final String DATASET_ID = "dataset-1";
    private static final String DOCUMENT_ID = "document-1";
    private static final Map<String, Object> RAG_CONFIG = Map.of("type", "local_knowledge");

    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final DocumentDao documentDao = mock(DocumentDao.class);
    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private KnowledgeFilesServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeFilesServiceImpl(knowledgeBaseService, documentDao, objectMapper, redisUtils);
        ReflectionTestUtils.setField(service, "maxUploadSize", 1024L * 1024L);
        ReflectionTestUtils.setField(service, "self", service);
        when(knowledgeBaseService.getRAGConfigByDatasetId(DATASET_ID)).thenReturn(RAG_CONFIG);
    }

    @Test
    void parseRejectsDocumentThatDoesNotBelongToPathDataset() {
        when(documentDao.selectList(any())).thenReturn(List.of());

        assertThrows(RuntimeException.class,
                () -> service.parseDocuments(DATASET_ID, List.of("document-from-another-dataset")));

        verify(knowledgeBaseService, never()).getRAGConfigByDatasetId(any());
    }

    @Test
    void parseDeduplicatesDocumentIdsBeforeCallingAdapter() {
        when(documentDao.selectList(any())).thenReturn(List.of(document(DOCUMENT_ID)));
        KnowledgeBaseAdapter adapter = mock(KnowledgeBaseAdapter.class);
        when(adapter.parseDocuments(DATASET_ID, List.of(DOCUMENT_ID))).thenReturn(true);

        try (MockedStatic<KnowledgeBaseAdapterFactory> factory = mockStatic(KnowledgeBaseAdapterFactory.class)) {
            factory.when(() -> KnowledgeBaseAdapterFactory.isAdapterTypeRegistered("local_knowledge"))
                    .thenReturn(true);
            factory.when(() -> KnowledgeBaseAdapterFactory.getAdapter("local_knowledge", RAG_CONFIG))
                    .thenReturn(adapter);

            assertTrue(service.parseDocuments(DATASET_ID, List.of(DOCUMENT_ID, DOCUMENT_ID)));
        }

        verify(adapter).parseDocuments(DATASET_ID, List.of(DOCUMENT_ID));
    }

    @Test
    void deleteDeduplicatesIdsAndKeepsDatasetOwnershipCheck() {
        when(documentDao.selectList(any())).thenReturn(List.of(document(DOCUMENT_ID)));
        KnowledgeBaseAdapter adapter = mock(KnowledgeBaseAdapter.class);
        KnowledgeFilesService self = mock(KnowledgeFilesService.class);
        ReflectionTestUtils.setField(service, "self", self);
        DocumentDTO.BatchIdReq request = DocumentDTO.BatchIdReq.builder()
                .ids(List.of(DOCUMENT_ID, DOCUMENT_ID))
                .build();

        try (MockedStatic<KnowledgeBaseAdapterFactory> factory = mockStatic(KnowledgeBaseAdapterFactory.class)) {
            factory.when(() -> KnowledgeBaseAdapterFactory.isAdapterTypeRegistered("local_knowledge"))
                    .thenReturn(true);
            factory.when(() -> KnowledgeBaseAdapterFactory.getAdapter("local_knowledge", RAG_CONFIG))
                    .thenReturn(adapter);

            service.deleteDocuments(DATASET_ID, request);
        }

        assertEquals(List.of(DOCUMENT_ID), request.getIds());
        verify(adapter).deleteDocument(DATASET_ID, request);
        verify(self).deleteDocumentShadows(List.of(DOCUMENT_ID), DATASET_ID, 0L, 0L);
    }

    @Test
    void uploadCompensatesAdapterWhenShadowPersistenceFails() {
        KnowledgeBaseAdapter adapter = mock(KnowledgeBaseAdapter.class);
        KnowledgeFilesService self = mock(KnowledgeFilesService.class);
        ReflectionTestUtils.setField(service, "self", self);
        KnowledgeFilesDTO uploaded = uploadedDocument(DOCUMENT_ID, "manual.md");
        when(adapter.uploadDocument(any())).thenReturn(uploaded);
        when(self.saveDocumentShadow(eq(DATASET_ID), eq(uploaded), eq("manual.md"), isNull(), isNull()))
                .thenThrow(new IllegalStateException("database unavailable"));

        try (MockedStatic<KnowledgeBaseAdapterFactory> factory = mockStatic(KnowledgeBaseAdapterFactory.class)) {
            factory.when(() -> KnowledgeBaseAdapterFactory.isAdapterTypeRegistered("local_knowledge"))
                    .thenReturn(true);
            factory.when(() -> KnowledgeBaseAdapterFactory.getAdapter("local_knowledge", RAG_CONFIG))
                    .thenReturn(adapter);

            assertThrows(RuntimeException.class, () -> service.uploadDocument(
                    DATASET_ID,
                    new MockMultipartFile("file", "manual.md", "text/markdown", "content".getBytes()),
                    null, null, null, null));
        }

        ArgumentCaptor<DocumentDTO.BatchIdReq> cleanup = ArgumentCaptor.forClass(DocumentDTO.BatchIdReq.class);
        verify(adapter).deleteDocument(eq(DATASET_ID), cleanup.capture());
        assertEquals(List.of(DOCUMENT_ID), cleanup.getValue().getIds());
    }

    @Test
    void sameNameUploadsRemainSeparateAndPreserveNestedParserConfig() {
        KnowledgeBaseDTO dataset = new KnowledgeBaseDTO();
        dataset.setWorkspaceId("workspace-1");
        when(knowledgeBaseService.getByDatasetId(DATASET_ID)).thenReturn(dataset);
        when(documentDao.selectOne(any())).thenReturn(null);
        Map<String, Object> parserConfig = Map.of(
                "llm_model_id", "llm-1",
                "retrieval_config", Map.of("semantic_top_k", 12));

        service.saveDocumentShadow(DATASET_ID, uploadedDocument("document-a", "manual.md"),
                "manual.md", "naive", parserConfig);
        service.saveDocumentShadow(DATASET_ID, uploadedDocument("document-b", "manual.md"),
                "manual.md", "naive", parserConfig);

        ArgumentCaptor<DocumentEntity> captor = ArgumentCaptor.forClass(DocumentEntity.class);
        verify(documentDao, times(2)).insert(captor.capture());
        assertEquals(List.of("document-a", "document-b"),
                captor.getAllValues().stream().map(DocumentEntity::getDocumentId).toList());
        assertTrue(captor.getAllValues().stream().allMatch(entity -> "manual.md".equals(entity.getName())));
        assertTrue(captor.getAllValues().stream()
                .allMatch(entity -> entity.getParserConfig().contains("semantic_top_k")));
    }

    @Test
    void documentListRunsFullReconciliationOnlyOnceDuringRedisCooldown() {
        KnowledgeFilesService self = mock(KnowledgeFilesService.class);
        ReflectionTestUtils.setField(service, "self", self);
        when(redisUtils.getKeyOrCreate(
                "knowledge:documents:full-sync:" + DATASET_ID, "1", 60L))
                .thenReturn(null, "1");
        when(documentDao.selectPage(any(Page.class), any(Wrapper.class)))
                .thenReturn(new Page<DocumentEntity>(1, 10));
        KnowledgeFilesDTO query = new KnowledgeFilesDTO();
        query.setDatasetId(DATASET_ID);

        service.getPageList(query, 1, 10);
        service.getPageList(query, 1, 10);

        verify(self, times(1)).syncDocumentsFromRAG(DATASET_ID);
        verify(documentDao, times(2)).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    void documentListSkipsFullReconciliationWhenRedisIsUnavailable() {
        KnowledgeFilesService self = mock(KnowledgeFilesService.class);
        ReflectionTestUtils.setField(service, "self", self);
        when(redisUtils.getKeyOrCreate(
                "knowledge:documents:full-sync:" + DATASET_ID, "1", 60L))
                .thenThrow(new IllegalStateException("redis unavailable"));
        when(documentDao.selectPage(any(Page.class), any(Wrapper.class)))
                .thenReturn(new Page<DocumentEntity>(1, 10));
        KnowledgeFilesDTO query = new KnowledgeFilesDTO();
        query.setDatasetId(DATASET_ID);

        assertEquals(0, service.getPageList(query, 1, 10).getTotal());

        verify(self, never()).syncDocumentsFromRAG(DATASET_ID);
        verify(documentDao).selectPage(any(Page.class), any(Wrapper.class));
    }

    private DocumentEntity document(String documentId) {
        DocumentEntity entity = new DocumentEntity();
        entity.setDatasetId(DATASET_ID);
        entity.setDocumentId(documentId);
        entity.setRun("DONE");
        return entity;
    }

    private KnowledgeFilesDTO uploadedDocument(String documentId, String name) {
        KnowledgeFilesDTO dto = new KnowledgeFilesDTO();
        dto.setDocumentId(documentId);
        dto.setDatasetId(DATASET_ID);
        dto.setName(name);
        dto.setSourceType("local_knowledge");
        dto.setRun("DONE");
        dto.setStatus("1");
        dto.setProgress(1.0);
        dto.setChunkCount(1);
        dto.setTokenCount(10L);
        return dto;
    }
}
