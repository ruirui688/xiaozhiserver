package xiaozhi.modules.reminder.service;

import java.util.*;
import java.util.concurrent.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.modules.reminder.dao.*;
import xiaozhi.modules.reminder.config.ReminderTaskProperties;
import xiaozhi.modules.reminder.entity.ReminderEntity;

/** One indexed cursor per schedule, global scan/rate budgets, no unbounded worker queue. */
@Service
@Slf4j
public class ReminderDispatchService {
    static final long LEASE_MS = 30_000L;
    private final ReminderDao reminderDao;
    private final ReminderDeliveryExecutor deliveryExecutor;
    private final ReminderTaskDao taskDao;
    private final ReminderTaskProperties properties;
    private final ReminderTaskMetrics metrics;
    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor maintenanceWorkers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), action -> {
                Thread thread = new Thread(action, "reminder-maintenance");
                thread.setDaemon(true);
                return thread;
            });
    private final Set<String> activeDevices = ConcurrentHashMap.newKeySet();

    public ReminderDispatchService(ReminderDao reminderDao, ReminderDeliveryExecutor deliveryExecutor,
            ReminderTaskDao taskDao, ReminderTaskProperties properties, ReminderTaskMetrics metrics) {
        this.reminderDao = reminderDao;
        this.deliveryExecutor = deliveryExecutor;
        this.taskDao = taskDao;
        this.properties = properties;
        this.metrics = metrics;
        workers = new ThreadPoolExecutor(properties.getWorkers(), properties.getWorkers(), 0, TimeUnit.SECONDS,
                new SynchronousQueue<>(), action -> {
                    Thread thread = new Thread(action, "reminder-dispatch");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Scheduled(fixedDelayString = "#{@reminderTaskProperties.scanMs}", initialDelay = 5000)
    public void dispatchDueReminders() {
        if (workers.isShutdown() || workers.getActiveCount() >= properties.getWorkers()) return;
        if (taskDao.claimScan(properties.getScanMs()) == 0) return;
        Date now = new Date();
        List<ReminderEntity> due = reminderDao.selectList(new QueryWrapper<ReminderEntity>()
                .select("id", "device_id", "workspace_id", "next_attempt_at")
                .eq("status", "PENDING").le("next_attempt_at", now)
                // Retry cursors never precede scheduled_at. This lower index bound skips an ancient expiry backlog.
                .gt("next_attempt_at", new Date(now.getTime() - properties.getGraceSeconds() * 1000L))
                .gt("remind_at", new Date(now.getTime() - properties.getGraceSeconds() * 1000L))
                .and(scope -> scope.isNull("lease_until").or().le("lease_until", now))
                .orderByAsc("next_attempt_at", "id").last("LIMIT " + properties.getBatchSize()));
        metrics.selectedLag(due.isEmpty() ? 0 : now.getTime() - due.getFirst().getNextAttemptAt().getTime());
        // Round-robin across workspaces in this bounded oldest-ready page, then one active delivery per device.
        Map<String, Queue<ReminderEntity>> workspaces = new LinkedHashMap<>();
        for (ReminderEntity item : due) workspaces.computeIfAbsent(item.getWorkspaceId(), key -> new ArrayDeque<>()).add(item);
        boolean remaining = true;
        while (remaining) {
            remaining = false;
            for (Queue<ReminderEntity> queue : workspaces.values()) {
                ReminderEntity item = queue.poll();
                if (item == null) continue;
                remaining = true;
                if (!activeDevices.add(item.getDeviceId())) continue;
                try {
                    workers.execute(() -> {
                        try { dispatchOne(item.getId()); }
                        finally { activeDevices.remove(item.getDeviceId()); }
                    });
                } catch (RejectedExecutionException full) {
                    activeDevices.remove(item.getDeviceId());
                    return;
                }
            }
        }
    }

    void dispatchOne(String reminderId) {
        if (taskDao.acquirePermit(properties.getDispatchPerSecond()) == 0) { metrics.throttled(); return; }
        Date now = new Date();
        String token = UUID.randomUUID().toString();
        int claimed = reminderDao.update(null, new UpdateWrapper<ReminderEntity>()
                .eq("id", reminderId).eq("status", "PENDING").le("next_attempt_at", now)
                .and(scope -> scope.isNull("lease_until").or().le("lease_until", now))
                .set("lease_token", token).set("lease_until", new Date(now.getTime() + LEASE_MS))
                .set("last_attempt_at", now).setSql("attempt_count = attempt_count + 1").set("update_date", now));
        if (claimed == 0) return;
        metrics.claimed();
        ReminderEntity reminder = reminderDao.selectById(reminderId);
        if (reminder == null) return;
        try { deliveryExecutor.execute(reminder, token); }
        catch (RuntimeException error) {
            log.warn("reminder_delivery_failed errorCode={}", error.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void maintenance() {
        submitMaintenance(this::cleanupIfDue);
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void reportMetrics() {
        metrics.report(workers.getActiveCount());
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    public void recoverExpired() {
        submitMaintenance(() -> {
            try {
                if (taskDao.claimMaintenance(3, 1) == 0) return;
                Date now = new Date();
                List<ReminderEntity> expired = reminderDao.selectList(new QueryWrapper<ReminderEntity>()
                        .select("id").eq("status", "PENDING")
                        .le("remind_at", new Date(now.getTime() - properties.getGraceSeconds() * 1000L))
                        .and(scope -> scope.isNull("lease_until").or().le("lease_until", now))
                        .orderByAsc("remind_at", "id").last("LIMIT " + properties.getBatchSize()));
                for (ReminderEntity item : expired) deliveryExecutor.expire(item.getId());
            } finally {
                // A continuously busy expiry lane must not starve the periodic retention tick.
                // The shared database gate still allows at most one cleanup batch per ten seconds.
                cleanupIfDue();
            }
        });
    }

    private void cleanupIfDue() {
        if (taskDao.claimMaintenance(4, 10) == 0) return;
        taskDao.cleanup(new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(properties.getRetentionDays())), properties.getCleanupBatch());
    }

    private void submitMaintenance(Runnable action) {
        try {
            maintenanceWorkers.execute(() -> {
                try { action.run(); }
                catch (RuntimeException error) { log.warn("reminder_maintenance_failed errorCode={}", error.getClass().getSimpleName()); }
            });
        } catch (RejectedExecutionException full) { /* Next tick retries; never accumulate maintenance jobs. */ }
    }

    @PreDestroy
    public void close() { workers.shutdownNow(); maintenanceWorkers.shutdownNow(); }
}
