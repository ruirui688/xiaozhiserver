package xiaozhi.modules.security.ops;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import xiaozhi.common.exception.RenException;

@Service @RequiredArgsConstructor
public class OpsApprovalService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OpsIdentityService identities;
    private final PlatformTransactionManager transactions;
    public record Policy(String action,String approvalPermission,String domain) {}
    public record Proposal(String action,String targetId,String version,JsonNode payload) {}
    private static final Map<String,Policy> POLICIES=Map.of(
        "ota.publish",new Policy("ota.publish","ota.approve","firmware"),
        "official.resource.publish",new Policy("official.resource.publish","official.resource.approve","official-resources"),
        "model.catalog.publish",new Policy("model.catalog.publish","model.catalog.approve","models"));
    public Map<String,Object> propose(Proposal request) {
        Policy policy=policy(request.action());OpsAccess.require(policy.action(),"PLATFORM",policy.domain());
        if(request.targetId()==null||request.targetId().isBlank()||request.targetId().length()>100||request.version()==null||request.version().length()>100||request.payload()==null)throw new RenException("审批对象与版本不能为空");
        JsonNode payload=canonical(request.payload());rejectSecrets(payload);
        String json=payload.toString();if(json.length()>1000000)throw new RenException("审批内容过大");
        String id=UUID.randomUUID().toString(),hash=OpsIdentityService.hash(json),actor=OpsAccess.employee().getEmployeeId();
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            jdbc.update("INSERT INTO ops_operation_approval(id,requester_id,action,target_id,target_version,payload_hash,payload_json,status,expires_at) VALUES(?,?,?,?,?,?,?,?,?)",id,actor,policy.action(),request.targetId(),request.version(),hash,json,"PENDING",Timestamp.from(Instant.now().plusSeconds(86400)));
            identities.audit(actor,"release.propose",id,"PENDING");
        });
        return Map.of("id",id,"payloadHash",hash,"status","PENDING");
    }
    public void approve(String id) {
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            var row=locked(id);Policy policy=policy(row.get("action").toString());
            OpsAccess.require(policy.approvalPermission(),"PLATFORM",policy.domain());
            String actor=OpsAccess.employee().getEmployeeId();if(actor.equals(row.get("requester_id")))throw new RenException("不能审批自己的发布申请");
            if(!"PENDING".equals(row.get("status")))throw new RenException("审批已处理");
            requireCurrentRequester(row,policy);
            jdbc.update("UPDATE ops_operation_approval SET status='APPROVED',approver_id=? WHERE id=?",actor,id);
            identities.audit(actor,"release.approve",id,"APPROVED");
        });
    }
    public <T> T executeCurrent(String action,String target,String version,Object payload,Supplier<T> mutation) {
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))throw new RenException("发布操作需要受审计的员工请求");
        return execute(attributes.getRequest().getHeader("X-Ops-Approval-Id"),action,target,version,payload,mutation);
    }
    public <T> T execute(String id,String action,String target,String version,Object payload,Supplier<T> mutation) {
        Policy policy=policy(action);OpsAccess.require(action,"PLATFORM",policy.domain());String actor=OpsAccess.employee().getEmployeeId();
        String hash=hash(payload);
        return new TransactionTemplate(transactions).execute(tx->{
            var row=locked(id);
            if(!"APPROVED".equals(row.get("status"))||!actor.equals(row.get("requester_id"))||!action.equals(row.get("action"))||!target.equals(row.get("target_id"))||!version.equals(row.get("target_version"))||!hash.equals(row.get("payload_hash")))throw new RenException("审批未通过、已消费或发布内容发生变化");
            requireCurrentRequester(row,policy);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM ops_employee WHERE id=? AND status=1",Integer.class,row.get("approver_id"))!=1
                ||identities.grants(row.get("approver_id").toString()).stream().noneMatch(g->g.permissions().contains(policy.approvalPermission())&&g.scopeId().equals(policy.domain())))throw new RenException("审批人的权限已失效");
            jdbc.update("UPDATE ops_operation_approval SET status='CONSUMED',consumed_at=CURRENT_TIMESTAMP WHERE id=?",id);
            T result=mutation.get();identities.audit(actor,"release.execute",id,"SUCCESS");return result;
        });
    }
    public List<Map<String,Object>> list() {
        OpsAccess.employee();return jdbc.queryForList("SELECT id,requester_id AS requesterId,approver_id AS approverId,action,target_id AS targetId,target_version AS version,payload_hash AS payloadHash,payload_json AS payload,status,expires_at AS expiresAt FROM ops_operation_approval ORDER BY created_at DESC LIMIT 200")
            .stream().filter(row->{var policy=policy(row.get("action").toString());return OpsAccess.has(policy.action(),"PLATFORM",policy.domain())||OpsAccess.has(policy.approvalPermission(),"PLATFORM",policy.domain());}).toList();
    }
    public String hash(Object payload){return OpsIdentityService.hash(canonical(mapper.valueToTree(payload)).toString());}
    private Map<String,Object> locked(String id){
        if(id==null||id.isBlank())throw new RenException("缺少发布审批凭证");
        var rows=jdbc.queryForList("SELECT * FROM ops_operation_approval WHERE id=? AND expires_at>CURRENT_TIMESTAMP FOR UPDATE",id);
        if(rows.isEmpty())throw new RenException("审批不存在或已过期");return rows.getFirst();
    }
    private void requireCurrentRequester(Map<String,Object> row,Policy policy){
        Integer active=jdbc.queryForObject("SELECT COUNT(*) FROM ops_employee WHERE id=? AND status=1",Integer.class,row.get("requester_id"));
        if(active!=1||identities.grants(row.get("requester_id").toString()).stream().noneMatch(g->g.permissions().contains(policy.action())&&g.scopeId().equals(policy.domain())))throw new RenException("申请人的发布权限已失效");
    }
    private static Policy policy(String action){Policy policy=POLICIES.get(action);if(policy==null)throw new RenException("不支持的审批动作");return policy;}
    private static JsonNode canonical(JsonNode node){
        if(node.isObject()){ObjectNode result=JsonNodeFactory.instance.objectNode();var names=new TreeSet<String>();node.fieldNames().forEachRemaining(names::add);names.forEach(name->result.set(name,canonical(node.get(name))));return result;}
        if(node.isArray()){ArrayNode result=JsonNodeFactory.instance.arrayNode();node.forEach(value->result.add(canonical(value)));return result;}return node;
    }
    private static void rejectSecrets(JsonNode node){
        if(node.isObject())node.fields().forEachRemaining(entry->{if(entry.getKey().toLowerCase(Locale.ROOT).matches(".*(password|secret|api.?key|access.?token|authorization).*"))throw new RenException("审批记录不能包含秘密字段");rejectSecrets(entry.getValue());});
        else if(node.isArray())node.forEach(OpsApprovalService::rejectSecrets);
    }
}
