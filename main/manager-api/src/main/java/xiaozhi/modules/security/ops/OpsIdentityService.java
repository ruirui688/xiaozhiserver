package xiaozhi.modules.security.ops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.security.password.PasswordUtils;

@Service @RequiredArgsConstructor
public class OpsIdentityService {
    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;
    private final OpsLoginPolicy loginPolicy;
    private static final SecureRandom RANDOM = new SecureRandom();
    public Map<String,Object> login(String username, String password, String otp, String remoteAddress) {
        return login(username,()->password,otp,remoteAddress);
    }
    public Map<String,Object> login(String username, Supplier<String> passwordDecoder, String otp, String remoteAddress) {
        limit("ip:" + remoteAddress, 100);
        limit("account:" + String.valueOf(username).toLowerCase(Locale.ROOT), 10);
        // Include malformed encrypted submissions in the existing persistent limits.
        String password=passwordDecoder.get();
        var rows = jdbc.queryForList("SELECT id,password_hash,mfa_cipher,mfa_last_step,status FROM ops_employee WHERE username=?", username);
        if (rows.isEmpty()) throw invalid();
        var row = rows.getFirst(); String employee = row.get("id").toString();
        if (((Number)row.get("status")).intValue()!=1 || !PasswordUtils.matches(password,(String)row.get("password_hash"))) throw invalid();
        if(loginPolicy.mfaRequired()) {
            String secret = cipher.decrypt("ops-mfa:" + employee,(String)row.get("mfa_cipher"));
            long step = OpsTotp.verify(secret,otp,Instant.now().getEpochSecond(),((Number)row.get("mfa_last_step")).longValue());
            if (step < 0 || jdbc.update("UPDATE ops_employee SET mfa_last_step=? WHERE id=? AND mfa_last_step<? AND status=1",step,employee,step)!=1) throw invalid();
        }
        byte[] entropy = new byte[32]; RANDOM.nextBytes(entropy);
        String token="ops_"+Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        if(jdbc.update("INSERT INTO ops_session(id,employee_id,token_hash,expires_at) SELECT ?,id,?,? FROM ops_employee WHERE id=? AND status=1 AND password_hash=?",
                UUID.randomUUID().toString(),hash(token),Timestamp.from(Instant.now().plusSeconds(3600)),employee,row.get("password_hash"))!=1)throw invalid();
        audit(employee,"session.login",employee,"SUCCESS");
        return Map.of("token",token,"expire",3600,"audience","OPS");
    }
    public UserDetail authenticate(String token) {
        if(token==null || !token.startsWith("ops_")) throw invalid();
        var rows=jdbc.queryForList("SELECT e.id,e.username,e.status FROM ops_session s JOIN ops_employee e ON e.id=s.employee_id WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP AND e.status=1",hash(token));
        if(rows.isEmpty()) throw invalid();
        var row=rows.getFirst(); UserDetail user=new UserDetail();
        user.setEmployeeId(row.get("id").toString()); user.setUsername(row.get("username").toString());
        user.setAudience("OPS"); user.setStatus(1); user.setToken(token); // No tenant user ID.
        user.setGrants(grants(user.getEmployeeId())); return user;
    }
    public List<OpsGrant> grants(String employee) {
        return jdbc.queryForList("SELECT role_code,scope_type,scope_id FROM ops_role_grant WHERE employee_id=? AND status=1 AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)",employee)
            .stream().map(row->{String role=row.get("role_code").toString(),type=row.get("scope_type").toString(),scope=row.get("scope_id").toString();
                return new OpsGrant(role,type,scope,OpsPermissionCatalog.validate(role,type,scope).permissions());}).toList();
    }
    public void logout(String token) { jdbc.update("DELETE FROM ops_session WHERE token_hash=?",hash(token)); }
    public void revokeSessions(String employee) { jdbc.update("DELETE FROM ops_session WHERE employee_id=?",employee); }
    public void audit(String actor,String action,String target,String outcome) {
        jdbc.update("INSERT INTO ops_audit_event(id,actor_id,action,target_id,outcome) VALUES(?,?,?,?,?)",UUID.randomUUID().toString(),actor,action,target,outcome);
    }
    void limit(String key,int maximum) {
        String id=hash(key);
        try { jdbc.update("INSERT INTO ops_login_limit(key_hash,window_start,attempts) VALUES(?,CURRENT_TIMESTAMP,0)",id); }
        catch(org.springframework.dao.DuplicateKeyException ignored) { /* existing persistent counter */ }
        jdbc.update("UPDATE ops_login_limit SET window_start=CURRENT_TIMESTAMP,attempts=0 WHERE key_hash=? AND window_start<?",id,Timestamp.from(Instant.now().minusSeconds(600)));
        if(jdbc.update("UPDATE ops_login_limit SET attempts=attempts+1 WHERE key_hash=? AND attempts<?",id,maximum)!=1) throw new RenException("登录尝试过多，请稍后重试");
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private static RenException invalid() { return new RenException("员工登录失败，请检查登录信息"); }
}
