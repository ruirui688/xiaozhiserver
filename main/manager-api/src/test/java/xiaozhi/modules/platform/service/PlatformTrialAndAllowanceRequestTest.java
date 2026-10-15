package xiaozhi.modules.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.modules.billing.service.BillingSupport;
import xiaozhi.modules.platform.dto.PlatformRequests.*;
import xiaozhi.modules.platform.service.AllowanceRequestService.Create;

/**
 * Registration trial credit, credential inventory visibility and the
 * user-requested allowance path. These behaviours are what makes a
 * "no top-up" product operable: a new account must be usable immediately, an
 * exhausted account must have a way to ask, and operators must see when the
 * hosted-key pool is about to run dry.
 */
class PlatformTrialAndAllowanceRequestTest {
    JdbcTemplate jdbc; TransactionTemplate tx; BillingSupport json;
    PlatformCredentialService credentials; PlatformAccountService accounts;
    PlatformTrialGrantService trials; PlatformCredentialInventoryService inventory;
    AllowanceRequestService requests;
    boolean disposableMysql;
    java.util.List<String> ownedMysqlTables = java.util.List.of();

    @BeforeEach void setup() throws Exception {
        String mysql = System.getenv("TRIAL_TEST_MYSQL_URL");
        disposableMysql = mysql != null && !mysql.isBlank();
        if (disposableMysql) assertTrue(mysql.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_trial_test(?:\\?.*)?"),
                "Only an explicitly named disposable localhost schema is accepted");
        var ds = disposableMysql
                ? new DriverManagerDataSource(mysql, System.getenv().getOrDefault("TRIAL_TEST_MYSQL_USER", "root"), System.getenv("TRIAL_TEST_MYSQL_PASSWORD"))
                : new DriverManagerDataSource("jdbc:h2:mem:trial" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(ds);
        if (disposableMysql) assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class),
                "Refusing to use a nonempty database");
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        json = new BillingSupport(new ObjectMapper());
        jdbc.execute("CREATE TABLE sys_user(id bigint PRIMARY KEY,username varchar(50),status int,create_date datetime)");
        jdbc.execute("CREATE TABLE sys_workspace(id varchar(64) PRIMARY KEY,owner_user_id bigint,status int)");
        jdbc.execute("CREATE TABLE sys_workspace_member(workspace_id varchar(64),user_id bigint,status int)");
        String platform = Files.readString(Path.of("src/main/resources/db/changelog/202610121610.sql"))
                .replaceAll("(?m)^--.*$", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "")
                // Production backfill requires existing workspace tables and is excluded from this fixture.
                .replaceAll("INSERT INTO ai_platform_account\\(user_id,updated_at\\) SELECT[^;]*;", "")
                .replaceAll("INSERT INTO ai_platform_responsibility\\(workspace_id,responsible_user_id,version\\)[^;]*;", "");
        for (String statement : platform.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/changelog/202610121700.sql"))
                .replaceAll("(?m)^--.*$", ""));
        String trialOutbox = Files.readString(Path.of("src/main/resources/db/changelog/platform-registration-trial-outbox-20261015.sql"))
                .replaceAll("(?m)^--.*$", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        for (String statement : trialOutbox.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/changelog/202610121710.sql"))
                .replaceAll("(?m)^--.*$", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", ""));
        jdbc.update("INSERT INTO sys_user VALUES(1,'alice',1,CURRENT_TIMESTAMP),(2,'bob',1,CURRENT_TIMESTAMP),(3,'carol',1,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO sys_workspace VALUES('team-a',1,1)");
        jdbc.update("INSERT INTO sys_workspace_member VALUES('team-a',1,1),('team-a',2,1)");
        jdbc.update("INSERT INTO ai_platform_account(user_id,updated_at) VALUES(1,CURRENT_TIMESTAMP),(2,CURRENT_TIMESTAMP),(3,CURRENT_TIMESTAMP)");
        credentials = new PlatformCredentialService(jdbc, tx, json, new CredentialCipher("test-master-key-only-32-characters-long"));
        accounts = new PlatformAccountService(jdbc, tx, org.mockito.Mockito.mock(PlatformChannelService.class));
        trials = trialService(true, "100", "trial-v1", 0);
        inventory = new PlatformCredentialInventoryService(jdbc);
        ReflectionTestUtils.setField(inventory, "minimumInventory", 2L);
        requests = new AllowanceRequestService(jdbc, tx, accounts);
        accounts.adjust(new PoolAdjustment("pool-fixture", "10000", "fixture"), "operator");
        if (disposableMysql) ownedMysqlTables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()", String.class);
    }

    @AfterEach void dropOnlyTablesCreatedInDisposableMysqlFixture() {
        if (!disposableMysql || ownedMysqlTables.isEmpty()) return;
        for (String table : ownedMysqlTables) {
            assertTrue(table.matches("ai_platform_[a-z_]+|sys_user|sys_workspace|sys_workspace_member"));
            jdbc.execute("DROP TABLE `" + table + "`");
        }
    }

    /** Mirrors Spring's @Value binding for the trial policy under test. */
    PlatformTrialGrantService trialService(boolean enabled, String amount, String policy, long maxUsers) {
        PlatformTrialPolicy settings = new PlatformTrialPolicy();
        settings.setTrialEnabled(enabled); settings.setTrialAmount(amount);
        settings.setTrialPolicy(policy); settings.setTrialMaxUsers(maxUsers);
        return new PlatformTrialGrantService(jdbc, tx, settings, new PlatformTrialGrantWorker(jdbc, accounts, tx, settings));
    }

    String issueState(PlatformTrialGrantService service, long user) {
        service.issue(user);
        return service.status(user).get("status").toString();
    }

    void makeDue(long user) {
        jdbc.update("UPDATE ai_platform_trial_grant SET next_attempt_at='2000-01-01' WHERE user_id=?", user);
    }

    PlatformTrialGrantWorker worker(PlatformTrialGrantService service) {
        return (PlatformTrialGrantWorker) ReflectionTestUtils.getField(service, "worker");
    }

    BigDecimal granted(long user) {
        return jdbc.queryForObject("SELECT granted FROM ai_platform_account WHERE user_id=?", BigDecimal.class, user);
    }

    @Test void trialIsIssuedOncePerUserAndPolicyChangeDoesNotRegrant() {
        assertEquals("GRANTED", issueState(trials, 1));
        assertEquals(0, new BigDecimal("100").compareTo(granted(1)));
        // Repeated registration hooks and a new policy version must both stay single-issue.
        assertEquals("ALREADY_GRANTED", trials.issue(1).get("status"));
        PlatformTrialGrantService bumped = trialService(true, "200", "trial-v2", 0);
        assertEquals("ALREADY_GRANTED", bumped.issue(1).get("status"));
        assertEquals(0, new BigDecimal("100").compareTo(granted(1)));
        assertEquals(1L, trials.issuedUsers());
        // Duplicate issuance attempts must not add a second grant entry.
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_platform_ledger WHERE user_id=1 AND entry_type='GRANT' AND operator_id=?",
                Integer.class, PlatformTrialGrantService.SYSTEM_OPERATOR));
    }

    @Test void trialRespectsTheIssuanceCapAndRemainsDeferredWithoutPoolCredit() {
        assertEquals("GRANTED", issueState(trials, 1));
        var limited = trialService(true, "100", "trial-v1", 1);
        assertEquals("PENDING", issueState(limited, 2));
        assertEquals("TRIAL_CAP_REACHED", limited.status(2).get("reasonCode"));
        assertEquals(0, BigDecimal.ZERO.compareTo(granted(2)));
        // Raising the cap lets the next user through without re-granting the first.
        makeDue(2);
        assertEquals("GRANTED", issueState(trialService(true, "100", "trial-v1", 2), 2));
        assertEquals(0, new BigDecimal("100").compareTo(granted(2)));
        assertEquals(0, new BigDecimal("100").compareTo(granted(1)));

        // Drain the unallocated remainder: total may not drop below what users already hold.
        // Drain exactly the unallocated remainder; total may not drop below allocated.
        String unallocated = jdbc.queryForObject(
                "SELECT (total-allocated) FROM ai_platform_pool WHERE id='SERVICE'", BigDecimal.class).toPlainString();
        accounts.adjust(new PoolAdjustment("pool-drain", "-" + unallocated, "drain"), "operator");
        // A first-time user proves the pool block; an already-granted user would short-circuit.
        PlatformTrialGrantService capped = trialService(true, "100", "trial-v1", 0);
        assertEquals("PENDING", issueState(capped, 3));
        assertEquals("ISSUANCE_POOL_EXHAUSTED", capped.status(3).get("reasonCode"));
        assertEquals(0, BigDecimal.ZERO.compareTo(granted(3)));
        // A pool rejection must not consume the user's single trial claim.
        accounts.adjust(new PoolAdjustment("pool-refill", "500", "refill"), "operator");
        makeDue(3);
        assertEquals("GRANTED", issueState(capped, 3));
        assertEquals(0, new BigDecimal("100").compareTo(granted(3)));
    }

    @Test void concurrentTrialsRespectGlobalCapAndRegistrationFailureDoesNotRollbackUser() throws Exception {
        var limited=trialService(true,"100","trial-concurrent",1);var latch=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=workers.submit(()->{latch.await();return limited.issue(1);});var b=workers.submit(()->{latch.await();return limited.issue(2);});latch.countDown();
            a.get(10,java.util.concurrent.TimeUnit.SECONDS);b.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(1L,limited.issuedUsers());assertEquals(0,new BigDecimal("100").compareTo(granted(1).add(granted(2))));
        accounts.adjust(new PoolAdjustment("trial-pool-drain","-9900","fixture"),"operator");
        tx.executeWithoutResult(status->{jdbc.update("INSERT INTO sys_user VALUES(4,'dave',1,CURRENT_TIMESTAMP)");accounts.ensureUser(4);assertEquals("SCHEDULED",trials.issue(4).get("status"));});
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=4",Integer.class));assertEquals(0,BigDecimal.ZERO.compareTo(granted(4)));
    }
    @Test void changedRequestAmountOrWorkspaceCannotReuseAnIdempotencyKey() {
        requests.create(1,new Create("req-conflict","10","reason","team-a"));
        assertThrows(RenException.class,()->requests.create(1,new Create("req-conflict","20","reason","team-a")));
        assertThrows(RenException.class,()->requests.create(1,new Create("req-conflict","10","reason",null)));
        assertThrows(RenException.class,()->requests.create(2,new Create("req-member","10","reason","team-a")));
    }
    @Test void simultaneousApprovalsGrantOnceAndRejectedRequestsNeverReceiveCredit() throws Exception {
        String id=requests.create(1,new Create("req-approve-concurrent","100","request",null)).get("id").toString();
        var input=new AllowanceRequestService.Decision("approve-concurrent","30","policy");var latch=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=workers.submit(()->{latch.await();return requests.approve(id,"employee-1",input);});var b=workers.submit(()->{latch.await();return requests.approve(id,"employee-2",input);});latch.countDown();
            a.get(10,java.util.concurrent.TimeUnit.SECONDS);b.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(0,new BigDecimal("30").compareTo(granted(1)));
        String rejected=requests.create(2,new Create("req-rejected","10","request",null)).get("id").toString();requests.reject(rejected,"reviewer","denied");
        assertThrows(RenException.class,()->requests.approve(rejected,"employee-1",input));assertEquals(0,BigDecimal.ZERO.compareTo(granted(2)));
        accounts.adjust(new PoolAdjustment("request-pool-drain","-9970","fixture"),"operator");
        String empty=requests.create(3,new Create("req-pool-empty","10","request",null)).get("id").toString();assertThrows(RenException.class,()->requests.approve(empty,"employee-1",input));
        assertEquals("PENDING",requests.get(empty).get("status"));assertEquals(0,BigDecimal.ZERO.compareTo(granted(3)));
    }
    @Test void disabledTrialNeverCreditsAnAccount() {
        assertEquals("DISABLED", trialService(false, "100", "trial-v1", 0).issue(1).get("status"));
        assertEquals(0, BigDecimal.ZERO.compareTo(granted(1)));
    }

    @Test void registrationRollbackLeavesNeitherIntentNorGrant() {
        tx.executeWithoutResult(transaction -> {
            jdbc.update("INSERT INTO sys_user VALUES(4,'rollback',1,CURRENT_TIMESTAMP)");
            accounts.ensureUser(4);
            trials.issue(4);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_trial_grant WHERE user_id=4", Integer.class));
            transaction.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=4", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_trial_grant WHERE user_id=4", Integer.class));
        assertEquals(0L, trials.issuedUsers());
    }

    @Test void committedIntentSurvivesMissingAfterCommitWorkerAndKeepsPolicySnapshot() {
        PlatformTrialPolicy settings = new PlatformTrialPolicy();
        var stopped = org.mockito.Mockito.mock(PlatformTrialGrantWorker.class);
        var registrar = new PlatformTrialGrantService(jdbc, tx, settings, stopped);
        registrar.issue(1);
        assertEquals("PENDING", registrar.status(1).get("status"));
        assertEquals(0, BigDecimal.ZERO.compareTo(granted(1)));
        // Another process can recover without the registration callback and without changing the promised amount.
        settings.setTrialAmount("200"); settings.setTrialPolicy("trial-v2");
        new PlatformTrialGrantWorker(jdbc, accounts, tx, settings).dispatch();
        assertEquals(0, new BigDecimal("100").compareTo(granted(1)));
        assertEquals("trial-v1", registrar.status(1).get("grantedPolicy"));
        assertEquals("GRANTED", registrar.status(1).get("status"));
    }

    @Test void poolRecoveryIsAutomaticAndDoesNotScanOrGrantHistoricalAccounts() {
        accounts.adjust(new PoolAdjustment("drain-recover", "-10000", "fixture"), "operator");
        trials.issue(1);
        assertEquals("ISSUANCE_POOL_EXHAUSTED", trials.status(1).get("reasonCode"));
        assertEquals("NOT_SCHEDULED", trials.status(2).get("status"));
        accounts.adjust(new PoolAdjustment("refill-recover", "100", "fixture"), "operator");
        makeDue(1);
        worker(trials).dispatch();
        assertEquals("GRANTED", trials.status(1).get("status"));
        assertEquals(0, new BigDecimal("100").compareTo(granted(1)));
        assertEquals(0, BigDecimal.ZERO.compareTo(granted(2)));
    }

    @Test void disabledPolicyPausesPendingWithoutDiscardingOrGrantingIt() {
        var settings = new PlatformTrialPolicy();
        var delayed = new PlatformTrialGrantService(jdbc, tx, settings, org.mockito.Mockito.mock(PlatformTrialGrantWorker.class));
        delayed.issue(1);
        settings.setTrialEnabled(false);
        var processor = new PlatformTrialGrantWorker(jdbc, accounts, tx, settings);
        processor.dispatch(); processor.attempt(1);
        assertEquals(true, delayed.status(1).get("paused"));
        assertEquals("PENDING", delayed.status(1).get("status"));
        assertEquals(0, BigDecimal.ZERO.compareTo(granted(1)));
        settings.setTrialEnabled(true); processor.dispatch();
        assertEquals("GRANTED", delayed.status(1).get("status"));
        var off = trialService(false, "0", "off-policy", 0);
        off.issue(2); processor.dispatch();
        assertEquals("DISABLED", off.status(2).get("status"));
        assertEquals(0, BigDecimal.ZERO.compareTo(granted(2)));
    }

    @Test void frozenAndDisabledUsersNeverReceiveCredit() {
        jdbc.update("UPDATE ai_platform_account SET frozen=1 WHERE user_id=1");
        trials.issue(1);
        assertEquals("REVIEW", trials.status(1).get("status"));
        assertEquals("ACCOUNT_FROZEN", trials.status(1).get("reasonCode"));
        jdbc.update("UPDATE sys_user SET status=0 WHERE id=2");
        trials.issue(2);
        assertEquals("USER_UNAVAILABLE", trials.status(2).get("reasonCode"));
        assertEquals(0, BigDecimal.ZERO.compareTo(granted(1).add(granted(2))));
    }

    @Test void dailyBudgetFailurePreservesIntentAndWaitsUntilNextUtcDay() {
        ReflectionTestUtils.setField(accounts, "dailyGrant", "100");
        trials.issue(1); trials.issue(2);
        assertEquals("OPERATOR_DAILY_GRANT_LIMIT", trials.status(2).get("reasonCode"));
        java.sql.Timestamp next = jdbc.queryForObject("SELECT next_attempt_at FROM ai_platform_trial_grant WHERE user_id=2", java.sql.Timestamp.class);
        assertEquals(PlatformSupport.now().toLocalDate().plusDays(1), next.toLocalDateTime().toLocalDate());
        assertEquals(0, BigDecimal.ZERO.compareTo(granted(2)));
        jdbc.update("UPDATE ai_platform_ledger SET created_at=? WHERE operator_id=?",
                PlatformSupport.now().minusDays(1), PlatformTrialGrantService.SYSTEM_OPERATOR);
        makeDue(2); worker(trials).attempt(2);
        assertEquals("GRANTED", trials.status(2).get("status"));
    }

    @Test void expiredWorkerLeaseRecoversAndRetryBudgetEndsInReview() {
        var delayed = new PlatformTrialGrantService(jdbc, tx, new PlatformTrialPolicy(), org.mockito.Mockito.mock(PlatformTrialGrantWorker.class));
        delayed.issue(1);
        jdbc.update("UPDATE ai_platform_trial_grant SET status='PROCESSING',lease_token='dead-process',attempts=1,next_attempt_at='2000-01-01' WHERE user_id=1");
        worker(trials).attempt(1);
        assertEquals("GRANTED", trials.status(1).get("status"));
        accounts.adjust(new PoolAdjustment("drain-attempts", "-9900", "fixture"), "operator");
        trials.issue(2);
        for (int i=1; i<PlatformTrialGrantWorker.MAX_ATTEMPTS; i++) { makeDue(2); worker(trials).attempt(2); }
        assertEquals("REVIEW", trials.status(2).get("status"));
        assertEquals(12, ((Number) trials.status(2).get("attempts")).intValue());
        makeDue(2); worker(trials).attempt(2);
        assertEquals(12, ((Number) trials.status(2).get("attempts")).intValue());
        assertEquals(0, BigDecimal.ZERO.compareTo(granted(2)));
    }

    @Test void defaultPolicyIs100AndInvalidOverrideIsVisibleWithoutBlockingRegistration() {
        var defaults = new PlatformTrialPolicy();
        assertTrue(defaults.isTrialEnabled()); assertEquals("100", defaults.getTrialAmount());
        var invalid = trialService(true, "nonsense", "bad", 0);
        invalid.issue(1);
        assertEquals("REVIEW", invalid.status(1).get("status"));
        assertEquals("POLICY_INVALID", invalid.status(1).get("reasonCode"));
        assertEquals(0, BigDecimal.ZERO.compareTo(granted(1)));
    }

    @Test void repairRequiresOpsGrantForEveryTargetAndNeverRegrantsExistingTrial() {
        try (var security = org.mockito.Mockito.mockStatic(xiaozhi.modules.security.user.SecurityUser.class);
             var messages = org.mockito.Mockito.mockStatic(xiaozhi.common.utils.MessageUtils.class)) {
            messages.when(() -> xiaozhi.common.utils.MessageUtils.getMessage(xiaozhi.common.exception.ErrorCode.NO_PERMISSION)).thenReturn("denied");
            var actor = new xiaozhi.common.user.UserDetail(); actor.setId(1L); actor.setAudience("USER");
            security.when(xiaozhi.modules.security.user.SecurityUser::getUser).thenReturn(actor);
            assertThrows(RenException.class, () -> trials.retry(java.util.List.of(1L), "self-claim"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_trial_grant", Integer.class));
            actor.setAudience("OPS"); actor.setId(null); actor.setEmployeeId("test-operator");
            actor.setGrants(java.util.List.of(new xiaozhi.modules.security.ops.OpsGrant("ALLOWANCE", "USER", "1", java.util.Set.of("allowance.grant"))));
            assertThrows(RenException.class, () -> trials.retry(java.util.List.of(1L,2L), "partial forbidden"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_trial_grant", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_trial_repair", Integer.class));
            actor.setGrants(java.util.List.of(new xiaozhi.modules.security.ops.OpsGrant("ALLOWANCE", "PLATFORM", "platform-services", java.util.Set.of("allowance.grant"))));
            assertThrows(RenException.class, () -> trials.retry(java.util.Collections.nCopies(101, 1L), "too large"));
            trials.retry(java.util.List.of(1L), "authorized bounded repair");
            assertEquals("PENDING", trials.status(1).get("status"));
            assertEquals(0, BigDecimal.ZERO.compareTo(granted(1)), "repair queues work, never updates balance directly");
            worker(trials).dispatch();
            trials.retry(java.util.List.of(1L), "repeat repair"); worker(trials).attempt(1);
            assertEquals(0, new BigDecimal("100").compareTo(granted(1)));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_ledger WHERE user_id=1 AND entry_type='GRANT'", Integer.class));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_trial_repair WHERE user_id=1 AND operator_id='test-operator'", Integer.class));
            assertEquals("NOT_SCHEDULED", trials.status(2).get("status"));
        }
    }

    @Test void scheduledBatchIsBoundedAndGateSharedBetweenWorkers() {
        var settings = new PlatformTrialPolicy();
        var registrar = new PlatformTrialGrantService(jdbc, tx, settings, org.mockito.Mockito.mock(PlatformTrialGrantWorker.class));
        for (long id=4; id<34; id++) {
            jdbc.update("INSERT INTO sys_user VALUES(?, 'batch-user',1,CURRENT_TIMESTAMP)", id);
            accounts.ensureUser(id); registrar.issue(id);
        }
        worker(trials).dispatch();
        new PlatformTrialGrantWorker(jdbc, accounts, tx, settings).dispatch();
        assertEquals(10L, trials.issuedUsers());
        assertEquals(20, jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_trial_grant WHERE status='PENDING'", Integer.class));
    }

    @Test void inventoryReportsWaterlineWaitingUsersAndAlerts() {
        credentials.add(new CredentialInput("DEEPSEEK", "inv-1", Map.of("api_key", "fake-key-inv-1")));
        credentials.add(new CredentialInput("DEEPSEEK", "inv-2", Map.of("api_key", "fake-key-inv-2")));
        credentials.initialize(1);
        credentials.initialize(2);
        Map<String, Object> summary = inventory.summary();
        Map<String, Object> deepseek = provider(summary, "DEEPSEEK");
        // Both hosted keys are assigned, leaving nothing for the next registration.
        assertEquals(0L, deepseek.get("inventory"));
        assertEquals(2L, deepseek.get("active"));
        assertEquals(1L, deepseek.get("pendingUsers"));
        assertEquals(true, deepseek.get("lowInventory"));
        @SuppressWarnings("unchecked") var alerts = (java.util.List<Map<String, Object>>) summary.get("alerts");
        assertTrue(alerts.stream().anyMatch(a -> "CREDENTIAL_INVENTORY_LOW".equals(a.get("code")) && "DEEPSEEK".equals(a.get("provider"))));
        // Carol already has a platform account: missing pointers must not hide this waiting user.
        assertTrue(alerts.stream().anyMatch(a -> "CREDENTIAL_PENDING_USERS".equals(a.get("code")) && "DEEPSEEK".equals(a.get("provider"))));
        assertEquals(0, deepseekPending().size());

        // The hosted-key pool is now empty, so the next registration must enter the waiting queue.
        credentials.initialize(3);
        Map<String, Object> waiting = inventory.summary();
        assertEquals(1L, provider(waiting, "DEEPSEEK").get("pendingUsers"));
        @SuppressWarnings("unchecked") var waitingAlerts = (java.util.List<Map<String, Object>>) waiting.get("alerts");
        assertTrue(waitingAlerts.stream().anyMatch(a -> "CREDENTIAL_PENDING_USERS".equals(a.get("code"))
                && "DEEPSEEK".equals(a.get("provider")) && Long.valueOf(1L).equals(a.get("waiting"))));

        @SuppressWarnings("unchecked") var queue = (java.util.List<Map<String, Object>>) inventory.pending(1, 100).get("list");
        var deepseekRows = queue.stream().filter(row -> "DEEPSEEK".equals(row.get("provider"))).toList();
        assertEquals(1, deepseekRows.size());
        // The waiting queue must expose identity and provider only, never usage or content.
        assertEquals("3", deepseekRows.getFirst().get("userId"));
        assertEquals("carol", deepseekRows.getFirst().get("username"));
        assertEquals(4, deepseekRows.getFirst().size());

        // Importing a key does not by itself change the waiting user's state...
        credentials.add(new CredentialInput("DEEPSEEK", "inv-3", Map.of("api_key", "fake-key-inv-3")));
        assertEquals("PENDING_CREDENTIAL", credentials.status(3, "DEEPSEEK").get("status"));
        assertEquals(1, deepseekPending().size());
        // ...the backfill is what assigns it, exactly as the operator action does.
        assertEquals("ACTIVE", credentials.assign(3, "DEEPSEEK", false).get("status"));
        assertEquals(0, deepseekPending().size());
    }

    private java.util.List<Map<String, Object>> deepseekPending() {
        return jdbc.queryForList("SELECT user_id,provider FROM ai_platform_user_credential WHERE provider='DEEPSEEK' AND credential_id IS NULL");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> provider(Map<String, Object> summary, String name) {
        return ((java.util.List<Map<String, Object>>) summary.get("providers")).stream()
                .filter(item -> name.equals(item.get("provider"))).findFirst().orElseThrow();
    }

    @Test void userRequestIsIdempotentAndOnlyVisibleToItsOwner() {
        Map<String, Object> created = requests.create(1, new Create("req-0001", "50", "额度用完了", "team-a"));
        assertEquals("PENDING", created.get("status"));
        assertEquals("50", created.get("requestedAmount"));
        Map<String, Object> repeated = requests.create(1, new Create("req-0001", "50", "额度用完了", "team-a"));
        assertEquals(created.get("id"), repeated.get("id"));
        assertEquals(1L, requests.mine(1, 1, 20).get("total"));
        assertEquals(0L, requests.mine(2, 1, 20).get("total"));
        // A user cannot attach a request to a workspace they do not belong to.
        assertThrows(RenException.class, () -> requests.create(1, new Create("req-0002", null, "试试", "team-elsewhere")));
        // The same key with different content is a conflict, not a silent overwrite.
        assertThrows(RenException.class, () -> requests.create(1, new Create("req-0001", "50", "换个说法", "team-a")));
    }

    @Test void operatorDecisionIsSingleUseAndRejectionKeepsBalanceUntouched() {
        Map<String, Object> created = requests.create(1, new Create("req-0003", null, "需要更多额度", null));
        String id = created.get("id").toString();
        assertEquals("REJECTED", requests.reject(id, "employee-1", "不符合发放政策").get("status"));
        assertThrows(RenException.class, () -> requests.reject(id, "employee-1", "重复处理"));
        assertEquals(0, BigDecimal.ZERO.compareTo(granted(1)));
        assertEquals(0L, requests.pendingCount());
    }

    @Test void approvingARequestCreditsThroughTheOperatorGrantPath() {
        Map<String, Object> created = requests.create(1, new Create("req-0004", "9999", "想要很多", null));
        String id = created.get("id").toString();
        // The operator grants a different amount than requested; the request is intent only.
        Map<String, Object> decided = requests.approve(id, "employee-1", new AllowanceRequestService.Decision("req-0004-grant","30","按政策发放"));
        assertEquals("APPROVED", decided.get("status"));
        assertEquals("30", decided.get("grantedAmount"));
        assertEquals(0, new BigDecimal("30").compareTo(granted(1)));
        assertThrows(RenException.class, () -> requests.approve(id, "employee-1", new AllowanceRequestService.Decision("req-0004-retry","30","重复")));
    }
}
