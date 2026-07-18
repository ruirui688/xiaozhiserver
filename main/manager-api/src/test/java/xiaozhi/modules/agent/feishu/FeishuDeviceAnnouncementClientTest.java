package xiaozhi.modules.agent.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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

import xiaozhi.common.constant.Constant;
import xiaozhi.modules.sys.service.SysParamsService;

class FeishuDeviceAnnouncementClientTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void sendsAnnouncementToAuthenticatedInternalDeviceEndpoint() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SysParamsService sysParamsService = mock(SysParamsService.class);
        Environment environment = mock(Environment.class);
        when(sysParamsService.getValue(Constant.SERVER_SECRET, true)).thenReturn("server-secret");
        when(environment.getProperty("xiaozhi.server.http-base-url"))
                .thenReturn("http://xiaozhi-server:8003/");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("success", true)));
        FeishuDeviceAnnouncementClient client = new FeishuDeviceAnnouncementClient(
                restTemplate, sysParamsService, environment);

        client.broadcast("agent-a", "发布已经完成");

        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("http://xiaozhi-server:8003/device/notifications/broadcast"),
                eq(HttpMethod.POST),
                request.capture(),
                eq(Map.class));
        assertEquals(
                "Bearer server-secret",
                request.getValue().getHeaders().getFirst("Authorization"));
        assertEquals(
                Map.of("agentId", "agent-a", "content", "发布已经完成"),
                request.getValue().getBody());
    }
}
