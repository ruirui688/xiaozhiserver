package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.agent.dao.AgentPluginMappingMapper;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.model.dto.ModelProviderDTO;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.ModelProviderService;
import xiaozhi.modules.agent.entity.AgentPluginMapping;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.knowledge.entity.KnowledgeBaseEntity;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import cn.hutool.json.JSONObject;

class AgentPluginMappingServiceImplTest {

    @Test
    void buildsTrimmedToolPromptOverridesAndSkipsInvalidCatalogRows() {
        ModelProviderService providerService = mock(ModelProviderService.class);
        when(providerService.getPluginList()).thenReturn(List.of(
                provider("reset_conversation_context",
                        "[{\"key\":\"description\",\"default\":\"  仅在用户明确要求忘记时调用。  \"}]"),
                provider("blank_prompt",
                        "[{\"key\":\"description\",\"default\":\"   \"}]"),
                provider("set_session_voice",
                        "[{\"key\":\"description\",\"default\":\"retired prompt\"}]"),
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

    @Test
    void runtimeInheritsCurrentCatalogRulesAndRemovesRetiredMappings() {
        ModelProviderService providerService = mock(ModelProviderService.class);
        AgentPluginMappingMapper mapper = mock(AgentPluginMappingMapper.class);
        AgentPluginMapping activity = new AgentPluginMapping();
        activity.setPluginId("activity-id");
        activity.setProviderCode("idiom_chain");
        activity.setParamInfo("{\"description\":\"legacy\",\"opening_message\":\"\"}");
        AgentPluginMapping retired = new AgentPluginMapping();
        retired.setProviderCode("set_session_voice");
        when(mapper.selectPluginsByAgentId("agent-a")).thenReturn(new ArrayList<>(List.of(activity, retired)));
        when(providerService.getById("activity-id")).thenReturn(provider("idiom_chain",
                "[{\"key\":\"instructions\",\"default\":\"catalog rules\"},"
                        + "{\"key\":\"opening_message\",\"default\":\"catalog opening\"}]"));
        AgentPluginMappingServiceImpl service = new AgentPluginMappingServiceImpl(mapper,
                mock(KnowledgeBaseService.class), mock(ModelConfigService.class), providerService);
        List<AgentPluginMapping> result = service.agentPluginParamsByAgentId("agent-a");
        assertEquals(1, result.size());
        Map<?, ?> params = JsonUtils.parseObject(result.get(0).getParamInfo(), Map.class);
        assertEquals("catalog rules", params.get("instructions"));
        assertEquals("", params.get("opening_message"));
        assertEquals(false, params.containsKey("description"));
    }

    @Test
    void generatedKnowledgeDescriptionKeepsDatasetScopeAndRoleQueryPolicy() {
        AgentPluginMappingMapper mapper = mock(AgentPluginMappingMapper.class);
        KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
        ModelConfigService models = mock(ModelConfigService.class);
        AgentPluginMapping mapping = new AgentPluginMapping();
        mapping.setPluginId("kb-id");
        mapping.setParamInfo("{\"queryPolicy\":\"保留产品型号\"}");
        when(mapper.selectPluginsByAgentId("agent-a")).thenReturn(new ArrayList<>(List.of(mapping)));
        KnowledgeBaseEntity dataset = new KnowledgeBaseEntity();
        dataset.setDatasetId("dataset-a");
        dataset.setRagModelId("rag-model");
        dataset.setDescription("产品说明书");
        when(knowledge.selectById("kb-id")).thenReturn(dataset);
        ModelConfigEntity model = new ModelConfigEntity();
        model.setModelCode("local_knowledge");
        model.setConfigJson(new JSONObject().set("type", "local_knowledge"));
        when(models.getModelByIdFromCache("rag-model")).thenReturn(model);
        AgentPluginMappingServiceImpl service = new AgentPluginMappingServiceImpl(mapper,
                knowledge, models, mock(ModelProviderService.class));
        List<AgentPluginMapping> result = service.agentPluginParamsByAgentId("agent-a");
        assertEquals(1, result.size());
        Map<?, ?> params = JsonUtils.parseObject(result.get(0).getParamInfo(), Map.class);
        org.junit.jupiter.api.Assertions.assertTrue(params.get("description").toString().contains("产品说明书"));
        org.junit.jupiter.api.Assertions.assertTrue(params.get("description").toString().contains("保留产品型号"));
    }

    private ModelProviderDTO provider(String providerCode, String fields) {
        ModelProviderDTO provider = new ModelProviderDTO();
        provider.setProviderCode(providerCode);
        provider.setFields(fields);
        return provider;
    }
}
