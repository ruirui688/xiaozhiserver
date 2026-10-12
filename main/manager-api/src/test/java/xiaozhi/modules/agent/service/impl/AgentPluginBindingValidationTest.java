package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import xiaozhi.common.exception.RenException;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.dao.AgentTagDao;
import xiaozhi.modules.agent.dao.AgentVoicePrintDao;
import xiaozhi.modules.agent.dto.AgentUpdateDTO;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.entity.AgentPluginMapping;
import xiaozhi.modules.agent.service.*;
import xiaozhi.modules.agent.skill.service.AgentSemanticSkillService;
import xiaozhi.modules.agent.support.ManagedAsrHotwordGuard;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.correctword.service.CorrectWordFileService;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.knowledge.entity.KnowledgeBaseEntity;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.model.dto.ModelProviderDTO;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.ModelProviderService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;
import xiaozhi.modules.workspace.service.WorkspaceService;

class AgentPluginBindingValidationTest {
    private AgentDao agents;
    private AgentPluginMappingService mappings;
    private ModelProviderService providers;
    private KnowledgeBaseService knowledge;
    private WorkspacePermissionService permissions;
    private AgentServiceImpl service;

    @BeforeEach
    void setUp() {
        agents = mock(AgentDao.class);
        mappings = mock(AgentPluginMappingService.class);
        providers = mock(ModelProviderService.class);
        knowledge = mock(KnowledgeBaseService.class);
        permissions = mock(WorkspacePermissionService.class);
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-a");
        agent.setWorkspaceId("workspace-a");
        when(agents.selectAgentInfoById("agent-a")).thenReturn(agent);
        service = new AgentServiceImpl(
                agents, mock(AgentTagDao.class), mock(AgentVoicePrintDao.class),
                mock(TimbreService.class), mock(ModelConfigService.class), mock(RedisUtils.class),
                mock(DeviceService.class), mappings, mock(AgentChatHistoryService.class),
                mock(AgentTemplateService.class), providers, mock(AgentContextProviderService.class),
                mock(AgentTagService.class), mock(CorrectWordFileService.class), mock(WorkspaceService.class),
                permissions, knowledge, mock(WorkspaceAgentQuotaService.class),
                mock(AgentSemanticSkillService.class), mock(ApplicationEventPublisher.class),
                mock(ManagedAsrHotwordGuard.class));
        ReflectionTestUtils.setField(service, "baseDao", agents);
    }

    @Test
    void staleRolePageCannotRestoreDeletedCatalogMapping() {
        AgentUpdateDTO update = update("deleted-plugin", Map.of("instructions", "stale role rule"));
        RenException error = assertThrows(RenException.class, () -> service.updateAgentById("agent-a", update));
        assertTrue(error.getMessage().contains("不存在或已删除"));
        assertRejectedBeforePersistence();
    }

    @ParameterizedTest
    @ValueSource(strings = { "LLM", "Memory", "TTS" })
    void modelProviderCannotBeBoundAsFunctionPlugin(String modelType) {
        ModelProviderDTO provider = provider(modelType, "openai");
        when(providers.getById("provider-id")).thenReturn(provider);
        RenException error = assertThrows(RenException.class,
                () -> service.updateAgentById("agent-a", update("provider-id", Map.of())));
        assertTrue(error.getMessage().contains("不是功能插件"));
        assertRejectedBeforePersistence();
    }

    @ParameterizedTest
    @ValueSource(strings = { "set_session_voice", "manage_memory" })
    void retiredPluginCannotBeRestoredByDirectRoleRequest(String code) {
        when(providers.getById("provider-id")).thenReturn(provider("Plugin", code));
        RenException error = assertThrows(RenException.class,
                () -> service.updateAgentById("agent-a", update("provider-id", Map.of())));
        assertTrue(error.getMessage().contains("退役"));
        assertRejectedBeforePersistence();
    }

    @Test
    void activePluginKeepsInheritedRulesAbsentWhenSaved() {
        ModelProviderDTO provider = provider("Plugin", "riddle");
        provider.setFields("[{\"key\":\"custom_plugin\",\"default\":true},"
                + "{\"key\":\"instructions\",\"default\":\"catalog rule\"}]");
        when(providers.getById("provider-id")).thenReturn(provider);
        AgentPluginMapping saved = saveValid(update("provider-id", Map.of()));
        assertEquals("provider-id", saved.getPluginId());
        assertEquals("{}", saved.getParamInfo());
    }

    @ParameterizedTest
    @ValueSource(strings = { "workspace-a", "official-workspace" })
    void existingOwnedOrOfficialKnowledgeBaseRemainsBindable(String workspace) {
        KnowledgeBaseEntity dataset = new KnowledgeBaseEntity();
        dataset.setId("knowledge-id");
        dataset.setWorkspaceId(workspace);
        if (!"workspace-a".equals(workspace)) dataset.setScope("OFFICIAL");
        when(knowledge.selectById("knowledge-id")).thenReturn(dataset);
        AgentPluginMapping saved = saveValid(update("knowledge-id", Map.of("queryPolicy", "current question")));
        assertEquals("knowledge-id", saved.getPluginId());
        assertTrue(saved.getParamInfo().contains("current question"));
    }

    private void assertRejectedBeforePersistence() {
        verify(permissions).requireWrite("workspace-a");
        verifyNoInteractions(mappings);
        verify(agents, never()).updateById(any(AgentEntity.class));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private AgentPluginMapping saveValid(AgentUpdateDTO update) {
        when(agents.updateById(any(AgentEntity.class))).thenReturn(1);
        UserDetail user = new UserDetail();
        user.setId(7L);
        try (var security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user);
            service.updateAgentById("agent-a", update);
        }
        ArgumentCaptor<Collection<AgentPluginMapping>> captured = ArgumentCaptor.forClass(Collection.class);
        verify(mappings).saveBatch(captured.capture());
        assertEquals(1, captured.getValue().size());
        verify(permissions).requireWrite("workspace-a");
        return captured.getValue().iterator().next();
    }

    private ModelProviderDTO provider(String type, String code) {
        ModelProviderDTO provider = new ModelProviderDTO();
        provider.setId("provider-id");
        provider.setModelType(type);
        provider.setProviderCode(code);
        provider.setFields("[]");
        return provider;
    }

    private AgentUpdateDTO update(String pluginId, Map<String, Object> params) {
        AgentUpdateDTO.FunctionInfo function = new AgentUpdateDTO.FunctionInfo();
        function.setPluginId(pluginId);
        function.setParamInfo(new HashMap<>(params));
        AgentUpdateDTO update = new AgentUpdateDTO();
        update.setFunctions(List.of(function));
        return update;
    }
}
