package xiaozhi.modules.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import xiaozhi.common.exception.RenException;

/** Speech SaaS has a separate resource-pack API; a product resource ID is not a pack ID. */
@Component
public class VolcengineSpeechBenefitClient {
    private final VolcengineBillingClient client;
    private final String project;
    private final List<String> resourceIds;
    public VolcengineSpeechBenefitClient(VolcengineBillingClient client,
            @Value("${xiaozhi.billing.volcengine.speech-project:}") String project,
            @Value("${xiaozhi.billing.volcengine.speech-resource-ids:}") String resourceIds) {
        this.client = client; this.project = project;
        this.resourceIds = Arrays.stream(resourceIds.split(",")).map(String::trim).filter(id -> !id.isEmpty()).distinct().toList();
    }
    public boolean enabled() { return client.enabled() && !project.isBlank() && !resourceIds.isEmpty() && snapshotRef().length() <= 128; }
    public String snapshotRef() { return client.accountRef() + "/speech/" + project; }
    public Map<String,Object> queryScope() { return Map.of("scope","CONFIGURED_SPEECH_RESOURCES","project",project,"resourceIds",resourceIds); }
    public List<Map<String, Object>> fetch() {
        if (!enabled()) throw new RenException("尚未配置语音计费项目名称和服务标识");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode source : client.fetchSpeechPackages(project, resourceIds)) {
            Map<String, Object> row = pack(source, client.accountRef(), project);
            // A snapshot-local UI key is not a supplier instance ID and must never grant ownership.
            row.put("rowKey", "speech-pack-" + rows.size());
            rows.add(row);
        }
        return rows;
    }

    static Map<String, Object> pack(JsonNode source, String account, String project) {
        if (source == null || !source.isObject()) throw new RenException("语音资源包字段格式无效");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", "VOLCENGINE_SPEECH"); row.put("accountRef", account); row.put("project", project);
        row.put("instanceNo", scalar(source, "InstanceNumber"));
        // Free packs may omit InstanceNumber. Code/ResourceID identify products, not purchased instances.
        row.put("code", scalar(source, "Code"));
        row.put("name", scalar(source, "Alias")); row.put("product", scalar(source, "ResourceID"));
        row.put("configurationName", scalar(source, "ResourceDisplay")); row.put("configurationCode", scalar(source, "ConfigurationCode"));
        row.put("packageType", scalar(source, "PackType")); row.put("status", scalar(source, "State"));
        row.put("expiryTime", scalar(source, "Expires")); row.put("isShareable", source.path("IsShareable").isBoolean() ? source.get("IsShareable").asBoolean() : null);
        // Preserve official named measures; never invent available = purchased - current usage.
        JsonNode harvest = source.path("Harvest");
        if (!harvest.isMissingNode() && !harvest.isNull() && !harvest.isObject())
            throw new RenException("语音资源包计量字段格式无效");
        row.put("unit", scalar(harvest, "Unit"));
        row.put("purchasedAmount", amount(harvest, "PurchasedAmount"));
        row.put("currentUsage", amount(harvest, "CurrentUsage"));
        // The top-level fields are supplier display strings (for example "20,000 字数").
        // Preserve them as a fallback without parsing localized text or manufacturing a zero.
        if (row.get("purchasedAmount") == null) row.put("purchasedAmount", scalar(source, "PurchasedAmount"));
        if (row.get("currentUsage") == null) row.put("currentUsage", scalar(source, "CurrentUsage"));
        return row;
    }
    private static String amount(JsonNode source, String field) {
        JsonNode value = source.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw new RenException("语音资源包计量字段格式无效");
        return value.decimalValue().toPlainString();
    }
    private static String scalar(JsonNode source, String field) {
        JsonNode value = source.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.asText().length() > 1024) throw new RenException("语音资源包字段格式无效");
        if (value.asText().isBlank()) return null;
        return value.asText();
    }
}
