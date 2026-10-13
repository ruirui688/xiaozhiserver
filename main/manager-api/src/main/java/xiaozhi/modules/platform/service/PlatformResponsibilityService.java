package xiaozhi.modules.platform.service;

import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.platform.dto.PlatformRequests.*;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;
import static xiaozhi.modules.platform.service.PlatformSupport.*;

@Service @RequiredArgsConstructor
public class PlatformResponsibilityService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkspacePermissionService permissions;
    private final PlatformChannelService channels;
    public void ensureWorkspace(String workspaceId) {
        Map<String,Object> workspace=jdbc.queryForMap("SELECT owner_user_id FROM sys_workspace WHERE id=?",workspaceId);
        jdbc.update("INSERT INTO ai_platform_responsibility(workspace_id,responsible_user_id,version) VALUES(?,?,1) ON DUPLICATE KEY UPDATE workspace_id=workspace_id",
            workspaceId,workspace.get("owner_user_id"));
    }
    public Map<String,Object> lock(String workspaceId) { return jdbc.queryForMap("SELECT * FROM ai_platform_responsibility WHERE workspace_id=? FOR UPDATE",workspaceId); }
    public Map<String,Object> summary(String workspaceId,long userId) {
        permissions.requireRead(workspaceId);
        Map<String,Object> row=jdbc.queryForMap("SELECT * FROM ai_platform_responsibility WHERE workspace_id=?",workspaceId);
        long payer=number(row.get("responsible_user_id"));
        Map<String,Object> balance=jdbc.queryForMap("SELECT granted,spent,reserved,frozen,(granted-spent-reserved) AS available FROM ai_platform_account WHERE user_id=?",payer);
        Map<String,Object> totals=usage(workspaceId,"TOTAL"),daily=usage(workspaceId,java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString());
        balance.put("status",number(balance.get("frozen"))!=0?"FROZEN":"ACTIVE");balance.put("currency","CNY");
        List<Map<String,Object>> services=channels.availability();
        String serviceStatus=number(balance.get("frozen"))!=0?"PAYER_FROZEN"
            :PlatformAccountService.available(balance).signum()<=0?"ALLOWANCE_EXHAUSTED"
            :limitReached(row.get("total_limit"),totals)||limitReached(row.get("daily_limit"),daily)?"WORKSPACE_ALLOWANCE_LIMIT"
            :services.stream().anyMatch(service->"READY".equals(service.get("status")))?"READY":"PLATFORM_SERVICE_NOT_READY";
        return publicMap(map("workspaceId",workspaceId,"ownerUserId",payer,"responsibleUserId",payer,"payerUserId",payer,"responsibilityVersion",row.get("version"),
            "canViewBalance",payer==userId,"canManage",payer==userId,"allowance",payer==userId?balance:null,"serviceStatus",serviceStatus,
            "totalSpent",totals.get("spent"),"totalReserved",totals.get("reserved"),"dailySpent",daily.get("spent"),"dailyReserved",daily.get("reserved"),"services",services,"totalLimit",row.get("total_limit"),"dailyLimit",row.get("daily_limit")));
    }
    private static boolean limitReached(Object limit,Map<String,Object> usage) {
        return limit!=null&&amount(usage.get("spent")).add(amount(usage.get("reserved"))).compareTo(amount(limit))>=0;
    }
    private Map<String,Object> usage(String workspace,String period) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT spent,reserved FROM ai_platform_workspace_usage WHERE workspace_id=? AND period=?",workspace,period);
        return rows.isEmpty()?map("spent","0","reserved","0"):rows.getFirst();
    }
    public Map<String,Object> limits(WorkspaceLimits input,long userId) {
        permissions.requireRead(input.workspaceId());
        return tx.execute(status->{
            Map<String,Object> row=lock(input.workspaceId());requireResponsible(row,userId,input.expectedVersion());
            BigDecimal total=input.totalLimit()==null?null:nonnegative(input.totalLimit());BigDecimal daily=input.dailyLimit()==null?null:nonnegative(input.dailyLimit());
            jdbc.update("UPDATE ai_platform_responsibility SET total_limit=?,daily_limit=? WHERE workspace_id=?",total,daily,input.workspaceId());
            return summary(input.workspaceId(),userId);
        });
    }
    private static void requireResponsible(Map<String,Object> row,long user,long version) {
        if(number(row.get("responsible_user_id"))!=user)throw new RenException("RESPONSIBLE_USER_REQUIRED");
        if(number(row.get("version"))!=version)throw new RenException("RESPONSIBILITY_CHANGED");
    }
}
