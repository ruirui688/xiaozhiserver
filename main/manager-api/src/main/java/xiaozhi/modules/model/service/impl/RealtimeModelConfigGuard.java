package xiaozhi.modules.model.service.impl;

import java.net.URI;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import cn.hutool.json.JSONObject;
import xiaozhi.common.exception.RenException;

/** Enforces the one-to-one contract between a Realtime template and its adapter. */
public final class RealtimeModelConfigGuard {

    private static final Map<String, String> TEMPLATE_ADAPTERS = Map.of(
            "LLM_ALI_QWEN_OMNI_REALTIME", "qwen_omni_realtime",
            "LLM_DOUBAO_REALTIME", "doubao_realtime");

    private RealtimeModelConfigGuard() {
    }

    public static void validateForSave(String modelType, String provideCode, String templateCode,
            String adapterCode, JSONObject config, String endpointFallback) {
        String adapter = firstNonBlank(adapterCode, config == null ? "" : config.getStr("type"), provideCode);
        String endpoint = config == null ? endpointFallback : firstNonBlank(
                config.getStr("base_url"), config.getStr("ws_url"), config.getStr("endpoint_url"),
                endpointFallback);
        String error = validationError(modelType, templateCode, adapter,
                config == null ? "" : config.getStr("conversation_mode"), endpoint);
        if (StringUtils.isNotBlank(error)) {
            throw new RenException(error);
        }
        if (isRealtimeAdapter(adapter) && StringUtils.isNotBlank(provideCode)
                && !adapter.equalsIgnoreCase(provideCode)) {
            throw new RenException("Realtime 配置不一致：请求路径 provider=" + provideCode
                    + " 与 adapter=" + adapter + " 不一致，请重新选择 Realtime 模板后保存");
        }
    }

    public static String validationError(String modelType, String templateCode, String adapterCode,
            String conversationMode, String endpointUrl) {
        String type = StringUtils.upperCase(StringUtils.trimToEmpty(modelType));
        String template = StringUtils.upperCase(StringUtils.trimToEmpty(templateCode));
        String adapter = StringUtils.lowerCase(StringUtils.trimToEmpty(adapterCode));
        String mode = StringUtils.lowerCase(StringUtils.trimToEmpty(conversationMode));
        String expectedAdapter = TEMPLATE_ADAPTERS.get(template);
        boolean realtimeSignal = "realtime".equals(mode)
                || isRealtimeAdapter(adapter)
                || expectedAdapter != null;
        if (!realtimeSignal) {
            return "";
        }
        if (!"LLM".equals(type)) {
            return "Realtime 端到端模型只能作为 LLM 主模型，不能保存为 " + type;
        }
        if (expectedAdapter == null) {
            return "Realtime 配置不一致：conversation_mode=realtime 不能使用普通 "
                    + StringUtils.defaultIfBlank(adapter, "空")
                    + " adapter；请选择 Qwen Omni Realtime 或豆包端到端实时语音模板";
        }
        if (!expectedAdapter.equals(adapter)) {
            return "Realtime 配置不一致：模板 " + template + " 必须使用 adapter="
                    + expectedAdapter + "，当前却是 " + StringUtils.defaultIfBlank(adapter, "空");
        }
        if (!"realtime".equals(mode)) {
            return "Realtime 配置不一致：adapter=" + adapter
                    + " 的 conversation_mode 必须为 realtime";
        }
        if (StringUtils.isNotBlank(endpointUrl) && !isWssUrl(endpointUrl)) {
            return "Realtime 配置不一致：adapter=" + adapter
                    + " 必须使用 wss:// Realtime WebSocket 地址，不能使用 HTTP Chat Completions 地址";
        }
        return "";
    }

    private static boolean isRealtimeAdapter(String adapter) {
        return TEMPLATE_ADAPTERS.containsValue(StringUtils.lowerCase(StringUtils.trimToEmpty(adapter)));
    }

    private static boolean isWssUrl(String endpointUrl) {
        try {
            URI uri = URI.create(StringUtils.trimToEmpty(endpointUrl));
            return "wss".equalsIgnoreCase(uri.getScheme()) && StringUtils.isNotBlank(uri.getHost());
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }
}
