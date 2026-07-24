package xiaozhi.modules.agent.service.biz.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.agent.dto.AgentChatHistoryReportDTO;
import xiaozhi.modules.agent.entity.AgentChatHistoryEntity;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.service.AgentChatAudioService;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.agent.service.AgentChatSummaryService;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.service.impl.WorkspaceAgentQuotaService;
import xiaozhi.modules.device.service.DeviceService;

class AgentChatHistoryBizServiceImplTest {

    @Test
    void savesWorkspaceAndMeasuredBytesWhileDowngradingOversizedAudioToText() {
        Fixture fixture = fixture();
        when(fixture.quotaService.evaluateStorage("agent-a", 3L, 4L))
                .thenReturn(new WorkspaceAgentQuotaService.StorageDecision(true, false, 3L, 100L, 103L));

        assertTrue(fixture.service.report(report()));

        ArgumentCaptor<AgentChatHistoryEntity> captor = ArgumentCaptor.forClass(AgentChatHistoryEntity.class);
        verify(fixture.historyService).save(captor.capture());
        AgentChatHistoryEntity saved = captor.getValue();
        assertEquals("workspace-a", saved.getWorkspaceId());
        assertEquals(3L, saved.getStorageBytes());
        assertNull(saved.getAudioId());
        verify(fixture.audioService, never()).saveAudio(any());
    }

    @Test
    void skipsPersistenceButCompletesReportWhenOneTextRecordExceedsTheEntireLimit() {
        Fixture fixture = fixture();
        when(fixture.quotaService.evaluateStorage("agent-a", 3L, 4L))
                .thenReturn(new WorkspaceAgentQuotaService.StorageDecision(false, false, 0L, 0L, 2L));

        assertTrue(fixture.service.report(report()));

        verify(fixture.historyService, never()).save(any());
        verify(fixture.audioService, never()).saveAudio(any());
        verify(fixture.deviceService).getDeviceByMacAddress("00:11:22:33:44:55");
    }

    @Test
    void storesAudioOnceWhenQuotaAllowsIt() {
        Fixture fixture = fixture();
        when(fixture.quotaService.evaluateStorage("agent-a", 3L, 4L))
                .thenReturn(new WorkspaceAgentQuotaService.StorageDecision(true, true, 7L, 0L, 200L));
        when(fixture.audioService.saveAudio(any())).thenReturn("audio-a");

        assertTrue(fixture.service.report(report()));

        ArgumentCaptor<byte[]> audioCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(fixture.audioService).saveAudio(audioCaptor.capture());
        assertEquals(4, audioCaptor.getValue().length);
        ArgumentCaptor<AgentChatHistoryEntity> historyCaptor = ArgumentCaptor.forClass(AgentChatHistoryEntity.class);
        verify(fixture.historyService).save(historyCaptor.capture());
        assertEquals("audio-a", historyCaptor.getValue().getAudioId());
        assertEquals(7L, historyCaptor.getValue().getStorageBytes());
    }

    @Test
    void fallsBackToTextWhenAudioPersistenceFails() {
        Fixture fixture = fixture();
        when(fixture.quotaService.evaluateStorage("agent-a", 3L, 4L))
                .thenReturn(new WorkspaceAgentQuotaService.StorageDecision(true, true, 7L, 0L, 200L));
        doThrow(new IllegalStateException("database unavailable")).when(fixture.audioService).saveAudio(any());

        assertTrue(fixture.service.report(report()));

        ArgumentCaptor<AgentChatHistoryEntity> historyCaptor = ArgumentCaptor.forClass(AgentChatHistoryEntity.class);
        verify(fixture.historyService).save(historyCaptor.capture());
        assertNull(historyCaptor.getValue().getAudioId());
        assertEquals(3L, historyCaptor.getValue().getStorageBytes());
    }

    private Fixture fixture() {
        AgentService agentService = mock(AgentService.class);
        AgentChatHistoryService historyService = mock(AgentChatHistoryService.class);
        AgentChatAudioService audioService = mock(AgentChatAudioService.class);
        DeviceService deviceService = mock(DeviceService.class);
        WorkspaceAgentQuotaService quotaService = mock(WorkspaceAgentQuotaService.class);
        AgentEntity agent = new AgentEntity();
        agent.setId("agent-a");
        agent.setWorkspaceId("workspace-a");
        agent.setChatHistoryConf(Constant.ChatHistoryConfEnum.RECORD_TEXT_AUDIO.getCode());
        when(agentService.getDefaultAgentByMacAddress("00:11:22:33:44:55")).thenReturn(agent);

        AgentChatHistoryBizServiceImpl service = new AgentChatHistoryBizServiceImpl(
                agentService,
                historyService,
                audioService,
                mock(AgentChatSummaryService.class),
                mock(RedisUtils.class),
                deviceService,
                quotaService);
        return new Fixture(service, historyService, audioService, deviceService, quotaService);
    }

    private AgentChatHistoryReportDTO report() {
        AgentChatHistoryReportDTO report = new AgentChatHistoryReportDTO();
        report.setMacAddress("00:11:22:33:44:55");
        report.setSessionId("session-a");
        report.setChatType((byte) 1);
        report.setContent("abc");
        report.setAudioBase64(Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3, 4 }));
        return report;
    }

    private record Fixture(
            AgentChatHistoryBizServiceImpl service,
            AgentChatHistoryService historyService,
            AgentChatAudioService audioService,
            DeviceService deviceService,
            WorkspaceAgentQuotaService quotaService) {
    }
}
