package xiaozhi.modules.maintenance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.knowledge.dto.KnowledgeBaseDTO;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

class MaintenanceResourceAccessServiceTest {
    @Test
    void acceptsLowercaseTypeFromRestPath() {
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        KnowledgeBaseDTO dataset = new KnowledgeBaseDTO();
        dataset.setWorkspaceId("workspace-1");
        when(knowledgeBaseService.getByDatasetId("dataset-1")).thenReturn(dataset);
        MaintenanceResourceAccessService service = new MaintenanceResourceAccessService(
                knowledgeBaseService, mock(AgentService.class), mock(WorkspacePermissionService.class));

        assertEquals("workspace-1", service.resolveWorkspace("knowledge", "dataset-1"));
    }
}
