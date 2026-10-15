package xiaozhi.modules.platform.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.security.ops.OpsAccess;
import static xiaozhi.modules.platform.service.PlatformSupport.*;

/** Registration records a durable policy snapshot before committing; balances only change through the ledger. */
@Service @RequiredArgsConstructor
public class PlatformTrialGrantService {
    public static final String SYSTEM_OPERATOR = "SYSTEM:TRIAL";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PlatformTrialPolicy policy;
    private final PlatformTrialGrantWorker worker;

    public Map<String,Object> issue(long userId) {
        if (userId <= 0) throw new RenException("INVALID_USER");
        return tx.execute(status -> {
            if (grantedPolicy(userId) != null) return map("status", "ALREADY_GRANTED");
            enqueue(userId, "SYSTEM:REGISTRATION");
            // User, workspace and intent share one transaction. A crash before this callback is recoverable.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { worker.attempt(userId); }
            });
            return map("status", policy.isTrialEnabled() ? "SCHEDULED" : "DISABLED");
        });
    }

    private void enqueue(long userId, String actor) {
        BigDecimal amount = BigDecimal.ZERO;
        String version = "INVALID";
        String state = policy.isTrialEnabled() ? "PENDING" : "DISABLED";
        String reason = policy.isTrialEnabled() ? null : "POLICY_DISABLED";
        try {
            amount = positive(policy.getTrialAmount());
            version = required(policy.getTrialPolicy(), 64);
        } catch (RenException invalid) {
            if (policy.isTrialEnabled()) { state = "REVIEW"; reason = "POLICY_INVALID"; }
        }
        jdbc.update("INSERT INTO ai_platform_trial_grant(user_id,policy,amount,status,next_attempt_at,reason_code,requested_by,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE user_id=user_id",
                userId, version, amount, state, now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), reason, actor, now(), now());
    }

    /** Only explicitly selected users; all targets are authorized before any write. */
    public List<Map<String,Object>> retry(List<Long> requestedUsers, String reason) {
        if (requestedUsers == null || requestedUsers.isEmpty() || requestedUsers.size() > 100
                || requestedUsers.stream().anyMatch(id -> id == null || id <= 0)) throw new RenException("INVALID_TRIAL_TARGETS");
        required(reason, 500);
        List<Long> users = requestedUsers.stream().distinct().sorted().toList();
        String operator = OpsAccess.employee().getEmployeeId();
        for (Long user : users) {
            if (!OpsAccess.has("allowance.grant", "PLATFORM", "platform-services"))
                OpsAccess.require("allowance.grant", "USER", Long.toString(user));
        }
        if (!policy.isTrialEnabled()) throw new RenException("POLICY_DISABLED");
        positive(policy.getTrialAmount()); required(policy.getTrialPolicy(), 64);
        return tx.execute(status -> {
            jdbc.queryForMap("SELECT id FROM ai_platform_pool WHERE id='SERVICE' FOR UPDATE");
            for (Long user : users) {
                var active = jdbc.queryForList("SELECT status FROM sys_user WHERE id=?", Integer.class, user);
                if (active.isEmpty() || !Integer.valueOf(1).equals(active.getFirst())) throw new RenException("USER_UNAVAILABLE");
                jdbc.queryForMap("SELECT user_id FROM ai_platform_account WHERE user_id=? FOR UPDATE", user);
            }
            String batch = id();
            for (Long user : users) {
                jdbc.update("INSERT INTO ai_platform_trial_repair(batch_id,user_id,operator_id,reason,created_at) VALUES(?,?,?,?,?)",
                        batch, user, operator, reason, now());
                if (grantedPolicy(user) != null) continue;
                enqueue(user, operator);
                // Running workers own their leases; valid policy snapshots remain immutable on retries.
                jdbc.update("UPDATE ai_platform_trial_grant SET policy=?,amount=? WHERE user_id=? "
                        + "AND (status='DISABLED' OR (status='REVIEW' AND reason_code='POLICY_INVALID'))",
                        policy.getTrialPolicy(), positive(policy.getTrialAmount()), user);
                jdbc.update("UPDATE ai_platform_trial_grant SET status='PENDING',attempts=0,reason_code=NULL,next_attempt_at=?,requested_by=?,updated_at=? "
                        + "WHERE user_id=? AND status IN ('PENDING','REVIEW','DISABLED')", now(), operator, now(), user);
            }
            return users.stream().map(this::status).toList();
        });
    }

    public Map<String,Object> status(long userId) {
        String granted = grantedPolicy(userId);
        var jobs = jdbc.queryForList("SELECT policy,amount,status,attempts,next_attempt_at,reason_code FROM ai_platform_trial_grant WHERE user_id=?", userId);
        Map<String,Object> result = map("userId", Long.toString(userId), "enabled", policy.isTrialEnabled(), "policy", policy.getTrialPolicy(),
                "grantedPolicy", granted, "granted", granted != null, "status", granted != null ? "GRANTED" : "NOT_SCHEDULED");
        if (!jobs.isEmpty()) {
            var job = jobs.getFirst();
            result.put("policy", job.get("policy")); result.put("amount", decimal(job.get("amount")));
            result.put("status", granted != null ? "GRANTED" : job.get("status"));
            result.put("reasonCode", job.get("reason_code")); result.put("attempts", job.get("attempts"));
            result.put("nextAttemptAt", job.get("next_attempt_at"));
            result.put("paused", !policy.isTrialEnabled() && List.of("PENDING", "PROCESSING").contains(job.get("status")));
        }
        return result;
    }

    private String grantedPolicy(long userId) {
        var rows = jdbc.queryForList("SELECT trial_policy FROM ai_platform_account WHERE user_id=?", userId);
        return rows.isEmpty() || rows.getFirst().get("trial_policy") == null ? null : rows.getFirst().get("trial_policy").toString();
    }

    public long issuedUsers() {
        Long count = jdbc.queryForObject("SELECT issued_users FROM ai_platform_trial_gate WHERE id=1", Long.class);
        return count == null ? 0 : count;
    }

    public Map<String,Object> summary() {
        return map("enabled", policy.isTrialEnabled(), "policy", policy.getTrialPolicy(), "amount", policy.getTrialAmount(),
                "maxUsers", policy.getTrialMaxUsers(), "issuedUsers", issuedUsers());
    }

    public List<Map<String,Object>> requests(String state, long afterUserId, int limit) {
        if (!List.of("PENDING", "PROCESSING", "REVIEW", "DISABLED", "GRANTED").contains(state)) throw new RenException("INVALID_TRIAL_STATUS");
        var rows = jdbc.queryForList("SELECT user_id AS userId,policy,amount,status,attempts,reason_code AS reasonCode,"
                + "requested_by AS requestedBy,next_attempt_at AS nextAttemptAt,updated_at AS updatedAt FROM ai_platform_trial_grant "
                + "WHERE status=? AND user_id>? ORDER BY user_id LIMIT " + Math.max(1, Math.min(100, limit)), state, Math.max(0, afterUserId));
        rows.forEach(row -> { row.put("userId", row.get("userId").toString()); row.put("amount", decimal(row.get("amount"))); });
        return rows;
    }
}
