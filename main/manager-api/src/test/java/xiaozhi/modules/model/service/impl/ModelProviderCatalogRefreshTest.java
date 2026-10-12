package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;

import xiaozhi.common.user.UserDetail;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.dao.AgentPluginMappingMapper;
import xiaozhi.modules.agent.runtime.AgentRuntimeConfigRefreshEvent;
import xiaozhi.modules.model.dao.ModelProviderDao;
import xiaozhi.modules.model.dto.ModelProviderDTO;
import xiaozhi.modules.model.entity.ModelProviderEntity;
import xiaozhi.modules.security.user.SecurityUser;

class ModelProviderCatalogRefreshTest {
    @ParameterizedTest
    @ValueSource(strings = { "set_session_voice", "manage_memory" })
    void retiredCatalogCodesCannotBeAddedOrAssignedToExistingEntries(String code) {
        ModelProviderDao dao = mock(ModelProviderDao.class);
        AgentDao agents = mock(AgentDao.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        ModelProviderServiceImpl service = new ModelProviderServiceImpl(dao, agents, events, mock(AgentPluginMappingMapper.class));
        ModelProviderDTO request = new ModelProviderDTO();
        request.setId("activity-id");
        request.setModelType("Plugin");
        request.setProviderCode(code);
        request.setFields("[]");

        assertTrue(assertThrows(RenException.class, () -> service.add(request)).getMessage().contains("退役"));
        assertTrue(assertThrows(RenException.class, () -> service.edit(request)).getMessage().contains("退役"));
        verifyNoInteractions(dao, agents, events);
    }

    @ParameterizedTest
    @ValueSource(strings = { "Plugin", "LLM" })
    void retiredEntryCannotBypassReadOnlyStatusByChangingCodeOrModelType(String newType) {
        ModelProviderDao dao = mock(ModelProviderDao.class);
        AgentDao agents = mock(AgentDao.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        ModelProviderServiceImpl service = new ModelProviderServiceImpl(dao, agents, events, mock(AgentPluginMappingMapper.class));
        ModelProviderEntity retired = entity();
        retired.setProviderCode("set_session_voice");
        when(dao.selectById("activity-id")).thenReturn(retired);
        ModelProviderDTO request = new ModelProviderDTO();
        request.setId("activity-id");
        request.setModelType(newType);
        request.setProviderCode("renamed_active_tool");
        request.setFields("[]");

        RenException error = assertThrows(RenException.class, () -> service.edit(request));
        assertTrue(error.getMessage().contains("退役"));
        verify(dao, never()).updateById(any(ModelProviderEntity.class));
        verifyNoInteractions(agents, events);
    }

    @Test
    void rulesOnlyUpdateRefreshesOnlineAgentsWithoutChangingRoleMappings() {
        ModelProviderDao dao = mock(ModelProviderDao.class);
        AgentDao agents = mock(AgentDao.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        ModelProviderServiceImpl service = new ModelProviderServiceImpl(dao, agents, events, mock(AgentPluginMappingMapper.class));
        ModelProviderEntity existing = entity();
        existing.setFields("[{\"key\":\"instructions\",\"default\":\"old\"}]");
        when(dao.selectById("activity-id")).thenReturn(existing);
        when(dao.updateById(any(ModelProviderEntity.class))).thenReturn(1);
        when(agents.selectAllAgentIds()).thenReturn(List.of("agent-a"));
        ModelProviderDTO update = new ModelProviderDTO();
        update.setId("activity-id");
        update.setModelType("Plugin");
        update.setProviderCode("idiom_chain");
        update.setFields("[{\"key\":\"instructions\",\"default\":\"new\"}]");
        UserDetail user = new UserDetail();
        user.setId(7L);
        try (var security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user);
            service.edit(update);
        }
        var event = org.mockito.ArgumentCaptor.forClass(AgentRuntimeConfigRefreshEvent.class);
        verify(events).publishEvent(event.capture());
        assertEquals(List.of("agent-a"), event.getValue().agentIds());
        assertEquals("tool_catalog_updated", event.getValue().reason());
    }

    @Test
    void deletingCatalogEntryRefreshesOnlineAgents() {
        ModelProviderDao dao = mock(ModelProviderDao.class);
        AgentDao agents = mock(AgentDao.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(dao.selectById("activity-id")).thenReturn(entity());
        when(dao.deleteById("activity-id")).thenReturn(1);
        when(agents.selectAllAgentIds()).thenReturn(List.of("agent-a"));
        AgentPluginMappingMapper mappings = mock(AgentPluginMappingMapper.class);
        new ModelProviderServiceImpl(dao, agents, events, mappings).delete("activity-id");
        verify(mappings).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        var event = org.mockito.ArgumentCaptor.forClass(AgentRuntimeConfigRefreshEvent.class);
        verify(events).publishEvent(event.capture());
        assertEquals("tool_catalog_deleted", event.getValue().reason());
    }

    private ModelProviderEntity entity() {
        ModelProviderEntity entity = new ModelProviderEntity();
        entity.setId("activity-id");
        entity.setModelType("Plugin");
        entity.setProviderCode("idiom_chain");
        entity.setFields("[]");
        return entity;
    }
}
