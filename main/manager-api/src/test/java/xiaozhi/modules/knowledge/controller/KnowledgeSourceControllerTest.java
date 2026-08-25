package xiaozhi.modules.knowledge.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xiaozhi.modules.knowledge.dto.KnowledgeBaseDTO;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.knowledge.source.dto.KnowledgeSourceRequest;
import xiaozhi.modules.knowledge.source.service.KnowledgeSourceService;
import xiaozhi.modules.knowledge.source.service.KnowledgeSourceSyncService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

class KnowledgeSourceControllerTest {
    private final KnowledgeSourceService sourceService = mock(KnowledgeSourceService.class);
    private final KnowledgeSourceSyncService sourceSyncService = mock(KnowledgeSourceSyncService.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final WorkspacePermissionService permissionService = mock(WorkspacePermissionService.class);
    private KnowledgeSourceController controller;

    @BeforeEach
    void setUp() {
        controller = new KnowledgeSourceController(
                sourceService, sourceSyncService, knowledgeBaseService, permissionService);
        KnowledgeBaseDTO dataset = new KnowledgeBaseDTO();
        dataset.setDatasetId("dataset-1");
        dataset.setWorkspaceId("workspace-1");
        when(knowledgeBaseService.getByDatasetId("dataset-1")).thenReturn(dataset);
    }

    @Test
    void readEndpointsRequireDatasetWorkspaceReadPermission() {
        controller.list("dataset-1");
        verify(permissionService).requireRead("workspace-1");
        verify(sourceService).list("dataset-1");
    }

    @Test
    void writeEndpointsRequireDatasetWorkspaceWritePermission() {
        KnowledgeSourceRequest request = new KnowledgeSourceRequest();
        request.setName("patch notes");
        request.setSourceType("WEB_PAGE");
        request.setSourceUri("https://example.com/patch-notes");
        controller.create("dataset-1", request);
        verify(permissionService).requireWrite("workspace-1");
        verify(sourceService).create("dataset-1", request);
    }
}
