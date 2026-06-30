package xiaozhi.modules.memory.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import cn.hutool.json.JSONObject;
import xiaozhi.common.page.PageData;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.dto.AgentChatHistoryDTO;
import xiaozhi.modules.agent.dto.AgentChatSessionDTO;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.llm.service.EmbeddingService;
import xiaozhi.modules.llm.service.LLMService;
import xiaozhi.modules.memory.dao.MemoryCandidateDao;
import xiaozhi.modules.memory.dao.MemoryFactDao;
import xiaozhi.modules.memory.dao.MemoryForgetTombstoneDao;
import xiaozhi.modules.memory.dao.MemoryProfileDao;
import xiaozhi.modules.memory.dao.MemoryRevisionDao;
import xiaozhi.modules.memory.dao.MemorySourceEventDao;
import xiaozhi.modules.memory.dao.MemoryToolBehaviorDao;
import xiaozhi.modules.memory.dto.MemoryRememberDTO;
import xiaozhi.modules.memory.dto.MemoryRebuildRequestDTO;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.vo.MemoryRebuildResultVO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.impl.ModelRuntimeAssembler;

class MemoryServiceImplTest {

    private static final String AGENT_ID = "agent-1";
    private static final String SCOPE_ID = "AA:BB:CC:DD";
    private static final String MEMORY_MODEL_ID = "memory-model-1";
    private static final String MAINTENANCE_MODEL_ID = "llm-memory-1";

    private final MemorySourceEventDao sourceEventDao = mock(MemorySourceEventDao.class);
    private final MemoryCandidateDao candidateDao = mock(MemoryCandidateDao.class);
    private final MemoryFactDao factDao = mock(MemoryFactDao.class);
    private final MemoryRevisionDao revisionDao = mock(MemoryRevisionDao.class);
    private final MemoryProfileDao profileDao = mock(MemoryProfileDao.class);
    private final MemoryToolBehaviorDao toolBehaviorDao = mock(MemoryToolBehaviorDao.class);
    private final MemoryForgetTombstoneDao tombstoneDao = mock(MemoryForgetTombstoneDao.class);
    private final DeviceDao deviceDao = mock(DeviceDao.class);
    private final AgentDao agentDao = mock(AgentDao.class);
    private final LLMService llmService = mock(LLMService.class);
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final ModelConfigService modelConfigService = mock(ModelConfigService.class);
    private final ModelRuntimeAssembler modelRuntimeAssembler = mock(ModelRuntimeAssembler.class);
    private final AgentChatHistoryService agentChatHistoryService = mock(AgentChatHistoryService.class);

    private MemoryServiceImpl memoryService;

    @BeforeEach
    void setUp() {
        memoryService = new MemoryServiceImpl(
                sourceEventDao,
                candidateDao,
                factDao,
                revisionDao,
                profileDao,
                toolBehaviorDao,
                tombstoneDao,
                deviceDao,
                agentDao,
                llmService,
                embeddingService,
                modelConfigService,
                modelRuntimeAssembler,
                agentChatHistoryService);

        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT_ID);
        agent.setUserId(1001L);
        agent.setMemModelId(MEMORY_MODEL_ID);
        when(agentDao.selectById(AGENT_ID)).thenReturn(agent);

        ModelConfigEntity memoryModel = new ModelConfigEntity();
        memoryModel.setId(MEMORY_MODEL_ID);
        when(modelConfigService.getModelByIdFromCache(MEMORY_MODEL_ID)).thenReturn(memoryModel);
        JSONObject memoryConfig = new JSONObject();
        memoryConfig.set("maintenance_llm", MAINTENANCE_MODEL_ID);
        when(modelRuntimeAssembler.buildTypedConfig(memoryModel)).thenReturn(memoryConfig);

        when(agentChatHistoryService.getSessionListByAgentId(any())).thenReturn(new PageData<>(List.of(session("s1")), 1));
        when(agentChatHistoryService.getChatHistoryBySessionId(eq(AGENT_ID), eq("s1"))).thenReturn(List.of(
                history((byte) 1, "我是高瑞瑞"),
                history((byte) 1, "我的老板是钟文杰")));
        when(tombstoneDao.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(candidateDao.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(factDao.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(llmService.generateEmbedding(any(), eq(MAINTENANCE_MODEL_ID))).thenReturn(null);
    }

    @Test
    void clearThenRebuildDoesNotClearExistingFactsWhenLlmReturnsNoActions() {
        MemoryFactEntity existing = activeFact("old-1", "用户的旧记忆");
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID))).thenReturn("{\"actions\":[]}");

        MemoryRebuildResultVO result = memoryService.rebuildFromRecentChatHistory(
                AGENT_ID, rebuildRequest(false), "user", "tester");

        assertEquals(1, result.getSessionsRead());
        assertEquals(0, result.getActionsProposed());
        assertEquals(0, result.getFactsCleared());
        assertEquals(0, result.getFactsChanged());
        verify(factDao, never()).updateById(any());
        verify(factDao, never()).insert(any());
    }

    @Test
    void clearThenRebuildAppliesLlmAddActionAsActiveFact() {
        MemoryFactEntity existing = activeFact("old-1", "用户的旧记忆");
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        String addAction = """
                {
                  "actions": [
                    {
                      "action": "ADD",
                      "related_memory_id": "",
                      "memory_type": "人物关系",
                      "category": "工作关系",
                      "canonical_text": "用户的老板是钟文杰",
                      "confidence": 0.92,
                      "importance": 0.86,
                      "memory_tier": "long_term",
                      "importance_dimensions": {
                        "relevance": 0.9,
                        "novelty": 0.8,
                        "emotional_weight": 0.2,
                        "factual_density": 0.9,
                        "actionability": 0.6,
                        "personal_nature": 0.7
                      },
                      "sensitivity": "normal",
                      "reason": "用户明确说明"
                    }
                  ]
                }
                """;
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID))).thenReturn(addAction, addAction);

        MemoryRebuildResultVO result = memoryService.rebuildFromRecentChatHistory(
                AGENT_ID, rebuildRequest(false), "user", "tester");

        assertEquals(1, result.getSessionsRead());
        assertEquals(1, result.getActionsProposed());
        assertEquals(1, result.getFactsCleared());
        assertEquals(1, result.getFactsChanged());

        verify(factDao).updateById(existing);
        ArgumentCaptor<MemoryFactEntity> factCaptor = ArgumentCaptor.forClass(MemoryFactEntity.class);
        verify(factDao).insert(factCaptor.capture());
        MemoryFactEntity inserted = factCaptor.getValue();
        assertEquals("用户的老板是钟文杰", inserted.getCanonicalText());
        assertEquals("active", inserted.getStatus());
        assertEquals("auto", inserted.getConfirmationState());
        assertEquals(SCOPE_ID, inserted.getScopeId());
        assertTrue(inserted.getImportance().compareTo(new BigDecimal("0.86")) == 0);
    }

    @Test
    void rebuildRepairsInvalidJsonWithMaintenanceLlmBeforeApplyingActions() {
        MemoryFactEntity existing = activeFact("old-1", "用户的旧记忆");
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID)))
                .thenReturn("{\"actions\":[{\"action\":\"ADD\",\"canonical_text\":\"用户的老板是钟文杰\"")
                .thenReturn("""
                        {
                          "actions": [
                            {
                              "action": "ADD",
                              "related_memory_id": "",
                              "memory_type": "人物关系",
                              "category": "工作关系",
                              "canonical_text": "用户的老板是钟文杰",
                              "confidence": 0.92,
                              "importance": 0.86,
                              "memory_tier": "long_term",
                              "importance_dimensions": {
                                "relevance": 0.9,
                                "novelty": 0.8,
                                "emotional_weight": 0.2,
                                "factual_density": 0.9,
                                "actionability": 0.6,
                                "personal_nature": 0.7
                              },
                              "sensitivity": "normal",
                              "reason": "修复格式"
                            }
                          ]
                        }
                        """)
                .thenReturn("""
                        {
                          "actions": [
                            {
                              "action": "ADD",
                              "related_memory_id": "",
                              "memory_type": "人物关系",
                              "category": "工作关系",
                              "canonical_text": "用户的老板是钟文杰",
                              "confidence": 0.92,
                              "importance": 0.86,
                              "memory_tier": "long_term",
                              "importance_dimensions": {
                                "relevance": 0.9,
                                "novelty": 0.8,
                                "emotional_weight": 0.2,
                                "factual_density": 0.9,
                                "actionability": 0.6,
                                "personal_nature": 0.7
                              },
                              "sensitivity": "normal",
                              "reason": "审校格式"
                            }
                          ]
                        }
                        """);

        MemoryRebuildResultVO result = memoryService.rebuildFromRecentChatHistory(
                AGENT_ID, rebuildRequest(false), "user", "tester");

        assertEquals(1, result.getActionsProposed());
        assertEquals(1, result.getFactsChanged());
        verify(llmService, times(3)).generateJson(any(), eq(MAINTENANCE_MODEL_ID));
        ArgumentCaptor<MemoryFactEntity> factCaptor = ArgumentCaptor.forClass(MemoryFactEntity.class);
        verify(factDao).insert(factCaptor.capture());
        assertEquals("用户的老板是钟文杰", factCaptor.getValue().getCanonicalText());
    }

    @Test
    void rememberReviewsMaintenanceDecisionBeforeWritingFact() {
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID)))
                .thenReturn("""
                        {
                          "action": "ADD",
                          "related_memory_id": "",
                          "memory_type": "factual",
                          "category": "relationship",
                          "canonical_text": "我的老板是钟文杰",
                          "confidence": 0.92,
                          "importance": 0.86,
                          "memory_tier": "long_term",
                          "importance_dimensions": {
                            "relevance": 0.9,
                            "novelty": 0.8,
                            "emotional_weight": 0.2,
                            "factual_density": 0.9,
                            "actionability": 0.6,
                            "personal_nature": 0.7
                          },
                          "sensitivity": "normal",
                          "reason": "用户明确说明"
                        }
                        """)
                .thenReturn("""
                        {
                          "actions": [
                            {
                              "action": "ADD",
                              "related_memory_id": "",
                              "memory_type": "人物关系",
                              "category": "工作关系",
                              "canonical_text": "我的老板是钟文杰",
                              "confidence": 0.92,
                              "importance": 0.86,
                              "memory_tier": "long_term",
                              "importance_dimensions": {
                                "relevance": 0.9,
                                "novelty": 0.8,
                                "emotional_weight": 0.2,
                                "factual_density": 0.9,
                                "actionability": 0.6,
                                "personal_nature": 0.7
                              },
                              "sensitivity": "normal",
                              "reason": "标签审校"
                            }
                          ]
                        }
                        """);

        MemoryRememberDTO dto = new MemoryRememberDTO();
        dto.setAgentId(AGENT_ID);
        dto.setText("我的老板是钟文杰");
        memoryService.remember(dto, "user", "tester");

        ArgumentCaptor<MemoryFactEntity> factCaptor = ArgumentCaptor.forClass(MemoryFactEntity.class);
        verify(factDao).insert(factCaptor.capture());
        MemoryFactEntity fact = factCaptor.getValue();
        assertEquals("我的老板是钟文杰", fact.getCanonicalText());
        assertEquals("工作关系", fact.getCategory());
        assertEquals("人物关系", fact.getMemoryType());
    }

    private MemoryRebuildRequestDTO rebuildRequest(boolean dryRun) {
        MemoryRebuildRequestDTO request = new MemoryRebuildRequestDTO();
        request.setSessionLimit(15);
        request.setMode("clear_then_rebuild");
        request.setDryRun(dryRun);
        return request;
    }

    private AgentChatSessionDTO session(String sessionId) {
        AgentChatSessionDTO session = new AgentChatSessionDTO();
        session.setSessionId(sessionId);
        return session;
    }

    private AgentChatHistoryDTO history(byte chatType, String content) {
        AgentChatHistoryDTO history = new AgentChatHistoryDTO();
        history.setChatType(chatType);
        history.setMacAddress(SCOPE_ID);
        history.setContent(content);
        return history;
    }

    private MemoryFactEntity activeFact(String memoryId, String text) {
        MemoryFactEntity fact = new MemoryFactEntity();
        fact.setMemoryId(memoryId);
        fact.setAgentId(AGENT_ID);
        fact.setScopeType("device");
        fact.setScopeId(SCOPE_ID);
        fact.setCanonicalText(text);
        fact.setStatus("active");
        fact.setConfidence(new BigDecimal("0.70"));
        fact.setImportance(new BigDecimal("0.70"));
        return fact;
    }
}
