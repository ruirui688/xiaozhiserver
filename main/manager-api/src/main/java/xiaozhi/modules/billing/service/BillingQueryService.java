package xiaozhi.modules.billing.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.modules.billing.dto.BillingFilter;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@Service @RequiredArgsConstructor
public class BillingQueryService {
    private final JdbcTemplate jdbc;
    private final WorkspacePermissionService permissions;
    private final VolcengineBillingClient volcengine;
    private final BillingInternalOutbox outbox;
    private final BillingSupport support;
    private static final String COUNTS = "COUNT(*) callCount,COALESCE(SUM(status='SUCCESS'),0) successCount,COALESCE(SUM(status='FAILED'),0) failedCount,COALESCE(SUM(status='CANCELLED'),0) cancelledCount,COUNT(DISTINCT session_id) sessionCount";
    // Detail rows supersede their parent amount globally, even when a filter excludes some details.
    static final String CANONICAL_BILL = " AND ((b.level='BILL' AND b.detail_complete=0) OR (b.level='DETAIL' AND NOT EXISTS (SELECT 1 FROM ai_billing_bill p WHERE p.provider=b.provider AND p.account_ref=b.account_ref AND p.bill_id=b.bill_id AND p.level='BILL' AND p.detail_complete=0)))";
    private static final String PAYMENT_BILL=" AND (b.level='BILL' OR NOT EXISTS (SELECT 1 FROM ai_billing_bill p WHERE p.provider=b.provider AND p.account_ref=b.account_ref AND p.bill_id=b.bill_id AND p.level='BILL'))";

    public String workspace() {
        String id = permissions.currentWorkspaceId(); permissions.requireRead(id); return id;
    }
    public static boolean canManage() { return xiaozhi.modules.security.ops.OpsAccess.has("supplier.bill.read", "PLATFORM", "platform-services"); }
    public static void requireAdmin() { if (!canManage()) throw new RenException("仅平台管理员可管理官方账单"); }

    public Map<String, Object> overview(BillingFilter filter) {
        String workspace = workspace(); filter.validate();
        Sql scope = eventScope(workspace, filter);
        Map<String, Object> result = summary(scope);
        result.put("workspaceId",workspace);
        result.put("scope","WORKSPACE");result.put("workspaceName",workspaceName(workspace));
        result.put("supportedFilters",Map.of("usage",List.of("startDate","endDate","provider","serviceType","agentId","deviceId","modelId","keyword","board","purpose")));
        result.put("trend", jdbc.queryForList("SELECT DATE_FORMAT(DATE_ADD(e.started_at,INTERVAL 8 HOUR),'%Y-%m-%d') date,COUNT(*) callCount FROM ai_billing_usage_event e" + scope.text + " GROUP BY date ORDER BY date", scope.args.toArray()));
        return result;
    }

    public PageData<Map<String, Object>> groups(BillingFilter filter, String groupBy) {
        if (!Set.of("agent", "device").contains(groupBy)) throw new RenException("分组参数无效");
        String workspace = workspace(); filter.validate();
        String column = groupBy + "_id";
        Sql events = eventScope(workspace, filter);
        String candidates = "SELECT DISTINCT e." + column + " id FROM ai_billing_usage_event e" + events.text;
        List<Object> params = new ArrayList<>(events.args);
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM (" + candidates + ") groups_all WHERE id IS NOT NULL", Long.class, params.toArray());
        List<Object> pageArgs = new ArrayList<>(params); pageArgs.add(filter.getLimit()); pageArgs.add((filter.getPage()-1)*filter.getLimit());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id FROM (" + candidates + ") groups_all WHERE id IS NOT NULL ORDER BY id LIMIT ? OFFSET ?", pageArgs.toArray());
        for (Map<String, Object> row : rows) {
            Object id = row.get("id"); Sql eventGroup = events.copy().and("e." + column + "=?", id);
            row.putAll(summary(eventGroup));
            String nameColumn = "agent".equals(groupBy) ? "agent_name" : "device_name";
            Object name = jdbc.queryForObject("SELECT MAX(e." + nameColumn + ") FROM ai_billing_usage_event e" + eventGroup.text, Object.class, eventGroup.args.toArray());
            row.put("name", name == null ? id : name);
            if ("device".equals(groupBy)) row.put("board", jdbc.queryForObject("SELECT MAX(e.device_board) FROM ai_billing_usage_event e" + eventGroup.text, Object.class, eventGroup.args.toArray()));
        }
        return new PageData<>(rows, count);
    }

    public PageData<Map<String, Object>> events(BillingFilter filter) {
        String workspace = workspace(); filter.validate(); Sql scope = eventScope(workspace, filter);
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_usage_event e" + scope.text, Long.class, scope.args.toArray());
        List<Object> params = new ArrayList<>(scope.args); params.add(filter.getLimit()); params.add((filter.getPage()-1)*filter.getLimit());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT e.event_id eventId,e.agent_id agentId,e.agent_name agentName,e.device_id deviceId,e.device_name deviceName,e.device_board board,e.model_id modelId,e.model_name modelName,e.provider,e.service_type serviceType,e.purpose,e.session_id sessionId,e.turn_id turnId,e.provider_request_id providerRequestId,e.started_at startedAt,e.ended_at endedAt,e.status,e.usage_complete usageComplete FROM ai_billing_usage_event e" + scope.text + " ORDER BY e.started_at DESC,e.event_id LIMIT ? OFFSET ?", params.toArray());
        for (Map<String, Object> row : rows) {
            row.put("usageComplete",row.get("usageComplete") instanceof Boolean value ? value : row.get("usageComplete") instanceof Number number && number.intValue()==1);
            row.put("usage", jdbc.queryForList("SELECT metric,unit,source,quantity FROM ai_billing_usage_metric WHERE event_id=? ORDER BY metric,source", row.get("eventId")));
        }
        return new PageData<>(rows, count);
    }

    public PageData<Map<String, Object>> bills(BillingFilter filter) {
        requireAdmin(); String workspace = null; filter.validate(); Sql scope;
        if(text(filter.getModelId()) || text(filter.getPurpose()))throw new RenException("官方账单不支持按内部模型或调用用途筛选");
        if(filter.isUnattributed() && (text(filter.getAgentId()) || text(filter.getDeviceId()) || text(filter.getBoard())))
            throw new RenException("未归属账单不支持按智能体、设备或型号筛选");
        if (filter.isUnattributed()) { requireAdmin(); scope = billScope(null, filter); }
        else scope = billScope(workspace, filter);
        // Statement detail view retains parent cash payment fields; only aggregate queries choose a layer.
        scope.text=scope.text.replace(CANONICAL_BILL,"");
        scope.eq("b.level",filter.getLevel()).eq("b.bill_id",filter.getBillId()).eq("b.account_ref",filter.getAccountRef());
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_bill b" + scope.text, Long.class, scope.args.toArray());
        List<Object> params = new ArrayList<>(scope.args); params.add(filter.getLimit()); params.add((filter.getPage()-1)*filter.getLimit());
        var rows = jdbc.queryForList("SELECT b.id,b.workspace_id workspaceId,b.provider,b.account_ref accountRef,b.bill_id billId,b.bill_detail_id billDetailId,b.level,b.bill_period billPeriod,b.expense_begin expenseBegin,b.expense_end expenseEnd,b.product,b.service_type serviceType,b.project,b.resource_id resourceId,b.currency,b.settlement_currency settlementCurrency,b.payable_amount payableAmount,b.paid_amount paidAmount,b.unpaid_amount unpaidAmount,b.coupon_amount couponAmount,b.settlement_type settlementType,b.pay_status payStatus,b.bill_category billCategory,b.agent_id agentId,b.device_id deviceId,b.attribution_level attributionLevel,b.source,b.evidence_hash evidenceHash,b.synced_at syncedAt FROM ai_billing_bill b" + scope.text + " ORDER BY b.expense_begin DESC,b.id LIMIT ? OFFSET ?", params.toArray());
        for(var row:rows) {
            row.put("aggregationIncluded",jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_bill b WHERE b.id=?"+CANONICAL_BILL,Long.class,row.get("id"))>0);
            String evidence=jdbc.queryForObject("SELECT evidence_json FROM ai_billing_bill WHERE id=?",String.class,row.get("id"));
            var evidenceFields=support.object(evidence);row.put("evidence",evidenceFields);
            Object supplierUsage=evidenceFields.get("supplier_usage");row.put("supplierUsage",supplierUsage instanceof Map<?,?> ? supplierUsage : Map.of());
            row.put("billingMode",supplierUsage instanceof Map<?,?> data ? data.get("BillingMode") : null);
            boolean verified=row.get("settlementCurrency")==null || row.get("settlementCurrency").equals(row.get("currency"));
            row.put("paymentCurrencyVerified",verified);
            if(!verified){row.put("paidAmount",null);row.put("unpaidAmount",null);}
        }
        return new PageData<>(rows, count);
    }

    public Map<String, Object> options() {
        if (canManage()) return Map.of("scope", "PLATFORM", "canManage", true, "providers", List.of("volcengine", "deepseek"), "serviceTypes", List.of("LLM", "ASR", "TTS", "REALTIME", "EMBEDDING"), "volcengineSyncEnabled", volcengine.enabled(), "volcengine", Map.of("enabled", volcengine.enabled(), "accountRef", volcengine.accountRef()));
        String workspace = workspace(); Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspaceId", workspace); result.put("canManage", canManage());
        result.put("workspaceName",workspaceName(workspace));result.put("scope","WORKSPACE");
        result.put("providers", jdbc.queryForList("SELECT DISTINCT provider FROM ai_billing_usage_event WHERE workspace_id=? ORDER BY provider", String.class, workspace));
        result.put("serviceTypes", List.of("LLM", "ASR", "TTS", "REALTIME", "EMBEDDING", "OTHER"));
        result.put("agents", jdbc.queryForList("SELECT id,COALESCE(MAX(name),id) name FROM (SELECT agent_id id,agent_name name FROM ai_billing_usage_event WHERE workspace_id=? AND agent_id IS NOT NULL UNION ALL SELECT id,agent_name name FROM ai_agent WHERE workspace_id=?) history GROUP BY id",workspace,workspace));
        result.put("devices", jdbc.queryForList("SELECT id,COALESCE(MAX(name),id) name,MAX(board) board FROM (SELECT device_id id,device_name name,device_board board FROM ai_billing_usage_event WHERE workspace_id=? AND device_id IS NOT NULL UNION ALL SELECT id,alias name,board FROM ai_device WHERE workspace_id=?) history GROUP BY id",workspace,workspace));
        result.put("models", jdbc.queryForList("SELECT model_id id,COALESCE(MAX(model_name),model_id) name FROM ai_billing_usage_event WHERE workspace_id=? GROUP BY model_id", workspace));
        return result;
    }

    private Map<String, Object> summary(Sql scope) {
        Map<String,Object> raw=jdbc.queryForMap("SELECT " + COUNTS + " FROM ai_billing_usage_event e" + scope.text, scope.args.toArray());
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("callCount","successCount","failedCount","cancelledCount","sessionCount"))result.put(key,raw.get(key));
        result.put("statusCounts",Map.of("SUCCESS",result.get("successCount"),"FAILED",result.get("failedCount"),"CANCELLED",result.get("cancelledCount")));
        result.put("usageUnknownCount",jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_usage_event e"+scope.text+" AND e.usage_complete=0",Long.class,scope.args.toArray()));
        result.put("usage", jdbc.queryForList("SELECT e.service_type serviceType,m.metric,m.unit,m.source,SUM(m.quantity) quantity FROM ai_billing_usage_event e JOIN ai_billing_usage_metric m ON m.event_id=e.event_id" + scope.text + " GROUP BY e.service_type,m.metric,m.unit,m.source ORDER BY e.service_type,m.metric,m.source", scope.args.toArray()));
        return result;
    }

    private void addCosts(Map<String, Object> result, Sql scope) {
        // The documented cross-currency relationship of PaidAmount is not established: keep it unknown.
        String sameCurrency="(b.settlement_currency IS NULL OR b.settlement_currency=b.currency)";
        Map<String,Map<String,Object>> grouped=new TreeMap<>();
        var payable=jdbc.queryForList("SELECT b.currency,CASE WHEN COUNT(b.payable_amount)=COUNT(*) THEN SUM(b.payable_amount) END payableAmount FROM ai_billing_bill b"+scope.text+" GROUP BY b.currency",scope.args.toArray());
        for(var row:payable){String currency=row.get("currency").toString();var item=costRow(currency);item.put("payableAmount",row.get("payableAmount"));grouped.put(currency,item);}
        // Cash settlement prefers one parent bill. It is never copied down to an unproven device scope.
        String paymentScope=scope.text.replace(CANONICAL_BILL,PAYMENT_BILL);
        var payment=jdbc.queryForList("SELECT b.currency,CASE WHEN COUNT(CASE WHEN "+sameCurrency+" THEN b.paid_amount END)=COUNT(*) THEN SUM(b.paid_amount) END paidAmount,CASE WHEN COUNT(CASE WHEN "+sameCurrency+" THEN b.unpaid_amount END)=COUNT(*) THEN SUM(b.unpaid_amount) END unpaidAmount FROM ai_billing_bill b"+paymentScope+" GROUP BY b.currency",scope.args.toArray());
        for(var row:payment){String currency=row.get("currency").toString();var item=grouped.computeIfAbsent(currency,BillingQueryService::costRow);item.put("paidAmount",row.get("paidAmount"));item.put("unpaidAmount",row.get("unpaidAmount"));}
        var costs=new ArrayList<>(grouped.values());
        result.put("costs", costs);
        boolean imported=jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_bill b"+scope.text+" AND b.source='STATEMENT_IMPORT'",Long.class,scope.args.toArray())>0;
        result.put("costStatus", costs.isEmpty() ? "NO_ATTRIBUTABLE_BILLS" : imported ? "IMPORTED_STATEMENT" : "OFFICIAL");
    }
    private static Map<String,Object> costRow(String currency){Map<String,Object> row=new LinkedHashMap<>();row.put("currency",currency);row.put("payableAmount",null);row.put("paidAmount",null);row.put("unpaidAmount",null);return row;}

    private Sql eventScope(String workspace, BillingFilter f) {
        Sql sql = new Sql(" WHERE e.workspace_id=?", workspace).and("e.started_at>=?", BillingSupport.time(f.from())).and("e.started_at<?", BillingSupport.time(f.to()));
        sql.eq("e.provider", f.getProvider()).eq("e.service_type", f.getServiceType()).eq("e.agent_id", f.getAgentId()).eq("e.device_id", f.getDeviceId()).eq("e.model_id", f.getModelId()).eq("e.device_board", f.getBoard()).eq("e.purpose",f.getPurpose());
        if (text(f.getKeyword())) sql.and("(e.agent_name LIKE ? OR e.device_name LIKE ? OR e.device_id LIKE ?)", "%"+f.getKeyword()+"%", "%"+f.getKeyword()+"%", "%"+f.getKeyword()+"%");
        return sql;
    }

    private Sql billScope(String workspace, BillingFilter f) {
        Sql sql = workspace == null ? new Sql(f.isUnattributed() ? " WHERE b.workspace_id IS NULL" : " WHERE 1=1") : new Sql(" WHERE b.workspace_id=?", workspace);
        // A bill straddling the requested dates cannot be prorated into an actual amount.
        sql.and("b.expense_begin>=?", BillingSupport.time(f.from())).and("b.expense_begin<?", BillingSupport.time(f.to()));
        // Only the admin's unassigned list can expose official bills whose consumption end is unknown.
        sql.and(workspace==null ? "(b.expense_end<=? OR b.expense_end IS NULL)" : "b.expense_end<=?", BillingSupport.time(f.to()));
        sql.text += CANONICAL_BILL;
        sql.eq("b.provider", f.getProvider()).eq("b.service_type", f.getServiceType()).eq("b.agent_id", f.getAgentId()).eq("b.device_id", f.getDeviceId()).eq("b.model_id", f.getModelId());
        if(workspace==null && text(f.getKeyword())) {
            String pattern="%"+f.getKeyword()+"%";
            sql.and("(b.bill_id LIKE ? OR b.product LIKE ? OR b.project LIKE ? OR b.resource_id LIKE ?)",pattern,pattern,pattern,pattern);
        } else if (text(f.getBoard()) || text(f.getKeyword())) {
            Sql evidence = eventScope(workspace, f); evidence.text += " AND e.device_id=b.device_id";
            sql.text += " AND EXISTS (SELECT 1 FROM ai_billing_usage_event e" + evidence.text + ")"; sql.args.addAll(evidence.args);
        }
        return sql;
    }
    private String workspaceName(String id) {
        var rows=jdbc.queryForList("SELECT name FROM sys_workspace WHERE id=?",String.class,id);
        return rows.isEmpty()?id:rows.getFirst();
    }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
    static class Sql {
        String text; final List<Object> args = new ArrayList<>();
        Sql(String text, Object... args) { this.text=text; this.args.addAll(Arrays.asList(args)); }
        Sql and(String clause, Object... values) { text += " AND " + clause; args.addAll(Arrays.asList(values)); return this; }
        Sql eq(String column, String value) { return BillingQueryService.text(value) ? and(column+"=?", value) : this; }
        Sql copy() { return new Sql(text, args.toArray()); }
    }
}
