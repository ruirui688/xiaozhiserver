package xiaozhi.modules.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.platform.dto.PlatformRequests.Grant;
import static xiaozhi.modules.platform.service.PlatformSupport.*;

/** Durable bounded work; no external calls, executor queue or timer per user. */
@Service @RequiredArgsConstructor @Slf4j
public class PlatformTrialGrantWorker {
    static final int MAX_ATTEMPTS = 12;
    static final int BATCH_SIZE = 10;
    private static final Set<String> SAFE_REASONS = Set.of("ISSUANCE_POOL_EXHAUSTED", "OPERATOR_DAILY_GRANT_LIMIT",
            "GRANT_LIMIT_EXCEEDED", "ACCOUNT_FROZEN", "USER_UNAVAILABLE", "TRIAL_CAP_REACHED", "POLICY_INVALID", "IDEMPOTENCY_CONFLICT");
    private final JdbcTemplate jdbc;
    private final PlatformAccountService accounts;
    private final TransactionTemplate tx;
    private final PlatformTrialPolicy policy;

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void dispatch() {
        if (!policy.isTrialEnabled()) return;
        try {
            LocalDateTime time = now();
            int gate = independent(() -> jdbc.update("UPDATE ai_platform_trial_gate SET next_scan_at=? WHERE id=1 AND next_scan_at<=?", time.plusSeconds(30), time));
            if (gate == 0) return;
            // Separate exact-status index ranges avoid filesorting an unbounded combined backlog.
            var due = new java.util.ArrayList<Due>(BATCH_SIZE * 2);
            for (String state : List.of("PENDING", "PROCESSING"))
                due.addAll(jdbc.query("SELECT user_id,next_attempt_at FROM ai_platform_trial_grant "
                        + "WHERE status=? AND next_attempt_at<=? ORDER BY next_attempt_at,user_id LIMIT " + BATCH_SIZE,
                        (row, index) -> new Due(row.getLong("user_id"), row.getTimestamp("next_attempt_at").toLocalDateTime()), state, time));
            due.stream().sorted(java.util.Comparator.comparing(Due::at).thenComparingLong(Due::userId)).limit(BATCH_SIZE)
                    .forEach(row -> attempt(row.userId()));
        } catch (RuntimeException unavailable) {
            log.warn("platform_trial_dispatch_failed code=STORAGE_UNAVAILABLE");
        }
    }

    public void attempt(long userId) {
        if (!policy.isTrialEnabled()) return; // Pause, do not discard pending intents.
        String lease = UUID.randomUUID().toString();
        try {
            LocalDateTime time = now();
            int claimed = independent(() -> {
                jdbc.update("UPDATE ai_platform_trial_grant SET status='REVIEW',reason_code='RETRY_EXHAUSTED',lease_token=NULL,updated_at=? "
                        + "WHERE user_id=? AND status IN ('PENDING','PROCESSING') AND attempts>=? AND next_attempt_at<=?", time, userId, MAX_ATTEMPTS, time);
                return jdbc.update("UPDATE ai_platform_trial_grant SET status='PROCESSING',attempts=attempts+1,lease_token=?,next_attempt_at=?,updated_at=? "
                        + "WHERE user_id=? AND status IN ('PENDING','PROCESSING') AND attempts<? AND next_attempt_at<=?",
                        lease, time.plusSeconds(120), time, userId, MAX_ATTEMPTS, time);
            });
            if (claimed == 0) return;
            try { independent(() -> { grantLocked(userId, lease); return null; }); }
            catch (RuntimeException error) { recordFailure(userId, lease, safeReason(error)); }
        } catch (RuntimeException unavailable) {
            // A committed claim has a recoverable lease; an unclaimed intent stays PENDING.
            log.warn("platform_trial_attempt_failed userId={} code=STORAGE_UNAVAILABLE", userId);
        }
    }

    private void grantLocked(long userId, String lease) {
        // Match all existing grants: pool -> account. Job-only claims commit before taking these locks.
        jdbc.queryForMap("SELECT id FROM ai_platform_pool WHERE id='SERVICE' FOR UPDATE");
        Map<String,Object> account = accounts.lock(userId);
        Map<String,Object> job = jdbc.queryForMap("SELECT * FROM ai_platform_trial_grant WHERE user_id=? FOR UPDATE", userId);
        if (!lease.equals(job.get("lease_token")) || !"PROCESSING".equals(job.get("status"))) return;
        if (account.get("trial_policy") != null) { complete(userId); return; }
        List<Integer> active = jdbc.queryForList("SELECT status FROM sys_user WHERE id=?", Integer.class, userId);
        if (active.isEmpty() || !Integer.valueOf(1).equals(active.getFirst())) throw new RenException("USER_UNAVAILABLE");
        if (number(account.get("frozen")) != 0) throw new RenException("ACCOUNT_FROZEN");
        long grantedUsers = jdbc.queryForObject("SELECT issued_users FROM ai_platform_trial_gate WHERE id=1 FOR UPDATE", Long.class);
        if (policy.getTrialMaxUsers() > 0 && grantedUsers >= policy.getTrialMaxUsers()) throw new RenException("TRIAL_CAP_REACHED");
        if (amount(job.get("amount")).signum() <= 0) throw new RenException("POLICY_INVALID");
        String appliedPolicy = required((String) job.get("policy"), 64);
        accounts.grant(new Grant(requestId("TRIAL_CLAIM:" + userId), userId,
                amount(job.get("amount")).toPlainString(), "TRIAL:" + appliedPolicy), PlatformTrialGrantService.SYSTEM_OPERATOR);
        jdbc.update("UPDATE ai_platform_account SET trial_policy=?,updated_at=? WHERE user_id=?", appliedPolicy, now(), userId);
        jdbc.update("UPDATE ai_platform_trial_gate SET issued_users=issued_users+1 WHERE id=1");
        complete(userId);
    }

    private void complete(long userId) {
        jdbc.update("UPDATE ai_platform_trial_grant SET status='GRANTED',reason_code=NULL,lease_token=NULL,updated_at=? WHERE user_id=?", now(), userId);
    }

    private void recordFailure(long userId, String lease, String reason) {
        independent(() -> {
            var jobs = jdbc.queryForList("SELECT attempts FROM ai_platform_trial_grant WHERE user_id=? AND lease_token=? FOR UPDATE", userId, lease);
            if (jobs.isEmpty()) return null;
            int attempts = ((Number) jobs.getFirst().get("attempts")).intValue();
            boolean review = attempts >= MAX_ATTEMPTS || Set.of("ACCOUNT_FROZEN", "USER_UNAVAILABLE", "POLICY_INVALID",
                    "GRANT_LIMIT_EXCEEDED", "IDEMPOTENCY_CONFLICT").contains(reason);
            LocalDateTime next = "OPERATOR_DAILY_GRANT_LIMIT".equals(reason)
                    ? now().toLocalDate().plusDays(1).atStartOfDay().plusSeconds(30)
                    : now().plusSeconds(Math.min(3600L, 30L << Math.min(attempts - 1, 7)));
            jdbc.update("UPDATE ai_platform_trial_grant SET status=?,reason_code=?,next_attempt_at=?,lease_token=NULL,updated_at=? WHERE user_id=? AND lease_token=?",
                    review ? "REVIEW" : "PENDING", reason, next, now(), userId, lease);
            return null;
        });
    }

    private String safeReason(RuntimeException error) {
        return error instanceof RenException && SAFE_REASONS.contains(error.getMessage()) ? error.getMessage() : "TRANSIENT_ERROR";
    }

    private <T> T independent(Supplier<T> operation) {
        TransactionTemplate independent = new TransactionTemplate(tx.getTransactionManager());
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setTimeout(10);
        return independent.execute(status -> operation.get());
    }

    private record Due(long userId, LocalDateTime at) {}
}
