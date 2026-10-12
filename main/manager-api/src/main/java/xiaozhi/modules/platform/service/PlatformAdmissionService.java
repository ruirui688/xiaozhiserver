package xiaozhi.modules.platform.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.modules.billing.service.*;
import xiaozhi.modules.platform.dto.PlatformRequests.*;
import static xiaozhi.modules.platform.service.PlatformSupport.*;

/** One durable permit per upstream attempt. Unknown outcome always retains the hold. */
@Service @RequiredArgsConstructor
public class PlatformAdmissionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BillingSupport json;
    private final BillingContextService contexts;
    private final PlatformAccountService accounts;
    private final PlatformCredentialService credentials;
    private final PlatformResponsibilityService responsibility;
    private final PlatformRateService rates;
    private final PlatformRuntimeService runtimes;
    private final PlatformServiceAccountService services;
    private final CredentialCipher cipher;
    private static final SecureRandom RANDOM=new SecureRandom();

    public Map<String,Object> admit(Admit request) {
        Map<String,Object> context=contexts.verify(request.contextId(),request.contextToken(),request.modelId(),Instant.now());
        return admitVerified(request,context);
    }
    public Map<String,Object> admitVerified(Admit request,Map<String,Object> context) {
        String purpose=required(request.purpose(),64),contextPurpose=String.valueOf(context.get("purpose"));
        if(!purpose.equals(contextPurpose)&&!("CONVERSATION".equals(contextPurpose)&&Set.of("INTENT","MEMORY","KNOWLEDGE","RAG","CONVERSATION","EXTERNAL_TTS").contains(purpose)))throw new RenException("PURPOSE_OUTSIDE_CONTEXT");
        if(context.get("workspace_id")==null)services.validate(String.valueOf(context.get("platform_service_id")),request.modelId(),purpose);
        PlatformRuntimeService.Runtime runtime=runtimes.approved(request.modelId(),request.serviceType(),context.get("workspace_id")==null&&"MODEL_TEST".equals(purpose));
        Map<String,Object> requested=PlatformRateService.normalize(request.maxUsage());
        String attempt=requestId(request.attemptId());requestId(request.logicalCallId());
        String requestHash=hash(json.json(map("contextId",request.contextId(),"modelId",request.modelId(),"service",request.serviceType(),"purpose",purpose,"logicalCallId",request.logicalCallId(),"maxUsage",requested)));
        return tx.execute(status->{
            String workspace=(String)context.get("workspace_id");
            Map<String,Object> route=route(context,workspace);
            long payer=number(route.get("payerUserId"));
            Map<String,Object> account=accounts.lock(payer);
            List<Map<String,Object>> previous=jdbc.queryForList("SELECT * FROM ai_platform_reservation WHERE attempt_id=? FOR UPDATE",attempt);
            if(!previous.isEmpty()) {
                Map<String,Object> old=previous.getFirst();if(!requestHash.equals(old.get("request_hash")))throw new RenException("IDEMPOTENCY_CONFLICT");
                if(!"RESERVED".equals(old.get("state")))throw new RenException("ATTEMPT_ALREADY_DISPATCHED");
                if(expired(old,"expires_at"))throw new RenException("PERMIT_EXPIRED");
                return grant(old);
            }
            if(number(account.get("frozen"))!=0)throw new RenException("PAYER_FROZEN");
            Map<String,Object> credential=credentials.active(payer,runtime.provider());
            Map<String,Object> rate=rates.latest(request.modelId());
            Map<String,Object> published=json.object(rate.get("max_usage_json").toString()), metricRates=json.object(rate.get("metric_rates_json").toString());
            PlatformRateService.bounded(requested,published);BigDecimal hold=PlatformRateService.cost(metricRates,requested);
            if(PlatformAccountService.available(account).compareTo(hold)<0)throw new RenException("ALLOWANCE_EXHAUSTED");
            String period=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
            workspaceReserve(workspace,period,hold);
            String id=id(),permit=token();LocalDateTime created=now();
            jdbc.update("UPDATE ai_platform_account SET reserved=reserved+?,updated_at=? WHERE user_id=?",hold,created,payer);
            jdbc.update("INSERT INTO ai_platform_reservation(id,attempt_id,logical_call_id,request_hash,context_id,model_id,service_type,payer_user_id,workspace_id,usage_period,route_json,runtime_json,credential_id,credential_version,responsibility_version,rate_version,metric_rates_json,published_max_json,max_usage_json,reserved_amount,state,permit_hash,permit_ciphertext,expires_at,hard_expires_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,attempt,request.logicalCallId(),requestHash,request.contextId(),request.modelId(),request.serviceType(),payer,workspace,period,json.json(route),json.json(runtime.config()),credential.get("id"),credential.get("version"),route.get("responsibilityVersion"),rate.get("version"),json.json(metricRates),json.json(published),json.json(requested),hold,"RESERVED",hash(permit),cipher.encrypt("platform-permit:"+id,permit),created.plusSeconds(60),created.plusMinutes(5),created);
            accounts.entry("reserve:"+id,requestHash,payer,"RESERVE",hold,PlatformAccountService.available(account).subtract(hold),workspace,id,null,purpose);
            return grant(jdbc.queryForMap("SELECT * FROM ai_platform_reservation WHERE id=?",id));
        });
    }
    private Map<String,Object> route(Map<String,Object> context,String workspace) {
        if(workspace==null) {
            String service=String.valueOf(context.get("platform_service_id"));
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM ai_platform_service_account WHERE id=? AND status='ACTIVE'",service);
            if(rows.isEmpty())throw new RenException("PLATFORM_SERVICE_BUDGET_REQUIRED");
            long payer=number(rows.getFirst().get("account_id"));
            return map("actorType","PLATFORM_SERVICE","actorUserId",null,"platformServiceId",service,"credentialOwnerUserId",payer,"payerUserId",payer,"responsibilityVersion",1L);
        }
        Map<String,Object> owner=responsibility.lock(workspace);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_workspace WHERE id=? AND status=1",Long.class,workspace)==0)throw new RenException("WORKSPACE_DISABLED");
        Object agent=context.get("agent_id"),device=context.get("device_id"),actor=context.get("actor_user_id");
        if(agent!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent WHERE id=? AND workspace_id=?",Long.class,agent,workspace)==0)throw new RenException("CONTEXT_RELATION_CHANGED");
        if(device!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM ai_device WHERE id=? AND agent_id=? AND workspace_id=?",Long.class,device,agent,workspace)==0)throw new RenException("CONTEXT_RELATION_CHANGED");
        if(actor!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM sys_workspace_member WHERE workspace_id=? AND user_id=? AND status=1",Long.class,workspace,actor)==0)throw new RenException("CONTEXT_MEMBERSHIP_CHANGED");
        long payer=number(owner.get("responsible_user_id"));
        if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1",Long.class,payer)==0)throw new RenException("PAYER_DISABLED");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_workspace_member WHERE workspace_id=? AND user_id=? AND status=1",Long.class,workspace,payer)==0)throw new RenException("PAYER_MEMBERSHIP_CHANGED");
        if(actor!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND status=1",Long.class,actor)==0)throw new RenException("ACTOR_DISABLED");
        return map("workspaceId",workspace,"actorType",device!=null?"DEVICE":actor!=null?"USER":"AGENT_TASK","actorUserId",actor,
            "agentId",agent,"deviceId",device,"credentialOwnerUserId",payer,"payerUserId",payer,"responsibilityVersion",owner.get("version"));
    }
    public Map<String,Object> start(Permit request) {
        return tx.execute(status->{
            Map<String,Object> row=locked(request);if(!"RESERVED".equals(row.get("state")))throw new RenException("ATTEMPT_ALREADY_DISPATCHED");
            if(expired(row,"expires_at")) { finishRelease(row,"EXPIRED");return map("started",false,"reason","PERMIT_EXPIRED"); }
            if(!currentRoute(row)||number(accounts.lock(number(row.get("payer_user_id"))).get("frozen"))!=0) {finishRelease(row,"CANCELLED");return map("started",false,"reason","ROUTE_CHANGED");}
            try { credentials.runtimeSecrets(row.get("credential_id").toString(),number(row.get("credential_version")),row.get("service_type").toString()); }
            catch(RenException changed) {finishRelease(row,"CANCELLED");return map("started",false,"reason","CREDENTIAL_CHANGED");}
            jdbc.update("UPDATE ai_platform_reservation SET state='STARTED',started_at=? WHERE id=?",now(),row.get("id"));return map("started",true);
        });
    }
    public Map<String,Object> release(Permit request) {
        return tx.execute(status->{Map<String,Object> row=locked(request);String state=row.get("state").toString();
            if(Set.of("RELEASED","EXPIRED","CANCELLED").contains(state))return map("released",true);
            if(!"RESERVED".equals(state))throw new RenException("STARTED_RESERVATION_CANNOT_RELEASE");finishRelease(row,"RELEASED");return map("released",true);});
    }
    public Map<String,Object> extend(Extend request) {
        return tx.execute(status->{Map<String,Object> row=locked(new Permit(request.reservationId(),request.permitToken()));
            if(!"STARTED".equals(row.get("state"))||expired(row,"hard_expires_at")||!currentRoute(row))throw new RenException("PERMIT_NOT_EXTENDABLE");
            credentials.runtimeSecrets(row.get("credential_id").toString(),number(row.get("credential_version")),row.get("service_type").toString());
            Map<String,Object> max=PlatformRateService.normalize(request.maxUsage()),old=json.object(row.get("max_usage_json").toString());
            PlatformRateService.bounded(max,json.object(row.get("published_max_json").toString()));
            old.forEach((key,value)->{if(nonnegative(max.get(key)).compareTo(nonnegative(value))<0)throw new RenException("LIMIT_CANNOT_SHRINK");});
            BigDecimal hold=PlatformRateService.cost(json.object(row.get("metric_rates_json").toString()),max),extra=hold.subtract(amount(row.get("reserved_amount")));
            Map<String,Object> account=accounts.lock(number(row.get("payer_user_id")));
            if(number(account.get("frozen"))!=0||PlatformAccountService.available(account).compareTo(extra)<0)throw new RenException("ALLOWANCE_EXHAUSTED");
            workspaceReserve((String)row.get("workspace_id"),row.get("usage_period").toString(),extra);
            jdbc.update("UPDATE ai_platform_account SET reserved=reserved+? WHERE user_id=?",extra,row.get("payer_user_id"));
            jdbc.update("UPDATE ai_platform_reservation SET reserved_amount=?,max_usage_json=? WHERE id=?",hold,json.json(max),row.get("id"));
            return map("limits",max,"hardExpiresAt",iso(row.get("hard_expires_at")));});
    }
    public Map<String,Object> settle(Settle request) {
        return tx.execute(status->{
            Map<String,Object> row=locked(new Permit(request.reservationId(),request.permitToken()));String state=row.get("state").toString();
            String fingerprint=hash(json.json(map("status",request.status(),"complete",request.usageComplete(),"usage",new TreeMap<>(request.usage()),"providerRequestId",request.providerRequestId())));
            if("SETTLED".equals(state)) {if(!fingerprint.equals(row.get("settlement_hash")))throw new RenException("SETTLEMENT_CONFLICT");return map("state",state,"charged",decimal(row.get("settled_amount")));}
            if(Set.of("RELEASED","EXPIRED","CANCELLED").contains(state)&&request.usage().isEmpty())return map("state",state,"charged","0");
            if(!Set.of("STARTED","UNKNOWN").contains(state))throw new RenException("RESERVATION_NOT_STARTED");
            Map<String,Object> prices=json.object(row.get("metric_rates_json").toString());
            if(!request.usageComplete()||"UNKNOWN".equals(request.status())||!request.usage().keySet().containsAll(prices.keySet())) {
                jdbc.update("UPDATE ai_platform_reservation SET state='UNKNOWN',provider_request_id=? WHERE id=?",request.providerRequestId(),row.get("id"));return map("state","UNKNOWN","reserved",decimal(row.get("reserved_amount")));}
            Map<String,Object> used=new TreeMap<>();prices.keySet().forEach(key->used.put(key,request.usage().get(key)));
            try {PlatformRateService.bounded(used,json.object(row.get("max_usage_json").toString()));}
            catch(RenException invalid) {jdbc.update("UPDATE ai_platform_reservation SET state='UNKNOWN' WHERE id=?",row.get("id"));return map("state","UNKNOWN","reason","USAGE_LIMIT_EXCEEDED");}
            BigDecimal cost=PlatformRateService.cost(prices,used),reserved=amount(row.get("reserved_amount"));
            Map<String,Object> account=accounts.lock(number(row.get("payer_user_id")));
            jdbc.update("UPDATE ai_platform_account SET reserved=reserved-?,spent=spent+?,updated_at=? WHERE user_id=?",reserved,cost,now(),row.get("payer_user_id"));
            workspaceFinish(row,reserved,cost);
            jdbc.update("UPDATE ai_platform_reservation SET state='SETTLED',settled_amount=?,settlement_hash=?,provider_request_id=?,settled_at=? WHERE id=?",cost,fingerprint,request.providerRequestId(),now(),row.get("id"));
            accounts.entry("settle:"+row.get("id"),fingerprint,number(row.get("payer_user_id")),"SETTLE",cost,PlatformAccountService.available(account).add(reserved).subtract(cost),(String)row.get("workspace_id"),row.get("id").toString(),null,request.status());
            return map("state","SETTLED","charged",cost.toPlainString());
        });
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${xiaozhi.platform.expiry-scan-ms:30000}")
    public void expireReservations() {
        for(Map<String,Object> preview:jdbc.queryForList("SELECT id,permit_ciphertext FROM ai_platform_reservation WHERE (state='RESERVED' AND expires_at<?) OR (state='STARTED' AND hard_expires_at<?) LIMIT 100",now(),now())) {
            try {tx.executeWithoutResult(status->{
                String id=preview.get("id").toString();Map<String,Object> row=locked(new Permit(id,cipher.decrypt("platform-permit:"+id,preview.get("permit_ciphertext").toString())));
                if("RESERVED".equals(row.get("state"))&&expired(row,"expires_at"))finishRelease(row,"EXPIRED");
                else if("STARTED".equals(row.get("state"))&&expired(row,"hard_expires_at"))jdbc.update("UPDATE ai_platform_reservation SET state='UNKNOWN' WHERE id=?",id);
            });}catch(org.springframework.dao.ConcurrencyFailureException contention){ /* Retry during the next bounded scan. */ }
        }
    }
    private Map<String,Object> locked(Permit request) {
        Map<String,Object> preview=jdbc.queryForMap("SELECT * FROM ai_platform_reservation WHERE id=?",request.reservationId());
        if(!MessageDigest.isEqual(hash(request.permitToken()).getBytes(StandardCharsets.US_ASCII),preview.get("permit_hash").toString().getBytes(StandardCharsets.US_ASCII)))throw new RenException("INVALID_PERMIT");
        // Uniform order: responsibility -> payer -> reservation -> team counters.
        if(preview.get("workspace_id")!=null)responsibility.lock(preview.get("workspace_id").toString());
        accounts.lock(number(preview.get("payer_user_id")));
        return jdbc.queryForMap("SELECT * FROM ai_platform_reservation WHERE id=? FOR UPDATE",request.reservationId());
    }
    private boolean currentRoute(Map<String,Object> row) {
        Map<String,Object> frozen=json.object(row.get("route_json").toString());
        Map<String,Object> context=map("agent_id",frozen.get("agentId"),"device_id",frozen.get("deviceId"),"actor_user_id",frozen.get("actorUserId"),"platform_service_id",frozen.get("platformServiceId"));
        try {
            Map<String,Object> current=route(context,(String)row.get("workspace_id"));
            return number(current.get("responsibilityVersion"))==number(row.get("responsibility_version"))&&number(current.get("payerUserId"))==number(row.get("payer_user_id"));
        }catch(RenException unavailable){return false;}
    }
    private void finishRelease(Map<String,Object> row,String state) {
        BigDecimal hold=amount(row.get("reserved_amount"));Map<String,Object> account=accounts.lock(number(row.get("payer_user_id")));
        jdbc.update("UPDATE ai_platform_account SET reserved=reserved-?,updated_at=? WHERE user_id=?",hold,now(),row.get("payer_user_id"));workspaceFinish(row,hold,BigDecimal.ZERO);
        jdbc.update("UPDATE ai_platform_reservation SET state=?,settled_at=? WHERE id=?",state,now(),row.get("id"));
        accounts.entry("release:"+row.get("id"),hash(state),number(row.get("payer_user_id")),"RELEASE",hold,PlatformAccountService.available(account).add(hold),(String)row.get("workspace_id"),row.get("id").toString(),null,state);
    }
    private void workspaceReserve(String workspace,String period,BigDecimal amount) {
        if(workspace==null)return;Map<String,Object> owner=responsibility.lock(workspace);
        for(String bucket:List.of("TOTAL",period)) {
            jdbc.update("INSERT INTO ai_platform_workspace_usage(workspace_id,period) VALUES(?,?) ON DUPLICATE KEY UPDATE period=period",workspace,bucket);
            Map<String,Object> usage=jdbc.queryForMap("SELECT * FROM ai_platform_workspace_usage WHERE workspace_id=? AND period=? FOR UPDATE",workspace,bucket);
            Object limit=owner.get("TOTAL".equals(bucket)?"total_limit":"daily_limit");
            if(limit!=null&&amount(usage.get("spent")).add(amount(usage.get("reserved"))).add(amount).compareTo(amount(limit))>0)throw new RenException("WORKSPACE_ALLOWANCE_LIMIT");
            jdbc.update("UPDATE ai_platform_workspace_usage SET reserved=reserved+? WHERE workspace_id=? AND period=?",amount,workspace,bucket);
        }
    }
    private void workspaceFinish(Map<String,Object> row,BigDecimal released,BigDecimal cost) {
        if(row.get("workspace_id")==null)return;
        jdbc.update("UPDATE ai_platform_workspace_usage SET reserved=reserved-?,spent=spent+? WHERE workspace_id=? AND period IN ('TOTAL',?)",released,cost,row.get("workspace_id"),row.get("usage_period"));
    }
    private Map<String,Object> grant(Map<String,Object> row) {
        String id=row.get("id").toString();return map("reservationId",id,"permitToken",cipher.decrypt("platform-permit:"+id,row.get("permit_ciphertext").toString()),
            "expiresAt",iso(row.get("expires_at")),"hardExpiresAt",iso(row.get("hard_expires_at")),"route",json.object(row.get("route_json").toString()),"rateVersion",row.get("rate_version"),
            "reservedAmount",decimal(row.get("reserved_amount")),"limits",json.object(row.get("max_usage_json").toString()),"runtimeConfig",json.object(row.get("runtime_json").toString()),
            "credentials",credentials.runtimeSecrets(row.get("credential_id").toString(),number(row.get("credential_version")),row.get("service_type").toString()));
    }
    private static boolean expired(Map<String,Object> row,String field) {return !BillingSupport.databaseTime(row.get(field)).isAfter(now());}
    private static String iso(Object value) {return BillingSupport.databaseTime(value).toInstant(ZoneOffset.UTC).toString();}
    private static String token() {byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
}
