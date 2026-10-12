package xiaozhi.modules.security.ops;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xiaozhi.common.exception.RenException;

@Service @RequiredArgsConstructor
public class OpsIamService {
    private final JdbcTemplate jdbc;
    private final OpsIdentityService identities;
    public record GrantRequest(String targetEmployeeId,String roleCode,String scopeType,String scopeId,Instant expiresAt) {}
    @Transactional
    public String request(GrantRequest request) {
        OpsAccess.require("iam.request","PLATFORM","identity");
        String actor=OpsAccess.employee().getEmployeeId();
        OpsPermissionCatalog.validate(request.roleCode(),request.scopeType(),request.scopeId());
        if(actor.equals(request.targetEmployeeId())) throw new RenException("不能申请自己的授权");
        if(request.expiresAt()==null || !request.expiresAt().isAfter(Instant.now()) || request.expiresAt().isAfter(Instant.now().plusSeconds(90L*86400))) throw new RenException("授权必须在90天内到期");
        requireActive(request.targetEmployeeId());
        requireDelegation(actor,request.roleCode(),request.scopeType(),request.scopeId(),request.expiresAt());
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ops_grant_request(id,target_employee_id,requester_id,role_code,scope_type,scope_id,grant_expires_at,expires_at,status) VALUES(?,?,?,?,?,?,?,?,?)",
            id,request.targetEmployeeId(),actor,request.roleCode(),request.scopeType(),request.scopeId(),Timestamp.from(request.expiresAt()),Timestamp.from(Instant.now().plusSeconds(86400)),"PENDING");
        identities.audit(actor,"grant.request",id,"PENDING"); return id;
    }
    @Transactional
    public void approve(String id) {
        OpsAccess.require("iam.approve","PLATFORM","identity"); String actor=OpsAccess.employee().getEmployeeId();
        var rows=jdbc.queryForList("SELECT * FROM ops_grant_request WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new RenException("授权申请不存在");
        var row=rows.getFirst(); String target=row.get("target_employee_id").toString(),requester=row.get("requester_id").toString();
        if(actor.equals(target)||actor.equals(requester)) throw new RenException("审批人不能是申请人或受益人");
        if(!"PENDING".equals(row.get("status")) || !instant(row.get("expires_at")).isAfter(Instant.now())) throw new RenException("授权申请已处理或过期");
        String role=row.get("role_code").toString(),type=row.get("scope_type").toString(),scope=row.get("scope_id").toString();
        Instant expires=instant(row.get("grant_expires_at"));
        if(!expires.isAfter(Instant.now())) throw new RenException("授权有效期已结束");
        OpsPermissionCatalog.validate(role,type,scope); requireActive(target); requireActive(requester);
        if(identities.grants(requester).stream().noneMatch(g->g.permissions().contains("iam.request"))) throw new RenException("申请人的权限已失效");
        requireDelegation(requester,role,type,scope,expires); requireDelegation(actor,role,type,scope,expires);
        requireNoConflict(target,role);
        jdbc.update("INSERT INTO ops_role_grant(id,employee_id,role_code,scope_type,scope_id,expires_at,status) VALUES(?,?,?,?,?,?,1)",UUID.randomUUID().toString(),target,role,type,scope,Timestamp.from(expires));
        jdbc.update("UPDATE ops_grant_request SET status='APPROVED',approver_id=? WHERE id=?",actor,id);
        identities.revokeSessions(target); identities.audit(actor,"grant.approve",id,"APPROVED");
    }
    @Transactional
    public void revoke(String id) {
        OpsAccess.require("iam.revoke","PLATFORM","identity"); String actor=OpsAccess.employee().getEmployeeId();
        var rows=jdbc.queryForList("SELECT * FROM ops_role_grant WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new RenException("授权不存在"); var row=rows.getFirst();
        requireDelegation(actor,row.get("role_code").toString(),row.get("scope_type").toString(),row.get("scope_id").toString(),Instant.now());
        jdbc.update("UPDATE ops_role_grant SET status=0 WHERE id=?",id);
        identities.revokeSessions(row.get("employee_id").toString()); identities.audit(actor,"grant.revoke",id,"REVOKED");
    }
    public void requireNoConflict(String employee,String newRole) {
        Set<String> roles=new HashSet<>();
        identities.grants(employee).forEach(grant->roles.add(grant.roleCode()));
        roles.add(newRole); OpsPermissionCatalog.requireCompatible(roles);
    }
    private void requireActive(String employee) {
        // Serialize competing grants for the same employee before checking incompatible roles.
        var rows=jdbc.queryForList("SELECT status FROM ops_employee WHERE id=? FOR UPDATE",employee);
        if(rows.isEmpty()||((Number)rows.getFirst().get("status")).intValue()!=1) throw new RenException("员工不存在或已停用");
    }
    private void requireDelegation(String employee,String role,String type,String scope,Instant end) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM ops_delegation WHERE employee_id=? AND role_code=? AND scope_type=? AND scope_id=? AND status=1 AND (expires_at IS NULL OR expires_at>=?)",Integer.class,employee,role,type,scope,Timestamp.from(end))<1) throw new RenException("超出被委派的角色、资源或期限");
    }
    private static Instant instant(Object value) {
        return value instanceof Timestamp timestamp?timestamp.toInstant():((java.time.LocalDateTime)value).toInstant(java.time.ZoneOffset.UTC);
    }
}
