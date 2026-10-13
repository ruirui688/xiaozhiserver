package xiaozhi.modules.reminder.service;

import java.util.Date;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.reminder.client.ReminderDeviceNotificationClient;
import xiaozhi.modules.reminder.client.ReminderDeviceNotificationClient.DeliveryResult;
import xiaozhi.modules.reminder.dao.ReminderDao;
import xiaozhi.modules.reminder.entity.ReminderEntity;
import xiaozhi.modules.reminder.dao.ReminderTaskDao;
import xiaozhi.modules.reminder.config.ReminderTaskProperties;

/** Executes one lease-owned reminder delivery under the current device binding. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReminderDeliveryExecutor {
    private static final String PENDING = "PENDING";

    private final ReminderDao reminderDao;
    private final DeviceDao deviceDao;
    private final AgentDao agentDao;
    private final ReminderDeviceNotificationClient notificationClient;
    private final ReminderTaskDao taskDao;
    private final ReminderTaskProperties properties;
    private final ReminderTaskMetrics metrics;

    /**
     * Holds the device row lock across the bounded internal HTTP call (2s connect +
     * 5s read timeout). This deliberately serializes delivery with device rebinding
     * and deletion so a reminder cannot be authorized against a stale binding.
     *
     * <p>The stable request id supports Python's in-process duplicate suppression,
     * but the HTTP response is only a queue acknowledgement, not a durable playback
     * acknowledgement or a cross-process exactly-once guarantee.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class, timeout = 12)
    public void execute(ReminderEntity snapshot, String leaseToken) {
        DeviceEntity device = deviceDao.selectByIdForUpdate(snapshot.getDeviceId());

        Date leaseRenewedAt = new Date();
        int renewed = reminderDao.update(null, owned(snapshot.getId(), leaseToken).gt("lease_until", leaseRenewedAt)
                .set("lease_until", new Date(leaseRenewedAt.getTime() + ReminderDispatchService.LEASE_MS))
                .set("update_date", leaseRenewedAt));
        if (renewed == 0) {
            return;
        }

        ReminderEntity reminder = reminderDao.selectOne(new QueryWrapper<ReminderEntity>()
                .eq("id", snapshot.getId())
                .eq("status", PENDING)
                .eq("lease_token", leaseToken)
                .last("LIMIT 1 FOR UPDATE"));
        if (reminder == null
                || !StringUtils.equals(reminder.getDeviceId(), snapshot.getDeviceId())) {
            return;
        }

        if (new Date().getTime() >= reminder.getRemindAt().getTime() + properties.getGraceSeconds() * 1000L
                || attempts(reminder) > properties.getMaxAttempts()) {
            finish(reminder, leaseToken, "MISSED", "delivery_window_expired", new Date(), true);
            return;
        }

        if (device == null) {
            retryOrMiss(reminder, leaseToken, "device_missing", new Date());
            return;
        }
        if (!StringUtils.equals(reminder.getWorkspaceId(), device.getWorkspaceId())) {
            finish(reminder, leaseToken, "MISSED", "device_workspace_changed", new Date(), false);
            return;
        }
        if (StringUtils.isAnyBlank(device.getMacAddress(), device.getAgentId())) {
            retryOrMiss(reminder, leaseToken, "device_unbound", new Date());
            return;
        }
        if (!StringUtils.equals(reminder.getAgentId(), device.getAgentId())) {
            finish(reminder, leaseToken, "MISSED", "device_agent_changed", new Date(), false);
            return;
        }

        // ai_device.agent_id is the binding authority protected by the device lock.
        // Keep this as a normal read to avoid introducing an agent -> device reverse
        // lock order with unrelated agent maintenance paths.
        AgentEntity currentAgent = agentDao.selectById(device.getAgentId());
        if (currentAgent == null
                || !StringUtils.equals(reminder.getWorkspaceId(), currentAgent.getWorkspaceId())) {
            finish(reminder, leaseToken, "MISSED", "agent_workspace_mismatch", new Date(), false);
            return;
        }

        DeliveryResult result;
        long deliveryStarted = System.nanoTime();
        try {
            String deliveryRequestId = ReminderDeliveryRequestId.from(reminder, device);
            result = notificationClient.deliver(
                    deliveryRequestId,
                    device.getMacAddress(),
                    device.getAgentId(),
                    reminder.getSpokenText());
        } catch (RuntimeException error) {
            retryOrMiss(reminder, leaseToken, "dispatch_error", new Date());
            log.warn(
                    "日程提醒投递失败: reminderId={}, deviceId={}, errorCode={}",
                    reminder.getId(),
                    reminder.getDeviceId(),
                    error.getClass().getSimpleName());
            return;
        } finally {
            metrics.deliveryDuration(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - deliveryStarted));
        }

        if (result.delivered()) {
            finish(reminder, leaseToken, "DELIVERED", null, new Date(), true);
        } else if ("delivery_uncertain".equals(result.status()) || "request_conflict".equals(result.status())) {
            finish(reminder, leaseToken, "MISSED", result.status(), new Date(), true);
        } else {
            retryOrMiss(reminder, leaseToken, result.status(), new Date());
        }
    }

    /** Expiry has a separate bounded lane and never consumes network delivery permits or device locks. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class, timeout = 5)
    public void expire(String reminderId) {
        ReminderEntity reminder = reminderDao.selectOne(new QueryWrapper<ReminderEntity>()
                .eq("id", reminderId).eq("status", PENDING)
                .le("remind_at", new Date(System.currentTimeMillis() - properties.getGraceSeconds() * 1000L))
                .and(scope -> scope.isNull("lease_until").or().le("lease_until", new Date()))
                .last("LIMIT 1 FOR UPDATE"));
        if (reminder == null) return;
        finish(reminder, reminder.getLeaseToken(), "MISSED", "delivery_window_expired", new Date(), true);
        metrics.expired();
    }

    private void retryOrMiss(
            ReminderEntity reminder,
            String leaseToken,
            String errorCode,
            Date now) {
        if (now.getTime() >= reminder.getRemindAt().getTime() + properties.getGraceSeconds() * 1000L
                || attempts(reminder) >= properties.getMaxAttempts()) {
            finish(reminder, leaseToken, "MISSED", errorCode, now, true);
            return;
        }
        metrics.retried();
        long delay = retryDelayMillis(attempts(reminder));
        long expires = reminder.getRemindAt().getTime() + properties.getGraceSeconds() * 1000L;
        reminderDao.update(null, owned(reminder.getId(), leaseToken)
                .set("next_attempt_at", new Date(Math.min(expires, now.getTime() + delay)))
                .set("last_error_code", errorCode)
                .set("lease_token", null)
                .set("lease_until", null)
                .set("update_date", now));
    }

    private void finish(ReminderEntity reminder, String leaseToken, String status, String errorCode, Date now, boolean repeat) {
        java.time.Instant next = repeat ? ReminderRecurrence.next(reminder, now.toInstant()) : null;
        taskDao.record(reminder, status, errorCode, now);
        if ("DELIVERED".equals(status)) metrics.accepted(); else metrics.missed();
        UpdateWrapper<ReminderEntity> update = owned(reminder.getId(), leaseToken)
                .set("status", next == null ? status : PENDING)
                .set("next_attempt_at", next == null ? null : Date.from(next))
                .set("last_error_code", errorCode)
                .set("lease_token", null)
                .set("lease_until", null)
                .set("update_date", now);
        if ("DELIVERED".equals(status)) update.set("delivered_at", now);
        if (next != null) update.set("remind_at", Date.from(next)).set("attempt_count", 0).set("last_attempt_at", null);
        if (reminderDao.update(null, update) != 1) throw new IllegalStateException("Reminder lease lost during completion");
    }

    static long retryDelayMillis(int attempts) {
        long base = Math.min(240_000L, 15_000L * (1L << Math.min(4, Math.max(0, attempts - 1))));
        return base + java.util.concurrent.ThreadLocalRandom.current().nextLong(base / 4 + 1);
    }

    private static int attempts(ReminderEntity reminder) {
        return reminder.getAttemptCount() == null ? 0 : reminder.getAttemptCount();
    }

    private UpdateWrapper<ReminderEntity> owned(String reminderId, String leaseToken) {
        return new UpdateWrapper<ReminderEntity>()
                .eq("id", reminderId)
                .eq("status", PENDING)
                .eq(leaseToken != null, "lease_token", leaseToken)
                .isNull(leaseToken == null, "lease_token");
    }
}
