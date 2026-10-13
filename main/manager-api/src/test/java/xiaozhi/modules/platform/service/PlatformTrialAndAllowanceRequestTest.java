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

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:trial" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(ds);
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
    }

    /** Mirrors Spring's @Value binding for the trial policy under test. */
    PlatformTrialGrantService trialService(boolean enabled, String amount, String policy, long maxUsers) {
        PlatformTrialGrantService service = new PlatformTrialGrantService(jdbc, accounts, tx);
        ReflectionTestUtils.setField(service, "enabled", enabled);
        ReflectionTestUtils.setField(service, "trialAmount", amount);
        ReflectionTestUtils.setField(service, "policy", policy);
        ReflectionTestUtils.setField(service, "maxUsers", maxUsers);
        return service;
    }

    BigDecimal granted(long user) {
        return jdbc.queryForObject("SELECT granted FROM ai_platform_account WHERE user_id=?", BigDecimal.class, user);
    }

    @Test void trialIsIssuedOncePerUserAndPolicyChangeDoesNotRegrant() {
        assertEquals("GRANTED", trials.issue(1).get("status"));
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
        assertEquals("GRANTED", trials.issue(1).get("status"));
        assertEquals("TRIAL_CAP_REACHED", trialService(true, "100", "trial-v1", 1).issue(2).get("status"));
        assertEquals(0, BigDecimal.ZERO.compareTo(granted(2)));
        // Raising the cap lets the next user through without re-granting the first.
        assertEquals("GRANTED", trialService(true, "100", "trial-v1", 2).issue(2).get("status"));
        assertEquals(0, new BigDecimal("100").compareTo(granted(2)));
        assertEquals(0, new BigDecimal("100").compareTo(granted(1)));

        // Drain the unallocated remainder: total may not drop below what users already hold.
        // Drain exactly the unallocated remainder; total may not drop below allocated.
        String unallocated = jdbc.queryForObject(
                "SELECT (total-allocated) FROM ai_platform_pool WHERE id='SERVICE'", BigDecimal.class).toPlainString();
        accounts.adjust(new PoolAdjustment("pool-drain", "-" + unallocated, "drain"), "operator");
        // A first-time user proves the pool block; an already-granted user would short-circuit.
        PlatformTrialGrantService capped = trialService(true, "100", "trial-v1", 0);
        assertEquals("DEFERRED", capped.issue(3).get("status"));
        assertEquals(0, BigDecimal.ZERO.compareTo(granted(3)));
        // A pool rejection must not consume the user's single trial claim.
        accounts.adjust(new PoolAdjustment("pool-refill", "500", "refill"), "operator");
        assertEquals("GRANTED", capped.issue(3).get("status"));
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
