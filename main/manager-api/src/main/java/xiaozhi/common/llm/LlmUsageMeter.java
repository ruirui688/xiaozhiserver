package xiaozhi.common.llm;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.modules.billing.dto.Usage;
import xiaozhi.modules.billing.service.BillingUsageRecorder;
import xiaozhi.modules.billing.service.BillingUsageRecorder.InternalUsage;

/** Observes one HTTP attempt; never persists prompts, response content or credentials. */
@Slf4j
@Component
public class LlmUsageMeter {
    private final BillingUsageRecorder recorder;

    private final PlatformJavaExecutor platform;
    public LlmUsageMeter(BillingUsageRecorder recorder, PlatformJavaExecutor platform) {
        this.recorder = recorder; this.platform = platform;
    }

    public <T> ResponseEntity<T> exchange(RestTemplate client, String endpoint,
            HttpEntity<?> request, Class<T> responseType, String modelId, String serviceType) {
        LlmUsageContext.Identity identity = LlmUsageContext.current();
        PlatformJavaExecutor.Call call = platform.prepare(identity, request, modelId, serviceType);
        endpoint = call.endpoint(); request = call.request();
        Instant started = Instant.now();
        String eventId = UUID.randomUUID().toString();
        String responseBody = null;
        String requestId = null;
        boolean successful = false;
        try {
            ResponseEntity<T> response = client.exchange(endpoint, HttpMethod.POST, request, responseType);
            successful = response.getStatusCode().is2xxSuccessful();
            if (response.getBody() instanceof String body) responseBody = body;
            requestId = response.getHeaders().getFirst("x-request-id");
            return response;
        } catch (HttpStatusCodeException error) {
            responseBody = error.getResponseBodyAsString();
            throw error;
        } finally {
            List<Usage> platformUsage = parseUsage(parseObject(responseBody), serviceType);
            platform.finish(call, successful, platformUsage, complete(platformUsage, serviceType), requestId);
            record(call, identity, eventId, modelId, serviceType, endpoint, started,
                    successful, responseBody, requestId);
        }
    }

    private void record(PlatformJavaExecutor.Call call, LlmUsageContext.Identity identity, String eventId, String modelId,
            String serviceType, String endpoint, Instant started, boolean successful,
            String responseBody, String requestId) {
        try {
            Map<?, ?> response = parseObject(responseBody);
            List<Usage> usage = parseUsage(response, serviceType);
            Object responseId = response.get("id");
            if (responseId instanceof String id && id.length() <= 200) requestId = id;
            if (requestId != null && requestId.length() > 200) requestId = null;
            String resolvedModel = modelId != null ? modelId : identity == null ? null : identity.modelId();
            recorder.recordInternal(InternalUsage.builder()
                    .eventId(eventId)
                    .attemptId(call.attemptId()).logicalCallId(call.attemptId())
                    .reservationId(call.permit() == null ? null : call.permit().reservationId())
                    .workspaceId(identity == null ? null : identity.workspaceId())
                    .agentId(identity == null ? null : identity.agentId())
                    .deviceId(identity == null ? null : identity.deviceId())
                    .sessionId(identity == null ? null : identity.sessionId())
                    .modelId(resolvedModel).provider(provider(endpoint)).serviceType(serviceType)
                    .purpose(identity == null ? "BACKGROUND" : identity.purpose())
                    .startedAt(started).endedAt(Instant.now())
                    .status(successful ? "SUCCESS" : "FAILED")
                    .providerRequestId(requestId).usage(usage)
                    .usageComplete(complete(usage, serviceType)).build());
        } catch (RuntimeException error) {
            // Accounting must not cause a successful upstream request to be sent again.
            log.error("Model usage recording failed, errorType={}", error.getClass().getSimpleName());
        }
    }

    static Map<?, ?> parseObject(String body) {
        if (body == null || body.isBlank()) return Map.of();
        try {
            return JSONUtil.parseObj(body);
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    static List<Usage> parseUsage(Map<?, ?> response, String serviceType) {
        if (!(response.get("usage") instanceof Map<?, ?> raw)) return List.of();
        List<Usage> usage = new ArrayList<>();
        add(usage, serviceType, "input_tokens", first(raw, "prompt_tokens", "input_tokens"));
        add(usage, serviceType, "output_tokens", first(raw, "completion_tokens", "output_tokens"));
        add(usage, serviceType, "total_tokens", raw.get("total_tokens"));
        Object cached = raw.get("prompt_cache_hit_tokens");
        if (cached == null && raw.get("prompt_tokens_details") instanceof Map<?, ?> details)
            cached = details.get("cached_tokens");
        if (cached == null && raw.get("input_tokens_details") instanceof Map<?, ?> details)
            cached = details.get("cached_tokens");
        add(usage, serviceType, "cached_input_tokens", cached);
        add(usage, serviceType, "cache_miss_input_tokens", raw.get("prompt_cache_miss_tokens"));
        if (raw.get("completion_tokens_details") instanceof Map<?, ?> details)
            add(usage, serviceType, "reasoning_tokens", details.get("reasoning_tokens"));
        return List.copyOf(usage);
    }

    private static Object first(Map<?, ?> values, String first, String second) {
        return values.get(first) == null ? values.get(second) : values.get(first);
    }

    private static void add(List<Usage> metrics, String type, String name, Object value) {
        if (!(value instanceof Number)) return;
        try {
            BigDecimal quantity = new BigDecimal(value.toString());
            if (quantity.signum() < 0 || quantity.precision() > 20 || quantity.stripTrailingZeros().scale() > 0)
                return;
            metrics.add(Usage.builder().serviceType(type).metric(name).unit("token")
                    .source("PROVIDER").quantity(quantity).build());
        } catch (NumberFormatException ignored) {
            // Missing or malformed usage remains unknown, never zero.
        }
    }

    private static boolean complete(List<Usage> usage, String serviceType) {
        boolean input = usage.stream().anyMatch(item -> "input_tokens".equals(item.getMetric()));
        boolean output = usage.stream().anyMatch(item -> "output_tokens".equals(item.getMetric()));
        return "EMBEDDING".equals(serviceType) ? input : input && output;
    }

    static String provider(String endpoint) {
        try {
            String host = URI.create(endpoint).getHost().toLowerCase(Locale.ROOT);
            if (host.equals("api.deepseek.com")) return "deepseek";
            if (host.endsWith(".volces.com") || host.endsWith(".volcengineapi.com")
                    || host.equals("openspeech.bytedance.com")) return "volcengine";
            return "other";
        } catch (RuntimeException ignored) {
            return "other";
        }
    }
}
