package xiaozhi.modules.platform.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.exception.RenException;

import static xiaozhi.modules.platform.service.PlatformSupport.*;

/**
 * User-initiated allowance requests. Since users cannot top up, an exhausted
 * account needs an explicit path to ask the operator for credit.
 *
 * <p>A request records intent only; it never moves money. Approval is performed
 * by {@code allowance.grant} so pool accounting, the issuance cap and the ledger
 * stay on the single operator-grant path. The credited amount is always the
 * operator's decision, not the amount the user asked for.
 */
@Service
@RequiredArgsConstructor
public class AllowanceRequestService {
    public record Create(String requestId, String amount, String reason, String workspaceId) {}
    public record Decision(String requestId, String amount, String reason) {}

    private static final String PENDING = "PENDING";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PlatformAccountService accounts;

    public Map<String, Object> create(long userId, Create input) {
        BigDecimal requested = input.amount() == null || input.amount().isBlank() ? null : nonnegative(input.amount());
        String key = "user:" + userId + ":" + requestId(input.requestId());
        String reason = required(input.reason(), 500);
        String workspace = input.workspaceId() == null || input.workspaceId().isBlank() ? null : input.workspaceId().trim();
        return tx.execute(status -> {
            jdbc.queryForMap("SELECT user_id FROM ai_platform_account WHERE user_id=? FOR UPDATE",userId);
            List<Map<String, Object>> existing = jdbc.queryForList("SELECT * FROM ai_platform_allowance_request WHERE request_key=?", key);
            if (!existing.isEmpty()) {
                // Same idempotency key must not silently represent a different request.
                Map<String,Object> prior=existing.getFirst();
                boolean sameAmount=requested==null?prior.get("requested_amount")==null:prior.get("requested_amount")!=null&&requested.compareTo(amount(prior.get("requested_amount")))==0;
                if (!reason.equals(prior.get("reason"))||!sameAmount||!java.util.Objects.equals(workspace,prior.get("workspace_id"))) throw new RenException("IDEMPOTENCY_CONFLICT");
                return view(existing.getFirst());
            }
            if (workspace != null && jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_workspace WHERE id=? AND owner_user_id=? AND status=1",
                    Long.class, workspace, userId) == 0) {
                throw new RenException("WORKSPACE_OWNER_REQUIRED");
            }
            String id = id();
            jdbc.update("INSERT INTO ai_platform_allowance_request(id,user_id,workspace_id,request_key,requested_amount,reason,status,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    id, userId, workspace, key, requested, reason, PENDING, now());
            return view(jdbc.queryForMap("SELECT * FROM ai_platform_allowance_request WHERE id=?", id));
        });
    }

    /** A user only ever reads their own requests. */
    public Map<String, Object> mine(long userId, int page, int limit) {
        page(page, limit);
        String from = " FROM ai_platform_allowance_request WHERE user_id=?";
        return page(read("SELECT id,user_id AS userId,workspace_id AS workspaceId,requested_amount AS requestedAmount,"
                        + "granted_amount AS grantedAmount,reason,status,decision_reason AS decisionReason,"
                        + "created_at AS createdAt,decided_at AS decidedAt"
                        + from + " ORDER BY id DESC LIMIT ? OFFSET ?", userId, limit, (long) (page - 1) * limit),
                count("SELECT COUNT(*)" + from, userId));
    }

    /** Operator queue: oldest pending request first so users are served in order. */
    public Map<String, Object> pending(int page, int limit) {
        page(page, limit);
        String from = " FROM ai_platform_allowance_request WHERE status='" + PENDING + "'";
        return page(read("SELECT id,user_id AS userId,workspace_id AS workspaceId,requested_amount AS requestedAmount,reason,created_at AS createdAt"
                        + from + " ORDER BY created_at,id LIMIT ? OFFSET ?", limit, (long) (page - 1) * limit),
                count("SELECT COUNT(*)" + from));
    }

    public Map<String,Object> approve(String id,String operator,Decision input) {
        BigDecimal granted=positive(input.amount());String reason=required(input.reason(),500);requestId(input.requestId());
        return tx.execute(status->{
            jdbc.queryForMap("SELECT id FROM ai_platform_pool WHERE id='SERVICE' FOR UPDATE");
            Map<String,Object> row=locked(id);
            if("APPROVED".equals(row.get("status"))) {
                if(granted.compareTo(amount(row.get("granted_amount")))!=0||!reason.equals(row.get("decision_reason")))throw new RenException("IDEMPOTENCY_CONFLICT");
                return view(row);
            }
            if(!PENDING.equals(row.get("status")))throw new RenException("ALLOWANCE_REQUEST_ALREADY_DECIDED");
            accounts.grant(new xiaozhi.modules.platform.dto.PlatformRequests.Grant("allowance-request:"+id,number(row.get("user_id")),granted.toPlainString(),reason),operator);
            return markGranted(id,operator,granted,reason);
        });
    }

    /** Reject a pending request. Approval goes through {@link #markGranted} after a real grant succeeds. */
    public Map<String, Object> reject(String id, String operator, String reason) {
        String decision = required(reason, 500);
        return tx.execute(status -> {
            Map<String, Object> row = locked(id);
            if (!PENDING.equals(row.get("status"))) throw new RenException("ALLOWANCE_REQUEST_ALREADY_DECIDED");
            jdbc.update("UPDATE ai_platform_allowance_request SET status='REJECTED',operator_id=?,decision_reason=?,decided_at=? WHERE id=?",
                    operator, decision, now(), id);
            return view(jdbc.queryForMap("SELECT * FROM ai_platform_allowance_request WHERE id=?", id));
        });
    }

    /**
     * Marks a request approved after the caller has issued the credit in the same
     * transaction. The granted amount is supplied by the operator path, so a pool
     * rejection rolls the whole decision back and leaves the request pending.
     */
    private Map<String, Object> markGranted(String id, String operator, BigDecimal grantedAmount, String reason) {
        return tx.execute(status -> {
            Map<String, Object> row = locked(id);
            if (!PENDING.equals(row.get("status"))) throw new RenException("ALLOWANCE_REQUEST_ALREADY_DECIDED");
            jdbc.update("UPDATE ai_platform_allowance_request SET status='APPROVED',operator_id=?,granted_amount=?,decision_reason=?,decided_at=? WHERE id=?",
                    operator, grantedAmount, required(reason, 500), now(), id);
            return view(jdbc.queryForMap("SELECT * FROM ai_platform_allowance_request WHERE id=?", id));
        });
    }

    public Map<String, Object> get(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM ai_platform_allowance_request WHERE id=?", id);
        if (rows.isEmpty()) throw new RenException("ALLOWANCE_REQUEST_NOT_FOUND");
        return view(rows.getFirst());
    }

    public long pendingCount() {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_allowance_request WHERE status=?", Long.class, PENDING);
        return total == null ? 0L : total;
    }

    private Map<String, Object> locked(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM ai_platform_allowance_request WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new RenException("ALLOWANCE_REQUEST_NOT_FOUND");
        return rows.getFirst();
    }

    private static void page(int page, int limit) {
        if (page < 1 || limit < 1 || limit > 200 || page > 100000) throw new RenException("INVALID_PAGINATION");
    }

    private List<Map<String, Object>> read(String sql, Object... parameters) {
        return jdbc.queryForList(sql, parameters);
    }

    private long count(String sql, Object... parameters) {
        Long total = jdbc.queryForObject(sql, Long.class, parameters);
        return total == null ? 0L : total;
    }

    private static Map<String, Object> page(List<Map<String, Object>> list, long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return publicMap(result);
    }

    private static Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("userId", number(row.get("user_id")));
        result.put("workspaceId", row.get("workspace_id"));
        result.put("requestedAmount", row.get("requested_amount") == null ? null : decimal(row.get("requested_amount")));
        result.put("grantedAmount", row.get("granted_amount") == null ? null : decimal(row.get("granted_amount")));
        result.put("reason", row.get("reason"));
        result.put("status", row.get("status"));
        result.put("decisionReason", row.get("decision_reason"));
        result.put("createdAt", row.get("created_at"));
        result.put("decidedAt", row.get("decided_at"));
        return result;
    }
}
