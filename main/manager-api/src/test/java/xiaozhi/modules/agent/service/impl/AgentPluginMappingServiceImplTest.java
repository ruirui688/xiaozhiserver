package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.agent.dao.AgentPluginMappingMapper;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.model.dto.ModelProviderDTO;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.ModelProviderService;

class AgentPluginMappingServiceImplTest {

    @Test
    void buildsTrimmedToolPromptOverridesAndSkipsInvalidCatalogRows() {
        ModelProviderService providerService = mock(ModelProviderService.class);
        when(providerService.getPluginList()).thenReturn(List.of(
                provider("reset_conversation_context",
                        "[{\"key\":\"description\",\"default\":\"  仅在用户明确要求忘记时调用。  \"}]"),
                provider("blank_prompt",
                        "[{\"key\":\"description\",\"default\":\"   \"}]"),
                provider("broken_fields", "not-json")));
        AgentPluginMappingServiceImpl service = new AgentPluginMappingServiceImpl(
                mock(AgentPluginMappingMapper.class),
                mock(KnowledgeBaseService.class),
                mock(ModelConfigService.class),
                providerService);

        Map<String, String> overrides = service.getToolPromptOverrides();

        assertEquals(Map.of(
                "reset_conversation_context", "仅在用户明确要求忘记时调用。"), overrides);
    }

    private ModelProviderDTO provider(String providerCode, String fields) {
        ModelProviderDTO provider = new ModelProviderDTO();
        provider.setProviderCode(providerCode);
        provider.setFields(fields);
        return provider;
    }
}
