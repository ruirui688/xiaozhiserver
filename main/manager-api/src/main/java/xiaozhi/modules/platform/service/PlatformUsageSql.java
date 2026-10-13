package xiaozhi.modules.platform.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import xiaozhi.modules.billing.dto.BillingFilter;
import xiaozhi.modules.billing.service.BillingSupport;

/** A tenant-scoped projection; raw runtime, route and credential fields never leave this query. */
final class PlatformUsageSql {
    private PlatformUsageSql() {}
    static final String MATCH = "e.reservation_id=r.id AND e.workspace_id=r.workspace_id AND e.model_id=r.model_id AND e.service_type=r.service_type";
    private static final String AGENT = "NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.route_json,'$.agentId')),'null')";
    private static final String DEVICE = "NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.route_json,'$.deviceId')),'null')";
    private static final String PROVIDER = "LOWER(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.route_json,'$.provider')),'null'))";
    static final String SOURCE = """
        FROM (
          SELECT e.event_id AS eventId,'USAGE' AS recordType,e.agent_id AS agentId,e.agent_name AS agentName,
            e.device_id AS deviceId,e.device_name AS deviceName,e.device_board AS board,e.model_id AS modelId,
            COALESCE(e.model_name,e.model_id) AS modelName,LOWER(e.provider) AS provider,e.service_type AS serviceType,
            e.purpose,e.session_id AS sessionId,e.turn_id AS turnId,e.started_at AS startedAt,e.ended_at AS endedAt,
            e.status,e.usage_complete AS usageComplete,r.id AS reservationId,r.state AS settlementStatus,
            r.settled_amount AS settledAmount,r.reserved_amount AS originalReserved
          FROM ai_billing_usage_event e LEFT JOIN ai_platform_reservation r ON %s
          WHERE e.workspace_id=?
          UNION ALL
          SELECT CONCAT('reservation:',r.id),'RESERVATION',%s,COALESCE(a.agent_name,%s),
            %s,COALESCE(d.alias,%s),d.board,r.model_id,COALESCE(m.model_name,r.model_id),%s,r.service_type,
            NULL,NULL,NULL,COALESCE(r.started_at,r.created_at),r.settled_at,'UNKNOWN',0,r.id,r.state,r.settled_amount,r.reserved_amount
          FROM ai_platform_reservation r
          LEFT JOIN ai_agent a ON a.id=%s AND a.workspace_id=r.workspace_id
          LEFT JOIN ai_device d ON d.id=%s AND d.workspace_id=r.workspace_id
          LEFT JOIN ai_model_config m ON m.id=r.model_id
          WHERE r.workspace_id=? AND NOT EXISTS (SELECT 1 FROM ai_billing_usage_event e WHERE %s)
        ) u
        """.formatted(MATCH,AGENT,AGENT,DEVICE,DEVICE,PROVIDER,AGENT,DEVICE,MATCH);

    static Scope all(String workspace) { return new Scope(SOURCE + " WHERE 1=1",workspace,workspace); }
    static Scope filtered(String workspace,BillingFilter filter) {
        Scope scope=all(workspace).and("u.startedAt>=?",BillingSupport.time(filter.from()))
            .and("u.startedAt<?",BillingSupport.time(filter.to()));
        scope.eq("u.provider",filter.getProvider()).eq("u.serviceType",filter.getServiceType())
            .eq("u.agentId",filter.getAgentId()).eq("u.deviceId",filter.getDeviceId())
            .eq("u.modelId",filter.getModelId()).eq("u.board",filter.getBoard()).eq("u.purpose",filter.getPurpose());
        if(text(filter.getKeyword())) {
            String value="%"+filter.getKeyword().replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            scope.and("(u.agentName LIKE ? ESCAPE '!' OR u.deviceName LIKE ? ESCAPE '!' OR u.deviceId LIKE ? ESCAPE '!')",value,value,value);
        }
        return scope;
    }
    static boolean text(String value) { return value!=null&&!value.isBlank(); }
    static final class Scope {
        String sql; final List<Object> args=new ArrayList<>();
        Scope(String sql,Object... args) { this.sql=sql;this.args.addAll(Arrays.asList(args)); }
        Scope and(String predicate,Object... args) { sql+=" AND "+predicate;this.args.addAll(Arrays.asList(args));return this; }
        Scope eq(String field,String value) { return text(value)?and(field+"=?",value):this; }
        Scope copy() { return new Scope(sql,args.toArray()); }
    }
}
