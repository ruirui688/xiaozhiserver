package xiaozhi.modules.memory.utils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import xiaozhi.common.utils.JsonUtils;

/**
 * 长期记忆模块的静态工具类和常量。
 * 所有方法均为无依赖纯函数，可在任何 Service 中安全调用。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MemoryHelper {

    // ===================== 状态常量 =====================

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_ARCHIVED = "archived";
    public static final String STATUS_DELETED = "deleted";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_INACTIVE = "inactive";
    public static final String SCOPE_DEVICE = "device";
    public static final BigDecimal DEFAULT_SCORE = new BigDecimal("0.700");
    public static final BigDecimal MIN_SCORE = new BigDecimal("0.000");
    public static final BigDecimal MAX_SCORE = new BigDecimal("1.000");
    public static final BigDecimal MIN_AUTO_IMPORTANCE = new BigDecimal("0.350");
    public static final BigDecimal MIN_AUTO_CONFIDENCE = new BigDecimal("0.500");
    public static final int MAX_MAINTENANCE_INPUT_CHARS = 2000;
    public static final int DEFAULT_ACTIVE_CONTEXT_MAX_CHARS = 1000;
    public static final int SESSION_MEMORY_IDLE_SECONDS = 60;
    public static final int SESSION_MEMORY_PROCESSING_STALE_MINUTES = 5;
    public static final int SESSION_MEMORY_IDLE_BATCH_SIZE = 20;
    public static final String MAINTENANCE_LLM_FIELD = "maintenance_llm";
    public static final String MAINTENANCE_EXTRACTOR_VERSION = "maintenance-llm-v3-powerMem";
    public static final int SESSION_MAX_CHARS = 8000;
    public static final int REBUILD_MAX_TOTAL_CHARS = 48000;
    public static final int WORKING_MEMORY_TTL_HOURS = 24;
    public static final int SHORT_TERM_MEMORY_TTL_DAYS = 30;

    // ===================== 纯工具方法 =====================

    /**
     * 从 LLM 输出中提取 JSON 对象（处理 ``` 围栏）。
     */
    public static String extractJsonObject(String output) {
        String text = StringUtils.trimToEmpty(output);
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return text.substring(start, end + 1);
    }

    /**
     * 将 Map / String 统一转换为 JSONObject。
     */
    public static JSONObject toJsonObject(Object item) {
        if (item instanceof JSONObject jsonObject) {
            return jsonObject;
        }
        if (item instanceof Map<?, ?> map) {
            return JSONUtil.parseObj(map);
        }
        if (item instanceof String text && StringUtils.isNotBlank(text)) {
            return JSONUtil.parseObj(text);
        }
        return null;
    }

    /**
     * 清理记忆文本：去除首尾标点符号和空白。
     */
    public static String cleanupMemoryText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("^[：:，,。\\s]+", "").replaceAll("[。！!\\s]+$", "").trim();
    }

    /**
     * 按 Unicode 码点计算用户可见字符数，避免 emoji 等辅助平面字符被重复计数。
     */
    public static int characterCount(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    /**
     * 按 Unicode 码点截断，不切断代理项对。
     */
    public static String truncateToCharacters(String text, int maxCharacters) {
        if (text == null || maxCharacters <= 0) {
            return "";
        }
        if (characterCount(text) <= maxCharacters) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxCharacters));
    }

    /**
     * 对数值打分，结果在 [MIN_SCORE, MAX_SCORE] 范围内。
     */
    public static BigDecimal score(Object value, BigDecimal defaultValue) {
        BigDecimal score = defaultValue;
        if (value instanceof Number number) {
            score = new BigDecimal(number.toString());
        } else if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
            try {
                score = new BigDecimal(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                score = defaultValue;
            }
        }
        if (score.compareTo(MIN_SCORE) < 0) {
            return MIN_SCORE;
        }
        if (score.compareTo(MAX_SCORE) > 0) {
            return MAX_SCORE;
        }
        return score;
    }

    /**
     * null-safe 最大值比较。
     */
    public static BigDecimal max(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        return left.compareTo(right) >= 0 ? left : right;
    }

    /**
     * SHA-256 十六进制字符串，失败时回退到 UUID。
     */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    /**
     * 生成无连字符的 UUID。
     */
    public static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 标准化记忆维护动作：ADD / UPDATE / DELETE / NONE。
     */
    public static String normalizeAction(String value) {
        String action = StringUtils.defaultIfBlank(value, "NONE").trim().toUpperCase();
        return List.of("ADD", "UPDATE", "DELETE", "ARCHIVE", "NONE").contains(action) ? action : "NONE";
    }

    /**
     * 标准化记忆标签（memoryType / category），超过 40 字符自动截断。
     */
    public static String normalizeMemoryLabel(String value, String defaultValue) {
        String label = cleanupMemoryText(value);
        if (StringUtils.isBlank(label)) {
            return defaultValue;
        }
        return StringUtils.abbreviate(label, 40);
    }

    /**
     * 标准化敏感度：normal / personal / sensitive。
     */
    public static String normalizeSensitivity(String value) {
        String sensitivity = StringUtils.defaultIfBlank(value, "normal").trim();
        if (List.of("normal", "personal", "sensitive").contains(sensitivity)) {
            return sensitivity;
        }
        return "normal";
    }

    /**
     * 标准化记忆层级：working / short_term / long_term。
     */
    public static String normalizeMemoryTier(String value) {
        String tier = StringUtils.trimToEmpty(value).toLowerCase();
        return List.of("working", "short_term", "long_term").contains(tier) ? tier : "";
    }

    /**
     * 标准化候选记忆动作：ADD / UPDATE / IGNORE。
     */
    public static String normalizeCandidateAction(String value) {
        String action = StringUtils.defaultIfBlank(value, "ADD").trim().toUpperCase();
        if (List.of("ADD", "UPDATE", "DELETE", "ARCHIVE", "IGNORE").contains(action)) {
            return action;
        }
        return "ADD";
    }

    /**
     * 压缩查询字符串：去除标点符号，最多 24 字符。
     */
    public static String compactQuery(String query) {
        if (query == null) {
            return "";
        }
        String compact = query.replaceAll("[\\p{Punct}\\s，。！？、；：]+", "");
        if (compact.length() > 24) {
            compact = compact.substring(0, 24);
        }
        return compact;
    }

    /**
     * 从 text 或 json 中提取规范化内容文本。
     */
    public static String normalizeContent(String text, Map<String, Object> json) {
        if (StringUtils.isNotBlank(text)) {
            return text.trim();
        }
        if (json == null) {
            return "";
        }
        Object content = json.get("content");
        return content == null ? JsonUtils.toJsonString(json) : String.valueOf(content);
    }

    /**
     * 安全解析 payload JSON 字符串，失败时返回空对象。
     */
    public static JSONObject parsePayload(String payload) {
        if (StringUtils.isBlank(payload)) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(payload);
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
