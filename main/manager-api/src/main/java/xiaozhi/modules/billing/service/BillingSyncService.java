package xiaozhi.modules.billing.service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.security.ops.OpsAccess;

@Service @RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
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
        OpsAccess.require("supplier.bill.sync","PLATFORM","platform-services");
        String actor=OpsAccess.employee().getEmployeeId();
        if(actor.isBlank())throw new RenException("操作员工身份无效");
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
            createRun(id,period,actor);
            return executeRun(id,period);
        } finally { running.set(false); }
    }

    private void createRun(String id,String period,String actor) {
        try {
            jdbc.update("INSERT INTO ai_billing_sync_run (id,provider,account_ref,bill_period,status,created_by_employee_id,started_at) VALUES (?,'VOLCENGINE',?,?,'RUNNING',?,?)",id,client.accountRef(),period,actor,BillingSupport.time(Instant.now()));
        } catch(DataAccessException e) {
            throw new RenException("账单同步记录创建失败，尚未查询供应商");
        }
    }

    private Map<String,Object> executeRun(String id,String period) {
        // Run status commits independently; replacePeriod owns the atomic snapshot transaction.
        try {
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
        }
    }
}
