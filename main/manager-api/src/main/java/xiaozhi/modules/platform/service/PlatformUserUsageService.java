package xiaozhi.modules.platform.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.billing.dto.BillingFilter;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;
import static xiaozhi.modules.platform.service.PlatformSupport.map;

/** User usage and platform deductions. Supplier invoices and sync state are deliberately absent. */
@Service @RequiredArgsConstructor
public class PlatformUserUsageService {
    private final JdbcTemplate jdbc;
    private final WorkspacePermissionService permissions;
    private static final String COUNTS="COUNT(*) AS callCount,COUNT(DISTINCT u.sessionId) AS sessionCount,"
        +"COALESCE(SUM(CASE WHEN u.status='SUCCESS' THEN 1 ELSE 0 END),0) AS successCount,"
        +"COALESCE(SUM(CASE WHEN u.status='FAILED' THEN 1 ELSE 0 END),0) AS failedCount,"
        +"COALESCE(SUM(CASE WHEN u.status='CANCELLED' THEN 1 ELSE 0 END),0) AS cancelledCount,"
        +"COALESCE(SUM(CASE WHEN u.usageComplete=0 THEN 1 ELSE 0 END),0) AS usageUnknownCount";
    private String workspace() { String id=permissions.currentWorkspaceId();permissions.requireRead(id);return id; }

    public Map<String,Object> options() {
        String workspace=workspace();var scope=PlatformUsageSql.all(workspace);
        var result=map("workspaceId",workspace,"scope","WORKSPACE","serviceTypes",List.of("LLM","ASR","TTS","REALTIME","EMBEDDING","OTHER"));
        result.put("providers",jdbc.queryForList("SELECT DISTINCT u.provider "+scope.sql+" AND u.provider IS NOT NULL ORDER BY u.provider",String.class,scope.args.toArray()));
        result.put("agents",jdbc.queryForList("SELECT u.agentId AS id,COALESCE(MAX(u.agentName),u.agentId) AS name "+scope.sql+" AND u.agentId IS NOT NULL GROUP BY u.agentId ORDER BY id",scope.args.toArray()));
        result.put("devices",jdbc.queryForList("SELECT u.deviceId AS id,COALESCE(MAX(u.deviceName),u.deviceId) AS name,MAX(u.board) AS board "+scope.sql+" AND u.deviceId IS NOT NULL GROUP BY u.deviceId ORDER BY id",scope.args.toArray()));
        result.put("models",jdbc.queryForList("SELECT u.modelId AS id,COALESCE(MAX(u.modelName),u.modelId) AS name "+scope.sql+" GROUP BY u.modelId ORDER BY id",scope.args.toArray()));
        return presentMap(result);
    }
    public Map<String,Object> overview(BillingFilter filter) {
        String workspace=workspace();filter.validate();
        Map<String,Object> result=summary(PlatformUsageSql.filtered(workspace,filter));result.put("workspaceId",workspace);result.put("scope","WORKSPACE");
        return presentMap(result);
    }
    public Map<String,Object> groups(BillingFilter filter,String groupBy) {
        if(!Set.of("agent","device").contains(groupBy))throw new RenException("分组参数无效");
        String workspace=workspace();filter.validate();String column="u."+groupBy+"Id",name="u."+groupBy+"Name";
        var scope=PlatformUsageSql.filtered(workspace,filter);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT "+column+" "+scope.sql+" GROUP BY "+column+") usage_groups",Long.class,scope.args.toArray());
        List<Object> args=new ArrayList<>(scope.args);args.add(filter.getLimit());args.add((filter.getPage()-1L)*filter.getLimit());
        var rows=jdbc.queryForList("SELECT "+column+" AS id,COALESCE(MAX("+name+"),"+column+",'空间公共任务') AS name,MAX(u.board) AS board "+scope.sql+" GROUP BY "+column+" ORDER BY id LIMIT ? OFFSET ?",args.toArray());
        List<Map<String,Object>> result=new ArrayList<>();
        for(var row:rows) {
            var item=map("id",row.get("id"),"name",row.get("name"),"board",row.get("board"));
            var group=row.get("id")==null?scope.copy().and(column+" IS NULL"):scope.copy().and(column+"=?",row.get("id"));
            item.putAll(summary(group));result.add(item);
        }
        return presentMap(map("workspaceId",workspace,"scope","WORKSPACE","list",result,"total",total,"page",filter.getPage(),"limit",filter.getLimit()));
    }
    public Map<String,Object> events(BillingFilter filter) {
        String workspace=workspace();filter.validate();var scope=PlatformUsageSql.filtered(workspace,filter);
        long total=jdbc.queryForObject("SELECT COUNT(*) "+scope.sql,Long.class,scope.args.toArray());
        List<Object> args=new ArrayList<>(scope.args);args.add(filter.getLimit());args.add((filter.getPage()-1L)*filter.getLimit());
        var rows=jdbc.queryForList("SELECT u.* "+scope.sql+" ORDER BY u.startedAt DESC,u.eventId LIMIT ? OFFSET ?",args.toArray());
        Set<String> shared=sharedSettlements(workspace,rows);
        List<Map<String,Object>> result=new ArrayList<>();
        for(var row:rows) {
            var item=new LinkedHashMap<String,Object>();
            for(String field:List.of("eventId","recordType","agentId","agentName","deviceId","deviceName","board","modelId","modelName","provider","serviceType","purpose","sessionId","turnId","startedAt","endedAt","status","reservationId"))item.put(field,row.get(field));
            Object complete=row.get("usageComplete");item.put("usageComplete",Boolean.TRUE.equals(complete)||complete instanceof Number number&&number.intValue()==1);
            String state=row.get("settlementStatus")==null?"UNTRACKED":row.get("settlementStatus").toString();
            item.put("settlementStatus",state);
            // These terminal states release a reservation before dispatch, so their charge is known zero.
            item.put("settledAmount","SETTLED".equals(state)?row.get("settledAmount"):Set.of("RELEASED","EXPIRED","CANCELLED").contains(state)?BigDecimal.ZERO:null);
            item.put("settlementShared",row.get("reservationId")!=null&&shared.contains(row.get("reservationId").toString()));
            item.put("reservedAmount","UNTRACKED".equals(state)?null:Set.of("RESERVED","STARTED","UNKNOWN").contains(state)?row.get("originalReserved"):BigDecimal.ZERO);
            item.put("usage","USAGE".equals(row.get("recordType"))?jdbc.queryForList("SELECT metric,unit,source,quantity FROM ai_billing_usage_metric WHERE event_id=? ORDER BY metric,unit,source",row.get("eventId")):List.of());
            result.add(item);
        }
        return presentMap(map("workspaceId",workspace,"scope","WORKSPACE","list",result,"total",total,"page",filter.getPage(),"limit",filter.getLimit()));
    }
    private Set<String> sharedSettlements(String workspace,List<Map<String,Object>> rows) {
        List<String> ids=rows.stream().map(row->row.get("reservationId")).filter(Objects::nonNull).map(Object::toString).distinct().toList();
        if(ids.isEmpty())return Set.of();
        List<Object> args=new ArrayList<>();args.add(workspace);args.addAll(ids);
        // Check all trusted receipts, including those outside this page or the selected filters.
        return new HashSet<>(jdbc.queryForList("SELECT r.id FROM ai_platform_reservation r JOIN ai_billing_usage_event e ON "+PlatformUsageSql.MATCH
            +" WHERE r.workspace_id=? AND r.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") GROUP BY r.id HAVING COUNT(*)>1",String.class,args.toArray()));
    }
    private Map<String,Object> summary(PlatformUsageSql.Scope scope) {
        var counts=jdbc.queryForMap("SELECT "+COUNTS+" "+scope.sql,scope.args.toArray());var result=new LinkedHashMap<String,Object>();
        for(String field:List.of("callCount","sessionCount","successCount","failedCount","cancelledCount","usageUnknownCount"))result.put(field,counts.get(field));
        result.put("usage",jdbc.queryForList("SELECT u.serviceType,m.metric,m.unit,m.source,SUM(m.quantity) AS quantity "+scope.sql.replace(" WHERE 1=1"," JOIN ai_billing_usage_metric m ON m.event_id=u.eventId AND u.recordType='USAGE' WHERE 1=1")+" GROUP BY u.serviceType,m.metric,m.unit,m.source ORDER BY u.serviceType,m.metric,m.source",scope.args.toArray()));
        // One reservation can have several telemetry receipts; financial totals count its ID once.
        String unique=" FROM (SELECT DISTINCT u.reservationId,u.settlementStatus,u.settledAmount,u.originalReserved "+scope.sql+" AND u.reservationId IS NOT NULL) r";
        var amounts=jdbc.queryForMap("SELECT COALESCE(SUM(CASE WHEN r.settlementStatus='SETTLED' THEN r.settledAmount ELSE 0 END),0) AS settledAmount,"
            +"COALESCE(SUM(CASE WHEN r.settlementStatus IN ('RESERVED','STARTED','UNKNOWN') THEN r.originalReserved ELSE 0 END),0) AS reservedAmount,"
            +"COALESCE(SUM(CASE WHEN r.settlementStatus='SETTLED' THEN 1 ELSE 0 END),0) AS settledCount,"
            +"COALESCE(SUM(CASE WHEN r.settlementStatus IN ('RESERVED','STARTED') THEN 1 ELSE 0 END),0) AS pendingCount,"
            +"COALESCE(SUM(CASE WHEN r.settlementStatus='UNKNOWN' THEN 1 ELSE 0 END),0) AS reviewCount"+unique,scope.args.toArray());
        var settlement=new LinkedHashMap<String,Object>();
        for(String field:List.of("settledAmount","reservedAmount","settledCount","pendingCount","reviewCount"))settlement.put(field,amounts.get(field));
        settlement.put("unlinkedCount",jdbc.queryForObject("SELECT COUNT(*) "+scope.sql+" AND u.reservationId IS NULL",Long.class,scope.args.toArray()));
        result.put("platformSettlement",settlement);return result;
    }
    private static Map<String,Object> presentMap(Map<String,Object> value) {
        Map<String,Object> result=new LinkedHashMap<>();value.forEach((key,item)->result.put(key,present(item)));return result;
    }
    private static Object present(Object value) {
        if(value instanceof BigDecimal number)return number.toPlainString();
        if(value instanceof Timestamp time)return time.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
        if(value instanceof LocalDateTime time)return time.toInstant(ZoneOffset.UTC).toString();
        if(value instanceof Map<?,?> values){Map<String,Object> result=new LinkedHashMap<>();values.forEach((key,item)->result.put(key.toString(),present(item)));return result;}
        if(value instanceof Collection<?> values)return values.stream().map(PlatformUserUsageService::present).toList();
        return value;
    }
}
