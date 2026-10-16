package xiaozhi.modules.security.ops;

import java.util.Map;
import java.util.Set;
import xiaozhi.common.exception.RenException;

/** Versioned in code: operators cannot edit templates or delegation ceilings at runtime. */
public final class OpsPermissionCatalog {
    public record Template(String code, String domain, Set<String> permissions) {}
    private static Template role(String code, String domain, String... actions) {
        return new Template(code, domain, Set.of(actions));
    }
    public static final Map<String, Template> TEMPLATES = Map.ofEntries(
        Map.entry("ALLOWANCE", role("ALLOWANCE", "platform-services", "allowance.read", "allowance.grant", "allowance.freeze")),
        Map.entry("SUPPLIER_FINANCE", role("SUPPLIER_FINANCE", "platform-services", "pool.read", "supplier.bill.read", "supplier.bill.sync", "supplier.bill.import")),
        Map.entry("CREDENTIALS", role("CREDENTIALS", "platform-services", "credentials.read", "credentials.write", "credentials.revoke", "credentials.assign", "credentials.rotate")),
        Map.entry("ALLOWANCE_POOL", role("ALLOWANCE_POOL", "platform-services", "pool.read", "pool.adjust")),
        Map.entry("ALLOWANCE_REVIEW", role("ALLOWANCE_REVIEW", "platform-services", "allowance.review")),
        Map.entry("RATES", role("RATES", "platform-services", "rates.read", "rates.write")),
        Map.entry("MODEL_CATALOG", role("MODEL_CATALOG", "models", "model.catalog.read", "model.catalog.write", "model.catalog.test", "model.catalog.publish")),
        Map.entry("OFFICIAL_CONTENT", role("OFFICIAL_CONTENT", "official-resources", "official.resource.read", "official.resource.write", "official.resource.publish", "prompt.catalog.read", "prompt.catalog.write")),
        Map.entry("OTA", role("OTA", "firmware", "ota.read", "ota.upload", "ota.publish")),
        Map.entry("OTA_REVIEW", role("OTA_REVIEW", "firmware", "ota.approve")),
        Map.entry("CONTENT_REVIEW", role("CONTENT_REVIEW", "official-resources", "official.resource.approve")),
        Map.entry("MODEL_REVIEW", role("MODEL_REVIEW", "models", "model.catalog.approve")),
        Map.entry("OPERATIONS", role("OPERATIONS", "operations", "system.read", "system.write", "device.diagnostics.read")),
        Map.entry("SUPPORT", role("SUPPORT", "support", "users.read", "users.status")),
        Map.entry("IAM_REQUEST", role("IAM_REQUEST", "identity", "iam.read", "iam.request", "iam.revoke")),
        Map.entry("IAM_APPROVE", role("IAM_APPROVE", "identity", "iam.read", "iam.approve", "iam.revoke")),
        Map.entry("AUDIT", role("AUDIT", "audit", "audit.read"))
    );
    private OpsPermissionCatalog() {}
    public static void requireCompatible(Set<String> roles) {
        // Model operations own funding and issuance; settlement review stays separate.
        for (Set<String> pair : java.util.List.of(Set.of("IAM_REQUEST", "IAM_APPROVE"),
                Set.of("ALLOWANCE_POOL", "ALLOWANCE_REVIEW"), Set.of("ALLOWANCE", "ALLOWANCE_REVIEW"),
                Set.of("OTA", "OTA_REVIEW"), Set.of("OFFICIAL_CONTENT", "CONTENT_REVIEW"),
                Set.of("MODEL_CATALOG", "MODEL_REVIEW"))) {
            if (roles.containsAll(pair)) throw new RenException("申请、执行和复核岗位必须分离");
        }
        if (roles.stream().anyMatch(r -> r.startsWith("IAM_"))
                && roles.stream().anyMatch(r -> !r.startsWith("IAM_") && !"AUDIT".equals(r)))
            throw new RenException("身份治理岗位不能同时持有业务操作岗位");
    }
    public static Template validate(String roleCode, String scopeType, String scopeId) {
        Template template = TEMPLATES.get(roleCode);
        if (template == null || !"PLATFORM".equals(scopeType) || !template.domain().equals(scopeId))
            throw new RenException("角色或授权资源范围无效");
        return template;
    }
    public static Set<String> actions() {
        return TEMPLATES.values().stream().flatMap(t -> t.permissions().stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
