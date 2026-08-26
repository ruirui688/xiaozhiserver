package xiaozhi.modules.maintenance.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import cn.hutool.json.JSONObject;
import lombok.AllArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.maintenance.dto.HarnessModelConfig;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.impl.ModelRuntimeAssembler;

/** Resolves one tested managed DeepSeek model without persisting another credential copy. */
@Service
@AllArgsConstructor
public class MaintenanceModelResolver {
    private static final int ENABLED = 1;
    private static final int TEST_PASSED = 1;

    private final ModelConfigDao modelConfigDao;
    private final ModelRuntimeAssembler runtimeAssembler;

    public HarnessModelConfig resolve(String modelConfigId) {
        if (StringUtils.isBlank(modelConfigId)) {
            throw new RenException("请选择模型管理中已测试通过的 DeepSeek 模型");
        }
        ModelConfigEntity model = modelConfigDao.selectById(modelConfigId);
        if (model == null || !"LLM".equalsIgnoreCase(model.getModelType())) {
            throw new RenException("自主维护模型不存在或不是语言模型");
        }
        if (!Integer.valueOf(ENABLED).equals(model.getIsEnabled())
                || !Integer.valueOf(TEST_PASSED).equals(model.getTestStatus())) {
            throw new RenException("自主维护模型必须已启用且测试通过");
        }
        JSONObject config = runtimeAssembler.buildTypedConfig(model);
        if (!isDeepSeek(model, config)) {
            throw new RenException("自主维护当前仅支持模型管理中的 DeepSeek 模型");
        }
        String apiKey = firstNonBlank(config, "api_key", "apiKey");
        if (!isUsableSecret(apiKey)) {
            throw new RenException("所选 DeepSeek 模型没有可用的 API Key");
        }
        String baseUrl = firstNonBlank(config, "base_url", "api_url", "endpoint_url", "url");
        if (StringUtils.isBlank(baseUrl)) {
            baseUrl = StringUtils.defaultIfBlank(model.getEndpointUrl(), "https://api.deepseek.com");
        }
        String upstreamModel = firstNonBlank(config, "model_name", "model");
        upstreamModel = StringUtils.defaultIfBlank(upstreamModel, model.getUpstreamModelId());
        if (StringUtils.isBlank(upstreamModel)) {
            throw new RenException("所选 DeepSeek 模型没有配置上游模型名称");
        }
        return new HarnessModelConfig(apiKey, baseUrl, upstreamModel);
    }

    public void validate(String modelConfigId) {
        resolve(modelConfigId);
    }

    private boolean isDeepSeek(ModelConfigEntity model, JSONObject config) {
        String identity = String.join(" ",
                StringUtils.defaultString(model.getModelName()),
                StringUtils.defaultString(model.getModelCode()),
                StringUtils.defaultString(model.getAdapterCode()),
                StringUtils.defaultString(model.getTemplateCode()),
                StringUtils.defaultString(model.getVendorName()),
                StringUtils.defaultString(model.getProductName()),
                StringUtils.defaultString(model.getUpstreamModelId()),
                firstNonBlank(config, "type", "model_name", "model"));
        return StringUtils.containsIgnoreCase(identity, "deepseek");
    }

    private String firstNonBlank(JSONObject config, String... keys) {
        if (config == null) return "";
        for (String key : keys) {
            String value = config.getStr(key);
            if (StringUtils.isNotBlank(value)) return value;
        }
        return "";
    }

    private boolean isUsableSecret(String value) {
        return StringUtils.isNotBlank(value)
                && !StringUtils.contains(value, "****")
                && !StringUtils.startsWithIgnoreCase(StringUtils.trim(value), "你的");
    }
}
