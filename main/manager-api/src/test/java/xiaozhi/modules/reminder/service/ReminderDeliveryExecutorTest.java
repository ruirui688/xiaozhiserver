package xiaozhi.modules.reminder.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.reminder.client.ReminderDeviceNotificationClient;
import xiaozhi.modules.reminder.client.ReminderDeviceNotificationClient.DeliveryResult;
import xiaozhi.modules.reminder.dao.ReminderDao;
import xiaozhi.modules.reminder.entity.ReminderEntity;

class ReminderDeliveryExecutorTest {
    private final ReminderDao reminderDao = mock(ReminderDao.class);
    private final DeviceDao deviceDao = mock(DeviceDao.class);
    private final AgentDao agentDao = mock(AgentDao.class);
    private final ReminderDeviceNotificationClient client = mock(ReminderDeviceNotificationClient.class);
    private final ReminderDeliveryExecutor executor = new ReminderDeliveryExecutor(
            reminderDao, deviceDao, agentDao, client);

    @Test
    void locksDeviceAndOwnedReminderBeforeDeliveringAndMarkingDelivered() {
        ReminderEntity reminder = reminder();
        DeviceEntity device = device("agent-current");
        AgentEntity agent = agent("agent-current");
        when(deviceDao.selectByIdForUpdate("device-db-id")).thenReturn(device);
        when(reminderDao.update(isNull(), any())).thenReturn(1);
        when(reminderDao.selectOne(any())).thenReturn(reminder);
        when(agentDao.selectById("agent-current")).thenReturn(agent);
        when(client.deliver(any(), any(), any(), any()))
                .thenReturn(new DeliveryResult(true, "queued"));

        executor.execute(reminder, "lease-a");

        InOrder order = inOrder(deviceDao, reminderDao, agentDao, client);
        order.verify(deviceDao).selectByIdForUpdate("device-db-id");
        order.verify(reminderDao).update(isNull(), any());
        order.verify(reminderDao).selectOne(any());
        order.verify(agentDao).selectById("agent-current");
        order.verify(client).deliver(any(), eq("AA:BB:CC"), eq("agent-current"), eq("现在该开会了"));
        order.verify(reminderDao).update(isNull(), any());

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<UpdateWrapper> updates = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(reminderDao, times(2)).update(isNull(), updates.capture());
        assertTrue(updates.getAllValues().get(0).getSqlSet().contains("lease_until"));
        assertTrue(updates.getAllValues().get(0).getCustomSqlSegment().contains("lease_token"));
        assertTrue(updates.getAllValues().get(0).getParamNameValuePairs().containsValue("lease-a"));
        assertTrue(updates.getAllValues().get(1).getParamNameValuePairs().containsValue("DELIVERED"));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> reminderLock = ArgumentCaptor.forClass(Wrapper.class);
        verify(reminderDao).selectOne(reminderLock.capture());
        QueryWrapper<?> query = (QueryWrapper<?>) reminderLock.getValue();
        assertTrue(query.getCustomSqlSegment().toUpperCase(Locale.ROOT).contains("FOR UPDATE"));
        assertTrue(query.getParamNameValuePairs().containsValue("reminder-a"));
        assertTrue(query.getParamNameValuePairs().containsValue("PENDING"));
        assertTrue(query.getParamNameValuePairs().containsValue("lease-a"));
    }

    @Test
    void lostLeaseAfterWaitingForDeviceLockNeverSendsNotification() {
        ReminderEntity reminder = reminder();
        when(deviceDao.selectByIdForUpdate("device-db-id")).thenReturn(device("agent-current"));
        when(reminderDao.update(isNull(), any())).thenReturn(0);

        executor.execute(reminder, "stale-lease");

        InOrder order = inOrder(deviceDao, reminderDao);
        order.verify(deviceDao).selectByIdForUpdate("device-db-id");
        order.verify(reminderDao).update(isNull(), any());
        verify(reminderDao, never()).selectOne(any());
        verify(agentDao, never()).selectById(any());
        verify(client, never()).deliver(any(), any(), any(), any());
    }

    @Test
    void changedAgentBindingIsMissedWithoutCallingTheOldAgent() {
        ReminderEntity reminder = reminder();
        reminder.setAgentId("agent-original");
        when(deviceDao.selectByIdForUpdate("device-db-id")).thenReturn(device("agent-new"));
        when(reminderDao.update(isNull(), any())).thenReturn(1);
        when(reminderDao.selectOne(any())).thenReturn(reminder);

        executor.execute(reminder, "lease-a");

        verify(agentDao, never()).selectById(any());
        verify(client, never()).deliver(any(), any(), any(), any());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<UpdateWrapper> updates = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(reminderDao, times(2)).update(isNull(), updates.capture());
        assertTrue(updates.getAllValues().get(1).getParamNameValuePairs().containsValue("MISSED"));
        assertTrue(updates.getAllValues().get(1).getParamNameValuePairs()
                .containsValue("device_agent_changed"));
    }

    @Test
    void executeUsesAnIndependentRollbackOnExceptionTransaction() throws Exception {
        Transactional transaction = ReminderDeliveryExecutor.class
                .getMethod("execute", ReminderEntity.class, String.class)
                .getAnnotation(Transactional.class);

        assertNotNull(transaction);
        assertTrue(ReminderDeliveryExecutor.class.isAnnotationPresent(Service.class));
        assertEquals(Propagation.REQUIRES_NEW, transaction.propagation());
        assertArrayEquals(new Class<?>[] { Exception.class }, transaction.rollbackFor());
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

    private DeviceEntity device(String agentId) {
        DeviceEntity device = new DeviceEntity();
        device.setId("device-db-id");
        device.setWorkspaceId("workspace-a");
        device.setMacAddress("AA:BB:CC");
        device.setAgentId(agentId);
        return device;
    }

    private AgentEntity agent(String agentId) {
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setWorkspaceId("workspace-a");
        return agent;
    }
}
