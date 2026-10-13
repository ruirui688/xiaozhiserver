package xiaozhi.modules.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import xiaozhi.common.exception.RenException;

class PlatformAccountQueryServiceTest {
    private JdbcTemplate jdbc;
    private PlatformAccountQueryService queries;

    @BeforeEach
    void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:account-directory-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,username VARCHAR(50),"
                + "password VARCHAR(100),mobile VARCHAR(100),email VARCHAR(100))");
        String accountSchema = Files.readString(Path.of("src/main/resources/db/changelog/202610121610.sql"))
                .split(";", 2)[0].replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        jdbc.execute(accountSchema);
        queries = new PlatformAccountQueryService(jdbc);
    }

    @Test
    void serializesLargeIdsExactlyAndKeepsOrphanAccountsWithoutPrivateFields() throws Exception {
        account(9007199254740993L, "alice");
        account(Long.MAX_VALUE, "bob");
        account(42L, null);
        jdbc.update("UPDATE ai_platform_account SET frozen=1 WHERE user_id=42");
        Map<String, Object> page = queries.accounts(1, 20, null);
        assertEquals(3L, page.get("total"));
        assertEquals(Set.of("list", "total", "page", "limit"), page.keySet());
        assertEquals(List.of("42", "9007199254740993", "9223372036854775807"), ids(page));
        for (var row : rows(page)) {
            assertEquals(Set.of("userId", "username", "granted", "spent", "remaining", "reserved", "available", "frozen", "status", "currency"), row.keySet());
            assertEquals("100.000000000", row.get("granted"));
            assertEquals("75.000000000", row.get("available"));
            assertEquals("80.000000000", row.get("remaining"));
        }
        assertNull(rows(page).getFirst().get("username"));
        assertEquals("FROZEN", rows(page).getFirst().get("status"));
        assertEquals("alice", rows(page).get(1).get("username"));
        var json = new ObjectMapper().valueToTree(page);
        assertTrue(json.path("list").get(1).path("userId").isTextual());
        assertEquals("9007199254740993", json.path("list").get(1).path("userId").textValue());
        assertFalse(json.toString().contains("private-fixture"));
        assertEquals(0L, queries.accounts(1, 20, "private-fixture").get("total"));
    }

    @Test
    void filtersBeforePaginationAndCountAndCanReachPastFiveHundredAccounts() {
        for (long id = 1; id <= 520; id++) account(id, "member-" + id);
        account(521, "target-first");
        account(522, "target-second");
        var all = queries.accounts(27, 20, null);
        assertEquals(522L, all.get("total"));
        assertEquals(List.of("521", "522"), ids(all));
        var filtered = queries.accounts(2, 1, " target- ");
        assertEquals(2L, filtered.get("total"));
        assertEquals(2, filtered.get("page"));
        assertEquals(1, filtered.get("limit"));
        assertEquals(List.of("522"), ids(filtered));
        var empty = queries.accounts(3, 1, "target-");
        assertEquals(2L, empty.get("total"));
        assertTrue(rows(empty).isEmpty());
    }

    @Test
    void exactIdLookupPreservesOrphansAndDoesNotMatchIdPrefixes() {
        account(123, null);
        account(1234, "another");
        account(9007199254740993L, "large");
        account(Long.MAX_VALUE, "maximum");
        assertEquals(List.of("123"), ids(queries.accounts(1, 20, "123")));
        assertEquals(List.of("9007199254740993"), ids(queries.accounts(1, 20, "9007199254740993")));
        assertEquals(List.of("9223372036854775807"), ids(queries.accounts(1, 20, "9223372036854775807")));
    }

    @Test
    void searchesNumericUsernamesWithoutOverflowOrChangingIdMeaning() {
        account(10, "9223372036854775808");
        account(11, "123-team");
        account(123, "owner");
        assertEquals(List.of("10"), ids(queries.accounts(1, 20, "9223372036854775808")));
        assertEquals(List.of("11", "123"), ids(queries.accounts(1, 20, "123")));
    }

    @Test
    void treatsWildcardsAndSqlTextAsLiteralUsernameSearches() {
        account(1, "alice");
        account(2, "alice%_!team");
        account(3, "x' OR 1=1 --");
        assertEquals(List.of("2"), ids(queries.accounts(1, 20, "%_!")));
        assertEquals(List.of("3"), ids(queries.accounts(1, 20, "' OR 1=1 --")));
        assertEquals(0L, queries.accounts(1, 20, "missing").get("total"));
    }

    @Test
    void excludesPlatformServiceAccountsAndTreatsWhitespaceAsNoFilter() {
        account(-1, "service");
        account(0, "zero");
        account(1, "member");
        assertEquals(List.of("1"), ids(queries.accounts(1, 200, "  ")));
        assertEquals(0L, queries.accounts(1, 20, "service").get("total"));
        assertTrue(rows(queries.accounts(100000, 200, null)).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"0,20", "100001,20", "1,0", "1,201", "-1,20"})
    void rejectsInvalidPagination(int page, int limit) {
        assertEquals("INVALID_PAGINATION", assertThrows(RenException.class,
                () -> queries.accounts(page, limit, null)).getMessage());
    }

    @Test
    void boundsSearchLength() {
        assertEquals("INVALID_ACCOUNT_KEYWORD", assertThrows(RenException.class,
                () -> queries.accounts(1, 20, "a".repeat(101))).getMessage());
        assertEquals(0L, queries.accounts(1, 20, "a".repeat(100)).get("total"));
    }

    private void account(long id, String username) {
        jdbc.update("INSERT INTO ai_platform_account(user_id,granted,spent,reserved,updated_at)"
                + " VALUES(?,100,20,5,CURRENT_TIMESTAMP)", id);
        if (username != null) jdbc.update("INSERT INTO sys_user VALUES(?,?, 'private-fixture-password',"
                + "'private-fixture-mobile','private-fixture-email')", id, username);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("list");
    }

    private static List<String> ids(Map<String, Object> page) {
        return rows(page).stream().map(row -> (String) row.get("userId")).toList();
    }
}
