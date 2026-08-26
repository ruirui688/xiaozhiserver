package xiaozhi.modules.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.knowledge.dto.KnowledgeBaseDTO;
import xiaozhi.modules.workspace.service.OfficialResourcePermissionService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

class KnowledgeResourcePermissionServiceTest {
    private final WorkspacePermissionService workspacePermission = mock(WorkspacePermissionService.class);
    private final OfficialResourcePermissionService officialPermission = mock(OfficialResourcePermissionService.class);
    private final KnowledgeResourcePermissionService service =
            new KnowledgeResourcePermissionService(workspacePermission, officialPermission);

    @Test
    void officialKnowledgeIsGloballyReadableButUsesOfficialWriteGate() {
        KnowledgeBaseDTO dataset = dataset("OFFICIAL");

        service.requireRead(dataset);
        service.requireWrite(dataset);

        verify(workspacePermission, never()).requireRead("workspace-1");
        verify(workspacePermission, never()).requireWrite("workspace-1");
        verify(officialPermission).requireOfficialMaintainer();
    }

    @Test
    void workspaceKnowledgeCannotEnableAutonomousMaintenance() {
        assertThrows(RenException.class, () -> service.requireAutomatedMaintenance(dataset("WORKSPACE")));
        verify(officialPermission, never()).requireOfficialMaintainer();
    }

    private KnowledgeBaseDTO dataset(String scope) {
        KnowledgeBaseDTO dataset = new KnowledgeBaseDTO();
        dataset.setDatasetId("dataset-1");
        dataset.setWorkspaceId("workspace-1");
        dataset.setScope(scope);
        return dataset;
    }
}
