package xiaozhi.modules.model.service.impl.tester;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import xiaozhi.modules.model.dto.ModelTestResultDTO;
import xiaozhi.modules.model.service.impl.benchmark.PythonBenchmarkClient;

@Component
@RequiredArgsConstructor
public class QwenOmniRealtimeModelTester implements ModelTestExecutor {

    private static final String TEST_PROMPT = "请只回复 OK";
    private static final int MAX_TIMEOUT_SECONDS = 330;
    private static final int TIMEOUT_GRACE_SECONDS = 10;
    private static final Pattern WORKSPACE_HOST = Pattern.compile(
            "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\."
                    + "(?:cn-beijing|ap-southeast-1)\\.maas\\.aliyuncs\\.com$",
            Pattern.CASE_INSENSITIVE);

    private final PythonBenchmarkClient benchmarkClient;

    @Override
    public boolean supports(String modelType, String providerType) {
        return "LLM".equalsIgnoreCase(modelType)
                && "qwen_omni_realtime".equalsIgnoreCase(providerType);
    }

    @Override
    public ModelTestResultDTO test(JSONObject configJson, String providerType) {
        String validationError = validateConfig(configJson);
        if (StringUtils.isNotBlank(validationError)) {
            return failed(validationError);
        }

        String modelName = firstNonBlank(configJson, "model_name", "model");
        ModelTestResultDTO result = new ModelTestResultDTO();
        result.setTestInput("供应器类型：" + providerType
                + "\n模型：" + modelName
                + "\n会话模式：realtime"
                + "\n测试文本：" + TEST_PROMPT
                + "\n测试方式：调用 xiaozhi-server 建立真实 DashScope Realtime WebSocket 并完成一轮响应");
        try {
            JSONObject payload = buildPayload(configJson, providerType, modelName);
            long started = System.currentTimeMillis();
            JSONObject response = benchmarkClient.runRuntimeTest(
                    payload, Duration.ofSeconds(resolveTimeoutSeconds(configJson)));
            result.setResponseTimeMs(System.currentTimeMillis() - started);
            return applyResponse(result, response);
        } catch (Exception error) {
            String message = "Qwen Omni Realtime 真实推理测试失败：" + sanitizeError(
                    StringUtils.defaultIfBlank(error.getMessage(), error.getClass().getSimpleName()));
            result.setSuccess(false);
            result.setMessage(message);
            result.setTestOutput(message);
            return result;
        }
    }

    public static int resolveTimeoutSeconds(JSONObject config) {
        int connectTimeout = Math.max(1, Math.min(config.getInt("connect_timeout_seconds", 10), 60));
        int responseTimeout = Math.max(1, Math.min(config.getInt("response_timeout_seconds", 120), 300));
        return Math.min(MAX_TIMEOUT_SECONDS, connectTimeout + responseTimeout + TIMEOUT_GRACE_SECONDS);
    }

    private String validateConfig(JSONObject config) {
        if (!hasUsableValue(config, "api_key")) {
            return "Qwen Omni Realtime 缺少有效 api_key";
        }
        if (!hasUsableValue(config, "base_url")) {
            return "Qwen Omni Realtime 缺少 base_url";
        }
        if (!hasUsableValue(config, "model_name")) {
            return "Qwen Omni Realtime 缺少 model_name";
        }
        if (!"realtime".equalsIgnoreCase(config.getStr("conversation_mode"))) {
            return "Qwen Omni Realtime 的 conversation_mode 必须为 realtime";
        }
        return validateEndpoint(config.getStr("base_url"));
    }

    private String validateEndpoint(String baseUrl) {
        try {
            URI uri = URI.create(StringUtils.trimToEmpty(baseUrl));
            String host = StringUtils.lowerCase(StringUtils.defaultString(uri.getHost()));
            boolean officialHost = "dashscope.aliyuncs.com".equals(host)
                    || WORKSPACE_HOST.matcher(host).matches();
            boolean valid = "wss".equalsIgnoreCase(uri.getScheme())
                    && officialHost
                    && "/api-ws/v1/realtime".equals(StringUtils.removeEnd(uri.getPath(), "/"))
                    && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && StringUtils.isBlank(uri.getQuery())
                    && StringUtils.isBlank(uri.getFragment());
            return valid ? "" : "Qwen Omni Realtime 的 base_url 仅允许阿里云百炼官方 DashScope 地址，"
                    + "或北京/新加坡地域的业务空间 Realtime 地址";
        } catch (IllegalArgumentException error) {
            return "Qwen Omni Realtime 的 base_url 格式无效，仅允许阿里云百炼官方 DashScope Realtime 地址";
        }
    }

    private JSONObject buildPayload(JSONObject config, String providerType, String modelName) {
        JSONObject benchmarkCase = new JSONObject()
                .set("case_id", "single_realtime_llm_test")
                .set("scenario", "单模型 Realtime 真实推理测试")
                .set("turns", List.of(TEST_PROMPT))
                .set("expected_behavior", "模型应通过真实 Realtime 会话返回非空文本或有效音频，不得只完成握手。");
        JSONObject model = new JSONObject()
                .set("id", "single-realtime-llm-test")
                .set("model_name", modelName)
                .set("model_type", "LLM")
                .set("provider_type", providerType)
                .set("config", config);
        return new JSONObject()
                .set("task_id", "single-realtime-llm-test-" + UUID.randomUUID())
                .set("model_type", "LLM")
                .set("iterations", 1)
                .set("concurrency", 1)
                .set("benchmark_cases", List.of(benchmarkCase))
                .set("models", List.of(model));
    }

    private ModelTestResultDTO applyResponse(ModelTestResultDTO result, JSONObject response) {
        JSONObject benchmarkResult = firstJsonObject(response.getJSONArray("results"));
        if (benchmarkResult == null) {
            result.setSuccess(false);
            result.setMessage("Realtime 真实推理测试没有返回结果");
            result.setTestOutput(response.toStringPretty());
            return result;
        }
        JSONObject sample = firstJsonObject(benchmarkResult.getJSONArray("samples"));
        if (sample == null) {
            result.setSuccess(false);
            result.setMessage(StringUtils.defaultIfBlank(
                    benchmarkResult.getStr("error_summary"), "Realtime 真实推理测试没有返回样本"));
            result.setTestOutput(benchmarkResult.toStringPretty());
            return result;
        }

        Long latencyMs = sample.getLong("latency_ms");
        if (latencyMs != null) {
            result.setResponseTimeMs(latencyMs);
        }
        result.setFirstTextMs(sample.getLong("ttft_ms"));
        result.setFirstAudioMs(sample.getLong("first_audio_ms"));
        String output = extractOutput(sample);
        long audioBytes = extractAudioBytes(sample);
        result.setTestOutput(StringUtils.isNotBlank(output)
                ? output
                : audioBytes > 0 ? "Realtime 返回有效音频：" + audioBytes + " bytes" : sample.toStringPretty());
        boolean success = Boolean.TRUE.equals(sample.getBool("success"))
                && Boolean.TRUE.equals(sample.getBool("measured"))
                && (StringUtils.isNotBlank(output) || audioBytes > 0);
        result.setSuccess(success);
        result.setMessage(success
                ? "Qwen Omni Realtime 真实推理测试通过"
                : StringUtils.defaultIfBlank(sample.getStr("message"), "Realtime 会话未返回有效文本或音频"));
        return result;
    }

    private static String extractOutput(JSONObject sample) {
        JSONObject detail = sample.getJSONObject("output_detail");
        String output = detail == null ? "" : firstNonBlank(detail,
                "assistant_text", "transcript", "text", "audio_transcript");
        return StringUtils.defaultIfBlank(output, sample.getStr("output_summary"));
    }

    private static long extractAudioBytes(JSONObject sample) {
        JSONObject detail = sample.getJSONObject("output_detail");
        Long value = detail == null ? null : detail.getLong("audio_bytes");
        value = value == null ? sample.getLong("audio_bytes") : value;
        return value == null ? 0 : value;
    }

    private static JSONObject firstJsonObject(JSONArray values) {
        return values == null || values.isEmpty() ? null : values.getJSONObject(0);
    }

    private static String firstNonBlank(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.getStr(key);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private static boolean hasUsableValue(JSONObject config, String key) {
        String value = StringUtils.trimToEmpty(config.getStr(key));
        String lower = value.toLowerCase();
        return StringUtils.isNotBlank(value)
                && !lower.contains("你的")
                && !lower.contains("placeholder")
                && !lower.contains("your_")
                && !lower.contains("sk-xxx")
                && !lower.contains("xxxx")
                && !lower.contains("***");
    }

    private static ModelTestResultDTO failed(String message) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        result.setSuccess(false);
        result.setMessage(message);
        result.setTestOutput(message);
        return result;
    }

    private static String sanitizeError(String message) {
        return StringUtils.defaultString(message)
                .replaceAll("(?i)Bearer\\s+[^\\s,;}]+", "Bearer ***")
                .replaceAll("(?i)(api[_-]?key|authorization|access[_-]?token|secret)(\\s*[:=]\\s*)[^\\s,;}]+", "$1$2***");
    }
}
