package xiaozhi.modules.model.service.impl.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import cn.hutool.json.JSONObject;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.entity.AgentPluginMapping;
import xiaozhi.modules.agent.service.AgentPluginMappingService;
import xiaozhi.modules.knowledge.dao.KnowledgeBaseDao;
import xiaozhi.modules.knowledge.dao.KnowledgeChunkDao;
import xiaozhi.modules.knowledge.entity.KnowledgeBaseEntity;
import xiaozhi.modules.knowledge.entity.KnowledgeChunkEntity;
import xiaozhi.modules.model.dto.ModelBenchmarkCaseGenerateRequestDTO;
import xiaozhi.modules.model.dto.ModelBenchmarkCaseGenerateResponseDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.sys.service.SysParamsService;

class BenchmarkCaseGeneratorTest {

    private final AgentDao agentDao = mock(AgentDao.class);
    private final AgentPluginMappingService agentPluginMappingService = mock(AgentPluginMappingService.class);
    private final KnowledgeBaseDao knowledgeBaseDao = mock(KnowledgeBaseDao.class);
    private final KnowledgeChunkDao knowledgeChunkDao = mock(KnowledgeChunkDao.class);
    private final SysParamsService sysParamsService = mock(SysParamsService.class);
    private final ModelConfigService modelConfigService = mock(ModelConfigService.class);
    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final BenchmarkCaseGenerator generator = new BenchmarkCaseGenerator(
            agentDao,
            agentPluginMappingService,
            knowledgeBaseDao,
            knowledgeChunkDao,
            sysParamsService,
            modelConfigService,
            restTemplate);

    @BeforeEach
    void setUp() {
        xiaozhi.common.llm.MeteredHttpTestSupport.install(generator);
        when(agentPluginMappingService.list(any(Wrapper.class))).thenReturn(List.of());
        when(agentPluginMappingService.agentPluginParamsByAgentId(any())).thenReturn(List.of());
        when(knowledgeBaseDao.selectList(any())).thenReturn(List.of());
    }

    @Test
    void generateUsesPresetCasesWithoutExternalModel() {
        ModelBenchmarkCaseGenerateResponseDTO response = generator.generate(null);

        assertEquals("LLM", response.getModelType());
        assertEquals("PRESET_ONLY", response.getGenerationMode());
        assertFalse(response.getExternalModelUsed());
        assertFalse(response.getCases().isEmpty());
        assertEquals(response.getCases().size(), response.getCustomQuestions().size());
        assertTrue(response.getCases().stream().allMatch(item -> "preset".equals(item.getSource())));
    }

    @Test
    void generateCanUseAgentKnowledgeAndPluginFallbackContext() {
        AgentEntity agent = new AgentEntity();
        agent.setId("agent-1");
        agent.setAgentName("导购小智");
        agent.setSystemPrompt("你是酒类导购助手。");
        when(agentDao.selectById("agent-1")).thenReturn(agent);

        AgentPluginMapping knowledgeMapping = new AgentPluginMapping();
        knowledgeMapping.setAgentId("agent-1");
        knowledgeMapping.setPluginId("kb-row-1");
        when(agentPluginMappingService.list(any(Wrapper.class))).thenReturn(List.of(knowledgeMapping));

        AgentPluginMapping weatherMapping = new AgentPluginMapping();
        weatherMapping.setAgentId("agent-1");
        weatherMapping.setPluginId("SYSTEM_PLUGIN_WEATHER");
        weatherMapping.setProviderCode("get_weather");
        when(agentPluginMappingService.agentPluginParamsByAgentId("agent-1")).thenReturn(List.of(weatherMapping));

        KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity();
        knowledgeBase.setId("kb-row-1");
        knowledgeBase.setDatasetId("dataset-1");
        knowledgeBase.setName("红酒知识库");
        knowledgeBase.setDescription("覆盖红酒产区、风味和餐酒搭配。");
        when(knowledgeBaseDao.selectById("kb-row-1")).thenReturn(knowledgeBase);

        KnowledgeChunkEntity chunk = new KnowledgeChunkEntity();
        chunk.setQuestion("赤霞珠适合搭配什么餐食？");
        when(knowledgeChunkDao.selectList(any())).thenReturn(List.of(chunk));

        ModelBenchmarkCaseGenerateRequestDTO request = new ModelBenchmarkCaseGenerateRequestDTO();
        request.setModelType("LLM");
        request.setAgentId("agent-1");
        request.setLimit(20);

        ModelBenchmarkCaseGenerateResponseDTO response = generator.generate(request);

        assertEquals("agent-1", response.getAgentId());
        assertEquals("导购小智", response.getAgentName());
        assertEquals("AGENT_CONTEXT_FALLBACK", response.getGenerationMode());
        assertFalse(response.getExternalModelUsed());
        assertTrue(response.getCases().stream().anyMatch(item -> "agent_profile".equals(item.getSource())));
        assertTrue(response.getCases().stream().anyMatch(item -> "knowledge".equals(item.getSource())));
        assertTrue(response.getCases().stream().anyMatch(item -> "plugin".equals(item.getSource())));
        assertNotNull(response.getCustomQuestions());
        assertTrue(response.getCustomQuestions().stream().anyMatch(item -> item.contains("赤霞珠")));
    }

    @Test
    void generateUsesConfiguredLlmWhenAgentContextExists() {
        AgentEntity agent = new AgentEntity();
        agent.setId("agent-2");
        agent.setAgentName("售后小智");
        agent.setSystemPrompt("你是售后客服助手，负责解释保修政策。");
        when(agentDao.selectById("agent-2")).thenReturn(agent);
        when(sysParamsService.getValue("model.assistant.llm_model_id", true)).thenReturn("llm-1");

        ModelConfigEntity llmModel = new ModelConfigEntity();
        llmModel.setId("llm-1");
        llmModel.setModelType("LLM");
        llmModel.setIsEnabled(1);
        llmModel.setTestStatus(1);
        JSONObject config = new JSONObject();
        config.set("base_url", "https://example.test/v1");
        config.set("model_name", "case-generator");
        config.set("api_key", "test-key");
        llmModel.setConfigJson(config);
        when(modelConfigService.getModelByIdFromCache("llm-1")).thenReturn(llmModel);

        String llmBody = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\\"cases\\":[{\\"caseId\\":\\"llm_case_1\\",\\"source\\":\\"llm_agent_context\\",\\"scenario\\":\\"售后政策\\",\\"riskLevel\\":\\"NORMAL\\",\\"tags\\":[\\"售后\\",\\"知识库\\"],\\"turns\\":[\\"我的设备坏了，怎么确认是否还在保修期？\\"],\\"expectedBehavior\\":\\"按售后人设说明保修确认流程\\",\\"safetyRules\\":[\\"不得编造保修结果\\"]}]}"
                      }
                    }
                  ]
                }
                """;
        when(restTemplate.exchange(
                eq("https://example.test/v1/chat/completions"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class))).thenReturn(new ResponseEntity<>(llmBody, HttpStatus.OK));

        ModelBenchmarkCaseGenerateRequestDTO request = new ModelBenchmarkCaseGenerateRequestDTO();
        request.setModelType("LLM");
        request.setAgentId("agent-2");
        request.setLimit(4);

        ModelBenchmarkCaseGenerateResponseDTO response = generator.generate(request);

        assertEquals("LLM_GENERATED", response.getGenerationMode());
        assertTrue(response.getExternalModelUsed());
        assertEquals("llm_agent_context", response.getSource());
        assertEquals(1, response.getCases().size());
        assertTrue(response.getCustomQuestions().get(0).contains("保修期"));
        verify(restTemplate).exchange(
                eq("https://example.test/v1/chat/completions"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class));
    }
}
