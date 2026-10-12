package xiaozhi.modules.security.ops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.modules.security.password.PasswordUtils;

/** Explicit local initialization; no HTTP bootstrap, no first-user privilege, no reset-on-empty fallback. */
@Component @RequiredArgsConstructor
public class OpsBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager transactions;
    @Value("${xiaozhi.security.ops-bootstrap-file:}") private String manifestFile;
    public record Assignment(String roleCode,String scopeType,String scopeId) {}
    public record Employee(String username,String password,String totpSecret,List<Assignment> grants,List<Assignment> delegations) {}
    public record Manifest(List<Employee> employees) {}
    @Override public void run(ApplicationArguments arguments) throws Exception {
        if(manifestFile==null||manifestFile.isBlank()) return;
        Path file=Path.of(manifestFile).toRealPath();
        if(!Files.isRegularFile(file)||Files.size(file)>65536) throw new IllegalArgumentException("员工初始化清单无效");
        if(Files.getFileStore(file).supportsFileAttributeView("posix")) {
            var permissions=Files.getPosixFilePermissions(file);
            if(permissions.stream().anyMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_"))) throw new IllegalArgumentException("员工初始化清单只能由文件所有者访问");
        }
        byte[] bytes=Files.readAllBytes(file); Manifest manifest=mapper.readValue(bytes,Manifest.class);
        if(manifest.employees()==null||manifest.employees().size()<2||manifest.employees().size()>100) throw new IllegalArgumentException("初始化需要至少两名独立员工");
        Set<String> names=new HashSet<>(); Set<String> governance=new HashSet<>();
        for(Employee employee:manifest.employees()) {
            if(employee.username()==null||!employee.username().matches("[a-zA-Z0-9_.@-]{3,100}")||!names.add(employee.username())
                ||employee.password()==null||employee.password().length()<14||!employee.password().matches("(?s).*[A-Z].*")||!employee.password().matches("(?s).*[a-z].*")||!employee.password().matches("(?s).*[0-9].*")) throw new IllegalArgumentException("员工初始化身份或密码无效");
            OpsTotp.decode(employee.totpSecret());
            if(employee.grants()==null||employee.delegations()==null) throw new IllegalArgumentException("必须显式声明岗位与委派范围");
            Set<String> roles=new HashSet<>();
            for(Assignment grant:employee.grants()) { OpsPermissionCatalog.validate(grant.roleCode(),grant.scopeType(),grant.scopeId()); roles.add(grant.roleCode()); }
            OpsPermissionCatalog.requireCompatible(roles);
            governance.addAll(roles);
            for(Assignment grant:employee.delegations()) OpsPermissionCatalog.validate(grant.roleCode(),grant.scopeType(),grant.scopeId());
        }
        if(!governance.containsAll(Set.of("IAM_REQUEST","IAM_APPROVE"))) throw new IllegalArgumentException("需要独立申请与审批岗位");
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            Integer consumed=jdbc.queryForObject("SELECT consumed FROM ops_bootstrap_state WHERE id=1 FOR UPDATE",Integer.class);
            if(consumed==null||consumed!=0) throw new IllegalStateException("员工初始化已经完成，禁止重复执行");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM ops_employee",Integer.class)!=0) throw new IllegalStateException("已有员工时禁止重新初始化");
            for(Employee employee:manifest.employees()) {
                String id=UUID.randomUUID().toString();
                jdbc.update("INSERT INTO ops_employee(id,username,password_hash,mfa_cipher) VALUES(?,?,?,?)",id,employee.username(),PasswordUtils.encode(employee.password()),cipher.encrypt("ops-mfa:"+id,employee.totpSecret()));
                for(Assignment grant:employee.grants()) jdbc.update("INSERT INTO ops_role_grant(id,employee_id,role_code,scope_type,scope_id) VALUES(?,?,?,?,?)",UUID.randomUUID().toString(),id,grant.roleCode(),grant.scopeType(),grant.scopeId());
                for(Assignment grant:employee.delegations()) jdbc.update("INSERT INTO ops_delegation(id,employee_id,role_code,scope_type,scope_id) VALUES(?,?,?,?,?)",UUID.randomUUID().toString(),id,grant.roleCode(),grant.scopeType(),grant.scopeId());
            }
            jdbc.update("UPDATE ops_bootstrap_state SET consumed=1,manifest_hash=?,consumed_at=CURRENT_TIMESTAMP WHERE id=1",OpsIdentityService.hash(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)));
            jdbc.update("INSERT INTO ops_audit_event(id,action,target_id,outcome) VALUES(?,?,?,?)",UUID.randomUUID().toString(),"bootstrap.consume","initial-roster","SUCCESS");
        });
        Arrays.fill(bytes,(byte)0);
    }
}
