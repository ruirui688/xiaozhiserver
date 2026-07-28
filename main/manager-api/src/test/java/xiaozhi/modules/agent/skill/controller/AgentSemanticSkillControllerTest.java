package xiaozhi.modules.agent.skill.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.skill.service.AgentSemanticSkillService;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

class AgentSemanticSkillControllerTest {

    @Test
    void legacyAgentWithoutWorkspaceUsesOwnerCheckWithoutWorkspaceLookup() {
        AgentSemanticSkillService skillService = mock(AgentSemanticSkillService.class);
        AgentService agentService = mock(AgentService.class);
        WorkspacePermissionService workspacePermissionService = mock(WorkspacePermissionService.class);
        AgentSemanticSkillController controller = new AgentSemanticSkillController(
                skillService, agentService, workspacePermissionService);

        AgentInfoVO legacyAgent = new AgentInfoVO();
        legacyAgent.setId("legacy-agent");
        legacyAgent.setWorkspaceId(null);
        when(agentService.checkAgentPermission("legacy-agent", 7L)).thenReturn(true);
        when(agentService.getAgentById("legacy-agent")).thenReturn(legacyAgent);
        when(skillService.list("legacy-agent")).thenReturn(List.of());

        try (MockedStatic<SecurityUser> securityUser = mockStatic(SecurityUser.class)) {
            securityUser.when(SecurityUser::getUserId).thenReturn(7L);
            controller.list("legacy-agent");
        }

        verify(agentService).checkAgentPermission("legacy-agent", 7L);
        verify(skillService).list("legacy-agent");
        verify(workspacePermissionService, never()).requireRead(null);
        verify(workspacePermissionService, never()).requireWrite(null);
    }
}
