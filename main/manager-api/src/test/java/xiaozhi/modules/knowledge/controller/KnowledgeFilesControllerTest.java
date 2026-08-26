package xiaozhi.modules.knowledge.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import xiaozhi.modules.knowledge.dto.KnowledgeBaseDTO;
import xiaozhi.modules.knowledge.dto.document.RetrievalDTO;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.knowledge.service.KnowledgeFilesService;
import xiaozhi.modules.knowledge.service.KnowledgeResourcePermissionService;

class KnowledgeFilesControllerTest {
    private static final String DATASET_ID = "dataset-allowed";
    private static final String WORKSPACE_ID = "workspace-1";

    private final KnowledgeFilesService knowledgeFilesService = mock(KnowledgeFilesService.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final KnowledgeResourcePermissionService permissionService = mock(KnowledgeResourcePermissionService.class);

    private KnowledgeFilesController controller;

    @BeforeEach
    void setUp() {
        controller = new KnowledgeFilesController(
                knowledgeFilesService, knowledgeBaseService, permissionService);
        KnowledgeBaseDTO dataset = new KnowledgeBaseDTO();
        dataset.setDatasetId(DATASET_ID);
        dataset.setWorkspaceId(WORKSPACE_ID);
        when(knowledgeBaseService.getByDatasetId(DATASET_ID)).thenReturn(dataset);
        when(knowledgeFilesService.retrievalTest(any())).thenReturn(new RetrievalDTO.ResultVO());
    }

    @Test
    void retrievalTestUsesAuthorizedPathDatasetInsteadOfBodyDatasetIds() {
        RetrievalDTO.TestReq request = new RetrievalDTO.TestReq();
        request.setDatasetIds(List.of("dataset-not-authorized"));
        request.setQuestion("产品保修多久");

        controller.retrievalTest(DATASET_ID, request);

        verify(permissionService).requireRead(knowledgeBaseService.getByDatasetId(DATASET_ID));
        ArgumentCaptor<RetrievalDTO.TestReq> captor = ArgumentCaptor.forClass(RetrievalDTO.TestReq.class);
        verify(knowledgeFilesService).retrievalTest(captor.capture());
        assertEquals(List.of(DATASET_ID), captor.getValue().getDatasetIds());
    }
}
