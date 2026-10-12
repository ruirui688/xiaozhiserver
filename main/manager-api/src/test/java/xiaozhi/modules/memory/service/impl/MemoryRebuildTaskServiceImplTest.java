package xiaozhi.modules.memory.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;

import xiaozhi.common.exception.RenException;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.memory.dto.MemoryRebuildRequestDTO;
import xiaozhi.modules.memory.service.MemoryMaintenanceService;
import xiaozhi.modules.memory.service.impl.task.MemoryRebuildTaskLockStore;
import xiaozhi.modules.memory.service.impl.task.MemoryRebuildTaskStateStore;
import xiaozhi.modules.memory.vo.MemoryRebuildResultVO;
import xiaozhi.modules.memory.vo.MemoryRebuildTaskVO;

class MemoryRebuildTaskServiceImplTest {

    private static final String AGENT_ID = "agent-1";
    private static final String OTHER_AGENT_ID = "agent-2";

    private final MemoryMaintenanceService maintenanceService = mock(MemoryMaintenanceService.class);
    private final AgentDao agentDao = mock(AgentDao.class);
    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final MemoryRebuildTaskLockStore lockStore = mock(MemoryRebuildTaskLockStore.class);
    private final MemoryRebuildTaskStateStore stateStore = mock(MemoryRebuildTaskStateStore.class);
    private final AsyncTaskExecutor taskExecutor = mock(AsyncTaskExecutor.class);
    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
    private final Future<?> future = mock(Future.class);
    private final Map<String, Object> redis = new ConcurrentHashMap<>();
    private final AtomicReference<Runnable> worker = new AtomicReference<>();
    private final AtomicReference<Runnable> timeout = new AtomicReference<>();
    private final AtomicLong acquiredLockTtl = new AtomicLong();
    private final AtomicInteger casFailuresRemaining = new AtomicInteger();

    private MemoryRebuildTaskServiceImpl service;
    private xiaozhi.common.user.UserDetail principal;

    @org.junit.jupiter.api.AfterEach
    void clearIdentity() { org.apache.shiro.util.ThreadContext.unbindSubject(); }

    @BeforeEach
    void setUp() {
        principal=new xiaozhi.common.user.UserDetail();principal.setAudience("USER");principal.setId(1001L);
        var subject=new org.apache.shiro.subject.support.DelegatingSubject(
                new org.apache.shiro.subject.SimplePrincipalCollection(principal,"test"),true,"localhost",null,new org.apache.shiro.mgt.DefaultSecurityManager());
        org.apache.shiro.util.ThreadContext.bind(subject);
        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT_ID);
        agent.setUserId(1001L);
        agent.setWorkspaceId("workspace-1");
        when(agentDao.selectById(AGENT_ID)).thenReturn(agent);

        doAnswer(invocation -> {
            redis.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(redisUtils).set(anyString(), any(), anyLong());
        when(redisUtils.get(anyString())).thenAnswer(invocation -> redis.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            redis.remove(invocation.getArgument(0));
            return null;
        }).when(redisUtils).delete(anyString());
        doAnswer(invocation -> {
            redis.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(stateStore).set(anyString(), anyString(), anyLong());
        when(stateStore.get(anyString())).thenAnswer(invocation -> redis.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            redis.remove(invocation.getArgument(0));
            return null;
        }).when(stateStore).delete(anyString());
        when(stateStore.compareAndSet(anyString(), anyString(), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    if (casFailuresRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                        return false;
                    }
                    return redis.replace(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2));
                });
        when(lockStore.acquire(anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            acquiredLockTtl.set(invocation.getArgument(2));
            Object existing = redis.putIfAbsent(key, value);
            return existing == null ? null : String.valueOf(existing);
        });
        when(lockStore.release(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            return redis.remove(key, value);
        });
        when(taskExecutor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            worker.set(invocation.getArgument(0));
            return future;
        });
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            timeout.set(invocation.getArgument(0));
            return null;
        });

        service = new MemoryRebuildTaskServiceImpl(
                maintenanceService, agentDao, redisUtils, lockStore, stateStore,
                taskExecutor, taskScheduler, 60_000L);
    }

    @Test
    void workerCarriesAuthenticatedUserToAnotherThreadAndClearsAfterward() throws Exception {
        java.util.concurrent.atomic.AtomicReference<xiaozhi.common.user.UserDetail> observed=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<org.apache.shiro.subject.Subject> inherited=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<org.apache.shiro.subject.Subject> after=new java.util.concurrent.atomic.AtomicReference<>();
        when(maintenanceService.rebuildFromRecentChatHistory(anyString(),any(),anyString(),anyString())).thenAnswer(call->{observed.set(xiaozhi.modules.security.user.SecurityUser.getUser());return new MemoryRebuildResultVO();});
        MemoryRebuildTaskVO task=service.startRebuild(AGENT_ID,request(),"user","1001");
        Thread thread=new Thread(()->{inherited.set(org.apache.shiro.util.ThreadContext.getSubject());worker.get().run();after.set(org.apache.shiro.util.ThreadContext.getSubject());});thread.start();thread.join(5000);
        assertTrue(!thread.isAlive());assertNotNull(inherited.get());assertEquals(principal,inherited.get().getPrincipal());
        assertEquals(principal,observed.get());assertEquals(null,after.get());assertEquals("SUCCEEDED",service.getTask(AGENT_ID,task.getTaskId()).getStatus());
        assertEquals(principal,xiaozhi.modules.security.user.SecurityUser.getUser());
    }

    @Test
    void startPersistsPendingTaskAndDuplicateReturnsSameTask() {
        MemoryRebuildTaskVO first = service.startRebuild(AGENT_ID, request(), "user", "1001");
        MemoryRebuildTaskVO duplicate = service.startRebuild(AGENT_ID, request(), "user", "1001");

        assertEquals("PENDING", first.getStatus());
        assertEquals(first.getTaskId(), duplicate.getTaskId());
        assertEquals(1001L, first.getOwnerUserId());
        assertEquals("workspace-1", first.getWorkspaceId());
        assertNotNull(service.getLatestTask(AGENT_ID));
        verify(taskExecutor, times(1)).submit(any(Runnable.class));
        assertEquals(180L, acquiredLockTtl.get());
    }

    @Test
    void lockAcquisitionFailureDoesNotSubmitWorker() {
        when(lockStore.acquire(anyString(), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThrows(RenException.class,
                () -> service.startRebuild(AGENT_ID, request(), "user", "1001"));

        verify(taskExecutor, never()).submit(any(Runnable.class));
    }

    @Test
    void initialTaskWriteFailureReleasesLockAndDoesNotSubmitWorker() {
        doThrow(new IllegalStateException("redis unavailable"))
                .when(stateStore).set(anyString(), anyString(), anyLong());

        assertThrows(RenException.class,
                () -> service.startRebuild(AGENT_ID, request(), "user", "1001"));

        assertTrue(redis.keySet().stream().noneMatch(key -> key.startsWith("memory:rebuild:active:")));
        verify(taskExecutor, never()).submit(any(Runnable.class));
    }

    @Test
    void noOpTaskWriteIsDetectedAndRolledBack() {
        doNothing().when(stateStore).set(anyString(), anyString(), anyLong());

        assertThrows(RenException.class,
                () -> service.startRebuild(AGENT_ID, request(), "user", "1001"));

        assertTrue(redis.keySet().stream().noneMatch(key -> key.startsWith("memory:rebuild:")));
        verify(taskExecutor, never()).submit(any(Runnable.class));
    }

    @Test
    void latestPointerWriteFailureRollsBackTaskAndLock() {
        doThrow(new IllegalStateException("redis unavailable"))
                .when(redisUtils).set(anyString(), any(), anyLong());

        assertThrows(RenException.class,
                () -> service.startRebuild(AGENT_ID, request(), "user", "1001"));

        assertTrue(redis.keySet().stream().noneMatch(key -> key.startsWith("memory:rebuild:")));
        verify(taskExecutor, never()).submit(any(Runnable.class));
    }

    @Test
    void noOpLatestPointerWriteIsDetectedAndRolledBack() {
        doNothing().when(redisUtils).set(anyString(), any(), anyLong());

        assertThrows(RenException.class,
                () -> service.startRebuild(AGENT_ID, request(), "user", "1001"));

        assertTrue(redis.keySet().stream().noneMatch(key -> key.startsWith("memory:rebuild:")));
        verify(taskExecutor, never()).submit(any(Runnable.class));
    }

    @Test
    void executorRejectionPersistsFailureAndReleasesLock() {
        when(taskExecutor.submit(any(Runnable.class)))
                .thenThrow(new IllegalStateException("executor rejected"));

        MemoryRebuildTaskVO failed = service.startRebuild(AGENT_ID, request(), "user", "1001");

        assertEquals("FAILED", failed.getStatus());
        assertTrue(redis.keySet().stream().noneMatch(key -> key.startsWith("memory:rebuild:active:")));
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void workerPersistsSuccessfulResultAndReleasesAgentLock() {
        MemoryRebuildResultVO result = new MemoryRebuildResultVO();
        result.setSessionsRead(7);
        result.setFactsChanged(3);
        when(maintenanceService.rebuildFromRecentChatHistory(anyString(), any(), anyString(), anyString()))
                .thenReturn(result);

        MemoryRebuildTaskVO started = service.startRebuild(AGENT_ID, request(), "user", "1001");
        worker.get().run();

        MemoryRebuildTaskVO completed = service.getTask(AGENT_ID, started.getTaskId());
        assertEquals("SUCCEEDED", completed.getStatus());
        assertEquals(100, completed.getProgress());
        assertEquals(7, completed.getResult().getSessionsRead());
        assertEquals(3, completed.getResult().getFactsChanged());

        MemoryRebuildTaskVO next = service.startRebuild(AGENT_ID, request(), "user", "1001");
        assertNotEquals(started.getTaskId(), next.getTaskId());
        verify(taskExecutor, times(2)).submit(any(Runnable.class));
    }

    @Test
    void workerFailureIsObservableAndSensitiveValuesAreRedacted() {
        when(maintenanceService.rebuildFromRecentChatHistory(anyString(), any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException(
                        "Authorization: Bearer bearer-secret api_key=key-secret upstream unavailable"));

        MemoryRebuildTaskVO started = service.startRebuild(AGENT_ID, request(), "user", "1001");
        worker.get().run();

        MemoryRebuildTaskVO failed = service.getTask(AGENT_ID, started.getTaskId());
        assertEquals("FAILED", failed.getStatus());
        assertTrue(failed.getErrorMessage().contains("api_key=***"));
        assertTrue(failed.getErrorMessage().contains("Authorization: ***"));
        assertTrue(!failed.getErrorMessage().contains("bearer-secret"));
        assertTrue(!failed.getErrorMessage().contains("key-secret"));
    }

    @Test
    void timeoutCancelsWorkerAndCannotBeOverwrittenByLateCompletion() {
        MemoryRebuildResultVO result = new MemoryRebuildResultVO();
        when(maintenanceService.rebuildFromRecentChatHistory(anyString(), any(), anyString(), anyString()))
                .thenReturn(result);

        MemoryRebuildTaskVO started = service.startRebuild(AGENT_ID, request(), "user", "1001");
        timeout.get().run();
        worker.get().run();

        MemoryRebuildTaskVO timedOut = service.getTask(AGENT_ID, started.getTaskId());
        assertEquals("TIMED_OUT", timedOut.getStatus());
        verify(future).cancel(true);
        verify(maintenanceService, never()).rebuildFromRecentChatHistory(anyString(), any(), anyString(), anyString());
    }

    @Test
    void timeoutFromRestartedInstanceWinsOverInterruptionIgnoringLateCompletion() throws Exception {
        CountDownLatch externalCallEntered = new CountDownLatch(1);
        CountDownLatch allowExternalCallToReturn = new CountDownLatch(1);
        when(maintenanceService.rebuildFromRecentChatHistory(anyString(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    externalCallEntered.countDown();
                    while (true) {
                        try {
                            if (allowExternalCallToReturn.await(1, TimeUnit.SECONDS)) {
                                return new MemoryRebuildResultVO();
                            }
                        } catch (InterruptedException ignored) {
                            // Simulates an external SDK that consumes interruption and still returns.
                        }
                    }
                });

        MemoryRebuildTaskVO started = service.startRebuild(AGENT_ID, request(), "user", "1001");
        Thread workerThread = new Thread(worker.get());
        when(future.cancel(true)).thenAnswer(invocation -> {
            workerThread.interrupt();
            return true;
        });
        workerThread.start();
        assertTrue(externalCallEntered.await(2, TimeUnit.SECONDS));

        MemoryRebuildTaskServiceImpl restartedService = newService(60_000L);
        MemoryRebuildTaskVO persistedRunning = restartedService.getTask(AGENT_ID, started.getTaskId());
        assertEquals("RUNNING", persistedRunning.getStatus());
        timeout.get().run();
        allowExternalCallToReturn.countDown();
        workerThread.join(2_000L);

        assertTrue(!workerThread.isAlive());
        assertEquals("TIMED_OUT", service.getTask(AGENT_ID, started.getTaskId()).getStatus());
    }

    @Test
    void casConflictIsRetriedWithoutLosingSuccessfulCompletion() {
        when(maintenanceService.rebuildFromRecentChatHistory(anyString(), any(), anyString(), anyString()))
                .thenReturn(new MemoryRebuildResultVO());
        MemoryRebuildTaskVO started = service.startRebuild(AGENT_ID, request(), "user", "1001");
        casFailuresRemaining.set(1);

        worker.get().run();

        assertEquals("SUCCEEDED", service.getTask(AGENT_ID, started.getTaskId()).getStatus());
    }

    @Test
    void restartedServiceReadsPersistedPendingTask() {
        MemoryRebuildTaskVO started = service.startRebuild(AGENT_ID, request(), "user", "1001");

        MemoryRebuildTaskVO restored = newService(60_000L).getTask(AGENT_ID, started.getTaskId());

        assertEquals("PENDING", restored.getStatus());
        assertEquals(started.getTaskId(), restored.getTaskId());
    }

    @Test
    void restartedServiceConvertsExpiredPersistedTaskToTimedOut() {
        MemoryRebuildTaskServiceImpl immediateDeadlineService = newService(0L);
        MemoryRebuildTaskVO started = immediateDeadlineService.startRebuild(
                AGENT_ID, request(), "user", "1001");

        MemoryRebuildTaskVO restored = newService(0L).getTask(AGENT_ID, started.getTaskId());

        assertEquals("TIMED_OUT", restored.getStatus());
        assertTrue(redis.keySet().stream().noneMatch(key -> key.startsWith("memory:rebuild:active:")));
    }

    @Test
    void missingTaskBehindActiveLockIsNotReclaimedByDuplicateRequest() {
        redis.put("memory:rebuild:active:" + AGENT_ID, "task-being-persisted");

        assertThrows(RenException.class,
                () -> service.startRebuild(AGENT_ID, request(), "user", "1001"));

        assertEquals("task-being-persisted", redis.get("memory:rebuild:active:" + AGENT_ID));
        verify(taskExecutor, never()).submit(any(Runnable.class));
    }

    @Test
    void schedulerFailureCancelsSubmittedWorkerAndPersistsFailure() {
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new IllegalStateException("scheduler unavailable"));

        MemoryRebuildTaskVO failed = service.startRebuild(AGENT_ID, request(), "user", "1001");

        assertEquals("FAILED", failed.getStatus());
        verify(future).cancel(true);
    }

    @Test
    void taskCannotBeReadThroughAnotherAgent() {
        MemoryRebuildTaskVO started = service.startRebuild(AGENT_ID, request(), "user", "1001");

        assertThrows(RenException.class, () -> service.getTask(OTHER_AGENT_ID, started.getTaskId()));
    }

    private MemoryRebuildRequestDTO request() {
        MemoryRebuildRequestDTO request = new MemoryRebuildRequestDTO();
        request.setSessionLimit(12);
        request.setMode("clear_then_rebuild");
        return request;
    }

    private MemoryRebuildTaskServiceImpl newService(long timeoutMs) {
        return new MemoryRebuildTaskServiceImpl(
                maintenanceService, agentDao, redisUtils, lockStore, stateStore,
                taskExecutor, taskScheduler, timeoutMs);
    }

}
