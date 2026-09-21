package xiaozhi.modules.memory.service.impl;

import xiaozhi.modules.memory.service.MemoryRuntimeRefresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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
import xiaozhi.modules.memory.dto.MemoryFactDTO;
import xiaozhi.modules.memory.dto.MemoryForgetDTO;
import xiaozhi.modules.memory.dto.MemoryQueryDTO;
import xiaozhi.modules.memory.dto.MemoryRememberDTO;
import xiaozhi.modules.memory.dto.MemoryRebuildRequestDTO;
import xiaozhi.modules.memory.dto.MemorySessionMessageDTO;
import xiaozhi.modules.memory.dto.MemorySessionProcessDTO;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.entity.MemoryForgetTombstoneEntity;
import xiaozhi.modules.memory.service.impl.maintenance.MemoryMaintenanceOutputParser;
import xiaozhi.modules.memory.service.impl.maintenance.MemorySpeakerConversationBuilder;
import xiaozhi.modules.memory.service.MemorySettingsService;
import xiaozhi.modules.memory.utils.MemoryHelper;
import xiaozhi.modules.memory.vo.MemoryQueryVO;
import xiaozhi.modules.memory.vo.MemoryRebuildResultVO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.impl.ModelRuntimeAssembler;

class MemoryServiceImplTest {

    private static final String AGENT_ID = "agent-1";
    private static final String SCOPE_ID = "AA:BB:CC:DD";
    private static final String NEW_SCOPE_ID = "11:22:33:44";
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
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final MemorySettingsService memorySettingsService = mock(MemorySettingsService.class);
    private final MemoryRuntimeRefresh memoryRuntimeRefresh = mock(MemoryRuntimeRefresh.class);

    private MemoryServiceImpl memoryService;
    private MemoryMaintenanceServiceImpl memoryMaintenanceService;

    @org.junit.jupiter.api.AfterEach
    void everyPersistedRevisionRequestsRuntimeInvalidation() {
        long revisions = org.mockito.Mockito.mockingDetails(revisionDao).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("insert")).count();
        long notifications = org.mockito.Mockito.mockingDetails(memoryRuntimeRefresh).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("changed")).count();
        assertEquals(revisions, notifications, "CRUD, forget, review, rebuild and automatic maintenance must all invalidate");
    }

    @BeforeEach
    void setUp() {
        MemoryQueryServiceImpl memoryQueryService = new MemoryQueryServiceImpl(
                factDao,
                profileDao,
                toolBehaviorDao,
                tombstoneDao,
                deviceDao,
                agentDao,
                memorySettingsService);
        memoryMaintenanceService = new MemoryMaintenanceServiceImpl(
                candidateDao,
                factDao,
                revisionDao,
                tombstoneDao,
                deviceDao,
                agentDao,
                llmService,
                modelConfigService,
                modelRuntimeAssembler,
                agentChatHistoryService,
                new MemoryMaintenanceOutputParser(),
                new MemorySpeakerConversationBuilder(),
                transactionTemplate,
                memorySettingsService,
                memoryRuntimeRefresh);
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
                memoryMaintenanceService,
                memorySettingsService,
                memoryRuntimeRefresh);

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
        when(memorySettingsService.activeContextMaxChars()).thenReturn(1000);

        when(agentChatHistoryService.getSessionListByAgentId(any())).thenReturn(new PageData<>(List.of(session("s1")), 1));
        when(agentChatHistoryService.getChatHistoryBySessionId(eq(AGENT_ID), eq("s1"))).thenReturn(List.of(
                history((byte) 1, "我是高瑞瑞"),
                history((byte) 1, "我的老板是钟文杰")));
        when(tombstoneDao.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(candidateDao.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(factDao.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(llmService.generateEmbedding(any(), eq(MAINTENANCE_MODEL_ID))).thenReturn(null);
        when(sourceEventDao.insert(any())).thenAnswer(invocation -> {
            xiaozhi.modules.memory.entity.MemorySourceEventEntity event = invocation.getArgument(0);
            event.setId(100L);
            return 1;
        });
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    void managedMemoryProcessesCompleteSessionWithoutMainLlmToolCall() {
        JSONObject managedConfig = new JSONObject();
        managedConfig.set("type", "managed_memory");
        managedConfig.set("maintenance_llm", MAINTENANCE_MODEL_ID);
        ModelConfigEntity memoryModel = modelConfigService.getModelByIdFromCache(MEMORY_MODEL_ID);
        when(modelRuntimeAssembler.buildTypedConfig(memoryModel)).thenReturn(managedConfig);
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of());
        String action = """
                {
                  "actions": [
                    {
                      "action": "ADD",
                      "source_role": "user",
                      "evidence_quote": "我喜欢跑步",
                      "memory_type": "个人偏好",
                      "category": "运动爱好",
                      "canonical_text": "用户喜欢跑步",
                      "confidence": 0.95,
                      "importance": 0.8,
                      "memory_tier": "long_term",
                      "sensitivity": "normal",
                      "reason": "稳定的跨会话偏好"
                    }
                  ]
                }
                """;
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID)))
                .thenReturn(action, action);

        MemorySessionProcessDTO dto = new MemorySessionProcessDTO();
        dto.setAgentId(AGENT_ID);
        dto.setSessionId("session-active-memory");
        MemorySessionMessageDTO user = new MemorySessionMessageDTO();
        user.setRole("user");
        user.setContent("我喜欢跑步");
        MemorySessionMessageDTO assistant = new MemorySessionMessageDTO();
        assistant.setRole("assistant");
        assistant.setContent("跑步是很好的运动。");
        dto.setMessages(List.of(user, assistant));

        int changed = memoryService.processSession(dto);

        assertEquals(1, changed);
        verify(factDao).insert(any(MemoryFactEntity.class));
        verify(llmService).generateJson(any(), eq(MAINTENANCE_MODEL_ID));
    }

    @Test
    void managedMemoryDoesNotApplyAnAlreadyProcessedSessionAgain() {
        JSONObject managedConfig = new JSONObject();
        managedConfig.set("type", "managed_memory");
        managedConfig.set("maintenance_llm", MAINTENANCE_MODEL_ID);
        ModelConfigEntity memoryModel = modelConfigService.getModelByIdFromCache(MEMORY_MODEL_ID);
        when(modelRuntimeAssembler.buildTypedConfig(memoryModel)).thenReturn(managedConfig);
        when(revisionDao.selectCount(any(Wrapper.class))).thenReturn(0L, 1L);
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID))).thenReturn("""
                {
                  "actions": [
                    {
                      "action": "ADD",
                      "source_role": "user",
                      "evidence_quote": "我喜欢跑步",
                      "canonical_text": "用户喜欢跑步"
                    }
                  ]
                }
                """);

        MemorySessionProcessDTO dto = new MemorySessionProcessDTO();
        dto.setAgentId(AGENT_ID);
        dto.setSessionId("session-already-processed");
        MemorySessionMessageDTO user = new MemorySessionMessageDTO();
        user.setRole("user");
        user.setContent("我喜欢跑步");
        dto.setMessages(List.of(user));

        assertEquals(0, memoryService.processSession(dto));
        verify(factDao, never()).insert(any(MemoryFactEntity.class));
    }

    @Test
    void nonManagedMemoryDoesNotProcessSession() {
        JSONObject shortMemoryConfig = new JSONObject();
        shortMemoryConfig.set("type", "mem_local_short");
        ModelConfigEntity memoryModel = modelConfigService.getModelByIdFromCache(MEMORY_MODEL_ID);
        when(modelRuntimeAssembler.buildTypedConfig(memoryModel)).thenReturn(shortMemoryConfig);

        MemorySessionProcessDTO dto = new MemorySessionProcessDTO();
        dto.setAgentId(AGENT_ID);
        dto.setSessionId("session-short-memory");
        MemorySessionMessageDTO user = new MemorySessionMessageDTO();
        user.setRole("user");
        user.setContent("我喜欢跑步");
        dto.setMessages(List.of(user));

        assertEquals(0, memoryService.processSession(dto));
        verify(llmService, never()).generateJson(any(), any());
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
    void clearThenRebuildRejectsNonAddActionBeforeClearingExistingFacts() {
        MemoryFactEntity existing = activeFact("old-1", "用户的旧记忆");
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        String updateAction = """
                {
                  "actions": [
                    {
                      "action": "UPDATE",
                      "related_memory_id": "old-1",
                      "source_role": "user",
                      "evidence_quote": "我的老板是钟文杰",
                      "memory_type": "人物关系",
                      "category": "工作关系",
                      "canonical_text": "用户的老板是钟文杰",
                      "confidence": 0.92,
                      "importance": 0.86,
                      "memory_tier": "long_term",
                      "sensitivity": "normal",
                      "reason": "错误地引用旧事实"
                    }
                  ]
                }
                """;
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID)))
                .thenReturn(updateAction, updateAction);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> memoryService.rebuildFromRecentChatHistory(
                        AGENT_ID, rebuildRequest(false), "user", "tester"));

        assertTrue(error.getMessage().contains("仅允许 ADD 或 NONE"));
        assertEquals("active", existing.getStatus());
        verify(transactionTemplate, never()).execute(any());
        verify(factDao, never()).updateById(any());
        verify(factDao, never()).insert(any());
    }

    @Test
    void clearThenRebuildRejectsUnknownActionBeforeApplyingValidAdd() {
        MemoryFactEntity existing = activeFact("old-1", "用户的旧记忆");
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        String mixedActions = """
                {
                  "actions": [
                    {
                      "action": "ADD",
                      "source_role": "user",
                      "evidence_quote": "我的老板是钟文杰",
                      "canonical_text": "用户的老板是钟文杰"
                    },
                    {
                      "action": "UPSERT",
                      "source_role": "user",
                      "evidence_quote": "我是高瑞瑞",
                      "canonical_text": "用户是高瑞瑞"
                    }
                  ]
                }
                """;
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID)))
                .thenReturn(mixedActions, mixedActions);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> memoryService.rebuildFromRecentChatHistory(
                        AGENT_ID, rebuildRequest(false), "user", "tester"));

        assertTrue(error.getMessage().contains("UPSERT"));
        assertEquals("active", existing.getStatus());
        verify(transactionTemplate, never()).execute(any());
        verify(factDao, never()).updateById(any());
        verify(factDao, never()).insert(any());
    }

    @Test
    void clearThenRebuildThrowsWhenValidatedAddCannotBeApplied() {
        MemoryFactEntity existing = activeFact("old-1", "旧记忆");
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        when(memorySettingsService.activeContextMaxChars()).thenReturn(5);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            try {
                return callback.doInTransaction(mock(TransactionStatus.class));
            } catch (RuntimeException e) {
                existing.setStatus(MemoryHelper.STATUS_ACTIVE);
                throw e;
            }
        }).when(transactionTemplate).execute(any());
        String oversizedAdd = """
                {
                  "actions": [
                    {
                      "action": "ADD",
                      "related_memory_id": "",
                      "source_role": "user",
                      "evidence_quote": "我的老板是钟文杰",
                      "memory_type": "人物关系",
                      "category": "工作关系",
                      "canonical_text": "这是一条超过容量上限的记忆",
                      "confidence": 0.92,
                      "importance": 0.86,
                      "memory_tier": "long_term",
                      "sensitivity": "normal",
                      "reason": "用户明确说明"
                    }
                  ]
                }
                """;
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID)))
                .thenReturn(oversizedAdd, oversizedAdd);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> memoryService.rebuildFromRecentChatHistory(
                        AGENT_ID, rebuildRequest(false), "user", "tester"));

        assertTrue(error.getMessage().contains("已回滚并保留事务开始前的记忆"));
        assertEquals("active", existing.getStatus());
        verify(transactionTemplate, times(1)).execute(any());
        verify(factDao, never()).insert(any());
    }

    @Test
    void clearThenRebuildRollsBackFirstAddWhenSecondAddFails() {
        MemoryFactEntity existing = activeFact("old-1", "旧记忆");
        List<MemoryFactEntity> persistedFacts = new ArrayList<>();
        persistedFacts.add(existing);
        List<Object> persistedRevisions = new ArrayList<>();
        when(factDao.selectList(any(Wrapper.class))).thenAnswer(invocation -> persistedFacts.stream()
                .filter(fact -> MemoryHelper.STATUS_ACTIVE.equals(fact.getStatus()))
                .toList());
        when(factDao.insert(any())).thenAnswer(invocation -> {
            persistedFacts.add(invocation.getArgument(0));
            return 1;
        });
        when(revisionDao.insert(any())).thenAnswer(invocation -> {
            persistedRevisions.add(invocation.getArgument(0));
            return 1;
        });
        when(memorySettingsService.activeContextMaxChars()).thenReturn(12);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            try {
                return callback.doInTransaction(mock(TransactionStatus.class));
            } catch (RuntimeException e) {
                persistedFacts.removeIf(fact -> fact != existing);
                existing.setStatus(MemoryHelper.STATUS_ACTIVE);
                persistedRevisions.clear();
                throw e;
            }
        }).when(transactionTemplate).execute(any());
        String twoAdds = """
                {
                  "actions": [
                    {
                      "action": "ADD",
                      "source_role": "user",
                      "evidence_quote": "我喜欢骑车",
                      "canonical_text": "用户喜欢骑车"
                    },
                    {
                      "action": "ADD",
                      "source_role": "user",
                      "evidence_quote": "我的老板是钟文杰",
                      "canonical_text": "这是一条必然超过容量上限的第二条记忆"
                    }
                  ]
                }
                """;
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID)))
                .thenReturn(twoAdds, twoAdds);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> memoryService.rebuildFromRecentChatHistory(
                        AGENT_ID, rebuildRequest(false), "user", "tester"));

        assertTrue(error.getMessage().contains("已回滚并保留事务开始前的记忆"));
        assertEquals(1, persistedFacts.size());
        assertEquals(existing, persistedFacts.get(0));
        assertEquals(MemoryHelper.STATUS_ACTIVE, existing.getStatus());
        assertTrue(persistedRevisions.isEmpty());
        verify(factDao, times(1)).insert(any());
        verify(transactionTemplate, times(1)).execute(any());
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
                      "source_role": "user",
                      "evidence_quote": "我的老板是钟文杰",
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
        assertEquals(1, result.getFactsAdded());

        InOrder transactionOrder = inOrder(llmService, transactionTemplate);
        transactionOrder.verify(llmService, times(2)).generateJson(any(), eq(MAINTENANCE_MODEL_ID));
        transactionOrder.verify(transactionTemplate).execute(any());

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
    void clearThenRebuildAppliesModelAddWithoutScoreGate() {
        MemoryFactEntity existing = activeFact("old-1", "用户的旧记忆");
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        String lowQualityAdd = """
                {
                  "actions": [
                    {
                      "action": "ADD",
                      "related_memory_id": "",
                      "source_role": "user",
                      "evidence_quote": "我的老板是钟文杰",
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

        assertEquals(1, result.getActionsProposed());
        assertEquals(1, result.getFactsCleared());
        assertEquals(1, result.getFactsChanged());
        verify(factDao).updateById(existing);
        ArgumentCaptor<MemoryFactEntity> factCaptor = ArgumentCaptor.forClass(MemoryFactEntity.class);
        verify(factDao).insert(factCaptor.capture());
        assertEquals("用户刚才随口说了一句话", factCaptor.getValue().getCanonicalText());
    }

    @Test
    void modelSelectedShortTermTierDoesNotCreateFixedExpiry() {
        MemoryFactEntity existing = activeFact("old-1", "用户的旧记忆");
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        String shortTermAdd = """
                {
                  "actions": [
                    {
                      "action": "ADD",
                      "related_memory_id": "",
                      "source_role": "user",
                      "evidence_quote": "我的老板是钟文杰",
                      "memory_type": "阶段性事项",
                      "category": "近期安排",
                      "canonical_text": "用户近期正在准备一次演示",
                      "confidence": 0.88,
                      "importance": 0.72,
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
        assertNull(inserted.getExpiresAt());
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
                              "source_role": "user",
                              "evidence_quote": "我的老板是钟文杰",
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
                              "source_role": "user",
                              "evidence_quote": "我的老板是钟文杰",
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
    void modelRequestedRememberUsesOneMaintenanceDecision() {
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID)))
                .thenReturn("""
                        {
                          "action": "ADD",
                          "related_memory_id": "",
                          "source_role": "user",
                          "evidence_quote": "我的老板是钟文杰",
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
                          "reason": "用户明确说明"
                        }
                        """);

        MemoryRememberDTO dto = new MemoryRememberDTO();
        dto.setAgentId(AGENT_ID);
        dto.setText("我的老板是钟文杰");
        dto.setSpeakerId("voice-1");
        dto.setSpeakerName("用户A");
        memoryService.remember(dto, "user", "tester");

        ArgumentCaptor<MemoryFactEntity> factCaptor = ArgumentCaptor.forClass(MemoryFactEntity.class);
        verify(factDao).insert(factCaptor.capture());
        MemoryFactEntity fact = factCaptor.getValue();
        assertEquals("我的老板是钟文杰", fact.getCanonicalText());
        assertEquals("工作关系", fact.getCategory());
        assertEquals("人物关系", fact.getMemoryType());
        JSONObject payload = JSONUtil.parseObj(fact.getPayload());
        assertEquals("voice-1", payload.getStr("speaker_id"));
        assertEquals("用户A", payload.getStr("speaker_name"));
        verify(llmService, times(1)).generateJson(any(), eq(MAINTENANCE_MODEL_ID));
    }

    @Test
    void modelRequestedRememberSurvivesMaintenanceNoneDecision() {
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID)))
                .thenReturn("{\"action\":\"NONE\",\"reason\":\"模型误判为无需保存\"}");

        MemoryRememberDTO dto = new MemoryRememberDTO();
        dto.setAgentId(AGENT_ID);
        dto.setText("用户每周三晚上学习法语");
        dto.setShared(true);
        memoryService.remember(dto, "system", null);

        ArgumentCaptor<MemoryFactEntity> factCaptor = ArgumentCaptor.forClass(MemoryFactEntity.class);
        verify(factDao).insert(factCaptor.capture());
        assertEquals("用户每周三晚上学习法语", factCaptor.getValue().getCanonicalText());
        verify(llmService, times(1)).generateJson(any(), eq(MAINTENANCE_MODEL_ID));
    }

    @Test
    void modelSelectedSharedMemoryDoesNotKeepPrivateSpeakerOwnership() {
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID)))
                .thenReturn("""
                        {"action":"ADD","memory_type":"待办","category":"协作",
                        "canonical_text":"模型决定供后续多人物对话使用的内容",
                        "confidence":0.9,"importance":0.8,"memory_tier":"short_term"}
                        """);

        MemoryRememberDTO dto = new MemoryRememberDTO();
        dto.setAgentId(AGENT_ID);
        dto.setText("模型决定供后续多人物对话使用的内容");
        dto.setSpeakerId("voice-1");
        dto.setSpeakerName("用户A");
        dto.setShared(true);
        memoryService.remember(dto, "system", null);

        ArgumentCaptor<MemoryFactEntity> factCaptor = ArgumentCaptor.forClass(MemoryFactEntity.class);
        verify(factDao).insert(factCaptor.capture());
        MemoryFactEntity sharedFact = factCaptor.getValue();
        JSONObject payload = JSONUtil.parseObj(sharedFact.getPayload());
        assertTrue(!payload.containsKey("speaker_id"));
        assertTrue(!payload.containsKey("speaker_name"));
        assertNull(sharedFact.getExpiresAt());
    }

    @Test
    void rememberFromNewDeviceCanUpdateExistingFactFromOldDevice() {
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
        dto.setMacAddress(NEW_SCOPE_ID);
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
        verify(llmService, times(1)).generateJson(promptCaptor.capture(), eq(MAINTENANCE_MODEL_ID));
        assertTrue(promptCaptor.getAllValues().get(0).contains("id=fact-1"));
        assertTrue(promptCaptor.getAllValues().get(0).contains("高瑞瑞的领导是钟文杰"));
    }

    @Test
    void rememberFromNewDeviceRefreshesExactOldDeviceFactInsteadOfAddingDuplicate() {
        MemoryFactEntity existing = activeFact("fact-existing", "用户喜欢骑自行车");
        existing.setPayload("{}");
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        when(factDao.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID))).thenReturn("""
                {"action":"ADD","memory_type":"长期记忆","category":"兴趣偏好",
                "canonical_text":"用户喜欢骑自行车","confidence":0.9,"importance":0.8,
                "memory_tier":"long_term"}
                """);

        MemoryRememberDTO dto = new MemoryRememberDTO();
        dto.setAgentId(AGENT_ID);
        dto.setMacAddress(NEW_SCOPE_ID);
        dto.setText("用户喜欢骑自行车");
        dto.setShared(true);
        memoryService.remember(dto, "system", null);

        verify(factDao, never()).insert(any());
        verify(factDao).updateById(existing);
        assertEquals(SCOPE_ID, existing.getScopeId());
        assertEquals("active", existing.getStatus());
    }

    @Test
    void recordEventStoresEvidenceWithoutSchedulingAutomaticMemoryExtraction() {

        MemoryEventDTO dto = new MemoryEventDTO();
        dto.setAgentId(AGENT_ID);
        dto.setMacAddress(SCOPE_ID);
        dto.setSessionId("session-1");
        dto.setEventId("evt-1");
        dto.setChatType(1);
        dto.setContentText("我喜欢自己熬番茄酱配饺子");
        memoryService.recordEvent(dto);

        verify(llmService, never()).generateJson(any(), any());
        verify(factDao, never()).insert(any(MemoryFactEntity.class));
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
    void queryLeavesForgetIntentAndFactsForMainModelWithoutStringMatchingGate() {
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

        assertEquals(2, result.getFacts().size());
        assertEquals(List.of("不想让钟文杰知道自己不想上班"), result.getBlockedPatterns());
        assertTrue(result.getContext().contains("不要再主动记住或使用"));
        verify(factDao, never()).updateById(any());
    }

    @Test
    void maintenanceAddKeepsModelSelectedSensitivityWithoutKeywordOverride() {
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID))).thenReturn("""
                {
                  "action": "ADD",
                  "related_memory_id": "",
                  "source_role": "user",
                  "evidence_quote": "我驾照科目二第6次才通过",
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
                      "source_role": "user",
                      "evidence_quote": "我驾照科目二第6次才通过",
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
        assertEquals("personal", factCaptor.getValue().getSensitivity());
    }

    @Test
    void rememberRejectsUpdateToUnknownMemoryIdWithoutFallingBackToAdd() {
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(factDao.selectOne(any(Wrapper.class))).thenReturn(null);
        String invalidUpdate = """
                {
                  "action": "UPDATE",
                  "related_memory_id": "invented-memory-id",
                  "memory_type": "人物关系",
                  "category": "工作关系",
                  "canonical_text": "用户的老板是钟文杰",
                  "confidence": 0.92,
                  "importance": 0.86,
                  "memory_tier": "long_term",
                  "sensitivity": "normal",
                  "reason": "模型返回了不存在的ID"
                }
                """;
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID)))
                .thenReturn(invalidUpdate)
                .thenReturn("{\"actions\":[" + invalidUpdate + "]}");

        MemoryRememberDTO dto = new MemoryRememberDTO();
        dto.setAgentId(AGENT_ID);
        dto.setText("我的老板是钟文杰");
        assertThrows(IllegalStateException.class,
                () -> memoryService.remember(dto, "user", "tester"));

        verify(factDao, never()).insert(any());
        verify(factDao, never()).updateById(any());
        verify(llmService, never()).generateEmbedding(any(), any());
    }

    @Test
    void maintenanceRejectsAddWhenAgentCapacityWouldExceedLimitWithoutEviction() {
        MemoryFactEntity existing = activeFact("memory-full", "旧".repeat(990));
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        stubToolMaintenanceDecision(addDecision("新".repeat(20), ""));

        assertThrows(IllegalStateException.class,
                () -> runModelRequestedMemory("模型决定的新信息"));

        verify(factDao, never()).insert(any(MemoryFactEntity.class));
        verify(factDao, never()).updateById(any());
    }

    @Test
    void maintenanceArchivesModelSelectedFactBeforeAddingWithinAgentCapacity() {
        MemoryFactEntity important = activeFact("memory-important", "重".repeat(750));
        MemoryFactEntity expendable = activeFact("memory-expendable", "旧".repeat(240));
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(important, expendable));
        stubToolMaintenanceDecision(addDecision("新".repeat(80), "\"memory-expendable\""));

        runModelRequestedMemory("模型决定的更重要信息");

        ArgumentCaptor<MemoryFactEntity> updatedCaptor = ArgumentCaptor.forClass(MemoryFactEntity.class);
        verify(factDao).updateById(updatedCaptor.capture());
        assertEquals("memory-expendable", updatedCaptor.getValue().getMemoryId());
        assertEquals("archived", updatedCaptor.getValue().getStatus());
        assertNotNull(updatedCaptor.getValue().getArchivedAt());
        verify(factDao).insert(any(MemoryFactEntity.class));
    }

    @Test
    void maintenanceRejectsHallucinatedCapacityEvictionWithoutPartialArchive() {
        MemoryFactEntity existing = activeFact("memory-real", "旧".repeat(990));
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        stubToolMaintenanceDecision(addDecision("新".repeat(20), "\"memory-invented\""));

        assertThrows(IllegalStateException.class,
                () -> runModelRequestedMemory("模型决定的新信息"));

        verify(factDao, never()).insert(any(MemoryFactEntity.class));
        verify(factDao, never()).updateById(any());
    }

    @Test
    void maintenanceRejectsInsufficientCapacityEvictionWithoutPartialArchive() {
        MemoryFactEntity large = activeFact("memory-large", "旧".repeat(985));
        MemoryFactEntity tooSmall = activeFact("memory-small", "小".repeat(5));
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(large, tooSmall));
        stubToolMaintenanceDecision(addDecision("新".repeat(20), "\"memory-small\""));

        assertThrows(IllegalStateException.class,
                () -> runModelRequestedMemory("模型决定的容量替换"));

        verify(factDao, never()).insert(any(MemoryFactEntity.class));
        verify(factDao, never()).updateById(any());
        assertEquals("active", tooSmall.getStatus());
    }

    @Test
    void maintenanceUpdateCanEvictAnotherFactButNeverItsOwnTarget() {
        MemoryFactEntity target = activeFact("memory-target", "目".repeat(500));
        MemoryFactEntity expendable = activeFact("memory-expendable", "旧".repeat(490));
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(target, expendable));
        when(factDao.selectOne(any(Wrapper.class))).thenReturn(target, (MemoryFactEntity) null);
        String decision = """
                {"action":"UPDATE","related_memory_id":"memory-target",
                "evict_memory_ids":["memory-expendable"],"memory_type":"长期记忆",
                "category":"动态分类","canonical_text":"%s","confidence":0.9,
                "importance":0.9,"memory_tier":"long_term","sensitivity":"normal",
                "reason":"新事实更完整"}
                """.formatted("新".repeat(600));
        stubToolMaintenanceDecision(decision);

        runModelRequestedMemory("模型决定更新已有记忆");

        assertEquals("active", target.getStatus());
        assertEquals(600, target.getCanonicalText().length());
        assertEquals("archived", expendable.getStatus());
        verify(factDao, times(2)).updateById(any(MemoryFactEntity.class));
    }

    @Test
    void manualCreateAlsoRejectsAgentCapacityOverLimit() {
        when(factDao.selectList(any(Wrapper.class))).thenReturn(
                List.of(activeFact("memory-full", "旧".repeat(995))));
        xiaozhi.modules.memory.dto.MemoryFactDTO dto = new xiaozhi.modules.memory.dto.MemoryFactDTO();
        dto.setCanonicalText("新".repeat(10));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> memoryService.createFact(AGENT_ID, dto, "user", "tester"));
        assertTrue(error.getMessage().contains("1000"));

        verify(factDao, never()).insert(any(MemoryFactEntity.class));
    }

    @Test
    void manualCreateIsStoredWithoutMaintenanceModelAndMarkedManual() {
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of());
        MemoryFactDTO dto = new MemoryFactDTO();
        dto.setCanonicalText("用户每周三晚上学习法语");
        dto.setCategory("手动记录");
        dto.setMemoryType("长期记忆");

        memoryService.createFact(AGENT_ID, dto, "user", "tester");

        ArgumentCaptor<MemoryFactEntity> factCaptor = ArgumentCaptor.forClass(MemoryFactEntity.class);
        verify(factDao).insert(factCaptor.capture());
        MemoryFactEntity fact = factCaptor.getValue();
        assertEquals("用户每周三晚上学习法语", fact.getCanonicalText());
        assertEquals("manual", fact.getConfirmationState());
        verify(llmService, never()).generateJson(any(), any());
    }

    private void stubToolMaintenanceDecision(String decision) {
        when(llmService.generateJson(any(), eq(MAINTENANCE_MODEL_ID)))
                .thenReturn(decision)
                .thenReturn("");
    }

    private String addDecision(String canonicalText, String evictionIds) {
        return """
                {"action":"ADD","related_memory_id":"","evict_memory_ids":[%s],
                "source_role":"user",                "memory_type":"长期记忆","category":"动态分类","canonical_text":"%s",
                "confidence":0.9,"importance":0.9,"memory_tier":"long_term",
                "sensitivity":"normal","reason":"语义价值更高"}
                """.formatted(evictionIds, canonicalText);
    }

    private void runModelRequestedMemory(String content) {
        MemoryRememberDTO dto = new MemoryRememberDTO();
        dto.setAgentId(AGENT_ID);
        dto.setMacAddress(SCOPE_ID);
        dto.setText(content);
        dto.setShared(true);
        memoryService.remember(dto, "system", null);
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
        if (chatType == 1) {
            history.setContent("""
                    {"speaker":"用户A","speaker_id":"voice-history","content":"%s"}
                    """.formatted(content));
        } else {
            history.setContent(content);
        }
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
