package xiaozhi.modules.model.service.impl;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import xiaozhi.modules.model.dao.ModelConfigParamDao;
import xiaozhi.modules.model.dao.ModelConfigSecretDao;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.dao.ModelTemplateDao;
import xiaozhi.modules.model.dao.ModelTemplateFieldDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.entity.ModelConfigParamEntity;
import xiaozhi.modules.model.entity.ModelConfigSecretEntity;
import xiaozhi.modules.model.entity.ModelTemplateEntity;
import xiaozhi.modules.model.entity.ModelTemplateFieldEntity;

@Component
@AllArgsConstructor
public class ModelRuntimeAssembler {
    private static final Set<String> SECRET_KEYS = new HashSet<>(List.of(
            "api_key", "api_password", "access_token", "token", "secret_key", "api_secret",
            "access_key_secret", "personal_access_token"));
    private static final Set<String> METADATA_KEYS = new HashSet<>(List.of(
            "type", "base_url", "api_url", "url", "ws_url", "server_url", "host",
            "endpoint_url", "model_name", "model", "resource_id", "protocol"));
    private static final Set<String> ENDPOINT_KEYS = new HashSet<>(List.of(
            "base_url", "api_url", "url", "ws_url", "server_url", "host", "endpoint_url"));
    private static final Set<String> UPSTREAM_KEYS = new HashSet<>(List.of(
            "model_name", "model", "resource_id", "voice", "speaker", "model_dir", "model_path", "appkey", "cluster"));
    private final ModelConfigParamDao modelConfigParamDao;
    private final ModelConfigSecretDao modelConfigSecretDao;
    private final ModelConfigDao modelConfigDao;
    private final ModelTemplateDao modelTemplateDao;
    private final ModelTemplateFieldDao modelTemplateFieldDao;

    public Map<String, Object> buildRuntimeModel(ModelConfigEntity model) {
        Map<String, Object> runtime = new HashMap<>();
        if (model == null) {
            return runtime;
        }
        runtime.put("id", model.getId());
        runtime.put("model_type", canonicalModelType(model.getModelType()));
        runtime.put("display_name", model.getModelName());
        runtime.put("model_code", model.getModelCode());
        runtime.put("adapter_code", model.getAdapterCode());
        runtime.put("template_code", model.getTemplateCode());
        runtime.put("vendor_name", model.getVendorName());
        runtime.put("product_name", model.getProductName());
        runtime.put("protocol", model.getProtocol());
        runtime.put("typed_config", buildTypedConfig(model));
        return runtime;
    }

    public JSONObject buildTypedConfig(ModelConfigEntity model) {
        JSONObject config = new JSONObject();
        if (model == null) {
            return config;
        }

        putIfPresent(config, "type", model.getAdapterCode());
        ModelTemplateEntity template = loadTemplate(model.getTemplateCode());
        List<ModelTemplateFieldEntity> templateFields = loadTemplateFields(model.getTemplateCode());
        if (template == null || templateFields.isEmpty()) {
            putIfPresent(config, "base_url", model.getEndpointUrl());
            putIfPresent(config, "api_url", model.getEndpointUrl());
            putIfPresent(config, "url", model.getEndpointUrl());
            putIfPresent(config, "ws_url", model.getEndpointUrl());
            putIfPresent(config, "host", model.getEndpointUrl());
            putIfPresent(config, "endpoint_url", model.getEndpointUrl());
            putIfPresent(config, "model_name", model.getUpstreamModelId());
            putIfPresent(config, "model", model.getUpstreamModelId());
            putIfPresent(config, "resource_id", model.getUpstreamModelId());
        } else {
            String endpointKey = firstRuntimeKey(templateFields, ENDPOINT_KEYS);
            String upstreamKey = firstNonBlank(template.getUpstreamModelField(), firstRuntimeKey(templateFields, UPSTREAM_KEYS));
            putIfPresent(config, endpointKey, model.getEndpointUrl());
            putIfPresent(config, upstreamKey, model.getUpstreamModelId());
        }
        putIfPresent(config, "protocol", model.getProtocol());

        Map<String, String> runtimeKeys = runtimeKeyMap(templateFields);
        for (ModelConfigParamEntity param : loadParams(model.getId())) {
            putTypedValue(config, runtimeKeys.getOrDefault(param.getParamKey(), param.getParamKey()),
                    param.getParamValue(), param.getValueType());
        }
        for (ModelConfigSecretEntity secret : loadSecrets(model.getId())) {
            putIfPresent(config, runtimeKeys.getOrDefault(secret.getSecretKey(), secret.getSecretKey()),
                    secret.getSecretValue());
        }
        HuoshanDoubleStreamTtsHelper.normalize(model, config);

        return config;
    }

    public Map<String, Object> buildPublicParams(ModelConfigEntity model) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (model == null || StringUtils.isBlank(model.getId())) {
            return params;
        }
        for (ModelConfigParamEntity param : loadParams(model.getId())) {
            if (param == null || StringUtils.isBlank(param.getParamKey())) {
                continue;
            }
            params.put(param.getParamKey(), parseTypedValue(param.getParamValue(), param.getValueType()));
        }
        return params;
    }

    public Map<String, Object> buildMaskedSecrets(ModelConfigEntity model) {
        Map<String, Object> secrets = new LinkedHashMap<>();
        if (model == null || StringUtils.isBlank(model.getId())) {
            return secrets;
        }
        for (ModelConfigSecretEntity secret : loadSecrets(model.getId())) {
            if (secret == null || StringUtils.isBlank(secret.getSecretKey())) {
                continue;
            }
            secrets.put(secret.getSecretKey(), StringUtils.defaultString(secret.getMaskedValue()));
        }
        return secrets;
    }

    public Map<String, Object> buildPlainSecrets(ModelConfigEntity model) {
        Map<String, Object> secrets = new LinkedHashMap<>();
        if (model == null || StringUtils.isBlank(model.getId())) {
            return secrets;
        }
        for (ModelConfigSecretEntity secret : loadSecrets(model.getId())) {
            if (secret == null || StringUtils.isBlank(secret.getSecretKey())) {
                continue;
            }
            secrets.put(secret.getSecretKey(), StringUtils.defaultString(secret.getSecretValue()));
        }
        return secrets;
    }

    private List<ModelConfigParamEntity> loadParams(String modelId) {
        return modelConfigParamDao.selectList(new LambdaQueryWrapper<ModelConfigParamEntity>()
                .eq(ModelConfigParamEntity::getModelId, modelId));
    }

    private List<ModelConfigSecretEntity> loadSecrets(String modelId) {
        return modelConfigSecretDao.selectList(new LambdaQueryWrapper<ModelConfigSecretEntity>()
                .eq(ModelConfigSecretEntity::getModelId, modelId));
    }

    private ModelTemplateEntity loadTemplate(String templateCode) {
        if (StringUtils.isBlank(templateCode)) {
            return null;
        }
        return modelTemplateDao.selectOne(new LambdaQueryWrapper<ModelTemplateEntity>()
                .eq(ModelTemplateEntity::getTemplateCode, templateCode)
                .last("limit 1"));
    }

    private List<ModelTemplateFieldEntity> loadTemplateFields(String templateCode) {
        if (StringUtils.isBlank(templateCode)) {
            return List.of();
        }
        return modelTemplateFieldDao.selectList(new LambdaQueryWrapper<ModelTemplateFieldEntity>()
                .eq(ModelTemplateFieldEntity::getTemplateId, templateCode)
                .orderByAsc(ModelTemplateFieldEntity::getSort));
    }

    private Map<String, String> runtimeKeyMap(List<ModelTemplateFieldEntity> fields) {
        Map<String, String> result = new HashMap<>();
        for (ModelTemplateFieldEntity field : fields) {
            if (field == null || StringUtils.isBlank(field.getFieldKey())) {
                continue;
            }
            result.put(field.getFieldKey(), StringUtils.defaultIfBlank(field.getRuntimeKey(), field.getFieldKey()));
        }
        return result;
    }

    private String firstRuntimeKey(List<ModelTemplateFieldEntity> fields, Set<String> keys) {
        for (ModelTemplateFieldEntity field : fields) {
            if (field == null) {
                continue;
            }
            String fieldKey = StringUtils.defaultString(field.getFieldKey());
            String runtimeKey = StringUtils.defaultIfBlank(field.getRuntimeKey(), fieldKey);
            if (keys.contains(fieldKey) || keys.contains(runtimeKey)) {
                return runtimeKey;
            }
        }
        return "";
    }

    public void saveRuntimeConfig(ModelConfigEntity model, JSONObject config) {
        if (model == null || StringUtils.isBlank(model.getId()) || config == null) {
            return;
        }
        HuoshanDoubleStreamTtsHelper.normalize(model, config);
        updateModelMetadata(model, config);
        Map<String, ModelTemplateFieldEntity> templateFieldMap = templateFieldMap(
                loadTemplateFields(model.getTemplateCode()));
        Map<String, ModelConfigSecretEntity> existingSecrets = new HashMap<>();
        for (ModelConfigSecretEntity secret : loadSecrets(model.getId())) {
            existingSecrets.put(secret.getSecretKey(), secret);
        }
        modelConfigParamDao.delete(new QueryWrapper<ModelConfigParamEntity>().eq("model_id", model.getId()));
        modelConfigSecretDao.delete(new QueryWrapper<ModelConfigSecretEntity>().eq("model_id", model.getId()));

        for (String key : config.keySet()) {
            Object value = config.get(key);
            if (value == null || METADATA_KEYS.contains(key)) {
                continue;
            }
            if (SECRET_KEYS.contains(key)) {
                String secretValue = String.valueOf(value);
                ModelConfigSecretEntity existing = existingSecrets.get(key);
                if (existing != null && isMaskedSecretPlaceholder(secretValue, existing.getMaskedValue())) {
                    secretValue = existing.getSecretValue();
                }
                ModelConfigSecretEntity secret = new ModelConfigSecretEntity();
                secret.setModelId(model.getId());
                secret.setSecretKey(key);
                secret.setSecretValue(secretValue);
                secret.setMaskedValue(maskSecret(secretValue));
                secret.setValueType("secret");
                modelConfigSecretDao.insert(secret);
            } else {
                ModelConfigParamEntity param = new ModelConfigParamEntity();
                param.setModelId(model.getId());
                param.setParamKey(key);
                param.setParamValue(serializeParamValue(value));
                ModelTemplateFieldEntity templateField = templateFieldMap.get(key);
                param.setValueType(resolveValueType(value, templateField));
                param.setParamGroup(resolveParamGroup(key, templateField));
                modelConfigParamDao.insert(param);
            }
        }
    }

    private Map<String, ModelTemplateFieldEntity> templateFieldMap(List<ModelTemplateFieldEntity> fields) {
        Map<String, ModelTemplateFieldEntity> result = new HashMap<>();
        for (ModelTemplateFieldEntity field : fields) {
            if (field == null || StringUtils.isBlank(field.getFieldKey())) {
                continue;
            }
            result.put(field.getFieldKey(), field);
            if (StringUtils.isNotBlank(field.getRuntimeKey())) {
                result.put(field.getRuntimeKey(), field);
            }
        }
        return result;
    }

    private String resolveValueType(Object value, ModelTemplateFieldEntity templateField) {
        if (templateField != null && StringUtils.isNotBlank(templateField.getValueType())) {
            return templateField.getValueType();
        }
        return inferValueType(value);
    }

    private String resolveParamGroup(String key, ModelTemplateFieldEntity templateField) {
        if (templateField != null && StringUtils.isNotBlank(templateField.getFieldGroup())) {
            return templateField.getFieldGroup();
        }
        return inferParamGroup(key);
    }

    private Object parseTypedValue(String value, String valueType) {
        if (value == null) {
            return null;
        }
        String type = StringUtils.defaultString(valueType, "string");
        if ("number".equalsIgnoreCase(type)) {
            try {
                double number = Double.parseDouble(value);
                return number == (long) number ? (long) number : number;
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        if ("boolean".equalsIgnoreCase(type)) {
            return Boolean.valueOf(value);
        }
        if ("json".equalsIgnoreCase(type)) {
            return JSONUtil.parse(value);
        }
        return value;
    }

    public void deleteRuntimeConfig(String modelId) {
        if (StringUtils.isBlank(modelId)) {
            return;
        }
        modelConfigParamDao.delete(new QueryWrapper<ModelConfigParamEntity>().eq("model_id", modelId));
        modelConfigSecretDao.delete(new QueryWrapper<ModelConfigSecretEntity>().eq("model_id", modelId));
    }

    public boolean hasRuntimeReference(String modelType, String referencedModelId) {
        if (StringUtils.isBlank(modelType) || StringUtils.isBlank(referencedModelId)) {
            return false;
        }
        List<ModelConfigParamEntity> refs = modelConfigParamDao.selectList(new QueryWrapper<ModelConfigParamEntity>()
                .eq("param_key", "llm")
                .eq("param_value", referencedModelId));
        for (ModelConfigParamEntity ref : refs) {
            ModelConfigEntity owner = modelConfigDao.selectById(ref.getModelId());
            if (owner != null && modelType.equalsIgnoreCase(owner.getModelType())) {
                return true;
            }
        }
        return false;
    }

    private void putIfPresent(JSONObject config, String key, String value) {
        if (StringUtils.isNotBlank(key) && StringUtils.isNotBlank(value)) {
            config.set(key, value);
        }
    }

    private void putTypedValue(JSONObject config, String key, String value, String valueType) {
        if (StringUtils.isBlank(key) || value == null) {
            return;
        }
        String type = StringUtils.defaultString(valueType, "string");
        if ("number".equalsIgnoreCase(type)) {
            try {
                config.set(key, Double.valueOf(value));
                return;
            } catch (NumberFormatException ignored) {
                config.set(key, value);
                return;
            }
        }
        if ("boolean".equalsIgnoreCase(type)) {
            config.set(key, Boolean.valueOf(value));
            return;
        }
        if ("json".equalsIgnoreCase(type)) {
            config.set(key, parseJsonValue(value));
            return;
        }
        config.set(key, value);
    }

    private Object parseJsonValue(String value) {
        try {
            return JSONUtil.parse(value);
        } catch (Exception ignored) {
            return value;
        }
    }

    private String inferValueType(Object value) {
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof JSONObject || value instanceof JSONArray || value instanceof Map<?, ?> || value instanceof List<?>) {
            return "json";
        }
        return "string";
    }

    private String serializeParamValue(Object value) {
        if (value instanceof JSONObject || value instanceof JSONArray || value instanceof Map<?, ?> || value instanceof List<?>) {
            return JSONUtil.toJsonStr(value);
        }
        return String.valueOf(value);
    }

    private void updateModelMetadata(ModelConfigEntity model, JSONObject config) {
        ModelConfigEntity update = new ModelConfigEntity();
        update.setId(model.getId());
        update.setAdapterCode(firstNonBlank(config.getStr("type"), model.getAdapterCode()));
        update.setEndpointUrl(firstNonBlank(
                config.getStr("ws_url"),
                config.getStr("base_url"),
                config.getStr("api_url"),
                config.getStr("url"),
                config.getStr("host"),
                model.getEndpointUrl()));
        update.setUpstreamModelId(firstNonBlank(
                config.getStr("model_name"),
                config.getStr("model"),
                config.getStr("resource_id"),
                config.getStr("voice"),
                model.getUpstreamModelId()));
        modelConfigDao.updateById(update);
        model.setAdapterCode(update.getAdapterCode());
        model.setEndpointUrl(update.getEndpointUrl());
        model.setUpstreamModelId(update.getUpstreamModelId());
    }

    private String inferParamGroup(String key) {
        if (StringUtils.containsAnyIgnoreCase(key, "temperature", "top_", "max_", "penalty")) {
            return "generation";
        }
        if (StringUtils.containsAnyIgnoreCase(key, "voice", "audio", "sample", "speed", "rate", "pitch", "volume")) {
            return "audio";
        }
        return "provider";
    }

    private String maskSecret(String value) {
        if (StringUtils.isBlank(value)) {
            return "";
        }
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private boolean isMaskedSecretPlaceholder(String value, String maskedValue) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        return "****".equals(value)
                || "******".equals(value)
                || StringUtils.equals(value, maskedValue);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private String canonicalModelType(String modelType) {
        String normalized = StringUtils.defaultString(modelType).trim();
        return switch (normalized.toLowerCase()) {
            case "asr" -> "ASR";
            case "tts" -> "TTS";
            case "llm" -> "LLM";
            case "slm" -> "SLM";
            case "vllm" -> "VLLM";
            case "vad" -> "VAD";
            case "memory", "mem" -> "Memory";
            case "intent" -> "Intent";
            case "rag" -> "RAG";
            default -> normalized;
        };
    }
}
