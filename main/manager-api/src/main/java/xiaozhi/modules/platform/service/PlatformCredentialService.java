package xiaozhi.modules.platform.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.modules.billing.service.BillingSupport;
import xiaozhi.modules.platform.dto.PlatformRequests.*;
import static xiaozhi.modules.platform.service.PlatformSupport.*;

/** Immutable credential ownership. Rotation changes the pointer, never the historical owner. */
@Service @RequiredArgsConstructor
public class PlatformCredentialService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BillingSupport json;
    private final CredentialCipher cipher;
    public static final Set<String> PROVIDERS=Set.of("DEEPSEEK","VOLCENGINE");
    private static final Set<String> FIELDS=Set.of("api_key","llm_api_key","speech_api_key");

    public Map<String,Object> add(CredentialInput input) {
        CredentialInput normalized=normalize(input);
        String provider=normalized.provider();
        Map<String,String> secrets=normalized.secrets();
        String id=id();
        Map<String,String> masked=new TreeMap<>();secrets.forEach((key,value)->masked.put(key,"***"));
        return tx.execute(status->{
        jdbc.update("INSERT INTO ai_platform_credential(id,provider,upstream_id,fingerprint,secret_ciphertext,masked_json,status,created_at) VALUES(?,?,?,?,?,?,?,?)",
            id,provider,normalized.upstreamId(),hash(json.json(secrets)),cipher.encrypt("platform-credential:"+id,json.json(secrets)),json.json(masked),"INVENTORY",now());
        secrets.forEach((key,value)->{if(Set.of("api_key","llm_api_key","speech_api_key").contains(key))jdbc.update("INSERT INTO ai_platform_credential_fingerprint(provider,component,fingerprint,credential_id) VALUES(?,?,?,?)",provider,"api_key",hash(value),id);});
        return map("id",id,"provider",provider,"status","INVENTORY");
        });
    }
    /** Shared validation for single and atomic bulk import; never includes rejected values. */
    static CredentialInput normalize(CredentialInput input) {
        if(input==null||input.secrets()==null||input.secrets().isEmpty()||input.secrets().size()>12)
            throw new RenException("INVALID_CREDENTIAL_FIELDS");
        String provider=provider(input.provider());
        TreeMap<String,String> secrets=new TreeMap<>();
        input.secrets().forEach((key,value)->{
            if(key==null||!FIELDS.contains(key)||value==null||value.isBlank()||value.length()>4096)throw new RenException("INVALID_CREDENTIAL_FIELDS");
            secrets.put(key,value);
        });
        if("DEEPSEEK".equals(provider)&&!secrets.keySet().equals(Set.of("api_key")) || "VOLCENGINE".equals(provider)&&(secrets.containsKey("api_key")||!(secrets.containsKey("llm_api_key")||secrets.containsKey("speech_api_key"))))
            throw new RenException("CREDENTIAL_INCOMPLETE");
        return new CredentialInput(provider,required(input.upstreamId(),200),secrets);
    }
    public List<Map<String,Object>> list() {
        return jdbc.queryForList("SELECT id,provider,upstream_id AS upstreamId,user_id AS userId,version,status,masked_json AS maskedJson,created_at AS createdAt FROM ai_platform_credential ORDER BY created_at DESC LIMIT 500");
    }
    public void initialize(long userId) {
        for(String provider:PROVIDERS) {
            jdbc.update("INSERT INTO ai_platform_user_credential(user_id,provider) VALUES(?,?) ON DUPLICATE KEY UPDATE provider=provider",userId,provider);
            assign(userId,provider,false);
        }
    }
    public Map<String,Object> assign(Assignment input,boolean rotate) {
        return tx.execute(status->{
            String request=requestId(input.requestId()),fingerprint=hash(input.userId()+"|"+input.provider()+"|"+rotate);
            // Serialize operations by credential owner before checking the request id.
            jdbc.queryForMap("SELECT user_id FROM ai_platform_account WHERE user_id=? FOR UPDATE",input.userId());
            List<Map<String,Object>> previous=jdbc.queryForList("SELECT * FROM ai_platform_credential_operation WHERE request_id=?",request);
            if(!previous.isEmpty()){if(!fingerprint.equals(previous.getFirst().get("payload_hash")))throw new RenException("IDEMPOTENCY_CONFLICT");return json.object(previous.getFirst().get("result_json").toString());}
            Map<String,Object> result=assign(input.userId(),input.provider(),rotate);
            if("PENDING_CREDENTIAL".equals(result.get("status")))throw new RenException("PENDING_CREDENTIAL");
            jdbc.update("INSERT INTO ai_platform_credential_operation(request_id,payload_hash,result_json,created_at) VALUES(?,?,?,?)",request,fingerprint,json.json(result),now());return result;
        });
    }
    public Map<String,Object> assign(long userId,String provider,boolean rotate) {
        return tx.execute(status->{
            String vendor=provider(provider);
            if(userId > 0 && jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=?",Long.class,userId)==0)throw new RenException("USER_NOT_FOUND");
            if(userId < 0 && jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_service_account WHERE account_id=? AND status='ACTIVE'",Long.class,userId)==0)throw new RenException("PLATFORM_SERVICE_REQUIRED");
            jdbc.update("INSERT INTO ai_platform_user_credential(user_id,provider) VALUES(?,?) ON DUPLICATE KEY UPDATE provider=provider",userId,vendor);
            Map<String,Object> pointer=jdbc.queryForMap("SELECT * FROM ai_platform_user_credential WHERE user_id=? AND provider=? FOR UPDATE",userId,vendor);
            if(pointer.get("credential_id")!=null&&!rotate) return status(userId,vendor);
            List<Map<String,Object>> available=jdbc.queryForList("SELECT id FROM ai_platform_credential WHERE provider=? AND status='INVENTORY' AND user_id IS NULL ORDER BY created_at,id LIMIT 1 FOR UPDATE",vendor);
            if(available.isEmpty()) return map("provider",vendor,"status","PENDING_CREDENTIAL");
            String next=available.getFirst().get("id").toString();
            if(pointer.get("credential_id")!=null) jdbc.update("UPDATE ai_platform_credential SET status='ROTATED',version=version+1 WHERE id=?",pointer.get("credential_id"));
            jdbc.update("UPDATE ai_platform_credential SET user_id=?,status='ACTIVE' WHERE id=? AND user_id IS NULL",userId,next);
            jdbc.update("UPDATE ai_platform_user_credential SET credential_id=? WHERE user_id=? AND provider=?",next,userId,vendor);
            return status(userId,vendor);
        });
    }
    public Map<String,Object> status(long userId,String provider) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT c.id,c.provider,c.status,c.version FROM ai_platform_user_credential p JOIN ai_platform_credential c ON c.id=p.credential_id WHERE p.user_id=? AND p.provider=?",userId,provider);
        return rows.isEmpty()?map("provider",provider,"status","PENDING_CREDENTIAL"):rows.getFirst();
    }
    public List<Map<String,Object>> statuses(long userId) { return PROVIDERS.stream().sorted().map(p->status(userId,p)).toList(); }
    public Map<String,Object> active(long userId,String provider) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT c.id,c.version,c.status,c.provider FROM ai_platform_user_credential p JOIN ai_platform_credential c ON c.id=p.credential_id WHERE p.user_id=? AND p.provider=? AND c.user_id=? AND c.status='ACTIVE'",userId,provider,userId);
        if(rows.isEmpty())throw new RenException("PENDING_CREDENTIAL");return rows.getFirst();
    }
    public Map<String,Object> runtimeSecrets(String id,long version) {
        Map<String,Object> row=jdbc.queryForMap("SELECT secret_ciphertext,version,status FROM ai_platform_credential WHERE id=?",id);
        if(number(row.get("version"))!=version||!"ACTIVE".equals(row.get("status")))throw new RenException("CREDENTIAL_CHANGED");
        return json.object(cipher.decrypt("platform-credential:"+id,row.get("secret_ciphertext").toString()));
    }
    public Map<String,Object> runtimeSecrets(String id,long version,String service) {
        Map<String,Object> raw=runtimeSecrets(id,version);
        String provider=jdbc.queryForObject("SELECT provider FROM ai_platform_credential WHERE id=?",String.class,id);
        String field="DEEPSEEK".equals(provider)?"api_key":"LLM".equals(service)?"llm_api_key":"speech_api_key";
        Object key=raw.get(field);if(key==null)throw new RenException("CREDENTIAL_SERVICE_NOT_READY");return Map.of("api_key",key);
    }
    public void revoke(String id) {
        tx.executeWithoutResult(status->{
            Map<String,Object> row=jdbc.queryForMap("SELECT * FROM ai_platform_credential WHERE id=?",id);
            if(row.get("user_id")!=null)jdbc.queryForList("SELECT * FROM ai_platform_user_credential WHERE user_id=? AND provider=? FOR UPDATE",row.get("user_id"),row.get("provider"));
            Map<String,Object> locked=jdbc.queryForMap("SELECT status FROM ai_platform_credential WHERE id=? FOR UPDATE",id);
            if("REVOKED".equals(locked.get("status")))return;
            jdbc.update("UPDATE ai_platform_credential SET status='REVOKED',version=version+1 WHERE id=?",id);
            jdbc.update("UPDATE ai_platform_user_credential SET credential_id=NULL WHERE credential_id=?",id);
        });
    }
    public static String provider(String value) { String p=required(value,32).toUpperCase(Locale.ROOT);if(!PROVIDERS.contains(p))throw new RenException("UNSUPPORTED_PROVIDER");return p; }
}
