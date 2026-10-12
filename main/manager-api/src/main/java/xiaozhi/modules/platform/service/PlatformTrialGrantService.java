package xiaozhi.modules.platform.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.platform.dto.PlatformRequests.Grant;

import static xiaozhi.modules.platform.service.PlatformSupport.*;

/**
 * Registration trial allowance. The trial is a policy grant, never a payment:
 * it consumes the same issuance pool as an operator grant and refuses to issue
 * when the pool is empty instead of blocking account creation.
 *
 * <p>Each user consumes at most one trial across all policy versions, so changing
 * the policy amount never re-grants an existing user. The consumed policy is
 * persisted on the account row to keep the decision auditable without reading
 * ledger reasons.
 *
 * <p>The account row is locked before issuing, which makes concurrent
 * registrations of the same user single-issue. The grant reuses the operator
 * grant path so pool accounting, the idempotency ledger and the daily issuance
 * cap stay in one place.
 */
@Service
@RequiredArgsConstructor
public class PlatformTrialGrantService {
    /** Ledger actor for policy-issued credit; not an employee id and not a tenant user. */
    public static final String SYSTEM_OPERATOR = "SYSTEM:TRIAL";
    private static final String CLAIM_KEY = "TRIAL_CLAIM";

    private final JdbcTemplate jdbc;
    private final PlatformAccountService accounts;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    @Value("${xiaozhi.platform.trial-enabled:false}")
    private boolean enabled = false;
    @Value("${xiaozhi.platform.trial-amount:0}")
    private String trialAmount = "0";
    @Value("${xiaozhi.platform.trial-policy:trial-v1}")
    private String policy = "trial-v1";
    @Value("${xiaozhi.platform.trial-max-users:0}")
    private long maxUsers = 0;

    /** Registration commits independently; an exhausted pool cannot roll the user back. */
    public Map<String, Object> issue(long userId) {
        if (!enabled || userId <= 0) return outcome("DISABLED", null);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { issueCommitted(userId); }
                });
            return outcome("SCHEDULED", null);
        }
        return issueCommitted(userId);
    }
    private Map<String,Object> issueCommitted(long userId) {
        try {
            var independent=new org.springframework.transaction.support.TransactionTemplate(tx.getTransactionManager());
            independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return independent.execute(status->{
                // All grants use pool -> account order; this also serializes the global trial cap.
                jdbc.queryForMap("SELECT id FROM ai_platform_pool WHERE id='SERVICE' FOR UPDATE");
                jdbc.queryForMap("SELECT user_id FROM ai_platform_account WHERE user_id=? FOR UPDATE",userId);
                return issueLocked(userId);
            });
        } catch (RuntimeException unavailable) { return outcome("DEFERRED",null); }
    }

    private Map<String, Object> issueLocked(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT trial_policy FROM ai_platform_account WHERE user_id=?", userId);
        Object current = rows.isEmpty() ? null : rows.getFirst().get("trial_policy");
        if (current != null) return outcome("ALREADY_GRANTED", current.toString());
        if (maxUsers > 0 && issuedUsers() >= maxUsers) return outcome("TRIAL_CAP_REACHED", null);
        BigDecimal amount;
        try {
            amount = amount(trialAmount);
        } catch (RenException invalid) {
            return outcome("POLICY_INVALID", null);
        }
        if (amount.signum() <= 0) return outcome("POLICY_INVALID", null);
        String effectivePolicy = required(policy, 64);
        accounts.grant(new Grant(requestId(CLAIM_KEY + ":" + userId), userId,
                amount.toPlainString(), "TRIAL:" + effectivePolicy), SYSTEM_OPERATOR);
        jdbc.update("UPDATE ai_platform_account SET trial_policy=?,updated_at=? WHERE user_id=?", effectivePolicy, now(), userId);
        return outcome("GRANTED", effectivePolicy);
    }

    /** Read-only trial state for a user, used by the user-facing allowance page. */
    public Map<String, Object> status(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT trial_policy FROM ai_platform_account WHERE user_id=?", userId);
        Object granted = rows.isEmpty() ? null : rows.getFirst().get("trial_policy");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("policy", policy);
        result.put("grantedPolicy", granted);
        result.put("granted", granted != null);
        return result;
    }

    /** Operators need a bounded view of trial adoption; never exposes a user's content. */
    public long issuedUsers() {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_account WHERE trial_policy IS NOT NULL", Long.class);
        return total == null ? 0L : total;
    }

    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("policy", policy);
        result.put("amount", enabled ? decimal(trialAmount) : "0");
        result.put("maxUsers", maxUsers);
        result.put("issuedUsers", issuedUsers());
        return result;
    }

    private static Map<String, Object> outcome(String status, String appliedPolicy) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        if (appliedPolicy != null) result.put("policy", appliedPolicy);
        return result;
    }
}
