package xiaozhi.modules.platform.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;

import static xiaozhi.modules.platform.service.PlatformSupport.map;

/** Bounded metadata directories: no secret, fingerprint, balance or user content is selected. */
@Service
@RequiredArgsConstructor
public class PlatformCredentialQueryService {
    private final JdbcTemplate jdbc;
    // Shared by queue, counts and alerts; historical users without a pointer still need a key.
    static final String PENDING_FROM = " FROM ai_platform_account a JOIN sys_user u ON u.id=a.user_id"
            + " CROSS JOIN (SELECT 'DEEPSEEK' AS provider UNION ALL SELECT 'VOLCENGINE') v";
    static final String PENDING_WHERE = " AND a.user_id>0 AND u.status=1 AND NOT EXISTS"
            + " (SELECT 1 FROM ai_platform_user_credential p JOIN ai_platform_credential c ON c.id=p.credential_id"
            + " WHERE p.user_id=a.user_id AND p.provider=v.provider AND c.user_id=a.user_id"
            + " AND c.provider=v.provider AND c.status='ACTIVE')";

    public Map<String, Object> credentials(int page, int limit, String provider, String status, String keyword) {
        Filter filter = new Filter(page, limit, keyword);
        filter.provider("c.provider", provider);
        if (status != null && !status.isBlank()) {
            String state = status.trim().toUpperCase(Locale.ROOT);
            if ("HISTORY".equals(state)) filter.where += " AND c.status IN ('ROTATED','REVOKED')";
            else if (Set.of("INVENTORY", "ACTIVE", "ROTATED", "REVOKED").contains(state)) {
                filter.where += " AND c.status=?";
                filter.parameters.add(state);
            } else throw new RenException("INVALID_CREDENTIAL_STATUS");
        }
        filter.search("u.username", "c.user_id", "c.upstream_id");
        return select(" FROM ai_platform_credential c LEFT JOIN sys_user u ON u.id=c.user_id", filter,
                "c.id,c.provider,c.upstream_id,c.user_id,u.username,c.version,c.status,c.created_at",
                "c.created_at DESC,c.id", (row, index) -> map("id", row.getString("id"),
                        "provider", row.getString("provider"), "upstreamId", row.getString("upstream_id"),
                        "userId", row.getString("user_id"), "username", row.getString("username"),
                        "version", row.getLong("version"), "status", row.getString("status"),
                        "createdAt", row.getTimestamp("created_at")));
    }

    public Map<String, Object> pending(int page, int limit, String provider, String keyword) {
        Filter filter = new Filter(page, limit, keyword);
        filter.where += PENDING_WHERE;
        filter.provider("v.provider", provider);
        filter.search("u.username", "a.user_id", null);
        return select(PENDING_FROM, filter,
                "a.user_id,v.provider,u.username,u.create_date", "u.create_date,a.user_id,v.provider",
                (row, index) -> map("userId", row.getString("user_id"), "provider", row.getString("provider"),
                        "username", row.getString("username"), "registeredAt", row.getTimestamp("create_date")));
    }

    /** User selection for credential assignment does not require or expose allowance permission. */
    public Map<String, Object> users(int page, int limit, String keyword) {
        Filter filter = new Filter(page, limit, keyword);
        filter.where += " AND u.id>0 AND u.status=1";
        filter.search("u.username", "u.id", null);
        return select(" FROM sys_user u JOIN ai_platform_account a ON a.user_id=u.id", filter, "u.id,u.username", "u.id",
                (row, index) -> map("userId", row.getString("id"), "username", row.getString("username")));
    }

    private Map<String, Object> select(String from, Filter filter, String fields, String order,
                                       RowMapper<Map<String, Object>> mapper) {
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + filter.where,
                Long.class, filter.parameters.toArray());
        List<Object> arguments = new ArrayList<>(filter.parameters);
        arguments.add(filter.limit);
        arguments.add((long) (filter.page - 1) * filter.limit);
        var rows = jdbc.query("SELECT " + fields + from + filter.where + " ORDER BY " + order
                + " LIMIT ? OFFSET ?", mapper, arguments.toArray());
        return map("list", rows, "total", total == null ? 0L : total, "page", filter.page, "limit", filter.limit);
    }

    private static final class Filter {
        final int page;
        final int limit;
        final String keyword;
        final List<Object> parameters = new ArrayList<>();
        String where = " WHERE 1=1";

        Filter(int page, int limit, String keyword) {
            if (page < 1 || page > 100000 || limit < 1 || limit > 200) throw new RenException("INVALID_PAGINATION");
            if (keyword != null && keyword.length() > 200) throw new RenException("INVALID_CREDENTIAL_KEYWORD");
            this.page = page;
            this.limit = limit;
            this.keyword = keyword == null ? "" : keyword.trim();
        }

        void provider(String field, String provider) {
            if (provider != null && !provider.isBlank()) {
                where += " AND " + field + "=?";
                parameters.add(PlatformCredentialService.provider(provider));
            }
        }

        void search(String username, String id, String label) {
            if (keyword.isEmpty()) return;
            String literal = "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            where += " AND (" + username + " LIKE ? ESCAPE '!'";
            parameters.add(literal);
            if (label != null) {
                where += " OR " + label + " LIKE ? ESCAPE '!'";
                parameters.add(literal);
            }
            if (keyword.matches("[0-9]{1,19}")) {
                try {
                    long value = Long.parseLong(keyword);
                    if (value > 0) { where += " OR " + id + "=?"; parameters.add(value); }
                } catch (NumberFormatException ignored) {
                    // Oversized numeric usernames remain text; no lossy ID conversion.
                }
            }
            where += ")";
        }
    }
}
