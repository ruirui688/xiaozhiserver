package xiaozhi.modules.billing.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;

@Component
@RequiredArgsConstructor
public class BillingSupport {
    private final ObjectMapper mapper;
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new RenException("计量数据格式无效"); }
    }
    public Map<String, Object> object(String value) {
        try { return mapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { throw new RenException("计量上下文格式无效"); }
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static LocalDateTime time(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    /** DATETIME columns store UTC wall time; JDBC drivers expose either modern or legacy Java types. */
    public static LocalDateTime databaseTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime time) return time;
        if (value instanceof java.sql.Timestamp time) return time.toLocalDateTime();
        throw new RenException("数据库时间格式无效");
    }
    public static String service(String value) {
        String type = value == null ? "OTHER" : value.toUpperCase(Locale.ROOT);
        if (Set.of("SLM", "VLLM", "INTENT", "MEMORY").contains(type)) return "LLM";
        return Set.of("LLM", "ASR", "TTS", "REALTIME", "EMBEDDING").contains(type) ? type : "OTHER";
    }
    public static String provider(String vendor, String adapter) {
        String value = ((vendor == null ? "" : vendor) + " " + (adapter == null ? "" : adapter)).toLowerCase(Locale.ROOT);
        if (value.contains("volc") || value.contains("doubao") || value.contains("火山") || value.contains("豆包")) return "VOLCENGINE";
        if (value.contains("deepseek") || value.contains("深度求索")) return "DEEPSEEK";
        if (value.contains("aliyun") || value.contains("qwen") || value.contains("阿里")) return "ALIYUN";
        if (value.contains("openai")) return "OPENAI";
        String normalized = vendor == null || vendor.isBlank() ? adapter : vendor;
        return normalized == null || normalized.isBlank() ? "UNKNOWN" : normalized.toUpperCase(Locale.ROOT).substring(0, Math.min(64, normalized.length()));
    }
}
