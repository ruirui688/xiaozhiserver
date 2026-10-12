package xiaozhi.modules.model.service.impl.tester;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import xiaozhi.common.llm.LlmUsageMeter;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.dto.ModelTestResultDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.impl.ModelRuntimeAssembler;

@Component
@RequiredArgsConstructor
public class IntentModelTester implements ModelTestExecutor {

    private static final String TEST_TEXT = "你好啊";
    private static final String EXPECTED_FUNCTION = "continue_chat";

    private final ModelConfigDao modelConfigDao;
    private final ModelRuntimeAssembler modelRuntimeAssembler;
    private final RestTemplate restTemplate;

    @Autowired
    private LlmUsageMeter usageMeter;

    @Override
    public boolean supports(String modelType, String providerType) {
        if (!"INTENT".equalsIgnoreCase(modelType)) {
            return false;
        }
        String normalizedType = StringUtils.lowerCase(StringUtils.defaultString(providerType));
        return "nointent".equals(normalizedType)
                || "function_call".equals(normalizedType)
                || "intent_llm".equals(normalizedType);
    }

    @Override
    public ModelTestResultDTO test(JSONObject configJson, String providerType) {
        String normalizedType = StringUtils.lowerCase(StringUtils.defaultString(providerType));
        if ("nointent".equals(normalizedType)) {
            return buildLocalResult(normalizedType, "无意图识别配置有效，用户输入会直接进入主聊天。");
        }
        if ("function_call".equals(normalizedType)) {
            return buildLocalResult(normalizedType, "函数调用意图配置有效，将由主 LLM 的工具调用能力选择函数。");
        }
        if (!"intent_llm".equals(normalizedType)) {
            throw new IllegalArgumentException("不支持的意图模型测试类型：" + normalizedType);
        }
        return testIntentLlm(configJson);
    }

    private ModelTestResultDTO testIntentLlm(JSONObject configJson) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String llmModelId = configJson.getStr("llm");
        if (StringUtils.isBlank(llmModelId)) {
            result.setSuccess(false);
            result.setMessage("外挂大模型意图识别缺少关联 LLM，无法执行真实意图分类测试");
            result.setTestInput("关联 LLM：未配置\n测试文本：" + TEST_TEXT);
            result.setTestOutput(result.getMessage());
            return result;
        }

        ModelConfigEntity llm = modelConfigDao.selectById(llmModelId);
        if (llm == null) {
            result.setSuccess(false);
            result.setMessage("关联 LLM 不存在：" + llmModelId);
            result.setTestInput("关联 LLM：" + llmModelId + "\n测试文本：" + TEST_TEXT);
            result.setTestOutput(result.getMessage());
            return result;
        }

        String llmModelType = StringUtils.defaultString(llm.getModelType()).toUpperCase();
        if (!"LLM".equals(llmModelType) && !"SLM".equals(llmModelType)) {
            result.setSuccess(false);
            result.setMessage("外挂大模型意图识别只能关联 LLM 或 SLM，当前类型：" + llm.getModelType());
            result.setTestInput("关联 LLM：" + llmModelId + "\n测试文本：" + TEST_TEXT);
            result.setTestOutput(result.getMessage());
            return result;
        }

        JSONObject runtimeConfig = modelRuntimeAssembler.buildTypedConfig(llm);
        if (runtimeConfig == null) {
            result.setSuccess(false);
            result.setMessage("关联 LLM 配置为空：" + llmModelId);
            result.setTestInput("关联 LLM：" + llmModelId + "\n测试文本：" + TEST_TEXT);
            result.setTestOutput(result.getMessage());
            return result;
        }

        String llmType = StringUtils.lowerCase(runtimeConfig.getStr("type"));
        if ("openai".equals(llmType)) {
            return testOpenAi(runtimeConfig, llmModelId);
        }
        if ("ollama".equals(llmType)) {
            return testOllama(runtimeConfig, llmModelId);
        }

        result.setSuccess(false);
        result.setMessage("关联 LLM 类型暂不支持真实意图分类测试：" + StringUtils.defaultIfBlank(llmType, "-"));
        result.setTestInput("关联 LLM：" + llmModelId
                + "\n供应器类型：" + StringUtils.defaultIfBlank(llmType, "-")
                + "\n测试文本：" + TEST_TEXT);
        result.setTestOutput("当前单模型测试只支持通过 OpenAI 兼容或 Ollama 接口执行外挂大模型意图识别真实调用。");
        return result;
    }

    private ModelTestResultDTO buildLocalResult(String type, String message) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        result.setSuccess(true);
        result.setMessage(message);
        result.setTestInput("模型类型：INTENT\n供应器类型：" + type + "\n测试内容：本地运行时配置校验");
        result.setTestOutput(message);
        return result;
    }

    private ModelTestResultDTO testOpenAi(JSONObject configJson, String llmModelId) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String baseUrl = configJson.getStr("base_url");
        String model = configJson.getStr("model_name");
        String apiKey = StringUtils.defaultIfBlank(configJson.getStr("api_key"), configJson.getStr("api_password"));
        String missingField = describeOpenAiMissingField(baseUrl, model, apiKey);
        if (missingField != null) {
            result.setSuccess(false);
            result.setMessage(missingField);
            result.setTestInput("关联 LLM：" + llmModelId
                    + "\n接口：" + StringUtils.defaultString(baseUrl, "-")
                    + "\n模型：" + StringUtils.defaultString(model, "-")
                    + "\nAPI Key：" + describeApiKeyStatus(apiKey)
                    + "\n测试文本：" + TEST_TEXT);
            result.setTestOutput(missingField + " 请在模型管理中编辑该关联 LLM，补全缺失配置后重试。");
            return result;
        }

        String apiUrl = buildChatCompletionsUrl(baseUrl);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", buildMessages());
        if (!isDeepSeek(baseUrl)) {
            requestBody.put("temperature", 0);
        }
        requestBody.put("max_tokens", 80);
        applyDeepSeekOptions(baseUrl, configJson, requestBody);
        result.setTestInput("关联 LLM：" + llmModelId
                + "\n接口：" + apiUrl
                + "\n模型：" + model
                + "\n测试文本：" + TEST_TEXT
                + "\n期望意图：" + EXPECTED_FUNCTION);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);
        long startTime = System.currentTimeMillis();
        ResponseEntity<String> response = exchange(apiUrl, new HttpEntity<>(requestBody, headers), llmModelId);
        result.setResponseTimeMs(System.currentTimeMillis() - startTime);
        result.setTestOutput(StringUtils.defaultString(response.getBody()));
        if (!response.getStatusCode().is2xxSuccessful()) {
            result.setSuccess(false);
            result.setMessage("外挂大模型意图识别接口返回非成功状态：" + response.getStatusCode());
            return result;
        }

        JSONObject responseJson = JSONUtil.parseObj(response.getBody());
        return applyJsonResult(result, extractOpenAiChoiceText(responseJson.getJSONArray("choices")));
    }

    private ModelTestResultDTO testOllama(JSONObject configJson, String llmModelId) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String baseUrl = configJson.getStr("base_url");
        String model = configJson.getStr("model_name");
        String missingField = describeOllamaMissingField(baseUrl, model);
        if (missingField != null) {
            result.setSuccess(false);
            result.setMessage(missingField);
            result.setTestInput("关联 LLM：" + llmModelId
                    + "\n接口：" + StringUtils.defaultString(baseUrl, "-")
                    + "\n模型：" + StringUtils.defaultString(model, "-")
                    + "\n测试文本：" + TEST_TEXT);
            result.setTestOutput(missingField + " 请在模型管理中编辑该关联 LLM，补全缺失配置后重试。");
            return result;
        }

        String apiUrl = baseUrl.endsWith("/") ? baseUrl + "api/chat" : baseUrl + "/api/chat";
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", buildMessages());
        requestBody.put("stream", false);
        result.setTestInput("关联 LLM：" + llmModelId
                + "\n接口：" + apiUrl
                + "\n模型：" + model
                + "\n测试文本：" + TEST_TEXT
                + "\n期望意图：" + EXPECTED_FUNCTION);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        long startTime = System.currentTimeMillis();
        ResponseEntity<String> response = exchange(apiUrl, new HttpEntity<>(requestBody, headers), llmModelId);
        result.setResponseTimeMs(System.currentTimeMillis() - startTime);
        result.setTestOutput(StringUtils.defaultString(response.getBody()));
        if (!response.getStatusCode().is2xxSuccessful()) {
            result.setSuccess(false);
            result.setMessage("外挂大模型意图识别接口返回非成功状态：" + response.getStatusCode());
            return result;
        }

        JSONObject responseJson = JSONUtil.parseObj(response.getBody());
        JSONObject message = responseJson.getJSONObject("message");
        String answer = message == null ? responseJson.getStr("response") : message.getStr("content");
        return applyJsonResult(result, answer);
    }

    private static List<Map<String, String>> buildMessages() {
        return List.of(
                Map.of("role", "system", "content", buildSystemPrompt()),
                Map.of("role", "user", "content", "current dialogue:\nUser: " + TEST_TEXT + "\n"));
    }

    private ModelTestResultDTO applyJsonResult(ModelTestResultDTO result, String answer) {
        String normalizedAnswer = extractJsonObjectText(answer);
        result.setTestOutput(StringUtils.isNotBlank(normalizedAnswer)
                ? normalizedAnswer
                : StringUtils.defaultString(answer));
        try {
            JSONObject intentJson = JSONUtil.parseObj(normalizedAnswer);
            JSONObject functionCall = intentJson.getJSONObject("function_call");
            String functionName = functionCall == null ? "" : functionCall.getStr("name");
            boolean success = EXPECTED_FUNCTION.equals(functionName);
            result.setSuccess(success);
            result.setMessage(success
                    ? "外挂大模型意图识别真实调用通过"
                    : "外挂大模型意图识别返回的 function_call.name 不符合预期："
                            + StringUtils.defaultIfBlank(functionName, "-"));
            return result;
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("外挂大模型意图识别未返回合法 JSON function_call："
                    + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            return result;
        }
    }

    private static String buildSystemPrompt() {
        return "你是小智的外挂大模型意图识别器，只能返回 JSON，不能返回自然语言。"
                + "可用函数：continue_chat 表示普通聊天；handle_exit_intent 表示用户明确要求结束对话。"
                + "如果用户没有明确要求调用工具或结束对话，必须返回 {\"function_call\":{\"name\":\"continue_chat\"}}。";
    }

    private static String extractJsonObjectText(String text) {
        String value = StringUtils.trimToEmpty(text);
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start < 0 || end < start ? "" : value.substring(start, end + 1);
    }

    private static String extractOpenAiChoiceText(JSONArray choices) {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        JSONObject firstChoice = choices.getJSONObject(0);
        JSONObject message = firstChoice == null ? null : firstChoice.getJSONObject("message");
        Object content = message == null ? null : message.get("content");
        return content == null ? null : content.toString();
    }

    private static String buildChatCompletionsUrl(String baseUrl) {
        if (baseUrl.endsWith("/chat/completions")) {
            return baseUrl;
        }
        return baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
    }

    private static boolean isDeepSeek(String baseUrl) {
        return StringUtils.containsIgnoreCase(baseUrl, "api.deepseek.com");
    }

    private static void applyDeepSeekOptions(String baseUrl, JSONObject configJson, Map<String, Object> requestBody) {
        if (!isDeepSeek(baseUrl)) {
            return;
        }
        String thinkingType = normalizeThinkingType(configJson.get("thinking"));
        if (thinkingType != null) {
            requestBody.put("thinking", Map.of("type", thinkingType));
        }
        String reasoningEffort = normalizeReasoningEffort(configJson.getStr("reasoning_effort"));
        if ("enabled".equals(thinkingType) && reasoningEffort != null) {
            requestBody.put("reasoning_effort", reasoningEffort);
        }
    }

    private static String normalizeThinkingType(Object value) {
        if (value == null) {
            return null;
        }
        String thinkingType;
        if (value instanceof JSONObject thinking) {
            thinkingType = thinking.getStr("type");
        } else if (value instanceof Map<?, ?> thinking) {
            Object type = thinking.get("type");
            thinkingType = type == null ? null : String.valueOf(type);
        } else if (value instanceof Boolean enabled) {
            thinkingType = enabled ? "enabled" : "disabled";
        } else {
            thinkingType = String.valueOf(value);
        }
        thinkingType = StringUtils.lowerCase(StringUtils.trimToEmpty(thinkingType));
        thinkingType = switch (thinkingType) {
            case "enable", "true", "on", "1" -> "enabled";
            case "disable", "false", "off", "0" -> "disabled";
            default -> thinkingType;
        };
        return "enabled".equals(thinkingType) || "disabled".equals(thinkingType) ? thinkingType : null;
    }

    private static String normalizeReasoningEffort(String value) {
        String effort = StringUtils.lowerCase(StringUtils.trimToEmpty(value));
        effort = switch (effort) {
            case "low", "medium" -> "high";
            case "xhigh" -> "max";
            default -> effort;
        };
        return "high".equals(effort) || "max".equals(effort) ? effort : null;
    }

    private static String describeOpenAiMissingField(String baseUrl, String model, String apiKey) {
        if (StringUtils.isBlank(baseUrl)) {
            return "关联 OpenAI 兼容 LLM 缺少 base_url（接口地址）";
        }
        if (StringUtils.isBlank(model)) {
            return "关联 OpenAI 兼容 LLM 缺少 model_name（模型名称）";
        }
        if (StringUtils.isBlank(apiKey)) {
            return "关联 OpenAI 兼容 LLM 缺少 api_key（API 密钥）";
        }
        if (isPlaceholderSecret(apiKey)) {
            return "关联 OpenAI 兼容 LLM 的 api_key 仍为占位符，请填写真实密钥";
        }
        return null;
    }

    private static String describeOllamaMissingField(String baseUrl, String model) {
        if (StringUtils.isBlank(baseUrl)) {
            return "关联 Ollama LLM 缺少 base_url（服务地址）";
        }
        if (StringUtils.isBlank(model)) {
            return "关联 Ollama LLM 缺少 model_name（模型名称）";
        }
        return null;
    }

    private static String describeApiKeyStatus(String apiKey) {
        if (StringUtils.isBlank(apiKey)) {
            return "未配置";
        }
        return isPlaceholderSecret(apiKey) ? "占位符，需填写真实密钥" : "已配置";
    }

    private ResponseEntity<String> exchange(String url, HttpEntity<?> request, String modelId) {
        return usageMeter == null
                ? restTemplate.exchange(url, HttpMethod.POST, request, String.class)
                : usageMeter.exchange(restTemplate, url, request, String.class, modelId, "LLM");
    }

    private static boolean isPlaceholderSecret(String value) {
        String lowerValue = StringUtils.defaultString(value).toLowerCase();
        return lowerValue.contains("你的")
                || lowerValue.contains("请输入")
                || lowerValue.contains("placeholder")
                || lowerValue.contains("your ")
                || lowerValue.contains("your_")
                || lowerValue.contains("sk-xxx")
                || lowerValue.contains("xxxx")
                || lowerValue.contains("***");
    }
}
