package xiaozhi.modules.mobile.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.apache.ibatis.annotations.Select;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceSceneService;
import xiaozhi.modules.device.service.impl.DeviceServiceImpl;
import xiaozhi.modules.mobile.config.MobileVoiceProperties;
import xiaozhi.modules.mobile.dao.MobileVoiceDao;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.workspace.dao.WorkspaceDao;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

/** Real transaction proxies and mappers, disposable localhost MySQL only; no external providers. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "MOBILE_TEST_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_mobile_test(?:\\?.*)?")
class MobileVoiceMysqlIntegrationTest {
    private JdbcTemplate jdbc;
    private SqlSessionTemplate sql;
    private DataSourceTransactionManager transactions;
    private SysParamsService params;
    private MobileVoiceProperties limits;
    private volatile CountDownLatch authorizationRead;

    interface TestMembershipDao {
        @Select("SELECT writable FROM synthetic_membership WHERE user_id=7")
        Integer canWrite();
    }

    @BeforeAll void initializeEmptyDisposableSchema() throws Exception {
        DataSource source = new DriverManagerDataSource(System.getenv("MOBILE_TEST_MYSQL_URL"), "root",
                System.getenv("MOBILE_TEST_MYSQL_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class),
                "Refusing to initialize a nonempty database");
        jdbc.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY, status INT)");
        jdbc.execute("CREATE TABLE sys_workspace(id VARCHAR(64) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE ai_agent(id VARCHAR(64) PRIMARY KEY, workspace_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE ai_device(id VARCHAR(64) PRIMARY KEY, user_id BIGINT, workspace_id VARCHAR(64), "
                + "mac_address VARCHAR(64) UNIQUE, last_connected_at DATETIME, auto_update INT, board VARCHAR(64), "
                + "alias VARCHAR(128), agent_id VARCHAR(64), config_version BIGINT, speech_input_mode VARCHAR(32), "
                + "speech_output_mode VARCHAR(32), app_version VARCHAR(64), sort INT, updater BIGINT, update_date DATETIME, "
                + "creator BIGINT, create_date DATETIME, INDEX idx_ai_device_workspace_agent(workspace_id, agent_id))");
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/mobile-voice-device-quota-index-20261013.sql"))
                .execute(source);
        jdbc.execute("CREATE TABLE synthetic_scene(device_id VARCHAR(64) PRIMARY KEY, agent_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE synthetic_membership(user_id BIGINT PRIMARY KEY, writable INT)");
        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(DeviceDao.class, SysUserDao.class, WorkspaceDao.class, MobileVoiceDao.class, TestMembershipDao.class)) config.addMapper(mapper);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(config);
        sql = new SqlSessionTemplate(factory.getObject());
        transactions = new DataSourceTransactionManager(source);
    }

    @BeforeEach void syntheticFixtures() {
        for (String table : List.of("synthetic_scene", "synthetic_membership", "ai_device", "ai_agent", "sys_workspace", "sys_user")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO sys_user VALUES (7,1)");
        jdbc.update("INSERT INTO sys_workspace VALUES ('space-a')");
        jdbc.update("INSERT INTO ai_agent VALUES ('agent-a','space-a')");
        jdbc.update("INSERT INTO synthetic_membership VALUES (7,1)");
        authorizationRead = null;
        params = mock(SysParamsService.class);
        when(params.getValue(Constant.SERVER_WEBSOCKET, true)).thenReturn("wss://voice.example.invalid/xiaozhi/v1/");
        when(params.getValue(Constant.SERVER_SECRET, false)).thenReturn("synthetic-test-signing-key");
        limits = new MobileVoiceProperties();
    }

    private MobileVoiceSessionService instance() {
        AgentEntity agent = new AgentEntity(); agent.setId("agent-a"); agent.setWorkspaceId("space-a");
        AgentService agents = mock(AgentService.class); when(agents.selectById("agent-a")).thenReturn(agent);
        AgentDao agentDao = mock(AgentDao.class); when(agentDao.selectById("agent-a")).thenReturn(agent);
        WorkspacePermissionService permissions = mock(WorkspacePermissionService.class);
        when(permissions.currentWorkspaceId()).thenReturn("space-a");
        doAnswer(invocation -> {
            if (!Integer.valueOf(1).equals(sql.getMapper(TestMembershipDao.class).canWrite())) throw new RenException("revoked");
            if (authorizationRead != null) authorizationRead.countDown();
            return null;
        }).when(permissions).requireWrite("space-a");
        DeviceDao devices = sql.getMapper(DeviceDao.class);
        DeviceSceneService scenes = mock(DeviceSceneService.class);
        doAnswer(invocation -> {
            DeviceEntity device = invocation.getArgument(0);
            jdbc.update("INSERT INTO synthetic_scene VALUES (?,?)", device.getId(), device.getAgentId());
            return null;
        }).when(scenes).ensureCurrentBinding(any(), any(), anyLong());
        DeviceServiceImpl deviceService = new DeviceServiceImpl(devices, null, params, mock(RedisUtils.class), null,
                agentDao, null, permissions, scenes, null);
        ReflectionTestUtils.setField(deviceService, "baseDao", devices);
        MobileVoiceSessionService target = new MobileVoiceSessionService(agents, deviceService, params, permissions,
                sql.getMapper(SysUserDao.class), sql.getMapper(WorkspaceDao.class), devices, limits, sql.getMapper(MobileVoiceDao.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (MobileVoiceSessionService) proxy.getProxy();
    }

    @Test void separateInstancesCreateOneIdentityConcurrently() throws Exception {
        String installation = UUID.randomUUID().toString();
        List<Object> results = concurrent(instance(), installation, instance(), installation);
        assertTrue(results.stream().allMatch(MobileVoiceSessionService.Ticket.class::isInstance));
        var first = (MobileVoiceSessionService.Ticket) results.get(0);
        var second = (MobileVoiceSessionService.Ticket) results.get(1);
        assertEquals(first.deviceId(), second.deviceId()); assertNotEquals(first.clientId(), second.clientId());
        assertEquals(1, count("ai_device")); assertEquals(1, count("synthetic_scene"));
        assertEquals(0, jdbc.queryForObject("SELECT auto_update FROM ai_device", Integer.class));
        assertEquals("server", jdbc.queryForObject("SELECT speech_input_mode FROM ai_device", String.class));
    }

    @Test void concurrentNewInstallationsCannotExceedQuota() throws Exception {
        limits.setMaxDevicesPerUserWorkspace(1);
        List<Object> results = concurrent(instance(), UUID.randomUUID().toString(), instance(), UUID.randomUUID().toString());
        assertEquals(1, results.stream().filter(MobileVoiceSessionService.Ticket.class::isInstance).count());
        assertEquals(1, results.stream().filter(RenException.class::isInstance).count());
        assertEquals(1, count("ai_device")); assertEquals(1, count("synthetic_scene"));
    }

    @Test void signingFailureRollsBackDeviceAndBinding() {
        when(params.getValue(Constant.SERVER_SECRET, false)).thenReturn(null);
        assertThrows(RenException.class, () -> instance().issue("agent-a", UUID.randomUUID().toString(), 7));
        assertEquals(0, count("ai_device")); assertEquals(0, count("synthetic_scene"));
    }

    @Test void reconnectAtQuotaReusesIdentityAndMaintainsReminderAddress() {
        limits.setMaxDevicesPerUserWorkspace(1);
        String installation = UUID.randomUUID().toString();
        var first = instance().issue("agent-a", installation, 7);
        var second = instance().issue("agent-a", installation.toUpperCase(java.util.Locale.ROOT), 7);
        assertEquals(first.deviceId(), second.deviceId()); assertEquals(1, count("ai_device"));
        DeviceEntity device = sql.getMapper(DeviceDao.class).selectById(first.deviceId());
        assertEquals(first.deviceId(), device.getMacAddress()); assertEquals("space-a", device.getWorkspaceId());
        assertEquals("agent-a", device.getAgentId()); assertEquals(7L, device.getUserId());
    }

    @Test void switchedBindingIsNotSilentlyReassigned() {
        String installation = UUID.randomUUID().toString();
        var first = instance().issue("agent-a", installation, 7);
        jdbc.update("UPDATE ai_device SET agent_id='different-agent' WHERE id=?", first.deviceId());
        assertThrows(RenException.class, () -> instance().issue("agent-a", installation, 7));
        assertEquals("different-agent", jdbc.queryForObject("SELECT agent_id FROM ai_device", String.class));
    }

    @Test void missingWorkspaceOrDisabledUserCannotLeaveDevices() {
        jdbc.update("UPDATE sys_user SET status=0 WHERE id=7");
        assertThrows(RenException.class, () -> instance().issue("agent-a", UUID.randomUUID().toString(), 7));
        jdbc.update("UPDATE sys_user SET status=1 WHERE id=7");
        jdbc.update("DELETE FROM sys_workspace");
        assertThrows(RenException.class, () -> instance().issue("agent-a", UUID.randomUUID().toString(), 7));
        assertEquals(0, count("ai_device"));
    }

    @Test void revocationCommittedDuringLockWaitInvalidatesPreLockOrmSnapshot() throws Exception {
        MobileVoiceSessionService service = instance();
        authorizationRead = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = new TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForObject("SELECT id FROM sys_user WHERE id=7 FOR UPDATE", Long.class);
                var running = executor.submit(() -> service.issue("agent-a", UUID.randomUUID().toString(), 7));
                try { assertTrue(authorizationRead.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                jdbc.update("UPDATE synthetic_membership SET writable=0 WHERE user_id=7");
                return running;
            });
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
            assertInstanceOf(RenException.class, failure.getCause());
            assertEquals(0, count("ai_device"));
        }
    }

    @Test void quotaLookupUsesCoveringIndexAmongUnrelatedDevices() {
        jdbc.batchUpdate("INSERT INTO ai_device(id,user_id,workspace_id,board) VALUES (?,?,?,?)",
                java.util.stream.IntStream.range(0, 3000).mapToObj(i -> new Object[] {
                    "synthetic-physical-" + i, i % 2 == 0 ? 7L : 8L, "space-a",
                    i % 2 == 0 ? "esp32" : "mobile-voice"
                }).toList());
        instance().issue("agent-a", UUID.randomUUID().toString(), 7);
        jdbc.execute("ANALYZE TABLE ai_device");
        Map<String, Object> plan = jdbc.queryForMap("EXPLAIN SELECT id FROM ai_device "
                + "WHERE workspace_id='space-a' AND user_id=7 AND board='mobile-voice' LIMIT 10");
        assertEquals("idx_ai_device_workspace_user_board", plan.get("key"));
        assertTrue(((Number) plan.get("rows")).longValue() < 10, plan.toString());
        assertTrue(String.valueOf(plan.get("Extra")).contains("Using index"), plan.toString());
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private List<Object> concurrent(MobileVoiceSessionService first, String left, MobileVoiceSessionService second, String right) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> invokeAfter(start, first, left));
            var b = executor.submit(() -> invokeAfter(start, second, right));
            start.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }

    private Object invokeAfter(CountDownLatch start, MobileVoiceSessionService service, String installation) throws InterruptedException {
        start.await();
        try { return service.issue("agent-a", installation, 7); }
        catch (RenException e) { return e; }
    }
}
