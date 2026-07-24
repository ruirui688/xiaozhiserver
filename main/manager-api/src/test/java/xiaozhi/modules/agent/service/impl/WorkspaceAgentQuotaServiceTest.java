package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.dao.AiAgentChatHistoryDao;
import xiaozhi.modules.agent.entity.AgentChatHistoryEntity;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.workspace.dao.WorkspaceDao;

class WorkspaceAgentQuotaServiceTest {

    @Test
    void rejectsAgentCreationWhenCurrentWorkspaceReachedConfiguredLimit() {
        SysParamsService paramsService = mock(SysParamsService.class);
        WorkspaceDao workspaceDao = mock(WorkspaceDao.class);
        AgentDao agentDao = mock(AgentDao.class);
        WorkspaceAgentQuotaService service = service(paramsService, workspaceDao, agentDao,
                mock(AiAgentChatHistoryDao.class));

        when(workspaceDao.lockByIdForAgentQuota("workspace-a")).thenReturn("workspace-a");
        when(paramsService.getValue(Constant.WORKSPACE_AGENT_MAX_COUNT, true)).thenReturn("10");
        when(agentDao.selectCount(any(Wrapper.class))).thenReturn(10L);

        RenException error = assertThrows(RenException.class,
                () -> service.requireCanCreateAgent("workspace-a"));

        assertTrue(error.getMessage().contains("10"));
        verify(workspaceDao).lockByIdForAgentQuota("workspace-a");
    }

    @Test
    void storageDecisionEvictsOldestRecordsAndKeepsTheNewestAudio() {
        SysParamsService paramsService = mock(SysParamsService.class);
        AgentDao agentDao = mock(AgentDao.class);
        AiAgentChatHistoryDao historyDao = mock(AiAgentChatHistoryDao.class);
        AgentChatAudioCleanupService cleanupService = mock(AgentChatAudioCleanupService.class);
        WorkspaceAgentQuotaService service = service(paramsService, mock(WorkspaceDao.class), agentDao, historyDao,
                cleanupService);

        long limitBytes = 1024L * 1024L;
        AgentChatHistoryEntity oldest = history(1L, 6L, "audio-1", "session-1");
        AgentChatHistoryEntity secondOldest = history(2L, 5L, null, "session-1");

        when(agentDao.lockByIdForStorageQuota("agent-a")).thenReturn("agent-a");
        when(paramsService.getValue(Constant.WORKSPACE_AGENT_STORAGE_LIMIT_MB, true)).thenReturn("1");
        when(historyDao.getStorageUsageBytes("agent-a")).thenReturn(limitBytes - 5L, limitBytes - 16L);
        when(historyDao.getOldestForStorageEviction("agent-a", 100))
                .thenReturn(List.of(oldest, secondOldest));
        when(historyDao.deleteByStorageEvictionIds(List.of(1L, 2L))).thenReturn(2);

        WorkspaceAgentQuotaService.StorageDecision decision = service.evaluateStorage("agent-a", 5L, 8L);

        assertTrue(decision.storeRecord());
        assertTrue(decision.storeAudio());
        assertEquals(13L, decision.recordBytes());
        assertEquals(limitBytes - 16L, decision.usedBytes());
        verify(historyDao).deleteByStorageEvictionIds(List.of(1L, 2L));
        verify(cleanupService).scheduleAfterCommit(List.of("audio-1"));
    }

    @Test
    void storageDecisionOnlyRejectsTextThatExceedsTheEntireConfiguredCapacity() {
        SysParamsService paramsService = mock(SysParamsService.class);
        AgentDao agentDao = mock(AgentDao.class);
        AiAgentChatHistoryDao historyDao = mock(AiAgentChatHistoryDao.class);
        AgentChatAudioCleanupService cleanupService = mock(AgentChatAudioCleanupService.class);
        WorkspaceAgentQuotaService service = service(paramsService, mock(WorkspaceDao.class), agentDao, historyDao,
                cleanupService);

        when(agentDao.lockByIdForStorageQuota("agent-a")).thenReturn("agent-a");
        when(paramsService.getValue(Constant.WORKSPACE_AGENT_STORAGE_LIMIT_MB, true)).thenReturn("1");
        when(historyDao.getStorageUsageBytes("agent-a")).thenReturn(0L);

        WorkspaceAgentQuotaService.StorageDecision decision = service.evaluateStorage(
                "agent-a", 1024L * 1024L + 1L, 0L);

        assertFalse(decision.storeRecord());
        assertEquals(0L, decision.recordBytes());
        verify(historyDao, never()).getOldestForStorageEviction("agent-a", 100);
        verify(cleanupService, never()).scheduleAfterCommit(any());
    }

    @Test
    void validatesQuotaParametersAsPositiveIntegers() {
        WorkspaceAgentQuotaService service = service(mock(SysParamsService.class), mock(WorkspaceDao.class),
                mock(AgentDao.class), mock(AiAgentChatHistoryDao.class));

        assertThrows(RenException.class,
                () -> service.validateParameterChange(Constant.WORKSPACE_AGENT_MAX_COUNT, "0"));
        assertThrows(RenException.class,
                () -> service.validateParameterChange(Constant.WORKSPACE_AGENT_STORAGE_LIMIT_MB, "1.5"));
        service.validateParameterChange(Constant.WORKSPACE_AGENT_MAX_COUNT, "25");
    }

    private WorkspaceAgentQuotaService service(SysParamsService paramsService, WorkspaceDao workspaceDao,
            AgentDao agentDao, AiAgentChatHistoryDao historyDao) {
        return service(paramsService, workspaceDao, agentDao, historyDao, mock(AgentChatAudioCleanupService.class));
    }

    private WorkspaceAgentQuotaService service(SysParamsService paramsService, WorkspaceDao workspaceDao,
            AgentDao agentDao, AiAgentChatHistoryDao historyDao, AgentChatAudioCleanupService cleanupService) {
        return new WorkspaceAgentQuotaService(paramsService, workspaceDao, agentDao, historyDao, cleanupService);
    }

    private AgentChatHistoryEntity history(Long id, Long storageBytes, String audioId, String sessionId) {
        AgentChatHistoryEntity history = new AgentChatHistoryEntity();
        history.setId(id);
        history.setStorageBytes(storageBytes);
        history.setAudioId(audioId);
        history.setSessionId(sessionId);
        return history;
    }
}
