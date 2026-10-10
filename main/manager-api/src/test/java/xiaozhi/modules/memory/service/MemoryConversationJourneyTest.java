package xiaozhi.modules.memory.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.mybatis.spring.SqlSessionTemplate;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import cn.hutool.json.JSONUtil;
import xiaozhi.modules.agent.dao.*;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.llm.service.LLMService;
import xiaozhi.modules.memory.dao.*;
import xiaozhi.modules.memory.dto.*;
import xiaozhi.modules.memory.entity.*;
import xiaozhi.modules.memory.service.impl.*;
import xiaozhi.modules.memory.service.impl.maintenance.*;
import xiaozhi.modules.memory.service.processing.MemorySessionTaskService;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.impl.ModelRuntimeAssembler;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

/**
 * Simulated users through production services, real MySQL mappers and transactions.
 * Only the LLM, device registry and workspace identity boundaries are controlled fixtures.
 * Requires a NEW disposable local database; never accepts a business database URL.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "MEMORY_TEST_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_memory_test.*")
class MemoryConversationJourneyTest {
    private static final String AGENT = "journey-agent";
    private static final String DEVICE = "journey-device-a";
    private static final String SECOND_DEVICE = "journey-device-b";
    private static final String ALICE = "journey-alice";
    private static final String BOB = "journey-bob";
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private SqlSessionTemplate sql;
    private TransactionTemplate tx;
    private MemoryFactDao facts;
    private MemoryRevisionDao revisions;
    private MemoryForgetTombstoneDao tombstones;
    private MemorySessionTaskDao taskDao;
    private MemoryPolicyService policies;
    private MemoryServiceImpl memory;
    private MemoryMaintenanceServiceImpl maintenance;
    private ManagedMemorySnapshotService snapshots;
    private MemorySessionTaskService tasks;
    private LLMService llm;

    @BeforeAll
    void createOnlyInEmptyDisposableDatabase() throws Exception {
        String url = System.getenv("MEMORY_TEST_MYSQL_URL");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/xiaozhi_memory_test(?:\\?.*)?"));
        dataSource = new DriverManagerDataSource(url, "root", System.getenv("MEMORY_TEST_MYSQL_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()", Integer.class),
                "Refusing to initialize a nonempty database");
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/202606291520.sql"),
                new ClassPathResource("db/changelog/202606301800.sql")).execute(dataSource);
        // Apply the memory-only workspace ALTER statements from the existing migration.
        String workspace = new ClassPathResource("db/changelog/202607091100.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        var matcher = java.util.regex.Pattern.compile("'(ALTER TABLE `ai_memory_[^\\n]+?)', 'SELECT").matcher(workspace);
        while (matcher.find()) jdbc.execute(matcher.group(1).replace("''", "'"));
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/202610101200.sql"),
                new ClassPathResource("db/changelog/202610101230.sql")).execute(dataSource);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        var sessionFactory = factory.getObject();
        for (Class<?> mapper : List.of(MemorySourceEventDao.class, MemoryCandidateDao.class, MemoryFactDao.class,
                MemoryRevisionDao.class, MemoryProfileDao.class, MemoryToolBehaviorDao.class,
                MemoryForgetTombstoneDao.class, MemoryPolicyDao.class, MemorySessionTaskDao.class)) {
            sessionFactory.getConfiguration().addMapper(mapper);
        }
        sql = new SqlSessionTemplate(sessionFactory);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wireProductionServices() {
        // This schema was created above in the disposable container and contains only synthetic fixtures.
        for (String table : jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class)) {
            assertTrue(table.matches("ai_memory_[a-z_]+"));
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        facts = sql.getMapper(MemoryFactDao.class);
        revisions = sql.getMapper(MemoryRevisionDao.class);
        tombstones = sql.getMapper(MemoryForgetTombstoneDao.class);
        taskDao = sql.getMapper(MemorySessionTaskDao.class);
        var events = sql.getMapper(MemorySourceEventDao.class);
        var candidates = sql.getMapper(MemoryCandidateDao.class);
        var profiles = sql.getMapper(MemoryProfileDao.class);
        var tools = sql.getMapper(MemoryToolBehaviorDao.class);
        AgentDao agents = mock(AgentDao.class);
        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT);
        agent.setUserId(1L);
        agent.setWorkspaceId("journey-workspace");
        agent.setMemModelId("journey-memory-model");
        when(agents.selectById(AGENT)).thenReturn(agent);
        when(agents.selectOne(any())).thenReturn(agent);
        DeviceDao devices = mock(DeviceDao.class);
        when(devices.selectCount(any())).thenAnswer(call -> {
            QueryWrapper<DeviceEntity> query = call.getArgument(0);
            query.getSqlSegment();
            var values = query.getParamNameValuePairs().values();
            return values.contains(AGENT) && (values.contains(DEVICE) || values.contains(SECOND_DEVICE)) ? 1L : 0L;
        });
        when(devices.selectOne(any())).thenAnswer(call -> {
            QueryWrapper<DeviceEntity> query = call.getArgument(0);
            query.getSqlSegment();
            String mac = query.getParamNameValuePairs().containsValue(SECOND_DEVICE) ? SECOND_DEVICE : DEVICE;
            DeviceEntity device = new DeviceEntity();
            device.setMacAddress(mac);
            device.setAgentId(AGENT);
            return device;
        });
        AgentVoicePrintDao voices = mock(AgentVoicePrintDao.class);
        when(voices.selectBoundIdsByAgentId(AGENT)).thenReturn(List.of(ALICE, BOB));
        MemoryRuntimeRefresh refresh = mock(MemoryRuntimeRefresh.class);
        policies = new MemoryPolicyService(sql.getMapper(MemoryPolicyDao.class), agents, devices, voices,
                mock(WorkspacePermissionService.class), refresh);
        MemoryGovernanceService governance = new MemoryGovernanceService(policies, tombstones, revisions);
        MemoryRequestScopeService scopes = new MemoryRequestScopeService(devices, events);
        MemorySettingsService settings = mock(MemorySettingsService.class);
        when(settings.activeContextMaxChars()).thenReturn(1000);
        llm = mock(LLMService.class);
        var models = mock(ModelConfigService.class);
        ModelConfigEntity model = new ModelConfigEntity();
        model.setId("journey-memory-model");
        when(models.getModelByIdFromCache(model.getId())).thenReturn(model);
        var assembler = mock(ModelRuntimeAssembler.class);
        when(assembler.buildTypedConfig(model)).thenReturn(JSONUtil.parseObj(
                Map.of("type", "managed_memory", "maintenance_llm", "journey-text-model")));
        ReflectionTestUtils.setField(policies, "modelConfigService", models);
        ReflectionTestUtils.setField(policies, "modelRuntimeAssembler", assembler);
        var query = new MemoryQueryServiceImpl(facts, profiles, tools, tombstones, devices, agents, settings);
        maintenance = new MemoryMaintenanceServiceImpl(candidates, facts, revisions, tombstones, devices,
                agents, llm, models, assembler, mock(AgentChatHistoryService.class), new MemoryMaintenanceOutputParser(),
                new MemorySpeakerConversationBuilder(), tx, settings, refresh);
        memory = new MemoryServiceImpl(events, candidates, facts, revisions, profiles, tools, tombstones,
                devices, agents, query, maintenance, settings, refresh);
        snapshots = new ManagedMemorySnapshotService(facts, tombstones);
        for (Object service : List.of(memory, maintenance, snapshots)) ReflectionTestUtils.setField(service, "policyService", policies);
        ReflectionTestUtils.setField(memory, "governance", governance);
        ReflectionTestUtils.setField(maintenance, "governance", governance);
        ReflectionTestUtils.setField(memory, "requestScopes", scopes);
        tasks = new MemorySessionTaskService(taskDao, memory, scopes, policies);
    }

    @AfterEach
    void stopOwnedWorker() {
        if (tasks != null) tasks.close();
    }

    @Test
    void rememberRecallCorrectEditForgetAndAttemptRelearning() {
        modelAction("ADD", null, ALICE, "我喜欢绿茶", "用户喜欢绿茶");
        MemorySessionProcessDTO first = session(DEVICE, ALICE, "我喜欢绿茶");
        assertEquals(1, tasks.process(first));
        assertEquals(1, tasks.process(first), "duplicate receipt returns original result without another model call");
        verify(llm, times(1)).generateJson(anyString(), anyString());
        assertTrue(context(SECOND_DEVICE, ALICE).contains("绿茶"));
        assertEquals("", context(DEVICE, BOB));
        assertEquals("", context(DEVICE, null));
        MemoryFactEntity fact = active().get(0);
        modelAction("UPDATE", fact.getMemoryId(), ALICE, "我现在更喜欢红茶", "用户现在更喜欢红茶");
        assertEquals(1, tasks.process(session(DEVICE, ALICE, "我现在更喜欢红茶")));
        assertFalse(context(DEVICE, ALICE).contains("绿茶"));
        assertTrue(context(DEVICE, ALICE).contains("红茶"));

        MemoryFactDTO edit = new MemoryFactDTO();
        edit.setCanonicalText("用户喜欢乌龙茶");
        edit.setVisibility("private");
        edit.setSpeakerId(ALICE);
        tx.execute(status -> { memory.updateFact(AGENT, fact.getMemoryId(), edit, "user", "1"); return null; });
        assertTrue(context(DEVICE, ALICE).contains("乌龙茶"));
        tx.execute(status -> { memory.deleteFact(AGENT, fact.getMemoryId(), "user", "1"); return null; });
        assertEquals("", context(DEVICE, ALICE));
        assertEquals(1, tombstones.selectCount(new QueryWrapper<>()));
        modelAction("ADD", null, ALICE, "我喜欢乌龙茶", "用户喜欢乌龙茶");
        assertThrows(IllegalStateException.class, () -> tasks.process(session(DEVICE, ALICE, "我喜欢乌龙茶")));
        assertTrue(active().isEmpty(), "forgotten facts must not reappear from old evidence");
        assertTrue(tasks.list(AGENT).stream().noneMatch(view -> view.containsKey("payloadJson")));
        assertTrue(taskDao.selectList(new QueryWrapper<MemorySessionTaskEntity>().eq("status", "SUCCEEDED"))
                .stream().allMatch(task -> task.getPayloadJson() == null));
    }

    @Test
    void deviceIsolationUnknownSpeakerAndReviewPoliciesAreActuallyPersisted() {
        MemoryPolicyDTO policy = new MemoryPolicyDTO();
        policy.setMemoryScope("DEVICE");
        policy.setUnknownSpeakerPolicy("IGNORE");
        tx.execute(status -> { policies.save(AGENT, policy); return null; });
        modelAction("ADD", null, ALICE, "我喜欢游泳", "用户喜欢游泳");
        tasks.process(session(DEVICE, ALICE, "我喜欢游泳"));
        assertTrue(context(DEVICE, ALICE).contains("游泳"));
        assertEquals("", context(SECOND_DEVICE, ALICE));
        int before = mockingDetails(llm).getInvocations().size();
        assertEquals(0, tasks.process(session(DEVICE, null, "我的个人地址是虚构地址")));
        assertEquals(before, mockingDetails(llm).getInvocations().size());
        policy.setMaintenanceMode("REVIEW");
        tx.execute(status -> { policies.save(AGENT, policy); return null; });
        modelAction("ADD", null, BOB, "我喜欢骑车", "用户喜欢骑车");
        tasks.process(session(DEVICE, BOB, "我喜欢骑车"));
        assertEquals("", context(DEVICE, BOB));
        assertEquals(1, sql.getMapper(MemoryCandidateDao.class).selectCount(new QueryWrapper<>()));
    }

    @Test
    void modelFailureIsDurableAndRetryCannotChangeTheSegment() {
        var request = session(DEVICE, ALICE, "我喜欢登山");
        when(llm.generateJson(anyString(), anyString())).thenThrow(new IllegalStateException("synthetic provider failure"));
        assertThrows(IllegalStateException.class, () -> tasks.process(request));
        MemorySessionTaskEntity failed = taskDao.selectList(new QueryWrapper<>()).get(0);
        assertEquals("FAILED", failed.getStatus());
        assertNotNull(failed.getPayloadJson());
        assertEquals("IllegalStateException", failed.getLastError());
        assertTrue(active().isEmpty());
        // Simulates a new service instance after a process restart: all state comes from MySQL.
        tasks.close();
        tasks = new MemorySessionTaskService(taskDao, memory,
                (MemoryRequestScopeService) ReflectionTestUtils.getField(memory, "requestScopes"), policies);
        tasks.retry(AGENT, failed.getTaskId());
        reset(llm);
        modelAction("ADD", null, ALICE, "我喜欢登山", "用户喜欢登山");
        assertEquals(1, tasks.process(request));
        request.getMessages().get(0).setContent("changed after receipt");
        assertThrows(IllegalArgumentException.class, () -> tasks.process(request));
        assertEquals(1, active().size());
    }

    @Test
    void schedulerRecoversPersistedWorkAndStopsExpiredLeasesAtAttemptLimit() {
        var request = session(DEVICE, ALICE, "我喜欢徒步");
        when(llm.generateJson(anyString(), anyString())).thenThrow(new IllegalStateException("controlled failure"));
        assertThrows(IllegalStateException.class, () -> tasks.process(request));
        MemorySessionTaskEntity failed = taskDao.selectList(new QueryWrapper<>()).get(0);
        tasks.close();
        tasks = new MemorySessionTaskService(taskDao, memory,
                (MemoryRequestScopeService) ReflectionTestUtils.getField(memory, "requestScopes"), policies);
        reset(llm);
        modelAction("ADD", null, ALICE, "我喜欢徒步", "用户喜欢徒步");
        jdbc.update("UPDATE ai_memory_session_task SET next_attempt_at = ? WHERE task_id = ?", new Date(1), failed.getTaskId());
        tasks.recoverDueTasks();
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            while (!"SUCCEEDED".equals(taskDao.selectById(failed.getTaskId()).getStatus())) Thread.sleep(20);
        });
        assertEquals(1, active().size());
        assertNull(taskDao.selectById(failed.getTaskId()).getPayloadJson());

        reset(llm);
        when(llm.generateJson(anyString(), anyString())).thenThrow(new IllegalStateException("controlled failure"));
        var stalled = session(DEVICE, ALICE, "我喜欢露营");
        assertThrows(IllegalStateException.class, () -> tasks.process(stalled));
        MemorySessionTaskEntity stalledTask = taskDao.selectList(new QueryWrapper<MemorySessionTaskEntity>().eq("status", "FAILED")).get(0);
        jdbc.update("UPDATE ai_memory_session_task SET status = 'RUNNING', attempts = 5, lease_until = ?, lease_token = 'expired' WHERE task_id = ?",
                new Date(1), stalledTask.getTaskId());
        tasks.recoverDueTasks();
        MemorySessionTaskEntity terminal = taskDao.selectById(stalledTask.getTaskId());
        assertEquals("FAILED", terminal.getStatus());
        assertEquals(5, terminal.getAttempts());
        assertEquals("LeaseExpired", terminal.getLastError());
        assertNull(terminal.getNextAttemptAt());
    }

    @Test
    void concurrentDuplicateCannotExecuteAnotherModelCall() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var request = session(DEVICE, ALICE, "今天聊聊天气");
        when(llm.generateJson(anyString(), anyString())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return "{\"actions\":[]}";
        });
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> tasks.process(request));
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> tasks.process(request));
            } finally {
                release.countDown();
            }
            assertEquals(0, first.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, tasks.process(request));
            verify(llm, times(1)).generateJson(anyString(), anyString());
        }
    }

    @Test
    void fabricatedDeviceCannotCreateAReceiptOrReadAnotherAgentsRetry() {
        assertThrows(IllegalArgumentException.class, () -> tasks.process(session("unknown-device", ALICE, "我喜欢越权")));
        assertEquals(0, taskDao.selectCount(new QueryWrapper<>()));
        assertThrows(IllegalArgumentException.class, () -> tasks.retry("another-agent", "unknown-task"));
        verifyNoInteractions(llm);
    }

    @Test
    void workerLosingLeaseDuringModelCallCannotCommitFacts() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var request = session(DEVICE, ALICE, "我喜欢古典音乐");
        modelAction("ADD", null, ALICE, "我喜欢古典音乐", "用户喜欢古典音乐");
        String proposal = llm.generateJson("fixture", "fixture");
        reset(llm);
        when(llm.generateJson(anyString(), anyString())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return proposal;
        });
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var old = executor.submit(() -> tasks.process(request));
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                jdbc.update("UPDATE ai_memory_session_task SET lease_token = 'new-owner' WHERE status = 'RUNNING'");
            } finally {
                release.countDown();
            }
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> old.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertTrue(active().isEmpty());
        assertEquals(0, revisions.selectCount(new QueryWrapper<MemoryRevisionEntity>().eq("revision_action", "session_memory_processed")));
        assertEquals("new-owner", taskDao.selectList(new QueryWrapper<>()).get(0).getLeaseToken());
    }

    @Test
    void pythonSegmentJsonPassesHttpValidationAndReturnsIdempotentReceipt() throws Exception {
        var controller = new xiaozhi.modules.memory.controller.MemoryConfigController(memory,
                (MemoryRequestScopeService) ReflectionTestUtils.getField(memory, "requestScopes"), tasks);
        var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        String body = JSONUtil.toJsonStr(Map.of("agentId", AGENT, "macAddress", DEVICE,
                "sessionId", "http-session", "segmentId", "0", "messages", List.of(Map.of(
                        "role", "user", "content", JSONUtil.toJsonStr(Map.of("speaker_id", ALICE, "content", "我喜欢钢琴")),
                        "messageId", "http-message", "speakerId", ALICE, "memoryEligible", true))));
        modelAction("ADD", null, ALICE, "我喜欢钢琴", "用户喜欢钢琴");
        for (int attempt = 0; attempt < 2; attempt++) {
            var response = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/config/memory/process-session")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertEquals(1, JSONUtil.parseObj(response).getInt("data"));
        }
        assertTrue(context(DEVICE, ALICE).contains("钢琴"));
        assertEquals("", context(DEVICE, BOB));
        verify(llm, times(1)).generateJson(anyString(), anyString());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/config/memory/process-session")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"messages\":[]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }

    @Test
    void durableHttpReceiptAllowsLaterSegmentsAfterModelFailure() throws Exception {
        var controller = new xiaozhi.modules.memory.controller.MemoryConfigController(memory,
                (MemoryRequestScopeService) ReflectionTestUtils.getField(memory, "requestScopes"), tasks);
        var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        var request = session(DEVICE, ALICE, "我喜欢看海");
        when(llm.generateJson(anyString(), anyString())).thenThrow(new IllegalStateException("controlled provider failure"));
        String body = JSONUtil.toJsonStr(request);
        String response = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/config/memory/submit-session")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var receipt = JSONUtil.parseObj(response).getJSONObject("data");
        assertEquals(Boolean.TRUE, receipt.getBool("accepted"));
        String taskId = receipt.getStr("taskId");
        assertNotNull(taskDao.selectById(taskId), "the HTTP ACK must follow the database insert");
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            while (!"FAILED".equals(taskDao.selectById(taskId).getStatus())) Thread.sleep(20);
        });
        jdbc.update("UPDATE ai_memory_session_task SET attempts = 5, next_attempt_at = NULL WHERE task_id = ?", taskId);
        var failedReceipt = tasks.submit(request);
        assertEquals(true, failedReceipt.get("accepted"));
        assertEquals("FAILED", failedReceipt.get("status"));
        assertNotNull(taskDao.selectById(taskId).getPayloadJson());
        reset(llm);
        modelAction("ADD", null, ALICE, "我喜欢攀岩", "用户喜欢攀岩");
        var next = session(DEVICE, ALICE, "我喜欢攀岩");
        next.setSessionId(request.getSessionId());
        next.setSegmentId("segment-2");
        String nextId = (String) tasks.submit(next).get("taskId");
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            while (!"SUCCEEDED".equals(taskDao.selectById(nextId).getStatus())) Thread.sleep(20);
        });
        assertTrue(context(DEVICE, ALICE).contains("攀岩"));
        assertEquals("FAILED", taskDao.selectById(taskId).getStatus());
    }

    @Test
    void forgedEvidenceRollsBackTheWholeSegment() {
        modelAction("ADD", null, ALICE, "用户没有说过的证据", "用户拥有虚构信息");
        assertThrows(IllegalStateException.class, () -> tasks.process(session(DEVICE, ALICE, "你好")));
        assertTrue(active().isEmpty());
        assertEquals(0, revisions.selectCount(new QueryWrapper<MemoryRevisionEntity>().eq("revision_action", "session_memory_processed")));
    }

    @Test
    void moreThanFiftyForgetRulesDoNotDisableUnrelatedMemory() {
        modelAction("ADD", null, ALICE, "我喜欢摄影", "用户喜欢摄影");
        tasks.process(session(DEVICE, ALICE, "我喜欢摄影"));
        for (int i = 0; i < 60; i++) {
            var rule = new MemoryForgetTombstoneEntity();
            rule.setTombstoneId(UUID.randomUUID().toString());
            rule.setAgentId(AGENT);
            rule.setScopeType("device");
            rule.setScopeId(DEVICE);
            rule.setSpeakerId(ALICE);
            rule.setPatternText("不相关遗忘事实" + i);
            rule.setMatchType("exact");
            rule.setStatus("active");
            rule.setCreatedAt(new Date());
            rule.setUpdatedAt(new Date());
            tombstones.insert(rule);
        }
        assertTrue(context(DEVICE, ALICE).contains("摄影"));
    }

    @Test
    void privateFactsSurviveSharedBudgetAndExpirationStopsInjection() {
        MemoryPolicyDTO policy = new MemoryPolicyDTO();
        policy.setActiveMaxChars(2000);
        policy.setContextMaxItems(3);
        policy.setRetentionDays(1);
        tx.execute(status -> { policies.save(AGENT, policy); return null; });
        for (int i = 0; i < 5; i++) {
            MemoryFactDTO shared = new MemoryFactDTO();
            shared.setCanonicalText("共享事实" + i);
            shared.setVisibility("shared");
            tx.execute(status -> memory.createFact(AGENT, shared, "user", "1"));
        }
        modelAction("ADD", null, ALICE, "我喜欢读书", "用户喜欢读书");
        tasks.process(session(DEVICE, ALICE, "我喜欢读书"));
        String selected = context(DEVICE, ALICE);
        assertTrue(selected.contains("读书"));
        assertTrue(selected.lines().count() <= 3);
        MemoryFactEntity personal = active().stream().filter(fact -> fact.getCanonicalText().contains("读书")).findFirst().orElseThrow();
        assertNotNull(personal.getExpiresAt());
        jdbc.update("UPDATE ai_memory_fact SET expires_at = ? WHERE memory_id = ?", new Date(1), personal.getMemoryId());
        assertFalse(context(DEVICE, ALICE).contains("读书"));
    }

    private List<MemoryFactEntity> active() {
        return facts.selectList(new QueryWrapper<MemoryFactEntity>().eq("agent_id", AGENT).eq("status", "active"));
    }

    private String context(String device, String speaker) {
        return ManagedMemorySnapshotService.selectedContext(snapshots.build(AGENT, device, List.of(ALICE, BOB)), speaker);
    }

    private MemorySessionProcessDTO session(String device, String speaker, String text) {
        MemorySessionMessageDTO message = new MemorySessionMessageDTO();
        message.setRole("user");
        message.setContent(text);
        message.setSpeakerId(speaker == null ? "" : speaker);
        message.setMessageId(UUID.randomUUID().toString());
        message.setMemoryEligible(true);
        MemorySessionProcessDTO request = new MemorySessionProcessDTO();
        request.setAgentId(AGENT);
        request.setMacAddress(device);
        request.setSessionId(UUID.randomUUID().toString());
        request.setSegmentId("segment-1");
        request.setMessages(List.of(message));
        return request;
    }

    private void modelAction(String action, String id, String speaker, String quote, String canonical) {
        Map<String, Object> proposed = new java.util.LinkedHashMap<>();
        proposed.put("action", action);
        proposed.put("related_memory_id", id == null ? "" : id);
        proposed.put("speaker_id", speaker == null ? "" : speaker);
        proposed.put("source_role", "user");
        proposed.put("evidence_quote", quote);
        proposed.put("canonical_text", canonical);
        proposed.put("importance", 0.8);
        proposed.put("confidence", 0.9);
        when(llm.generateJson(anyString(), anyString())).thenReturn(JSONUtil.toJsonStr(Map.of("actions", List.of(proposed))));
    }
}
