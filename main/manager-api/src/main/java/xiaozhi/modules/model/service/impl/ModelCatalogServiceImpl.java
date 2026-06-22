package xiaozhi.modules.model.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.dao.ModelTemplateDao;
import xiaozhi.modules.model.dao.ModelTemplateFieldDao;
import xiaozhi.modules.model.dto.ModelCatalogDTO;
import xiaozhi.modules.model.dto.ModelFieldSchemaDTO;
import xiaozhi.modules.model.dto.ModelMigrationPreviewDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.entity.ModelTemplateEntity;
import xiaozhi.modules.model.entity.ModelTemplateFieldEntity;
import xiaozhi.modules.model.service.ModelCatalogService;

@Service
@RequiredArgsConstructor
public class ModelCatalogServiceImpl implements ModelCatalogService {

    private final ModelConfigDao modelConfigDao;
    private final ModelTemplateDao modelTemplateDao;
    private final ModelTemplateFieldDao modelTemplateFieldDao;

    private static final List<ModelCatalogDTO> CATALOG = buildCatalog();
    private volatile boolean catalogSeedChecked = false;

    @Override
    public List<ModelCatalogDTO> getCatalog(String modelType, String providerCode, String capability, String vendor) {
        return effectiveCatalog().stream()
                .filter(item -> matches(item.getModelType(), modelType))
                .filter(item -> matches(item.getProviderCode(), providerCode))
                .filter(item -> matches(item.getCapability(), capability))
                .filter(item -> containsIgnoreCase(item.getVendorName(), vendor))
                .sorted(Comparator.comparing(ModelCatalogDTO::getModelType, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(item -> item.getSort() == null ? 9999 : item.getSort())
                        .thenComparing(ModelCatalogDTO::getTemplateCode, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public List<ModelCatalogDTO> getTemplates(String modelType, String providerCode) {
        return getCatalog(modelType, providerCode, null, null).stream()
                .filter(item -> !Boolean.TRUE.equals(item.getHidden()))
                .filter(item -> !Boolean.TRUE.equals(item.getDeprecated()))
                .toList();
    }

    @Override
    public ModelCatalogDTO resolve(ModelConfigEntity entity) {
        if (entity == null) {
            return null;
        }
        JSONObject configJson = entity.getConfigJson();
        String providerCode = firstNonBlank(entity.getAdapterCode(), configJson == null ? "" : configJson.getStr("type"));
        return resolve(entity.getModelType(), providerCode, entity.getModelCode(), entity.getModelName(), configJson);
    }

    @Override
    public ModelCatalogDTO resolve(String modelType, String providerCode, String modelCode, String modelName,
            JSONObject configJson) {
        String type = normalize(modelType);
        String provider = normalize(providerCode);
        List<ModelCatalogDTO> candidates = effectiveCatalog().stream()
                .filter(item -> normalize(item.getModelType()).equals(type))
                .filter(item -> normalize(item.getProviderCode()).equals(provider))
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }

        String resource = firstConfigString(configJson, "resource_id", "model", "model_name", "voice", "speaker",
                "model_dir", "base_url", "api_url", "url", "host");
        String haystack = normalize(String.join(" ", StringUtils.defaultString(modelCode),
                StringUtils.defaultString(modelName), resource,
                configJson == null ? "" : configJson.toString()));

        ModelCatalogDTO exact = candidates.stream()
                .filter(item -> normalize(item.getTemplateCode()).equals(normalize(modelCode))
                        || item.getAliases().stream().anyMatch(alias -> haystack.contains(normalize(alias))))
                .findFirst()
                .orElse(null);
        if (exact != null) {
            return exact;
        }

        if ("ASR".equalsIgnoreCase(modelType) && "doubao_stream".equalsIgnoreCase(providerCode)) {
            if (haystack.contains("seedasr")) {
                return find("ASR_DOUBAO_SEED_ASR_2_STREAM");
            }
            return find("ASR_DOUBAO_BIGASR_STREAM");
        }
        if ("TTS".equalsIgnoreCase(modelType) && "huoshan_double_stream".equalsIgnoreCase(providerCode)) {
            if (haystack.contains("seed-tts-2.0")) {
                return find("TTS_DOUBAO_SEED_TTS_2_STREAM");
            }
            return find("TTS_DOUBAO_BIGTTS_STREAM");
        }
        if (("LLM".equalsIgnoreCase(modelType) || "SLM".equalsIgnoreCase(modelType)
                || "VLLM".equalsIgnoreCase(modelType)) && "openai".equalsIgnoreCase(providerCode)) {
            ModelCatalogDTO inferred = inferOpenAiCompatible(modelType, haystack);
            if (inferred != null) {
                return inferred;
            }
        }
        if ("TTS".equalsIgnoreCase(modelType) && "openai".equalsIgnoreCase(providerCode)) {
            if (haystack.contains("volces") || haystack.contains("ai-gateway")) {
                return find("TTS_VOLCES_AI_GATEWAY_OPENAI");
            }
            return find("TTS_OPENAI_SPEECH");
        }
        if ("ASR".equalsIgnoreCase(modelType) && "openai".equalsIgnoreCase(providerCode)) {
            if (haystack.contains("groq")) {
                return find("ASR_GROQ_OPENAI_TRANSCRIBE");
            }
            return find("ASR_OPENAI_TRANSCRIBE");
        }
        return candidates.get(0);
    }

    @Override
    public List<ModelMigrationPreviewDTO> previewMigration(String modelType) {
        List<ModelConfigEntity> entities = modelConfigDao.selectList(
                new QueryWrapper<ModelConfigEntity>()
                        .eq(StringUtils.isNotBlank(modelType), "model_type", modelType)
                        .orderByAsc("model_type", "sort"));
        return entities.stream().map(this::previewOne).toList();
    }

    private ModelMigrationPreviewDTO previewOne(ModelConfigEntity entity) {
        ModelMigrationPreviewDTO preview = new ModelMigrationPreviewDTO();
        preview.setId(entity.getId());
        preview.setModelName(entity.getModelName());
        preview.setModelType(entity.getModelType());
        JSONObject configJson = entity.getConfigJson();
        String providerCode = configJson == null ? "" : StringUtils.defaultString(configJson.getStr("type"));
        preview.setProviderCode(providerCode);

        ModelCatalogDTO template = resolve(entity);
        if (template == null) {
            preview.setStatus("UNSUPPORTED");
            preview.setNotes(List.of("未匹配到标准模型模板，需要先补充目录元数据或手动确认。"));
            return preview;
        }

        preview.setTemplateCode(template.getTemplateCode());
        preview.setTemplateName(template.getVendorName() + " / " + template.getProductName() + " / "
                + template.getAccessModeLabel());
        List<String> missing = template.getRequiredFields().stream()
                .filter(field -> !hasUsableConfigValue(configJson, field))
                .toList();
        preview.setMissingFields(missing);
        preview.setDeprecatedFields(findDeprecatedFields(entity, template));
        List<String> notes = new ArrayList<>();
        if (missing.isEmpty()) {
            preview.setStatus("AUTO");
            notes.add("可自动整理为标准模型运行时配置。");
        } else {
            preview.setStatus("NEEDS_REVIEW");
            notes.add("缺少必填字段，迁移前需要补齐或确认。");
        }
        notes.addAll(template.getKnownLimitations());
        preview.setNotes(notes);
        return preview;
    }

    private List<String> findDeprecatedFields(ModelConfigEntity entity, ModelCatalogDTO template) {
        JSONObject configJson = entity.getConfigJson();
        if (configJson == null) {
            return List.of();
        }
        Set<String> fields = new LinkedHashSet<>();
        if ("TTS_PaddleSpeechTTS".equals(entity.getId()) && "http".equalsIgnoreCase(configJson.getStr("protocol"))) {
            fields.add("protocol=http");
        }
        if ("TTS_IndexStreamTTS".equals(entity.getId()) && configJson.containsKey("audio_format")) {
            fields.add("audio_format");
        }
        if ("ASR".equalsIgnoreCase(entity.getModelType()) && "tencent".equalsIgnoreCase(template.getProviderCode())
                && configJson.containsKey("appid")) {
            fields.add("appid");
        }
        return new ArrayList<>(fields);
    }

    private ModelCatalogDTO inferOpenAiCompatible(String modelType, String haystack) {
        String type = "VLLM".equalsIgnoreCase(modelType) ? "VLLM" : "LLM";
        if (haystack.contains("dashscope") || haystack.contains("qwen")) {
            return "VLLM".equals(type) ? find("VLLM_QWEN_VL_OPENAI") : find("LLM_ALI_QWEN_OPENAI");
        }
        if (haystack.contains("ark.cn") || haystack.contains("doubao")) {
            return find("LLM_DOUBAO_ARK_OPENAI");
        }
        if (haystack.contains("deepseek")) {
            return find("LLM_DEEPSEEK_OPENAI");
        }
        if (haystack.contains("bigmodel") || haystack.contains("glm")) {
            return "VLLM".equals(type) ? find("VLLM_ZHIPU_GLM_OPENAI") : find("LLM_ZHIPU_GLM_OPENAI");
        }
        if (haystack.contains("xf-yun") || haystack.contains("spark")) {
            return "VLLM".equals(type) ? find("VLLM_XUNFEI_SPARK_OPENAI") : find("LLM_OPENAI_COMPATIBLE");
        }
        if (haystack.contains("localhost:1234") || haystack.contains("lm-studio")) {
            return find("LLM_LM_STUDIO_OPENAI");
        }
        return "VLLM".equals(type) ? find("VLLM_OPENAI_COMPATIBLE") : find("LLM_OPENAI_COMPATIBLE");
    }

    private ModelCatalogDTO find(String templateCode) {
        return effectiveCatalog().stream()
                .filter(item -> templateCode.equals(item.getTemplateCode()))
                .findFirst()
                .orElse(null);
    }

    private List<ModelCatalogDTO> effectiveCatalog() {
        ensureCatalogSeeded();
        List<ModelCatalogDTO> fromDb = loadCatalogFromDatabase();
        return fromDb.isEmpty() ? CATALOG : fromDb;
    }

    private synchronized void ensureCatalogSeeded() {
        if (catalogSeedChecked) {
            return;
        }
        Long count = modelTemplateDao.selectCount(new QueryWrapper<>());
        if (count == null || count == 0) {
            seedCatalogTables();
        }
        catalogSeedChecked = true;
    }

    private void seedCatalogTables() {
        for (ModelCatalogDTO template : CATALOG) {
            ModelTemplateEntity entity = new ModelTemplateEntity();
            entity.setId(template.getTemplateCode());
            entity.setTemplateCode(template.getTemplateCode());
            entity.setModelType(template.getModelType());
            entity.setAdapterCode(template.getProviderCode());
            entity.setVendorName(template.getVendorName());
            entity.setProductName(template.getProductName());
            entity.setCapability(template.getCapability());
            entity.setAccessMode(template.getAccessMode());
            entity.setDeployment(template.getDeployment());
            entity.setProtocol(template.getProtocolLabel());
            entity.setUpstreamModelField(template.getUpstreamModelField());
            entity.setDocLink(template.getDocLink());
            entity.setEnabled(Boolean.TRUE.equals(template.getHidden()) || Boolean.TRUE.equals(template.getDeprecated()) ? 0 : 1);
            entity.setSort(template.getSort());
            modelTemplateDao.insert(entity);

            for (ModelFieldSchemaDTO schema : template.getFieldSchemas()) {
                ModelTemplateFieldEntity field = new ModelTemplateFieldEntity();
                field.setTemplateId(template.getTemplateCode());
                field.setFieldKey(schema.getKey());
                field.setFieldLabel(schema.getLabel());
                field.setFieldGroup(StringUtils.defaultIfBlank(schema.getGroup(), "advanced"));
                field.setValueType(valueTypeForSchema(schema));
                field.setInputType(inputTypeForSchema(schema));
                field.setRuntimeKey(StringUtils.defaultIfBlank(schema.getRuntimeKey(), schema.getKey()));
                field.setAdvanced(Boolean.TRUE.equals(schema.getAdvanced()) ? 1 : 0);
                field.setVisibleWhen(schema.getVisibleWhen());
                field.setRequired(Boolean.TRUE.equals(schema.getRequired()) ? 1 : 0);
                field.setSecret(Boolean.TRUE.equals(schema.getSecret()) ? 1 : 0);
                field.setDefaultValue(serializeDefaultValue(schema.getDefaultValue()));
                field.setOptionsJson(schema.getOptions() == null || schema.getOptions().isEmpty()
                        ? null
                        : JSONUtil.toJsonStr(schema.getOptions()));
                field.setHelpText(schema.getHelpText());
                field.setSort(schema.getOrder());
                modelTemplateFieldDao.insert(field);
            }
        }
    }

    private List<ModelCatalogDTO> loadCatalogFromDatabase() {
        List<ModelTemplateEntity> templates = modelTemplateDao.selectList(new QueryWrapper<ModelTemplateEntity>()
                .orderByAsc("model_type", "sort", "template_code"));
        if (templates == null || templates.isEmpty()) {
            return List.of();
        }
        List<ModelTemplateFieldEntity> fields = modelTemplateFieldDao.selectList(new QueryWrapper<ModelTemplateFieldEntity>()
                .orderByAsc("template_id", "sort", "field_key"));
        Map<String, List<ModelTemplateFieldEntity>> fieldsByTemplate = new HashMap<>();
        for (ModelTemplateFieldEntity field : fields) {
            fieldsByTemplate.computeIfAbsent(field.getTemplateId(), key -> new ArrayList<>()).add(field);
        }
        return templates.stream()
                .map(template -> toCatalogDTO(template, fieldsByTemplate.getOrDefault(template.getTemplateCode(), List.of())))
                .toList();
    }

    private ModelCatalogDTO toCatalogDTO(ModelTemplateEntity entity, List<ModelTemplateFieldEntity> fields) {
        ModelCatalogDTO dto = new ModelCatalogDTO();
        ModelCatalogDTO staticMeta = CATALOG.stream()
                .filter(item -> StringUtils.equals(item.getTemplateCode(), entity.getTemplateCode()))
                .findFirst()
                .orElse(null);

        dto.setTemplateCode(entity.getTemplateCode());
        dto.setModelType(entity.getModelType());
        dto.setProviderCode(entity.getAdapterCode());
        dto.setAdapterName(staticMeta == null ? entity.getProductName() : staticMeta.getAdapterName());
        dto.setVendorName(entity.getVendorName());
        dto.setProductName(entity.getProductName());
        dto.setCapability(entity.getCapability());
        dto.setCapabilityLabel(staticMeta == null ? entity.getCapability() : staticMeta.getCapabilityLabel());
        dto.setAccessMode(entity.getAccessMode());
        dto.setAccessModeLabel(staticMeta == null ? entity.getAccessMode() : staticMeta.getAccessModeLabel());
        dto.setDeployment(entity.getDeployment());
        dto.setDeploymentLabel(staticMeta == null ? entity.getDeployment() : staticMeta.getDeploymentLabel());
        dto.setProtocolLabel(StringUtils.defaultIfBlank(entity.getProtocol(), staticMeta == null ? "" : staticMeta.getProtocolLabel()));
        dto.setUpstreamResourceLabel(staticMeta == null ? entity.getUpstreamModelField() : staticMeta.getUpstreamResourceLabel());
        dto.setUpstreamModelField(entity.getUpstreamModelField());
        dto.setCredentialFields(fields.stream().filter(field -> intValue(field.getSecret()) == 1)
                .map(ModelTemplateFieldEntity::getFieldKey).toList());
        dto.setRequiredFields(fields.stream().filter(field -> intValue(field.getRequired()) == 1)
                .map(ModelTemplateFieldEntity::getFieldKey).toList());
        dto.setRecommendedFields(fields.stream()
                .filter(field -> intValue(field.getRequired()) == 0 && intValue(field.getSecret()) == 0)
                .map(ModelTemplateFieldEntity::getFieldKey).toList());
        dto.setFieldSchemas(fields.stream().map(this::toFieldSchemaDTO).toList());
        dto.setDocLink(StringUtils.defaultIfBlank(entity.getDocLink(), staticMeta == null ? "" : staticMeta.getDocLink()));
        dto.setSetupHints(staticMeta == null ? List.of() : staticMeta.getSetupHints());
        dto.setKnownLimitations(staticMeta == null ? List.of() : staticMeta.getKnownLimitations());
        dto.setAliases(staticMeta == null ? List.of() : staticMeta.getAliases());
        dto.setHidden(intValue(entity.getEnabled()) == 0);
        dto.setDeprecated(staticMeta != null && Boolean.TRUE.equals(staticMeta.getDeprecated()));
        dto.setSort(entity.getSort());
        return dto;
    }

    private ModelFieldSchemaDTO toFieldSchemaDTO(ModelTemplateFieldEntity entity) {
        ModelFieldSchemaDTO dto = new ModelFieldSchemaDTO();
        dto.setKey(entity.getFieldKey());
        dto.setLabel(StringUtils.defaultIfBlank(entity.getFieldLabel(), labelFor(entity.getFieldKey())));
        dto.setType(StringUtils.defaultIfBlank(entity.getValueType(), "string"));
        dto.setInputType(StringUtils.defaultIfBlank(entity.getInputType(), inputTypeForType(dto.getType())));
        dto.setGroup(StringUtils.defaultIfBlank(entity.getFieldGroup(), groupFor(entity.getFieldKey(), intValue(entity.getSecret()) == 1)));
        dto.setRuntimeKey(StringUtils.defaultIfBlank(entity.getRuntimeKey(), entity.getFieldKey()));
        dto.setAdvanced(intValue(entity.getAdvanced()) == 1);
        dto.setVisibleWhen(entity.getVisibleWhen());
        dto.setRequired(intValue(entity.getRequired()) == 1);
        dto.setSecret(intValue(entity.getSecret()) == 1);
        dto.setDefaultValue(parseDefaultValue(entity.getDefaultValue(), dto.getType()));
        dto.setOptions(parseOptions(entity.getOptionsJson()));
        dto.setHelpText(entity.getHelpText());
        dto.setOrder(entity.getSort());
        return dto;
    }

    private String valueTypeForSchema(ModelFieldSchemaDTO schema) {
        String type = StringUtils.defaultString(schema.getType(), "string");
        if ("password".equalsIgnoreCase(type) || "select".equalsIgnoreCase(type)) {
            return "string";
        }
        return type;
    }

    private String inputTypeForSchema(ModelFieldSchemaDTO schema) {
        if (StringUtils.isNotBlank(schema.getInputType())) {
            return schema.getInputType();
        }
        if (Boolean.TRUE.equals(schema.getSecret())) {
            return "password";
        }
        if (schema.getOptions() != null && !schema.getOptions().isEmpty()) {
            return "select";
        }
        return inputTypeForType(schema.getType());
    }

    private String inputTypeForType(String type) {
        return switch (StringUtils.defaultString(type, "string")) {
            case "number" -> "number";
            case "boolean" -> "switch";
            case "json" -> "textarea";
            default -> "text";
        };
    }

    private String serializeDefaultValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JSONObject || value instanceof Map<?, ?> || value instanceof List<?>) {
            return JSONUtil.toJsonStr(value);
        }
        return String.valueOf(value);
    }

    private Object parseDefaultValue(String value, String valueType) {
        if (value == null) {
            return "";
        }
        try {
            return switch (StringUtils.defaultString(valueType, "string")) {
                case "number" -> {
                    double number = Double.parseDouble(value);
                    yield number == (long) number ? (long) number : number;
                }
                case "boolean" -> Boolean.valueOf(value);
                case "json" -> JSONUtil.parse(value);
                default -> value;
            };
        } catch (Exception ignored) {
            return value;
        }
    }

    private List<String> parseOptions(String optionsJson) {
        if (StringUtils.isBlank(optionsJson)) {
            return List.of();
        }
        try {
            return JSONUtil.parseArray(optionsJson).stream()
                    .map(String::valueOf)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private int intValue(Integer value) {
        return value == null ? 0 : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private static boolean matches(String value, String filter) {
        return StringUtils.isBlank(filter) || StringUtils.equalsIgnoreCase(value, filter);
    }

    private static boolean containsIgnoreCase(String value, String filter) {
        return StringUtils.isBlank(filter)
                || StringUtils.containsIgnoreCase(StringUtils.defaultString(value), filter);
    }

    private static String normalize(String value) {
        return StringUtils.defaultString(value).toLowerCase(Locale.ROOT);
    }

    private static String firstConfigString(JSONObject configJson, String... keys) {
        if (configJson == null) {
            return "";
        }
        for (String key : keys) {
            String value = configJson.getStr(key);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private static boolean hasUsableConfigValue(JSONObject configJson, String field) {
        if (configJson == null || !configJson.containsKey(field)) {
            return false;
        }
        Object value = configJson.get(field);
        if (value == null) {
            return false;
        }
        String text = StringUtils.trim(String.valueOf(value));
        return StringUtils.isNotBlank(text)
                && !"null".equalsIgnoreCase(text)
                && !StringUtils.startsWith(text, "你的")
                && !StringUtils.startsWith(text, "（选填）你的");
    }

    private static List<ModelCatalogDTO> buildCatalog() {
        List<ModelCatalogDTO> items = new ArrayList<>();
        addAsr(items);
        addTts(items);
        addLlm(items);
        addVllm(items);
        addMemoryIntentRagVad(items);
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setSort(i + 1);
        }
        return List.copyOf(items);
    }

    private static void addAsr(List<ModelCatalogDTO> items) {
        items.add(item("ASR_SHERPA_SENSE_VOICE", "ASR", "sherpa_onnx_local", "Sherpa-ONNX",
                "Sherpa-ONNX", "SenseVoice 本地识别", "asr", "语音识别", "local", "本地离线",
                "local", "本地部署", "ONNX", "模型目录", "model_dir",
                List.of(), List.of("model_dir"), List.of("model_dir", "model_type"),
                List.of("下载模型后填写服务器上的模型目录。"), List.of(), List.of("SherpaASR", "sense_voice"),
                "https://github.com/k2-fsa/sherpa-onnx"));
        items.add(item("ASR_SHERPA_PARAFORMER", "ASR", "sherpa_onnx_local", "Sherpa-ONNX",
                "Sherpa-ONNX", "Paraformer 本地识别", "asr", "语音识别", "local", "本地离线",
                "local", "本地部署", "ONNX", "模型目录", "model_dir",
                List.of(), List.of("model_dir"), List.of("model_dir", "model_type"),
                List.of("中文专用轻量模型，适合低性能设备。"), List.of(), List.of("SherpaParaformerASR", "paraformer"),
                "https://github.com/k2-fsa/sherpa-onnx"));
        items.add(item("ASR_FUN_LOCAL", "ASR", "fun_local", "FunASR 本地",
                "FunASR", "本地语音识别", "asr", "语音识别", "local", "本地离线",
                "local", "本地部署", "Python", "模型目录", "model_dir",
                List.of(), List.of("model_dir"), List.of("model_dir"),
                List.of("模型文件在服务器本地，配置时不要填写云平台密钥。"), List.of(), List.of("FunASR"),
                "https://github.com/modelscope/FunASR"));
        items.add(item("ASR_FUN_SERVER", "ASR", "fun_server", "FunASR 服务",
                "FunASR", "独立服务识别", "asr", "语音识别", "service", "自部署服务",
                "local", "本地/内网服务", "HTTP/WebSocket", "服务地址", "host",
                List.of("api_key"), List.of("host", "port"), List.of("host", "port", "type", "is_ssl"),
                List.of("适合把 FunASR 单独部署成服务后接入。"), List.of(), List.of("FunASRServer"),
                "https://github.com/modelscope/FunASR"));
        items.add(item("ASR_DOUBAO_LEGACY_HTTP", "ASR", "doubao", "豆包旧版 ASR",
                "火山引擎", "豆包语音识别旧版", "asr", "语音识别", "http", "HTTP 非流式",
                "cloud", "云平台", "HTTP", "集群", "cluster",
                List.of("appid", "access_token"), List.of("appid", "access_token", "cluster"),
                List.of("appid", "access_token", "cluster", "boosting_table_name", "correct_table_name"),
                List.of("这是旧版按次识别接口，不是 Seed ASR 2.0。"), List.of(), List.of("DoubaoASR"),
                "https://www.volcengine.com/docs/6561"));
        items.add(item("ASR_DOUBAO_BIGASR_STREAM", "ASR", "doubao_stream", "豆包大模型 ASR",
                "火山引擎", "豆包大模型流式语音识别", "asr", "语音识别", "websocket", "WebSocket 流式",
                "cloud", "云平台", "WebSocket", "Resource ID", "resource_id",
                List.of("appid", "access_token"), List.of("appid", "access_token", "resource_id"),
                List.of("resource_id", "end_window_size", "enable_multilingual", "language"),
                List.of("旧大模型 ASR 资源 ID 常见为 volc.bigasr.sauc.duration。"),
                List.of(), List.of("DoubaoStreamASR", "volc.bigasr"),
                "https://www.volcengine.com/docs/6561/1354869"));
        items.add(item("ASR_DOUBAO_SEED_ASR_2_STREAM", "ASR", "doubao_stream", "Seed ASR 2.0",
                "火山引擎", "豆包语音识别 Seed ASR 2.0", "asr", "语音识别", "websocket", "WebSocket 流式",
                "cloud", "云平台", "WebSocket", "Resource ID", "resource_id",
                List.of("appid", "access_token"), List.of("appid", "access_token", "resource_id"),
                List.of("resource_id", "end_window_size", "enable_multilingual", "language"),
                List.of("Seed ASR 2.0 资源 ID 常见为 volc.seedasr.sauc.duration。"),
                List.of(), List.of("DoubaoStreamASRV2", "seedasr", "volc.seedasr"),
                "https://www.volcengine.com/docs/6561/1354869"));
        items.add(item("ASR_TENCENT_SENTENCE", "ASR", "tencent", "腾讯云 ASR",
                "腾讯云", "一句话识别", "asr", "语音识别", "http", "HTTP 非流式",
                "cloud", "云平台", "HTTP TC3", "区域/密钥", "secret_id",
                List.of("secret_id", "secret_key"), List.of("secret_id", "secret_key"),
                List.of("secret_id", "secret_key", "output_dir"),
                List.of("当前运行时代码主要使用 SecretId/SecretKey，appid 仅作为旧模板字段保留。"),
                List.of("旧 appid 字段会在标准表单中弱化。"), List.of("TencentASR"),
                "https://cloud.tencent.com/document/product/1093"));
        items.add(item("ASR_ALIYUN_NLS_HTTP", "ASR", "aliyun", "阿里云 NLS ASR",
                "阿里云", "智能语音交互一句话识别", "asr", "语音识别", "http", "HTTP 非流式",
                "cloud", "云平台", "HTTP", "AppKey", "appkey",
                List.of("token", "access_key_id", "access_key_secret"), List.of("appkey"),
                List.of("appkey", "token", "access_key_id", "access_key_secret"),
                List.of("token 和 AccessKey 二选一，长期运行建议使用 AccessKey 自动换 token。"),
                List.of(), List.of("AliyunASR"),
                "https://help.aliyun.com/zh/isi"));
        items.add(item("ASR_ALIYUN_NLS_STREAM", "ASR", "aliyun_stream", "阿里云 NLS 流式 ASR",
                "阿里云", "智能语音交互实时识别", "asr", "语音识别", "websocket", "WebSocket 流式",
                "cloud", "云平台", "WebSocket", "AppKey", "appkey",
                List.of("token", "access_key_id", "access_key_secret"), List.of("appkey"),
                List.of("appkey", "host", "max_sentence_silence"),
                List.of("host 决定接入地域，实时交互优先选择低延迟地域。"),
                List.of(), List.of("AliyunStreamASR"),
                "https://help.aliyun.com/zh/isi"));
        items.add(item("ASR_BAIDU_SPEECH", "ASR", "baidu", "百度语音 ASR",
                "百度智能云", "短语音识别", "asr", "语音识别", "http", "HTTP 非流式",
                "cloud", "云平台", "HTTP", "语言参数", "dev_pid",
                List.of("app_id", "api_key", "secret_key"), List.of("app_id", "api_key", "secret_key"),
                List.of("dev_pid"), List.of("dev_pid 表示识别语言和模型。"), List.of(), List.of("BaiduASR"),
                "https://ai.baidu.com/ai-doc/SPEECH/0lbxfnc9b"));
        items.add(item("ASR_OPENAI_TRANSCRIBE", "ASR", "openai", "OpenAI 转写",
                "OpenAI", "Speech to Text", "asr", "语音识别", "http", "HTTP 非流式",
                "cloud", "云平台", "OpenAI Audio", "模型", "model_name",
                List.of("api_key"), List.of("api_key", "base_url", "model_name"),
                List.of("base_url", "model_name"), List.of("base_url 必须指向 audio/transcriptions 接口。"),
                List.of(), List.of("OpenaiASR", "gpt-4o-mini-transcribe"),
                "https://developers.openai.com/api/docs/guides/speech-to-text"));
        items.add(item("ASR_GROQ_OPENAI_TRANSCRIBE", "ASR", "openai", "Groq Whisper",
                "Groq", "OpenAI 兼容转写", "asr", "语音识别", "http", "HTTP 非流式",
                "cloud", "云平台", "OpenAI Audio Compatible", "模型", "model_name",
                List.of("api_key"), List.of("api_key", "base_url", "model_name"),
                List.of("base_url", "model_name"), List.of("Groq 使用 OpenAI 兼容转写接口。"),
                List.of(), List.of("GroqASR", "whisper-large-v3"),
                "https://console.groq.com/docs/speech-to-text"));
        items.add(item("ASR_VOSK_LOCAL", "ASR", "vosk", "Vosk 本地 ASR",
                "Vosk", "离线语音识别", "asr", "语音识别", "local", "本地离线",
                "local", "本地部署", "Python", "模型路径", "model_path",
                List.of(), List.of("model_path"), List.of("model_path"),
                List.of("完全离线，中文模型通常不带标点。"), List.of(), List.of("VoskASR"),
                "https://alphacephei.com/vosk/models"));
        items.add(item("ASR_QWEN3_FLASH", "ASR", "qwen3_asr_flash", "Qwen3-ASR-Flash",
                "阿里云百炼", "通义千问 ASR Flash", "asr", "语音识别", "openai-compatible", "OpenAI 兼容",
                "cloud", "云平台", "OpenAI Compatible", "模型", "model_name",
                List.of("api_key"), List.of("api_key", "base_url", "model_name"),
                List.of("enable_lid", "enable_itn", "language", "context"),
                List.of("适合多模态基座 ASR，不是 Paraformer WebSocket 实时识别。"),
                List.of(), List.of("Qwen3ASRFlash", "qwen3-asr-flash"),
                "https://help.aliyun.com/zh/model-studio"));
        items.add(item("ASR_XUNFEI_STREAM", "ASR", "xunfei_stream", "讯飞流式 ASR",
                "讯飞开放平台", "语音听写流式 WebAPI", "asr", "语音识别", "websocket", "WebSocket 流式",
                "cloud", "云平台", "WebSocket", "领域", "domain",
                List.of("app_id", "api_key", "api_secret"), List.of("app_id", "api_key", "api_secret"),
                List.of("domain", "language", "accent"),
                List.of("APPID、APIKey、APISecret 都来自讯飞开放平台应用。"),
                List.of(), List.of("XunfeiStreamASR"),
                "https://www.xfyun.cn/doc/asr/voicedictation/API.html"));
        items.add(item("ASR_ALIYUN_BAILIAN_PARAFORMER", "ASR", "aliyunbl_stream", "百炼 Paraformer",
                "阿里云百炼", "Paraformer 实时语音识别", "asr", "语音识别", "websocket", "WebSocket 流式",
                "cloud", "云平台", "DashScope WebSocket", "模型", "model",
                List.of("api_key"), List.of("api_key", "model"),
                List.of("model", "sample_rate", "semantic_punctuation_enabled", "max_sentence_silence"),
                List.of("推荐 paraformer-realtime-v2；v1/8k 版本的采样率约束不同。"),
                List.of(), List.of("AliyunBLStreamASR", "paraformer-realtime-v2"),
                "https://help.aliyun.com/zh/model-studio/websocket-for-paraformer-real-time-service"));
    }

    private static void addTts(List<ModelCatalogDTO> items) {
        items.add(item("TTS_EDGE", "TTS", "edge", "Edge TTS",
                "Microsoft", "Edge TTS", "tts", "语音合成", "library", "本地库",
                "local", "本地运行", "edge-tts", "音色", "voice",
                List.of(), List.of("voice"), List.of("voice"),
                List.of("无需云端密钥，依赖 edge-tts 库。"), List.of(), List.of("EdgeTTS"),
                "https://github.com/rany2/edge-tts"));
        items.add(item("TTS_DOUBAO_HTTP", "TTS", "doubao", "豆包 TTS HTTP",
                "火山引擎", "豆包语音合成旧版", "tts", "语音合成", "http", "HTTP 非流式",
                "cloud", "云平台", "HTTP", "音色", "voice",
                List.of("access_token", "authorization", "appid"), List.of("api_url", "voice"),
                List.of("appid", "access_token", "cluster", "speed_ratio", "volume_ratio", "pitch_ratio"),
                List.of("旧版 HTTP TTS，不是双向流式 Seed TTS 2.0。"), List.of(),
                List.of("DoubaoTTS", "TTS302AI"), "https://www.volcengine.com/docs/6561"));
        items.add(item("TTS_DOUBAO_BIGTTS_STREAM", "TTS", "huoshan_double_stream", "豆包大模型 TTS",
                "火山引擎", "豆包语音合成大模型", "tts", "语音合成", "websocket-bidirectional", "WebSocket 双向流式",
                "cloud", "云平台", "WebSocket", "Resource ID", "resource_id",
                List.of("appid", "access_token"), List.of("appid", "access_token", "resource_id", "speaker"),
                List.of("resource_id", "speaker", "enable_ws_reuse", "audio_params", "additions"),
                List.of("TTS 1.0 大模型资源 ID 常见为 volc.service_type.10029。"),
                List.of(), List.of("HuoshanDoubleStreamTTS", "volc.service_type.10029"),
                "https://www.volcengine.com/docs/6561/1329505"));
        items.add(item("TTS_DOUBAO_SEED_TTS_2_STREAM", "TTS", "huoshan_double_stream", "Seed TTS 2.0",
                "火山引擎", "豆包语音合成 Seed TTS 2.0", "tts", "语音合成", "websocket-bidirectional", "WebSocket 双向流式",
                "cloud", "云平台", "WebSocket", "Resource ID", "resource_id",
                List.of("appid", "access_token"), List.of("appid", "access_token", "resource_id", "speaker"),
                List.of("resource_id", "speaker", "enable_ws_reuse", "audio_params"),
                List.of("Seed TTS 2.0 资源 ID 为 seed-tts-2.0。"),
                List.of(), List.of("HuoshanDoubleStreamTTSV2", "seed-tts-2.0", "TTS_HSDSTTS_V2"),
                "https://www.volcengine.com/docs/6561/1329505"));
        items.add(item("TTS_SILICONFLOW_COSYVOICE", "TTS", "siliconflow", "硅基流动 CosyVoice",
                "硅基流动", "CosyVoice2", "tts", "语音合成", "http", "HTTP",
                "cloud", "云平台", "HTTP", "模型/音色", "model",
                List.of("access_token"), List.of("access_token", "model", "voice"),
                List.of("model", "voice", "response_format"), List.of("voice 通常包含模型名前缀。"),
                List.of(), List.of("CosyVoiceSiliconflow"), "https://cloud.siliconflow.cn"));
        items.add(item("TTS_COZE_CN", "TTS", "cozecn", "Coze 语音合成",
                "Coze", "扣子中国 TTS", "tts", "语音合成", "http", "HTTP",
                "cloud", "云平台", "HTTP", "音色", "voice",
                List.of("access_token"), List.of("access_token", "voice"),
                List.of("response_format", "speed", "loudness_rate"), List.of(), List.of(),
                List.of("CozeCnTTS"), "https://www.coze.cn/open"));
        items.add(item("TTS_VOLCES_AI_GATEWAY_OPENAI", "TTS", "openai", "火山 AI Gateway TTS",
                "火山引擎", "边缘大模型网关语音合成", "tts", "语音合成", "openai-compatible", "OpenAI 兼容",
                "cloud", "云平台", "OpenAI Speech Compatible", "模型/音色", "model",
                List.of("api_key"), List.of("api_key", "api_url", "model", "voice"),
                List.of("speed", "format"), List.of("通过火山边缘大模型网关访问语音合成。"), List.of(),
                List.of("VolcesAiGatewayTTS", "doubao-tts", "ai-gateway"), "https://console.volcengine.com/vei/aigateway/"));
        items.add(item("TTS_OPENAI_SPEECH", "TTS", "openai", "OpenAI TTS",
                "OpenAI", "Text to Speech", "tts", "语音合成", "http", "HTTP",
                "cloud", "云平台", "OpenAI Audio", "模型/音色", "model",
                List.of("api_key"), List.of("api_key", "api_url", "model", "voice"),
                List.of("speed", "format"), List.of(), List.of(), List.of("OpenAITTS", "tts-1"),
                "https://developers.openai.com/api/docs/guides/text-to-speech"));
        items.add(item("TTS_FISHSPEECH", "TTS", "fishspeech", "Fish Speech",
                "Fish Speech", "自部署语音合成", "tts", "语音合成", "http", "HTTP",
                "local", "自部署服务", "HTTP msgpack", "参考音频/角色", "reference_id",
                List.of("api_key"), List.of("api_url"), List.of("reference_id", "reference_audio", "reference_text",
                        "response_format", "streaming", "temperature"),
                List.of("本地或远程自部署，重点配置 api_url 和参考音频。"), List.of(), List.of("FishSpeech"),
                "https://github.com/fishaudio/fish-speech"));
        items.add(item("TTS_GPT_SOVITS_V2", "TTS", "gpt_sovits_v2", "GPT-SoVITS v2",
                "GPT-SoVITS", "v2 自部署服务", "tts", "语音合成", "http", "HTTP",
                "local", "自部署服务", "HTTP", "参考音频", "ref_audio_path",
                List.of(), List.of("url"), List.of("text_lang", "ref_audio_path", "prompt_text", "top_k", "top_p",
                        "temperature", "streaming_mode"),
                List.of("需要先启动 GPT-SoVITS v2 API 服务。"), List.of(), List.of("GPT_SOVITS_V2"),
                "https://github.com/RVC-Boss/GPT-SoVITS"));
        items.add(item("TTS_GPT_SOVITS_V3", "TTS", "gpt_sovits_v3", "GPT-SoVITS v3",
                "GPT-SoVITS", "v3 自部署服务", "tts", "语音合成", "http", "HTTP",
                "local", "自部署服务", "HTTP", "参考音频", "refer_wav_path",
                List.of(), List.of("url"), List.of("text_language", "refer_wav_path", "prompt_text", "sample_steps"),
                List.of("需要先启动 GPT-SoVITS v3 API 服务。"), List.of(), List.of("GPT_SOVITS_V3"),
                "https://github.com/RVC-Boss/GPT-SoVITS"));
        items.add(item("TTS_MINIMAX_STREAM", "TTS", "minimax_httpstream", "MiniMax 流式 TTS",
                "MiniMax", "Speech HTTP Stream", "tts", "语音合成", "http-stream", "HTTP 流式",
                "cloud", "云平台", "HTTP Stream", "模型/音色", "model",
                List.of("api_key", "group_id"), List.of("api_key", "group_id", "voice_id", "model"),
                List.of("voice_setting", "pronunciation_dict", "audio_setting"),
                List.of("返回十六进制音频流。"), List.of(), List.of("MinimaxTTSHTTPStream", "TTS_MinimaxStreamTTS"),
                "https://www.minimaxi.com/document"));
        items.add(item("TTS_ALIYUN_NLS", "TTS", "aliyun", "阿里云 NLS TTS",
                "阿里云", "智能语音交互语音合成", "tts", "语音合成", "http", "HTTP 非流式",
                "cloud", "云平台", "HTTP", "AppKey/音色", "appkey",
                List.of("token", "access_key_id", "access_key_secret"), List.of("appkey", "voice"),
                List.of("voice", "format", "volume", "speech_rate", "pitch_rate"),
                List.of("token 和 AccessKey 二选一。"), List.of(), List.of("AliyunTTS"),
                "https://help.aliyun.com/zh/isi"));
        items.add(item("TTS_ALIYUN_NLS_STREAM", "TTS", "aliyun_stream", "阿里云 NLS 流式 TTS",
                "阿里云", "CosyVoice 流式合成", "tts", "语音合成", "websocket", "WebSocket 流式",
                "cloud", "云平台", "WebSocket", "AppKey/音色", "appkey",
                List.of("token", "access_key_id", "access_key_secret"), List.of("appkey", "voice"),
                List.of("host", "format", "volume", "speech_rate", "pitch_rate"),
                List.of("大模型音色需确认地域支持。"), List.of(), List.of("AliyunStreamTTS"),
                "https://help.aliyun.com/zh/isi"));
        items.add(item("TTS_ALIYUN_BAILIAN_COSYVOICE", "TTS", "alibl_stream", "百炼 CosyVoice",
                "阿里云百炼", "CosyVoice 大模型流式合成", "tts", "语音合成", "websocket", "WebSocket 流式",
                "cloud", "云平台", "DashScope WebSocket", "模型/音色", "model",
                List.of("api_key"), List.of("api_key", "model", "voice"),
                List.of("format", "volume", "rate", "pitch"),
                List.of("推荐使用 cosyvoice-v2；v3 和部分音色可能需要单独开通。"), List.of(),
                List.of("AliBLTTS", "TTS_AliBLStreamTTS", "cosyvoice-v2"),
                "https://help.aliyun.com/zh/model-studio"));
        items.add(item("TTS_TENCENT", "TTS", "tencent", "腾讯云 TTS",
                "腾讯云", "语音合成", "tts", "语音合成", "http", "HTTP",
                "cloud", "云平台", "HTTP TC3", "音色", "voice",
                List.of("appid", "secret_id", "secret_key"), List.of("appid", "secret_id", "secret_key", "voice"),
                List.of("region", "format", "speed", "volume"), List.of(), List.of(), List.of("TencentTTS"),
                "https://cloud.tencent.com/document/product/1073"));
        items.add(item("TTS_CUSTOM_HTTP", "TTS", "custom", "自定义 HTTP TTS",
                "自定义", "HTTP 语音合成服务", "tts", "语音合成", "http", "HTTP",
                "service", "自定义服务", "HTTP", "服务地址", "url",
                List.of(), List.of("url"), List.of("method", "params", "headers", "format"),
                List.of("params 支持 {prompt_text} 占位符。"), List.of(), List.of("CustomTTS"),
                ""));
        items.add(item("TTS_PADDLESPEECH", "TTS", "paddle_speech", "PaddleSpeech",
                "PaddleSpeech", "自部署流式语音合成", "tts", "语音合成", "websocket", "WebSocket 流式",
                "local", "自部署服务", "WebSocket", "服务地址", "url",
                List.of(), List.of("url"), List.of("protocol", "spk_id", "speed", "volume"),
                List.of("当前运行时代码只支持 websocket 分支。"),
                List.of("protocol=http 不应在标准模型向导中作为可选项展示。"), List.of("PaddleSpeechTTS"),
                "https://github.com/PaddlePaddle/PaddleSpeech"));
        items.add(item("TTS_INDEX_STREAM", "TTS", "index_stream", "IndexStreamTTS",
                "IndexTTS", "Index-TTS-vLLM", "tts", "语音合成", "http-stream", "HTTP 流式",
                "local", "自部署服务", "HTTP", "音色", "voice",
                List.of(), List.of("api_url", "voice"), List.of("api_url", "voice"),
                List.of("当前运行时代码固定输出 pcm。"),
                List.of("audio_format 配置存在但运行时代码固定为 pcm。"), List.of("IndexStreamTTS"),
                "https://github.com/Ksuriuri/index-tts-vllm"));
        items.add(item("TTS_XUNFEI_STREAM", "TTS", "xunfei_stream", "讯飞超拟人合成",
                "讯飞开放平台", "超拟人语音合成", "tts", "语音合成", "websocket-bidirectional", "WebSocket 双向流式",
                "cloud", "云平台", "WebSocket", "音色", "voice",
                List.of("app_id", "api_key", "api_secret"), List.of("app_id", "api_key", "api_secret", "voice"),
                List.of("api_url", "format", "volume", "speed", "pitch", "oral_level", "spark_assist"),
                List.of("APPID、APIKey、APISecret 都来自讯飞开放平台应用。"), List.of(),
                List.of("XunFeiTTS", "TTS_XunFeiStreamTTS"),
                "https://www.xfyun.cn/doc/tts"));
        items.add(item("TTS_LINKERAI", "TTS", "linkerai", "Linkerai TTS",
                "Linkerai", "语音合成", "tts", "语音合成", "http", "HTTP",
                "cloud", "云平台", "HTTP", "音色", "voice",
                List.of("access_token"), List.of("api_url", "access_token", "voice"),
                List.of("audio_format"), List.of(), List.of(), List.of("LinkeraiTTS"),
                ""));
    }

    private static void addLlm(List<ModelCatalogDTO> items) {
        items.add(openAiLlm("LLM_OPENAI_COMPATIBLE", "OpenAI 兼容", "OpenAI Compatible", "OpenAI 兼容聊天", "api.openai.com",
                "OpenAI 兼容接口要求 base_url、model_name、api_key。", List.of("AliLLM", "SLM_openai")));
        items.add(openAiLlm("LLM_ALI_QWEN_OPENAI", "阿里云百炼", "通义千问 OpenAI 兼容", "OpenAI 兼容聊天", "qwen-flash",
                "通过 DashScope compatible-mode/v1 接入通义千问。", List.of("AliLLM", "Qwen")));
        items.add(item("LLM_ALI_BAILIAN_APP", "LLM", "AliBL", "百炼应用",
                "阿里云百炼", "百炼 Application", "llm", "大语言模型", "application-api", "应用 API",
                "cloud", "云平台", "DashScope Application", "应用 ID", "app_id",
                List.of("api_key", "app_id"), List.of("base_url", "app_id", "api_key"),
                List.of("is_no_prompt", "ali_memory_id"),
                List.of("这是百炼应用调用，不是普通 OpenAI 兼容模型调用。"), List.of(), List.of("AliAppLLM"),
                "https://help.aliyun.com/zh/model-studio"));
        items.add(openAiLlm("LLM_DOUBAO_ARK_OPENAI", "火山引擎", "豆包方舟", "OpenAI 兼容聊天", "doubao-1-5-pro",
                "通过火山方舟 OpenAI 兼容接口接入豆包模型。", List.of("DoubaoLLM", "ark.cn")));
        items.add(openAiLlm("LLM_DEEPSEEK_OPENAI", "DeepSeek", "DeepSeek API", "OpenAI 兼容聊天", "deepseek-v4-flash",
                "语音场景默认关闭思考更快；需要时再启用 thinking/reasoning_effort。", List.of("DeepSeekLLM")));
        items.add(openAiLlm("LLM_ZHIPU_GLM_OPENAI", "智谱AI", "GLM", "OpenAI 兼容聊天", "glm-4-flash",
                "智谱使用 OpenAI 兼容接口。", List.of("ChatGLMLLM", "glm")));
        items.add(item("LLM_OLLAMA_LOCAL", "LLM", "ollama", "Ollama",
                "Ollama", "本地大模型", "llm", "大语言模型", "local-openai", "本地 OpenAI 兼容",
                "local", "本地部署", "OpenAI Compatible", "模型", "model_name",
                List.of(), List.of("model_name", "base_url"), List.of("model_name", "base_url"),
                List.of("需要先在本机或内网 Ollama pull 模型。"), List.of(), List.of("OllamaLLM", "SLM_ollama"),
                "https://ollama.com"));
        items.add(item("LLM_DIFY_APP", "LLM", "dify", "Dify",
                "Dify", "Dify 应用", "llm", "大语言模型", "application-api", "应用 API",
                "service", "云/自部署服务", "HTTP SSE", "模式", "mode",
                List.of("api_key"), List.of("base_url", "api_key"), List.of("mode"),
                List.of("prompt 在 Dify 控制台维护，本地 prompt 不生效。"), List.of(), List.of("DifyLLM"),
                "https://docs.dify.ai"));
        items.add(item("LLM_GEMINI", "LLM", "gemini", "Gemini",
                "Google", "Gemini API", "llm", "大语言模型", "sdk", "SDK",
                "cloud", "云平台", "Gemini SDK", "模型", "model_name",
                List.of("api_key"), List.of("api_key", "model_name"), List.of("http_proxy", "https_proxy"),
                List.of("网络不可达环境需要配置代理。"), List.of(), List.of("GeminiLLM"),
                "https://ai.google.dev/gemini-api/docs"));
        items.add(item("LLM_COZE", "LLM", "coze", "Coze",
                "Coze", "扣子机器人", "llm", "大语言模型", "application-api", "机器人 API",
                "cloud", "云平台", "Coze SDK", "机器人 ID", "bot_id",
                List.of("personal_access_token"), List.of("bot_id", "personal_access_token"), List.of("user_id"),
                List.of("bot_id 和 token 来自扣子开放平台。"), List.of(), List.of("CozeLLM"),
                "https://www.coze.cn/open"));
        items.add(item("LLM_FASTGPT", "LLM", "fastgpt", "FastGPT",
                "FastGPT", "FastGPT 应用", "llm", "大语言模型", "application-api", "应用 API",
                "service", "云/自部署服务", "HTTP SSE", "应用地址", "base_url",
                List.of("api_key"), List.of("base_url", "api_key"), List.of("variables"),
                List.of("prompt 在 FastGPT 控制台维护，本地 prompt 不生效。"), List.of(), List.of("FastgptLLM"),
                "https://doc.fastgpt.io"));
        items.add(item("LLM_XINFERENCE_LOCAL", "LLM", "xinference", "Xinference",
                "Xinference", "本地模型服务", "llm", "大语言模型", "local-openai", "本地 OpenAI 兼容",
                "local", "本地部署", "OpenAI Compatible", "模型", "model_name",
                List.of(), List.of("base_url", "model_name"), List.of("base_url", "model_name"),
                List.of("需要先在 Xinference 中启动对应模型。"), List.of(), List.of("XinferenceLLM", "XinferenceSmallLLM", "SLM_xinference"),
                "https://inference.readthedocs.io"));
        items.add(openAiLlm("LLM_LM_STUDIO_OPENAI", "LM Studio", "本地 OpenAI 兼容", "OpenAI 兼容聊天", "localhost:1234",
                "LM Studio 常用固定 api_key=lm-studio。", List.of("LMStudioLLM")));
        items.add(item("LLM_HOME_ASSISTANT", "LLM", "homeassistant", "Home Assistant",
                "Home Assistant", "Conversation Agent", "llm", "大语言模型", "application-api", "智能家居会话 API",
                "service", "本地/内网服务", "HTTP", "Agent ID", "agent_id",
                List.of("api_key"), List.of("base_url", "agent_id", "api_key"), List.of(),
                List.of("用于接入 Home Assistant conversation，不是通用聊天模型。"), List.of(), List.of("HomeAssistant"),
                "https://www.home-assistant.io/integrations/conversation"));
    }

    private static void addVllm(List<ModelCatalogDTO> items) {
        items.add(item("VLLM_OPENAI_COMPATIBLE", "VLLM", "openai", "OpenAI 兼容视觉",
                "OpenAI 兼容", "视觉大模型", "vllm", "视觉理解", "openai-compatible", "OpenAI 兼容",
                "cloud", "云/自部署", "OpenAI Chat Vision", "模型", "model_name",
                List.of("api_key"), List.of("base_url", "model_name", "api_key"),
                List.of("prompt", "max_tokens", "temperature", "top_p"),
                List.of("发送 image_url base64；prompt 为空时使用系统默认自然口语化提示词。"), List.of(),
                List.of(), "https://platform.openai.com/docs/guides/vision"));
        items.add(item("VLLM_ZHIPU_GLM_OPENAI", "VLLM", "openai", "智谱视觉",
                "智谱AI", "GLM-4V", "vllm", "视觉理解", "openai-compatible", "OpenAI 兼容",
                "cloud", "云平台", "OpenAI Chat Vision", "模型", "model_name",
                List.of("api_key"), List.of("base_url", "model_name", "api_key"),
                List.of("prompt", "max_tokens", "temperature", "top_p"),
                List.of("默认模型 glm-4v-flash。"), List.of(), List.of("ChatGLMVLLM", "glm-4v"),
                "https://bigmodel.cn"));
        items.add(item("VLLM_QWEN_VL_OPENAI", "VLLM", "openai", "通义千问视觉",
                "阿里云百炼", "Qwen-VL", "vllm", "视觉理解", "openai-compatible", "OpenAI 兼容",
                "cloud", "云平台", "OpenAI Chat Vision", "模型", "model_name",
                List.of("api_key"), List.of("base_url", "model_name", "api_key"),
                List.of("prompt", "max_tokens", "temperature", "top_p"),
                List.of("通过 DashScope OpenAI 兼容接口接入。"), List.of(), List.of("QwenVLVLLM"),
                "https://help.aliyun.com/zh/model-studio"));
        items.add(item("VLLM_XUNFEI_SPARK_OPENAI", "VLLM", "openai", "讯飞星火视觉",
                "讯飞开放平台", "星火视觉", "vllm", "视觉理解", "openai-compatible", "OpenAI 兼容",
                "cloud", "云平台", "OpenAI Chat Vision", "模型", "model_name",
                List.of("api_key", "api_password"), List.of("base_url", "model_name"),
                List.of("prompt", "max_tokens", "temperature", "top_p"),
                List.of("讯飞星火可能使用 api_password 字段。"), List.of(), List.of("XunfeiSparkLLM"),
                "https://www.xfyun.cn/doc/spark"));
    }

    private static void addMemoryIntentRagVad(List<ModelCatalogDTO> items) {
        items.add(item("VAD_SILERO", "VAD", "silero", "Silero VAD",
                "Silero", "语音活动检测", "vad", "语音活动检测", "local", "本地模型",
                "local", "本地部署", "PyTorch/ONNX", "模型目录", "model_dir",
                List.of(), List.of("model_dir", "threshold", "min_silence_duration_ms"),
                List.of("threshold", "threshold_low", "min_silence_duration_ms"),
                List.of("检测用户是否在说话，影响打断和断句。"), List.of(), List.of("SileroVAD"),
                "https://github.com/snakers4/silero-vad"));
        items.add(item("MEMORY_NONE", "Memory", "nomem", "无记忆",
                "本地系统", "无记忆", "memory", "记忆", "builtin", "内置",
                "local", "本地", "None", "无", "",
                List.of(), List.of(), List.of(), List.of("不保存、不查询历史记忆。"), List.of(), List.of("Memory_nomem"),
                ""));
        items.add(item("MEMORY_LOCAL_SHORT", "Memory", "mem_local_short", "本地短记忆",
                "本地系统", "短期记忆", "memory", "记忆", "builtin", "内置",
                "local", "本地", "YAML", "总结 LLM", "llm",
                List.of(), List.of("llm"), List.of("llm"), List.of("使用 LLM 总结，默认写入本地数据文件。"),
                List.of(), List.of("Memory_mem_local_short"), ""));
        items.add(item("MEMORY_MEM0", "Memory", "mem0ai", "Mem0AI",
                "Mem0", "云端记忆", "memory", "记忆", "api", "云 API",
                "cloud", "云平台", "HTTP", "API Key", "api_key",
                List.of("api_key"), List.of("api_key"), List.of("api_version"), List.of(), List.of(),
                List.of("Memory_mem0ai"), "https://docs.mem0.ai"));
        items.add(item("MEMORY_POWERMEM", "Memory", "powermem", "PowerMem",
                "PowerMem", "长期记忆", "memory", "记忆", "service", "本地/云组合",
                "service", "本地/云服务", "LLM+Embedding+VectorStore", "LLM/Embedding", "llm_model",
                List.of("llm_api_key", "embedding_api_key"), List.of("llm_model", "llm_api_key", "embedding_model", "embedding_api_key"),
                List.of("vector_store", "enable_user_profile"), List.of("支持 sqlite/oceanbase/postgres 等向量存储。"),
                List.of(), List.of("Memory_powermem"), ""));
        ModelCatalogDTO reportOnly = item("MEMORY_REPORT_ONLY", "Memory", "mem_report_only", "仅上报记忆",
                "本地系统", "仅上报聊天记录", "memory", "记忆", "builtin", "内置",
                "local", "本地", "None", "无", "",
                List.of(), List.of(), List.of(), List.of("代码存在但默认配置未展示，作为高级/隐藏模板。"),
                List.of("默认 config.yaml 未列出。"), List.of("mem_report_only"), "");
        reportOnly.setHidden(true);
        items.add(reportOnly);
        items.add(item("INTENT_NONE", "Intent", "nointent", "无意图识别",
                "本地系统", "无意图识别", "intent", "意图识别", "builtin", "内置",
                "local", "本地", "None", "无", "",
                List.of(), List.of(), List.of(), List.of("所有请求直接进入聊天。"), List.of(),
                List.of("Intent_nointent"), ""));
        items.add(item("INTENT_LLM", "Intent", "intent_llm", "LLM 意图识别",
                "本地系统", "LLM 意图识别", "intent", "意图识别", "llm", "LLM 分类",
                "local", "本地", "LLM", "LLM 模型", "llm",
                List.of(), List.of("llm"), List.of("llm", "functions"),
                List.of("可使用独立小模型做意图识别。"), List.of(), List.of("Intent_intent_llm"), ""));
        items.add(item("INTENT_FUNCTION_CALL", "Intent", "function_call", "函数调用意图",
                "本地系统", "主 LLM function call", "intent", "意图识别", "llm-tools", "函数调用",
                "local", "本地", "Function Calling", "函数列表", "functions",
                List.of(), List.of(), List.of("functions"),
                List.of("依赖主 LLM 工具调用能力。"), List.of(), List.of("Intent_function_call"), ""));
        items.add(item("RAG_RAGFLOW", "RAG", "ragflow", "RAGFlow",
                "RAGFlow", "外部知识库", "rag", "知识库", "api", "外部 API",
                "service", "自部署服务", "HTTP", "服务地址", "base_url",
                List.of("api_key"), List.of("base_url", "api_key"), List.of(),
                List.of("调用 RAGFlow retrieval API。"), List.of(), List.of("RAG_RAGFlow"),
                "https://github.com/infiniflow/ragflow"));
        items.add(item("RAG_LOCAL_KNOWLEDGE", "RAG", "local_knowledge", "本地知识库",
                "本地系统", "manager-api 本地知识库", "rag", "知识库", "builtin", "内置",
                "local", "本地数据库", "HTTP internal", "切片配置", "max_chunk_chars",
                List.of(), List.of(), List.of("max_chunk_chars", "parser_model_id"),
                List.of("数据存储在 manager-api 数据库，不依赖 RAGFlow。"), List.of(), List.of("RAG_LocalKnowledge"),
                ""));
    }

    private static ModelCatalogDTO openAiLlm(String code, String vendor, String product, String accessModeLabel,
            String upstreamHint, String setupHint, List<String> aliases) {
        return item(code, "LLM", "openai", product, vendor, product, "llm", "大语言模型",
                "openai-compatible", accessModeLabel, vendor.toLowerCase(Locale.ROOT).contains("lm studio") ? "local" : "cloud",
                vendor.toLowerCase(Locale.ROOT).contains("lm studio") ? "本地部署" : "云/兼容服务",
                "OpenAI Chat", "模型", "model_name",
                List.of("api_key"), List.of("base_url", "model_name", "api_key"),
                List.of("temperature", "max_tokens", "top_p", "top_k", "frequency_penalty", "thinking", "reasoning_effort"),
                List.of(setupHint + " 上游标识示例：" + upstreamHint + "。"), List.of(), aliases,
                "https://platform.openai.com/docs/api-reference/chat");
    }

    private static ModelCatalogDTO item(String templateCode, String modelType, String providerCode,
            String adapterName, String vendorName, String productName, String capability, String capabilityLabel,
            String accessMode, String accessModeLabel, String deployment, String deploymentLabel, String protocolLabel,
            String upstreamResourceLabel, String upstreamModelField, List<String> credentialFields,
            List<String> requiredFields, List<String> recommendedFields, List<String> setupHints,
            List<String> knownLimitations, List<String> aliases, String docLink) {
        ModelCatalogDTO dto = new ModelCatalogDTO();
        dto.setTemplateCode(templateCode);
        dto.setModelType(modelType);
        dto.setProviderCode(providerCode);
        dto.setAdapterName(adapterName);
        dto.setVendorName(vendorName);
        dto.setProductName(productName);
        dto.setCapability(capability);
        dto.setCapabilityLabel(capabilityLabel);
        dto.setAccessMode(accessMode);
        dto.setAccessModeLabel(accessModeLabel);
        dto.setDeployment(deployment);
        dto.setDeploymentLabel(deploymentLabel);
        dto.setProtocolLabel(protocolLabel);
        dto.setUpstreamResourceLabel(upstreamResourceLabel);
        dto.setUpstreamModelField(upstreamModelField);
        dto.setCredentialFields(credentialFields);
        dto.setRequiredFields(requiredFields);
        dto.setRecommendedFields(recommendedFields);
        dto.setFieldSchemas(buildFieldSchemas(credentialFields, requiredFields, recommendedFields));
        applyTemplateDefaults(dto);
        dto.setSetupHints(setupHints);
        dto.setKnownLimitations(knownLimitations);
        dto.setAliases(aliases);
        dto.setDocLink(docLink);
        return dto;
    }

    private static void applyTemplateDefaults(ModelCatalogDTO dto) {
        switch (dto.getTemplateCode()) {
            case "LLM_ALI_QWEN_OPENAI" -> {
                setFieldDefault(dto, "base_url", "https://dashscope.aliyuncs.com/compatible-mode/v1");
                setFieldDefault(dto, "model_name", "qwen-flash");
            }
            case "LLM_DOUBAO_ARK_OPENAI" -> {
                setFieldDefault(dto, "base_url", "https://ark.cn-beijing.volces.com/api/v3");
                setFieldDefault(dto, "model_name", "doubao-1-5-pro");
            }
            case "LLM_DEEPSEEK_OPENAI" -> {
                setFieldDefault(dto, "base_url", "https://api.deepseek.com");
                setFieldDefault(dto, "model_name", "deepseek-v4-flash");
                setFieldDefault(dto, "thinking", "disabled");
                setFieldDefault(dto, "reasoning_effort", "high");
            }
            case "LLM_ZHIPU_GLM_OPENAI" -> {
                setFieldDefault(dto, "base_url", "https://open.bigmodel.cn/api/paas/v4");
                setFieldDefault(dto, "model_name", "glm-4-flash");
            }
            case "LLM_OLLAMA_LOCAL" -> {
                setFieldDefault(dto, "base_url", "http://127.0.0.1:11434/v1");
                setFieldDefault(dto, "model_name", "qwen2.5:7b");
            }
            case "LLM_LM_STUDIO_OPENAI" -> {
                setFieldDefault(dto, "base_url", "http://127.0.0.1:1234/v1");
                setFieldDefault(dto, "api_key", "lm-studio");
            }
            case "ASR_DOUBAO_BIGASR_STREAM" -> setFieldDefault(dto, "resource_id", "volc.bigasr.sauc.duration");
            case "ASR_DOUBAO_SEED_ASR_2_STREAM" -> setFieldDefault(dto, "resource_id", "volc.seedasr.sauc.duration");
            case "ASR_QWEN3_FLASH" -> {
                setFieldDefault(dto, "base_url", "https://dashscope.aliyuncs.com/compatible-mode/v1");
                setFieldDefault(dto, "model_name", "qwen3-asr-flash");
            }
            case "ASR_ALIYUN_BAILIAN_PARAFORMER" -> setFieldDefault(dto, "model", "paraformer-realtime-v2");
            case "TTS_DOUBAO_BIGTTS_STREAM" -> setFieldDefault(dto, "resource_id", "volc.service_type.10029");
            case "TTS_DOUBAO_SEED_TTS_2_STREAM" -> setFieldDefault(dto, "resource_id", "seed-tts-2.0");
            case "TTS_ALIYUN_BAILIAN_COSYVOICE" -> setFieldDefault(dto, "model", "cosyvoice-v2");
            case "VLLM_ZHIPU_GLM_OPENAI" -> {
                setFieldDefault(dto, "base_url", "https://open.bigmodel.cn/api/paas/v4");
                setFieldDefault(dto, "model_name", "glm-4v-flash");
            }
            case "VLLM_QWEN_VL_OPENAI" -> {
                setFieldDefault(dto, "base_url", "https://dashscope.aliyuncs.com/compatible-mode/v1");
                setFieldDefault(dto, "model_name", "qwen-vl-plus");
            }
            default -> {
            }
        }
    }

    private static void setFieldDefault(ModelCatalogDTO dto, String key, Object value) {
        dto.getFieldSchemas().stream()
                .filter(field -> key.equals(field.getKey()))
                .findFirst()
                .ifPresent(field -> field.setDefaultValue(value));
    }

    private static List<ModelFieldSchemaDTO> buildFieldSchemas(List<String> credentialFields,
            List<String> requiredFields, List<String> recommendedFields) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(requiredFields);
        keys.addAll(recommendedFields);
        keys.addAll(credentialFields);
        int[] order = { 0 };
        return keys.stream()
                .filter(StringUtils::isNotBlank)
                .map(key -> field(key, credentialFields.contains(key), requiredFields.contains(key), ++order[0]))
                .toList();
    }

    private static ModelFieldSchemaDTO field(String key, boolean credential, boolean required, int order) {
        ModelFieldSchemaDTO field = new ModelFieldSchemaDTO();
        field.setKey(key);
        field.setLabel(labelFor(key));
        field.setType(typeFor(key));
        field.setInputType(inputTypeForTypeStatic(field.getType(), credential || isSecretKey(key), optionsFor(key)));
        field.setGroup(groupFor(key, credential));
        field.setRuntimeKey(key);
        field.setAdvanced("advanced".equals(groupFor(key, credential)));
        field.setSecret(credential || isSecretKey(key));
        field.setRequired(required);
        field.setDefaultValue(defaultValueFor(key));
        field.setOrder(order);
        field.setHelpText(helpFor(key));
        field.setOptions(optionsFor(key));
        return field;
    }

    private static String inputTypeForTypeStatic(String type, boolean secret, List<String> options) {
        if (secret) {
            return "password";
        }
        if (options != null && !options.isEmpty()) {
            return "select";
        }
        return switch (StringUtils.defaultString(type, "string")) {
            case "number" -> "number";
            case "boolean" -> "switch";
            case "json" -> "textarea";
            default -> "text";
        };
    }

    private static Object defaultValueFor(String key) {
        return switch (key) {
            case "base_url" -> "";
            case "api_url", "ws_url", "url", "host" -> "";
            case "model_name", "model", "resource_id", "voice", "speaker", "appkey", "cluster" -> "";
            case "temperature" -> "";
            case "top_p", "top_k", "max_tokens" -> "";
            default -> "";
        };
    }

    private static String labelFor(String key) {
        return switch (key) {
            case "api_key" -> "API Key";
            case "api_secret" -> "API Secret";
            case "access_token" -> "Access Token";
            case "secret_id" -> "Secret ID";
            case "secret_key" -> "Secret Key";
            case "app_id", "appid" -> "App ID";
            case "appkey" -> "AppKey";
            case "base_url" -> "Base URL";
            case "api_url" -> "API URL";
            case "ws_url" -> "WebSocket URL";
            case "model", "model_name" -> "模型";
            case "resource_id" -> "Resource ID";
            case "voice", "speaker", "voice_id" -> "音色";
            case "model_dir" -> "模型目录";
            case "model_path" -> "模型路径";
            default -> key;
        };
    }

    private static String typeFor(String key) {
        if (isSecretKey(key)) {
            return "password";
        }
        if (Stream.of("threshold", "temperature", "top_p", "top_k", "max_tokens", "port", "sample_rate",
                "volume", "speed", "pitch", "rate", "end_window_size", "max_sentence_silence")
                .anyMatch(key::equals)) {
            return "number";
        }
        if (key.startsWith("enable_") || key.startsWith("is_") || key.equals("streaming")) {
            return "boolean";
        }
        if (key.equals("audio_params") || key.equals("additions") || key.equals("params") || key.equals("headers")
                || key.equals("variables")) {
            return "json";
        }
        return "string";
    }

    private static String groupFor(String key, boolean credential) {
        if (credential || isSecretKey(key)) {
            return "credentials";
        }
        if (key.contains("url") || key.equals("host") || key.equals("port")) {
            return "endpoint";
        }
        if (key.contains("model") || key.contains("resource") || key.equals("voice") || key.equals("speaker")
                || key.equals("appkey")) {
            return "upstream";
        }
        return "advanced";
    }

    private static String helpFor(String key) {
        return switch (key) {
            case "resource_id" -> "云平台开通具体语音服务后得到的资源标识，不等同于配置名称。";
            case "model", "model_name" -> "上游平台实际调用的模型 ID。";
            case "voice", "speaker", "voice_id" -> "上游平台音色 ID。";
            case "base_url", "api_url", "ws_url" -> "接口地址，注意是否需要包含具体 path。";
            case "appkey" -> "阿里云 NLS 项目 AppKey，不是 API Key。";
            default -> "";
        };
    }

    private static List<String> optionsFor(String key) {
        return switch (key) {
            case "protocol" -> List.of("websocket");
            case "mode" -> List.of("chat-messages", "workflows/run", "completion-messages");
            case "model_type" -> List.of("sense_voice", "paraformer");
            default -> List.of();
        };
    }

    private static boolean isSecretKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("key") || lower.contains("secret") || lower.contains("token")
                || lower.contains("authorization") || lower.contains("password");
    }
}
