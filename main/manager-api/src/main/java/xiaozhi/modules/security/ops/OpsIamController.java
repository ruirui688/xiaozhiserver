package xiaozhi.modules.security.ops;

import java.util.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;

@RestController @RequestMapping("/ops/iam") @RequiredArgsConstructor
public class OpsIamController {
    private final OpsIamService iam;
    private final JdbcTemplate jdbc;
    private final OpsEnrollmentService enrollment;
    @GetMapping("/identity-requests") public Result<List<Map<String,Object>>> identityRequests(){return new Result<List<Map<String,Object>>>().ok(enrollment.list());}
    @PostMapping("/identity-requests/{id}/endorse") public Result<Void> endorseIdentity(@PathVariable String id){enrollment.endorse(id);return new Result<>();}
    @PostMapping("/identity-requests/{id}/approve") public Result<Void> approveIdentity(@PathVariable String id){enrollment.approve(id);return new Result<>();}
    @GetMapping("/templates") public Result<Collection<OpsPermissionCatalog.Template>> templates() {
        OpsAccess.require("iam.read","PLATFORM","identity");return new Result<Collection<OpsPermissionCatalog.Template>>().ok(OpsPermissionCatalog.TEMPLATES.values());
    }
    @GetMapping("/employees") public Result<List<Map<String,Object>>> employees() {
        OpsAccess.require("iam.read","PLATFORM","identity");return new Result<List<Map<String,Object>>>().ok(jdbc.queryForList("SELECT id,username,status,created_at AS createdAt FROM ops_employee ORDER BY created_at LIMIT 500"));
    }
    @GetMapping("/grants") public Result<List<Map<String,Object>>> grants() {
        OpsAccess.require("iam.read","PLATFORM","identity");return new Result<List<Map<String,Object>>>().ok(jdbc.queryForList("SELECT id,employee_id AS employeeId,role_code AS roleCode,scope_type AS scopeType,scope_id AS scopeId,expires_at AS expiresAt,status FROM ops_role_grant ORDER BY created_at DESC LIMIT 500"));
    }
    @GetMapping("/grant-requests") public Result<List<Map<String,Object>>> requests() {
        OpsAccess.require("iam.read","PLATFORM","identity");return new Result<List<Map<String,Object>>>().ok(jdbc.queryForList("SELECT id,target_employee_id AS targetEmployeeId,requester_id AS requesterId,approver_id AS approverId,role_code AS roleCode,scope_type AS scopeType,scope_id AS scopeId,grant_expires_at AS grantExpiresAt,expires_at AS expiresAt,status FROM ops_grant_request ORDER BY created_at DESC LIMIT 500"));
    }
    @PostMapping("/grant-requests") public Result<Map<String,String>> request(@Valid @RequestBody OpsIamService.GrantRequest request) { return new Result<Map<String,String>>().ok(Map.of("id",iam.request(request))); }
    @PostMapping("/grant-requests/{id}/approve") public Result<Void> approve(@PathVariable String id) { iam.approve(id); return new Result<>(); }
    @DeleteMapping("/grants/{id}") public Result<Void> revoke(@PathVariable String id) { iam.revoke(id);return new Result<>(); }
    @GetMapping("/audit") public Result<List<Map<String,Object>>> audit() {
        OpsAccess.require("audit.read","PLATFORM","audit");return new Result<List<Map<String,Object>>>().ok(jdbc.queryForList("SELECT id,actor_id AS actorId,action,target_id AS targetId,outcome,created_at AS createdAt FROM ops_audit_event ORDER BY created_at DESC LIMIT 500"));
    }
}
