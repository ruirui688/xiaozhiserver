package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.dao.AgentTagDao;
import xiaozhi.modules.agent.dto.AgentDTO;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.agent.service.AgentContextProviderService;
import xiaozhi.modules.agent.service.AgentPluginMappingService;
import xiaozhi.modules.agent.service.AgentTagService;
import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.correctword.service.CorrectWordFileService;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.ModelProviderService;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.workspace.entity.WorkspaceEntity;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;
import xiaozhi.modules.workspace.service.WorkspaceService;

class AgentWorkspaceIsolationTest {

    @Test
    void getUserAgentsFiltersByCurrentWorkspaceInsteadOfOnlyUserId() {
        AgentDao agentDao = mock(AgentDao.class);
        AgentTagDao agentTagDao = mock(AgentTagDao.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId("workspace-a");
        when(workspaceService.getCurrentWorkspaceEntity()).thenReturn(workspace);

        AgentEntity agent = new AgentEntity();
        agent.setId("agent-a");
        agent.setWorkspaceId("workspace-a");
        agent.setAgentName("A");
        when(agentDao.selectList(any(Wrapper.class))).thenReturn(List.of(agent));
        when(agentDao.getDeviceCountByAgentId("agent-a")).thenReturn(0);
        when(agentTagDao.selectByAgentId("agent-a")).thenReturn(List.of());

        AgentServiceImpl service = new AgentServiceImpl(
                agentDao,
                agentTagDao,
                mock(TimbreService.class),
                mock(ModelConfigService.class),
                mock(RedisUtils.class),
                mock(DeviceService.class),
                mock(AgentPluginMappingService.class),
                mock(AgentChatHistoryService.class),
                mock(AgentTemplateService.class),
                mock(ModelProviderService.class),
                mock(AgentContextProviderService.class),
                mock(AgentTagService.class),
                mock(CorrectWordFileService.class),
                workspaceService,
                mock(WorkspacePermissionService.class));
        ReflectionTestUtils.setField(service, "baseDao", agentDao);

        List<AgentDTO> result = service.getUserAgents(1001L, null, null);

        ArgumentCaptor<Wrapper<AgentEntity>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(agentDao).selectList(wrapperCaptor.capture());
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("workspace_id"));
        verify(workspaceService).getCurrentWorkspaceEntity();
        assertEquals(1, result.size());
        assertEquals("agent-a", result.get(0).getId());
    }
}
