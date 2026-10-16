package xiaozhi.modules.security.ops;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class OpsThreeRoleMigrationTest {
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    @BeforeEach void setup() {
        source = new DriverManagerDataSource("jdbc:h2:mem:role-move-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE ops_employee(id VARCHAR(36) PRIMARY KEY, username VARCHAR(100), status INT)");
        jdbc.execute("CREATE TABLE ops_role_grant(id VARCHAR(36) PRIMARY KEY, employee_id VARCHAR(36), role_code VARCHAR(64), scope_type VARCHAR(32), scope_id VARCHAR(100), expires_at TIMESTAMP, status INT DEFAULT 1)");
        jdbc.execute("CREATE TABLE ops_session(id VARCHAR(36) PRIMARY KEY, employee_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE ops_audit_event(id VARCHAR(36) PRIMARY KEY, action VARCHAR(80), target_id VARCHAR(100), outcome VARCHAR(32))");
        jdbc.update("INSERT INTO ops_employee VALUES('resources','ops-resources',1),('finance','ops-finance',1),('keys','ops-keys',1),('other','another-operator',1)");
        for (String employee : Set.of("resources", "finance", "keys", "other")) jdbc.update("INSERT INTO ops_session VALUES(?,?)", employee, employee);
        grant("pool", "resources", "ALLOWANCE_POOL", "platform-services");
        grant("content", "resources", "OFFICIAL_CONTENT", "official-resources");
        grant("review", "finance", "ALLOWANCE_REVIEW", "platform-services");
        grant("supplier", "finance", "SUPPLIER_FINANCE", "platform-services");
        grant("other-pool", "other", "ALLOWANCE_POOL", "platform-services");
    }
    @Test void movesOnlyPoolPreservesHistoryRevokesOnlyAffectedSessionsAndIsRepeatable() throws Exception {
        migrate();
        assertEquals(0, status("pool")); assertEquals(0, status("supplier")); assertEquals(1, status("review"));
        assertEquals(1, count("SELECT COUNT(*) FROM ops_role_grant WHERE employee_id='keys' AND role_code='SUPPLIER_FINANCE' AND status=1")); assertEquals(1, status("content")); assertEquals(1, status("other-pool"));
        assertEquals(1, count("SELECT COUNT(*) FROM ops_role_grant WHERE employee_id='keys' AND role_code='ALLOWANCE_POOL' AND status=1"));
        assertEquals(Set.of("other"), Set.copyOf(jdbc.queryForList("SELECT employee_id FROM ops_session", String.class)));
        assertEquals(3, count("SELECT COUNT(*) FROM ops_audit_event"));
        migrate();
        assertEquals(7, count("SELECT COUNT(*) FROM ops_role_grant")); assertEquals(3, count("SELECT COUNT(*) FROM ops_audit_event"));
    }
    @Test void missingDisabledOrConflictingModelOperatorDoesNotRemoveExistingAccess() throws Exception {
        jdbc.update("UPDATE ops_employee SET status=0 WHERE id='keys'"); migrate(); assertUnchanged();
        jdbc.update("UPDATE ops_employee SET status=1 WHERE id='keys'");
        for (String role : Set.of("ALLOWANCE_REVIEW", "MODEL_REVIEW", "IAM_REQUEST", "IAM_APPROVE")) {
            grant("conflict", "keys", role, "platform-services"); migrate(); assertUnchanged();
            jdbc.update("DELETE FROM ops_role_grant WHERE id='conflict'");
        }
        jdbc.update("DELETE FROM ops_employee WHERE id='keys'"); migrate(); assertUnchanged();
    }
    @Test void preservesGrantExpiryAndIgnoresExpiredOrWrongScopeGrants() throws Exception {
        jdbc.update("UPDATE ops_role_grant SET expires_at=TIMESTAMP '2099-01-01 00:00:00' WHERE id='pool'");
        grant("wrong-scope", "resources", "ALLOWANCE_POOL", "unexpected");
        grant("expired", "resources", "ALLOWANCE_POOL", "platform-services");
        jdbc.update("UPDATE ops_role_grant SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE id='expired'"); migrate();
        assertEquals(jdbc.queryForObject("SELECT expires_at FROM ops_role_grant WHERE id='pool'", java.sql.Timestamp.class), jdbc.queryForObject("SELECT expires_at FROM ops_role_grant WHERE employee_id='keys' AND role_code='ALLOWANCE_POOL'", java.sql.Timestamp.class));
        assertEquals(1, status("wrong-scope")); assertEquals(1, status("expired"));
    }
    @Test void movesLegacyFinancePoolAndDoesNotDuplicateExistingTargetGrant() throws Exception {
        jdbc.update("UPDATE ops_role_grant SET employee_id='finance' WHERE id='pool'");
        grant("existing-pool", "keys", "ALLOWANCE_POOL", "platform-services");
        migrate();
        assertEquals(0, status("pool")); assertEquals(1, status("existing-pool"));
        assertEquals(1, count("SELECT COUNT(*) FROM ops_role_grant WHERE employee_id='keys' AND role_code='ALLOWANCE_POOL' AND status=1"));
        assertEquals(Set.of("resources", "other"), Set.copyOf(jdbc.queryForList("SELECT employee_id FROM ops_session", String.class)));
    }
    @Test void expiredTargetGrantDoesNotSuppressActiveTransfer() throws Exception {
        grant("expired-target", "keys", "ALLOWANCE_POOL", "platform-services");
        jdbc.update("UPDATE ops_role_grant SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE id='expired-target'");
        migrate();
        assertEquals(0, status("pool"));
        assertEquals(1, count("SELECT COUNT(*) FROM ops_role_grant WHERE employee_id='keys' AND role_code='ALLOWANCE_POOL' AND status=1 AND expires_at IS NULL"));
    }
    @Test void threeBusinessRolesAreCompatibleButExecutionAndReviewStaySeparated() {
        var roles = java.util.List.of(Set.of("ALLOWANCE", "CREDENTIALS", "RATES", "MODEL_CATALOG", "SUPPORT", "ALLOWANCE_POOL", "SUPPLIER_FINANCE"), Set.of("OFFICIAL_CONTENT", "OTA", "OPERATIONS"), Set.of("ALLOWANCE_REVIEW", "MODEL_REVIEW", "CONTENT_REVIEW", "OTA_REVIEW", "AUDIT"));
        roles.forEach(group -> assertDoesNotThrow(() -> OpsPermissionCatalog.requireCompatible(group)));
        try (var security = org.mockito.Mockito.mockStatic(xiaozhi.modules.security.user.SecurityUser.class)) {
            var employee = new xiaozhi.common.user.UserDetail();
            employee.setAudience("OPS"); employee.setEmployeeId("test-employee");
            security.when(xiaozhi.modules.security.user.SecurityUser::getUser).thenReturn(employee);
            for (int index = 0; index < roles.size(); index++) {
                employee.setGrants(roles.get(index).stream().map(role -> {
                    var template = OpsPermissionCatalog.TEMPLATES.get(role);
                    return new OpsGrant(role, "PLATFORM", template.domain(), template.permissions());
                }).toList());
                assertEquals(index == 0, OpsAccess.has("allowance.grant", "PLATFORM", "platform-services"));
                assertEquals(index == 1, OpsAccess.has("ota.publish", "PLATFORM", "firmware"));
                assertEquals(index == 0, OpsAccess.has("pool.adjust", "PLATFORM", "platform-services"));
                assertEquals(index == 2, OpsAccess.has("ota.approve", "PLATFORM", "firmware"));
                assertFalse(OpsAccess.isUser());
                assertFalse(OpsAccess.has("pool.adjust", "PLATFORM", "wrong-scope"));
                assertFalse(OpsAccess.has("iam.approve"));
            }
        }
        assertThrows(RuntimeException.class, () -> OpsPermissionCatalog.requireCompatible(Set.of("ALLOWANCE_POOL", "ALLOWANCE_REVIEW")));
        assertThrows(RuntimeException.class, () -> OpsPermissionCatalog.requireCompatible(Set.of("ALLOWANCE", "ALLOWANCE_REVIEW")));
    }
    private void assertUnchanged() { assertEquals(1, status("pool")); assertEquals(4, count("SELECT COUNT(*) FROM ops_session")); assertEquals(0, count("SELECT COUNT(*) FROM ops_audit_event")); }
    private int status(String id) { return jdbc.queryForObject("SELECT status FROM ops_role_grant WHERE id=?", Integer.class, id); }
    private int count(String sql) { return jdbc.queryForObject(sql, Integer.class); }
    private void grant(String id, String employee, String role, String scope) { jdbc.update("INSERT INTO ops_role_grant(id,employee_id,role_code,scope_type,scope_id) VALUES(?,?,?,'PLATFORM',?)", id, employee, role, scope); }
    private void migrate() throws Exception { try (var connection = source.getConnection()) { var resource = new ClassPathResource("db/changelog/ops-three-role-boundaries-20261016.sql");
        String sql = resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        // H2 accepts CREATE TEMPORARY but spells its DROP without TEMPORARY.
        ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ByteArrayResource(sql.replace("DROP TEMPORARY TABLE", "DROP TABLE").getBytes(java.nio.charset.StandardCharsets.UTF_8))); } }
}
