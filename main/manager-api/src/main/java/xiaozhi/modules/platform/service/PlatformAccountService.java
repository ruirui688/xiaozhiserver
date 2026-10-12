package xiaozhi.modules.platform.service;

import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.platform.dto.PlatformRequests.*;
import static xiaozhi.modules.platform.service.PlatformSupport.*;

@Service @RequiredArgsConstructor
public class PlatformAccountService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PlatformCredentialService credentials;
    @Value("${xiaozhi.platform.max-grant:1000}") private String maxGrant="1000";
    @Value("${xiaozhi.platform.operator-daily-grant:10000}") private String dailyGrant="10000";

    public void ensureUser(long userId) {
        tx.executeWithoutResult(status->{
            jdbc.update("INSERT INTO ai_platform_account(user_id,updated_at) VALUES(?,?) ON DUPLICATE KEY UPDATE user_id=user_id",userId,now());
            credentials.initialize(userId);
        });
    }
    public Map<String,Object> account(long userId) {
        Map<String,Object> row=jdbc.queryForMap("SELECT * FROM ai_platform_account WHERE user_id=?",userId);
        return map("userId",userId,"currency","CNY","status",number(row.get("frozen"))!=0?"FROZEN":"ACTIVE","granted",decimal(row.get("granted")),"spent",decimal(row.get("spent")),"reserved",decimal(row.get("reserved")),
            "available",available(row).toPlainString(),"frozen",number(row.get("frozen"))!=0,"credentials",credentials.statuses(userId));
    }
    public List<Map<String,Object>> accounts() {
        return jdbc.queryForList("SELECT user_id AS userId,granted,spent,reserved,(granted-spent-reserved) AS available,frozen FROM ai_platform_account WHERE user_id>0 ORDER BY user_id LIMIT 500");
    }
    public List<Map<String,Object>> ledger(long userId) {
        return jdbc.queryForList("SELECT id,entry_type AS type,amount,balance_after AS balanceAfter,workspace_id AS workspaceId,reservation_id AS reservationId,reason,created_at AS createdAt FROM ai_platform_ledger WHERE user_id=? ORDER BY id DESC LIMIT 200",userId);
    }
    public Map<String,Object> pool() {
        Map<String,Object> row=jdbc.queryForMap("SELECT * FROM ai_platform_pool WHERE id='SERVICE'");
        return map("total",decimal(row.get("total")),"allocated",decimal(row.get("allocated")),"available",amount(row.get("total")).subtract(amount(row.get("allocated"))).toPlainString());
    }
    public Map<String,Object> adjust(PoolAdjustment input,String operator) {
        return tx.execute(status->{
            Map<String,Object> row=jdbc.queryForMap("SELECT * FROM ai_platform_pool WHERE id='SERVICE' FOR UPDATE");
            BigDecimal delta=amount(input.amount());String key="pool:"+requestId(input.requestId()), hash=hash(delta+"|"+input.reason());
            if(replayed(key,hash))return pool();
            BigDecimal total=amount(row.get("total")).add(delta);
            if(total.compareTo(amount(row.get("allocated")))<0)throw new RenException("POOL_ALREADY_ALLOCATED");
            jdbc.update("UPDATE ai_platform_pool SET total=? WHERE id='SERVICE'",total);
            entry(key,hash,null,"POOL_ADJUST",delta,total.subtract(amount(row.get("allocated"))),null,null,operator,input.reason());
            return pool();
        });
    }
    public Map<String,Object> grant(Grant input,String operator) {
        return tx.execute(status->{
            Map<String,Object> pool=jdbc.queryForMap("SELECT * FROM ai_platform_pool WHERE id='SERVICE' FOR UPDATE");
            Map<String,Object> account=lock(input.userId());BigDecimal delta=amount(input.amount());
            String key="grant:"+requestId(input.requestId()),hash=hash(input.userId()+"|"+delta+"|"+input.reason());
            if(replayed(key,hash))return account(input.userId());
            if(delta.signum()==0||delta.abs().compareTo(positive(maxGrant))>0)throw new RenException("GRANT_LIMIT_EXCEEDED");
            if(delta.signum()>0) {
                BigDecimal today=jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM ai_platform_ledger WHERE operator_id=? AND entry_type='GRANT' AND amount>0 AND created_at>=?",BigDecimal.class,operator,now().toLocalDate().atStartOfDay());
                if(today.add(delta).compareTo(positive(dailyGrant))>0)throw new RenException("OPERATOR_DAILY_GRANT_LIMIT");
                if(amount(pool.get("total")).subtract(amount(pool.get("allocated"))).compareTo(delta)<0)throw new RenException("ISSUANCE_POOL_EXHAUSTED");
            } else if(available(account).add(delta).signum()<0)throw new RenException("ALLOWANCE_ALREADY_USED");
            jdbc.update("UPDATE ai_platform_pool SET allocated=allocated+? WHERE id='SERVICE'",delta);
            jdbc.update("UPDATE ai_platform_account SET granted=granted+?,updated_at=? WHERE user_id=?",delta,now(),input.userId());
            entry(key,hash,input.userId(),delta.signum()>0?"GRANT":"RECLAIM",delta,available(account).add(delta),null,null,operator,input.reason());
            return account(input.userId());
        });
    }
    public Map<String,Object> freeze(Freeze input,String operator) {
        return tx.execute(status->{
            Map<String,Object> account=lock(input.userId());String key="freeze:"+requestId(input.requestId()),hash=hash(input.userId()+"|"+input.frozen()+"|"+input.reason());
            if(!replayed(key,hash)) {
                jdbc.update("UPDATE ai_platform_account SET frozen=?,updated_at=? WHERE user_id=?",input.frozen()?1:0,now(),input.userId());
                entry(key,hash,input.userId(),input.frozen()?"FREEZE":"UNFREEZE",BigDecimal.ZERO,available(account),null,null,operator,input.reason());
            }return account(input.userId());
        });
    }
    public Map<String,Object> lock(long userId) { return jdbc.queryForMap("SELECT * FROM ai_platform_account WHERE user_id=? FOR UPDATE",userId); }
    public static BigDecimal available(Map<String,Object> row) { return amount(row.get("granted")).subtract(amount(row.get("spent"))).subtract(amount(row.get("reserved"))); }
    public boolean replayed(String key,String hash) {
        List<String> rows=jdbc.queryForList("SELECT payload_hash FROM ai_platform_ledger WHERE entry_key=?",String.class,key);
        if(rows.isEmpty())return false;if(!rows.getFirst().equals(hash))throw new RenException("IDEMPOTENCY_CONFLICT");return true;
    }
    public void entry(String key,String hash,Long user,String type,BigDecimal amount,BigDecimal balance,String workspace,String reservation,String operator,String reason) {
        jdbc.update("INSERT INTO ai_platform_ledger(entry_key,payload_hash,user_id,entry_type,amount,balance_after,workspace_id,reservation_id,operator_id,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            key,hash,user,type,amount,balance,workspace,reservation,operator,required(reason,500),now());
    }
}
