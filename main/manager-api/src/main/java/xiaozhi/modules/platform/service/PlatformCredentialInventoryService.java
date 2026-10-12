package xiaozhi.modules.platform.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;

import static xiaozhi.modules.platform.service.PlatformSupport.*;

/**
 * Credential inventory visibility. With one hosted key per user the product has to
 * watch supplier key quotas: when the {@code INVENTORY} pool runs dry, new users are
 * created as {@code PENDING_CREDENTIAL} and stop having model access until an
 * operator imports more keys. This service only reports counts and timestamps,
 * never a secret, a user's content or a per-user usage figure.
 */
@Service
@RequiredArgsConstructor
public class PlatformCredentialInventoryService {
    private final JdbcTemplate jdbc;

    @Value("${xiaozhi.platform.credential-min-inventory:0}")
    private long minimumInventory = 0;

    private static final List<String> PROVIDERS = List.of("DEEPSEEK", "VOLCENGINE");

    public Map<String, Object> summary() {
        List<Map<String, Object>> providers = new ArrayList<>();
        for (String provider : PROVIDERS) {
            long inventory = count("SELECT COUNT(*) FROM ai_platform_credential WHERE provider=? AND status='INVENTORY' AND user_id IS NULL", provider);
            long active = count("SELECT COUNT(*) FROM ai_platform_credential WHERE provider=? AND status='ACTIVE' AND user_id IS NOT NULL", provider);
            long pending = count("SELECT COUNT(*) FROM ai_platform_user_credential WHERE provider=? AND credential_id IS NULL", provider);
            long rotated = count("SELECT COUNT(*) FROM ai_platform_credential WHERE provider=? AND status='ROTATED'", provider);
            providers.add(map("provider", provider, "inventory", inventory, "active", active,
                    "pendingUsers", pending, "rotated", rotated,
                    "lowInventory", minimumInventory > 0 && inventory < minimumInventory));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("minimumInventory", minimumInventory);
        result.put("providers", providers);
        result.put("alerts", alerts());
        return result;
    }

    /** Waiting users ordered by who has waited longest, so support can act in queue order. */
    public Map<String, Object> pending(int page, int limit) {
        if (page < 1 || limit < 1 || limit > 200 || page > 100000) throw new RenException("INVALID_PAGINATION");
        String from = " FROM ai_platform_user_credential p JOIN sys_user u ON u.id=p.user_id WHERE p.credential_id IS NULL";
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT p.user_id AS \"userId\",p.provider AS \"provider\",u.username AS \"username\",u.create_date AS \"registeredAt\""
                        + from + " ORDER BY u.create_date,p.user_id LIMIT ? OFFSET ?",
                limit, (long) (page - 1) * limit);
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", publicValue(list));
        result.put("total", total == null ? 0L : total);
        return result;
    }

    /** Bounded operational alerts; safe to surface on the ops home page. */
    public List<Map<String, Object>> alerts() {
        List<Map<String, Object>> alerts = new ArrayList<>();
        if (minimumInventory > 0) {
            // A provider with zero inventory rows must still alert; iterate the known
            // providers instead of the grouped rows, which cannot express "none left".
            for (String provider : PROVIDERS) {
                long inventory = count("SELECT COUNT(*) FROM ai_platform_credential WHERE provider=? AND status='INVENTORY' AND user_id IS NULL", provider);
                if (inventory < minimumInventory) {
                    alerts.add(map("code", "CREDENTIAL_INVENTORY_LOW", "provider", provider,
                            "inventory", inventory, "minimum", minimumInventory));
                }
            }
        }
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT provider,COUNT(*) AS waiting FROM ai_platform_user_credential WHERE credential_id IS NULL GROUP BY provider")) {
            alerts.add(map("code", "CREDENTIAL_PENDING_USERS", "provider", row.get("provider"),
                    "waiting", number(row.get("waiting"))));
        }
        return alerts;
    }

    private long count(String sql, Object... parameters) {
        Long total = jdbc.queryForObject(sql, Long.class, parameters);
        return total == null ? 0L : total;
    }
}
