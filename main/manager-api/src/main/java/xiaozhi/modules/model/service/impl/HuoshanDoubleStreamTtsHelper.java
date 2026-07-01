package xiaozhi.modules.model.service.impl;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import cn.hutool.json.JSONObject;
import xiaozhi.modules.model.entity.ModelConfigEntity;

/**
 * Shared helper for normalizing Huoshan Double Stream TTS configuration.
 * Extracted to eliminate code duplication across ModelConfigServiceImpl,
 * ModelRuntimeAssembler, and ModelTestServiceImpl.
 */
final class HuoshanDoubleStreamTtsHelper {

    static final String HUOSHAN_DOUBLE_STREAM_TYPE = "huoshan_double_stream";
    static final String HUOSHAN_DOUBLE_STREAM_WS_URL = "wss://openspeech.bytedance.com/api/v3/tts/bidirection";
    static final String DOUBAO_SEED_TTS_2_RESOURCE_ID = "seed-tts-2.0";
    static final String DOUBAO_SEED_TTS_2_DEFAULT_SPEAKER = "zh_female_sophie_uranus_bigtts";

    private HuoshanDoubleStreamTtsHelper() {
    }

    /**
     * Normalize Huoshan Double Stream TTS config using model entity to extract
     * template code and adapter code.
     */
    static void normalize(ModelConfigEntity model, JSONObject config) {
        String templateCode = model == null ? "" : model.getTemplateCode();
        String adapterCode = model == null ? "" : model.getAdapterCode();
        normalize(config, templateCode, adapterCode);
    }

    /**
     * Normalize Huoshan Double Stream TTS config with explicit template and adapter codes.
     */
    static void normalize(JSONObject config, String templateCode, String adapterCode) {
        if (config == null || !isHuoshanDoubleStreamTtsConfig(config, templateCode, adapterCode)) {
            return;
        }
        config.set("type", HUOSHAN_DOUBLE_STREAM_TYPE);
        config.set("ws_url", HUOSHAN_DOUBLE_STREAM_WS_URL);
        config.remove("model_name");
        config.remove("model");
        for (String key : List.of("base_url", "api_url", "url", "server_url", "host", "endpoint_url")) {
            String value = config.getStr(key);
            if (isLegacyHuoshanTtsEndpoint(value)) {
                config.remove(key);
            }
        }
        if (isManagedDoubaoTtsTemplate(templateCode)) {
            config.set("resource_id", DOUBAO_SEED_TTS_2_RESOURCE_ID);
            config.set("speaker", DOUBAO_SEED_TTS_2_DEFAULT_SPEAKER);
        } else {
            if (!hasUsableHuoshanTtsField(config.getStr("resource_id"))) {
                config.set("resource_id", DOUBAO_SEED_TTS_2_RESOURCE_ID);
            }
            if (!hasUsableHuoshanTtsSpeaker(templateCode, config.getStr("speaker"))) {
                config.set("speaker", DOUBAO_SEED_TTS_2_DEFAULT_SPEAKER);
            }
        }
    }

    static boolean isHuoshanDoubleStreamTtsConfig(JSONObject config, String templateCode, String adapterCode) {
        return HUOSHAN_DOUBLE_STREAM_TYPE.equalsIgnoreCase(StringUtils.defaultString(adapterCode))
                || HUOSHAN_DOUBLE_STREAM_TYPE.equalsIgnoreCase(config.getStr("type"))
                || "TTS_DOUBAO_SEED_TTS_2_STREAM".equalsIgnoreCase(StringUtils.defaultString(templateCode));
    }

    static boolean hasUsableHuoshanTtsField(String value) {
        if (!hasUsableTextValue(value)) {
            return false;
        }
        return !"BV001_streaming".equalsIgnoreCase(value)
                && !"BV700_streaming".equalsIgnoreCase(value);
    }

    private static boolean hasUsableTextValue(String stringValue) {
        return StringUtils.isNotBlank(stringValue)
                && !"null".equalsIgnoreCase(stringValue)
                && !isPlaceholderSecret(stringValue);
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

    static boolean isManagedDoubaoTtsTemplate(String templateCode) {
        return "TTS_DOUBAO_SEED_TTS_2_STREAM".equalsIgnoreCase(StringUtils.defaultString(templateCode));
    }

    static boolean hasUsableHuoshanTtsSpeaker(String templateCode, String value) {
        if (!hasUsableHuoshanTtsField(value)) {
            return false;
        }
        return !"TTS_DOUBAO_SEED_TTS_2_STREAM".equalsIgnoreCase(StringUtils.defaultString(templateCode))
                || !StringUtils.endsWithIgnoreCase(StringUtils.trim(value), "_moon_bigtts");
    }

    static boolean isLegacyHuoshanTtsEndpoint(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.contains("openspeech.bytedance.com/api/v3/tts/unidirectional")
                || (normalized.startsWith("https://openspeech.bytedance.com/api/v3/tts/")
                        && !normalized.startsWith(HUOSHAN_DOUBLE_STREAM_WS_URL));
    }
}
