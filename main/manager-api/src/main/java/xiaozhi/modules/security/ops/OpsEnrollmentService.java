package xiaozhi.modules.security.ops;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.modules.security.password.PasswordUtils;

/** Employees set their own factors; governance approves identity, never receives factors. */
@Service @RequiredArgsConstructor
public class OpsEnrollmentService {
    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;
    private final OpsIdentityService identities;
    private final PlatformTransactionManager transactions;
    private static final SecureRandom RANDOM=new SecureRandom();
    public Map<String,Object> submit(String username,String password,String secret,String otp,String recoveryCode,String address) {
        identities.limit("enroll-ip:"+address,20);
        identities.limit("enroll-name:"+String.valueOf(username).toLowerCase(Locale.ROOT),5);
        if(username==null||!username.matches("[a-zA-Z0-9_.@-]{3,100}")||password==null||password.length()<14||password.length()>128
            ||!password.matches("(?s).*[A-Z].*")||!password.matches("(?s).*[a-z].*")||!password.matches("(?s).*[0-9].*")) throw new RenException("账号或密码格式无效，密码至少14位且包含大小写字母及数字");
        long step=OpsTotp.verify(secret,otp,Instant.now().getEpochSecond(),-1);
        if(step<0)throw new RenException("验证器验证码无效");
        String replacementCode=randomCode();
        return new TransactionTemplate(transactions).execute(tx->{
            // Serialize with the one-time bootstrap before an anonymous request can create an employee.
            Integer initialized=jdbc.queryForObject("SELECT consumed FROM ops_bootstrap_state WHERE id=1 FOR UPDATE",Integer.class);
            if(!Integer.valueOf(1).equals(initialized))throw new RenException("运营身份尚未初始化，请联系部署负责人");
            var rows=jdbc.queryForList("SELECT id,status,recovery_hash FROM ops_employee WHERE username=? FOR UPDATE",username);
            boolean recovering=recoveryCode!=null&&!recoveryCode.isBlank();String employee;
            if(recovering) {
                if(rows.isEmpty()||((Number)rows.getFirst().get("status")).intValue()!=1
                    ||!OpsIdentityService.hash(recoveryCode).equals(rows.getFirst().get("recovery_hash")))throw new RenException("身份恢复凭证无效");
                employee=rows.getFirst().get("id").toString();
                jdbc.update("UPDATE ops_employee SET recovery_hash=NULL WHERE id=?",employee);
                jdbc.update("UPDATE ops_identity_change SET status='CANCELLED' WHERE employee_id=? AND status IN ('PENDING','ENDORSED')",employee);
                identities.revokeSessions(employee);
            } else {
                if(!rows.isEmpty())throw new RenException("无法申请该员工身份");
                employee=UUID.randomUUID().toString();
                jdbc.update("INSERT INTO ops_employee(id,username,password_hash,mfa_cipher,mfa_last_step,status) VALUES(?,?,?,?,?,0)",employee,username,PasswordUtils.encode(password),cipher.encrypt("ops-mfa:"+employee,secret),step);
            }
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO ops_identity_change(id,employee_id,kind,password_hash,mfa_cipher,mfa_last_step,recovery_hash,status,expires_at) VALUES(?,?,?,?,?,?,?,'PENDING',?)",
                id,employee,recovering?"RECOVER":"ENROLL",PasswordUtils.encode(password),cipher.encrypt("ops-mfa:"+employee,secret),step,OpsIdentityService.hash(replacementCode),Timestamp.from(Instant.now().plusSeconds(86400)));
            identities.audit(employee,"identity.request",id,"PENDING");
            return Map.of("id",id,"employeeId",employee,"recoveryCode",replacementCode,"status","PENDING");
        });
    }
    public List<Map<String,Object>> list() {
        OpsAccess.require("iam.read","PLATFORM","identity");
        return jdbc.queryForList("SELECT c.id,c.employee_id AS employeeId,e.username,c.kind,c.requester_id AS requesterId,c.status,c.expires_at AS expiresAt FROM ops_identity_change c JOIN ops_employee e ON e.id=c.employee_id ORDER BY c.created_at DESC LIMIT 200");
    }
    public void endorse(String id) {
        OpsAccess.require("iam.request","PLATFORM","identity");String actor=OpsAccess.employee().getEmployeeId();
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            var row=locked(id);if(!"PENDING".equals(row.get("status"))||actor.equals(row.get("employee_id")))throw new RenException("身份申请不可由本人确认或重复处理");
            jdbc.update("UPDATE ops_identity_change SET requester_id=?,status='ENDORSED' WHERE id=?",actor,id);
            identities.audit(actor,"identity.endorse",id,"ENDORSED");
        });
    }
    public void approve(String id) {
        OpsAccess.require("iam.approve","PLATFORM","identity");String actor=OpsAccess.employee().getEmployeeId();
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            var row=locked(id);String employee=row.get("employee_id").toString();
            if(!"ENDORSED".equals(row.get("status"))||actor.equals(employee)||actor.equals(row.get("requester_id")))throw new RenException("身份审批需要独立于申请人与本人");
            String requester=row.get("requester_id").toString();
            if(jdbc.queryForObject("SELECT COUNT(*) FROM ops_employee WHERE id=? AND status=1",Integer.class,requester)!=1
                ||identities.grants(requester).stream().noneMatch(g->g.permissions().contains("iam.request")))throw new RenException("申请人的身份治理权限已失效");
            int expected="ENROLL".equals(row.get("kind"))?0:1;
            if(jdbc.update("UPDATE ops_employee SET password_hash=?,mfa_cipher=?,mfa_last_step=?,recovery_hash=?,status=1 WHERE id=? AND status=?",row.get("password_hash"),row.get("mfa_cipher"),row.get("mfa_last_step"),row.get("recovery_hash"),employee,expected)!=1)throw new RenException("员工状态已改变");
            identities.revokeSessions(employee);
            // Recovery updates factors only. Revoked or expired roles stay revoked or expired.
            jdbc.update("UPDATE ops_identity_change SET status='APPROVED',approver_id=?,password_hash=NULL,mfa_cipher=NULL,recovery_hash=NULL WHERE id=?",actor,id);
            identities.audit(actor,"identity.approve",id,"APPROVED");
        });
    }
    public String issueRecoveryCode(String password,String otp) {
        String employee=OpsAccess.employee().getEmployeeId();
        identities.limit("recovery-code:"+employee,5);
        return new TransactionTemplate(transactions).execute(tx->{
            var row=jdbc.queryForMap("SELECT password_hash,mfa_cipher,mfa_last_step FROM ops_employee WHERE id=? AND status=1 FOR UPDATE",employee);
            if(!PasswordUtils.matches(password,row.get("password_hash").toString()))throw new RenException("员工验证失败");
            long step=OpsTotp.verify(cipher.decrypt("ops-mfa:"+employee,row.get("mfa_cipher").toString()),otp,Instant.now().getEpochSecond(),((Number)row.get("mfa_last_step")).longValue());
            if(step<0)throw new RenException("员工验证失败");
            String code=randomCode();jdbc.update("UPDATE ops_employee SET recovery_hash=?,mfa_last_step=? WHERE id=?",OpsIdentityService.hash(code),step,employee);
            identities.audit(employee,"identity.recovery-code",employee,"SUCCESS");return code;
        });
    }
    private Map<String,Object> locked(String id){
        var rows=jdbc.queryForList("SELECT * FROM ops_identity_change WHERE id=? AND expires_at>CURRENT_TIMESTAMP FOR UPDATE",id);
        if(rows.isEmpty())throw new RenException("身份申请不存在或已过期");return rows.getFirst();
    }
    private static String randomCode(){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
}
