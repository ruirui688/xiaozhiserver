package xiaozhi.modules.reminder.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.reminder.dao.ReminderDao;
import xiaozhi.modules.reminder.dto.ReminderRuntimeActionDTO;
import xiaozhi.modules.reminder.dto.ReminderSaveDTO;
import xiaozhi.modules.reminder.entity.ReminderEntity;
import xiaozhi.modules.reminder.service.ReminderScopeService;
import xiaozhi.modules.reminder.vo.ReminderVO;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

class ReminderServiceImplTest {
    @Test
    void miniProgramCreateUsesDatabaseDeviceIdAndDerivesAgentFromBinding() {
        Fixture fixture = new Fixture();
        when(fixture.scope.requireWorkspaceDeviceForUpdate("device-db", "workspace-a"))
                .thenReturn(fixture.device());
        fixture.assignInsertedId();

        var result = fixture.service.create(fixture.saveRequest());

        assertEquals("device-db", result.getDeviceId());
        assertEquals("agent-a", result.getAgentId());
        verify(fixture.scope).requireWorkspaceDeviceForUpdate("device-db", "workspace-a");
        ArgumentCaptor<ReminderEntity> inserted = ArgumentCaptor.forClass(ReminderEntity.class);
        verify(fixture.reminderDao).insert(inserted.capture());
        assertEquals("device-db", inserted.getValue().getDeviceId());
        assertEquals("MINI_PROGRAM", inserted.getValue().getSource());
    }

    @Test
    void voiceCreateResolvesMacToDatabaseDeviceIdAndPersistsRequestHash() {
        Fixture fixture = new Fixture();
        when(fixture.scope.requireRuntimeDeviceForUpdate("agent-a", "AA:BB:CC"))
                .thenReturn(fixture.device());
        when(fixture.reminderDao.selectOne(any())).thenReturn(null);
        fixture.assignInsertedId();
        ReminderRuntimeActionDTO request = fixture.runtimeRequest();
        request.setSpokenText(null);

        var result = fixture.service.executeRuntimeAction("agent-a", request);

        assertEquals("reminder-a", result.get("reminderId"));
        verify(fixture.scope).requireRuntimeDeviceForUpdate("agent-a", "AA:BB:CC");
        InOrder lockBeforeLookup = inOrder(fixture.scope, fixture.reminderDao);
        lockBeforeLookup.verify(fixture.scope)
                .requireRuntimeDeviceForUpdate("agent-a", "AA:BB:CC");
        lockBeforeLookup.verify(fixture.reminderDao, times(2)).selectOne(any());
        ArgumentCaptor<ReminderEntity> inserted = ArgumentCaptor.forClass(ReminderEntity.class);
        verify(fixture.reminderDao).insert(inserted.capture());
        assertEquals("device-db", inserted.getValue().getDeviceId());
        assertEquals("agent-a", inserted.getValue().getAgentId());
        assertEquals("voice-request-a", inserted.getValue().getSourceRequestId());
        assertEquals("日程提醒：开会", inserted.getValue().getSpokenText());
        assertNotNull(inserted.getValue().getRequestHash());
    }

    @Test
    void voiceRetryWithTheSameDeviceAndRequestIdReturnsTheOriginalReminder() {
        Fixture fixture = new Fixture();
        when(fixture.scope.requireRuntimeDeviceForUpdate("agent-a", "AA:BB:CC"))
                .thenReturn(fixture.device());
        when(fixture.reminderDao.selectOne(any())).thenReturn(null);
        fixture.assignInsertedId();
        ReminderRuntimeActionDTO request = fixture.runtimeRequest();

        fixture.service.executeRuntimeAction("agent-a", request);

        ArgumentCaptor<ReminderEntity> inserted = ArgumentCaptor.forClass(ReminderEntity.class);
        verify(fixture.reminderDao).insert(inserted.capture());
        when(fixture.reminderDao.selectOne(any())).thenReturn(inserted.getValue());

        var retried = fixture.service.executeRuntimeAction("agent-a", request);

        assertEquals("reminder-a", retried.get("reminderId"));
        verify(fixture.reminderDao, times(1)).insert(any(ReminderEntity.class));
    }

    @Test
    void voiceListLocksDeviceBeforeReadingReminders() {
        Fixture fixture = new Fixture();
        when(fixture.scope.requireRuntimeDeviceForUpdate("agent-a", "AA:BB:CC"))
                .thenReturn(fixture.device());
        when(fixture.reminderDao.selectList(any())).thenReturn(List.of());
        ReminderRuntimeActionDTO request = fixture.runtimeRequest();
        request.setAction("list");

        var result = fixture.service.executeRuntimeAction("agent-a", request);

        assertEquals(List.of(), result.get("reminders"));
        InOrder lockBeforeLookup = inOrder(fixture.scope, fixture.reminderDao);
        lockBeforeLookup.verify(fixture.scope)
                .requireRuntimeDeviceForUpdate("agent-a", "AA:BB:CC");
        lockBeforeLookup.verify(fixture.reminderDao).selectList(any());
    }

    @Test
    void voiceCancelLocksDeviceBeforeLoadingReminder() {
        Fixture fixture = new Fixture();
        when(fixture.scope.requireRuntimeDeviceForUpdate("agent-a", "AA:BB:CC"))
                .thenReturn(fixture.device());
        ReminderEntity pending = fixture.pendingReminder();
        when(fixture.reminderDao.selectOne(any())).thenReturn(pending);
        when(fixture.reminderDao.update(any(), any())).thenReturn(1);
        ReminderRuntimeActionDTO request = fixture.runtimeRequest();
        request.setAction("cancel");
        request.setReminderId("reminder-a");

        var result = fixture.service.executeRuntimeAction("agent-a", request);

        assertEquals(ReminderServiceImpl.CANCELLED, result.get("status"));
        InOrder lockBeforeLookup = inOrder(fixture.scope, fixture.reminderDao);
        lockBeforeLookup.verify(fixture.scope)
                .requireRuntimeDeviceForUpdate("agent-a", "AA:BB:CC");
        lockBeforeLookup.verify(fixture.reminderDao).selectOne(any());
    }

    @Test
    void voiceCreateReturnsSamePendingReminderForDifferentRequestIdAndContentMatch() {
        Fixture fixture = new Fixture();
        when(fixture.scope.requireRuntimeDeviceForUpdate("agent-a", "AA:BB:CC"))
                .thenReturn(fixture.device());
        when(fixture.reminderDao.selectOne(any())).thenReturn(null);
        fixture.assignInsertedId();
        ReminderRuntimeActionDTO firstRequest = fixture.runtimeRequest();

        fixture.service.executeRuntimeAction("agent-a", firstRequest);

        ArgumentCaptor<ReminderEntity> inserted = ArgumentCaptor.forClass(ReminderEntity.class);
        verify(fixture.reminderDao).insert(inserted.capture());
        ReminderEntity existing = inserted.getValue();
        ReminderRuntimeActionDTO repeatedRequest = fixture.runtimeRequest();
        repeatedRequest.setRequestId("voice-request-b");
        when(fixture.reminderDao.selectOne(any())).thenReturn(null, existing);

        var result = fixture.service.executeRuntimeAction("agent-a", repeatedRequest);

        assertEquals("reminder-a", result.get("reminderId"));
        verify(fixture.reminderDao, times(1)).insert(any(ReminderEntity.class));
        verify(fixture.scope, times(2))
                .requireRuntimeDeviceForUpdate("agent-a", "AA:BB:CC");
    }

    @Test
    void voiceCreateDoesNotMatchCancelledReminderWithSameHash() {
        Fixture fixture = new Fixture();
        when(fixture.scope.requireRuntimeDeviceForUpdate("agent-a", "AA:BB:CC"))
                .thenReturn(fixture.device());
        when(fixture.reminderDao.selectOne(any())).thenReturn(null);
        AtomicInteger insertedCount = new AtomicInteger();
        doAnswer(invocation -> {
            ReminderEntity entity = invocation.getArgument(0);
            entity.setId(insertedCount.getAndIncrement() == 0 ? "reminder-a" : "reminder-b");
            return 1;
        }).when(fixture.reminderDao).insert(any(ReminderEntity.class));
        fixture.service.executeRuntimeAction("agent-a", fixture.runtimeRequest());

        ArgumentCaptor<ReminderEntity> inserted = ArgumentCaptor.forClass(ReminderEntity.class);
        verify(fixture.reminderDao).insert(inserted.capture());
        ReminderEntity cancelled = inserted.getValue();
        cancelled.setStatus(ReminderServiceImpl.CANCELLED);
        ReminderRuntimeActionDTO recreatedRequest = fixture.runtimeRequest();
        recreatedRequest.setRequestId("voice-request-b");
        when(fixture.reminderDao.selectOne(any())).thenReturn(
                (ReminderEntity) null, (ReminderEntity) null);

        var result = fixture.service.executeRuntimeAction("agent-a", recreatedRequest);

        assertEquals("reminder-b", result.get("reminderId"));
        verify(fixture.reminderDao, times(2)).insert(any(ReminderEntity.class));
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> queries = ArgumentCaptor.forClass(Wrapper.class);
        verify(fixture.reminderDao, times(4)).selectOne(queries.capture());
        QueryWrapper<?> pendingHashQuery = (QueryWrapper<?>) queries.getAllValues().get(3);
        String pendingHashSql = pendingHashQuery.getCustomSqlSegment();
        assertTrue(pendingHashSql.contains("status"));
        assertTrue(pendingHashQuery.getParamNameValuePairs().containsValue(ReminderServiceImpl.PENDING));
        assertTrue(pendingHashQuery.getParamNameValuePairs().containsValue(cancelled.getRequestHash()));
    }

    @Test
    void createRejectsTimeWithoutOffset() {
        Fixture fixture = new Fixture();
        when(fixture.scope.requireWorkspaceDeviceForUpdate("device-db", "workspace-a"))
                .thenReturn(fixture.device());
        ReminderSaveDTO request = fixture.saveRequest();
        request.setRemindAt("2026-10-14T14:00:00");

        assertThrows(RenException.class, () -> fixture.service.create(request));
    }

    @Test
    void updateExplicitlyClearsExpiredDeliveryLeaseAndPreviousFailureState() {
        Fixture fixture = new Fixture();
        ReminderEntity pending = new ReminderEntity();
        pending.setId("reminder-a");
        pending.setWorkspaceId("workspace-a");
        pending.setDeviceId("device-db");
        pending.setAgentId("agent-a");
        pending.setTitle("旧提醒");
        pending.setSpokenText("日程提醒：旧提醒");
        pending.setRemindAt(Date.from(Instant.now().plusSeconds(60)));
        pending.setTimezone("Asia/Shanghai");
        pending.setStatus(ReminderServiceImpl.PENDING);
        pending.setSource("MINI_PROGRAM");
        pending.setAttemptCount(1);
        pending.setLastAttemptAt(new Date());
        pending.setLastErrorCode("device_offline");
        pending.setLeaseToken("expired-worker-token");
        pending.setLeaseUntil(Date.from(Instant.now().minusSeconds(1)));
        when(fixture.reminderDao.selectOne(any())).thenReturn(pending);
        when(fixture.scope.requireWorkspaceDeviceForUpdate("device-db", "workspace-a"))
                .thenReturn(fixture.device());
        when(fixture.reminderDao.update(any(ReminderEntity.class), any())).thenReturn(1);

        fixture.service.update("reminder-a", fixture.saveRequest());

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> updateCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(fixture.reminderDao).update(any(ReminderEntity.class), updateCaptor.capture());
        UpdateWrapper<?> update = (UpdateWrapper<?>) updateCaptor.getValue();
        String sqlSet = update.getSqlSet();
        assertTrue(sqlSet.contains("last_attempt_at"));
        assertTrue(sqlSet.contains("delivered_at"));
        assertTrue(sqlSet.contains("last_error_code"));
        assertTrue(sqlSet.contains("lease_token"));
        assertTrue(sqlSet.contains("lease_until"));
        assertEquals(5, update.getParamNameValuePairs().values().stream()
                .filter(Objects::isNull)
                .count());
    }

    @Test
    void updateRejectsMovingVoiceReminderSoTheRequestIdAnchorCannotSplit() {
        Fixture fixture = new Fixture();
        ReminderEntity pending = fixture.pendingReminder();
        pending.setSource("VOICE");
        pending.setSourceRequestId("voice-request-a");
        pending.setRequestHash("existing-request-hash");
        when(fixture.reminderDao.selectOne(any())).thenReturn(pending);
        ReminderSaveDTO request = fixture.saveRequest();
        request.setDeviceId("device-other");

        RenException error = assertThrows(
                RenException.class,
                () -> fixture.service.update("reminder-a", request));

        assertEquals("语音创建的提醒不能更换设备，请取消后重新创建", error.getMessage());
        verify(fixture.scope, never()).requireWorkspaceDeviceForUpdate(any(), any());
        verify(fixture.reminderDao, never()).update(any(ReminderEntity.class), any());
    }

    @Test
    void pendingListIsWorkspaceScopedAndReturnsTheNextFiveHundredAscending() {
        Fixture fixture = new Fixture();
        when(fixture.reminderDao.selectList(any())).thenReturn(List.of());

        fixture.service.list("pending");

        verify(fixture.permission).requireRead("workspace-a");
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(fixture.reminderDao).selectList(queryCaptor.capture());
        QueryWrapper<?> query = (QueryWrapper<?>) queryCaptor.getValue();
        String sql = query.getCustomSqlSegment().toUpperCase(Locale.ROOT);
        assertTrue(sql.contains("WORKSPACE_ID"));
        assertTrue(sql.contains("STATUS"));
        assertTrue(sql.contains("ORDER BY REMIND_AT ASC"));
        assertTrue(sql.contains("LIMIT 500"));
        assertTrue(query.getParamNameValuePairs().containsValue("workspace-a"));
        assertTrue(query.getParamNameValuePairs().containsValue(ReminderServiceImpl.PENDING));
    }

    @Test
    void historyListExcludesPendingAndReturnsTheMostRecentFiveHundredDescending() {
        Fixture fixture = new Fixture();
        when(fixture.reminderDao.selectList(any())).thenReturn(List.of());

        fixture.service.list("HISTORY");

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(fixture.reminderDao).selectList(queryCaptor.capture());
        QueryWrapper<?> query = (QueryWrapper<?>) queryCaptor.getValue();
        String sql = query.getCustomSqlSegment().toUpperCase(Locale.ROOT);
        assertTrue(sql.contains("STATUS IN"));
        assertTrue(sql.contains("ORDER BY REMIND_AT DESC"));
        assertTrue(sql.contains("LIMIT 500"));
        assertTrue(query.getParamNameValuePairs().containsValue("workspace-a"));
        assertTrue(query.getParamNameValuePairs().containsValue(ReminderServiceImpl.DELIVERED));
        assertTrue(query.getParamNameValuePairs().containsValue(ReminderServiceImpl.CANCELLED));
        assertTrue(query.getParamNameValuePairs().containsValue(ReminderServiceImpl.MISSED));
        assertFalse(query.getParamNameValuePairs().containsValue(ReminderServiceImpl.PENDING));
    }

    @Test
    void listRejectsUnknownFiltersBeforeQueryingTheDatabase() {
        Fixture fixture = new Fixture();

        assertThrows(RenException.class, () -> fixture.service.list("ALL"));

        verify(fixture.reminderDao, never()).selectList(any());
    }

    @Test
    void deliveredAtUsesTheReminderIanaTimezoneAndStringContract() throws Exception {
        Fixture fixture = new Fixture();
        ReminderEntity delivered = new ReminderEntity();
        delivered.setId("reminder-delivered");
        delivered.setDeviceId("device-db");
        delivered.setAgentId("agent-a");
        delivered.setTitle("冬令时会议");
        delivered.setRemindAt(Date.from(Instant.parse("2026-11-01T06:30:15Z")));
        delivered.setDeliveredAt(Date.from(Instant.parse("2026-11-01T06:31:15Z")));
        delivered.setTimezone("America/New_York");
        delivered.setStatus(ReminderServiceImpl.DELIVERED);
        delivered.setSource("MINI_PROGRAM");
        when(fixture.reminderDao.selectList(any())).thenReturn(List.of(delivered));
        when(fixture.deviceDao.selectList(any())).thenReturn(List.of(fixture.device()));

        ReminderVO result = fixture.service.list("HISTORY").get(0);

        assertEquals("2026-11-01T01:31:15-05:00", result.getDeliveredAt());
        assertEquals(String.class, ReminderVO.class.getDeclaredField("deliveredAt").getType());
    }

    private static final class Fixture {
        final ReminderDao reminderDao = mock(ReminderDao.class);
        final DeviceDao deviceDao = mock(DeviceDao.class);
        final AgentDao agentDao = mock(AgentDao.class);
        final ReminderScopeService scope = mock(ReminderScopeService.class);
        final WorkspacePermissionService permission = mock(WorkspacePermissionService.class);
        final ReminderServiceImpl service = new ReminderServiceImpl(
                reminderDao, deviceDao, agentDao, scope, permission);

        Fixture() {
            when(permission.currentWorkspaceId()).thenReturn("workspace-a");
            when(reminderDao.selectCount(any())).thenReturn(0L);
        }

        DeviceEntity device() {
            DeviceEntity device = new DeviceEntity();
            device.setId("device-db");
            device.setWorkspaceId("workspace-a");
            device.setAgentId("agent-a");
            device.setMacAddress("AA:BB:CC");
            device.setAlias("书房音箱");
            return device;
        }

        ReminderSaveDTO saveRequest() {
            ReminderSaveDTO dto = new ReminderSaveDTO();
            dto.setDeviceId("device-db");
            dto.setTitle("开会");
            dto.setRemindAt(OffsetDateTime.now().plusDays(2).withNano(0).toString());
            dto.setTimezone("Asia/Shanghai");
            return dto;
        }

        ReminderRuntimeActionDTO runtimeRequest() {
            ReminderRuntimeActionDTO dto = new ReminderRuntimeActionDTO();
            dto.setAction("create");
            dto.setDeviceId("AA:BB:CC");
            dto.setRequestId("voice-request-a");
            dto.setTitle("开会");
            dto.setSpokenText("现在该开会了");
            dto.setRemindAt(OffsetDateTime.now().plusDays(2).withNano(0).toString());
            dto.setTimezone("Asia/Shanghai");
            return dto;
        }

        ReminderEntity pendingReminder() {
            ReminderEntity reminder = new ReminderEntity();
            reminder.setId("reminder-a");
            reminder.setWorkspaceId("workspace-a");
            reminder.setDeviceId("device-db");
            reminder.setAgentId("agent-a");
            reminder.setTitle("开会");
            reminder.setRemindAt(Date.from(Instant.now().plusSeconds(60)));
            reminder.setTimezone("Asia/Shanghai");
            reminder.setStatus(ReminderServiceImpl.PENDING);
            return reminder;
        }

        void assignInsertedId() {
            doAnswer(invocation -> {
                ReminderEntity entity = invocation.getArgument(0);
                entity.setId("reminder-a");
                return 1;
            }).when(reminderDao).insert(any(ReminderEntity.class));
        }
    }
}
