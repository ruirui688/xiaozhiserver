package xiaozhi.modules.platform.service;

import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.modules.billing.service.BillingSupport;
import static xiaozhi.modules.platform.service.PlatformSupport.*;

/** A platform-owned upstream route. Credentials are immutable snapshots, never user properties. */
@Service @RequiredArgsConstructor
public class PlatformChannelService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BillingSupport json;
    private final CredentialCipher cipher;
    private final PlatformRuntimeService runtimes;

    private static final String READ_FIELDS="c.*,m.model_name,m.is_enabled AS model_enabled,m.test_status AS model_test_status,EXISTS (SELECT 1 FROM ai_platform_rate r WHERE r.model_id=c.model_id) AS rate_configured";

    public record Input(@NotBlank @Size(max=128) String requestId,@Size(max=64) String id,
            @Positive Long expectedVersion,@NotBlank @Size(max=100) String name,
            @NotBlank @Size(max=64) String modelId,@NotBlank @Size(max=200) String accountRef,
            @Size(max=100) String region,@Size(max=200) String projectRef,@NotNull Boolean enabled,
            @Min(1) @Max(200) Integer maxConcurrency,Map<String,String> secrets,@Size(max=64) String legacyCredentialId) {
        @Override public String toString(){return "ChannelInput[redacted]";}
    }
    public Map<String,Object> save(Input input) {
        String request=requestId(input.requestId());
        String name=required(input.name(),100),model=required(input.modelId(),64),account=required(input.accountRef(),200);
        if(input.enabled()==null)throw new RenException("INVALID_CHANNEL_INPUT");
        int concurrency=input.maxConcurrency()==null?20:input.maxConcurrency();
        if(concurrency<1||concurrency>200)throw new RenException("INVALID_CHANNEL_CONCURRENCY");
        String region=optional(input.region(),100),project=optional(input.projectRef(),200);
        String channelId=input.id()==null?null:required(input.id(),64);
        String legacy=input.legacyCredentialId()==null?null:required(input.legacyCredentialId(),64);
        Map<String,String> secret=validateSecret(input.secrets());
        if(secret!=null&&legacy!=null)throw new RenException("INVALID_CHANNEL_CREDENTIAL");
        // This fingerprint remains private and includes the submitted credential, never the result or audit log.
        String fingerprint=hash(json.json(map("id",channelId,"version",input.expectedVersion(),"name",name,"model",model,"account",account,
                "region",region,"project",project,"enabled",input.enabled(),"maxConcurrency",concurrency,"secret",secret,"legacy",legacy)));
        return tx.execute(status->{
            try { jdbc.update("INSERT INTO ai_platform_channel_operation(request_id,payload_hash,result_json,created_at) VALUES(?,?,?,?)",request,fingerprint,"{}",now()); }
            catch(DuplicateKeyException duplicate) {
                Map<String,Object> previous=jdbc.queryForMap("SELECT payload_hash,result_json FROM ai_platform_channel_operation WHERE request_id=? FOR UPDATE",request);
                if(!fingerprint.equals(previous.get("payload_hash")))throw new RenException("IDEMPOTENCY_CONFLICT");
                return json.object(previous.get("result_json").toString());
            }
            String service=modelService(model);
            PlatformRuntimeService.Runtime runtime=runtimes.approved(model,service,true);
            String id=channelId==null?id():channelId;
            Map<String,Object> old=null;
            long version=1;
            if(channelId!=null) {
                List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM ai_platform_channel WHERE id=? FOR UPDATE",channelId);
                if(rows.isEmpty())throw new RenException("CHANNEL_NOT_FOUND");old=rows.getFirst();
                if(input.expectedVersion()==null||number(old.get("version"))!=input.expectedVersion())throw new RenException("CHANNEL_VERSION_CHANGED");
                if(!model.equals(old.get("model_id"))||!runtime.provider().equals(old.get("provider"))||!service.equals(old.get("service_type")))throw new RenException("CHANNEL_MODEL_IMMUTABLE");
                version=number(old.get("version"))+1;
            }
            Map<String,String> effective=secret!=null?secret:legacy!=null?legacySecret(legacy,runtime.provider(),service):null;
            if(old!=null&&!account.equals(old.get("account_ref"))&&effective==null)throw new RenException("CHANNEL_ACCOUNT_REQUIRES_CREDENTIAL");
            if(old==null&&effective==null)throw new RenException("CHANNEL_CREDENTIAL_REQUIRED");
            String credential=effective==null?old.get("credential_id").toString():id();
            if(effective!=null)jdbc.update("INSERT INTO ai_platform_channel_credential(id,channel_id,version,secret_ciphertext,created_at) VALUES(?,?,?,?,?)",
                    credential,id,version,cipher.encrypt("platform-channel-credential:"+credential,json.json(effective)),now());
            try {
                if(old==null)jdbc.update("INSERT INTO ai_platform_channel(id,name,model_id,provider,service_type,account_ref,region,project_ref,status,version,credential_id,max_concurrency,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        id,name,model,runtime.provider(),service,account,region,project,input.enabled()?"ACTIVE":"DISABLED",version,credential,concurrency,now(),now());
                else jdbc.update("UPDATE ai_platform_channel SET name=?,account_ref=?,region=?,project_ref=?,status=?,version=?,credential_id=?,max_concurrency=?,updated_at=? WHERE id=?",
                        name,account,region,project,input.enabled()?"ACTIVE":"DISABLED",version,credential,concurrency,now(),id);
            }catch(DuplicateKeyException duplicate){throw new RenException("CHANNEL_MODEL_EXISTS");}
            Map<String,Object> result=metadata(jdbc.queryForMap("SELECT "+READ_FIELDS+" FROM ai_platform_channel c LEFT JOIN ai_model_config m ON m.id=c.model_id WHERE c.id=?",id));
            jdbc.update("UPDATE ai_platform_channel_operation SET result_json=? WHERE request_id=?",json.json(result),request);
            return result;
        });
    }
    public Map<String,Object> list(int page,int limit,String provider,String serviceType,String keyword) {
        if(page<1||page>100000||limit<1||limit>200)throw new RenException("INVALID_PAGINATION");
        List<Object> args=new ArrayList<>();String where=" WHERE 1=1";
        if(provider!=null&&!provider.isBlank()){where+=" AND c.provider=?";args.add(PlatformCredentialService.provider(provider));}
        if(serviceType!=null&&!serviceType.isBlank()){if(!Set.of("LLM","ASR","TTS").contains(serviceType))throw new RenException("INVALID_CHANNEL_SERVICE");where+=" AND c.service_type=?";args.add(serviceType);}
        if(keyword!=null&&!keyword.isBlank()) {
            String value=required(keyword,100).replace("!","!!").replace("%","!%").replace("_","!_");
            where+=" AND (c.name LIKE ? ESCAPE '!' OR m.model_name LIKE ? ESCAPE '!' OR c.account_ref LIKE ? ESCAPE '!')";
            for(int i=0;i<3;i++)args.add("%"+value+"%");
        }
        String from=" FROM ai_platform_channel c LEFT JOIN ai_model_config m ON m.id=c.model_id";
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from+where,Long.class,args.toArray());
        args.add(limit);args.add((page-1L)*limit);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT "+READ_FIELDS+from+where+" ORDER BY c.created_at DESC,c.id LIMIT ? OFFSET ?",args.toArray());
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:rows){Map<String,Object> item=metadata(row);
            List<Map<String,Object>> recent=jdbc.queryForList("SELECT state,created_at FROM ai_platform_reservation WHERE channel_id=? ORDER BY created_at DESC,id DESC LIMIT 1",row.get("id"));
            item.put("lastStatus",recent.isEmpty()?null:recent.getFirst().get("state"));item.put("lastCalledAt",recent.isEmpty()?null:recent.getFirst().get("created_at"));result.add(item);}
        return map("list",result,"total",total,"page",page,"limit",limit);
    }
    public List<Map<String,Object>> models() {
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT id,model_type FROM ai_model_config WHERE model_type IN ('LLM','SLM','VLLM','INTENT','MEMORY','ASR','TTS') ORDER BY id")) {
            try {var runtime=runtimes.approved(row.get("id").toString(),BillingSupport.service(row.get("model_type").toString()),true);var model=runtime.model();
                result.add(map("id",model.getId(),"name",model.getModelName(),"provider",runtime.provider(),"serviceType",BillingSupport.service(model.getModelType()),
                        "published",Integer.valueOf(1).equals(model.getIsEnabled())&&Integer.valueOf(1).equals(model.getTestStatus())));
            }catch(RenException unsupported){ /* Only models supported by trusted admission can be configured. */ }
        }return result;
    }
    /** Must run inside the admission transaction; locking the channel enforces its shared concurrency bound. */
    public Map<String,Object> select(String model,String provider,String service) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM ai_platform_channel WHERE model_id=? FOR UPDATE",model);
        if(rows.isEmpty()||!"ACTIVE".equals(rows.getFirst().get("status")))throw new RenException("MODEL_CHANNEL_UNAVAILABLE");
        Map<String,Object> row=rows.getFirst();
        if(!provider.equals(row.get("provider"))||!service.equals(row.get("service_type")))throw new RenException("MODEL_CHANNEL_MISMATCH");
        // Locking read observes the latest committed attempts even under MySQL REPEATABLE READ.
        long active=jdbc.queryForList("SELECT id FROM ai_platform_reservation WHERE channel_id=? AND ((state='RESERVED' AND expires_at>?) OR (state IN ('STARTED','UNKNOWN') AND hard_expires_at>?)) FOR UPDATE",row.get("id"),now(),now()).size();
        if(active>=number(row.get("max_concurrency")))throw new RenException("CHANNEL_CONCURRENCY_LIMIT");
        Map<String,Object> credential=jdbc.queryForMap("SELECT id,version FROM ai_platform_channel_credential WHERE id=? AND channel_id=?",row.get("credential_id"),row.get("id"));
        return map("channelId",row.get("id"),"channelVersion",row.get("version"),"provider",provider,"accountRef",row.get("account_ref"),
                "region",row.get("region"),"projectRef",row.get("project_ref"),"credentialId",credential.get("id"),"credentialVersion",credential.get("version"));
    }
    public void lockModel(String model) { jdbc.queryForList("SELECT id FROM ai_platform_channel WHERE model_id=? FOR UPDATE",model); }
    public void lock(String channel) { jdbc.queryForMap("SELECT id FROM ai_platform_channel WHERE id=? FOR UPDATE",channel); }
    public Map<String,Object> runtimeSecrets(String channel,String credential,long version,boolean requireEnabled) {
        if(requireEnabled) {
            List<String> states=jdbc.queryForList("SELECT status FROM ai_platform_channel WHERE id=? FOR UPDATE",String.class,channel);
            if(states.isEmpty()||!"ACTIVE".equals(states.getFirst()))throw new RenException("MODEL_CHANNEL_UNAVAILABLE");
        }
        List<String> rows=jdbc.queryForList("SELECT secret_ciphertext FROM ai_platform_channel_credential WHERE id=? AND channel_id=? AND version=?",String.class,credential,channel,version);
        if(rows.isEmpty())throw new RenException("CHANNEL_CREDENTIAL_CHANGED");
        return json.object(cipher.decrypt("platform-channel-credential:"+credential,rows.getFirst()));
    }
    /** User-safe availability, without channel names, supplier accounts or credential identifiers. */
    public List<Map<String,Object>> availability() {
        List<Map<String,Object>> result=new ArrayList<>();
        for(String service:List.of("LLM","ASR","TTS")) {
            long count=jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_channel c JOIN ai_model_config m ON m.id=c.model_id JOIN ai_platform_channel_credential k ON k.id=c.credential_id AND k.channel_id=c.id WHERE c.service_type=? AND c.status='ACTIVE' AND m.is_enabled=1 AND m.test_status=1 AND EXISTS (SELECT 1 FROM ai_platform_rate r WHERE r.model_id=c.model_id)",Long.class,service);
            result.add(map("serviceType",service,"status",count>0?"READY":"UNAVAILABLE"));
        }return result;
    }
    private Map<String,String> legacySecret(String id,String provider,String service) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT provider,status,secret_ciphertext FROM ai_platform_credential WHERE id=?",id);
        if(rows.isEmpty()||!provider.equals(rows.getFirst().get("provider"))||!Set.of("ACTIVE","INVENTORY").contains(rows.getFirst().get("status")))throw new RenException("INVALID_CHANNEL_CREDENTIAL");
        Map<String,Object> raw=json.object(cipher.decrypt("platform-credential:"+id,rows.getFirst().get("secret_ciphertext").toString()));
        String field="DEEPSEEK".equals(provider)?"api_key":"LLM".equals(service)?"llm_api_key":"speech_api_key";
        Object secret=raw.get(field);if(!(secret instanceof String))throw new RenException("CREDENTIAL_SERVICE_NOT_READY");
        return validateSecret(Map.of("api_key",secret.toString()));
    }
    private String modelService(String model){List<String> rows=jdbc.queryForList("SELECT model_type FROM ai_model_config WHERE id=?",String.class,model);if(rows.isEmpty())throw new RenException("MODEL_NOT_FOUND");return BillingSupport.service(rows.getFirst());}
    private static Map<String,String> validateSecret(Map<String,String> input){if(input==null||input.isEmpty())return null;if(!input.keySet().equals(Set.of("api_key")))throw new RenException("INVALID_CHANNEL_CREDENTIAL");String key=input.get("api_key");if(key==null||key.isBlank()||key.length()>4096)throw new RenException("INVALID_CHANNEL_CREDENTIAL");return Map.of("api_key",key);}
    private static String optional(String value,int max){return value==null||value.isBlank()?null:required(value,max);}
    private static boolean enabledValue(Object value){return Boolean.TRUE.equals(value)||"1".equals(String.valueOf(value));}
    private static Map<String,Object> metadata(Map<String,Object> row){return map("id",row.get("id"),"name",row.get("name"),"modelId",row.get("model_id"),"modelName",row.get("model_name"),"provider",row.get("provider"),"serviceType",row.get("service_type"),"accountRef",row.get("account_ref"),"region",row.get("region"),"projectRef",row.get("project_ref"),"status",row.get("status"),"enabled","ACTIVE".equals(row.get("status")),"version",row.get("version").toString(),"credentialConfigured",row.get("credential_id")!=null,"modelPublished",enabledValue(row.get("model_enabled"))&&enabledValue(row.get("model_test_status")),"rateConfigured",enabledValue(row.get("rate_configured")),"maxConcurrency",row.get("max_concurrency"),"updatedAt",row.get("updated_at"));}
}
