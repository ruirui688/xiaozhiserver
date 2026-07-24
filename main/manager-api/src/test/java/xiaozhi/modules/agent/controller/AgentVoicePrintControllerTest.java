package xiaozhi.modules.agent.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.service.AgentVoicePrintService;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.agent.voiceprint.VoiceprintApiClient;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

class AgentVoicePrintControllerTest {
    private static final String VOICEPRINT_ID = "voice-1";
    private static final String AGENT_ID = "agent-1";
    private static final String WORKSPACE_ID = "workspace-1";

    private final AgentVoicePrintService voicePrintService = mock(AgentVoicePrintService.class);
    private final VoiceprintApiClient voiceprintApiClient = mock(VoiceprintApiClient.class);
    private final AgentService agentService = mock(AgentService.class);
    private final WorkspacePermissionService workspacePermissionService = mock(WorkspacePermissionService.class);

    private AgentVoicePrintController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentVoicePrintController(
                voicePrintService, voiceprintApiClient, agentService, workspacePermissionService);
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId(AGENT_ID);
        agent.setWorkspaceId(WORKSPACE_ID);
        when(agentService.getAgentById(AGENT_ID)).thenReturn(agent);
    }

    @Test
    void deleteRemovesExistingVoiceprintAfterWorkspaceWriteCheck() {
        when(voicePrintService.getAgentIdByVoicePrintId(VOICEPRINT_ID)).thenReturn(AGENT_ID);
        when(voicePrintService.delete(any(), org.mockito.ArgumentMatchers.eq(VOICEPRINT_ID))).thenReturn(true);

        Result<Void> result = controller.delete(VOICEPRINT_ID);

        assertEquals(0, result.getCode());
        verify(workspacePermissionService).requireWrite(WORKSPACE_ID);
        verify(voicePrintService).delete(any(), org.mockito.ArgumentMatchers.eq(VOICEPRINT_ID));
    }

    @Test
    void repeatedDeleteIsIdempotentWhenVoiceprintNoLongerExists() {
        when(voicePrintService.getAgentIdByVoicePrintId(VOICEPRINT_ID)).thenReturn(null);

        Result<Void> result = controller.delete(VOICEPRINT_ID);

        assertEquals(0, result.getCode());
        verify(workspacePermissionService, never()).requireWrite(any());
        verify(voicePrintService, never()).delete(any(), any());
    }

    @Test
    void workspaceWriteDenialPreventsVoiceprintDeletion() {
        when(voicePrintService.getAgentIdByVoicePrintId(VOICEPRINT_ID)).thenReturn(AGENT_ID);
        doThrow(new RenException("无工作空间写权限"))
                .when(workspacePermissionService).requireWrite(WORKSPACE_ID);

        assertThrows(RenException.class, () -> controller.delete(VOICEPRINT_ID));

        verify(voicePrintService, never()).delete(any(), any());
    }
}
