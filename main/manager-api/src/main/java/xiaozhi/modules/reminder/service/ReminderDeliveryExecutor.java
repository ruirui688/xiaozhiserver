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

    /**
     * Holds the device row lock across the bounded internal HTTP call (2s connect +
     * 5s read timeout). This deliberately serializes delivery with device rebinding
     * and deletion so a reminder cannot be authorized against a stale binding.
     *
     * <p>The stable request id supports Python's in-process duplicate suppression,
     * but the HTTP response is only a queue acknowledgement, not a durable playback
     * acknowledgement or a cross-process exactly-once guarantee.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void execute(ReminderEntity snapshot, String leaseToken) {
        DeviceEntity device = deviceDao.selectByIdForUpdate(snapshot.getDeviceId());

        Date leaseRenewedAt = new Date();
        int renewed = reminderDao.update(null, owned(snapshot.getId(), leaseToken)
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

        if (device == null) {
            retryOrMiss(reminder, leaseToken, "device_missing", new Date());
            return;
        }
        if (!StringUtils.equals(reminder.getWorkspaceId(), device.getWorkspaceId())) {
            markMissed(reminder.getId(), leaseToken, "device_workspace_changed", new Date());
            return;
        }
        if (StringUtils.isAnyBlank(device.getMacAddress(), device.getAgentId())) {
            retryOrMiss(reminder, leaseToken, "device_unbound", new Date());
            return;
        }
        if (!StringUtils.equals(reminder.getAgentId(), device.getAgentId())) {
            markMissed(reminder.getId(), leaseToken, "device_agent_changed", new Date());
            return;
        }

        // ai_device.agent_id is the binding authority protected by the device lock.
        // Keep this as a normal read to avoid introducing an agent -> device reverse
        // lock order with unrelated agent maintenance paths.
        AgentEntity currentAgent = agentDao.selectById(device.getAgentId());
        if (currentAgent == null
                || !StringUtils.equals(reminder.getWorkspaceId(), currentAgent.getWorkspaceId())) {
            markMissed(reminder.getId(), leaseToken, "agent_workspace_mismatch", new Date());
            return;
        }

        DeliveryResult result;
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
        }

        if (result.delivered()) {
            Date deliveredAt = new Date();
            reminderDao.update(null, owned(reminder.getId(), leaseToken)
                    .set("status", "DELIVERED")
                    .set("delivered_at", deliveredAt)
                    .set("next_attempt_at", null)
                    .set("last_error_code", null)
                    .set("lease_token", null)
                    .set("lease_until", null)
                    .set("update_date", deliveredAt));
        } else {
            retryOrMiss(reminder, leaseToken, result.status(), new Date());
        }
    }

    private void retryOrMiss(
            ReminderEntity reminder,
            String leaseToken,
            String errorCode,
            Date now) {
        if (now.getTime() >= reminder.getRemindAt().getTime() + ReminderDispatchService.DELIVERY_GRACE_MS) {
            markMissed(reminder.getId(), leaseToken, errorCode, now);
            return;
        }
        reminderDao.update(null, owned(reminder.getId(), leaseToken)
                .set("next_attempt_at", new Date(now.getTime() + ReminderDispatchService.RETRY_DELAY_MS))
                .set("last_error_code", errorCode)
                .set("lease_token", null)
                .set("lease_until", null)
                .set("update_date", now));
    }

    private void markMissed(String reminderId, String leaseToken, String errorCode, Date now) {
        reminderDao.update(null, owned(reminderId, leaseToken)
                .set("status", "MISSED")
                .set("next_attempt_at", null)
                .set("last_error_code", errorCode)
                .set("lease_token", null)
                .set("lease_until", null)
                .set("update_date", now));
    }

    private UpdateWrapper<ReminderEntity> owned(String reminderId, String leaseToken) {
        return new UpdateWrapper<ReminderEntity>()
                .eq("id", reminderId)
                .eq("status", PENDING)
                .eq("lease_token", leaseToken);
    }
}
