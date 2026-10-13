package xiaozhi.modules.reminder.service;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.modules.reminder.dao.ReminderDao;
import xiaozhi.modules.reminder.entity.ReminderEntity;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReminderDispatchService {
    static final long RETRY_DELAY_MS = 15_000L;
    static final long DELIVERY_GRACE_MS = 10 * 60_000L;
    static final long LEASE_MS = 30_000L;
    private static final int SCAN_BATCH_SIZE = 20;

    private final ReminderDao reminderDao;
    private final ReminderDeliveryExecutor deliveryExecutor;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            2,
            4,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            action -> {
                Thread thread = new Thread(action, "device-reminder-dispatch");
                thread.setDaemon(true);
                return thread;
            });

    @Scheduled(
            fixedDelayString = "${xiaozhi.reminder.scan-ms:5000}",
            initialDelayString = "${xiaozhi.reminder.initial-delay-ms:5000}")
    public void dispatchDueReminders() {
        if (workers.isShutdown() || workers.getQueue().remainingCapacity() == 0) {
            return;
        }
        Date now = new Date();
        markExpired(now);
        List<ReminderEntity> due = reminderDao.selectList(new QueryWrapper<ReminderEntity>()
                .select("id")
                .eq("status", "PENDING")
                .le("remind_at", now)
                .le("next_attempt_at", now)
                .and(scope -> scope.isNull("lease_until").or().le("lease_until", now))
                .orderByAsc("remind_at")
                .last("LIMIT " + SCAN_BATCH_SIZE));
        for (ReminderEntity reminder : due) {
            try {
                workers.execute(() -> dispatchOne(reminder.getId()));
            } catch (RejectedExecutionException full) {
                break;
            }
        }
    }

    void dispatchOne(String reminderId) {
        Date now = new Date();
        String leaseToken = UUID.randomUUID().toString();
        int claimed = reminderDao.update(null, new UpdateWrapper<ReminderEntity>()
                .eq("id", reminderId)
                .eq("status", "PENDING")
                .le("remind_at", now)
                .le("next_attempt_at", now)
                .and(scope -> scope.isNull("lease_until").or().le("lease_until", now))
                .set("lease_token", leaseToken)
                .set("lease_until", new Date(now.getTime() + LEASE_MS))
                .set("last_attempt_at", now)
                .setSql("attempt_count = attempt_count + 1")
                .set("update_date", now));
        if (claimed == 0) {
            return;
        }

        ReminderEntity reminder = reminderDao.selectById(reminderId);
        if (reminder == null) {
            return;
        }
        try {
            deliveryExecutor.execute(reminder, leaseToken);
        } catch (RuntimeException error) {
            // The REQUIRES_NEW transaction rolled back. Keep the claimed lease so
            // another scan retries only after its bounded expiry.
            log.warn(
                    "日程提醒投递失败: reminderId={}, deviceId={}, errorCode={}",
                    reminderId,
                    reminder.getDeviceId(),
                    error.getClass().getSimpleName());
        }
    }

    private void markExpired(Date now) {
        Date cutoff = new Date(now.getTime() - DELIVERY_GRACE_MS);
        reminderDao.update(null, new UpdateWrapper<ReminderEntity>()
                .eq("status", "PENDING")
                .le("remind_at", cutoff)
                .and(scope -> scope.isNull("lease_until").or().le("lease_until", now))
                .set("status", "MISSED")
                .set("next_attempt_at", null)
                .set("last_error_code", "delivery_window_expired")
                .set("lease_token", null)
                .set("lease_until", null)
                .set("update_date", now));
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
    }
}
