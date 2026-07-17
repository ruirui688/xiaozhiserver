package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xiaozhi.common.exception.RenException;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.modules.agent.dao.AgentFeishuConfigDao;
import xiaozhi.modules.agent.dto.AgentFeishuConfigSaveDTO;
import xiaozhi.modules.agent.dto.FeishuRuntimeActionDTO;
import xiaozhi.modules.agent.entity.AgentFeishuConfigEntity;
import xiaozhi.modules.agent.feishu.FeishuOpenApiClient;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;

class AgentFeishuServiceImplTest {
    private AgentFeishuConfigDao configDao;
    private CredentialCipher cipher;
    private FeishuOpenApiClient client;
    private DeviceService deviceService;
    private AgentFeishuServiceImpl service;
    private AgentFeishuConfigEntity config;

    @BeforeEach
    void setUp() {
        configDao = mock(AgentFeishuConfigDao.class);
        cipher = mock(CredentialCipher.class);
        client = mock(FeishuOpenApiClient.class);
        deviceService = mock(DeviceService.class);
        service = new AgentFeishuServiceImpl(configDao, cipher, client, deviceService);
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
}
