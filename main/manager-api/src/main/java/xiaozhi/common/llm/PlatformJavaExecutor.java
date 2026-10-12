package xiaozhi.common.llm;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.billing.dto.Usage;
import xiaozhi.modules.platform.dto.PlatformRequests.*;
import xiaozhi.modules.platform.service.*;
import cn.hutool.json.JSONUtil;

/** Replaces all caller headers/endpoints with the authoritative admitted route. */
@Component @RequiredArgsConstructor
public class PlatformJavaExecutor {
    private final PlatformAdmissionService admission;
    private final PlatformServiceAccountService services;
    private final PlatformSettlementOutbox outbox;
    public record Call(String endpoint,HttpEntity<?> request,Permit permit) {}
    public Call prepare(LlmUsageContext.Identity identity,HttpEntity<?> request,String modelId,String service) {
        if(identity==null)throw new RenException("EXECUTION_IDENTITY_REQUIRED");
        String model=modelId==null?identity.modelId():modelId;
        if(model==null||!"LLM".equals(service))throw new RenException("ADAPTER_ADMISSION_UNSUPPORTED");
        Map<String,Object> body=new LinkedHashMap<>();
        if(request.getBody() instanceof Map<?,?> map)map.forEach((k,v)->body.put(k.toString(),v));
        else if(request.getBody() instanceof String text)body.putAll(JSONUtil.parseObj(text));
        else throw new RenException("BOUNDED_USAGE_REQUIRED");
        String serialized=JSONUtil.toJsonStr(body);
        if(serialized.contains("\"image_url\"")||serialized.contains("\"input_audio\"")||Boolean.TRUE.equals(body.get("stream")))throw new RenException("ADAPTER_ADMISSION_UNSUPPORTED");
        Object raw=body.getOrDefault("max_completion_tokens",body.getOrDefault("max_tokens",1024));
        int max;try {max=Integer.parseInt(raw.toString());}catch(RuntimeException invalid){throw new RenException("BOUNDED_USAGE_REQUIRED");}
        if(max<1||max>32768)throw new RenException("BOUNDED_USAGE_REQUIRED");
        body.put("max_tokens",max);
        String purpose=switch(identity.purpose()){case "CONFIG_ASSISTANT"->"MODEL_ASSISTANT";case "BENCHMARK_CASE_GENERATION"->"BENCHMARK";case "KNOWLEDGE_CHUNKING"->identity.workspaceId()==null?"OFFICIAL_KNOWLEDGE":"KNOWLEDGE_CHUNKING";default->identity.purpose();};
        Map<String,Object> context=PlatformSupport.map("workspace_id",identity.workspaceId(),"agent_id",identity.agentId(),"device_id",identity.deviceId(),"actor_user_id",identity.actorUserId(),"purpose",purpose);
        if(identity.workspaceId()==null)context.put("platform_service_id",services.resolve(model,purpose));
        String attempt=UUID.randomUUID().toString();
        Map<String,Object> grant=admission.admitVerified(new Admit("java:"+attempt,"internal",model,service,purpose,attempt,attempt,
            Map.of("input_tokens",Integer.toString(serialized.getBytes(StandardCharsets.UTF_8).length+4096),"output_tokens",Integer.toString(max))),context);
        Permit permit=new Permit(grant.get("reservationId").toString(),grant.get("permitToken").toString());
        try {
            @SuppressWarnings("unchecked") Map<String,Object> runtime=(Map<String,Object>)grant.get("runtimeConfig");
            @SuppressWarnings("unchecked") Map<String,Object> secrets=(Map<String,Object>)grant.get("credentials");
            String base=runtime.get("base_url").toString().replaceAll("/+$","");
            String endpoint=base.endsWith("/chat/completions")?base:base+"/chat/completions";
            Object key=secrets.get("api_key");if(key==null)throw new RenException("CREDENTIAL_NOT_READY");
            body.put("model",runtime.get("model_name"));
            HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setBearerAuth(key.toString());
            // Persist UNKNOWN before dispatch: crashes retain the reservation and retry evidence.
            outbox.store(new Settle(permit.reservationId(),permit.permitToken(),"UNKNOWN",false,Map.of(),null));
            if(!Boolean.TRUE.equals(admission.start(permit).get("started")))throw new RenException("PLATFORM_START_DENIED");
            return new Call(endpoint,new HttpEntity<>(body,headers),permit);
        } catch(RuntimeException failure) {
            try {admission.release(permit);outbox.remove(permit.reservationId());}catch(RuntimeException startedOrUnavailable) { /* Durable hold remains. */ }
            throw failure;
        }
    }
    public void finish(Call call,boolean success,List<Usage> metrics,boolean complete,String requestId) {
        Map<String,String> quantities=new TreeMap<>();metrics.forEach(metric->{if("PROVIDER".equals(metric.getSource()))quantities.put(metric.getMetric(),metric.getQuantity().toPlainString());});
        outbox.settle(new Settle(call.permit().reservationId(),call.permit().permitToken(),success?"SUCCESS":"FAILED",complete,quantities,requestId));
    }
}
