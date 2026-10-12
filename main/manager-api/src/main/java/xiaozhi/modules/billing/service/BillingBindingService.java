package xiaozhi.modules.billing.service;

import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.billing.dto.BillingBindingRequest;
import xiaozhi.modules.security.user.SecurityUser;

@Service @RequiredArgsConstructor
public class BillingBindingService {
    private final JdbcTemplate jdbc;
    private final BillingQueryService queries;

    public xiaozhi.common.page.PageData<Map<String,Object>> page(int page,int limit) {
        BillingQueryService.requireAdmin();
        if(page<1 || page>1_000_000 || limit<1 || limit>100)throw new RenException("分页参数无效");
        String workspace=queries.workspace();
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_binding WHERE workspace_id=?",Long.class,workspace);
        var rows=jdbc.queryForList("SELECT id,provider,account_ref accountRef,scope_type scopeType,scope_value scopeValue,workspace_id workspaceId,agent_id agentId,device_id deviceId,valid_from validFrom,valid_to validTo,note FROM ai_billing_binding WHERE workspace_id=? ORDER BY valid_from DESC,id LIMIT ? OFFSET ?",workspace,limit,(page-1)*limit);
        return new xiaozhi.common.page.PageData<>(rows,total);
    }

    @Transactional
    public Map<String, Object> save(BillingBindingRequest request) {
        BillingQueryService.requireAdmin(); String workspace = queries.workspace();
        if (!workspace.equals(request.getWorkspaceId())) throw new RenException("计费绑定必须属于当前工作空间");
        if (request.getValidTo()!=null && !request.getValidFrom().isBefore(request.getValidTo())) throw new RenException("绑定有效时间无效");
        checkResource("ai_agent", request.getAgentId(), workspace); checkResource("ai_device", request.getDeviceId(), workspace);
        String id = request.getId() == null || request.getId().isBlank() ? UUID.randomUUID().toString() : request.getId();
        lockAccount(request.getProvider(), request.getAccountRef());
        if (request.getId()!=null && !request.getId().isBlank()) {
            var previous = jdbc.queryForList("SELECT workspace_id,provider,account_ref FROM ai_billing_binding WHERE id=?", id);
            if (previous.isEmpty() || !workspace.equals(previous.getFirst().get("workspace_id"))) throw new RenException("绑定不存在");
            if (!request.getProvider().equals(previous.getFirst().get("provider")) || !request.getAccountRef().equals(previous.getFirst().get("account_ref"))) throw new RenException("供应商账号不能修改，请新建绑定");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_bill WHERE binding_id=?",Long.class,id)>0) {
                return retireReferenced(request,workspace);
            }
        }
        String scopeValue = "ACCOUNT".equals(request.getScopeType()) ? "*" : request.getScopeValue();
        Long overlap = jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_binding WHERE provider=? AND account_ref=? AND id<>? AND (scope_type='ACCOUNT' OR ?='ACCOUNT' OR (scope_type=? AND scope_value=?)) AND (valid_to IS NULL OR valid_to>?) AND (? IS NULL OR valid_from<?)", Long.class,
                request.getProvider(), request.getAccountRef(), id, request.getScopeType(), request.getScopeType(), scopeValue,
                BillingSupport.time(request.getValidFrom()), request.getValidTo()==null ? null : BillingSupport.time(request.getValidTo()), request.getValidTo()==null ? null : BillingSupport.time(request.getValidTo()));
        if (overlap != null && overlap>0) throw new RenException("同一供应商计费范围存在重叠有效期");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id); row.put("provider", request.getProvider()); row.put("account_ref", request.getAccountRef());
        row.put("scope_type", request.getScopeType()); row.put("scope_value", scopeValue); row.put("workspace_id", workspace);
        row.put("agent_id", empty(request.getAgentId())); row.put("device_id", empty(request.getDeviceId()));
        row.put("valid_from", BillingSupport.time(request.getValidFrom())); row.put("valid_to", request.getValidTo()==null ? null : BillingSupport.time(request.getValidTo()));
        row.put("note", request.getNote()); row.put("created_by", SecurityUser.getUserId()); row.put("created_at", BillingSupport.time(Instant.now()));
        jdbc.update("DELETE FROM ai_billing_binding WHERE id=?", id);
        BillingUsageService.insert(jdbc, "ai_billing_binding", row);
        return Map.of("id", id, "status", "RESYNC_REQUIRED");
    }

    @Transactional
    public void delete(String id) {
        BillingQueryService.requireAdmin(); String workspace = queries.workspace();
        var rows = jdbc.queryForList("SELECT provider,account_ref FROM ai_billing_binding WHERE id=? AND workspace_id=?", id, workspace);
        if (rows.isEmpty()) throw new RenException("绑定不存在");
        lockAccount(rows.getFirst().get("provider").toString(), rows.getFirst().get("account_ref").toString());
        requireUnused(id); jdbc.update("DELETE FROM ai_billing_binding WHERE id=? AND workspace_id=?", id, workspace);
    }

    void lockAccount(String provider, String accountRef) {
        String id = BillingSupport.hash(provider+":"+accountRef);
        jdbc.update("INSERT IGNORE INTO ai_billing_account_lock (id) VALUES (?)", id);
        jdbc.queryForObject("SELECT id FROM ai_billing_account_lock WHERE id=? FOR UPDATE", String.class, id);
    }

    /** Whole official consumption interval must fit one and only one effective binding. */
    Map<String, Object> attribution(Map<String, Object> bill) {
        var rows = jdbc.queryForList("SELECT id,workspace_id,agent_id,device_id FROM ai_billing_binding WHERE provider=? AND account_ref=? AND valid_from<=? AND (valid_to IS NULL OR (valid_to>=? AND valid_to>?)) AND (scope_type='ACCOUNT' OR (scope_type='PROJECT' AND scope_value=?) OR (scope_type='RESOURCE' AND scope_value=?))",
                bill.get("provider"), bill.get("account_ref"), bill.get("expense_begin"), bill.get("expense_end"), bill.get("expense_begin"), bill.get("project"), bill.get("resource_id"));
        if (rows.size()!=1) return Map.of("attribution_level", rows.isEmpty() ? "UNASSIGNED" : "AMBIGUOUS");
        var binding = rows.getFirst(); Map<String, Object> result = new LinkedHashMap<>();
        result.put("binding_id", binding.get("id")); result.put("workspace_id", binding.get("workspace_id"));
        result.put("agent_id", binding.get("agent_id")); result.put("device_id", binding.get("device_id"));
        result.put("attribution_level", binding.get("device_id")!=null ? "DEVICE" : binding.get("agent_id")!=null ? "AGENT" : "WORKSPACE");
        return result;
    }
    private void checkResource(String table, String id, String workspace) {
        if (empty(id)==null) return;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE id=? AND workspace_id=?", Long.class, id, workspace);
        if (count == null || count != 1) throw new RenException("资源不属于当前工作空间");
    }
    private void requireUnused(String id) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_bill WHERE binding_id=?", Long.class, id)>0)
            throw new RenException("已有账单引用此绑定，不能改写历史归属；请使用不重叠有效期的新绑定");
    }
    private Map<String,Object> retireReferenced(BillingBindingRequest request,String workspace) {
        var old=jdbc.queryForMap("SELECT * FROM ai_billing_binding WHERE id=?",request.getId());
        String scope="ACCOUNT".equals(request.getScopeType()) ? "*" : request.getScopeValue();
        LocalDateTimeValue dates=new LocalDateTimeValue(request);
        Object oldEnd=old.get("valid_to");
        if(!Objects.equals(old.get("scope_type"),request.getScopeType()) || !Objects.equals(old.get("scope_value"),scope)
                || !Objects.equals(old.get("agent_id"),empty(request.getAgentId())) || !Objects.equals(old.get("device_id"),empty(request.getDeviceId()))
                || !Objects.equals(((java.sql.Timestamp)old.get("valid_from")).toLocalDateTime(),dates.from)
                || dates.to==null || (oldEnd!=null && dates.to.isAfter(((java.sql.Timestamp)oldEnd).toLocalDateTime())))
            throw new RenException("已有账单的绑定仅允许结束有效期，不能改写历史归属");
        Long outside=jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_bill WHERE binding_id=? AND (expense_end>? OR expense_begin>=?)",Long.class,request.getId(),dates.to,dates.to);
        if(outside!=null && outside>0) throw new RenException("结束时间早于已归属账单，不能改写历史");
        jdbc.update("UPDATE ai_billing_binding SET valid_to=?,note=? WHERE id=? AND workspace_id=?",dates.to,request.getNote(),request.getId(),workspace);
        return Map.of("id",request.getId(),"status","RETIRED");
    }
    private static class LocalDateTimeValue {
        final java.time.LocalDateTime from,to;
        LocalDateTimeValue(BillingBindingRequest value) { from=BillingSupport.time(value.getValidFrom());to=value.getValidTo()==null ? null : BillingSupport.time(value.getValidTo()); }
    }
    private String empty(String value) { return value == null || value.isBlank() ? null : value; }
}
