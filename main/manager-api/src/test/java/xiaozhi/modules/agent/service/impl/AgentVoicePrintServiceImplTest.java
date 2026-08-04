package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.agent.dao.AgentVoicePrintDao;
import xiaozhi.modules.agent.dao.AgentVoicePrintSampleDao;
import xiaozhi.modules.agent.dto.AgentVoicePrintSaveDTO;
import xiaozhi.modules.agent.dto.IdentifyVoicePrintResponse;
import xiaozhi.modules.agent.service.AgentChatAudioService;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.voiceprint.VoiceprintApiClient;

class AgentVoicePrintServiceImplTest {

    @BeforeEach
    void configureMessages() {
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(any(String.class), any(Object[].class), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ReflectionTestUtils.setField(MessageUtils.class, "messageSource", messageSource);
    }

    @Test
    void rejectsSixthVoiceprintBeforeReadingAudioOrCallingRemoteService() {
        AgentVoicePrintDao dao = mock(AgentVoicePrintDao.class);
        when(dao.countBindingsByAgentId("agent-a")).thenReturn(5L);

        AgentVoicePrintServiceImpl service = service();
        ReflectionTestUtils.setField(service, "baseMapper", dao);

        AgentVoicePrintSaveDTO dto = new AgentVoicePrintSaveDTO();
        dto.setAgentId("agent-a");
        dto.setAudioId("audio-a");
        dto.setSourceName("用户A");

        RenException error = assertThrows(RenException.class, () -> service.insert(dto));
        assertEquals(ErrorCode.AGENT_VOICEPRINT_LIMIT_REACHED, error.getCode());
    }

    @Test
    void duplicateCheckTrustsExplicitUnmatchedResponse() {
        IdentifyVoicePrintResponse response = new IdentifyVoicePrintResponse();
        response.setMatched(false);
        response.setSpeakerId("voice-existing");
        response.setScore(0.99);

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(
                service(),
                "rejectDuplicateVoiceprint",
                response,
                null));
    }

    private AgentVoicePrintServiceImpl service() {
        return new AgentVoicePrintServiceImpl(
                mock(AgentChatAudioService.class),
                mock(AgentChatHistoryService.class),
                mock(AgentVoicePrintSampleDao.class),
                mock(TransactionTemplate.class),
                mock(VoiceprintApiClient.class),
                mock(AgentService.class));
    }
}
