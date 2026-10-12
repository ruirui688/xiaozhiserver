package xiaozhi.modules.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;

/** Supplier credit is a current snapshot, never an expense or an allocation by model usage. */
@Service @RequiredArgsConstructor
public class BillingBenefitService {
    private final JdbcTemplate jdbc;
    private final BillingSupport support;
    private final BillingQueryService queries;
    private final VolcengineBillingClient volcengine;
    private final DeepSeekBalanceClient deepseek;
    private final VolcengineSpeechBenefitClient speech;
    private final AtomicBoolean running = new AtomicBoolean();
    private static final Map<String, String> PACKAGE_FIELDS = Map.ofEntries(
            Map.entry("instanceNo", "InstanceNo"), Map.entry("name", "InstanceName"),
            Map.entry("product", "Product"), Map.entry("productName", "ProductName"),
            Map.entry("configurationName", "ConfigurationName"), Map.entry("configurationCode", "ConfigurationCode"),
            Map.entry("packageType", "PackageType"), Map.entry("status", "Status"),
            Map.entry("totalAmount", "TotalAmount"), Map.entry("availableAmount", "AvailableAmount"),
            Map.entry("unit", "Unit"), Map.entry("effectiveTime", "EffectiveTime"), Map.entry("expiryTime", "ExpiryTime"),
            Map.entry("resetPeriod", "ResetPeriod"), Map.entry("resetByNaturalMonth", "ResetByNaturalMonth"),
            Map.entry("specification", "Specification"), Map.entry("specificationUnit", "SpecificationUnit"));

    public Map<String, Object> overview(boolean accountView) {
        return overview(accountView,null,null);
    }
    public Map<String, Object> overview(boolean accountView,String provider,String accountRef) {
        String workspace = null;
        if (accountView) BillingQueryService.requireAdmin(); else workspace = queries.workspace();
        List<Map<String, Object>> packages = new ArrayList<>(), balances = new ArrayList<>(), sources = new ArrayList<>();
        read("VOLCENGINE", volcengine.accountRef(), volcengine.enabled(), workspace, accountView, packages, balances, sources);
        read("VOLCENGINE_SPEECH", speech.snapshotRef(), speech.enabled(), workspace, accountView, packages, balances, sources);
        // Account cash/credit cannot be attributed to a workspace just because it uses this API key.
        if (accountView) read("DEEPSEEK", deepseek.accountRef(), deepseek.enabled(), workspace, true, packages, balances, sources);
        if(provider!=null && !provider.isBlank()) {
            packages.removeIf(item->!provider.equals(BillingBenefitReadModel.canonicalProvider(Objects.toString(item.get("provider"),""))));
            balances.removeIf(item->!provider.equals(item.get("provider")));
            sources.removeIf(item->!provider.equals(BillingBenefitReadModel.canonicalProvider(item.get("provider").toString())));
        }
        if(accountRef!=null && !accountRef.isBlank()) {
            packages.removeIf(item->!accountRef.equals(item.get("accountRef")));
            balances.removeIf(item->!accountRef.equals(item.get("accountRef")));
            sources.removeIf(item->!accountRef.equals(item.get("accountRef")));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspaceId", workspace); result.put("canManage", BillingQueryService.canManage()); result.put("accountView", accountView);
        result.put("sources", sources); result.put("packages", packages); result.put("balances", balances);
        result.put("assets",BillingBenefitReadModel.assets(packages));
        result.put("scope",accountView?"ACCOUNT":"WORKSPACE");
        result.put("coverage",Map.of("status","CONFIGURED_SOURCES_ONLY","complete",false));
        result.put("notices", List.of(
                "此处为最近同步的当前额度快照，不受调用记录的日期、模型、智能体和设备筛选影响。",
                "资源包额度及充值/赠送余额不是消费金额，不能与官方应付或已付金额相加。",
                "通用资源包接口不保证覆盖所有语音/模型产品；未返回记录不代表没有资源包，请同时核对产品控制台。",
                "仅按供应商账号或资源包实例的明确有效绑定展示空间资源包；共享项目不自动获得整个资源包额度。",
                "临期和低余量提示基于当前快照；是否划算还需核对适用模型、计量单位、实际购买金额及到期未用额度，不自动购买。"));
        return result;
    }

    public Map<String, Object> sync() {
        BillingQueryService.requireAdmin();
        if (!running.compareAndSet(false, true)) throw new RenException("额度同步正在执行");
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            if (volcengine.enabled()) {
                try {
                    var packages = volcengine.fetchResourcePackages().stream().map(item -> packageRow(item, volcengine.accountRef())).toList();
                    Set<String> ids = new HashSet<>();
                    for (var item : packages) if (!ids.add((String) item.get("instanceNo"))) throw new RenException("资源包快照存在重复标识");
                    save("VOLCENGINE", volcengine.accountRef(), Map.of("packages", packages));
                    results.add(syncResult("VOLCENGINE", "READY"));
                } catch (Exception e) { failed("VOLCENGINE", volcengine.accountRef()); results.add(syncResult("VOLCENGINE", "SYNC_FAILED")); }
            } else results.add(syncResult("VOLCENGINE", "NOT_CONFIGURED"));
            if (speech.enabled()) {
                try {
                    var packs = speech.fetch(); Set<String> ids = new HashSet<>();
                    for (var item : packs) {
                        String instance = (String) item.get("instanceNo");
                        if (instance != null && !instance.isBlank() && !ids.add(instance))
                            throw new RenException("语音资源包存在重复标识");
                    }
                    save("VOLCENGINE_SPEECH", speech.snapshotRef(), Map.of("packages", packs)); results.add(syncResult("VOLCENGINE_SPEECH", "READY"));
                } catch (Exception e) { failed("VOLCENGINE_SPEECH", speech.snapshotRef()); results.add(syncResult("VOLCENGINE_SPEECH", "SYNC_FAILED")); }
            } else results.add(syncResult("VOLCENGINE_SPEECH", "NOT_CONFIGURED"));
            if (deepseek.enabled()) {
                try { save("DEEPSEEK", deepseek.accountRef(), Map.of("balances", deepseek.fetch())); results.add(syncResult("DEEPSEEK", "READY")); }
                catch (Exception e) { failed("DEEPSEEK", deepseek.accountRef()); results.add(syncResult("DEEPSEEK", "SYNC_FAILED")); }
            } else results.add(syncResult("DEEPSEEK", "NOT_CONFIGURED"));
            return Map.of("sources", results);
        } finally { running.set(false); }
    }

    private Map<String, Object> syncResult(String provider, String status) {
        return Map.of("provider", provider, "status", status, "message", message(provider, status));
    }

    private void save(String provider, String account, Map<String, Object> data) {
        LocalDateTime now = BillingSupport.time(Instant.now());
        jdbc.update("INSERT INTO ai_billing_benefit_snapshot (provider,account_ref,data_json,status,synced_at,attempted_at) VALUES (?,?,?,'READY',?,?) ON DUPLICATE KEY UPDATE data_json=VALUES(data_json),status='READY',synced_at=VALUES(synced_at),attempted_at=VALUES(attempted_at)",
                provider, account, support.json(data), now, now);
    }

    private void failed(String provider, String account) {
        jdbc.update("INSERT INTO ai_billing_benefit_snapshot (provider,account_ref,status,attempted_at) VALUES (?,?,'SYNC_FAILED',?) ON DUPLICATE KEY UPDATE status='SYNC_FAILED',attempted_at=VALUES(attempted_at)",
                provider, account, BillingSupport.time(Instant.now()));
    }

    @SuppressWarnings("unchecked")
    private void read(String provider, String account, boolean enabled, String workspace, boolean accountView,
            List<Map<String, Object>> packages, List<Map<String, Object>> balances, List<Map<String, Object>> sources) {
        Map<String, Object> source = new LinkedHashMap<>(); source.put("provider", provider);
        if(accountView)source.put("accountRef","VOLCENGINE_SPEECH".equals(provider)?volcengine.accountRef():account);
        source.put("coverage","VOLCENGINE_SPEECH".equals(provider)?speech.queryScope():Map.of("scope","SUPPLIER_API_RETURNED_RECORDS"));
        var rows = jdbc.queryForList("SELECT data_json,status,synced_at,attempted_at FROM ai_billing_benefit_snapshot WHERE provider=? AND account_ref=?", provider, account);
        String status = !enabled ? "NOT_CONFIGURED" : rows.isEmpty() ? "NEVER_SYNCED" : rows.getFirst().get("status").toString();
        source.put("status", status); source.put("message", message(provider, status));
        source.put("lastSyncedAt", rows.isEmpty() ? null : rows.getFirst().get("synced_at")); sources.add(source);
        source.put("lastSuccessAt",rows.isEmpty()?null:rows.getFirst().get("synced_at"));
        source.put("lastAttemptAt",rows.isEmpty()?null:rows.getFirst().get("attempted_at"));source.put("lastAttemptStatus",status);
        if (rows.isEmpty() || rows.getFirst().get("data_json") == null) return;
        Map<String, Object> data = support.object(rows.getFirst().get("data_json").toString());
        Instant now = Instant.now();
        for (Map<String, Object> stored : (List<Map<String, Object>>) data.getOrDefault("packages", List.of())) {
            Map<String, Object> item = new LinkedHashMap<>(stored);
            item.put("sourceProvider",provider);item.put("sourceStatus",status);item.put("lastSyncedAt",rows.getFirst().get("synced_at"));
            var bindings = jdbc.queryForList("SELECT workspace_id,agent_id,device_id FROM ai_billing_binding WHERE provider=? AND account_ref=? AND valid_from<=? AND (valid_to IS NULL OR valid_to>?) AND (scope_type='ACCOUNT' OR (scope_type='RESOURCE' AND scope_value=?))",
                    "VOLCENGINE_SPEECH".equals(provider) ? "VOLCENGINE" : provider, item.get("accountRef"), BillingSupport.time(now), BillingSupport.time(now), item.get("instanceNo"));
            boolean owned = workspace != null && bindings.size() == 1 && workspace.equals(bindings.getFirst().get("workspace_id"));
            if (!accountView && !owned) continue;
            item.put("attributionLevel", bindings.size() != 1 ? "UNASSIGNED" :
                    bindings.getFirst().get("device_id") != null ? "DEVICE" : bindings.getFirst().get("agent_id") != null ? "AGENT" : "WORKSPACE");
            item.put("alerts", alerts(item, now)); packages.add(item);
        }
        if (accountView) balances.addAll((List<Map<String, Object>>) data.getOrDefault("balances", List.of()));
    }

    static Map<String, Object> packageRow(JsonNode source, String account) {
        Map<String, Object> row = new LinkedHashMap<>(); row.put("provider", "VOLCENGINE"); row.put("accountRef", account);
        PACKAGE_FIELDS.forEach((key, field) -> {
            JsonNode value = source.get(field);
            if (value != null && !value.isNull() && !value.isValueNode()) throw new RenException("资源包字段格式无效");
            String text = value == null || value.isNull() ? null : value.asText();
            if (text != null && text.length() > 1024) throw new RenException("资源包字段超出长度");
            row.put(key, text);
        });
        if (row.get("instanceNo") == null || row.get("instanceNo").toString().isBlank()) throw new RenException("资源包缺少实例标识");
        // Supplier status and unit stay intact; amounts may be nonnumeric (e.g. unlimited).
        return row;
    }

    static List<Map<String, String>> alerts(Map<String, Object> item, Instant now) {
        List<Map<String, String>> alerts = new ArrayList<>();
        BigDecimal available = number(item.get("availableAmount")), total = number(item.get("totalAmount"));
        Instant expiry = instant(item.get("expiryTime"));
        String status = Objects.toString(item.get("status"), "");
        if ("Expired".equals(status) || expiry != null && !expiry.isAfter(now)) {
            alerts.add(Map.of("code", "EXPIRED", "message", "资源包已到期，请核对后续调用是否转为按量付费"));
        } else if ("UsedUp".equals(status) || available != null && available.signum() == 0) {
            alerts.add(Map.of("code", "DEPLETED", "message", "资源包额度已耗尽，请核对供应商后续计费方式"));
        } else {
            if (expiry != null && expiry.isBefore(now.plus(Duration.ofDays(7))))
                alerts.add(Map.of("code", "EXPIRING", "message", available == null
                        ? "资源包将在 7 天内到期；供应商未返回独立剩余额度，请核对购买量和当前用量"
                        : "资源包将在 7 天内到期且仍有余额，注意未使用额度到期失效"));
            if (available != null && total != null && total.signum() > 0 && available.signum() > 0 && available.multiply(BigDecimal.TEN).compareTo(total) <= 0)
                alerts.add(Map.of("code", "LOW_BALANCE", "message", "当前剩余额度不超过总额度的 10%，请关注补充或按量计费"));
        }
        if (item.get("resetPeriod") != null && !item.get("resetPeriod").toString().isBlank())
            alerts.add(Map.of("code", "PERIODIC_RESET", "message", "此包含周期重置信息；剩余额度不能当作全生命周期累计消耗"));
        return alerts;
    }

    private static BigDecimal number(Object value) {
        if (value == null) return null;
        try { return new BigDecimal(value.toString()); } catch (NumberFormatException e) { return null; }
    }
    private static Instant instant(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        try { return BillingBillService.parseTime(value.toString()).toInstant(ZoneOffset.UTC); }
        catch (RuntimeException e) { return null; }
    }
    private static String message(String provider, String status) {
        return switch (status) {
            case "NOT_CONFIGURED" -> "尚未配置此供应商的额度查询";
            case "NEVER_SYNCED" -> "尚未读取官方额度";
            case "SYNC_FAILED" -> "最近读取失败，保留上次快照；请检查配置、接口支持范围及只读权限";
            default -> "DEEPSEEK".equals(provider) ? "官方账户余额；不代表累计消费" : "VOLCENGINE_SPEECH".equals(provider)
                    ? "语音专属资源包快照；共享包不按项目自动归属，购买量与当前用量保留供应商口径"
                    : "官方通用资源包快照；产品专属包请同时核对产品控制台";
        };
    }
}
