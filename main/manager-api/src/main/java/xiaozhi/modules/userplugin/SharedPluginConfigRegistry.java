package xiaozhi.modules.userplugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import xiaozhi.common.exception.RenException;

public final class SharedPluginConfigRegistry {
    public static final String WEATHER_PROVIDER = "get_weather";
    public static final String WEB_SEARCH_PROVIDER = "web_search";

    private static final Pattern QWEATHER_HOST = Pattern.compile(
            "^(?:[a-z0-9-]+\\.)*qweatherapi\\.com$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> WEATHER_PUBLIC_HOSTS = Set.of(
            "geoapi.qweather.com",
            "api.qweather.com",
            "devapi.qweather.com",
            "geocoding-api.open-meteo.com",
            "api.open-meteo.com");

    private static final Map<String, Definition> DEFINITIONS;
    private static final Map<String, Definition> DEFINITIONS_BY_PLUGIN_ID;

    static {
        Map<String, Definition> byProvider = new LinkedHashMap<>();
        register(byProvider, new Definition(
                "SYSTEM_PLUGIN_WEATHER",
                WEATHER_PROVIDER,
                "天气查询",
                Set.of("api_host", "api_key", "default_location"),
                Set.of("api_key")));
        register(byProvider, new Definition(
                "SYSTEM_PLUGIN_WEB_SEARCH",
                WEB_SEARCH_PROVIDER,
                "联网搜索",
                Set.of("provider", "api_key"),
                Set.of("api_key")));
        DEFINITIONS = Collections.unmodifiableMap(byProvider);

        Map<String, Definition> byPluginId = new LinkedHashMap<>();
        for (Definition definition : byProvider.values()) {
            byPluginId.put(definition.pluginId(), definition);
        }
        DEFINITIONS_BY_PLUGIN_ID = Collections.unmodifiableMap(byPluginId);
    }

    private SharedPluginConfigRegistry() {
    }

    public static Definition requireByProviderCode(String providerCode) {
        Definition definition = DEFINITIONS.get(StringUtils.trimToEmpty(providerCode));
        if (definition == null) {
            throw new RenException("该插件不支持用户级共享配置");
        }
        return definition;
    }

    public static Definition findByProviderCode(String providerCode) {
        return DEFINITIONS.get(StringUtils.trimToEmpty(providerCode));
    }

    public static Map<String, Object> stripSharedAgentFields(String pluginId, Map<String, Object> params) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (params != null) {
            sanitized.putAll(params);
        }
        Definition definition = DEFINITIONS_BY_PLUGIN_ID.get(pluginId);
        if (definition != null) {
            definition.sharedKeys().forEach(sanitized::remove);
        }
        return sanitized;
    }

    public static Map<String, Object> normalize(
            Definition definition,
            Map<String, Object> submitted,
            Map<String, Object> existing) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (existing != null) {
            for (String key : definition.sharedKeys()) {
                String value = stringValue(existing.get(key));
                if (StringUtils.isNotBlank(value)) {
                    normalized.put(key, value);
                }
            }
        }
        if (submitted != null) {
            for (String key : definition.sharedKeys()) {
                if (!submitted.containsKey(key)) {
                    continue;
                }
                String value = stringValue(submitted.get(key));
                if (definition.secretKeys().contains(key) && StringUtils.isBlank(value)) {
                    continue;
                }
                if (StringUtils.isBlank(value)) {
                    normalized.remove(key);
                } else {
                    if (value.length() > 512) {
                        throw new RenException("插件配置字段过长: " + key);
                    }
                    normalized.put(key, value);
                }
            }
        }
        validate(definition, normalized);
        return normalized;
    }

    public static Map<String, Object> publicConfig(Definition definition, Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (config == null) {
            return result;
        }
        for (String key : definition.sharedKeys()) {
            if (!definition.secretKeys().contains(key) && config.containsKey(key)) {
                result.put(key, config.get(key));
            }
        }
        return result;
    }

    public static boolean secretsConfigured(Definition definition, Map<String, Object> config) {
        return definition.secretKeys().stream()
                .allMatch(key -> StringUtils.isNotBlank(stringValue(config == null ? null : config.get(key))));
    }

    private static void validate(Definition definition, Map<String, Object> config) {
        if (!secretsConfigured(definition, config)) {
            throw new RenException("首次配置时必须填写API Key");
        }
        if (WEATHER_PROVIDER.equals(definition.providerCode())) {
            String host = stringValue(config.get("api_host")).toLowerCase();
            if (!WEATHER_PUBLIC_HOSTS.contains(host) && !QWEATHER_HOST.matcher(host).matches()) {
                throw new RenException("天气API Host不在允许范围内");
            }
            if (StringUtils.isBlank(stringValue(config.get("default_location")))) {
                throw new RenException("天气默认城市不能为空");
            }
        } else if (WEB_SEARCH_PROVIDER.equals(definition.providerCode())) {
            String provider = stringValue(config.get("provider")).toLowerCase();
            if (!Set.of("metaso", "tavily").contains(provider)) {
                throw new RenException("联网搜索源仅支持metaso或tavily");
            }
            config.put("provider", provider);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : StringUtils.trim(String.valueOf(value));
    }

    private static void register(Map<String, Definition> definitions, Definition definition) {
        definitions.put(definition.providerCode(), definition);
    }

    public record Definition(
            String pluginId,
            String providerCode,
            String pluginName,
            Set<String> sharedKeys,
            Set<String> secretKeys) {
    }
}
