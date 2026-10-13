package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;

import org.mockito.ArgumentCaptor;

import java.util.List;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.MessageSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.TransactionStatus;

import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.agent.dao.AgentVoicePrintDao;
import xiaozhi.modules.agent.dao.AgentVoicePrintSampleDao;
import xiaozhi.modules.agent.dto.AgentVoicePrintSaveDTO;
import xiaozhi.modules.agent.dto.AgentVoicePrintUpdateDTO;
import xiaozhi.modules.agent.entity.AgentVoicePrintEntity;
import xiaozhi.modules.agent.runtime.AgentRuntimeConfigRefreshEvent;
import xiaozhi.modules.agent.dto.IdentifyVoicePrintResponse;
import xiaozhi.modules.agent.service.AgentChatAudioService;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.voiceprint.VoiceprintApiClient;

class AgentVoicePrintServiceImplTest {
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final VoiceprintApiClient remote = mock(VoiceprintApiClient.class);

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
                transactions,
                remote,
                mock(AgentService.class),
                publisher);
    }

    private AgentVoicePrintServiceImpl boundService(AgentVoicePrintDao dao) {
        AgentVoicePrintEntity voice = new AgentVoicePrintEntity();
        voice.setId("voice");
        voice.setAgentId("owner");
        voice.setRemoteUserId("owner");
        when(dao.selectById("voice")).thenReturn(voice);
        when(dao.selectBoundAgentIdsByVoicePrintId("voice"))
                .thenReturn(List.of("role-a", "role-b", "role-a"));
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        AgentVoicePrintServiceImpl service = service();
        ReflectionTestUtils.setField(service, "baseMapper", dao);
        return service;
    }

    @Test
    void deleteNotifiesAllBoundRolesAfterSuccessfulRemoteDeletion() {
        AgentVoicePrintDao dao = mock(AgentVoicePrintDao.class);
        AgentVoicePrintServiceImpl service = boundService(dao);
        when(dao.delete(any(LambdaQueryWrapper.class))).thenReturn(1);
        assertEquals(true, service.delete(1L, "voice"));
        var order = inOrder(dao, remote, publisher);
        order.verify(dao).selectBoundAgentIdsByVoicePrintId("voice");
        order.verify(dao).deleteBindingsByVoicePrintId("voice");
        order.verify(remote).deleteVoiceprint("owner", "voice");
        order.verify(publisher).publishEvent(new AgentRuntimeConfigRefreshEvent(
                List.of("role-a", "role-b", "owner"), "voiceprint_changed"));
    }

    @Test
    void failedRemoteDeletionDoesNotNotify() {
        AgentVoicePrintDao dao = mock(AgentVoicePrintDao.class);
        AgentVoicePrintServiceImpl service = boundService(dao);
        when(dao.delete(any(LambdaQueryWrapper.class))).thenReturn(1);
        doThrow(new RuntimeException("remote failure")).when(remote).deleteVoiceprint("owner", "voice");
        assertThrows(RenException.class, () -> service.delete(1L, "voice"));
        verifyNoInteractions(publisher);
    }

    @Test
    void metadataUpdateNotifiesEveryRoleSharingVoiceprint() {
        AgentVoicePrintDao dao = mock(AgentVoicePrintDao.class);
        AgentVoicePrintServiceImpl service = boundService(dao);
        AgentVoicePrintEntity existing = dao.selectById("voice");
        existing.setWorkspaceId("workspace-a");
        existing.setPersonId("person-a");
        when(dao.updateById(any(AgentVoicePrintEntity.class))).thenReturn(1);
        AgentVoicePrintUpdateDTO dto = new AgentVoicePrintUpdateDTO();
        dto.setId("voice");
        dto.setSourceName("new name");
        assertEquals(true, service.update(1L, dto));
        ArgumentCaptor<AgentVoicePrintEntity> updated = ArgumentCaptor.forClass(AgentVoicePrintEntity.class);
        verify(dao).updateById(updated.capture());
        assertEquals("workspace-a", updated.getValue().getWorkspaceId());
        assertNull(updated.getValue().getPersonId());
        verify(publisher).publishEvent(new AgentRuntimeConfigRefreshEvent(
                List.of("role-a", "role-b", "owner"), "voiceprint_changed"));
        verifyNoInteractions(remote);
    }

    @Test
    void genericUpdatesExcludePersonOwnershipColumn() throws Exception {
        TableField mapping = AgentVoicePrintEntity.class.getDeclaredField("personId").getAnnotation(TableField.class);

        assertEquals(FieldStrategy.NEVER, mapping.updateStrategy());
    }

    @Test
    void unbindingAllVoicesNotifiesTheChangedRole() {
        AgentVoicePrintDao dao = mock(AgentVoicePrintDao.class);
        AgentVoicePrintServiceImpl service = service();
        ReflectionTestUtils.setField(service, "baseMapper", dao);
        service.replaceBindings("role-a", List.of(), 1L);
        verify(dao).deleteBindingsByAgentId("role-a");
        verify(publisher).publishEvent(new AgentRuntimeConfigRefreshEvent(List.of("role-a"), "voiceprint_changed"));
    }
}
