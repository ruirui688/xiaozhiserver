package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.dao.AgentTagDao;
import xiaozhi.modules.agent.dto.AgentDTO;
import xiaozhi.modules.agent.dto.AgentUpdateDTO;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.entity.AgentPluginMapping;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.agent.service.AgentContextProviderService;
import xiaozhi.modules.agent.service.AgentPluginMappingService;
import xiaozhi.modules.agent.service.AgentTagService;
import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.correctword.service.CorrectWordFileService;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.knowledge.entity.KnowledgeBaseEntity;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
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
        WorkspaceAgentQuotaService quotaService = mock(WorkspaceAgentQuotaService.class);
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
        when(quotaService.getStorageUsageByAgentIds(List.of("agent-a"))).thenReturn(Map.of("agent-a", 1024L));
        when(quotaService.getAgentStorageLimitBytes()).thenReturn(2048L);

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
                mock(WorkspacePermissionService.class),
                mock(KnowledgeBaseService.class),
                quotaService);
        ReflectionTestUtils.setField(service, "baseDao", agentDao);

        List<AgentDTO> result = service.getUserAgents(1001L, null, null);

        ArgumentCaptor<Wrapper<AgentEntity>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(agentDao).selectList(wrapperCaptor.capture());
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("workspace_id"));
        verify(workspaceService).getCurrentWorkspaceEntity();
        assertEquals(1, result.size());
        assertEquals("agent-a", result.get(0).getId());
        assertEquals(1024L, result.get(0).getStorageBytes());
        assertEquals(2048L, result.get(0).getStorageLimitBytes());
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void getAgentByIdSanitizesMapBackedFunctionsWithoutClassCast() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("pluginId", "SYSTEM_PLUGIN_WEATHER");
        function.put(
                "paramInfo",
                "{\"api_host\":\"devapi.qweather.com\",\"api_key\":\"secret\",\"default_location\":\"杭州\"}");

        AgentInfoVO result = getAgentByIdWithFunctions((List) List.of(function));

        Object item = ((List<?>) result.getFunctions()).get(0);
        assertTrue(item instanceof Map);
        assertSanitizedParamInfo(String.valueOf(((Map<?, ?>) item).get("paramInfo")));
    }

    @Test
    void getAgentByIdSanitizesEntityBackedFunctions() {
        AgentPluginMapping function = new AgentPluginMapping();
        function.setPluginId("SYSTEM_PLUGIN_WEATHER");
        function.setParamInfo("{\"api_host\":\"devapi.qweather.com\",\"api_key\":\"secret\",\"default_location\":\"杭州\"}");

        AgentInfoVO result = getAgentByIdWithFunctions(List.of(function));

        AgentPluginMapping item = result.getFunctions().get(0);
        assertSanitizedParamInfo(item.getParamInfo());
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void getAgentByIdDropsInvalidFunctionParamInfoInsteadOfThrowing() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("pluginId", "SYSTEM_PLUGIN_WEATHER");
        function.put("paramInfo", "not-json");

        AgentInfoVO result = getAgentByIdWithFunctions((List) List.of(function));

        assertEquals("{}", ((Map<?, ?>) ((List<?>) result.getFunctions()).get(0)).get("paramInfo"));
    }

    @Test
    void updateRejectsKnowledgeBaseFromAnotherWorkspaceBeforeChangingMappings() {
        AgentDao agentDao = mock(AgentDao.class);
        AgentPluginMappingService mappingService = mock(AgentPluginMappingService.class);
        WorkspacePermissionService permissionService = mock(WorkspacePermissionService.class);
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);

        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-a");
        agent.setWorkspaceId("workspace-a");
        when(agentDao.selectAgentInfoById("agent-a")).thenReturn(agent);

        KnowledgeBaseEntity foreignKnowledgeBase = new KnowledgeBaseEntity();
        foreignKnowledgeBase.setId("knowledge-b");
        foreignKnowledgeBase.setWorkspaceId("workspace-b");
        when(knowledgeBaseService.selectById("knowledge-b")).thenReturn(foreignKnowledgeBase);

        AgentServiceImpl service = new AgentServiceImpl(
                agentDao,
                mock(AgentTagDao.class),
                mock(TimbreService.class),
                mock(ModelConfigService.class),
                mock(RedisUtils.class),
                mock(DeviceService.class),
                mappingService,
                mock(AgentChatHistoryService.class),
                mock(AgentTemplateService.class),
                mock(ModelProviderService.class),
                mock(AgentContextProviderService.class),
                mock(AgentTagService.class),
                mock(CorrectWordFileService.class),
                mock(WorkspaceService.class),
                permissionService,
                knowledgeBaseService,
                mock(WorkspaceAgentQuotaService.class));
        ReflectionTestUtils.setField(service, "baseDao", agentDao);

        AgentUpdateDTO.FunctionInfo function = new AgentUpdateDTO.FunctionInfo();
        function.setPluginId("knowledge-b");
        AgentUpdateDTO update = new AgentUpdateDTO();
        update.setFunctions(List.of(function));

        assertThrows(RuntimeException.class, () -> service.updateAgentById("agent-a", update));

        verify(permissionService).requireWrite("workspace-a");
        verify(mappingService, never()).saveBatch(any());
        verify(mappingService, never()).updateBatchById(any());
        verify(mappingService, never()).removeBatchByIds(any());
    }

    private AgentInfoVO getAgentByIdWithFunctions(List<AgentPluginMapping> functions) {
        AgentDao agentDao = mock(AgentDao.class);
        AgentTagDao agentTagDao = mock(AgentTagDao.class);
        CorrectWordFileService correctWordFileService = mock(CorrectWordFileService.class);

        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-a");
        agent.setFunctions(functions);
        when(agentDao.selectAgentInfoById("agent-a")).thenReturn(agent);
        when(correctWordFileService.getAgentCorrectWordFileIds("agent-a")).thenReturn(List.of());

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
                correctWordFileService,
                mock(WorkspaceService.class),
                mock(WorkspacePermissionService.class),
                mock(KnowledgeBaseService.class),
                mock(WorkspaceAgentQuotaService.class));
        ReflectionTestUtils.setField(service, "baseDao", agentDao);

        return service.getAgentById("agent-a");
    }

    private void assertSanitizedParamInfo(String paramInfo) {
        assertFalse(paramInfo.contains("api_host"));
        assertFalse(paramInfo.contains("api_key"));
        assertFalse(paramInfo.contains("default_location"));
    }
}
