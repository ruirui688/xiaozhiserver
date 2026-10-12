package xiaozhi.modules.billing.service;

import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.billing.dto.BillingCollectorHealthRequest;

/** Heartbeats are operational observations, never proof that usage reached the ledger. */
@Service @RequiredArgsConstructor
public class BillingCollectionHealthService {
    private final JdbcTemplate jdbc;
    private final BillingSupport support;
    private final BillingQueryService queries;
    private final BillingInternalOutbox outbox;

    public Map<String,Object> accept(BillingCollectorHealthRequest request) {
        Instant now=Instant.now();
        for(Instant value:new Instant[]{request.getStartedAt(),request.getLastCallStartedAt(),request.getLastDeliveryAt(),request.getLastContextAt()})
            if(value!=null && value.isAfter(now.plusSeconds(300)))throw new RenException("采集状态时间无效");
        // No client-supplied workspace or context ownership is accepted or stored.
        jdbc.update("INSERT INTO ai_billing_collector_health (instance_id,started_at,received_at,health_json) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE started_at=VALUES(started_at),received_at=VALUES(received_at),health_json=VALUES(health_json)",
                request.getInstanceId(),BillingSupport.time(request.getStartedAt()),BillingSupport.time(now),support.json(request));
        return Map.of("accepted",true,"receivedAt",now);
    }

    public Map<String,Object> overview(boolean accountView) {
        String workspace=null;
        if(accountView)BillingQueryService.requireAdmin();else workspace=queries.workspace();
        String where=accountView?"":" WHERE workspace_id=?";Object[] args=accountView?new Object[]{}:new Object[]{workspace};
        var contexts=jdbc.queryForMap("SELECT COUNT(*) count,MIN(issued_at) firstContextAt,MAX(issued_at) lastContextAt FROM ai_billing_context"+where,args);
        var events=jdbc.queryForMap("SELECT COUNT(*) count,COALESCE(SUM(CASE WHEN context_id IS NOT NULL THEN 1 ELSE 0 END),0) pythonEventCount,MIN(received_at) firstEventAt,MAX(received_at) lastEventAt FROM ai_billing_usage_event"+where,args);
        Map<String,Object> python=new LinkedHashMap<>();List<Map<String,Object>> instances=new ArrayList<>();
        String heartbeat="WORKSPACE_EVIDENCE_ONLY";boolean pending=false,incomplete=false;
        if(accountView) {
            var rows=jdbc.queryForList("SELECT received_at,health_json FROM ai_billing_collector_health ORDER BY received_at DESC LIMIT 1000");
            heartbeat=rows.isEmpty()?"NEVER_SEEN":"STALE";
            for(var row:rows) {
                var item=new LinkedHashMap<>(support.object(row.get("health_json").toString()));
                var received=BillingSupport.databaseTime(row.get("received_at"));
                boolean alive=received.isAfter(BillingSupport.time(Instant.now().minusSeconds(120)));
                item.put("receivedAt",row.get("received_at"));item.put("heartbeatStatus",alive?"ALIVE":"STALE");instances.add(item);
                if(alive){heartbeat="ALIVE";
                    boolean queue=Boolean.TRUE.equals(item.get("queueAvailable"));
                    pending|=queue && number(item,"pendingCount")>0;
                    incomplete|=!queue || number(item,"rejectedCount")>0 || number(item,"persistenceFailureCount")>0 || number(item,"missingContextCalls")>0 || number(item,"missingContextCount")>0;
                }
            }
        }
        python.put("status",heartbeat);python.put("instances",instances);
        Map<String,Object> javaQueue=accountView?new LinkedHashMap<>(outbox.status()):null;
        if(javaQueue!=null){javaQueue.put("scope","JAVA_OUTBOX_ONLY");pending|="PENDING".equals(javaQueue.get("status"));incomplete|=Set.of("INCOMPLETE","UNAVAILABLE").contains(javaQueue.get("status"));}
        String status=incomplete?"INCOMPLETE":pending?"PENDING":((Number)events.get("count")).longValue()>0?"COLLECTED_RECORDS_ONLY":"NO_USAGE_EVIDENCE";
        Map<String,Object> result=new LinkedHashMap<>();result.put("scope",accountView?"PLATFORM":"WORKSPACE");result.put("workspaceId",workspace);
        result.put("status",status);result.put("python",python);result.put("javaQueue",javaQueue);result.put("contexts",contexts);result.put("events",events);
        result.put("coverage",Map.of("complete",false,"reasonCodes",List.of("ADAPTER_COVERAGE_NOT_UNIVERSAL","FIRST_EVENT_DOES_NOT_PROVE_PRIOR_COVERAGE")));
        return result;
    }
    private long number(Map<String,Object> value,String key){return value.get(key) instanceof Number number?number.longValue():0;}
}
