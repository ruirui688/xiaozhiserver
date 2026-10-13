package xiaozhi.modules.reminder.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.reminder.dao.ReminderDao;
import xiaozhi.modules.reminder.entity.ReminderEntity;

class ReminderDispatchServiceTest {
    private final ReminderDao reminderDao = mock(ReminderDao.class);
    private final ReminderDeliveryExecutor deliveryExecutor = mock(ReminderDeliveryExecutor.class);
    private final xiaozhi.modules.reminder.dao.ReminderTaskDao taskDao = mock(xiaozhi.modules.reminder.dao.ReminderTaskDao.class);
    private final ReminderDispatchService service = new ReminderDispatchService(
            reminderDao, deliveryExecutor, taskDao, new xiaozhi.modules.reminder.config.ReminderTaskProperties(), new ReminderTaskMetrics());

    @org.junit.jupiter.api.BeforeEach
    void permit() { when(taskDao.acquirePermit(any(Integer.class))).thenReturn(1); }

    @AfterEach
    void closeWorkerPool() {
        service.close();
    }

    @Test
    void busyExpiryLaneStillCleansHistoryWhenPeriodicTicksAreRejected() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(taskDao.claimMaintenance(3, 1)).thenReturn(1);
        when(taskDao.claimMaintenance(4, 10)).thenReturn(1);
        when(reminderDao.selectList(any())).thenReturn(java.util.List.of(reminder()));
        org.mockito.Mockito.doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(2, java.util.concurrent.TimeUnit.SECONDS));
            return null;
        }).when(deliveryExecutor).expire("reminder-a");
        try {
            service.recoverExpired();
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            for (int tick = 0; tick < 10; tick++) {
                service.recoverExpired();
                service.maintenance();
            }
            verify(taskDao, never()).cleanup(any(), any(Integer.class));
        } finally {
            release.countDown();
        }
        verify(taskDao, org.mockito.Mockito.timeout(2000)).cleanup(any(), eq(200));
        verify(taskDao, times(1)).claimMaintenance(4, 10);
        verify(deliveryExecutor, times(1)).expire("reminder-a");
    }

    @Test
    void expiryFailureStillChecksSharedCleanupBudget() {
        when(taskDao.claimMaintenance(3, 1)).thenReturn(1);
        when(taskDao.claimMaintenance(4, 10)).thenReturn(1);
        when(reminderDao.selectList(any())).thenReturn(java.util.List.of(reminder()));
        doThrow(new IllegalStateException("failed expiry")).when(deliveryExecutor).expire("reminder-a");

        service.recoverExpired();

        verify(taskDao, org.mockito.Mockito.timeout(2000)).cleanup(any(), eq(200));
    }

    @Test
    void claimedReminderIsDelegatedWithTheOwnedLeaseToken() {
        ReminderEntity reminder = reminder();
        when(reminderDao.update(isNull(), any())).thenReturn(1);
        when(reminderDao.selectById("reminder-a")).thenReturn(reminder);

        service.dispatchOne("reminder-a");

        ArgumentCaptor<String> leaseToken = ArgumentCaptor.forClass(String.class);
        verify(deliveryExecutor).execute(eq(reminder), leaseToken.capture());
        assertFalse(leaseToken.getValue().isBlank());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<UpdateWrapper> claim = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(reminderDao).update(isNull(), claim.capture());
        String claimSql = claim.getValue().getCustomSqlSegment();
        assertTrue(claimSql.contains("status"));
        assertTrue(claim.getValue().getParamNameValuePairs().containsValue(leaseToken.getValue()));
        assertTrue(claim.getValue().getParamNameValuePairs().containsValue("PENDING"));
    }

    @Test
    void deliveryRequestIdIsStableForRetryAndChangesWithBusinessVersion() {
        ReminderEntity reminder = reminder();
        DeviceEntity device = new DeviceEntity();
        device.setMacAddress("AA:BB:CC");

        String original = ReminderDeliveryRequestId.from(reminder, device);

        assertEquals(original, ReminderDeliveryRequestId.from(reminder, device));
        reminder.setSpokenText("日程提醒：改为复盘");
        assertNotEquals(original, ReminderDeliveryRequestId.from(reminder, device));
        reminder.setSpokenText("现在该开会了");
        reminder.setDeviceId("device-edited");
        assertNotEquals(original, ReminderDeliveryRequestId.from(reminder, device));
        reminder.setDeviceId("device-db-id");
        Date originalTime = reminder.getRemindAt();
        reminder.setRemindAt(new Date(originalTime.getTime() + 60_000));
        assertNotEquals(original, ReminderDeliveryRequestId.from(reminder, device));
        reminder.setRemindAt(originalTime);
        reminder.setAgentId("agent-edited");
        assertNotEquals(original, ReminderDeliveryRequestId.from(reminder, device));
        reminder.setAgentId("agent-current");
        device.setMacAddress("DD:EE:FF");
        assertNotEquals(original, ReminderDeliveryRequestId.from(reminder, device));
    }

    @Test
    void failedClaimDoesNotExecuteDelivery() {
        when(reminderDao.update(isNull(), any())).thenReturn(0);

        service.dispatchOne("reminder-a");

        verify(deliveryExecutor, never()).execute(any(), any());
        verify(reminderDao, never()).selectById(any());
    }

    @Test
    void deletedReminderAfterClaimDoesNotExecuteDelivery() {
        when(reminderDao.update(isNull(), any())).thenReturn(1);
        when(reminderDao.selectById("reminder-a")).thenReturn(null);

        service.dispatchOne("reminder-a");

        verify(deliveryExecutor, never()).execute(any(), any());
    }

    @Test
    void executorFailureKeepsClaimForExpiryInsteadOfClearingItEarly() {
        ReminderEntity reminder = reminder();
        when(reminderDao.update(isNull(), any())).thenReturn(1);
        when(reminderDao.selectById("reminder-a")).thenReturn(reminder);
        doThrow(new IllegalStateException("database unavailable"))
                .when(deliveryExecutor).execute(eq(reminder), any());

        service.dispatchOne("reminder-a");

        verify(reminderDao, times(1)).update(isNull(), any());
    }

    private ReminderEntity reminder() {
        ReminderEntity reminder = new ReminderEntity();
        reminder.setId("reminder-a");
        reminder.setWorkspaceId("workspace-a");
        reminder.setDeviceId("device-db-id");
        reminder.setAgentId("agent-current");
        reminder.setSpokenText("现在该开会了");
        reminder.setRemindAt(new Date(System.currentTimeMillis() - 1_000));
        reminder.setStatus("PENDING");
        return reminder;
    }
}
