package xiaozhi.modules.platform.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.modules.billing.service.BillingSupport;

/** Retains legacy secrets only as encrypted archives; no archive is an execution fallback. */
@Component @RequiredArgsConstructor
public class PlatformLegacySecretMigration implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CredentialCipher cipher;
    private final BillingSupport json;
    private final RedisTemplate<String,Object> redis;
    @Override public void run(ApplicationArguments args) {
        tx.executeWithoutResult(status->{
            for(Map<String,Object> row:jdbc.queryForList("SELECT model_id,secret_key,secret_value FROM ai_model_config_secret FOR UPDATE")) {
                String value=Objects.toString(row.get("secret_value"),"");
                if(value.isBlank()||value.startsWith("v1:"))continue;
                String scope="legacy-model:"+row.get("model_id")+":"+row.get("secret_key");
                jdbc.update("UPDATE ai_model_config_secret SET secret_value=?,masked_value='***' WHERE model_id=? AND secret_key=?",cipher.encrypt(scope,value),row.get("model_id"),row.get("secret_key"));
            }
            for(Map<String,Object> row:jdbc.queryForList("SELECT id,config_json FROM ai_model_config WHERE config_json IS NOT NULL FOR UPDATE")) {
                String raw=row.get("config_json").toString();Map<String,Object> parsed=json.object(raw);
                String clean=json.json(PlatformCatalogSecrets.publicValue(parsed));
                if(!parsed.equals(json.object(clean))){archive("model-json:"+row.get("id"),raw);jdbc.update("UPDATE ai_model_config SET config_json=? WHERE id=?",clean,row.get("id"));}
            }
            for(Map<String,Object> row:jdbc.queryForList("SELECT id,model_id,param_key,param_value,value_type FROM ai_model_config_param FOR UPDATE")) {
                String raw=Objects.toString(row.get("param_value"),"");
                if(PlatformCatalogSecrets.secret(row.get("param_key").toString())||"secret".equals(row.get("value_type"))) {
                    if(!raw.isBlank())archive("model-param:"+row.get("id"),raw);jdbc.update("DELETE FROM ai_model_config_param WHERE id=?",row.get("id"));
                } else if("json".equals(row.get("value_type"))&&!raw.isBlank()) {
                    Object parsed=json.object("{\"value\":"+raw+"}").get("value");
                    Object clean=PlatformCatalogSecrets.publicValue(parsed);
                    if(!Objects.equals(parsed,clean)){archive("model-param:"+row.get("id"),raw);jdbc.update("UPDATE ai_model_config_param SET param_value=? WHERE id=?",json.json(clean),row.get("id"));}
                }
            }
        });
        // Invalidate only the model/config caches that formerly held global plaintext keys.
        try(var cursor=redis.scan(ScanOptions.scanOptions().match("model:data:*").count(200).build())) {while(cursor.hasNext())redis.delete(cursor.next());}
        redis.delete(List.of("server:config","server:config:public-v2"));
    }
    private void archive(String key,String plaintext) {
        jdbc.update("INSERT INTO ai_platform_legacy_secret_archive(source_key,ciphertext,archived_at) VALUES(?,?,?) ON DUPLICATE KEY UPDATE source_key=source_key",key,cipher.encrypt("legacy-archive:"+key,plaintext),PlatformSupport.now());
    }
}
