package xiaozhi.modules.agent.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.modules.agent.vo.FeishuChatVO;

class FeishuOpenApiClientTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void authenticatesAndListsBotChatsWithoutPuttingSecretInUrl() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        FeishuOpenApiClient client = new FeishuOpenApiClient(restTemplate, new ObjectMapper());
        when(restTemplate.postForEntity(
                eq(FeishuOpenApiClient.API_ROOT + "/auth/v3/tenant_access_token/internal"),
                any(),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "code", 0,
                        "tenant_access_token", "tenant-token",
                        "expire", 7200)));
        when(restTemplate.exchange(
                eq(URI.create(FeishuOpenApiClient.API_ROOT + "/im/v1/chats?page_size=100")),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "code", 0,
                        "data", Map.of("items", List.of(Map.of("chat_id", "oc_1", "name", "项目群"))))));

        List<FeishuChatVO> chats = client.listChats("cli_test", "app-secret");

        assertEquals(List.of(new FeishuChatVO("oc_1", "项目群")), chats);
        ArgumentCaptor<Map> tokenBody = ArgumentCaptor.forClass(Map.class);
        verify(restTemplate).postForEntity(
                eq(FeishuOpenApiClient.API_ROOT + "/auth/v3/tenant_access_token/internal"),
                tokenBody.capture(),
                eq(Map.class));
        assertEquals("app-secret", tokenBody.getValue().get("app_secret"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void sendsMessageToConfiguredChatWithBearerToken() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        FeishuOpenApiClient client = new FeishuOpenApiClient(restTemplate, new ObjectMapper());
        when(restTemplate.postForEntity(any(String.class), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "code", 0,
                        "tenant_access_token", "tenant-token",
                        "expire", 7200)));
        when(restTemplate.exchange(
                eq(URI.create(FeishuOpenApiClient.API_ROOT + "/im/v1/messages?receive_id_type=chat_id")),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("code", 0, "data", Map.of("message_id", "om_1"))));

        Map<String, Object> result = client.sendText("cli_test", "app-secret", "oc_1", "测试消息");

        assertEquals("om_1", result.get("message_id"));
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                any(URI.class), eq(HttpMethod.POST), entity.capture(), eq(Map.class));
        assertEquals("Bearer tenant-token", entity.getValue().getHeaders().getFirst("Authorization"));
        Map<String, Object> body = (Map<String, Object>) entity.getValue().getBody();
        assertEquals("oc_1", body.get("receive_id"));
        assertEquals("text", body.get("msg_type"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void listsOnlyBoundedTaskFields() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        FeishuOpenApiClient client = new FeishuOpenApiClient(restTemplate, new ObjectMapper());
        when(restTemplate.postForEntity(any(String.class), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "code", 0,
                        "tenant_access_token", "tenant-token",
                        "expire", 7200)));
        when(restTemplate.exchange(
                eq(URI.create(FeishuOpenApiClient.API_ROOT + "/task/v2/tasks?page_size=20")),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "code", 0,
                        "data", Map.of("items", List.of(Map.of(
                                "guid", "task-1",
                                "summary", "完成回归",
                                "description", "执行完整测试",
                                "due", Map.of("timestamp", "1784300000000")))))));

        List<Map<String, Object>> tasks = client.listTasks("cli_test", "app-secret", 20);

        assertEquals(1, tasks.size());
        assertEquals("task-1", tasks.get(0).get("guid"));
        assertEquals("完成回归", tasks.get(0).get("summary"));
    }
}
