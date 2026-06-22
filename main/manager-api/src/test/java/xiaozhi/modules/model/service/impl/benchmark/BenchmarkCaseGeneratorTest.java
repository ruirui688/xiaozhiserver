package xiaozhi.modules.model.service.impl.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

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

class BenchmarkCaseGeneratorTest {

    private final AgentDao agentDao = mock(AgentDao.class);
    private final AgentPluginMappingService agentPluginMappingService = mock(AgentPluginMappingService.class);
    private final KnowledgeBaseDao knowledgeBaseDao = mock(KnowledgeBaseDao.class);
    private final KnowledgeChunkDao knowledgeChunkDao = mock(KnowledgeChunkDao.class);
    private final BenchmarkCaseGenerator generator = new BenchmarkCaseGenerator(
            agentDao,
            agentPluginMappingService,
            knowledgeBaseDao,
            knowledgeChunkDao);

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
    void generateCanUseAgentKnowledgeAndPluginContext() {
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
        assertFalse(response.getExternalModelUsed());
        assertTrue(response.getCases().stream().anyMatch(item -> "agent_profile".equals(item.getSource())));
        assertTrue(response.getCases().stream().anyMatch(item -> "knowledge".equals(item.getSource())));
        assertTrue(response.getCases().stream().anyMatch(item -> "plugin".equals(item.getSource())));
        assertNotNull(response.getCustomQuestions());
        assertTrue(response.getCustomQuestions().stream().anyMatch(item -> item.contains("赤霞珠")));
    }
}
