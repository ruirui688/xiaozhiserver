package xiaozhi.modules.knowledge.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.mock.web.MockMultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.modules.knowledge.dao.DocumentDao;
import xiaozhi.modules.knowledge.dto.KnowledgeBaseDTO;
import xiaozhi.modules.knowledge.dto.KnowledgeFilesDTO;
import xiaozhi.modules.knowledge.dto.document.DocumentDTO;
import xiaozhi.modules.knowledge.rag.KnowledgeBaseAdapter;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.knowledge.service.KnowledgeFilesService;

class LocalKnowledgeUploadTaskServiceTest {
    private static final String DATASET_ID = "dataset-1";

    private final AsyncTaskExecutor taskExecutor = mock(AsyncTaskExecutor.class);
    private final KnowledgeFilesService knowledgeFilesService = mock(KnowledgeFilesService.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final DocumentDao documentDao = mock(DocumentDao.class);
    private final KnowledgeBaseAdapter adapter = mock(KnowledgeBaseAdapter.class);
    private final LocalKnowledgeUploadTaskService service = new LocalKnowledgeUploadTaskService(
            taskExecutor, knowledgeFilesService, knowledgeBaseService, documentDao, new ObjectMapper());

    @Test
    void returnsRunningDocumentBeforeExecutingLlmChunking() {
        AtomicReference<Runnable> submittedTask = new AtomicReference<>();
        when(taskExecutor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            submittedTask.set(invocation.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
        when(documentDao.selectCount(any())).thenReturn(1L);
        KnowledgeBaseDTO dataset = new KnowledgeBaseDTO();
        dataset.setWorkspaceId("workspace-1");
        when(knowledgeBaseService.getByDatasetId(DATASET_ID)).thenReturn(dataset);
        KnowledgeFilesDTO completed = completedDocument();
        when(adapter.uploadDocument(any())).thenReturn(completed);

        KnowledgeFilesDTO queued = service.submit(
                DATASET_ID,
                new MockMultipartFile("file", "manual.md", "text/markdown", "content".getBytes()),
                null, null, null, null, adapter);

        assertEquals("RUNNING", queued.getRun());
        assertEquals(0.02D, queued.getProgress());
        verify(adapter, never()).uploadDocument(any());

        submittedTask.get().run();

        ArgumentCaptor<DocumentDTO.UploadReq> request = ArgumentCaptor.forClass(DocumentDTO.UploadReq.class);
        verify(adapter).uploadDocument(request.capture());
        assertEquals(queued.getDocumentId(), request.getValue().getDocumentId());
        verify(knowledgeFilesService, times(2)).saveDocumentShadow(
                eq(DATASET_ID), any(), eq("manual.md"), eq(null), eq(null));
        verify(knowledgeBaseService).updateStatistics(DATASET_ID, 0, 2L, 20L);
    }

    @Test
    void rejectsMismatchedExtensionAndMimeBeforeCreatingTask() {
        assertThrows(RuntimeException.class, () -> service.submit(
                DATASET_ID,
                new MockMultipartFile("file", "manual.xlsx", "text/plain", "content".getBytes()),
                null, null, null, null, adapter));

        verify(taskExecutor, never()).submit(any(Runnable.class));
        verify(knowledgeFilesService, never()).saveDocumentShadow(any(), any(), any(), any(), any());
    }

    @Test
    void sourceCandidateStaysDisabledUntilPublicationCallback() {
        AtomicReference<Runnable> submittedTask = new AtomicReference<>();
        AtomicBoolean published = new AtomicBoolean();
        when(taskExecutor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            submittedTask.set(invocation.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
        when(documentDao.selectCount(any())).thenReturn(1L);
        when(adapter.uploadDocument(any())).thenReturn(completedDocument());

        LocalKnowledgeUploadTaskService.SourceUploadContext context =
                new LocalKnowledgeUploadTaskService.SourceUploadContext(
                        "source-1", "candidate-1", 2, "hash-1", "connector_web",
                        new LocalKnowledgeUploadTaskService.SourceUploadListener() {
                            @Override
                            public void onIndexed(KnowledgeFilesDTO document) {
                                assertEquals("candidate-1", document.getDocumentId());
                                assertEquals("source-1", document.getKnowledgeSourceId());
                                published.set(true);
                            }

                            @Override
                            public void onFailed(String error) {
                                throw new AssertionError(error);
                            }
                        });

        KnowledgeFilesDTO queued = service.submitSource(
                DATASET_ID,
                new MockMultipartFile("file", "source-v2.md", "text/markdown", "content".getBytes()),
                null, null, null, null, adapter, context);

        assertEquals("candidate-1", queued.getDocumentId());
        assertEquals("connector_web", queued.getSourceType());
        verify(knowledgeFilesService).saveDocumentShadow(
                eq(DATASET_ID), any(), eq("source-v2.md"), eq(null), eq(null), eq(false), eq(false));

        submittedTask.get().run();

        assertEquals(true, published.get());
        verify(knowledgeFilesService, times(2)).saveDocumentShadow(
                eq(DATASET_ID), any(), eq("source-v2.md"), eq(null), eq(null), eq(false), eq(false));
        verify(knowledgeBaseService, never()).updateStatistics(any(), any(), any(), any());
    }

    private KnowledgeFilesDTO completedDocument() {
        KnowledgeFilesDTO result = new KnowledgeFilesDTO();
        result.setDocumentId("ignored-by-test-adapter");
        result.setDatasetId(DATASET_ID);
        result.setName("manual.md");
        result.setRun("DONE");
        result.setProgress(1D);
        result.setStatus("1");
        result.setSourceType("local_knowledge");
        result.setChunkCount(2);
        result.setTokenCount(20L);
        return result;
    }
}
