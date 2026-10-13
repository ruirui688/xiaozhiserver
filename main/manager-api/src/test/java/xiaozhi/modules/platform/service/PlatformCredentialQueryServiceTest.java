package xiaozhi.modules.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xiaozhi.common.exception.RenException;

class PlatformCredentialQueryServiceTest {
    private CredentialPoolFixture fixture;
    private PlatformCredentialQueryService queries;

    @BeforeEach void setup() throws Exception {
        fixture = new CredentialPoolFixture();
        queries = new PlatformCredentialQueryService(fixture.jdbc);
    }

    @Test void reachesBeyondHundredRowsAndFiltersBeforeCountAndPagination() {
        for (int index = 1; index <= 121; index++) row("row-" + index, "DEEPSEEK", "label-" + index, null, "INVENTORY");
        row("target-a", "VOLCENGINE", "target-a", null, "INVENTORY");
        row("target-b", "VOLCENGINE", "target-b", null, "INVENTORY");
        var last = queries.credentials(7, 20, null, null, null);
        assertEquals(123L, last.get("total"));
        assertEquals(3, rows(last).size());
        var page = queries.credentials(2, 1, "volcengine", "INVENTORY", "target");
        assertEquals(2L, page.get("total"));
        assertEquals(2, page.get("page"));
        assertEquals(1, page.get("limit"));
        assertEquals("target-b", rows(page).getFirst().get("id"));
    }

    @Test void exactLongUserIdsAndResponseWhitelistExcludeCredentialsAndPrivateMetadata() {
        fixture.user(9007199254740993L, "alice", 1);
        fixture.user(Long.MAX_VALUE, "bob", 1);
        row("alice-key", "DEEPSEEK", "alice-label", 9007199254740993L, "ACTIVE");
        row("bob-key", "VOLCENGINE", "bob-label", Long.MAX_VALUE, "ACTIVE");
        row("orphan-key", "VOLCENGINE", "orphan-label", 42L, "ROTATED");
        var result = queries.credentials(1, 20, null, null, "9007199254740993");
        assertEquals(1L, result.get("total"));
        assertEquals("9007199254740993", rows(result).getFirst().get("userId"));
        assertEquals("alice", rows(result).getFirst().get("username"));
        assertEquals(Set.of("id", "provider", "upstreamId", "userId", "username", "version", "status", "createdAt"), rows(result).getFirst().keySet());
        assertEquals("9223372036854775807", rows(queries.credentials(1, 20, null, null, "bob")).getFirst().get("userId"));
        assertNull(rows(queries.credentials(1, 20, null, null, "42")).getFirst().get("username"));
        assertFalse(fixture.json.json(queries.credentials(1, 20, null, null, null)).contains("private-"));
        assertEquals(0L, queries.credentials(1, 20, null, null, "private-").get("total"));
    }

    @Test void literalSearchAndHistoryAreBoundedToRequestedStatuses() {
        fixture.user(1L, "user%_!", 1);
        row("a", "DEEPSEEK", "x' OR 1=1 --", 1L, "ACTIVE");
        row("b", "DEEPSEEK", "old", null, "ROTATED");
        row("c", "VOLCENGINE", "revoked%_!", null, "REVOKED");
        assertEquals(2L, queries.credentials(1, 20, null, "HISTORY", null).get("total"));
        assertEquals(1L, queries.credentials(1, 20, "DEEPSEEK", "HISTORY", null).get("total"));
        assertEquals(2L, queries.credentials(1, 20, null, null, "%_!").get("total"));
        assertEquals(1L, queries.credentials(1, 20, null, null, "' OR 1=1 --").get("total"));
        assertEquals(0L, queries.credentials(1, 20, null, "INVENTORY", null).get("total"));
    }

    @Test void pendingIncludesHistoricalUsersWithNoPointerAndKeepsSummaryAlertsConsistent() {
        fixture.user(1, "old-member", 1);
        fixture.user(2, "ready-member", 1);
        fixture.user(3, "revoked-member", 1);
        fixture.user(4, "disabled", 0);
        fixture.user(-1, "platform-service", 1);
        fixture.user(9007199254740993L, "large-member", 1);
        row("ready-deepseek", "DEEPSEEK", "ready", 2L, "ACTIVE");
        row("revoked-key", "DEEPSEEK", "revoked", 3L, "REVOKED");
        fixture.jdbc.update("INSERT INTO ai_platform_user_credential VALUES(2,'DEEPSEEK','ready-deepseek'),(3,'DEEPSEEK','revoked-key')");
        var deepseek = queries.pending(1, 20, "DEEPSEEK", null);
        assertEquals(3L, deepseek.get("total"));
        assertEquals(Set.of("1", "3", "9007199254740993"), rows(deepseek).stream().map(row -> row.get("userId")).collect(java.util.stream.Collectors.toSet()));
        assertEquals(7L, queries.pending(1, 20, null, null).get("total"));
        assertEquals(2L, queries.pending(1, 20, null, "old-").get("total"));
        assertEquals(1, rows(queries.pending(2, 1, null, "old-")).size());
        var summary = new PlatformCredentialInventoryService(fixture.jdbc).summary();
        for (var provider : list(summary, "providers")) {
            String vendor = provider.get("provider").toString();
            assertEquals(queries.pending(1, 20, vendor, null).get("total"), provider.get("pendingUsers"));
            var alert = list(summary, "alerts").stream().filter(row -> vendor.equals(row.get("provider"))
                    && "CREDENTIAL_PENDING_USERS".equals(row.get("code"))).findFirst().orElseThrow();
            assertEquals(provider.get("pendingUsers"), alert.get("waiting"));
        }
        assertEquals(2L, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_user_credential", Long.class));
    }

    @Test void userPickerOnlySelectsMetadataFromActivePositivePlatformAccounts() {
        fixture.user(9007199254740993L, "user%_!", 1);
        fixture.user(2, "disabled", 0);
        fixture.user(-2, "service", 1);
        fixture.jdbc.update("INSERT INTO sys_user(id,username,status) VALUES(3,'no-platform-account',1)");
        var found = queries.users(1, 20, "%_!");
        assertEquals(1L, found.get("total"));
        assertEquals(Map.of("userId", "9007199254740993", "username", "user%_!"), rows(found).getFirst());
        assertEquals(1L, queries.users(1, 20, null).get("total"));
        assertEquals(1L, queries.users(1, 20, "9007199254740993").get("total"));
        assertEquals(0L, queries.users(1, 20, "9223372036854775808").get("total"));
    }

    @Test void invalidInputNeverBecomesSql() {
        assertThrows(RenException.class, () -> queries.credentials(0, 20, null, null, null));
        assertThrows(RenException.class, () -> queries.credentials(1, 201, null, null, null));
        assertThrows(RenException.class, () -> queries.pending(100001, 20, null, null));
        assertThrows(RenException.class, () -> queries.users(1, 20, "a".repeat(201)));
        assertThrows(RenException.class, () -> queries.credentials(1, 20, "x' OR 1=1", null, null));
        assertThrows(RenException.class, () -> queries.credentials(1, 20, null, "INVALID", null));
    }

    private void row(String id, String provider, String label, Long userId, String status) {
        fixture.jdbc.update("INSERT INTO ai_platform_credential(id,provider,upstream_id,user_id,fingerprint,secret_ciphertext,masked_json,status,created_at)"
                + " VALUES(?,?,?,?,?,'private-cipher','private-mask',?,'2026-10-12 00:00:00')", id, provider, label, userId, PlatformSupport.hash(id), status);
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> list(Map<String,Object> value,String key) { return (List<Map<String,Object>>) value.get(key); }
    private static List<Map<String,Object>> rows(Map<String,Object> value) { return list(value,"list"); }
}
