package xiaozhi.modules.model.service.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.llm.LlmClient;
import xiaozhi.common.llm.LlmUsageContext;
import xiaozhi.modules.model.dto.ModelAssistantApplyRequestDTO;
import xiaozhi.modules.model.dto.ModelAssistantApplyResponseDTO;
import xiaozhi.modules.model.dto.ModelAssistantApplyResponseDTO.ArtifactWriteResultDTO;
import xiaozhi.modules.model.dto.ModelAssistantRequestDTO;
import xiaozhi.modules.model.dto.ModelAssistantResponseDTO;
import xiaozhi.modules.model.dto.ModelAssistantResponseDTO.IntegrationArtifactDTO;
import xiaozhi.modules.model.dto.ModelAssistantSettingsDTO;
import xiaozhi.modules.model.dto.ModelCatalogDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelAssistantService;
import xiaozhi.modules.model.service.ModelCatalogService;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.sys.service.SysParamsService;

@Slf4j
@Service
@AllArgsConstructor
public class ModelAssistantServiceImpl implements ModelAssistantService {

    private static final String ASSISTANT_LLM_MODEL_PARAM = "model.assistant.llm_model_id";
    private static final int MAX_CONTEXT_CHARS = 12000;
    private static final Set<String> SECRET_KEYS = Set.of(
            "api_key", "apikey", "apiKey", "api_secret", "apiSecret", "secret_key", "secretKey",
            "access_key_secret", "accessKeySecret", "access_token", "token", "authorization",
            "password", "api_password", "private_key");
    private static final List<String> AGENT_NAMES = List.of(
            "CoordinatorAgent",
            "DocReaderAgent",
            "AdapterMatcherAgent",
            "ConfigDraftAgent",
            "MissingFieldAgent",
            "ProtocolSpecAgent",
            "ValidationAgent");
    private static final List<String> ARTIFACT_PATH_ALLOWLIST = List.of(
            "main/xiaozhi-server/core/providers/",
            "main/xiaozhi-server/tests/",
            "main/manager-api/src/main/resources/db/changelog/",
            "main/manager-api/src/test/",
            "main/manager-web/src/",
            "docs/models/",
            "config/examples/");

    private final SysParamsService sysParamsService;
    private final ModelConfigService modelConfigService;
    private final ModelCatalogService modelCatalogService;
    private final LlmClient llmClient;

    @Override
    public ModelAssistantSettingsDTO getSettings() {
        ModelAssistantSettingsDTO dto = new ModelAssistantSettingsDTO();
        String modelId = normalizeParamValue(sysParamsService.getValue(ASSISTANT_LLM_MODEL_PARAM, true));
        dto.setLlmModelId(modelId);
        if (StringUtils.isNotBlank(modelId)) {
            ModelConfigEntity model = modelConfigService.getModelByIdFromCache(modelId);
            if (model != null) {
                dto.setLlmModelName(model.getModelName());
            }
        }
        return dto;
    }

    @Override
    public ModelAssistantSettingsDTO updateSettings(ModelAssistantSettingsDTO settings) {
        String modelId = settings == null ? "" : StringUtils.trimToEmpty(settings.getLlmModelId());
        if (StringUtils.isNotBlank(modelId)) {
            ModelConfigEntity model = modelConfigService.getModelByIdFromCache(modelId);
            if (model == null || !"LLM".equalsIgnoreCase(model.getModelType())) {
                throw new RenException("请选择一个已配置的 LLM 模型作为智能助手模型");
            }
        }
        sysParamsService.updateValueByCode(ASSISTANT_LLM_MODEL_PARAM, StringUtils.defaultIfBlank(modelId, "null"));
        return getSettings();
    }

    @Override
    public ModelAssistantResponseDTO chat(ModelAssistantRequestDTO request) {
        if (request == null || StringUtils.isBlank(request.getMessage())) {
            throw new RenException("请输入需要助手处理的问题");
        }

        ModelAssistantSettingsDTO settings = getSettings();
        if (StringUtils.isBlank(settings.getLlmModelId())) {
            throw new RenException("请先选择模型配置助手使用的 LLM 模型");
        }

        ModelConfigEntity llmModel = modelConfigService.getModelByIdFromCache(settings.getLlmModelId());
        validateAssistantLlm(llmModel);

        String prompt = buildPrompt(request);
        String content = invokeLlm(llmModel, prompt);
        ModelAssistantResponseDTO response = parseResponse(content);
        response.setAgents(AGENT_NAMES);
        normalizeResponse(response, request);
        return response;
    }

    @Override
    public ModelAssistantApplyResponseDTO applyArtifacts(ModelAssistantApplyRequestDTO request) {
        if (request == null || request.getArtifacts() == null || request.getArtifacts().isEmpty()) {
            throw new RenException("没有可写入的自动化产物");
        }

        Path projectRoot = findProjectRoot();
        boolean overwrite = Boolean.TRUE.equals(request.getOverwrite());
        List<ArtifactWriteResultDTO> results = new ArrayList<>();
        for (IntegrationArtifactDTO artifact : request.getArtifacts()) {
            results.add(writeArtifact(projectRoot, artifact, overwrite));
        }

        ModelAssistantApplyResponseDTO response = new ModelAssistantApplyResponseDTO();
        response.setResults(results);
        response.setSuccess(results.stream().allMatch(item -> "written".equals(item.getStatus())
                || "skipped".equals(item.getStatus())));
        return response;
    }

    private void validateAssistantLlm(ModelConfigEntity llmModel) {
        if (llmModel == null || llmModel.getConfigJson() == null) {
            throw new RenException("模型配置助手使用的 LLM 不存在或配置为空");
        }
        if (!"LLM".equalsIgnoreCase(llmModel.getModelType())) {
            throw new RenException("模型配置助手只能选择 LLM 类型模型");
        }
        if (!Integer.valueOf(1).equals(llmModel.getIsEnabled())
                || !Integer.valueOf(1).equals(llmModel.getTestStatus())) {
            throw new RenException("模型配置助手使用的 LLM 必须先启用并测试通过");
        }
        JSONObject config = llmModel.getConfigJson();
        if (StringUtils.isBlank(config.getStr("base_url"))
                || StringUtils.isBlank(config.getStr("model_name"))
                || StringUtils.isBlank(config.getStr("api_key"))) {
            throw new RenException("助手 LLM 配置缺少 base_url、model_name 或 api_key");
        }
    }

    private String buildPrompt(ModelAssistantRequestDTO request) {
        JSONObject context = new JSONObject();
        context.set("modelType", request.getModelType());
        context.set("modelId", request.getModelId());
        context.set("providerCode", request.getProviderCode());
        context.set("configJson", redactJson(request.getConfigJson()));
        context.set("docLink", request.getDocLink());
        context.set("documentUrl", request.getDocumentUrl());
        context.set("documentText", trimToMax(request.getDocumentText(), 5000));
        context.set("testMessage", trimToMax(redactText(request.getTestMessage()), 3000));
        context.set("pageContext", request.getPageContext());
        context.set("knownAdapters", buildAdapterContext(request.getModelType()));

        String system = """
                你是小智 ESP32 Server 的模型配置智能助手。你由多个内部智能体协作：
                CoordinatorAgent 负责拆解用户目标；DocReaderAgent 提取文档字段；AdapterMatcherAgent 匹配项目已有适配器；
                ConfigDraftAgent 生成配置草案；MissingFieldAgent 追问缺失字段；ProtocolSpecAgent 在协议未支持时生成接入草案；
                ValidationAgent 阻止把未适配协议说成可用。

                严格规则：
                1. 只能基于 knownAdapters 判断项目是否已支持，不允许把厂商名相同但协议不同的产品说成可用。
                2. 如果文档或用户需求对应的协议没有匹配 adapter，supported 必须是 false，draftConfigJson 必须为空或只包含不可直接应用的提示字段。
                3. 如果没有匹配 adapter，必须输出 protocolSpecDraft，内容要足够开发者据此新增 provider：endpoint、鉴权签名、音频格式、发送流程、结果解析、必填字段、测试方式。
                4. 如果没有匹配 adapter，还必须输出 implementationTasks 和 integrationArtifacts，形成接入级自动化草案：
                   - implementationTasks 是开发审核清单，覆盖 provider 代码、目录注册、数据库字段、前后端表单、真实测试、文档。
                   - integrationArtifacts 是建议文件内容，不直接写入仓库；每项包含 path、purpose、language、content。
                   - 产物必须适配本项目结构，优先使用 main/xiaozhi-server 的 provider、manager-api 的 changelog、manager-web 的配置字段和测试用例。
                5. 配置草案只能填表单，不能代表测试通过；最终可用性必须走真实模型测试。
                6. 不要输出、猜测或伪造 API Key、Secret、Token。
                7. 只输出 JSON，不要 Markdown，不要代码块。

                JSON 结构：
                {
                  "answer": "给用户看的中文结论",
                  "supported": true,
                  "matchedAdapter": "openai",
                  "confidence": 0.8,
                  "draftConfigJson": {"type":"openai"},
                  "missingFields": ["api_key"],
                  "warnings": ["..."],
                  "protocolSpecDraft": "",
                  "implementationTasks": ["新增 provider 代码"],
                  "integrationArtifacts": [
                    {
                      "path": "main/xiaozhi-server/core/providers/asr/example.py",
                      "purpose": "新增 ASR provider 草案",
                      "language": "python",
                      "content": "..."
                    }
                  ],
                  "nextActions": ["..."]
                }
                """;

        return system + "\n\n用户问题：\n" + trimToMax(request.getMessage(), 2000)
                + "\n\n页面上下文 JSON：\n" + trimToMax(context.toStringPretty(), MAX_CONTEXT_CHARS);
    }

    private List<JSONObject> buildAdapterContext(String modelType) {
        List<ModelCatalogDTO> catalog = modelCatalogService.getCatalog(
                normalizeModelType(modelType), null, null, null, null);
        return catalog.stream()
                .filter(item -> !Boolean.TRUE.equals(item.getHidden()))
                .map(item -> {
                    JSONObject obj = new JSONObject();
                    obj.set("templateCode", item.getTemplateCode());
                    obj.set("modelType", item.getModelType());
                    obj.set("providerCode", item.getProviderCode());
                    obj.set("adapterName", item.getAdapterName());
                    obj.set("vendorName", item.getVendorName());
                    obj.set("productName", item.getProductName());
                    obj.set("protocolLabel", item.getProtocolLabel());
                    obj.set("requiredFields", item.getRequiredFields());
                    obj.set("credentialFields", item.getCredentialFields());
                    obj.set("supportedStatus", item.getSupportedStatus());
                    obj.set("selectable", item.getSelectable());
                    obj.set("aliases", item.getAliases());
                    obj.set("docLink", item.getDocLink());
                    obj.set("knownLimitations", item.getKnownLimitations());
                    return obj;
                })
                .toList();
    }

    private String invokeLlm(ModelConfigEntity llmModel, String prompt) {
        JSONObject config = llmModel.getConfigJson();
        String apiUrl = normalizeChatCompletionsUrl(config.getStr("base_url"));
        String apiKey = config.getStr("api_key");
        String model = config.getStr("model_name");

        List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", prompt));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("max_tokens", defaultNumber(config.getInt("max_tokens"), 3000));
        if (!StringUtils.containsIgnoreCase(config.getStr("base_url"), "api.deepseek.com")) {
            options.put("temperature", defaultDouble(config.getDouble("temperature"), 0.2D));
        }
        applyDeepSeekOptions(config, options);

        JSONObject body = (JSONObject) LlmUsageContext.call(
                LlmUsageContext.Identity.platform(llmModel.getId(), "CONFIG_ASSISTANT"),
                () -> llmClient.chatCompletion(apiUrl, apiKey, model, messages, options));
        JSONArray choices = body.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RenException("助手 LLM 没有返回候选结果");
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        String content = message == null ? "" : message.getStr("content");
        if (StringUtils.isBlank(content)) {
            throw new RenException("助手 LLM 返回内容为空");
        }
        return content;
    }

    private ModelAssistantResponseDTO parseResponse(String content) {
        ModelAssistantResponseDTO response = new ModelAssistantResponseDTO();
        try {
            String jsonText = extractJson(content);
            JSONObject json = JSONUtil.parseObj(jsonText);
            response.setAnswer(StringUtils.defaultIfBlank(json.getStr("answer"), content));
            response.setSupported(json.getBool("supported", false));
            response.setMatchedAdapter(json.getStr("matchedAdapter"));
            response.setConfidence(json.getDouble("confidence", 0D));
            response.setDraftConfigJson(json.getJSONObject("draftConfigJson"));
            response.setMissingFields(jsonArrayToList(json.getJSONArray("missingFields")));
            response.setWarnings(jsonArrayToList(json.getJSONArray("warnings")));
            response.setProtocolSpecDraft(json.getStr("protocolSpecDraft"));
            response.setImplementationTasks(jsonArrayToList(json.getJSONArray("implementationTasks")));
            response.setIntegrationArtifacts(parseArtifacts(json.getJSONArray("integrationArtifacts")));
            response.setNextActions(jsonArrayToList(json.getJSONArray("nextActions")));
            response.setStructured(true);
        } catch (Exception e) {
            log.warn("模型配置助手响应不是结构化 JSON，将按纯文本返回");
            response.setAnswer(content);
            response.setSupported(false);
            response.setWarnings(List.of("助手返回未能解析为结构化 JSON，请人工确认后再应用配置。"));
            response.setNextActions(List.of("重新描述需求，或粘贴更完整的官方文档。"));
            response.setStructured(false);
        }
        return response;
    }

    private void normalizeResponse(ModelAssistantResponseDTO response, ModelAssistantRequestDTO request) {
        if (response.getWarnings() == null) {
            response.setWarnings(List.of());
        }
        if (response.getMissingFields() == null) {
            response.setMissingFields(List.of());
        }
        if (response.getNextActions() == null) {
            response.setNextActions(List.of());
        }
        if (response.getImplementationTasks() == null) {
            response.setImplementationTasks(List.of());
        }
        if (response.getIntegrationArtifacts() == null) {
            response.setIntegrationArtifacts(List.of());
        }

        if (isXunfeiRtasrLlmRequest(request)) {
            response.setSupported(false);
            response.setDraftConfigJson(null);
            response.setMatchedAdapter("xunfei_rtasr_llm");
            response.setAnswer("这份文档对应的是讯飞实时语音转写大模型，不是当前 xunfei_stream 适配器支持的讯飞语音听写 WebAPI。现在不能直接生成可用配置，但可以生成接入包草案，由开发流程审核后新增独立适配器。");
            response.setProtocolSpecDraft(buildXunfeiRtasrLlmProtocolSpec());
            response.setImplementationTasks(buildXunfeiRtasrLlmTasks());
            response.setIntegrationArtifacts(buildXunfeiRtasrLlmArtifacts());
            response.setWarnings(deduplicate(List.of(
                    "当前 xunfei_stream 是讯飞 IAT/WebAPI 类适配器，不等于讯飞实时语音转写大模型。",
                    "网页助手只生成接入草案，不直接修改后端源码或数据库；合入前必须由开发流程审核并运行真实音频测试。")));
            response.setNextActions(deduplicate(List.of(
                    "确认官方文档中的 AppID、AccessKeyId、AccessKeySecret、endpoint 和签名规则。",
                    "按接入包草案新增 xunfei_rtasr_llm provider、后台字段和真实测试用例。",
                    "保存配置后用统一测试音频分别验证流式和非流式测试路径。")));
            return;
        }

        if (!Boolean.TRUE.equals(response.getSupported())) {
            response.setDraftConfigJson(null);
            if (StringUtils.isBlank(response.getAnswer())) {
                response.setAnswer("当前项目没有匹配该协议的适配器，不能直接生成可用配置。");
            }
            if (StringUtils.isBlank(response.getProtocolSpecDraft())) {
                response.setProtocolSpecDraft(buildGenericProtocolSpec(request));
            }
            if (response.getImplementationTasks().isEmpty()) {
                response.setImplementationTasks(buildGenericImplementationTasks(request));
            }
            if (response.getIntegrationArtifacts().isEmpty()) {
                response.setIntegrationArtifacts(buildGenericArtifacts(request));
            }
            return;
        }

        JSONObject draft = response.getDraftConfigJson();
        if (draft != null && StringUtils.isBlank(draft.getStr("type"))
                && StringUtils.isNotBlank(response.getMatchedAdapter())) {
            draft.set("type", response.getMatchedAdapter());
        }

        List<String> warnings = new ArrayList<>(response.getWarnings());
        warnings.add("助手草案只用于填表，不能代表模型测试通过。保存后必须执行真实模型测试。");
        response.setWarnings(deduplicate(warnings));

    }

    private boolean isXunfeiRtasrLlmRequest(ModelAssistantRequestDTO request) {
        return StringUtils.equalsIgnoreCase(request.getProviderCode(), "xunfei_stream")
                && StringUtils.containsAnyIgnoreCase(
                        StringUtils.defaultString(request.getMessage()) + " "
                                + StringUtils.defaultString(request.getDocumentText()) + " "
                                + StringUtils.defaultString(request.getDocumentUrl()),
                        "实时语音转写大模型", "office-api-ast-dx", "rtasr_llm");
    }

    private String buildXunfeiRtasrLlmProtocolSpec() {
        return """
                新增适配器建议：xunfei_rtasr_llm

                产品/协议：
                - 讯飞实时语音转写大模型（RTASR LLM），不要复用现有 xunfei_stream。
                - WebSocket endpoint 按官方文档使用 office-api-ast-dx.iflyaisol.com 的 ast communicate 路径。

                配置字段：
                - app_id：讯飞开放平台应用 ID。
                - access_key_id：实时语音转写大模型的 AccessKeyId。
                - access_key_secret：用于计算 signature 的密钥。
                - lang：识别语言，默认 cn。
                - audio_encode：音频编码，按文档默认 pcm 或 raw。
                - samplerate：采样率，默认 16000。

                鉴权：
                - 建连参数包含 appId、accessKeyId、utc、signature、lang、audio_encode、samplerate。
                - signature 必须按官方文档用 access_key_secret 对约定字符串做 HMAC/签名，不能沿用 IAT 的 api_key/api_secret 签名。

                音频发送：
                - 使用统一测试音频 wakeup_words_short.wav。
                - 转成 16k、16bit、mono PCM 后分片发送。
                - 按官方协议发送开始、音频分片、结束帧；不能沿用 IAT business/data/status 字段。

                结果解析：
                - 解析服务端转写事件中的文本片段。
                - 聚合最终识别文本，非空文本才算真实测试通过。
                - 保留原始错误码、错误消息和完整耗时，展示到测试详情。

                测试要求：
                - 单模型测试和批量测试都必须走真实音频识别。
                - 仅 WebSocket 连通、鉴权成功或字段完整不能标记通过。
                """;
    }

    private List<String> buildXunfeiRtasrLlmTasks() {
        return List.of(
                "新增 main/xiaozhi-server/core/providers/asr/xunfei_rtasr_llm.py，独立实现 RTASR LLM WebSocket 协议。",
                "在 Python ASR provider 工厂或配置映射中注册 type=xunfei_rtasr_llm，避免复用 xunfei_stream。",
                "新增 manager-api 数据库迁移，给 ASR 目录补 xunfei_rtasr_llm 字段定义：app_id、access_key_id、access_key_secret、lang、audio_encode、samplerate。",
                "在 manager-web 模型配置目录中展示新的接入方式和必填字段。",
                "新增真实音频测试用例，使用统一测试音频验证分片发送、结束帧、结果聚合和错误详情。",
                "更新 ASR 接入文档，记录该协议和现有 xunfei_stream 的边界。");
    }

    private List<IntegrationArtifactDTO> buildXunfeiRtasrLlmArtifacts() {
        return List.of(
                artifact(
                        "main/xiaozhi-server/core/providers/asr/xunfei_rtasr_llm.py",
                        "新增讯飞实时语音转写大模型 ASR provider 草案",
                        "python",
                        """
                        # 草案：需按讯飞 RTASR LLM 官方签名细节补齐并通过真实音频测试
                        import hmac
                        import hashlib
                        import time
                        import urllib.parse


                        class XunfeiRtasrLlmASR:
                            def __init__(self, config):
                                self.app_id = config["app_id"]
                                self.access_key_id = config["access_key_id"]
                                self.access_key_secret = config["access_key_secret"]
                                self.lang = config.get("lang", "cn")
                                self.audio_encode = config.get("audio_encode", "pcm")
                                self.samplerate = int(config.get("samplerate", 16000))
                                self.endpoint = config.get("endpoint", "wss://office-api-ast-dx.iflyaisol.com/ast/communicate")

                            def build_url(self):
                                utc = str(int(time.time()))
                                signing_text = f"{self.app_id}{self.access_key_id}{utc}"
                                signature = hmac.new(
                                    self.access_key_secret.encode("utf-8"),
                                    signing_text.encode("utf-8"),
                                    hashlib.sha256,
                                ).hexdigest()
                                query = urllib.parse.urlencode({
                                    "appId": self.app_id,
                                    "accessKeyId": self.access_key_id,
                                    "utc": utc,
                                    "signature": signature,
                                    "lang": self.lang,
                                    "audio_encode": self.audio_encode,
                                    "samplerate": self.samplerate,
                                })
                                return f"{self.endpoint}?{query}"

                            # Provider implementation to be added when specifications are confirmed
                        """),
                artifact(
                        "main/manager-api/src/main/resources/db/changelog/YYYYMMDDHHMM.sql",
                        "新增后台 ASR 接入方式和字段迁移草案",
                        "sql",
                        """
                        -- Provider implementation to be added when specifications are confirmed
                        """),
                artifact(
                        "main/xiaozhi-server/tests/test_xunfei_rtasr_llm_asr.py",
                        "真实音频识别测试草案",
                        "python",
                        """
                        # Provider implementation to be added when specifications are confirmed
                        """));
    }

    private String buildGenericProtocolSpec(ModelAssistantRequestDTO request) {
        String modelType = StringUtils.defaultIfBlank(normalizeModelType(request.getModelType()), "MODEL");
        return """
                当前没有匹配适配器，不能直接保存为可用配置。

                接入草案需要补齐：
                - 产品和协议名称：区分厂商同名不同产品，不能只按厂商匹配。
                - endpoint 和传输方式：HTTP、SSE、WebSocket 或 SDK。
                - 鉴权方式：请求头、查询参数、签名字符串和时间戳规则。
                - 必填配置字段：后台表单字段、密钥字段脱敏和默认值。
                - 输入输出契约：请求体、流式事件、最终结果字段和错误码。
                - 真实测试：使用实际输入验证输出内容，不能只验证连通性。

                目标模型类型：%s
                """.formatted(modelType);
    }

    private List<String> buildGenericImplementationTasks(ModelAssistantRequestDTO request) {
        String modelType = StringUtils.defaultIfBlank(normalizeModelType(request.getModelType()), "MODEL");
        return List.of(
                "确认官方文档中的协议、鉴权、请求体、响应体和错误码，形成 provider 契约。",
                "在 main/xiaozhi-server 对应 providers/" + modelType.toLowerCase() + " 目录新增独立适配器，避免复用不兼容 provider。",
                "在 manager-api 新增数据库迁移，注册 provider_code、展示名称和字段 schema。",
                "在 manager-web 模型配置页确认新增字段可编辑、密钥脱敏、保存后可回显。",
                "新增真实模型测试路径，断言返回有效业务结果而不是连通成功。",
                "补充文档，记录该适配器与现有相似适配器的协议边界。");
    }

    private List<IntegrationArtifactDTO> buildGenericArtifacts(ModelAssistantRequestDTO request) {
        String modelType = StringUtils.defaultIfBlank(normalizeModelType(request.getModelType()), "model");
        String adapter = responseSafeAdapterName(request);
        return List.of(
                artifact(
                        "main/xiaozhi-server/core/providers/" + modelType.toLowerCase() + "/" + adapter + ".py",
                        "新增 provider 草案",
                        "python",
                        """
                        # Provider implementation to be added when specifications are confirmed
                        """),
                artifact(
                        "main/manager-api/src/main/resources/db/changelog/YYYYMMDDHHMM.sql",
                        "新增 provider 字段迁移草案",
                        "sql",
                        """
                        -- Provider implementation to be added when specifications are confirmed
                        """),
                artifact(
                        "tests/integration/test_" + adapter + ".py",
                        "真实模型测试草案",
                        "python",
                        """
                        # Provider implementation to be added when specifications are confirmed
                        """));
    }

    private String responseSafeAdapterName(ModelAssistantRequestDTO request) {
        String value = StringUtils.defaultIfBlank(request.getProviderCode(), "new_provider");
        return value.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private IntegrationArtifactDTO artifact(String path, String purpose, String language, String content) {
        IntegrationArtifactDTO dto = new IntegrationArtifactDTO();
        dto.setPath(path);
        dto.setPurpose(purpose);
        dto.setLanguage(language);
        dto.setContent(content.stripIndent().trim());
        return dto;
    }

    private ArtifactWriteResultDTO writeArtifact(Path projectRoot, IntegrationArtifactDTO artifact, boolean overwrite) {
        ArtifactWriteResultDTO result = new ArtifactWriteResultDTO();
        String rawPath = artifact == null ? "" : StringUtils.trimToEmpty(artifact.getPath());
        result.setPath(rawPath);

        if (artifact == null || StringUtils.isBlank(rawPath) || StringUtils.isBlank(artifact.getContent())) {
            result.setStatus("failed");
            result.setMessage("产物路径或内容为空");
            return result;
        }

        String normalizedPath = rawPath.replace('\\', '/');
        if (!isAllowedArtifactPath(normalizedPath)) {
            result.setStatus("failed");
            result.setMessage("路径不在模型接入白名单内");
            return result;
        }

        try {
            Path target = projectRoot.resolve(normalizedPath).normalize();
            if (!target.startsWith(projectRoot)) {
                result.setStatus("failed");
                result.setMessage("路径越界，已拒绝写入");
                return result;
            }
            if (Files.exists(target) && !overwrite) {
                result.setStatus("skipped");
                result.setMessage("文件已存在，未覆盖");
                return result;
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, artifact.getContent() + System.lineSeparator(), StandardCharsets.UTF_8);
            result.setStatus("written");
            result.setMessage(overwrite ? "已写入工作区（允许覆盖）" : "已写入工作区");
            return result;
        } catch (InvalidPathException e) {
            result.setStatus("failed");
            result.setMessage("路径格式非法");
            return result;
        } catch (IOException e) {
            log.warn("模型配置助手写入产物失败: {}", rawPath, e);
            result.setStatus("failed");
            result.setMessage("写入失败：" + e.getMessage());
            return result;
        }
    }

    private boolean isAllowedArtifactPath(String path) {
        if (StringUtils.isBlank(path) || path.startsWith("/") || path.contains("../") || path.contains("..\\")) {
            return false;
        }
        return ARTIFACT_PATH_ALLOWLIST.stream().anyMatch(path::startsWith);
    }

    private Path findProjectRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        Path cursor = current;
        while (cursor != null) {
            if (Files.exists(cursor.resolve("AGENTS.md"))
                    && Files.isDirectory(cursor.resolve("main"))
                    && Files.isDirectory(cursor.resolve("docs"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new RenException("无法定位项目根目录，不能写入自动化产物");
    }

    private JSONObject redactJson(JSONObject source) {
        if (source == null) {
            return new JSONObject();
        }
        JSONObject target = new JSONObject();
        source.forEach((key, value) -> {
            String normalized = StringUtils.lowerCase(String.valueOf(key));
            if (SECRET_KEYS.contains(normalized) || normalized.contains("secret")
                    || normalized.contains("token") || normalized.contains("password")) {
                target.set(String.valueOf(key), value == null || StringUtils.isBlank(String.valueOf(value)) ? "" : "***");
            } else {
                target.set(String.valueOf(key), value);
            }
        });
        return target;
    }

    private String redactText(String text) {
        if (StringUtils.isBlank(text)) {
            return text;
        }
        return text.replaceAll("(?i)(api[_-]?key|api[_-]?secret|secret[_-]?key|token|password)\\s*[:=]\\s*[^\\s,;，；]+", "$1=***");
    }

    private String extractJson(String content) {
        String trimmed = StringUtils.trimToEmpty(content);
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String normalizeChatCompletionsUrl(String baseUrl) {
        String url = StringUtils.removeEnd(StringUtils.trimToEmpty(baseUrl), "/");
        if (url.endsWith("/chat/completions")) {
            return url;
        }
        return url + "/chat/completions";
    }

    private void applyDeepSeekOptions(JSONObject config, Map<String, Object> requestBody) {
        String baseUrl = config.getStr("base_url");
        if (!StringUtils.containsIgnoreCase(baseUrl, "api.deepseek.com")) {
            return;
        }
        String thinking = normalizeThinking(config.get("thinking"));
        if (thinking != null) {
            requestBody.put("thinking", Map.of("type", thinking));
        }
        String effort = StringUtils.defaultIfBlank(config.getStr("reasoning_effort"), "high");
        if ("enabled".equals(thinking) && ("high".equals(effort) || "max".equals(effort))) {
            requestBody.put("reasoning_effort", effort);
        }
    }

    private String normalizeThinking(Object value) {
        if (value == null) {
            return null;
        }
        String text;
        if (value instanceof JSONObject json) {
            text = json.getStr("type");
        } else if (value instanceof Map<?, ?> map) {
            Object type = map.get("type");
            text = type == null ? null : String.valueOf(type);
        } else {
            text = String.valueOf(value);
        }
        text = StringUtils.lowerCase(StringUtils.trimToEmpty(text));
        return switch (text) {
            case "enable", "enabled", "true", "on", "1" -> "enabled";
            case "disable", "disabled", "false", "off", "0" -> "disabled";
            default -> null;
        };
    }

    private List<String> jsonArrayToList(JSONArray array) {
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : array) {
            if (item != null && StringUtils.isNotBlank(String.valueOf(item))) {
                values.add(String.valueOf(item));
            }
        }
        return values;
    }

    private List<IntegrationArtifactDTO> parseArtifacts(JSONArray array) {
        if (array == null) {
            return List.of();
        }
        List<IntegrationArtifactDTO> values = new ArrayList<>();
        for (Object item : array) {
            if (!(item instanceof JSONObject json)) {
                continue;
            }
            IntegrationArtifactDTO artifact = new IntegrationArtifactDTO();
            artifact.setPath(trimToMax(json.getStr("path"), 500));
            artifact.setPurpose(trimToMax(json.getStr("purpose"), 500));
            artifact.setLanguage(trimToMax(json.getStr("language"), 80));
            artifact.setContent(trimToMax(redactText(json.getStr("content")), 8000));
            if (StringUtils.isNotBlank(artifact.getPath()) || StringUtils.isNotBlank(artifact.getContent())) {
                values.add(artifact);
            }
        }
        return values;
    }

    private List<String> deduplicate(List<String> source) {
        if (source == null) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        source.stream().filter(StringUtils::isNotBlank).forEach(values::add);
        return List.copyOf(values);
    }

    private String trimToMax(String value, int max) {
        String text = StringUtils.defaultString(value);
        return text.length() > max ? text.substring(0, max) + "...[truncated]" : text;
    }

    private String normalizeParamValue(String value) {
        return StringUtils.equalsIgnoreCase(value, "null") ? "" : StringUtils.trimToEmpty(value);
    }

    private String normalizeModelType(String value) {
        return StringUtils.isBlank(value) ? null : StringUtils.upperCase(value);
    }

    private int defaultNumber(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private double defaultDouble(Double value, double fallback) {
        return value == null ? fallback : value;
    }
}
