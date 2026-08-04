package xiaozhi.modules.agent.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xiaozhi.common.utils.Result;
import xiaozhi.modules.agent.entity.AgentTemplateEntity;
import xiaozhi.modules.agent.service.AgentTemplateService;

class AgentTemplateControllerTest {
    private final AgentTemplateService agentTemplateService = mock(AgentTemplateService.class);

    private AgentTemplateController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentTemplateController(agentTemplateService);
    }

    @Test
    void deleteRejectsRemovingLastSystemTemplate() {
        AgentTemplateEntity template = systemTemplate("system-1", 0);
        when(agentTemplateService.getById("system-1")).thenReturn(template);
        when(agentTemplateService.count(any())).thenReturn(1L);

        Result<String> result = controller.deleteAgentTemplate("system-1");

        assertEquals("至少保留一个系统模板，不能删除最后一个系统模板", result.getMsg());
        verify(agentTemplateService, never()).removeById(any());
    }

    @Test
    void deleteAllowsRemovingSystemTemplateWhenAnotherRemains() {
        AgentTemplateEntity template = systemTemplate("system-2", 1);
        when(agentTemplateService.getById("system-2")).thenReturn(template);
        when(agentTemplateService.count(any())).thenReturn(2L);
        when(agentTemplateService.removeById("system-2")).thenReturn(true);

        Result<String> result = controller.deleteAgentTemplate("system-2");

        assertEquals(0, result.getCode());
        verify(agentTemplateService).removeById("system-2");
        verify(agentTemplateService).reorderTemplatesAfterDelete(1);
    }

    @Test
    void batchDeleteRejectsRemovingAllSystemTemplates() {
        List<String> ids = List.of("system-1", "system-2");
        when(agentTemplateService.count(any())).thenReturn(0L, 2L, 2L);

        Result<String> result = controller.batchRemoveAgentTemplates(ids);

        assertEquals("至少保留一个系统模板，不能删除全部系统模板", result.getMsg());
        verify(agentTemplateService, never()).removeByIds(any());
    }

    private AgentTemplateEntity systemTemplate(String id, int sort) {
        AgentTemplateEntity template = new AgentTemplateEntity();
        template.setId(id);
        template.setSort(sort);
        template.setWorkspaceId(null);
        return template;
    }
}
