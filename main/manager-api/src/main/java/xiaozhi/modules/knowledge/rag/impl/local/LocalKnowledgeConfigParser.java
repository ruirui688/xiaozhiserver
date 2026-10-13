package xiaozhi.modules.knowledge.rag.impl.local;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Parses local knowledge adapter settings while preserving legacy string values.
 */
@Slf4j
public final class LocalKnowledgeConfigParser {
    private final Map<String, Object> config;
    private final ObjectMapper objectMapper;

    public LocalKnowledgeConfigParser(Map<String, Object> config, ObjectMapper objectMapper) {
        this.config = config == null ? Collections.emptyMap() : new HashMap<>(config);
        this.objectMapper = objectMapper;
    }

    public String getString(String key, String defaultValue) {
        return getString(config, key, defaultValue);
    }

    public String getString(Map<String, Object> source, String key, String defaultValue) {
        if (source == null) {
            return defaultValue;
        }
        Object value = source.get(key);
        return StringUtils.defaultIfBlank(value == null ? null : value.toString(), defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return parseBoolean(config.get(key), defaultValue);
    }

    public boolean getBoolean(Map<String, Object> source, String key, boolean defaultValue) {
        return parseBoolean(source == null ? null : source.get(key), defaultValue);
    }

    public int getInt(String key, int defaultValue, int min, int max) {
        return parseInt(key, config.get(key), defaultValue, min, max);
    }

    public int getInt(Map<String, Object> source, String key, int defaultValue, int min, int max) {
        return parseInt(key, source == null ? null : source.get(key), defaultValue, min, max);
    }

    public float getFloat(Map<String, Object> source, String key, float defaultValue, float min, float max) {
        Object value = source == null ? null : source.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            float parsed = Float.parseFloat(value.toString());
            return Float.isFinite(parsed) ? clamp(parsed, min, max) : defaultValue;
        } catch (NumberFormatException e) {
            log.warn("本地知识库 {} 配置无效，使用默认值 {}", key, defaultValue);
            return defaultValue;
        }
    }

    public Map<String, Object> getMap(String key) {
        Object value = config.get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new HashMap<>();
            map.forEach((mapKey, mapValue) -> result.put(String.valueOf(mapKey), mapValue));
            return result;
        }
        if (value instanceof String text && StringUtils.isNotBlank(text)) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {
                });
                return parsed == null ? Collections.emptyMap() : parsed;
            } catch (Exception e) {
                log.warn("解析本地知识库 {} 配置失败，忽略检索扩展配置: {}", key, e.getClass().getSimpleName());
            }
        }
        return Collections.emptyMap();
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static boolean isPlaceholderSecret(String value) {
        String text = StringUtils.trimToEmpty(value);
        return StringUtils.equalsAnyIgnoreCase(text, "your_api_key", "your_key", "api_key", "none")
                || text.contains("你的");
    }

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = value.toString().trim();
        if (StringUtils.equalsAnyIgnoreCase(text, "true", "1", "yes", "y", "on")) {
            return true;
        }
        if (StringUtils.equalsAnyIgnoreCase(text, "false", "0", "no", "n", "off")) {
            return false;
        }
        return defaultValue;
    }

    private int parseInt(String key, Object value, int defaultValue, int min, int max) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return clamp(Integer.parseInt(value.toString()), min, max);
        } catch (NumberFormatException e) {
            log.warn("本地知识库 {} 配置无效，使用默认值 {}", key, defaultValue);
            return defaultValue;
        }
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
