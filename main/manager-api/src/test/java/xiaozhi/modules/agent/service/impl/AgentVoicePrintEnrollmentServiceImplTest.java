package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.agent.dao.AgentVoicePrintEnrollmentDao;
import xiaozhi.modules.agent.dao.AgentVoicePrintEnrollmentSampleDao;
import xiaozhi.modules.agent.dto.AgentVoicePrintEnrollmentSampleDTO;
import xiaozhi.modules.agent.dto.AgentVoicePrintSaveDTO;
import xiaozhi.modules.agent.entity.AgentVoicePrintEnrollmentEntity;
import xiaozhi.modules.agent.entity.AgentVoicePrintEnrollmentSampleEntity;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.agent.service.AgentChatAudioService;
import xiaozhi.modules.agent.service.AgentVoicePrintService;
import xiaozhi.modules.sys.service.SysParamsService;

class AgentVoicePrintEnrollmentServiceImplTest {
    private AgentVoicePrintEnrollmentDao enrollmentDao;
    private AgentVoicePrintEnrollmentSampleDao sampleDao;
    private AgentVoicePrintService voicePrintService;
    private AgentChatHistoryService chatHistoryService;
    private AgentChatAudioService chatAudioService;
    private SysParamsService sysParamsService;
    private Environment environment;
    private AgentVoicePrintEnrollmentServiceImpl service;

    @BeforeEach
    void setUp() {
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(any(String.class), any(Object[].class), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ReflectionTestUtils.setField(MessageUtils.class, "messageSource", messageSource);

        enrollmentDao = mock(AgentVoicePrintEnrollmentDao.class);
        sampleDao = mock(AgentVoicePrintEnrollmentSampleDao.class);
        voicePrintService = mock(AgentVoicePrintService.class);
        chatHistoryService = mock(AgentChatHistoryService.class);
        chatAudioService = mock(AgentChatAudioService.class);
        sysParamsService = mock(SysParamsService.class);
        environment = mock(Environment.class);
        service = new AgentVoicePrintEnrollmentServiceImpl(
                sampleDao,
                voicePrintService,
                chatHistoryService,
                chatAudioService,
                sysParamsService,
                environment);
        ReflectionTestUtils.setField(service, "baseMapper", enrollmentDao);
    }

    @Test
    void completeRegistersVoicePrintWithCollectedAudioIds() {
        AgentVoicePrintEnrollmentEntity collecting = enrollment("enroll-a", "agent-a",
                AgentVoicePrintEnrollmentServiceImpl.STATUS_COLLECTING);
        AgentVoicePrintEnrollmentEntity completed = enrollment("enroll-a", "agent-a",
                AgentVoicePrintEnrollmentServiceImpl.STATUS_COMPLETED);
        completed.setVoicePrintId("voice-a");
        when(enrollmentDao.selectById("enroll-a")).thenReturn(collecting, completed);
        when(sampleDao.selectList(any(Wrapper.class))).thenReturn(List.of(sample("audio-a"), sample("audio-b")));
        when(enrollmentDao.update(any(), any(Wrapper.class))).thenReturn(1);
        when(voicePrintService.insertAndReturnId(any(AgentVoicePrintSaveDTO.class))).thenReturn("voice-a");

        service.complete("enroll-a");

        ArgumentCaptor<AgentVoicePrintSaveDTO> captor = ArgumentCaptor.forClass(AgentVoicePrintSaveDTO.class);
        verify(voicePrintService).insertAndReturnId(captor.capture());
        assertEquals("agent-a", captor.getValue().getAgentId());
        assertEquals("用户A", captor.getValue().getSourceName());
        assertEquals(List.of("audio-a", "audio-b"), captor.getValue().getAudioIds());
    }

    @Test
    void completeRegistersVoicePrintWithSingleCollectedAudioId() {
        AgentVoicePrintEnrollmentEntity collecting = enrollment("enroll-a", "agent-a",
                AgentVoicePrintEnrollmentServiceImpl.STATUS_COLLECTING);
        collecting.setRequiredSamples(1);
        AgentVoicePrintEnrollmentEntity completed = enrollment("enroll-a", "agent-a",
                AgentVoicePrintEnrollmentServiceImpl.STATUS_COMPLETED);
        completed.setVoicePrintId("voice-a");
        when(enrollmentDao.selectById("enroll-a")).thenReturn(collecting, completed);
        when(sampleDao.selectList(any(Wrapper.class))).thenReturn(List.of(sample("audio-a")));
        when(enrollmentDao.update(any(), any(Wrapper.class))).thenReturn(1);
        when(voicePrintService.insertAndReturnId(any(AgentVoicePrintSaveDTO.class))).thenReturn("voice-a");

        service.complete("enroll-a");

        ArgumentCaptor<AgentVoicePrintSaveDTO> captor = ArgumentCaptor.forClass(AgentVoicePrintSaveDTO.class);
        verify(voicePrintService).insertAndReturnId(captor.capture());
        assertEquals(List.of("audio-a"), captor.getValue().getAudioIds());
    }

    @Test
    void completeRejectsInsufficientSamplesBeforeRegistering() {
        when(enrollmentDao.selectById("enroll-a")).thenReturn(enrollment("enroll-a", "agent-a",
                AgentVoicePrintEnrollmentServiceImpl.STATUS_COLLECTING));
        when(sampleDao.selectList(any(Wrapper.class))).thenReturn(List.of(sample("audio-a")));

        RenException error = assertThrows(RenException.class, () -> service.complete("enroll-a"));

        assertEquals(ErrorCode.VOICEPRINT_SAMPLE_COUNT_INVALID, error.getCode());
    }

    @Test
    void addSampleRejectsCompletedEnrollment() {
        when(enrollmentDao.selectById("enroll-a")).thenReturn(enrollment("enroll-a", "agent-a",
                AgentVoicePrintEnrollmentServiceImpl.STATUS_COMPLETED));
        AgentVoicePrintEnrollmentSampleDTO dto = new AgentVoicePrintEnrollmentSampleDTO();
        dto.setAudioId("audio-a");

        RenException error = assertThrows(RenException.class, () -> service.addSample("enroll-a", dto));

        assertEquals(ErrorCode.INVALID_SERVER_ACTION, error.getCode());
    }

    @Test
    void addSampleRejectsAudioOutsideAgent() {
        when(enrollmentDao.selectById("enroll-a")).thenReturn(enrollment("enroll-a", "agent-a",
                AgentVoicePrintEnrollmentServiceImpl.STATUS_COLLECTING));
        when(chatHistoryService.isAudioOwnedByAgent("audio-a", "agent-a")).thenReturn(false);
        AgentVoicePrintEnrollmentSampleDTO dto = new AgentVoicePrintEnrollmentSampleDTO();
        dto.setAudioId("audio-a");

        RenException error = assertThrows(RenException.class, () -> service.addSample("enroll-a", dto));

        assertEquals(ErrorCode.VOICEPRINT_AUDIO_NOT_BELONG_AGENT, error.getCode());
    }

    private AgentVoicePrintEnrollmentEntity enrollment(String id, String agentId, String status) {
        AgentVoicePrintEnrollmentEntity entity = new AgentVoicePrintEnrollmentEntity();
        entity.setId(id);
        entity.setAgentId(agentId);
        entity.setSourceName("用户A");
        entity.setIntroduce("测试声纹");
        entity.setRequiredSamples(2);
        entity.setStatus(status);
        return entity;
    }

    private AgentVoicePrintEnrollmentSampleEntity sample(String audioId) {
        AgentVoicePrintEnrollmentSampleEntity sample = new AgentVoicePrintEnrollmentSampleEntity();
        sample.setAudioId(audioId);
        return sample;
    }
}
