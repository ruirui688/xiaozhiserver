package xiaozhi.modules.reminder.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.constant.Constant;
import xiaozhi.modules.sys.service.SysParamsService;

class ReminderDeviceNotificationClientTest {
    @Test
    void sendsMacAddressWithCanonicalRequestIdAndContentFields() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SysParamsService params = mock(SysParamsService.class);
        Environment environment = mock(Environment.class);
        when(params.getValue(Constant.SERVER_SECRET, true)).thenReturn("test-server-secret");
        when(environment.getProperty("xiaozhi.server.http-base-url"))
                .thenReturn("http://voice-service:8003/");
        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"data\":{\"status\":\"queued\"}}"));
        ReminderDeviceNotificationClient client = new ReminderDeviceNotificationClient(
                restTemplate, new ObjectMapper(), params, environment);

        var result = client.deliver(
                "reminder-db-id", "AA:BB:CC", "agent-current", "现在该开会了");

        assertTrue(result.delivered());
        assertEquals("queued", result.status());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("http://voice-service:8003/device/notifications/deliver"),
                eq(HttpMethod.POST),
                entity.capture(),
                eq(String.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) entity.getValue().getBody();
        assertEquals("reminder-db-id", body.get("requestId"));
        assertEquals("AA:BB:CC", body.get("deviceId"));
        assertEquals("agent-current", body.get("agentId"));
        assertEquals("现在该开会了", body.get("content"));
        assertEquals(4, body.size());
        assertFalse(body.containsKey("eventId"));
        assertFalse(body.containsKey("interrupt"));
        assertFalse(body.containsKey("text"));
    }

    @Test
    void parsesPreciseDataStatusWithoutTrustingUnknownResponseText() {
        assertTrue(ReminderDeviceNotificationClient.classify("duplicate").delivered());
        assertEquals("device_busy",
                ReminderDeviceNotificationClient.classify("device_busy").status());
        assertEquals("call_active",
                ReminderDeviceNotificationClient.classify("call_active").status());
        assertEquals("unexpected_status",
                ReminderDeviceNotificationClient.classify("private upstream details").status());
    }
}
