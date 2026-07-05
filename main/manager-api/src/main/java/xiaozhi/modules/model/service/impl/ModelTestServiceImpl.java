package xiaozhi.modules.model.service.impl;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.dto.ModelTestResultDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelTestService;

@Slf4j
@Service
@AllArgsConstructor
public class ModelTestServiceImpl implements ModelTestService {

    private final ModelConfigDao modelConfigDao;
    private final ModelRuntimeAssembler modelRuntimeAssembler;
    private final RestTemplate restTemplate;
    private final Environment environment;

    private static final int MODEL_TEST_UNTESTED = 0;
    private static final int MODEL_TEST_PASSED = 1;
    private static final int MODEL_TEST_FAILED = 2;
    private static final Set<String> LOCAL_TESTABLE_TYPES = new HashSet<>(List.of(
            "silero", "nomem", "mem_local_short",
            "mem_report_only", "managed_memory", "nointent", "function_call"));
    private static final String LLM_TEST_PROMPT = "请只回复 OK";
    private static final String TTS_TEST_TEXT = "你好，我是小智，语音合成测试成功。";
    private static final String VLLM_TEST_QUESTION = "请用一句话描述这张图片。";
    private static final String STANDARD_ASR_TEST_AUDIO = "wakeup_words_short.wav";
    private static final int SINGLE_ASR_BENCHMARK_TIMEOUT_SECONDS = 120;
    private static final String TEST_LEVEL_REAL_INFERENCE = "REAL_INFERENCE";
    private static final String TEST_LEVEL_CONNECTIVITY = "CONNECTIVITY";
    private static final String TEST_LEVEL_RUNTIME_CONFIG = "RUNTIME_CONFIG";
    private static final String TEST_LEVEL_CONFIG_ONLY = "CONFIG_ONLY";
    private static final String TEST_LEVEL_UNSUPPORTED = "UNSUPPORTED";
    private static final List<String> UPSTREAM_MODEL_FIELDS = List.of(
            "model_name", "model", "resource_id", "model_dir", "llm_model", "llm");
    private static final List<String> ENDPOINT_FIELDS = List.of(
            "base_url", "api_url", "url", "server_url", "ws_url", "host");

    @Override
    public ModelTestResultDTO testModel(ModelConfigEntity modelConfig) {
        return doTestModel(modelConfig);
    }

    private ModelTestResultDTO doTestModel(ModelConfigEntity modelConfig) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        if (modelConfig.getConfigJson() == null) {
            result.setSuccess(false);
            result.setMessage("模型配置为空");
            markModelTestLevel(result, TEST_LEVEL_UNSUPPORTED, false);
            return result;
        }

        JSONObject configJson = modelConfig.getConfigJson();
        HuoshanDoubleStreamTtsHelper.normalize(modelConfig, configJson);
        String type = StringUtils.lowerCase(configJson.getStr("type"));
        if (StringUtils.isBlank(type)) {
            result.setSuccess(false);
            result.setMessage("模型配置缺少 type");
            markModelTestLevel(result, TEST_LEVEL_UNSUPPORTED, false);
            return result;
        }

        String modelType = StringUtils.defaultString(modelConfig.getModelType()).toUpperCase();
        try {
            String testLevel = getModelTestLevel(modelConfig);
            if ("LLM".equals(modelType) || "SLM".equals(modelType)) {
                result = testLanguageModel(configJson, type);
                return applyModelTestSemantics(result, testLevel);
            }
            if ("VLLM".equals(modelType)) {
                result = testVisionModel(configJson, type);
                return applyModelTestSemantics(result, testLevel);
            }
            if ("ASR".equals(modelType)) {
                result = testAsrModel(configJson, type);
                return applyModelTestSemantics(result, testLevel);
            }
            if ("TTS".equals(modelType)) {
                result = testTtsModel(configJson, type);
                return applyModelTestSemantics(result, testLevel);
            }
            if ("INTENT".equals(modelType) && "intent_llm".equals(type)) {
                result = testIntentModel(configJson);
                return applyModelTestSemantics(result, testLevel);
            }
            if ("MEMORY".equals(modelType)) {
                result = testMemoryModel(configJson, type);
                return applyModelTestSemantics(result, testLevel);
            }
            if (LOCAL_TESTABLE_TYPES.contains(type)) {
                result = validateBasicModelConfig(configJson, type, modelType);
                return applyModelTestSemantics(result, testLevel);
            }
            result = validateBasicModelConfig(configJson, type, modelType);
            return applyModelTestSemantics(result, testLevel);
        } catch (Exception e) {
            log.warn("模型测试失败，modelId: {}", modelConfig.getId(), e);
            result.setSuccess(false);
            result.setMessage("模型测试失败：" + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            result.setTestInput("模型类型：" + modelType + "\n供应器类型：" + type);
            result.setTestOutput(result.getMessage());
            markModelTestLevel(result, TEST_LEVEL_UNSUPPORTED, false);
            return result;
        }
    }

    private ModelTestResultDTO applyModelTestSemantics(ModelTestResultDTO result, String testLevel) {
        if (result == null) {
            result = new ModelTestResultDTO();
            result.setSuccess(false);
            result.setMessage("模型测试没有返回结果");
            result.setTestOutput(result.getMessage());
        }
        boolean rawSuccess = Boolean.TRUE.equals(result.getSuccess());
        boolean verified = rawSuccess
                && (TEST_LEVEL_REAL_INFERENCE.equals(testLevel) || TEST_LEVEL_CONNECTIVITY.equals(testLevel)
                        || TEST_LEVEL_RUNTIME_CONFIG.equals(testLevel));
        markModelTestLevel(result, testLevel, verified);
        if (rawSuccess && !verified) {
            result.setSuccess(false);
            result.setMessage("仅完成配置校验，未执行真实模型调用，不能标记为测试通过；"
                    + StringUtils.defaultString(result.getMessage()));
        }
        return result;
    }

    @Override
    public boolean isVerifiedModelTest(ModelTestResultDTO result) {
        return result != null && Boolean.TRUE.equals(result.getVerified());
    }

    @Override
    public boolean shouldShowModelTestResponseTime(ModelTestResultDTO result) {
        if (result == null) {
            return false;
        }
        return TEST_LEVEL_REAL_INFERENCE.equals(result.getTestLevel())
                || TEST_LEVEL_CONNECTIVITY.equals(result.getTestLevel());
    }

    private void markModelTestLevel(ModelTestResultDTO result, String testLevel, boolean verified) {
        result.setTestLevel(testLevel);
        result.setTestLevelText(getModelTestLevelText(testLevel));
        result.setVerified(verified);
        if (!TEST_LEVEL_REAL_INFERENCE.equals(testLevel) && !TEST_LEVEL_CONNECTIVITY.equals(testLevel)) {
            result.setResponseTimeMs(null);
        }
    }

    private String getModelTestLevel(ModelConfigEntity modelConfig) {
        if (modelConfig == null || modelConfig.getConfigJson() == null) {
            return TEST_LEVEL_UNSUPPORTED;
        }
        String modelType = StringUtils.defaultString(modelConfig.getModelType()).toUpperCase();
        String type = StringUtils.lowerCase(modelConfig.getConfigJson().getStr("type"));
        if (StringUtils.isBlank(type)) {
            return TEST_LEVEL_UNSUPPORTED;
        }
        if (("LLM".equals(modelType) || "SLM".equals(modelType))
                && ("openai".equals(type) || "ollama".equals(type))) {
            return TEST_LEVEL_REAL_INFERENCE;
        }
        if ("VLLM".equals(modelType) && "openai".equals(type)) {
            return TEST_LEVEL_REAL_INFERENCE;
        }
        if ("ASR".equals(modelType) && "openai".equals(type)) {
            return TEST_LEVEL_REAL_INFERENCE;
        }
        if ("TTS".equals(modelType) && "openai".equals(type)) {
            return TEST_LEVEL_REAL_INFERENCE;
        }
        if ("TTS".equals(modelType)
                && (CollectionUtil.isNotEmpty(getRequiredFields(modelType, type))
                        || CollectionUtil.isNotEmpty(getRequiredAnyFieldGroups(modelType, type))
                        || CollectionUtil.isNotEmpty(getMissingAlternativeRequirements(modelConfig.getConfigJson(), modelType, type)))) {
            return TEST_LEVEL_REAL_INFERENCE;
        }
        if ("ASR".equals(modelType) && ("doubao_stream".equals(type) || "doubao_recording".equals(type))) {
            return TEST_LEVEL_REAL_INFERENCE;
        }
        if ("ASR".equals(modelType)
                && (CollectionUtil.isNotEmpty(getRequiredFields(modelType, type))
                        || CollectionUtil.isNotEmpty(getRequiredAnyFieldGroups(modelType, type))
                        || CollectionUtil.isNotEmpty(getMissingAlternativeRequirements(modelConfig.getConfigJson(), modelType, type)))) {
            return TEST_LEVEL_REAL_INFERENCE;
        }
        if ("INTENT".equals(modelType) && "intent_llm".equals(type)) {
            return TEST_LEVEL_CONFIG_ONLY;
        }
        if ("MEMORY".equals(modelType)
                && ("nomem".equals(type) || "mem_report_only".equals(type) || "managed_memory".equals(type))) {
            return TEST_LEVEL_RUNTIME_CONFIG;
        }
        if ("VAD".equals(modelType) && "silero".equals(type)) {
            return TEST_LEVEL_RUNTIME_CONFIG;
        }
        if (LOCAL_TESTABLE_TYPES.contains(type)) {
            return TEST_LEVEL_CONFIG_ONLY;
        }
        if (CollectionUtil.isNotEmpty(getRequiredFields(modelType, type))
                || CollectionUtil.isNotEmpty(getRequiredAnyFieldGroups(modelType, type))
                || CollectionUtil.isNotEmpty(getMissingAlternativeRequirements(modelConfig.getConfigJson(), modelType, type))) {
            return TEST_LEVEL_CONFIG_ONLY;
        }
        return TEST_LEVEL_UNSUPPORTED;
    }

    private String getModelTestLevelText(String testLevel) {
        if (TEST_LEVEL_REAL_INFERENCE.equals(testLevel)) {
            return "真实推理";
        }
        if (TEST_LEVEL_CONNECTIVITY.equals(testLevel)) {
            return "连通性";
        }
        if (TEST_LEVEL_RUNTIME_CONFIG.equals(testLevel)) {
            return "运行时配置";
        }
        if (TEST_LEVEL_CONFIG_ONLY.equals(testLevel)) {
            return "配置校验";
        }
        return "未实测";
    }

    private ModelTestResultDTO testMemoryModel(JSONObject configJson, String type) {
        String normalizedType = StringUtils.lowerCase(StringUtils.defaultString(type));
        if ("nomem".equals(normalizedType)) {
            return buildLocalMemoryTestResult(normalizedType, "无记忆模式配置有效，不会保存、查询或注入长期记忆。");
        }
        if ("mem_report_only".equals(normalizedType)) {
            return buildLocalMemoryTestResult(normalizedType, "仅上报聊天记录模式配置有效，不会执行记忆总结。");
        }
        if ("managed_memory".equals(normalizedType)) {
            return testManagedMemoryModel(configJson, normalizedType);
        }
        return validateBasicModelConfig(configJson, normalizedType, "MEMORY");
    }

    private ModelTestResultDTO testManagedMemoryModel(JSONObject configJson, String type) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String maintenanceModelId = StringUtils.defaultIfBlank(
                configJson.getStr("maintenance_llm"),
                configJson.getStr("memory_maintenance_llm"));
        result.setTestInput("模型类型：MEMORY\n供应器类型：" + type + "\n维护模型："
                + StringUtils.defaultIfBlank(maintenanceModelId, "未配置"));

        if (StringUtils.isBlank(maintenanceModelId)) {
            result.setSuccess(true);
            result.setMessage("智控台治理记忆配置通过；未配置维护模型时，只执行显式记住/忘记和管理端人工维护。");
            result.setTestOutput("manager-api 长期记忆治理链路可启用；维护模型为空，不产生外部模型调用。");
            return result;
        }

        ModelConfigEntity maintenanceModel = modelConfigDao.selectById(maintenanceModelId);
        if (maintenanceModel == null) {
            result.setSuccess(false);
            result.setMessage("记忆维护模型不存在：" + maintenanceModelId);
            result.setTestOutput(result.getMessage());
            return result;
        }

        String modelType = StringUtils.defaultString(maintenanceModel.getModelType()).toUpperCase();
        if (!"LLM".equals(modelType) && !"SLM".equals(modelType)) {
            result.setSuccess(false);
            result.setMessage("记忆维护模型必须引用 LLM 或 SLM，当前类型：" + maintenanceModel.getModelType());
            result.setTestOutput(result.getMessage());
            return result;
        }

        if (!Integer.valueOf(1).equals(maintenanceModel.getIsEnabled())
                || !Integer.valueOf(MODEL_TEST_PASSED).equals(maintenanceModel.getTestStatus())) {
            result.setSuccess(false);
            result.setMessage("记忆维护模型尚未启用或测试通过，请先在模型管理中测试该 LLM/SLM。");
            result.setTestOutput("维护模型：" + maintenanceModelId + "，启用状态：" + maintenanceModel.getIsEnabled()
                    + "，测试状态：" + maintenanceModel.getTestStatus());
            return result;
        }

        result.setSuccess(true);
        result.setMessage("智控台治理记忆配置通过；记忆维护模型已启用并测试通过。");
        result.setTestOutput("维护模型：" + maintenanceModel.getModelName() + " (" + maintenanceModel.getId()
                + ")，类型：" + modelType + "。Memory 配置只保存模型 ID，不保存 API Key。");
        return result;
    }

    private ModelTestResultDTO buildLocalMemoryTestResult(String type, String message) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        result.setSuccess(true);
        result.setMessage(message);
        result.setTestInput("模型类型：MEMORY\n供应器类型：" + type + "\n测试内容：本地运行时配置校验");
        result.setTestOutput(message);
        return result;
    }

    private ModelTestResultDTO testIntentModel(JSONObject configJson) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String llmModelId = configJson.getStr("llm");
        if (StringUtils.isBlank(llmModelId)) {
            result.setSuccess(false);
            result.setMessage("意图识别配置缺少关联 LLM");
            result.setTestInput("关联 LLM：未配置");
            result.setTestOutput(result.getMessage());
            return result;
        }
        ModelConfigEntity llm = attachRuntimeConfig(modelConfigDao.selectById(llmModelId));
        if (llm == null || llm.getTestStatus() == null || llm.getTestStatus() != MODEL_TEST_PASSED) {
            result.setSuccess(false);
            result.setMessage("关联 LLM 尚未测试通过");
            result.setTestInput("关联 LLM：" + llmModelId);
            result.setTestOutput(result.getMessage());
            return result;
        }
        result.setSuccess(true);
        result.setMessage("关联 LLM 已测试通过");
        result.setTestInput("关联 LLM：" + llmModelId);
        result.setTestOutput("关联 LLM 已通过测试，可用于意图识别");
        return result;
    }

    private ModelTestResultDTO testAsrModel(JSONObject configJson, String type) {
        ModelTestResultDTO configCheck = validateBasicModelConfig(configJson, type, "ASR");
        if (!Boolean.TRUE.equals(configCheck.getSuccess())) {
            return configCheck;
        }
        return testAsrModelViaRuntimeBenchmark(configJson, type);
    }

    private ModelTestResultDTO testTtsModel(JSONObject configJson, String type) {
        if ("openai".equals(type)) {
            return testOpenAiTtsModel(configJson);
        }
        ModelTestResultDTO configCheck = validateBasicModelConfig(configJson, type, "TTS");
        if (!Boolean.TRUE.equals(configCheck.getSuccess())) {
            return configCheck;
        }
        return testTtsModelViaRuntimeBenchmark(configJson, type);
    }

    private ModelTestResultDTO testLanguageModel(JSONObject configJson, String type) {
        if ("openai".equals(type)) {
            return testOpenAiCompatibleModel(configJson);
        }
        if ("ollama".equals(type)) {
            return testOllamaModel(configJson);
        }
        ModelTestResultDTO result = new ModelTestResultDTO();
        return validateBasicModelConfig(configJson, type, "LLM");
    }

    private ModelTestResultDTO testVisionModel(JSONObject configJson, String type) {
        if ("openai".equals(type)) {
            return testOpenAiVisionModel(configJson);
        }
        ModelTestResultDTO result = new ModelTestResultDTO();
        return validateBasicModelConfig(configJson, type, "VLLM");
    }

    private boolean isDeepSeek(String baseUrl) {
        return StringUtils.containsIgnoreCase(baseUrl, "api.deepseek.com");
    }

    private String normalizeThinkingType(Object value) {
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

    private String normalizeReasoningEffort(String value) {
        String effort = StringUtils.lowerCase(StringUtils.trimToEmpty(value));
        effort = switch (effort) {
            case "low", "medium" -> "high";
            case "xhigh" -> "max";
            default -> effort;
        };
        return "high".equals(effort) || "max".equals(effort) ? effort : null;
    }

    private void applyDeepSeekOptions(String baseUrl, JSONObject configJson, Map<String, Object> requestBody) {
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

    private ModelTestResultDTO testOpenAiCompatibleModel(JSONObject configJson) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String baseUrl = configJson.getStr("base_url");
        String model = configJson.getStr("model_name");
        String apiKey = StringUtils.defaultIfBlank(configJson.getStr("api_key"), configJson.getStr("api_password"));
        if (StringUtils.isAnyBlank(baseUrl, model, apiKey) || isPlaceholderSecret(apiKey)) {
            result.setSuccess(false);
            result.setMessage("OpenAI 兼容模型缺少 base_url、model_name 或有效密钥");
            result.setTestInput("模型：" + StringUtils.defaultString(model, "-")
                    + "\n提示词：" + LLM_TEST_PROMPT);
            result.setTestOutput(result.getMessage());
            return result;
        }

        String apiUrl = buildChatCompletionsUrl(baseUrl);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", LLM_TEST_PROMPT)));
        if (!isDeepSeek(baseUrl)) {
            requestBody.put("temperature", 0);
        }
        requestBody.put("max_tokens", 8);
        applyDeepSeekOptions(baseUrl, configJson, requestBody);
        result.setTestInput("接口：" + apiUrl + "\n模型：" + model + "\n提示词：" + LLM_TEST_PROMPT);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);
        long startTime = System.currentTimeMillis();
        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl, HttpMethod.POST, new HttpEntity<>(requestBody, headers), String.class);
        result.setResponseTimeMs(System.currentTimeMillis() - startTime);
        result.setTestOutput(StringUtils.defaultString(response.getBody()));
        if (!response.getStatusCode().is2xxSuccessful()) {
            result.setSuccess(false);
            result.setMessage("模型接口返回非成功状态：" + response.getStatusCode());
            return result;
        }
        JSONObject responseJson = JSONUtil.parseObj(response.getBody());
        JSONArray choices = responseJson.getJSONArray("choices");
        String answer = extractOpenAiChoiceText(choices);
        if (StringUtils.isNotBlank(answer)) {
            result.setTestOutput(answer);
        }
        result.setSuccess(choices != null && !choices.isEmpty());
        result.setMessage(Boolean.TRUE.equals(result.getSuccess()) ? "模型连通性测试通过" : "模型接口响应缺少 choices");
        return result;
    }

    private ModelTestResultDTO testOpenAiAsrModel(JSONObject configJson) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String apiUrl = StringUtils.defaultIfBlank(configJson.getStr("base_url"), configJson.getStr("api_url"));
        String model = configJson.getStr("model_name");
        String apiKey = configJson.getStr("api_key");
        if (StringUtils.isAnyBlank(apiUrl, model, apiKey) || isPlaceholderSecret(apiKey)) {
            result.setSuccess(false);
            result.setMessage("OpenAI ASR 缺少 base_url/api_url、model_name 或有效密钥");
            result.setTestInput("接口：" + StringUtils.defaultString(apiUrl, "-")
                    + "\n模型：" + StringUtils.defaultString(model, "-")
                    + "\n测试音频：main/xiaozhi-server/config/assets/*.wav");
            result.setTestOutput(result.getMessage());
            return result;
        }

        try {
            Path audioPath = findAsrTestAudioPath();
            if (audioPath == null) {
                result.setSuccess(false);
                result.setMessage("未找到 ASR 测试音频，期望来源：main/xiaozhi-server/config/assets/*.wav");
                result.setTestInput("测试音频：main/xiaozhi-server/config/assets/*.wav");
                result.setTestOutput(result.getMessage());
                return result;
            }
            result.setTestInput("接口：" + apiUrl + "\n模型：" + model + "\n音频：" + audioPath);
            result.setTestAudioDataUrl(buildAudioDataUrl(audioPath));

            byte[] audioBytes = Files.readAllBytes(audioPath);
            ByteArrayResource audioResource = new ByteArrayResource(audioBytes) {
                @Override
                public String getFilename() {
                    return audioPath.getFileName().toString();
                }
            };
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("model", model);
            body.add("file", audioResource);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.set("Authorization", "Bearer " + apiKey);
            long startTime = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            result.setResponseTimeMs(System.currentTimeMillis() - startTime);
            result.setTestOutput(StringUtils.defaultString(response.getBody()));
            if (!response.getStatusCode().is2xxSuccessful()) {
                result.setSuccess(false);
                result.setMessage("ASR 接口返回非成功状态：" + response.getStatusCode());
                return result;
            }
            JSONObject responseJson = JSONUtil.parseObj(response.getBody());
            String text = responseJson.getStr("text");
            if (StringUtils.isNotBlank(text)) {
                result.setTestOutput(text);
            }
            result.setSuccess(StringUtils.isNotBlank(text));
            result.setMessage(Boolean.TRUE.equals(result.getSuccess())
                    ? "ASR 真实音频测试通过，样本：" + audioPath.toString()
                    : "ASR 接口响应缺少 text，样本：" + audioPath.toString());
            return result;
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("ASR 真实音频测试失败：" + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            if (StringUtils.isBlank(result.getTestOutput())) {
                result.setTestOutput(result.getMessage());
            }
            return result;
        }
    }

    private ModelTestResultDTO testAsrModelViaRuntimeBenchmark(JSONObject configJson, String type) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String providerType = StringUtils.defaultString(type);
        String modelName = firstNonBlank(configJson, "model_name", "model", "resource_id", "host");
        result.setTestInput("供应器类型：" + providerType
                + "\n模型：" + StringUtils.defaultIfBlank(modelName, "-")
                + "\n测试方式：调用 xiaozhi-server 运行时 provider，读取内置音频并执行 1 轮真实 ASR 识别");

        try {
            JSONObject payload = new JSONObject();
            payload.set("task_id", "single-asr-test-" + UUID.randomUUID());
            payload.set("model_type", "ASR");
            payload.set("iterations", 1);
            payload.set("concurrency", 1);

            JSONObject model = new JSONObject();
            model.set("id", "single-asr-test");
            model.set("model_name", StringUtils.defaultIfBlank(modelName, providerType));
            model.set("model_type", "ASR");
            model.set("provider_type", providerType);
            model.set("config", configJson);
            model.set("test_audio_name", STANDARD_ASR_TEST_AUDIO);
            model.set("include_audio_preview", true);
            JSONArray models = new JSONArray();
            models.add(model);
            payload.set("models", models);

            long startTime = System.currentTimeMillis();
            JSONObject response = callRuntimeBenchmark(payload);
            result.setResponseTimeMs(System.currentTimeMillis() - startTime);

            JSONObject benchmarkResult = firstJsonObject(response.getJSONArray("results"));
            if (benchmarkResult == null) {
                result.setSuccess(false);
                result.setMessage("ASR 真实音频测试没有返回结果");
                result.setTestOutput(response.toStringPretty());
                return result;
            }

            JSONObject sample = firstJsonObject(benchmarkResult.getJSONArray("samples"));
            if (sample == null) {
                result.setSuccess(false);
                result.setMessage(StringUtils.defaultIfBlank(
                        benchmarkResult.getStr("error_summary"),
                        "ASR 真实音频测试没有返回样本"));
                result.setTestOutput(benchmarkResult.toStringPretty());
                return result;
            }

            String recognizedText = extractRuntimeBenchmarkRecognizedText(sample);
            Long latencyMs = sample.getLong("latency_ms");
            if (latencyMs != null) {
                result.setResponseTimeMs(latencyMs);
            }
            result.setFirstTextMs(sample.getLong("ttft_ms"));
            result.setTestInput(buildRuntimeBenchmarkAsrInput(sample, providerType));
            result.setTestAudioDataUrl(buildRuntimeBenchmarkAsrAudioDataUrl(sample));
            result.setTestOutput(StringUtils.defaultIfBlank(recognizedText, sample.toStringPretty()));
            boolean success = Boolean.TRUE.equals(sample.getBool("success")) && StringUtils.isNotBlank(recognizedText);
            result.setSuccess(success);
            result.setMessage(success
                    ? "ASR 真实音频测试通过"
                    : StringUtils.defaultIfBlank(sample.getStr("message"), "ASR 真实音频测试未返回识别文本"));
            return result;
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("ASR 真实音频测试失败：" + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            result.setTestOutput(result.getMessage());
            return result;
        }
    }

    private JSONObject callRuntimeBenchmark(JSONObject payload) {
        String baseUrl = resolveXiaozhiServerHttpBaseUrl();
        String url = baseUrl + "/benchmark/models";
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(SINGLE_ASR_BENCHMARK_TIMEOUT_SECONDS));
        RestTemplate benchmarkRestTemplate = new RestTemplate(factory);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<String> response = benchmarkRestTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(payload.toString(), headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RenException("xiaozhi-server 运行时真实测试接口返回异常：" + response.getStatusCode());
            }
            return JSONUtil.parseObj(response.getBody());
        } catch (ResourceAccessException e) {
            throw new RenException("无法连接 xiaozhi-server 运行时真实测试接口：" + url
                    + "。请先确认 xiaozhi-server 已启动且 HTTP 端口可访问；源码开发可执行 `scripts/dev status server`。原始错误："
                    + e.getMessage(), e);
        }
    }

    private String resolveXiaozhiServerHttpBaseUrl() {
        String configured = environment.getProperty("xiaozhi.server.http-base-url");
        if (StringUtils.isNotBlank(configured)) {
            return StringUtils.removeEnd(configured.trim(), "/");
        }
        return "http://127.0.0.1:" + resolveXiaozhiServerHttpPort();
    }

    private int resolveXiaozhiServerHttpPort() {
        String value = environment.getProperty("xiaozhi.server.http-port");
        if (StringUtils.isNumeric(value)) {
            return Integer.parseInt(value);
        }
        return 8003;
    }

    private static JSONObject firstJsonObject(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return null;
        }
        return JSONUtil.parseObj(array.get(0));
    }

    private String buildRuntimeBenchmarkAsrInput(JSONObject sample, String providerType) {
        JSONObject inputDetail = sample.getJSONObject("input_detail");
        String audioName = inputDetail == null ? sample.getStr("input_summary") : inputDetail.getStr("audio_name");
        String reference = inputDetail == null ? null : inputDetail.getStr("reference_text");
        String audioBytes = inputDetail == null ? null : StringUtils.defaultString(inputDetail.getStr("audio_bytes"));
        Long audioDurationMs = inputDetail == null ? null : inputDetail.getLong("audio_duration_ms");
        Long totalLatencyMs = sample.getLong("latency_ms");
        Long tailLatencyMs = getRuntimeBenchmarkAsrTailLatencyMs(sample);
        Long firstTextMs = sample.getLong("ttft_ms");
        return "供应器类型：" + providerType
                + "\n音频：" + StringUtils.defaultIfBlank(audioName, "-")
                + "\n音频格式：pcm"
                + (StringUtils.isNotBlank(audioBytes) ? "\n音频大小：" + audioBytes + " bytes" : "")
                + (audioDurationMs != null ? "\n音频时长：" + audioDurationMs + " ms" : "")
                + (totalLatencyMs != null ? "\n端到端耗时：" + totalLatencyMs + " ms" : "")
                + (firstTextMs != null ? "\n首段识别耗时：" + firstTextMs + " ms" : "")
                + (tailLatencyMs != null ? "\n说完后最终识别延迟：" + tailLatencyMs + " ms" : "")
                + (tailLatencyMs == null ? "\n延迟口径：录音/非实时识别按端到端耗时统计，不扣除音频时长" : "")
                + (StringUtils.isNotBlank(reference) ? "\n参考文本：" + reference : "")
                + "\n测试方式：xiaozhi-server 运行时 provider 真实识别";
    }

    private Long getRuntimeBenchmarkAsrTailLatencyMs(JSONObject sample) {
        Long topLevelTailLatencyMs = sample == null ? null : sample.getLong("tail_latency_ms");
        if (topLevelTailLatencyMs != null) {
            return topLevelTailLatencyMs;
        }
        JSONObject outputDetail = sample == null ? null : sample.getJSONObject("output_detail");
        return outputDetail == null ? null : outputDetail.getLong("tail_latency_ms");
    }

    private String extractRuntimeBenchmarkRecognizedText(JSONObject sample) {
        JSONObject outputDetail = sample.getJSONObject("output_detail");
        if (outputDetail != null && StringUtils.isNotBlank(outputDetail.getStr("recognized_text"))) {
            return outputDetail.getStr("recognized_text");
        }
        return StringUtils.defaultString(sample.getStr("output_summary"));
    }

    private String buildRuntimeBenchmarkAsrAudioDataUrl(JSONObject sample) {
        JSONObject inputDetail = sample.getJSONObject("input_detail");
        String runtimeAudioDataUrl = inputDetail == null ? "" : inputDetail.getStr("audio_data_url");
        if (StringUtils.isNotBlank(runtimeAudioDataUrl)) {
            return runtimeAudioDataUrl;
        }
        String audioName = inputDetail == null ? sample.getStr("input_summary") : inputDetail.getStr("audio_name");
        if (StringUtils.isBlank(audioName)) {
            return "";
        }
        Path audioPath = findFirstExistingPath(List.of(
                "../xiaozhi-server/config/assets/" + audioName,
                "main/xiaozhi-server/config/assets/" + audioName));
        return buildAudioDataUrl(audioPath);
    }

    private ModelTestResultDTO testTtsModelViaRuntimeBenchmark(JSONObject configJson, String type) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String providerType = StringUtils.defaultString(type);
        String modelName = firstNonBlank(configJson, "model", "model_name", "resource_id", "voice", "speaker",
                "voice_id", "api_url", "url");
        result.setTestInput("供应器类型：" + providerType
                + "\n模型/音色：" + StringUtils.defaultIfBlank(modelName, "-")
                + "\n测试文本：" + TTS_TEST_TEXT
                + "\n测试方式：调用 xiaozhi-server 运行时 provider 执行 1 轮真实 TTS 合成");

        try {
            JSONObject payload = new JSONObject();
            payload.set("task_id", "single-tts-test-" + UUID.randomUUID());
            payload.set("model_type", "TTS");
            payload.set("iterations", 1);
            payload.set("concurrency", 1);

            JSONObject model = new JSONObject();
            model.set("id", "single-tts-test");
            model.set("model_name", StringUtils.defaultIfBlank(modelName, providerType));
            model.set("model_type", "TTS");
            model.set("provider_type", providerType);
            model.set("config", configJson);
            JSONArray models = new JSONArray();
            models.add(model);
            payload.set("models", models);

            long startTime = System.currentTimeMillis();
            JSONObject response = callRuntimeBenchmark(payload);
            result.setResponseTimeMs(System.currentTimeMillis() - startTime);

            JSONObject benchmarkResult = firstJsonObject(response.getJSONArray("results"));
            if (benchmarkResult == null) {
                result.setSuccess(false);
                result.setMessage("TTS 真实合成测试没有返回结果");
                result.setTestOutput(response.toStringPretty());
                return result;
            }

            JSONObject sample = firstJsonObject(benchmarkResult.getJSONArray("samples"));
            if (sample == null) {
                result.setSuccess(false);
                result.setMessage(StringUtils.defaultIfBlank(
                        benchmarkResult.getStr("error_summary"),
                        "TTS 真实合成测试没有返回样本"));
                result.setTestOutput(benchmarkResult.toStringPretty());
                return result;
            }

            Long latencyMs = sample.getLong("latency_ms");
            if (latencyMs != null) {
                result.setResponseTimeMs(latencyMs);
            }
            result.setFirstAudioMs(sample.getLong("first_audio_ms"));
            result.setTestInput(buildRuntimeBenchmarkTtsInput(sample, providerType));
            result.setTestAudioDataUrl(buildRuntimeBenchmarkTtsAudioDataUrl(sample));
            result.setTestOutput(buildRuntimeBenchmarkTtsOutput(sample));
            boolean success = Boolean.TRUE.equals(sample.getBool("success"))
                    && getRuntimeBenchmarkTtsAudioBytes(sample) > 0;
            result.setSuccess(success);
            result.setMessage(success
                    ? "TTS 真实合成测试通过"
                    : StringUtils.defaultIfBlank(sample.getStr("message"), "TTS 真实合成未生成有效音频"));
            return result;
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("TTS 真实合成测试失败：" + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            result.setTestOutput(result.getMessage());
            return result;
        }
    }

    private String buildRuntimeBenchmarkTtsInput(JSONObject sample, String providerType) {
        JSONObject inputDetail = sample.getJSONObject("input_detail");
        String text = inputDetail == null ? sample.getStr("input_summary") : inputDetail.getStr("text");
        String sampleRate = inputDetail == null ? null : StringUtils.defaultString(inputDetail.getStr("sample_rate"));
        String audioFormat = inputDetail == null ? null : inputDetail.getStr("audio_format");
        String resourceId = inputDetail == null ? null : inputDetail.getStr("resource_id");
        String speaker = inputDetail == null ? null : inputDetail.getStr("speaker");
        Object audioParamsValue = inputDetail == null ? null : inputDetail.get("audio_params");
        String audioParams = audioParamsValue == null ? null : String.valueOf(audioParamsValue);
        return "供应器类型：" + providerType
                + "\n文本：" + StringUtils.defaultIfBlank(text, TTS_TEST_TEXT)
                + (StringUtils.isNotBlank(resourceId) ? "\nResource ID：" + resourceId : "")
                + (StringUtils.isNotBlank(speaker) ? "\n音色：" + speaker : "")
                + (StringUtils.isNotBlank(audioParams) ? "\n音频参数：" + audioParams : "")
                + (StringUtils.isNotBlank(sampleRate) ? "\n采样率：" + sampleRate : "")
                + (StringUtils.isNotBlank(audioFormat) ? "\n音频格式：" + audioFormat : "")
                + "\n测试方式：xiaozhi-server 运行时 provider 真实合成";
    }

    private String buildRuntimeBenchmarkTtsOutput(JSONObject sample) {
        JSONObject outputDetail = sample.getJSONObject("output_detail");
        if (outputDetail == null) {
            return StringUtils.defaultString(sample.getStr("output_summary"));
        }
        String audioBytes = StringUtils.defaultString(outputDetail.getStr("audio_bytes"));
        String outputFileType = outputDetail.getStr("output_file_type");
        String previewFormat = outputDetail.getStr("audio_preview_format");
        return "音频字节数：" + StringUtils.defaultIfBlank(audioBytes, "0")
                + (StringUtils.isNotBlank(outputFileType) ? "\n输出类型：" + outputFileType : "")
                + (StringUtils.isNotBlank(previewFormat) ? "\n预览格式：" + previewFormat : "");
    }

    private String buildRuntimeBenchmarkTtsAudioDataUrl(JSONObject sample) {
        JSONObject outputDetail = sample.getJSONObject("output_detail");
        return outputDetail == null ? "" : StringUtils.defaultString(outputDetail.getStr("audio_data_url"));
    }

    private long getRuntimeBenchmarkTtsAudioBytes(JSONObject sample) {
        JSONObject outputDetail = sample.getJSONObject("output_detail");
        if (outputDetail == null) {
            return 0;
        }
        Long audioBytes = outputDetail.getLong("audio_bytes");
        return audioBytes == null ? 0 : audioBytes;
    }

    private static String firstNonBlank(JSONObject configJson, String... fields) {
        for (String field : fields) {
            String value = configJson.getStr(field);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private ModelTestResultDTO testOpenAiTtsModel(JSONObject configJson) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String apiUrl = StringUtils.defaultIfBlank(configJson.getStr("api_url"), configJson.getStr("base_url"));
        String model = configJson.getStr("model");
        String voice = configJson.getStr("voice");
        String apiKey = configJson.getStr("api_key");
        if (StringUtils.isAnyBlank(apiUrl, model, voice, apiKey) || isPlaceholderSecret(apiKey)) {
            result.setSuccess(false);
            result.setMessage("OpenAI TTS 缺少 api_url、model、voice 或有效密钥");
            result.setTestInput("接口：" + StringUtils.defaultString(apiUrl, "-")
                    + "\n模型：" + StringUtils.defaultString(model, "-")
                    + "\n音色：" + StringUtils.defaultString(voice, "-")
                    + "\n文本：" + TTS_TEST_TEXT);
            result.setTestOutput(result.getMessage());
            return result;
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", TTS_TEST_TEXT);
        requestBody.put("voice", voice);
        requestBody.put("response_format", StringUtils.defaultIfBlank(configJson.getStr("format"), "wav"));
        if (configJson.containsKey("speed")) {
            requestBody.put("speed", configJson.get("speed"));
        }
        result.setTestInput("接口：" + apiUrl + "\n模型：" + model + "\n音色：" + voice + "\n文本：" + TTS_TEST_TEXT);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);
        long startTime = System.currentTimeMillis();
        ResponseEntity<byte[]> response = restTemplate.exchange(
                apiUrl, HttpMethod.POST, new HttpEntity<>(requestBody, headers), byte[].class);
        result.setResponseTimeMs(System.currentTimeMillis() - startTime);
        byte[] audioBytes = response.getBody();
        result.setTestOutput("HTTP 状态：" + response.getStatusCode()
                + "\n音频字节数：" + (audioBytes == null ? 0 : audioBytes.length));
        result.setSuccess(response.getStatusCode().is2xxSuccessful() && audioBytes != null && audioBytes.length > 1024);
        result.setMessage(Boolean.TRUE.equals(result.getSuccess())
                ? "TTS 真实合成测试通过，测试文本：" + TTS_TEST_TEXT
                : "TTS 响应音频为空或过小，测试文本：" + TTS_TEST_TEXT);
        return result;
    }

    private ModelTestResultDTO testOpenAiVisionModel(JSONObject configJson) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String baseUrl = StringUtils.defaultIfBlank(configJson.getStr("base_url"), configJson.getStr("url"));
        String model = configJson.getStr("model_name");
        String apiKey = configJson.getStr("api_key");
        if (StringUtils.isAnyBlank(baseUrl, model, apiKey) || isPlaceholderSecret(apiKey)) {
            result.setSuccess(false);
            result.setMessage("视觉模型缺少 base_url/url、model_name 或有效密钥");
            result.setTestInput("模型：" + StringUtils.defaultString(model, "-")
                    + "\n问题：" + VLLM_TEST_QUESTION);
            result.setTestOutput(result.getMessage());
            return result;
        }

        try {
            Path imagePath = findFirstExistingPath(List.of(
                    "../../docs/images/demo1.png",
                    "docs/images/demo1.png",
                    "../docs/images/demo1.png"));
            String imageSource = imagePath == null ? "内置视觉测试图片" : imagePath.toString();
            byte[] imageBytes = imagePath == null ? createEmbeddedVisionTestImage() : Files.readAllBytes(imagePath);
            String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);
            result.setTestImageDataUrl("data:image/png;base64," + imageBase64);
            Map<String, Object> imageUrl = Map.of("url", "data:image/png;base64," + imageBase64);
            Map<String, Object> textContent = Map.of("type", "text", "text", VLLM_TEST_QUESTION);
            Map<String, Object> imageContent = Map.of("type", "image_url", "image_url", imageUrl);
            Map<String, Object> message = Map.of("role", "user", "content", List.of(textContent, imageContent));
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(message));
            requestBody.put("stream", false);
            requestBody.put("max_tokens", 120);
            result.setTestInput("图片：" + imageSource + "\n问题：" + VLLM_TEST_QUESTION);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);
            long startTime = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.exchange(
                    buildChatCompletionsUrl(baseUrl), HttpMethod.POST, new HttpEntity<>(requestBody, headers),
                    String.class);
            result.setResponseTimeMs(System.currentTimeMillis() - startTime);
            result.setTestOutput(StringUtils.defaultString(response.getBody()));
            if (!response.getStatusCode().is2xxSuccessful()) {
                result.setSuccess(false);
                result.setMessage("视觉模型接口返回非成功状态：" + response.getStatusCode());
                return result;
            }
            JSONObject responseJson = JSONUtil.parseObj(response.getBody());
            JSONArray choices = responseJson.getJSONArray("choices");
            String answer = extractOpenAiChoiceText(choices);
            if (StringUtils.isNotBlank(answer)) {
                result.setTestOutput(answer);
            }
            result.setSuccess(choices != null && !choices.isEmpty());
            result.setMessage(Boolean.TRUE.equals(result.getSuccess())
                    ? "视觉模型真实图片测试通过，样本：" + imageSource
                    : "视觉模型响应缺少 choices，样本：" + imageSource);
            return result;
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("视觉模型真实图片测试失败：" + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            if (StringUtils.isBlank(result.getTestOutput())) {
                result.setTestOutput(result.getMessage());
            }
            return result;
        }
    }

    private static byte[] createEmbeddedVisionTestImage() throws IOException {
        BufferedImage image = new BufferedImage(480, 320, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(246, 248, 252));
            graphics.fillRect(0, 0, 480, 320);
            graphics.setColor(new Color(43, 92, 171));
            graphics.fillRoundRect(54, 64, 160, 116, 18, 18);
            graphics.setColor(new Color(239, 91, 91));
            graphics.fillOval(276, 56, 128, 128);
            graphics.setColor(new Color(49, 168, 126));
            int[] xPoints = { 152, 244, 336 };
            int[] yPoints = { 264, 128, 264 };
            graphics.fillPolygon(xPoints, yPoints, 3);
            graphics.setColor(new Color(28, 36, 52));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            graphics.drawString("XIAOZHI VISION TEST", 82, 292);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static String extractOpenAiChoiceText(JSONArray choices) {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        JSONObject firstChoice = choices.getJSONObject(0);
        if (firstChoice == null) {
            return null;
        }
        JSONObject message = firstChoice.getJSONObject("message");
        if (message == null) {
            return null;
        }
        Object content = message.get("content");
        if (content == null) {
            return null;
        }
        return content.toString();
    }

    private ModelTestResultDTO testOllamaModel(JSONObject configJson) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String baseUrl = configJson.getStr("base_url");
        String model = configJson.getStr("model_name");
        if (StringUtils.isAnyBlank(baseUrl, model)) {
            result.setSuccess(false);
            result.setMessage("Ollama 模型缺少 base_url 或 model_name");
            result.setTestInput("接口：" + StringUtils.defaultString(baseUrl, "-")
                    + "\n模型：" + StringUtils.defaultString(model, "-")
                    + "\n提示词：" + LLM_TEST_PROMPT);
            result.setTestOutput(result.getMessage());
            return result;
        }

        String apiUrl = baseUrl.endsWith("/") ? baseUrl + "api/chat" : baseUrl + "/api/chat";
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", LLM_TEST_PROMPT)));
        requestBody.put("stream", false);
        result.setTestInput("接口：" + apiUrl + "\n模型：" + model + "\n提示词：" + LLM_TEST_PROMPT);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        long startTime = System.currentTimeMillis();
        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl, HttpMethod.POST, new HttpEntity<>(requestBody, headers), String.class);
        result.setResponseTimeMs(System.currentTimeMillis() - startTime);
        result.setTestOutput(StringUtils.defaultString(response.getBody()));
        if (!response.getStatusCode().is2xxSuccessful()) {
            result.setSuccess(false);
            result.setMessage("Ollama 接口返回非成功状态：" + response.getStatusCode());
            return result;
        }
        JSONObject responseJson = JSONUtil.parseObj(response.getBody());
        JSONObject message = responseJson.getJSONObject("message");
        if (message != null && StringUtils.isNotBlank(message.getStr("content"))) {
            result.setTestOutput(message.getStr("content"));
        } else if (StringUtils.isNotBlank(responseJson.getStr("response"))) {
            result.setTestOutput(responseJson.getStr("response"));
        }
        result.setSuccess(responseJson.containsKey("message") || responseJson.containsKey("response"));
        result.setMessage(Boolean.TRUE.equals(result.getSuccess()) ? "Ollama 连通性测试通过" : "Ollama 响应格式不符合预期");
        return result;
    }

    private static String buildChatCompletionsUrl(String baseUrl) {
        if (baseUrl.endsWith("/chat/completions")) {
            return baseUrl;
        }
        return baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
    }

    private ModelTestResultDTO validateBasicModelConfig(JSONObject configJson, String type, String modelType) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        result.setTestInput("模型类型：" + modelType + "\n供应器类型：" + type + "\n测试内容：配置完整性校验");
        List<String> missingFields = getRequiredFields(modelType, type).stream()
                .filter(field -> !hasUsableConfigValue(configJson, field))
                .collect(Collectors.toList());

        for (List<String> group : getRequiredAnyFieldGroups(modelType, type)) {
            boolean hasAny = group.stream().anyMatch(field -> hasUsableConfigValue(configJson, field));
            if (!hasAny) {
                missingFields.add(String.join("/", group));
            }
        }
        missingFields.addAll(getMissingAlternativeRequirements(configJson, modelType, type));

        if (CollectionUtil.isNotEmpty(missingFields)) {
            result.setSuccess(false);
            result.setMessage("模型配置缺少有效字段：" + String.join("、", missingFields));
            result.setTestOutput(result.getMessage());
            return result;
        }

        result.setSuccess(true);
        result.setMessage("模型配置完整性测试通过");
        result.setTestOutput(buildBasicConfigSuccessOutput(modelType, type));
        return result;
    }

    private static List<String> getRequiredFields(String modelType, String type) {
        String normalizedModelType = StringUtils.defaultString(modelType).toUpperCase();
        String normalizedType = StringUtils.lowerCase(StringUtils.defaultString(type));
        if ("ASR".equals(normalizedModelType)) {
            return switch (normalizedType) {
                case "fun_local", "sherpa_onnx_local" -> List.of("model_dir");
                case "vosk" -> List.of("model_path");
                case "fun_server" -> List.of("host", "port");
                case "doubao" -> List.of("appid", "access_token", "cluster");
                case "doubao_recording", "doubao_stream" -> List.of("api_key");
                case "tencent" -> List.of("appid", "secret_id", "secret_key");
                case "aliyun", "aliyun_stream" -> List.of("appkey");
                case "baidu" -> List.of("app_id", "api_key", "secret_key");
                case "qwen3_asr_flash" -> List.of("api_key", "base_url", "model_name");
                case "aliyunbl_stream" -> List.of("api_key", "model");
                case "xunfei_stream" -> List.of("app_id", "api_key", "api_secret");
                default -> List.of();
            };
        }
        if ("TTS".equals(normalizedModelType)) {
            return switch (normalizedType) {
                case "huoshan_double_stream" -> List.of("api_key");
                default -> List.of();
            };
        }
        if ("LLM".equals(normalizedModelType)) {
            return switch (normalizedType) {
                case "alibl" -> List.of("base_url", "app_id", "api_key");
                case "dify", "fastgpt" -> List.of("base_url", "api_key");
                case "gemini" -> List.of("api_key", "model_name");
                case "coze" -> List.of("bot_id", "personal_access_token");
                case "xinference" -> List.of("base_url", "model_name");
                default -> List.of();
            };
        }
        if ("VLLM".equals(normalizedModelType)) {
            return List.of("base_url", "model_name", "api_key");
        }
        if ("MEMORY".equals(normalizedModelType)) {
            return switch (normalizedType) {
                case "mem0ai" -> List.of("api_key");
                case "mem_local_short" -> List.of("llm");
                case "powermem" -> List.of("llm_model", "llm_api_key", "embedding_model", "embedding_api_key");
                default -> List.of();
            };
        }
        if ("VAD".equals(normalizedModelType)) {
            return "silero".equals(normalizedType) ? List.of("model_dir", "threshold", "min_silence_duration_ms")
                    : List.of();
        }
        if ("RAG".equals(normalizedModelType)) {
            return "ragflow".equals(normalizedType) ? List.of("base_url", "api_key") : List.of();
        }
        return List.of();
    }

    private static List<List<String>> getRequiredAnyFieldGroups(String modelType, String type) {
        String normalizedModelType = StringUtils.defaultString(modelType).toUpperCase();
        String normalizedType = StringUtils.lowerCase(StringUtils.defaultString(type));
        return List.of();
    }

    private static List<String> getMissingAlternativeRequirements(JSONObject configJson, String modelType, String type) {
        String normalizedModelType = StringUtils.defaultString(modelType).toUpperCase();
        String normalizedType = StringUtils.lowerCase(StringUtils.defaultString(type));
        if (!isAliyunNlsModel(modelType, type)) {
            return List.of();
        }
        boolean hasToken = hasUsableConfigValue(configJson, "token");
        boolean hasAccessKeyPair = hasUsableConfigValue(configJson, "access_key_id")
                && hasUsableConfigValue(configJson, "access_key_secret");
        if (hasToken || hasAccessKeyPair) {
            return List.of();
        }
        return List.of("token 或 access_key_id+access_key_secret");
    }

    private static boolean isAliyunNlsModel(String modelType, String type) {
        String normalizedModelType = StringUtils.defaultString(modelType).toUpperCase();
        String normalizedType = StringUtils.lowerCase(StringUtils.defaultString(type));
        return ("ASR".equals(normalizedModelType) || "TTS".equals(normalizedModelType))
                && ("aliyun".equals(normalizedType) || "aliyun_stream".equals(normalizedType));
    }

    private static String buildBasicConfigSuccessOutput(String modelType, String type) {
        String normalizedModelType = StringUtils.defaultString(modelType).toUpperCase();
        String normalizedType = StringUtils.lowerCase(StringUtils.defaultString(type));
        if ("VAD".equals(normalizedModelType) && "silero".equals(normalizedType)) {
            return "必填字段已配置，后台允许启用。SileroVAD 模型文件由 xiaozhi-server 按当前运行方式在运行时校验；"
                    + "Docker 部署需确保容器内路径可访问，源码运行需确保本机路径可访问。";
        }
        return "必填字段已配置";
    }

    private static boolean hasUsableConfigValue(JSONObject configJson, String field) {
        if (configJson == null) {
            return false;
        }
        Object value = configJson.get(field);
        return hasUsableObjectValue(value);
    }

    private static boolean hasUsableObjectValue(Object value) {
        if (value == null) {
            return false;
        }
        String stringValue = StringUtils.trim(String.valueOf(value));
        return hasUsableTextValue(stringValue);
    }

    private static boolean hasUsableTextValue(String stringValue) {
        return StringUtils.isNotBlank(stringValue) && !"null".equalsIgnoreCase(stringValue) && !isPlaceholderSecret(stringValue);
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

    private static Path findFirstExistingPath(List<String> candidates) {
        for (String candidate : candidates) {
            Path path = Paths.get(candidate).toAbsolutePath().normalize();
            if (Files.exists(path) && Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    private Path findAsrTestAudioPath() {
        Path codeRootPath = Paths.get("").toAbsolutePath().normalize();
        Path projectRootPath = locateProjectRoot(codeRootPath);
        return findFirstExistingPath(List.of(
                projectRootPath.resolve("main/xiaozhi-server/config/assets/" + STANDARD_ASR_TEST_AUDIO).toString(),
                projectRootPath.resolve("main/xiaozhi-server/config/assets/wakeup_words.wav").toString(),
                projectRootPath.resolve("main/xiaozhi-server/config/assets/bind_code.wav").toString(),
                codeRootPath.resolve("../xiaozhi-server/config/assets/" + STANDARD_ASR_TEST_AUDIO).toString(),
                "../xiaozhi-server/config/assets/" + STANDARD_ASR_TEST_AUDIO,
                "../xiaozhi-server/config/assets/wakeup_words.wav",
                "../xiaozhi-server/config/assets/bind_code.wav",
                "main/xiaozhi-server/config/assets/" + STANDARD_ASR_TEST_AUDIO,
                "main/xiaozhi-server/config/assets/wakeup_words.wav",
                "main/xiaozhi-server/config/assets/bind_code.wav"));
    }

    private Path locateProjectRoot(Path startPath) {
        Path current = startPath;
        while (current != null) {
            if (Files.exists(current.resolve("main/xiaozhi-server/config/assets/" + STANDARD_ASR_TEST_AUDIO))) {
                return current;
            }
            current = current.getParent();
        }
        return startPath;
    }

    private String buildAudioDataUrl(Path audioPath) {
        if (audioPath == null || !Files.exists(audioPath) || !Files.isRegularFile(audioPath)) {
            return "";
        }
        try {
            String filename = audioPath.getFileName().toString().toLowerCase();
            String mediaType = filename.endsWith(".mp3") ? "audio/mpeg" : "audio/wav";
            return "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(audioPath));
        } catch (IOException e) {
            log.warn("生成测试音频预览失败，path: {}", audioPath, e);
            return "";
        }
    }

    private static byte[] readAsrTestPcmBytes(Path audioPath) throws IOException {
        byte[] bytes = Files.readAllBytes(audioPath);
        if (bytes.length > 44 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            byte[] pcm = new byte[bytes.length - 44];
            System.arraycopy(bytes, 44, pcm, 0, pcm.length);
            return pcm;
        }
        return bytes;
    }

    // -- helper for test methods that need attachRuntimeConfig --
    private ModelConfigEntity attachRuntimeConfig(ModelConfigEntity model) {
        if (model == null) {
            return null;
        }
        model.setConfigJson(modelRuntimeAssembler.buildTypedConfig(model));
        return model;
    }
}
