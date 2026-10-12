package xiaozhi.modules.billing.service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.security.user.SecurityUser;

@Service @RequiredArgsConstructor
public class BillingSyncService {
    private final VolcengineBillingClient client;
    private final BillingBillService bills;
    private final JdbcTemplate jdbc;
    private final AtomicBoolean running=new AtomicBoolean();

    public Map<String,Object> sync(String period) {
        return sync(period,null,null);
    }

    public Map<String,Object> sync(String period,String provider,String accountRef) {
        BillingQueryService.requireAdmin();VolcengineBillingClient.validatePeriod(period);
        // Legacy clients omit both fields and still target the configured Volcengine account.
        if(provider!=null || accountRef!=null) {
            if("DEEPSEEK".equals(provider))throw new RenException("DeepSeek 未接入可核实的公开消费账单接口，请按项目模板导入官方金额凭证");
            if(!"VOLCENGINE".equals(provider) || accountRef==null || accountRef.isBlank() || !accountRef.equals(client.accountRef()))
                throw new RenException("账单同步必须指定已配置的火山引擎账号");
        }
        if(!client.enabled()) throw new RenException("账单同步未配置（仅支持管理端私有配置中的只读账单 AK/SK）");
        if(!running.compareAndSet(false,true)) throw new RenException("已有账单同步正在执行");
        String id=UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO ai_billing_sync_run (id,provider,account_ref,bill_period,status,created_by,started_at) VALUES (?,'VOLCENGINE',?,?,'RUNNING',?,?)",id,client.accountRef(),period,SecurityUser.getUserId(),BillingSupport.time(Instant.now()));
            List<Map<String,Object>> rows=new ArrayList<>();
            client.fetch("ListBill",period).forEach(value->rows.add(bills.official(value,client.accountRef(),period,false)));
            client.fetch("ListBillDetail",period).forEach(value->rows.add(bills.official(value,client.accountRef(),period,true)));
            int count=bills.replacePeriod("VOLCENGINE",client.accountRef(),period,rows);
            jdbc.update("UPDATE ai_billing_sync_run SET status='SUCCESS',imported_count=?,finished_at=? WHERE id=?",count,BillingSupport.time(Instant.now()),id);
            return Map.of("status","SUCCESS","importedCount",count,"message","已同步供应商当前账单快照；当月费用仍可能迟到或修订");
        } catch(Exception e) {
            jdbc.update("UPDATE ai_billing_sync_run SET status='FAILED',error_code='SYNC_FAILED',finished_at=? WHERE id=?",BillingSupport.time(Instant.now()),id);
            if(e instanceof RenException error) throw error;
            throw new RenException("账单同步失败，既有完整账单未被覆盖");
        } finally { running.set(false); }
    }
}
