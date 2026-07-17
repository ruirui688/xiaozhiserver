package xiaozhi.modules.agent.feishu;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.vo.FeishuChatVO;

@Component
@Slf4j
public class FeishuOpenApiClient {
    static final String API_ROOT = "https://open.feishu.cn/open-apis";
    private static final int TOKEN_EXPIRY_SKEW_SECONDS = 120;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, TokenEntry> tokenCache = new ConcurrentHashMap<>();

    public FeishuOpenApiClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public List<FeishuChatVO> listChats(String appId, String appSecret) {
        Map<String, Object> data = request(
                appId,
                appSecret,
                HttpMethod.GET,
                URI.create(API_ROOT + "/im/v1/chats?page_size=100"),
                null);
        List<FeishuChatVO> result = new ArrayList<>();
        for (Map<String, Object> item : mapList(data.get("items"))) {
            String chatId = text(item.get("chat_id"));
            if (StringUtils.isNotBlank(chatId)) {
                result.add(new FeishuChatVO(chatId, text(item.get("name"))));
            }
        }
        return result;
    }

    public Map<String, Object> sendText(String appId, String appSecret, String chatId, String text) {
        require(chatId, "尚未选择默认飞书群聊");
        require(text, "发送内容不能为空");
        return sendMessage(appId, appSecret, chatId, "text", json(Map.of("text", text)));
    }

    public Map<String, Object> sendMeetingReport(
            String appId, String appSecret, String chatId, String title, String summary) {
        require(chatId, "尚未选择默认飞书群聊");
        require(summary, "会议纪要内容不能为空");
        Map<String, Object> localized = new LinkedHashMap<>();
        localized.put("title", StringUtils.defaultIfBlank(title, "会议纪要"));
        localized.put("content", List.of(List.of(Map.of("tag", "text", "text", summary))));
        return sendMessage(appId, appSecret, chatId, "post", json(Map.of("zh_cn", localized)));
    }

    public List<Map<String, Object>> listMessages(
            String appId, String appSecret, String chatId, int pageSize) {
        require(chatId, "尚未选择默认飞书群聊");
        URI uri = UriComponentsBuilder.fromUriString(API_ROOT + "/im/v1/messages")
                .queryParam("container_id_type", "chat")
                .queryParam("container_id", chatId)
                .queryParam("sort_type", "ByCreateTimeDesc")
                .queryParam("page_size", Math.max(1, Math.min(pageSize, 20)))
                .build(true)
                .toUri();
        Map<String, Object> data = request(appId, appSecret, HttpMethod.GET, uri, null);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : mapList(data.get("items"))) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("messageId", text(item.get("message_id")));
            message.put("createTime", text(item.get("create_time")));
            message.put("messageType", text(item.get("msg_type")));
            Map<String, Object> body = map(item.get("body"));
            message.put("content", truncate(text(body.get("content")), 4000));
            result.add(message);
        }
        return result;
    }

    public Map<String, Object> createTask(
            String appId,
            String appSecret,
            String summary,
            String description,
            Long dueTimestamp,
            String assigneeOpenId) {
        require(summary, "任务标题不能为空");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("summary", summary);
        if (StringUtils.isNotBlank(description)) {
            body.put("description", description);
        }
        if (dueTimestamp != null) {
            body.put("due", Map.of("timestamp", String.valueOf(normalizeMillis(dueTimestamp)), "is_all_day", false));
        }
        if (StringUtils.isNotBlank(assigneeOpenId)) {
            body.put("members", List.of(Map.of("id", assigneeOpenId, "type", "user", "role", "assignee")));
        }
        return request(appId, appSecret, HttpMethod.POST, URI.create(API_ROOT + "/task/v2/tasks"), body);
    }

    public List<Map<String, Object>> listTasks(String appId, String appSecret, int pageSize) {
        URI uri = UriComponentsBuilder.fromUriString(API_ROOT + "/task/v2/tasks")
                .queryParam("page_size", Math.max(1, Math.min(pageSize, 50)))
                .build(true)
                .toUri();
        Map<String, Object> data = request(appId, appSecret, HttpMethod.GET, uri, null);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : mapList(data.get("items"))) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("guid", text(item.get("guid")));
            task.put("summary", truncate(text(item.get("summary")), 500));
            task.put("description", truncate(text(item.get("description")), 2000));
            task.put("completedAt", text(item.get("completed_at")));
            task.put("due", map(item.get("due")));
            result.add(task);
        }
        return result;
    }

    public Map<String, Object> createCalendarEvent(
            String appId,
            String appSecret,
            String calendarId,
            String summary,
            String description,
            Long startTimestamp,
            Long endTimestamp,
            String timezone) {
        require(calendarId, "尚未配置飞书日历ID");
        require(summary, "日程标题不能为空");
        if (startTimestamp == null || endTimestamp == null || endTimestamp <= startTimestamp) {
            throw new RenException("日程开始和结束时间无效");
        }
        String zone = StringUtils.defaultIfBlank(timezone, "Asia/Shanghai");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("summary", summary);
        body.put("description", StringUtils.defaultString(description));
        body.put("start_time", Map.of("timestamp", String.valueOf(normalizeSeconds(startTimestamp)), "timezone", zone));
        body.put("end_time", Map.of("timestamp", String.valueOf(normalizeSeconds(endTimestamp)), "timezone", zone));
        body.put("need_notification", true);
        URI uri = UriComponentsBuilder.fromUriString(API_ROOT + "/calendar/v4/calendars/{calendarId}/events")
                .buildAndExpand(calendarId)
                .encode()
                .toUri();
        return request(appId, appSecret, HttpMethod.POST, uri, body);
    }

    private Map<String, Object> sendMessage(
            String appId, String appSecret, String chatId, String messageType, String content) {
        URI uri = URI.create(API_ROOT + "/im/v1/messages?receive_id_type=chat_id");
        return request(appId, appSecret, HttpMethod.POST, uri, Map.of(
                "receive_id", chatId,
                "msg_type", messageType,
                "content", content));
    }

    private Map<String, Object> request(
            String appId, String appSecret, HttpMethod method, URI uri, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantToken(appId, appSecret));
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    uri, method, new HttpEntity<>(body, headers), Map.class);
            Map<String, Object> payload = map(response.getBody());
            assertSuccess(payload);
            return map(payload.get("data"));
        } catch (RenException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("飞书开放平台请求失败: operation={}, errorType={}", operation(uri), e.getClass().getSimpleName());
            throw new RenException("飞书开放平台请求失败，请检查网络和应用权限", e);
        }
    }

    private String tenantToken(String appId, String appSecret) {
        require(appId, "飞书App ID不能为空");
        require(appSecret, "飞书App Secret不能为空");
        String cacheKey = appId + ":" + fingerprint(appSecret);
        TokenEntry cached = tokenCache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(TOKEN_EXPIRY_SKEW_SECONDS))) {
            return cached.token();
        }
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    API_ROOT + "/auth/v3/tenant_access_token/internal",
                    Map.of("app_id", appId, "app_secret", appSecret),
                    Map.class);
            Map<String, Object> payload = map(response.getBody());
            assertSuccess(payload);
            String token = text(payload.get("tenant_access_token"));
            require(token, "飞书未返回tenant_access_token");
            long expireSeconds = number(payload.get("expire"), 7200L);
            tokenCache.keySet().removeIf(key -> key.startsWith(appId + ":") && !key.equals(cacheKey));
            tokenCache.put(cacheKey, new TokenEntry(token, Instant.now().plusSeconds(expireSeconds)));
            return token;
        } catch (RenException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("获取飞书tenant_access_token失败: errorType={}", e.getClass().getSimpleName());
            throw new RenException("飞书应用认证失败，请检查App ID、App Secret和网络", e);
        }
    }

    private void assertSuccess(Map<String, Object> payload) {
        long code = number(payload.get("code"), -1L);
        if (code != 0L) {
            String message = truncate(text(payload.get("msg")), 300);
            throw new RenException("飞书接口返回错误(" + code + "): " + StringUtils.defaultIfBlank(message, "未知错误"));
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RenException("飞书消息序列化失败", e);
        }
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return new HashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> source)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : source) {
            if (item instanceof Map<?, ?>) {
                result.add(map(item));
            }
        }
        return result;
    }

    private static String fingerprint(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64Url.encode(hash, 12);
        } catch (Exception e) {
            throw new RenException("飞书凭据指纹计算失败", e);
        }
    }

    private static void require(String value, String message) {
        if (StringUtils.isBlank(value)) {
            throw new RenException(message);
        }
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long normalizeMillis(long timestamp) {
        return timestamp < 10_000_000_000L ? timestamp * 1000L : timestamp;
    }

    private static long normalizeSeconds(long timestamp) {
        return timestamp > 10_000_000_000L ? timestamp / 1000L : timestamp;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private static String operation(URI uri) {
        String path = uri == null ? "" : uri.getPath();
        int index = path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    private record TokenEntry(String token, Instant expiresAt) {
    }

    private static final class Base64Url {
        private static String encode(byte[] bytes, int length) {
            String encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            return encoded.substring(0, Math.min(length, encoded.length()));
        }
    }
}
