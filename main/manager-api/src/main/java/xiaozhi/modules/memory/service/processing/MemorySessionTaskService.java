package xiaozhi.modules.memory.service.processing;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.modules.memory.dao.MemorySessionTaskDao;
import xiaozhi.modules.memory.dto.MemorySessionProcessDTO;
import xiaozhi.modules.memory.entity.MemorySessionTaskEntity;
import xiaozhi.modules.memory.service.MemoryPolicyService;
import xiaozhi.modules.memory.service.MemoryRequestScopeService;
import xiaozhi.modules.memory.service.MemoryService;
import xiaozhi.modules.memory.utils.MemoryHelper;

/** Durable receipt before model execution. Queue payloads never leave this service. */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemorySessionTaskService {
    private static final int MAX_ATTEMPTS = 5;
    private static final long LEASE_MS = 10 * 60_000L;
    private final MemorySessionTaskDao tasks;
    private final MemoryService memory;
    private final MemoryRequestScopeService scopes;
    private final MemoryPolicyService policies;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), action -> {
                Thread thread = new Thread(action, "memory-session-recovery");
                thread.setDaemon(true);
                return thread;
            });

    public int process(MemorySessionProcessDTO request) {
        MemorySessionTaskEntity task = receive(request);
        if (completed(task)) return task.getFactsChanged();
        return execute(task.getTaskId());
    }

    /** Acknowledge durable ownership independently of the eventual model outcome. */
    public Map<String, Object> submit(MemorySessionProcessDTO request) {
        MemorySessionTaskEntity task = receive(request);
        if (!completed(task)) schedule(task.getTaskId());
        return Map.of("accepted", true, "taskId", task.getTaskId(), "status", task.getStatus(),
                "factsChanged", task.getFactsChanged());
    }

    private MemorySessionTaskEntity receive(MemorySessionProcessDTO request) {
        scopes.require(request.getAgentId(), request.getMacAddress(), request.getSessionId());
        String json = JSONUtil.toJsonStr(request);
        String key = MemoryHelper.sha256(JSONUtil.toJsonStr(List.of(request.getAgentId(),
                request.getMacAddress(), request.getSessionId(),
                request.getSegmentId() == null ? "" : request.getSegmentId())));
        String hash = MemoryHelper.sha256(json);
        MemorySessionTaskEntity task = tasks.selectById(key);
        if (task == null) {
            if (tasks.selectCount(new QueryWrapper<MemorySessionTaskEntity>().eq("agent_id", request.getAgentId())
                    .in("status", "PENDING", "RUNNING", "FAILED")) >= 100) {
                throw new IllegalStateException("记忆整理待处理任务已达上限，请处理失败任务");
            }
            task = new MemorySessionTaskEntity();
            task.setTaskId(key);
            task.setAgentId(request.getAgentId());
            task.setDeviceId(request.getMacAddress());
            task.setSessionId(request.getSessionId());
            task.setRequestHash(hash);
            task.setPayloadJson(json);
            task.setStatus("PENDING");
            task.setAttempts(0);
            task.setFactsChanged(0);
            task.setCreatedAt(new Date());
            task.setUpdatedAt(new Date());
            task.setNextAttemptAt(new Date());
            try {
                tasks.insert(task);
            } catch (DuplicateKeyException concurrentReceipt) {
                task = tasks.selectById(key);
            }
        }
        if (task == null || !hash.equals(task.getRequestHash())) {
            throw new IllegalArgumentException("同一记忆分段的内容不能在重试时更改");
        }
        return task;
    }

    private int execute(String taskId) {
        Date now = new Date();
        String lease = UUID.randomUUID().toString();
        UpdateWrapper<MemorySessionTaskEntity> claim = new UpdateWrapper<MemorySessionTaskEntity>()
                .eq("task_id", taskId).isNotNull("payload_json")
                .and(w -> w.nested(due -> due.in("status", "PENDING", "FAILED")
                                .lt("attempts", MAX_ATTEMPTS).le("next_attempt_at", now))
                        .or(stale -> stale.eq("status", "RUNNING").lt("attempts", MAX_ATTEMPTS).le("lease_until", now)))
                .set("status", "RUNNING").setSql("attempts = attempts + 1")
                .set("lease_token", lease).set("lease_until", new Date(now.getTime() + LEASE_MS))
                .set("updated_at", now);
        if (tasks.update(null, claim) == 0) {
            MemorySessionTaskEntity existing = tasks.selectById(taskId);
            if (existing != null && completed(existing)) return existing.getFactsChanged();
            throw new IllegalStateException("记忆整理处理中或等待重试，请在任务列表查看状态");
        }
        MemorySessionTaskEntity task = tasks.selectById(taskId);
        try {
            MemorySessionProcessDTO request = JSONUtil.toBean(task.getPayloadJson(), MemorySessionProcessDTO.class);
            // Recheck current binding or recorded old-session evidence before every retry.
            scopes.require(request.getAgentId(), request.getMacAddress(), request.getSessionId());
            int changed = MemorySessionTaskContext.within(() -> {
                // The row lock lives until the fact transaction commits; a stale worker cannot write.
                MemorySessionTaskEntity current = tasks.selectOne(new QueryWrapper<MemorySessionTaskEntity>()
                        .eq("task_id", taskId).eq("lease_token", lease).eq("status", "RUNNING").last("FOR UPDATE"));
                if (current == null || current.getLeaseUntil() == null || !current.getLeaseUntil().after(new Date()))
                    throw new IllegalStateException("记忆任务执行租约已失效");
            }, () -> memory.processSession(request));
            int acknowledged = tasks.update(null, owned(taskId, lease).set("status", changed == 0 ? "NO_CHANGE" : "SUCCEEDED")
                    .set("facts_changed", changed).set("payload_json", null).set("last_error", null)
                    .set("next_attempt_at", null).set("lease_until", null).set("lease_token", null));
            if (acknowledged == 0) throw new IllegalStateException("记忆任务已由其他执行者接管");
            return changed;
        } catch (RuntimeException failure) {
            long delay = Math.min(300_000L, 10_000L << Math.min(task.getAttempts(), 5));
            Date retryAt = task.getAttempts() < MAX_ATTEMPTS ? new Date(System.currentTimeMillis() + delay) : null;
            tasks.update(null, owned(taskId, lease).set("status", "FAILED")
                    .set("last_error", failure.getClass().getSimpleName()).set("next_attempt_at", retryAt)
                    .set("lease_until", null).set("lease_token", null));
            log.warn("记忆整理任务失败: taskId={}, attempt={}, errorType={}",
                    taskId, task.getAttempts(), failure.getClass().getSimpleName());
            throw failure;
        }
    }

    private UpdateWrapper<MemorySessionTaskEntity> owned(String id, String lease) {
        return new UpdateWrapper<MemorySessionTaskEntity>().eq("task_id", id).eq("lease_token", lease)
                .set("updated_at", new Date());
    }

    private boolean completed(MemorySessionTaskEntity task) {
        return "SUCCEEDED".equals(task.getStatus()) || "NO_CHANGE".equals(task.getStatus());
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    public void recoverDueTasks() {
        if (worker.isShutdown() || worker.getQueue().remainingCapacity() == 0) return;
        Date now = new Date();
        tasks.update(null, new UpdateWrapper<MemorySessionTaskEntity>()
                .eq("status", "RUNNING").ge("attempts", MAX_ATTEMPTS).le("lease_until", now)
                .set("status", "FAILED").set("last_error", "LeaseExpired")
                .set("next_attempt_at", null).set("lease_token", null).set("lease_until", null)
                .set("updated_at", now));
        List<MemorySessionTaskEntity> due = tasks.selectList(new QueryWrapper<MemorySessionTaskEntity>()
                .select("task_id")
                .isNotNull("payload_json")
                .and(w -> w.nested(ready -> ready.in("status", "PENDING", "FAILED")
                                .lt("attempts", MAX_ATTEMPTS).le("next_attempt_at", now))
                        .or(stale -> stale.eq("status", "RUNNING").lt("attempts", MAX_ATTEMPTS).le("lease_until", now)))
                .orderByAsc("created_at").last("LIMIT 4"));
        for (MemorySessionTaskEntity task : due) {
            if (!schedule(task.getTaskId())) break;
        }
    }

    private boolean schedule(String taskId) {
        try {
            worker.execute(() -> {
                try {
                    execute(taskId);
                } catch (RuntimeException recordedOrClaimed) {
                    // Failure is recorded, or another instance owns the database lease.
                }
            });
            return true;
        } catch (RejectedExecutionException full) {
            return false; // The durable receipt remains eligible for the next recovery pass.
        }
    }

    public List<Map<String, Object>> list(String agentId) {
        policies.requireAccess(agentId, false);
        return tasks.selectList(new QueryWrapper<MemorySessionTaskEntity>().eq("agent_id", agentId)
                .select("task_id", "status", "attempts", "facts_changed", "last_error", "created_at", "updated_at", "next_attempt_at")
                .orderByDesc("updated_at").last("LIMIT 50")).stream().map(task -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("taskId", task.getTaskId());
                    view.put("status", task.getStatus());
                    view.put("attempts", task.getAttempts());
                    view.put("factsChanged", task.getFactsChanged());
                    view.put("lastError", task.getLastError());
                    view.put("createdAt", task.getCreatedAt());
                    view.put("updatedAt", task.getUpdatedAt());
                    view.put("nextAttemptAt", task.getNextAttemptAt());
                    return view;
                }).toList();
    }

    public void retry(String agentId, String taskId) {
        policies.requireAccess(agentId, true);
        int changed = tasks.update(null, new UpdateWrapper<MemorySessionTaskEntity>().eq("agent_id", agentId)
                .eq("task_id", taskId).eq("status", "FAILED").isNotNull("payload_json")
                .set("status", "PENDING").set("attempts", 0).set("next_attempt_at", new Date())
                .set("last_error", null).set("updated_at", new Date()));
        if (changed == 0) throw new IllegalArgumentException("任务不存在、不可重试或不属于当前智能体");
    }

    @PreDestroy
    public void close() {
        worker.shutdownNow();
    }
}
