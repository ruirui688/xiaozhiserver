package xiaozhi.modules.memory.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import xiaozhi.modules.llm.service.LLMService;
import xiaozhi.modules.memory.dao.MemoryCandidateDao;
import xiaozhi.modules.memory.dao.MemoryFactDao;
import xiaozhi.modules.memory.dao.MemoryForgetTombstoneDao;
import xiaozhi.modules.memory.dao.MemoryProfileDao;
import xiaozhi.modules.memory.dao.MemoryRevisionDao;
import xiaozhi.modules.memory.dao.MemorySourceEventDao;
import xiaozhi.modules.memory.dao.MemoryToolBehaviorDao;
import xiaozhi.modules.memory.dto.MemoryEventDTO;
import xiaozhi.modules.memory.dto.MemoryForgetDTO;
import xiaozhi.modules.memory.dto.MemoryQueryDTO;
import xiaozhi.modules.memory.dto.MemoryRememberDTO;
import xiaozhi.modules.memory.dto.MemoryRebuildRequestDTO;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.entity.MemoryForgetTombstoneEntity;
import xiaozhi.modules.memory.vo.MemoryQueryVO;
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
    private final ModelConfigService modelConfigService = mock(ModelConfigService.class);
    private final ModelRuntimeAssembler modelRuntimeAssembler = mock(ModelRuntimeAssembler.class);
    private final AgentChatHistoryService agentChatHistoryService = mock(AgentChatHistoryService.class);

    private MemoryServiceImpl memoryService;

    @BeforeEach
    void setUp() {
        MemoryQueryServiceImpl memoryQueryService = new MemoryQueryServiceImpl(
                factDao,
                profileDao,
                toolBehaviorDao,
                tombstoneDao,
                deviceDao,
                agentDao,
                llmService,
                modelConfigService,
                modelRuntimeAssembler);
        MemoryMaintenanceServiceImpl memoryMaintenanceService = new MemoryMaintenanceServiceImpl(
                sourceEventDao,
                candidateDao,
                factDao,
                revisionDao,
                tombstoneDao,
                deviceDao,
                agentDao,
                llmService,
                modelConfigService,
                modelRuntimeAssembler,
                agentChatHistoryService);
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
                memoryQueryService,
                memoryMaintenanceService);

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
        assertEquals("long_term", inserted.getMemoryTier());
        assertNull(inserted.getExpiresAt());
    }

    @Test
    void clearThenRebuildSkipsLowQualityAddBeforeClearingExistingFacts() {
        MemoryFactEntity existing = activeFact("old-1", "用户的旧记忆");
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        String lowQualityAdd = """
                {
                  "actions": [
                    {
                      "action": "ADD",
                      "related_memory_id": "",
                      "memory_type": "普通事实",
                      "category": "普通事实",
                      "canonical_text": "用户刚才随口说了一句话",
                      "confidence": 0.40,
                      "importance": 0.20,
                      "memory_tier": "working",
                      "sensitivity": "normal",
                      "reason": "复用价值低"
                    }
                  ]
                }
                """;
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID))).thenReturn(lowQualityAdd, lowQualityAdd);

        MemoryRebuildResultVO result = memoryService.rebuildFromRecentChatHistory(
                AGENT_ID, rebuildRequest(false), "user", "tester");

        assertEquals(0, result.getActionsProposed());
        assertEquals(0, result.getFactsCleared());
        assertEquals(0, result.getFactsChanged());
        verify(factDao, never()).updateById(any());
        verify(factDao, never()).insert(any());
    }

    @Test
    void shortTermAddGetsExpiry() {
        MemoryFactEntity existing = activeFact("old-1", "用户的旧记忆");
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        String shortTermAdd = """
                {
                  "actions": [
                    {
                      "action": "ADD",
                      "related_memory_id": "",
                      "memory_type": "阶段性事项",
                      "category": "近期安排",
                      "canonical_text": "用户近期正在准备一次演示",
                      "confidence": 0.88,
                      "importance": 0.62,
                      "memory_tier": "short_term",
                      "sensitivity": "normal",
                      "reason": "阶段性有用"
                    }
                  ]
                }
                """;
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID))).thenReturn(shortTermAdd, shortTermAdd);

        memoryService.rebuildFromRecentChatHistory(AGENT_ID, rebuildRequest(false), "user", "tester");

        ArgumentCaptor<MemoryFactEntity> factCaptor = ArgumentCaptor.forClass(MemoryFactEntity.class);
        verify(factDao).insert(factCaptor.capture());
        MemoryFactEntity inserted = factCaptor.getValue();
        assertEquals("short_term", inserted.getMemoryTier());
        assertNotNull(inserted.getExpiresAt());
    }

    @Test
    void reviewActiveMemoriesCanArchiveExistingFact() {
        MemoryFactEntity existing = activeFact("fact-archive", "用户的一条旧阶段性记忆");
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        when(factDao.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID))).thenReturn("""
                {
                  "actions": [
                    {
                      "action": "ARCHIVE",
                      "related_memory_id": "fact-archive",
                      "memory_type": "阶段性事项",
                      "category": "旧事项",
                      "canonical_text": "用户的一条旧阶段性记忆",
                      "confidence": 0.80,
                      "importance": 0.30,
                      "memory_tier": "working",
                      "sensitivity": "normal",
                      "reason": "不再主动注入"
                    }
                  ]
                }
                """);

        MemoryRebuildResultVO result = memoryService.reviewActiveMemories(
                AGENT_ID, false, "user", "tester");

        assertEquals(1, result.getActionsProposed());
        assertEquals(1, result.getFactsChanged());
        ArgumentCaptor<MemoryFactEntity> factCaptor = ArgumentCaptor.forClass(MemoryFactEntity.class);
        verify(factDao).updateById(factCaptor.capture());
        MemoryFactEntity archived = factCaptor.getValue();
        assertEquals("archived", archived.getStatus());
        assertNotNull(archived.getArchivedAt());
        assertNotNull(archived.getValidTo());
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

    @Test
    void rememberCanUpdateExistingDeviceScopedFactWithoutMacAddress() {
        MemoryFactEntity existing = activeFact("fact-1", "高瑞瑞的领导是钟文杰");
        existing.setCategory("人际关系");
        existing.setMemoryType("领导信息");
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        when(factDao.selectOne(any(Wrapper.class))).thenReturn(existing, (MemoryFactEntity) null);
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID)))
                .thenReturn("""
                        {
                          "action": "UPDATE",
                          "related_memory_id": "fact-1",
                          "memory_type": "人物关系",
                          "category": "工作关系",
                          "canonical_text": "高瑞瑞的老板是钟文杰",
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
                          "reason": "补充老板称呼"
                        }
                        """)
                .thenReturn("""
                        {
                          "actions": [
                            {
                              "action": "UPDATE",
                              "related_memory_id": "fact-1",
                              "memory_type": "人物关系",
                              "category": "工作关系",
                              "canonical_text": "高瑞瑞的老板是钟文杰",
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
                              "reason": "审校通过"
                            }
                          ]
                        }
                        """);

        MemoryRememberDTO dto = new MemoryRememberDTO();
        dto.setAgentId(AGENT_ID);
        dto.setText("我的老板是钟文杰");
        memoryService.remember(dto, "user", "tester");

        verify(factDao, never()).insert(any());
        ArgumentCaptor<MemoryFactEntity> factCaptor = ArgumentCaptor.forClass(MemoryFactEntity.class);
        verify(factDao).updateById(factCaptor.capture());
        MemoryFactEntity updated = factCaptor.getValue();
        assertEquals("fact-1", updated.getMemoryId());
        assertEquals("高瑞瑞的老板是钟文杰", updated.getCanonicalText());
        assertEquals(SCOPE_ID, updated.getScopeId());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmService, times(2)).generateJson(promptCaptor.capture(), eq(MAINTENANCE_MODEL_ID));
        assertTrue(promptCaptor.getAllValues().get(0).contains("id=fact-1"));
        assertTrue(promptCaptor.getAllValues().get(0).contains("高瑞瑞的领导是钟文杰"));
    }

    @Test
    void rememberReviewCanTurnAddIntoUpdateForExistingFact() {
        MemoryFactEntity existing = activeFact("fact-1", "高瑞瑞的领导是钟文杰");
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        when(factDao.selectOne(any(Wrapper.class))).thenReturn(existing, (MemoryFactEntity) null);
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID)))
                .thenReturn("""
                        {
                          "action": "ADD",
                          "related_memory_id": "",
                          "memory_type": "事实",
                          "category": "个人",
                          "canonical_text": "钟文杰是用户的老板",
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
                              "action": "UPDATE",
                              "related_memory_id": "fact-1",
                              "memory_type": "人物关系",
                              "category": "工作关系",
                              "canonical_text": "高瑞瑞的老板是钟文杰",
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
                              "reason": "已有同一人物关系"
                            }
                          ]
                        }
                        """);

        MemoryRememberDTO dto = new MemoryRememberDTO();
        dto.setAgentId(AGENT_ID);
        dto.setText("我的老板是钟文杰");
        memoryService.remember(dto, "user", "tester");

        verify(factDao, never()).insert(any());
        ArgumentCaptor<MemoryFactEntity> factCaptor = ArgumentCaptor.forClass(MemoryFactEntity.class);
        verify(factDao).updateById(factCaptor.capture());
        assertEquals("高瑞瑞的老板是钟文杰", factCaptor.getValue().getCanonicalText());
    }

    @Test
    void recordEventMaintenanceAddPreservesSourceEventId() {
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID)))
                .thenReturn("""
                        {
                          "facts": [
                            {
                              "text": "用户喜欢自己熬番茄酱配饺子",
                              "category": "饮食偏好",
                              "memory_type": "用户偏好",
                              "confidence": 0.92,
                              "importance": 0.72,
                              "memory_tier": "long_term"
                            }
                          ]
                        }
                        """)
                .thenReturn("""
                        {
                          "action": "ADD",
                          "related_memory_id": "",
                          "memory_type": "用户偏好",
                          "category": "饮食偏好",
                          "canonical_text": "用户喜欢自己熬番茄酱配饺子",
                          "confidence": 0.92,
                          "importance": 0.72,
                          "memory_tier": "long_term",
                          "sensitivity": "normal",
                          "reason": "用户明确说明"
                        }
                        """);

        MemoryEventDTO dto = new MemoryEventDTO();
        dto.setAgentId(AGENT_ID);
        dto.setMacAddress(SCOPE_ID);
        dto.setSessionId("session-1");
        dto.setEventId("evt-1");
        dto.setChatType(1);
        dto.setContentText("我喜欢自己熬番茄酱配饺子");
        memoryService.recordEvent(dto);

        ArgumentCaptor<MemoryFactEntity> factCaptor = ArgumentCaptor.forClass(MemoryFactEntity.class);
        verify(factDao).insert(factCaptor.capture());
        MemoryFactEntity inserted = factCaptor.getValue();
        assertEquals("用户喜欢自己熬番茄酱配饺子", inserted.getCanonicalText());
        assertEquals("evt-1", inserted.getSourceEventId());
        assertTrue(inserted.getPayload().contains(MAINTENANCE_MODEL_ID));
    }

    @Test
    void forgetDeletesFactAndWritesActiveTombstone() {
        MemoryFactEntity existing = activeFact("fact-secret", "用户不想让钟文杰知道自己不想上班");
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        when(factDao.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID))).thenReturn("""
                {
                  "action": "DELETE",
                  "related_memory_id": "fact-secret",
                  "canonical_text": "用户不想让钟文杰知道自己不想上班",
                  "tombstone_pattern": "不想让钟文杰知道自己不想上班",
                  "pattern_type": "semantic",
                  "confidence": 0.95,
                  "reason": "用户显式忘记"
                }
                """);

        MemoryForgetDTO dto = new MemoryForgetDTO();
        dto.setAgentId(AGENT_ID);
        dto.setText("忘记我不想让钟文杰知道自己不想上班");
        dto.setReason("管理端手动忘记");
        memoryService.forget(dto, "user", "tester");

        ArgumentCaptor<MemoryForgetTombstoneEntity> tombstoneCaptor =
                ArgumentCaptor.forClass(MemoryForgetTombstoneEntity.class);
        verify(tombstoneDao).insert(tombstoneCaptor.capture());
        MemoryForgetTombstoneEntity tombstone = tombstoneCaptor.getValue();
        assertEquals(AGENT_ID, tombstone.getAgentId());
        assertEquals(AGENT_ID, tombstone.getScopeId());
        assertEquals("active", tombstone.getStatus());
        assertEquals("不想让钟文杰知道自己不想上班", tombstone.getPatternText());

        ArgumentCaptor<MemoryFactEntity> factCaptor = ArgumentCaptor.forClass(MemoryFactEntity.class);
        verify(factDao).updateById(factCaptor.capture());
        assertEquals("deleted", factCaptor.getValue().getStatus());
    }

    @Test
    void queryDoesNotRefreshLastSeenAndFiltersTombstone() {
        MemoryFactEntity blocked = activeFact("fact-secret", "用户不想让钟文杰知道自己不想上班");
        MemoryFactEntity visible = activeFact("fact-bike", "用户有一辆黑色鱼雷6自行车");
        MemoryForgetTombstoneEntity tombstone = new MemoryForgetTombstoneEntity();
        tombstone.setPatternText("不想让钟文杰知道自己不想上班");
        tombstone.setStatus("active");
        when(tombstoneDao.selectList(any(Wrapper.class))).thenReturn(List.of(tombstone));
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(blocked, visible));

        MemoryQueryDTO dto = new MemoryQueryDTO();
        dto.setAgentId(AGENT_ID);
        dto.setQuery("");
        MemoryQueryVO result = memoryService.query(dto);

        assertEquals(1, result.getFacts().size());
        assertEquals("fact-bike", result.getFacts().get(0).getMemoryId());
        assertEquals(1, result.getMatchedTombstones().size());
        verify(factDao, never()).updateById(any());
    }

    @Test
    void maintenanceAddUpliftsDrivingTestSensitivity() {
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID))).thenReturn("""
                {
                  "action": "ADD",
                  "related_memory_id": "",
                  "memory_type": "个人经历",
                  "category": "驾考经历",
                  "canonical_text": "用户驾照科目二第6次才通过",
                  "confidence": 0.92,
                  "importance": 0.72,
                  "memory_tier": "long_term",
                  "sensitivity": "personal",
                  "reason": "用户明确说明"
                }
                """).thenReturn("""
                {
                  "actions": [
                    {
                      "action": "ADD",
                      "related_memory_id": "",
                      "memory_type": "个人经历",
                      "category": "驾考经历",
                      "canonical_text": "用户驾照科目二第6次才通过",
                      "confidence": 0.92,
                      "importance": 0.72,
                      "memory_tier": "long_term",
                      "sensitivity": "personal",
                      "reason": "审校通过"
                    }
                  ]
                }
                """);

        MemoryRememberDTO dto = new MemoryRememberDTO();
        dto.setAgentId(AGENT_ID);
        dto.setText("我驾照科目二第6次才通过");
        memoryService.remember(dto, "user", "tester");

        ArgumentCaptor<MemoryFactEntity> factCaptor = ArgumentCaptor.forClass(MemoryFactEntity.class);
        verify(factDao).insert(factCaptor.capture());
        assertEquals("sensitive", factCaptor.getValue().getSensitivity());
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
