package xiaozhi.modules.platform.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.billing.service.BillingSupport;
import static xiaozhi.modules.platform.service.PlatformSupport.*;

/** Internal execution principal, never a product user or tenant-content permission. */
@Service @RequiredArgsConstructor
public class PlatformServiceAccountService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BillingSupport json;
    public record ServiceInput(String id,Set<String> modelIds,Set<String> purposes) {}
    public Map<String,Object> create(ServiceInput input) {
        String id=requestId(input.id());
        if(input.modelIds()==null||input.modelIds().isEmpty()||input.purposes()==null||input.purposes().isEmpty()||!Set.of("MODEL_TEST","BENCHMARK","MODEL_ASSISTANT","OFFICIAL_KNOWLEDGE").containsAll(input.purposes()))throw new RenException("INVALID_PLATFORM_SERVICE");
        return tx.execute(status->{
            long account=-Math.abs(UUID.randomUUID().getMostSignificantBits()%9_000_000_000_000_000L)-1;
            jdbc.update("INSERT INTO ai_platform_service_account(id,account_id,model_ids_json,purposes_json,status,created_at) VALUES(?,?,?,?,?,?)",id,account,json.json(input.modelIds()),json.json(input.purposes()),"ACTIVE",now());
            jdbc.update("INSERT INTO ai_platform_account(user_id,updated_at) VALUES(?,?)",account,now());
            return map("id",id,"accountId",account,"status","ACTIVE");
        });
    }
    public List<Map<String,Object>> list() {
        return jdbc.queryForList("SELECT s.id,s.account_id AS accountId,s.model_ids_json AS modelIdsJson,s.purposes_json AS purposesJson,s.status,a.granted,a.spent,a.reserved,(a.granted-a.spent-a.reserved) AS available FROM ai_platform_service_account s JOIN ai_platform_account a ON a.user_id=s.account_id");
    }
    public String resolve(String model,String purpose) {
        List<String> matches=new ArrayList<>();
        for(Map<String,Object> row:jdbc.queryForList("SELECT * FROM ai_platform_service_account WHERE status='ACTIVE' ORDER BY id")) {
            Map<String,Object> models=json.object("{\"values\":"+row.get("model_ids_json")+"}"),purposes=json.object("{\"values\":"+row.get("purposes_json")+"}");
            if(((List<?>)models.get("values")).contains(model)&&((List<?>)purposes.get("values")).contains(purpose))matches.add(row.get("id").toString());
        }
        if(matches.size()!=1)throw new RenException("PLATFORM_SERVICE_BUDGET_REQUIRED");return matches.getFirst();
    }
    public void validate(String id,String model,String purpose) {
        if(!resolve(model,purpose).equals(id))throw new RenException("MODEL_OUTSIDE_PLATFORM_SERVICE");
    }
}
