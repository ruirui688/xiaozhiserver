package xiaozhi.modules.maintenance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.maintenance.dao.MaintenanceConversationDao;
import xiaozhi.modules.maintenance.dao.MaintenanceMessageDao;
import xiaozhi.modules.maintenance.dao.MaintenanceRunDao;
import xiaozhi.modules.maintenance.dto.HarnessConversationMessage;
import xiaozhi.modules.maintenance.dto.MaintenanceConversationUpdateRequest;
import xiaozhi.modules.maintenance.entity.MaintenanceConversationEntity;
import xiaozhi.modules.maintenance.entity.MaintenanceMessageEntity;
import xiaozhi.modules.maintenance.entity.MaintenancePolicyEntity;

class MaintenanceConversationServiceTest {
    @Test
    void userConversationPersistsVisibleMessagesAndAutoTitlesFirstTurn() {
        MaintenanceConversationDao conversations = mock(MaintenanceConversationDao.class);
        MaintenanceMessageDao messages = mock(MaintenanceMessageDao.class);
        MaintenanceConversationService service = new MaintenanceConversationService(
                conversations, messages, mock(MaintenanceRunDao.class));
        MaintenancePolicyEntity policy = new MaintenancePolicyEntity();
        policy.setId("policy-1");
        policy.setWorkspaceId("workspace-1");
        policy.setResourceType("KNOWLEDGE");
        policy.setResourceId("kb-1");

        MaintenanceConversationEntity conversation = service.createUser(policy, "新会话", 7L);
        conversation.setId("conversation-1");
        service.append(conversation, "run-1", "USER", "继续检查最新赛季公告");

        ArgumentCaptor<MaintenanceMessageEntity> message = ArgumentCaptor.forClass(MaintenanceMessageEntity.class);
        verify(messages).insert(message.capture());
        assertEquals("继续检查最新赛季公告", message.getValue().getContent());
        assertEquals("继续检查最新赛季公告", conversation.getTitle());
    }

    @Test
    void deletingConversationWithActiveRunIsRejected() {
        MaintenanceRunDao runs = mock(MaintenanceRunDao.class);
        when(runs.selectCount(any())).thenReturn(1L);
        MaintenanceConversationService service = new MaintenanceConversationService(
                mock(MaintenanceConversationDao.class), mock(MaintenanceMessageDao.class), runs);
        MaintenanceConversationEntity conversation = new MaintenanceConversationEntity();
        conversation.setId("conversation-1");

        RenException error = assertThrows(RenException.class, () -> service.delete(conversation));
        assertEquals("会话仍有运行中的维护任务，暂时不能删除", error.getMessage());
    }

    @Test
    void updateSupportsRenameAndArchiveWithoutRemovingMessages() {
        MaintenanceConversationDao conversations = mock(MaintenanceConversationDao.class);
        MaintenanceMessageDao messages = mock(MaintenanceMessageDao.class);
        when(messages.selectList(any())).thenReturn(List.of());
        MaintenanceConversationService service = new MaintenanceConversationService(
                conversations, messages, mock(MaintenanceRunDao.class));
        MaintenanceConversationEntity conversation = new MaintenanceConversationEntity();
        conversation.setTitle("旧名称");
        conversation.setArchived(0);
        MaintenanceConversationUpdateRequest request = new MaintenanceConversationUpdateRequest();
        request.setTitle("新名称");
        request.setArchived(true);

        service.update(conversation, request);

        assertEquals("新名称", conversation.getTitle());
        assertEquals(1, conversation.getArchived());
        assertEquals(0, service.messages("conversation-1").size());
    }

    @Test
    void conversationLookupRejectsAnotherResource() {
        MaintenanceConversationDao conversations = mock(MaintenanceConversationDao.class);
        MaintenanceConversationEntity conversation = new MaintenanceConversationEntity();
        conversation.setResourceType("KNOWLEDGE");
        conversation.setResourceId("kb-other");
        when(conversations.selectById("conversation-1")).thenReturn(conversation);
        MaintenanceConversationService service = new MaintenanceConversationService(
                conversations, mock(MaintenanceMessageDao.class), mock(MaintenanceRunDao.class));

        RenException error = assertThrows(RenException.class,
                () -> service.require("KNOWLEDGE", "kb-current", "conversation-1"));

        assertEquals("维护会话不存在或不属于当前资源", error.getMessage());
    }

    @Test
    void harnessContextKeepsVisibleMessagesInChronologicalOrder() {
        MaintenanceMessageDao messages = mock(MaintenanceMessageDao.class);
        MaintenanceMessageEntity assistant = visibleMessage("ASSISTANT", "上一轮完整回复");
        MaintenanceMessageEntity user = visibleMessage("USER", "上一轮完整要求");
        when(messages.selectList(any())).thenReturn(List.of(assistant, user));
        MaintenanceConversationService service = new MaintenanceConversationService(
                mock(MaintenanceConversationDao.class), messages, mock(MaintenanceRunDao.class));

        List<HarnessConversationMessage> context = service.contextBeforeRun("conversation-1", "run-current");

        assertEquals(2, context.size());
        assertEquals("USER", context.get(0).getRole());
        assertEquals("上一轮完整要求", context.get(0).getContent());
        assertEquals("ASSISTANT", context.get(1).getRole());
        assertEquals("上一轮完整回复", context.get(1).getContent());
    }

    private MaintenanceMessageEntity visibleMessage(String role, String content) {
        MaintenanceMessageEntity message = new MaintenanceMessageEntity();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
