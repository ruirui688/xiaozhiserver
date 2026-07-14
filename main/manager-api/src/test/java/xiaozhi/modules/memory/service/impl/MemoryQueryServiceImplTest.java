package xiaozhi.modules.memory.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.memory.dao.MemoryFactDao;
import xiaozhi.modules.memory.dao.MemoryForgetTombstoneDao;
import xiaozhi.modules.memory.dao.MemoryProfileDao;
import xiaozhi.modules.memory.dao.MemoryToolBehaviorDao;
import xiaozhi.modules.memory.dto.MemoryQueryDTO;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.service.MemorySettingsService;
import xiaozhi.modules.memory.vo.MemoryQueryVO;

class MemoryQueryServiceImplTest {

    private static final String AGENT_ID = "agent-1";
    private static final String MEMORY_MODEL_ID = "memory-model-1";

    private final MemoryFactDao factDao = mock(MemoryFactDao.class);
    private final MemoryProfileDao profileDao = mock(MemoryProfileDao.class);
    private final MemoryToolBehaviorDao toolBehaviorDao = mock(MemoryToolBehaviorDao.class);
    private final MemoryForgetTombstoneDao tombstoneDao = mock(MemoryForgetTombstoneDao.class);
    private final DeviceDao deviceDao = mock(DeviceDao.class);
    private final AgentDao agentDao = mock(AgentDao.class);
    private final MemorySettingsService memorySettingsService = mock(MemorySettingsService.class);

    private MemoryQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MemoryQueryServiceImpl(
                factDao,
                profileDao,
                toolBehaviorDao,
                tombstoneDao,
                deviceDao,
                agentDao,
                memorySettingsService);

        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT_ID);
        agent.setMemModelId(MEMORY_MODEL_ID);
        when(agentDao.selectById(AGENT_ID)).thenReturn(agent);

        when(tombstoneDao.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(toolBehaviorDao.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(memorySettingsService.activeContextMaxChars()).thenReturn(1000);
    }

    @Test
    void queryReturnsAllVisibleFactsWithoutCallingRecallModel() {
        MemoryFactEntity workTime = fact("memory-work-time", "用户每天工作时间为8:30至18:00");
        MemoryFactEntity location = fact("memory-location", "用户在常州新北区上班");
        MemoryFactEntity hobby = fact("memory-hobby", "用户喜欢骑自行车");
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(workTime, location, hobby));

        MemoryQueryDTO dto = query("我几点可以下班？", 2);
        MemoryQueryVO result = service.query(dto);

        assertEquals(List.of("memory-work-time", "memory-location", "memory-hobby"),
                result.getFacts().stream().map(MemoryFactEntity::getMemoryId).toList());
        assertTrue(result.getContext().contains("用户每天工作时间为8:30至18:00"));
        assertTrue(result.getContext().contains("用户在常州新北区上班"));
        assertTrue(result.getContext().contains("用户喜欢骑自行车"));
        assertTrue(result.getDebugSignals().contains("memory_full_context_facts:3"));
        verify(memorySettingsService).activeContextMaxChars();
    }

    @Test
    void emptyCandidatesReturnNoFactsWithoutRecall() {
        when(factDao.selectList(any(Wrapper.class))).thenReturn(
                List.of());

        MemoryQueryVO result = service.query(query("我几点下班？", 8));

        assertTrue(result.getFacts().isEmpty());
        assertTrue(result.getDebugSignals().contains("memory_full_context_no_active_facts"));
    }

    @Test
    void blankQueryAlsoReturnsAllVisibleFacts() {
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(
                fact("memory-1", "事实一"),
                fact("memory-2", "事实二")));

        MemoryQueryVO result = service.query(query("  ", 1));

        assertEquals(List.of("memory-1", "memory-2"),
                result.getFacts().stream().map(MemoryFactEntity::getMemoryId).toList());
        assertTrue(result.getDebugSignals().contains("memory_full_context_facts:2"));
    }

    @Test
    void runtimeMemoryContextNeverExceedsConfiguredCharacters() {
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(
                fact("memory-large", "记".repeat(2500))));

        MemoryQueryVO result = service.query(query("", 8));

        assertTrue(result.getContext().length() <= 1000);
        assertTrue(result.getContext().endsWith("归档或合并低价值记忆】"));
        assertTrue(result.getDebugSignals().contains("memory_full_context_truncated"));
    }

    @Test
    void voiceprintSpeakerOnlyReceivesOwnAndSharedFacts() {
        MemoryFactEntity shared = fact("memory-shared", "所有人都可以使用的共享信息");
        MemoryFactEntity userA = fact("memory-a", "用户A喜欢骑自行车");
        userA.setPayload("{\"speaker_id\":\"voice-a\",\"speaker_name\":\"用户A\"}");
        MemoryFactEntity userB = fact("memory-b", "用户B喜欢游泳");
        userB.setPayload("{\"speaker_id\":\"voice-b\",\"speaker_name\":\"用户B\"}");
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(userB, shared, userA));

        MemoryQueryDTO dto = query("", 8);
        dto.setSpeakerId("voice-a");
        dto.setSpeakerName("用户A");
        MemoryQueryVO result = service.query(dto);

        assertEquals(List.of("memory-shared", "memory-a"),
                result.getFacts().stream().map(MemoryFactEntity::getMemoryId).toList());
        assertTrue(result.getContext().contains("【用户A的长期记忆】"));
        assertTrue(!result.getContext().contains("用户B喜欢游泳"));
    }

    private MemoryQueryDTO query(String text, int limit) {
        MemoryQueryDTO dto = new MemoryQueryDTO();
        dto.setAgentId(AGENT_ID);
        dto.setQuery(text);
        dto.setLimit(limit);
        return dto;
    }

    private MemoryFactEntity fact(String memoryId, String text) {
        MemoryFactEntity fact = new MemoryFactEntity();
        fact.setMemoryId(memoryId);
        fact.setCategory("动态分类");
        fact.setCanonicalText(text);
        fact.setStatus("active");
        return fact;
    }
}
