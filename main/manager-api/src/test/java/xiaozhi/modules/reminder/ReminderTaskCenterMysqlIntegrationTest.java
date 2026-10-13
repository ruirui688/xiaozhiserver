package xiaozhi.modules.reminder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.reminder.client.ReminderDeviceNotificationClient;
import xiaozhi.modules.reminder.config.ReminderTaskProperties;
import xiaozhi.modules.reminder.dao.ReminderDao;
import xiaozhi.modules.reminder.dao.ReminderTaskDao;
import xiaozhi.modules.reminder.entity.ReminderEntity;
import xiaozhi.modules.reminder.service.ReminderDeliveryExecutor;
import xiaozhi.modules.reminder.service.ReminderDispatchService;
import xiaozhi.modules.reminder.service.ReminderTaskMetrics;

/** Real MySQL only: an empty disposable localhost schema and synthetic fixtures are mandatory. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "REMINDER_TEST_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_reminder_test(?:\\?.*)?")
class ReminderTaskCenterMysqlIntegrationTest {
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ReminderDao reminders;
    private ReminderTaskDao tasks;

    @BeforeAll
    void migrateOnlyAnEmptyDisposableSchema() throws Exception {
        String url = System.getenv("REMINDER_TEST_MYSQL_URL");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_reminder_test(?:\\?.*)?"));
        dataSource = new DriverManagerDataSource(url, "root", System.getenv("REMINDER_TEST_MYSQL_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()", Integer.class),
                "Refusing to initialize a nonempty database");
        jdbc.execute("CREATE TABLE ai_model_provider (id VARCHAR(64) PRIMARY KEY, model_type VARCHAR(32), "
                + "provider_code VARCHAR(64), name VARCHAR(100), fields JSON, sort INT, creator BIGINT, "
                + "create_date DATETIME, updater BIGINT, update_date DATETIME)");
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/device-reminders-20261013.sql"))
                .execute(dataSource);
        insert("legacy-pending", Instant.parse("2026-10-13T10:00:00Z"), "PENDING");
        insert("legacy-delivered", Instant.parse("2026-10-12T10:00:00Z"), "DELIVERED");
        jdbc.update("UPDATE ai_device_reminder SET source_request_id = 'legacy-request', request_hash = REPEAT('a',64) "
                + "WHERE id = 'legacy-pending'");
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/reminder-task-center-20261013.sql"))
                .execute(dataSource);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ai_device_reminder", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_device_reminder WHERE schedule_anchor_at = remind_at AND recurrence_json IS NULL",
                Integer.class));
        assertEquals("legacy-request", jdbc.queryForObject(
                "SELECT source_request_id FROM ai_device_reminder WHERE id = 'legacy-pending'", String.class));
        assertEquals("DELIVERED", jdbc.queryForObject(
                "SELECT status FROM ai_device_reminder WHERE id = 'legacy-delivered'", String.class));
        SqlSessionTemplate sql = mapperSession(dataSource);
        reminders = sql.getMapper(ReminderDao.class);
        tasks = sql.getMapper(ReminderTaskDao.class);
    }

    @BeforeEach
    void resetOnlyOwnedSyntheticFixtures() {
        jdbc.update("DELETE FROM ai_reminder_execution");
        jdbc.update("DELETE FROM ai_device_reminder");
        jdbc.update("UPDATE ai_reminder_dispatch_gate SET next_scan_at = '2000-01-01', window_second = 0, permits = 0");
    }

    @Test
    void twoInstancesShareOneScanLeaseAndOneDatabaseRateWindow() throws Exception {
        // Freeze only these two test sessions' DB clocks, avoiding a wall-clock boundary race in the test.
        try (SingleConnectionDataSource first = singleConnection();
             SingleConnectionDataSource second = singleConnection()) {
            ReminderTaskDao left = mapperSession(first).getMapper(ReminderTaskDao.class);
            ReminderTaskDao right = mapperSession(second).getMapper(ReminderTaskDao.class);
            List<Integer> scans = concurrent(() -> left.claimScan(1000), () -> right.claimScan(1000));
            assertEquals(1, scans.stream().mapToInt(Integer::intValue).sum());
            List<Integer> permits = concurrent(() -> acquireMany(left), () -> acquireMany(right));
            assertEquals(4, permits.stream().mapToInt(Integer::intValue).sum());
            assertEquals(4, jdbc.queryForObject("SELECT permits FROM ai_reminder_dispatch_gate WHERE id = 2", Integer.class));
            new JdbcTemplate(first).execute("SET timestamp = 1800000001");
            assertEquals(1, left.acquirePermit(4), "A new database second restores the budget");
            assertEquals(1, left.claimScan(1000), "The scan lease expires using the database clock");
        }
    }

    @Test
    void executionHistoryIsIdempotentBoundedAndContainsNoContent() {
        ReminderEntity reminder = new ReminderEntity();
        reminder.setId("history");
        reminder.setAttemptCount(2);
        Instant origin = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 10; i++) {
            reminder.setRemindAt(Date.from(origin.plusSeconds(i)));
            tasks.record(reminder, "MISSED", "offline", Date.from(origin.plusSeconds(i + 1)));
            tasks.record(reminder, "MISSED", "offline", Date.from(origin.plusSeconds(i + 1)));
        }
        assertEquals(10, jdbc.queryForObject("SELECT COUNT(*) FROM ai_reminder_execution", Integer.class));
        assertEquals(3, tasks.history("history", 3).size());
        assertEquals(4, tasks.cleanup(Date.from(origin.plusSeconds(100)), 4));
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM ai_reminder_execution", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name = 'ai_reminder_execution' AND column_name IN ('title','spoken_text')", Integer.class));
    }

    @Test
    void indexedDueLookupDoesNotScanAllFutureSchedules() {
        Instant now = Instant.now();
        jdbc.batchUpdate("INSERT INTO ai_device_reminder (id, workspace_id, device_id, agent_id, title, spoken_text, "
                + "remind_at, timezone, status, source, next_attempt_at) VALUES (?, 'workspace', 'device', 'agent', "
                + "'synthetic', 'synthetic', ?, 'UTC', 'PENDING', 'MINI_PROGRAM', ?)",
                java.util.stream.IntStream.range(0, 3000).mapToObj(i -> {
                    Timestamp time = Timestamp.from(now.plusSeconds(86400L + i));
                    return new Object[] {"future-" + i, time, time};
                }).toList());
        for (int i = 0; i < 12; i++) insert("due-" + i, now.minusSeconds(30 + i), "PENDING");
        jdbc.execute("ANALYZE TABLE ai_device_reminder");
        List<Map<String, Object>> plan = jdbc.queryForList("EXPLAIN SELECT id FROM ai_device_reminder "
                + "WHERE status = 'PENDING' AND next_attempt_at <= ? "
                + "AND (lease_until IS NULL OR lease_until <= ?) ORDER BY next_attempt_at, id LIMIT 5",
                Timestamp.from(now), Timestamp.from(now));
        assertEquals(1, plan.size());
        assertNotEquals("ALL", plan.getFirst().get("type"));
        assertNotNull(plan.getFirst().get("key"));
        assertTrue(((Number) plan.getFirst().get("rows")).longValue() < 100,
                "The selective due lookup must use the persisted due-time index: " + plan);
        assertEquals(5, reminders.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ReminderEntity>()
                .select("id", "device_id", "workspace_id").eq("status", "PENDING")
                .le("next_attempt_at", Date.from(now))
                .and(scope -> scope.isNull("lease_until").or().le("lease_until", Date.from(now)))
                .orderByAsc("next_attempt_at", "id").last("LIMIT 5")).size());
    }

    @Test
    void concurrentDispatchClaimsOnlyOnceAndRecoversAnExpiredLease() throws Exception {
        insert("claim", Instant.now().minusSeconds(30), "PENDING");
        ReminderDeliveryExecutor delivery = mock(ReminderDeliveryExecutor.class);
        ReminderTaskProperties properties = new ReminderTaskProperties();
        ReminderDispatchService first = new ReminderDispatchService(reminders, delivery, tasks, properties, new ReminderTaskMetrics());
        ReminderDispatchService second = new ReminderDispatchService(reminders, delivery, tasks, properties, new ReminderTaskMetrics());
        try {
            concurrent(() -> { ReflectionTestUtils.invokeMethod(first, "dispatchOne", "claim"); return 0; },
                    () -> { ReflectionTestUtils.invokeMethod(second, "dispatchOne", "claim"); return 0; });
            verify(delivery, times(1)).execute(any(), anyString());
            ReminderEntity claimed = reminders.selectById("claim");
            assertEquals(1, claimed.getAttemptCount());
            String originalToken = claimed.getLeaseToken();
            assertNotNull(originalToken);
            jdbc.update("UPDATE ai_device_reminder SET lease_until = '2000-01-01' WHERE id = 'claim'");
            ReflectionTestUtils.invokeMethod(second, "dispatchOne", "claim");
            verify(delivery, times(2)).execute(any(), anyString());
            ReminderEntity recovered = reminders.selectById("claim");
            assertEquals(2, recovered.getAttemptCount());
            assertNotEquals(originalToken, recovered.getLeaseToken());
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void recurringDeliveryAdvancesOneCursorAndCommitsExactlyOneHistoryRow() {
        Instant scheduled = Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        prepareClaim("repeat", scheduled, true);
        ReminderDeviceNotificationClient client = mock(ReminderDeviceNotificationClient.class);
        when(client.deliver(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ReminderDeviceNotificationClient.DeliveryResult(true, "queued"));
        ReminderDeliveryExecutor delivery = executor(client);
        transaction().executeWithoutResult(status -> delivery.execute(reminders.selectById("repeat"), "owned"));
        ReminderEntity advanced = reminders.selectById("repeat");
        assertEquals("PENDING", advanced.getStatus());
        assertEquals(scheduled.plusSeconds(86400), advanced.getRemindAt().toInstant());
        assertEquals(advanced.getRemindAt(), advanced.getNextAttemptAt());
        assertEquals(0, advanced.getAttemptCount());
        assertNull(advanced.getLeaseToken());
        assertEquals(1, tasks.history("repeat", 20).size());
        assertEquals("DELIVERED", tasks.history("repeat", 20).getFirst().getStatus());
        transaction().executeWithoutResult(status -> delivery.execute(advanced, "owned"));
        verify(client, times(1)).deliver(anyString(), anyString(), anyString(), anyString());
        assertEquals(1, tasks.history("repeat", 20).size(), "A stale worker cannot complete the next occurrence");
    }

    @Test
    void historyAndRecurringCursorRollBackTogether() {
        Instant scheduled = Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        prepareClaim("rollback", scheduled, true);
        ReminderDeviceNotificationClient client = mock(ReminderDeviceNotificationClient.class);
        when(client.deliver(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ReminderDeviceNotificationClient.DeliveryResult(true, "queued"));
        ReminderDeliveryExecutor delivery = executor(client);
        transaction().executeWithoutResult(status -> {
            delivery.execute(reminders.selectById("rollback"), "owned");
            assertEquals(1, tasks.history("rollback", 20).size());
            assertEquals(scheduled.plusSeconds(86400), reminders.selectById("rollback").getRemindAt().toInstant());
            status.setRollbackOnly();
        });
        assertEquals(0, tasks.history("rollback", 20).size());
        assertEquals(scheduled, reminders.selectById("rollback").getRemindAt().toInstant());
        assertEquals("owned", reminders.selectById("rollback").getLeaseToken());
    }

    @Test
    void downtimeSkipsHistoricalOccurrencesWithoutSendingOrExpandingTheBacklog() {
        Instant scheduled = Instant.now().minusSeconds(86400L * 3650 + 3600)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        prepareClaim("downtime", scheduled, true);
        ReminderDeviceNotificationClient client = mock(ReminderDeviceNotificationClient.class);
        ReminderDeliveryExecutor delivery = executor(client);
        transaction().executeWithoutResult(status -> delivery.execute(reminders.selectById("downtime"), "owned"));
        ReminderEntity advanced = reminders.selectById("downtime");
        assertEquals("PENDING", advanced.getStatus());
        assertTrue(advanced.getRemindAt().toInstant().isAfter(Instant.now()));
        assertTrue(advanced.getRemindAt().toInstant().isBefore(Instant.now().plusSeconds(86400)));
        assertEquals(1, tasks.history("downtime", 20).size());
        assertEquals("MISSED", tasks.history("downtime", 20).getFirst().getStatus());
        verifyNoInteractions(client);
    }

    @Test
    void retryIsDelayedAndTheFinalAttemptTerminates() {
        Instant scheduled = Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        prepareClaim("retry", scheduled, false);
        ReminderDeviceNotificationClient client = mock(ReminderDeviceNotificationClient.class);
        when(client.deliver(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ReminderDeviceNotificationClient.DeliveryResult(false, "offline"));
        ReminderDeliveryExecutor delivery = executor(client);
        Instant before = Instant.now();
        transaction().executeWithoutResult(status -> delivery.execute(reminders.selectById("retry"), "owned"));
        ReminderEntity retried = reminders.selectById("retry");
        assertEquals("PENDING", retried.getStatus());
        assertNull(retried.getLeaseToken());
        assertTrue(retried.getNextAttemptAt().toInstant().isAfter(before.plusSeconds(14)));
        assertTrue(retried.getNextAttemptAt().toInstant().isBefore(Instant.now().plusSeconds(20)));
        jdbc.update("UPDATE ai_device_reminder SET attempt_count = 8, lease_token = 'last', "
                + "lease_until = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 30 SECOND) WHERE id = 'retry'");
        transaction().executeWithoutResult(status -> delivery.execute(reminders.selectById("retry"), "last"));
        assertEquals("MISSED", reminders.selectById("retry").getStatus());
        assertNull(reminders.selectById("retry").getNextAttemptAt());
        assertEquals(1, tasks.history("retry", 20).size());
    }

    @Test
    void expiredBacklogUsesItsBoundedLaneWithoutStarvingNewDeliveries() {
        Instant now = Instant.now();
        for (int i = 0; i < 100; i++) insert("expired-" + i, now.minusSeconds(86400L + i), "PENDING");
        insert("new-due", now.minusSeconds(1), "PENDING");
        ReminderDeliveryExecutor delivery = mock(ReminderDeliveryExecutor.class);
        ReminderTaskProperties properties = new ReminderTaskProperties();
        properties.setBatchSize(7);
        ReminderDispatchService dispatch = new ReminderDispatchService(reminders, delivery, tasks, properties, new ReminderTaskMetrics());
        try {
            dispatch.recoverExpired();
            dispatch.dispatchDueReminders();
            verify(delivery, timeout(3000).times(7)).expire(anyString());
            verify(delivery, timeout(3000).times(1)).execute(
                    argThat(reminder -> "new-due".equals(reminder.getId())), anyString());
            assertEquals(1, jdbc.queryForObject("SELECT permits FROM ai_reminder_dispatch_gate WHERE id = 2", Integer.class),
                    "Expired maintenance must not consume the network rate budget");
        } finally {
            dispatch.close();
        }
    }

    @Test
    void expiryAdvancesAnUnleasedRecurringCursorButNeverTouchesALiveDelivery() {
        Instant scheduled = Instant.now().minusSeconds(86400L * 30).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        prepareClaim("expired-repeat", scheduled, true);
        ReminderDeviceNotificationClient client = mock(ReminderDeviceNotificationClient.class);
        ReminderDeliveryExecutor delivery = executor(client);
        transaction().executeWithoutResult(status -> delivery.expire("expired-repeat"));
        assertEquals(0, tasks.history("expired-repeat", 20).size(), "A live lease protects delivery against expiry");
        jdbc.update("UPDATE ai_device_reminder SET lease_token = NULL, lease_until = NULL WHERE id = 'expired-repeat'");
        transaction().executeWithoutResult(status -> delivery.expire("expired-repeat"));
        ReminderEntity advanced = reminders.selectById("expired-repeat");
        assertEquals("PENDING", advanced.getStatus());
        assertTrue(advanced.getRemindAt().toInstant().isAfter(Instant.now()));
        assertEquals(1, tasks.history("expired-repeat", 20).size());
        assertEquals("MISSED", tasks.history("expired-repeat", 20).getFirst().getStatus());
        verifyNoInteractions(client);
    }

    private void prepareClaim(String id, Instant scheduled, boolean repeat) {
        insert(id, scheduled, "PENDING");
        jdbc.update("UPDATE ai_device_reminder SET schedule_anchor_at = remind_at, recurrence_json = ?, "
                + "lease_token = 'owned', lease_until = DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 30 SECOND), attempt_count = 1 WHERE id = ?",
                repeat ? "{\"frequency\":\"DAILY\",\"interval\":1}" : null, id);
    }

    private ReminderDeliveryExecutor executor(ReminderDeviceNotificationClient client) {
        DeviceDao devices = mock(DeviceDao.class);
        AgentDao agents = mock(AgentDao.class);
        DeviceEntity device = new DeviceEntity();
        device.setId("device"); device.setWorkspaceId("workspace"); device.setAgentId("agent"); device.setMacAddress("AA:BB:CC:DD:EE:FF");
        AgentEntity agent = new AgentEntity(); agent.setId("agent"); agent.setWorkspaceId("workspace");
        when(devices.selectByIdForUpdate("device")).thenReturn(device);
        when(agents.selectById("agent")).thenReturn(agent);
        return new ReminderDeliveryExecutor(reminders, devices, agents, client, tasks, new ReminderTaskProperties(), new ReminderTaskMetrics());
    }

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setTimeout(12);
        return transaction;
    }

    private void insert(String id, Instant when, String status) {
        jdbc.update("INSERT INTO ai_device_reminder (id, workspace_id, device_id, agent_id, title, spoken_text, "
                + "remind_at, timezone, status, source, next_attempt_at) VALUES (?, 'workspace', 'device', 'agent', "
                + "'synthetic', 'synthetic', ?, 'UTC', ?, 'MINI_PROGRAM', ?)",
                id, Timestamp.from(when), status, Timestamp.from(when));
    }

    private SingleConnectionDataSource singleConnection() {
        SingleConnectionDataSource source = new SingleConnectionDataSource(
                System.getenv("REMINDER_TEST_MYSQL_URL"), "root", System.getenv("REMINDER_TEST_MYSQL_PASSWORD"), true);
        new JdbcTemplate(source).execute("SET timestamp = 1800000000");
        return source;
    }

    private int acquireMany(ReminderTaskDao dao) {
        int accepted = 0;
        for (int i = 0; i < 16; i++) accepted += dao.acquirePermit(4);
        return accepted;
    }

    private List<Integer> concurrent(java.util.concurrent.Callable<Integer> first,
                                     java.util.concurrent.Callable<Integer> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var left = pool.submit(() -> { ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return first.call(); });
            var right = pool.submit(() -> { ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return second.call(); });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
        }
    }

    private SqlSessionTemplate mapperSession(DataSource source) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var sessionFactory = factory.getObject();
        sessionFactory.getConfiguration().addMapper(ReminderDao.class);
        sessionFactory.getConfiguration().addMapper(ReminderTaskDao.class);
        return new SqlSessionTemplate(sessionFactory);
    }
}
