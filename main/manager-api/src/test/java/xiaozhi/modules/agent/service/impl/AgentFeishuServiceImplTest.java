package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.modules.agent.dao.AgentFeishuConfigDao;
import xiaozhi.modules.agent.dto.AgentFeishuConfigSaveDTO;
import xiaozhi.modules.agent.dto.FeishuRuntimeActionDTO;
import xiaozhi.modules.agent.entity.AgentFeishuConfigEntity;
import xiaozhi.modules.agent.feishu.FeishuOpenApiClient;
import xiaozhi.modules.agent.feishu.FeishuDeviceAnnouncementClient;
import xiaozhi.modules.agent.vo.FeishuChatVO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;

class AgentFeishuServiceImplTest {
    private AgentFeishuConfigDao configDao;
    private CredentialCipher cipher;
    private FeishuOpenApiClient client;
    private FeishuDeviceAnnouncementClient announcementClient;
    private DeviceService deviceService;
    private AgentFeishuServiceImpl service;
    private AgentFeishuConfigEntity config;

    @BeforeEach
    void setUp() {
        configDao = mock(AgentFeishuConfigDao.class);
        cipher = mock(CredentialCipher.class);
        client = mock(FeishuOpenApiClient.class);
        announcementClient = mock(FeishuDeviceAnnouncementClient.class);
        deviceService = mock(DeviceService.class);
        service = new AgentFeishuServiceImpl(
                configDao,
                cipher,
                client,
                announcementClient,
                deviceService,
                new ObjectMapper());
        config = new AgentFeishuConfigEntity();
        config.setId("config-1");
        config.setAgentId("agent-a");
        config.setAppId("cli_test");
        config.setAppSecretCiphertext("ciphertext");
        config.setDefaultChatId("oc_1");
        config.setEnabled(true);
        config.setMeetingReportEnabled(true);
        config.setMessageSendEnabled(false);
        config.setMessageReadEnabled(false);
        config.setMessageBroadcastEnabled(false);
        config.setTaskReadEnabled(false);
        config.setTaskCreateEnabled(false);
        config.setCalendarCreateEnabled(false);
        when(configDao.selectOne(any())).thenReturn(config);
        when(cipher.decrypt("agent-feishu:agent-a", "ciphertext")).thenReturn("app-secret");
        DeviceEntity device = new DeviceEntity();
        device.setAgentId("agent-a");
        when(deviceService.getDeviceByMacAddress("AA:BB:CC:DD:EE:FF")).thenReturn(device);
    }

    @Test
    void preservesStoredSecretWhenSavingUnchangedApp() {
        AgentFeishuConfigSaveDTO dto = new AgentFeishuConfigSaveDTO();
        dto.setAppId("cli_test");
        dto.setAppSecret("");
        dto.setEnabled(true);

        service.saveConfig("agent-a", dto, 7L);

        verify(cipher, never()).encrypt(any(), any());
        verify(configDao).updateById(config);
        assertEquals("ciphertext", config.getAppSecretCiphertext());
    }

    @Test
    void newBindingDoesNotPresetRuntimeOrMeetingActions() {
        when(configDao.selectOne(any())).thenReturn(null);
        when(cipher.encrypt("agent-feishu:agent-a", "app-secret")).thenReturn("new-ciphertext");
        AgentFeishuConfigSaveDTO dto = new AgentFeishuConfigSaveDTO();
        dto.setAppId("cli_test");
        dto.setAppSecret("app-secret");

        service.saveConfig("agent-a", dto, 7L);

        ArgumentCaptor<AgentFeishuConfigEntity> captor = ArgumentCaptor.forClass(AgentFeishuConfigEntity.class);
        verify(configDao).insert(captor.capture());
        AgentFeishuConfigEntity saved = captor.getValue();
        assertFalse(saved.getEnabled());
        assertFalse(saved.getMeetingReportEnabled());
    }

    @Test
    void rejectsGroupActionsWithoutExplicitDefaultChat() {
        AgentFeishuConfigSaveDTO dto = new AgentFeishuConfigSaveDTO();
        dto.setAppId("cli_test");
        dto.setMessageSendEnabled(true);

        RenException error = assertThrows(
                RenException.class,
                () -> service.saveConfig("agent-a", dto, 7L));

        assertTrue(error.getMessage().contains("默认飞书群聊"));
        verify(configDao, never()).updateById(any());
    }

    @Test
    void taskOnlyConfigurationDoesNotRequireDefaultChat() {
        AgentFeishuConfigSaveDTO dto = new AgentFeishuConfigSaveDTO();
        dto.setAppId("cli_test");
        dto.setTaskCreateEnabled(true);

        service.saveConfig("agent-a", dto, 7L);

        verify(configDao).updateById(config);
        assertTrue(config.getTaskCreateEnabled());
    }

    @Test
    void rejectsBroadcastWithoutVerificationToken() {
        AgentFeishuConfigSaveDTO dto = new AgentFeishuConfigSaveDTO();
        dto.setAppId("cli_test");
        dto.setDefaultChatId("oc_1");
        dto.setMessageBroadcastEnabled(true);

        RenException error = assertThrows(
                RenException.class,
                () -> service.saveConfig("agent-a", dto, 7L));

        assertTrue(error.getMessage().contains("Verification Token"));
        verify(configDao, never()).updateById(any());
    }

    @Test
    void runtimeCapabilitiesExposeOnlyExplicitlyEnabledActions() {
        config.setMessageReadEnabled(true);

        Map<String, Object> capabilities = service.getRuntimePluginConfig("agent-a");

        assertEquals(true, capabilities.get("list_messages"));
        assertEquals(false, capabilities.get("send_message"));
        assertEquals(false, capabilities.get("create_task"));
    }

    @Test
    void rejectsRuntimeMessageWhenAgentDidNotEnableSending() {
        FeishuRuntimeActionDTO dto = new FeishuRuntimeActionDTO();
        dto.setAction("send_message");
        dto.setContent("不应发送");
        dto.setMacAddress("AA:BB:CC:DD:EE:FF");

        assertThrows(RenException.class, () -> service.executeRuntimeAction("agent-a", dto));
        verify(client, never()).sendText(any(), any(), any(), any());
    }

    @Test
    void meetingReportUsesIndependentAutomaticPermission() {
        FeishuRuntimeActionDTO dto = new FeishuRuntimeActionDTO();
        dto.setAction("publish_meeting_report");
        dto.setMacAddress("AA:BB:CC:DD:EE:FF");
        dto.setTitle("会议纪要：评审");
        dto.setContent("结论：通过");
        when(client.sendMeetingReport(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("message_id", "om_1"));

        Map<String, Object> result = service.executeRuntimeAction("agent-a", dto);

        assertEquals("om_1", result.get("message_id"));
        verify(client).sendMeetingReport(
                eq("cli_test"), eq("app-secret"), eq("oc_1"), eq("会议纪要：评审"), eq("结论：通过"));
    }

    @Test
    void disabledBindingDoesNotRegisterRuntimeTool() {
        config.setEnabled(false);
        assertFalse(service.getRuntimePluginConfig("agent-a").containsKey("enabled"));
    }

    @Test
    void disabledBindingCanStillListChatsForConfiguration() {
        config.setEnabled(false);
        when(client.listChats("cli_test", "app-secret"))
                .thenReturn(List.of(new FeishuChatVO("oc_1", "小智测试")));

        assertEquals(List.of(new FeishuChatVO("oc_1", "小智测试")), service.listChats("agent-a"));
        verify(client).listChats("cli_test", "app-secret");
    }

    @Test
    void disabledBindingCanStillSendConfigurationTestMessage() {
        config.setEnabled(false);
        when(client.sendText("cli_test", "app-secret", "oc_1", "小智智能体飞书连接测试成功。"))
                .thenReturn(Map.of("message_id", "om_1"));

        assertEquals("om_1", service.sendTestMessage("agent-a").get("message_id"));
        verify(client).sendText("cli_test", "app-secret", "oc_1", "小智智能体飞书连接测试成功。");
    }

    @Test
    void disabledBindingStillRejectsRuntimeActions() {
        config.setEnabled(false);
        FeishuRuntimeActionDTO dto = new FeishuRuntimeActionDTO();
        dto.setAction("publish_meeting_report");
        dto.setMacAddress("AA:BB:CC:DD:EE:FF");
        dto.setContent("不应发送");

        assertThrows(RenException.class, () -> service.executeRuntimeAction("agent-a", dto));
        verify(client, never()).sendMeetingReport(any(), any(), any(), any(), any());
    }

    @Test
    void meetingOnlyBindingDoesNotRegisterGeneralPurposeTool() {
        assertEquals(Map.of(), service.getRuntimePluginConfig("agent-a"));
    }

    @Test
    void rejectsRuntimeActionFromDeviceBoundToAnotherAgent() {
        FeishuRuntimeActionDTO dto = new FeishuRuntimeActionDTO();
        dto.setAction("publish_meeting_report");
        dto.setMacAddress("11:22:33:44:55:66");
        dto.setContent("不应跨智能体发送");

        assertThrows(RenException.class, () -> service.executeRuntimeAction("agent-a", dto));
        verify(client, never()).sendMeetingReport(any(), any(), any(), any(), any());
    }

    @Test
    void validatesFeishuChallengeWithEncryptedVerificationToken() {
        config.setEventVerificationTokenCiphertext("event-ciphertext");
        when(cipher.decrypt("agent-feishu-event:agent-a", "event-ciphertext"))
                .thenReturn("verify-token");

        Map<String, Object> result = service.handleEvent(
                "agent-a",
                Map.of("type", "url_verification", "token", "verify-token", "challenge", "challenge-1"));

        assertEquals(Map.of("challenge", "challenge-1"), result);
    }

    @Test
    void broadcastsNewTextMessageFromConfiguredChat() {
        config.setMessageBroadcastEnabled(true);
        config.setEventVerificationTokenCiphertext("event-ciphertext");
        when(cipher.decrypt("agent-feishu-event:agent-a", "event-ciphertext"))
                .thenReturn("verify-token");
        Map<String, Object> payload = Map.of(
                "header", Map.of(
                        "token", "verify-token",
                        "event_type", "im.message.receive_v1",
                        "event_id", "event-1",
                        "app_id", "cli_test"),
                "event", Map.of(
                        "sender", Map.of("sender_type", "user"),
                        "message", Map.of(
                                "chat_id", "oc_1",
                                "message_type", "text",
                                "content", "{\"text\":\"发布已经完成\"}")));

        Map<String, Object> result = service.handleEvent("agent-a", payload);

        assertEquals(true, result.get("accepted"));
        verify(announcementClient).broadcast("agent-a", "发布已经完成");
    }
}
