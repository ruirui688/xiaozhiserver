package xiaozhi.modules.platform.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;

/** The allowance directory exposes account balances and usernames only. */
@Service
@RequiredArgsConstructor
public class PlatformAccountQueryService {
    private static final String FROM = " FROM ai_platform_account a LEFT JOIN sys_user u ON u.id=a.user_id";
    private final JdbcTemplate jdbc;

    public Map<String, Object> accounts(int page, int limit, String keyword) {
        if (page < 1 || page > 100000 || limit < 1 || limit > 200) {
            throw new RenException("INVALID_PAGINATION");
        }
        if (keyword != null && keyword.length() > 100) {
            throw new RenException("INVALID_ACCOUNT_KEYWORD");
        }
        String search = keyword == null ? "" : keyword.trim();
        String where = " WHERE a.user_id>0";
        List<Object> parameters = new ArrayList<>();
        if (!search.isEmpty()) {
            where += " AND (u.username LIKE ? ESCAPE '!'";
            parameters.add("%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            Long userId = userId(search);
            if (userId != null) {
                where += " OR a.user_id=?";
                parameters.add(userId);
            }
            where += ")";
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + FROM + where, Long.class, parameters.toArray());
        parameters.add(limit);
        parameters.add((long) (page - 1) * limit);
        var rows = jdbc.query("SELECT a.user_id,u.username,a.granted,a.spent,a.reserved,"
                        + "(a.granted-a.spent) AS remaining,(a.granted-a.spent-a.reserved) AS available,a.frozen" + FROM + where
                        + " ORDER BY a.user_id LIMIT ? OFFSET ?",
                (row, index) -> PlatformSupport.map(
                        // Read BIGINT as text before JSON serialization; JavaScript cannot preserve all IDs.
                        "userId", row.getString("user_id"), "username", row.getString("username"),
                        "granted", row.getBigDecimal("granted"), "spent", row.getBigDecimal("spent"),
                        "remaining", row.getBigDecimal("remaining"), "reserved", row.getBigDecimal("reserved"), "available", row.getBigDecimal("available"),
                        "frozen", row.getInt("frozen"), "status", row.getInt("frozen") == 1 ? "FROZEN" : "ACTIVE",
                        "currency", "CNY"), parameters.toArray());
        return PlatformSupport.publicMap(PlatformSupport.map("list", rows, "total", total, "page", page, "limit", limit));
    }

    private static Long userId(String search) {
        if (!search.matches("[0-9]{1,19}")) return null;
        try {
            long value = Long.parseLong(search);
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            // An oversized numeric username remains searchable without becoming a truncated ID.
            return null;
        }
    }
}
