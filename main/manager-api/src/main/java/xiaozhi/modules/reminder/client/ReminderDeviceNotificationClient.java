package xiaozhi.modules.reminder.client;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.constant.Constant;
import xiaozhi.modules.sys.service.SysParamsService;

@Component
public class ReminderDeviceNotificationClient {
    static final String DELIVERY_PATH = "/device/notifications/deliver";
    private static final Set<String> DELIVERED_STATUSES = Set.of("queued", "duplicate");
    private static final Set<String> RETRYABLE_STATUSES = Set.of(
            "offline",
            "busy",
            "device_busy",
            "meeting_active",
            "voiceprint_enrollment_active",
            "tts_unavailable",
            "unavailable");
    private static final Pattern SAFE_STATUS = Pattern.compile("[a-z0-9_]{1,64}");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final SysParamsService sysParamsService;
    private final Environment environment;

    @Autowired
    public ReminderDeviceNotificationClient(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            SysParamsService sysParamsService,
            Environment environment) {
        this(
                restTemplateBuilder
                        .connectTimeout(Duration.ofSeconds(2))
                        .readTimeout(Duration.ofSeconds(5))
                        .build(),
                objectMapper,
                sysParamsService,
                environment);
    }

    ReminderDeviceNotificationClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            SysParamsService sysParamsService,
            Environment environment) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.sysParamsService = sysParamsService;
        this.environment = environment;
    }

    public DeliveryResult deliver(
            String reminderId,
            String macAddress,
            String agentId,
            String spokenText) {
        String serverSecret = sysParamsService.getValue(Constant.SERVER_SECRET, true);
        if (StringUtils.isBlank(serverSecret) || StringUtils.equalsIgnoreCase(serverSecret, "null")) {
            return DeliveryResult.retry("server_secret_missing");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(serverSecret);
        Map<String, Object> payload = Map.of(
                "requestId", reminderId,
                "deviceId", macAddress,
                "agentId", agentId,
                "content", spokenText);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    resolveXiaozhiServerHttpBaseUrl() + DELIVERY_PATH,
                    HttpMethod.POST,
                    new HttpEntity<>(payload, headers),
                    String.class);
            return parse(response.getBody());
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 429) return DeliveryResult.retry("overloaded");
            DeliveryResult reported = parse(error.getResponseBodyAsString());
            if ("delivery_uncertain".equals(reported.status()) || "request_conflict".equals(reported.status())) return reported;
            return DeliveryResult.retry("http_" + error.getStatusCode().value());
        } catch (Exception error) {
            return DeliveryResult.retry("network_error");
        }
    }

    DeliveryResult parse(String body) {
        if (StringUtils.isBlank(body)) {
            return DeliveryResult.retry("empty_response");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            String status = data.path("status").asText(null);
            if (StringUtils.isBlank(status)) {
                status = root.path("status").asText(null);
            }
            return classify(status);
        } catch (Exception error) {
            return DeliveryResult.retry("invalid_response");
        }
    }

    static DeliveryResult classify(String rawStatus) {
        String status = StringUtils.lowerCase(StringUtils.trim(rawStatus));
        if (DELIVERED_STATUSES.contains(status)) {
            return DeliveryResult.delivered(status);
        }
        if (RETRYABLE_STATUSES.contains(status)) {
            return DeliveryResult.retry(status);
        }
        // The Python bridge may add new bounded machine-readable busy reasons.
        // Preserve such codes for diagnostics without ever storing response text.
        if (status != null && SAFE_STATUS.matcher(status).matches()) {
            return DeliveryResult.retry(status);
        }
        return DeliveryResult.retry("unexpected_status");
    }

    private String resolveXiaozhiServerHttpBaseUrl() {
        String configured = environment.getProperty("xiaozhi.server.http-base-url");
        if (StringUtils.isNotBlank(configured)) {
            return StringUtils.removeEnd(configured.trim(), "/");
        }
        String value = sysParamsService.getValue("server.http_port", true);
        int port = StringUtils.isNumeric(value) ? Integer.parseInt(value) : 8003;
        return "http://127.0.0.1:" + port;
    }

    public record DeliveryResult(boolean delivered, String status) {
        static DeliveryResult delivered(String status) {
            return new DeliveryResult(true, status);
        }

        static DeliveryResult retry(String status) {
            return new DeliveryResult(false, status);
        }
    }
}
