package xiaozhi.modules.memory.service.impl;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Future;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.memory.dto.MemoryRebuildRequestDTO;
import xiaozhi.modules.memory.service.MemoryMaintenanceService;
import xiaozhi.modules.memory.service.MemoryRebuildTaskService;
import xiaozhi.modules.memory.service.impl.task.MemoryRebuildTaskContext;
import xiaozhi.modules.memory.service.impl.task.MemoryRebuildTaskLockStore;
import xiaozhi.modules.memory.service.impl.task.MemoryRebuildTaskStateStore;
import xiaozhi.modules.memory.service.impl.task.MemoryRebuildTaskTimeoutException;
import xiaozhi.modules.memory.vo.MemoryRebuildResultVO;
import xiaozhi.modules.memory.vo.MemoryRebuildTaskVO;

@Service
@Slf4j
public class MemoryRebuildTaskServiceImpl implements MemoryRebuildTaskService {

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_RUNNING = "RUNNING";
    static final String STATUS_SUCCEEDED = "SUCCEEDED";
    static final String STATUS_FAILED = "FAILED";
    static final String STATUS_TIMED_OUT = "TIMED_OUT";
    static final long TASK_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long TASK_STATE_TTL_SECONDS = RedisUtils.DEFAULT_EXPIRE;
    private static final long ACTIVE_LOCK_GRACE_SECONDS = 120L;
    private static final int MAX_STATE_UPDATE_ATTEMPTS = 8;
    private static final String TASK_KEY_PREFIX = "memory:rebuild:task:";
    private static final String ACTIVE_KEY_PREFIX = "memory:rebuild:active:";
    private static final String LATEST_KEY_PREFIX = "memory:rebuild:latest:";
    private final MemoryMaintenanceService maintenanceService;
    private final AgentDao agentDao;
    private final RedisUtils redisUtils;
    private final MemoryRebuildTaskLockStore lockStore;
    private final MemoryRebuildTaskStateStore stateStore;
    private final AsyncTaskExecutor taskExecutor;
    private final TaskScheduler taskScheduler;
    private final long taskTimeoutMs;

    @Autowired
    public MemoryRebuildTaskServiceImpl(
            MemoryMaintenanceService maintenanceService,
            AgentDao agentDao,
            RedisUtils redisUtils,
            MemoryRebuildTaskLockStore lockStore,
            MemoryRebuildTaskStateStore stateStore,
            @Qualifier("memoryRebuildTaskExecutor") AsyncTaskExecutor taskExecutor,
            @Qualifier("memoryRebuildTaskScheduler") TaskScheduler taskScheduler) {
        this(maintenanceService, agentDao, redisUtils, lockStore, stateStore,
                taskExecutor, taskScheduler, TASK_TIMEOUT_MS);
    }

    MemoryRebuildTaskServiceImpl(MemoryMaintenanceService maintenanceService, AgentDao agentDao,
                                 RedisUtils redisUtils, MemoryRebuildTaskLockStore lockStore,
                                 MemoryRebuildTaskStateStore stateStore,
                                 AsyncTaskExecutor taskExecutor,
                                 TaskScheduler taskScheduler, long taskTimeoutMs) {
        this.maintenanceService = maintenanceService;
        this.agentDao = agentDao;
        this.redisUtils = redisUtils;
        this.lockStore = lockStore;
        this.stateStore = stateStore;
        this.taskExecutor = taskExecutor;
        this.taskScheduler = taskScheduler;
        this.taskTimeoutMs = taskTimeoutMs;
    }

    @Override
    public MemoryRebuildTaskVO startRebuild(String agentId, MemoryRebuildRequestDTO request,
                                            String actorType, String actorId) {
        xiaozhi.modules.security.ops.OpsAccess.requireUser();
        org.apache.shiro.subject.Subject subject = xiaozhi.modules.security.user.SecurityUser.getSubject();
        if (subject == null || !subject.isAuthenticated()) throw new RenException("记忆重建需要已认证用户身份");
        AgentEntity agent = agentDao.selectById(agentId);
        if (agent == null) {
            throw new RenException("智能体不存在");
        }
        MemoryRebuildRequestDTO normalizedRequest = normalizeRequest(request);
        String taskId = newTaskId();
        String existingTaskId;
        try {
            existingTaskId = lockStore.acquire(activeKey(agentId), taskId, activeLockTtlSeconds());
        } catch (RuntimeException e) {
            releaseActiveTask(agentId, taskId);
            throw new RenException("无法创建记忆重建任务，请检查 Redis 服务");
        }
        if (StringUtils.isNotBlank(existingTaskId)) {
            MemoryRebuildTaskVO existing = loadTask(existingTaskId);
            if (existing != null && !existing.isTerminal()) {
                return existing;
            }
            if (existing == null) {
                throw new RenException("已有记忆重建任务正在启动，请稍后查询任务状态");
            }
            releaseActiveTask(agentId, existingTaskId);
            try {
                existingTaskId = lockStore.acquire(activeKey(agentId), taskId, activeLockTtlSeconds());
            } catch (RuntimeException e) {
                releaseActiveTask(agentId, taskId);
                throw new RenException("无法创建记忆重建任务，请检查 Redis 服务");
            }
            if (StringUtils.isNotBlank(existingTaskId)) {
                MemoryRebuildTaskVO concurrent = loadTask(existingTaskId);
                if (concurrent != null) {
                    return concurrent;
                }
                throw new RenException("已有记忆重建任务正在启动，请稍后查询任务状态");
            }
        }

        MemoryRebuildTaskVO task = createTask(taskId, agent, normalizedRequest, actorId);
        persistNewTask(task);

        Future<?> future = null;
        try {
            Thread caller = Thread.currentThread();
            Runnable authenticatedWork = subject.associateWith(
                    (Runnable) () -> runTask(taskId, normalizedRequest, actorType, actorId));
            future = taskExecutor.submit(() -> {
                boolean workerThread = Thread.currentThread() != caller;
                // Shiro inherits request resources when a pool first creates a worker.
                // Clear that inherited state, otherwise associateWith would restore it afterward.
                // A synchronous executor must preserve the calling request's existing identity.
                if (workerThread) org.apache.shiro.util.ThreadContext.remove();
                try {
                    authenticatedWork.run();
                } finally {
                    if (workerThread) org.apache.shiro.util.ThreadContext.remove();
                }
            });
            Future<?> submittedFuture = future;
            taskScheduler.schedule(() -> timeoutTask(taskId, submittedFuture),
                    Instant.ofEpochMilli(task.getDeadlineAt().getTime()));
        } catch (RuntimeException e) {
            if (future != null) {
                future.cancel(true);
            }
            finishFailed(taskId, "任务执行队列已满，请稍后重试");
        }
        return loadTask(taskId);
    }

    @Override
    public MemoryRebuildTaskVO getLatestTask(String agentId) {
        Object value = redisUtils.get(latestKey(agentId));
        if (value == null) {
            return null;
        }
        return getTask(agentId, String.valueOf(value));
    }

    @Override
    public MemoryRebuildTaskVO getTask(String agentId, String taskId) {
        if (StringUtils.isBlank(taskId)) {
            throw new RenException("任务 ID 不能为空");
        }
        MemoryRebuildTaskVO task = loadTask(taskId);
        if (task == null || !StringUtils.equals(agentId, task.getAgentId())) {
            throw new RenException("记忆重建任务不存在");
        }
        if (!task.isTerminal() && task.getDeadlineAt() != null
                && System.currentTimeMillis() >= task.getDeadlineAt().getTime()) {
            finishTimedOut(taskId);
            task = loadTask(taskId);
        }
        return task;
    }

    private void runTask(String taskId, MemoryRebuildRequestDTO request, String actorType, String actorId) {
        MemoryRebuildTaskVO task = loadTask(taskId);
        if (task == null || task.isTerminal()) {
            return;
        }
        long deadline = task.getDeadlineAt().getTime();
        try {
            updateProgress(taskId, 5, "STARTING", "重建任务已开始");
            MemoryRebuildResultVO result = MemoryRebuildTaskContext.execute(
                    deadline,
                    (progress, stage, message) -> updateProgress(taskId, progress, stage, message),
                    () -> maintenanceService.rebuildFromRecentChatHistory(
                            task.getAgentId(), request, actorType, actorId));
            finishSucceeded(taskId, result);
        } catch (MemoryRebuildTaskTimeoutException e) {
            finishTimedOut(taskId);
        } catch (Exception e) {
            String safeMessage = safeFailureMessage(e);
            log.warn("记忆重建任务失败，taskId={}, agentId={}, type={}, message={}",
                    taskId, task.getAgentId(), e.getClass().getSimpleName(), safeMessage);
            finishFailed(taskId, safeMessage);
        }
    }

    private void timeoutTask(String taskId, Future<?> future) {
        if (finishTimedOut(taskId)) {
            future.cancel(true);
        }
    }

    private void updateProgress(String taskId, int progress, String stage, String message) {
        boolean updated = mutateTask(taskId, task -> {
            if (task.isTerminal()) {
                throw new MemoryRebuildTaskTimeoutException("记忆重建任务已结束");
            }
            Date now = new Date();
            task.setStatus(STATUS_RUNNING);
            task.setProgress(Math.max(task.getProgress(), Math.min(progress, 99)));
            task.setStage(stage);
            task.setMessage(message);
            if (task.getStartedAt() == null) {
                task.setStartedAt(now);
            }
            task.setUpdatedAt(now);
            return true;
        });
        if (!updated) {
            throw new MemoryRebuildTaskTimeoutException("记忆重建任务状态不存在");
        }
    }

    private void finishSucceeded(String taskId, MemoryRebuildResultVO result) {
        boolean updated = mutateTask(taskId, task -> {
            if (task.isTerminal()) {
                return false;
            }
            Date now = new Date();
            task.setStatus(STATUS_SUCCEEDED);
            task.setProgress(100);
            task.setStage("COMPLETED");
            task.setMessage("记忆重建已完成");
            task.setResult(result);
            finishFields(task, now);
            return true;
        });
        if (updated) {
            releaseTaskLock(taskId);
        }
    }

    private void finishFailed(String taskId, String message) {
        boolean updated = mutateTask(taskId, task -> {
            if (task.isTerminal()) {
                return false;
            }
            Date now = new Date();
            task.setStatus(STATUS_FAILED);
            task.setProgress(100);
            task.setStage("FAILED");
            task.setMessage("记忆重建失败");
            task.setErrorMessage(message);
            finishFields(task, now);
            return true;
        });
        if (updated) {
            releaseTaskLock(taskId);
        }
    }

    private boolean finishTimedOut(String taskId) {
        boolean updated = mutateTask(taskId, task -> {
            if (task.isTerminal()) {
                return false;
            }
            markTimedOutFields(task);
            return true;
        });
        if (updated) {
            releaseTaskLock(taskId);
        }
        return updated;
    }

    private void markTimedOutFields(MemoryRebuildTaskVO task) {
        Date now = new Date();
        task.setStatus(STATUS_TIMED_OUT);
        task.setProgress(100);
        task.setStage("TIMED_OUT");
        task.setMessage("记忆重建已超时，请刷新确认当前记忆状态");
        task.setErrorMessage("维护模型或数据应用在 10 分钟内未完成，请检查后重试");
        finishFields(task, now);
    }

    private void finishFields(MemoryRebuildTaskVO task, Date now) {
        task.setFinishedAt(now);
        task.setUpdatedAt(now);
        Date startedAt = task.getStartedAt() == null ? task.getCreatedAt() : task.getStartedAt();
        task.setDurationMs(Math.max(0L, now.getTime() - startedAt.getTime()));
    }

    private MemoryRebuildTaskVO createTask(String taskId, AgentEntity agent,
                                           MemoryRebuildRequestDTO request, String actorId) {
        Date now = new Date();
        MemoryRebuildTaskVO task = new MemoryRebuildTaskVO();
        task.setTaskId(taskId);
        task.setAgentId(agent.getId());
        task.setOwnerUserId(agent.getUserId());
        task.setWorkspaceId(agent.getWorkspaceId());
        task.setActorId(actorId);
        task.setStatus(STATUS_PENDING);
        task.setProgress(0);
        task.setStage("QUEUED");
        task.setMessage("记忆重建任务已进入队列");
        task.setSessionLimit(request.getSessionLimit());
        task.setMode(request.getMode());
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setDeadlineAt(new Date(now.getTime() + taskTimeoutMs));
        return task;
    }

    private MemoryRebuildRequestDTO normalizeRequest(MemoryRebuildRequestDTO request) {
        MemoryRebuildRequestDTO normalized = new MemoryRebuildRequestDTO();
        int sessionLimit = request == null || request.getSessionLimit() == null
                ? 15 : Math.max(1, Math.min(request.getSessionLimit(), 50));
        normalized.setSessionLimit(sessionLimit);
        normalized.setMode(StringUtils.defaultIfBlank(request == null ? null : request.getMode(),
                "clear_then_rebuild"));
        normalized.setDryRun(false);
        normalized.setDeviceId(request == null ? null : request.getDeviceId());
        return normalized;
    }

    private MemoryRebuildTaskVO loadTask(String taskId) {
        TaskSnapshot snapshot = loadTaskSnapshot(taskId);
        return snapshot == null ? null : snapshot.task();
    }

    private TaskSnapshot loadTaskSnapshot(String taskId) {
        Object value = stateStore.get(taskKey(taskId));
        if (value == null) {
            return null;
        }
        String serialized = String.valueOf(value);
        try {
            MemoryRebuildTaskVO task = JsonUtils.parseObject(serialized, MemoryRebuildTaskVO.class);
            return task == null ? null : new TaskSnapshot(serialized, task);
        } catch (RuntimeException e) {
            log.warn("读取记忆重建任务状态失败，taskId={}", taskId);
            return null;
        }
    }

    private void persistNewTask(MemoryRebuildTaskVO task) {
        String taskId = task.getTaskId();
        String agentId = task.getAgentId();
        String serialized = JsonUtils.toJsonString(task);
        try {
            stateStore.set(taskKey(taskId), serialized, TASK_STATE_TTL_SECONDS);
            redisUtils.set(latestKey(agentId), taskId, TASK_STATE_TTL_SECONDS);

            Object persistedTask = stateStore.get(taskKey(taskId));
            Object latestTaskId = redisUtils.get(latestKey(agentId));
            if (!serialized.equals(String.valueOf(persistedTask))
                    || !taskId.equals(String.valueOf(latestTaskId))) {
                throw new IllegalStateException("Redis task state verification failed");
            }
        } catch (RuntimeException e) {
            rollbackNewTask(task);
            throw new RenException("无法保存记忆重建任务，请检查 Redis 服务");
        }
    }

    private void rollbackNewTask(MemoryRebuildTaskVO task) {
        try {
            stateStore.delete(taskKey(task.getTaskId()));
        } catch (RuntimeException cleanupError) {
            log.warn("回滚记忆重建任务状态失败，taskId={}", task.getTaskId());
        }
        try {
            lockStore.release(latestKey(task.getAgentId()), task.getTaskId());
        } catch (RuntimeException cleanupError) {
            log.warn("回滚记忆重建任务最新指针失败，taskId={}, agentId={}",
                    task.getTaskId(), task.getAgentId());
        }
        releaseActiveTask(task.getAgentId(), task.getTaskId());
    }

    private boolean mutateTask(String taskId, TaskMutation mutation) {
        for (int attempt = 0; attempt < MAX_STATE_UPDATE_ATTEMPTS; attempt++) {
            TaskSnapshot snapshot = loadTaskSnapshot(taskId);
            if (snapshot == null || !mutation.apply(snapshot.task())) {
                return false;
            }
            String updated = JsonUtils.toJsonString(snapshot.task());
            if (stateStore.compareAndSet(
                    taskKey(taskId), snapshot.serialized(), updated, TASK_STATE_TTL_SECONDS)) {
                return true;
            }
        }
        log.warn("更新记忆重建任务状态冲突次数过多，taskId={}", taskId);
        return false;
    }

    private long activeLockTtlSeconds() {
        long timeoutSeconds = Math.max(1L, (taskTimeoutMs + 999L) / 1000L);
        return timeoutSeconds + ACTIVE_LOCK_GRACE_SECONDS;
    }

    private void releaseTaskLock(String taskId) {
        try {
            MemoryRebuildTaskVO task = loadTask(taskId);
            if (task != null) {
                releaseActiveTask(task.getAgentId(), taskId);
            }
        } catch (RuntimeException e) {
            log.warn("读取记忆重建任务锁归属失败，taskId={}", taskId);
        }
    }

    private void releaseActiveTask(String agentId, String taskId) {
        try {
            lockStore.release(activeKey(agentId), taskId);
        } catch (RuntimeException e) {
            log.warn("释放记忆重建任务锁失败，taskId={}, agentId={}", taskId, agentId);
        }
    }

    private String safeFailureMessage(Exception error) {
        String message = StringUtils.defaultIfBlank(error.getMessage(), "维护模型调用或重建校验失败");
        message = message.replaceAll(
                "(?i)(authorization\\s*[:=]\\s*)(?:bearer\\s+)?[^\\s,;，；]+",
                "$1***");
        message = message.replaceAll(
                "(?i)\\bbearer\\s+[^\\s,;，；]+",
                "Bearer ***");
        message = message.replaceAll(
                "(?i)(api[_-]?key|api[_-]?secret|secret[_-]?key|access[_-]?token|token|password)\\s*[:=]\\s*[^\\s,;，；]+",
                "$1=***");
        return StringUtils.abbreviate(message, 500);
    }

    private String newTaskId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String taskKey(String taskId) {
        return TASK_KEY_PREFIX + taskId;
    }

    private String activeKey(String agentId) {
        return ACTIVE_KEY_PREFIX + agentId;
    }

    private String latestKey(String agentId) {
        return LATEST_KEY_PREFIX + agentId;
    }

    private record TaskSnapshot(String serialized, MemoryRebuildTaskVO task) {
    }

    @FunctionalInterface
    private interface TaskMutation {
        boolean apply(MemoryRebuildTaskVO task);
    }
}
