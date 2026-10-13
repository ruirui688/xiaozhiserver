package xiaozhi.modules.reminder.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.SharedString;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;

class ReminderScopeServiceTest {
    private final DeviceDao deviceDao = mock(DeviceDao.class);
    private final AgentDao agentDao = mock(AgentDao.class);
    private final ReminderScopeService service = new ReminderScopeService(deviceDao, agentDao);

    @Test
    void runtimeScopeRequiresPersistedMacAgentAndMatchingWorkspace() {
        DeviceEntity device = device("device-db", "AA:BB:CC", "agent-a", "workspace-a");
        AgentEntity agent = new AgentEntity();
        agent.setId("agent-a");
        agent.setWorkspaceId("workspace-a");
        when(deviceDao.selectOne(any())).thenReturn(device);
        when(agentDao.selectById("agent-a")).thenReturn(agent);

        assertSame(device, service.requireRuntimeDeviceForUpdate("agent-a", "AA:BB:CC"));
    }

    @Test
    void runtimeActionScopeLocksDeviceRowForUpdate() throws Exception {
        DeviceEntity device = device("device-db", "AA:BB:CC", "agent-a", "workspace-a");
        AgentEntity agent = new AgentEntity();
        agent.setId("agent-a");
        agent.setWorkspaceId("workspace-a");
        when(deviceDao.selectOne(any())).thenReturn(device);
        when(agentDao.selectById("agent-a")).thenReturn(agent);

        assertSame(device, service.requireRuntimeDeviceForUpdate("agent-a", "AA:BB:CC"));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaQueryWrapper> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(deviceDao).selectOne(query.capture());
        Field lastSqlField = AbstractWrapper.class.getDeclaredField("lastSql");
        lastSqlField.setAccessible(true);
        SharedString lastSql = (SharedString) lastSqlField.get(query.getValue());
        assertTrue(lastSql.getStringValue().contains("LIMIT 1 FOR UPDATE"));
    }

    @Test
    void miniProgramCreateScopeLocksWorkspaceDeviceRowForUpdate() {
        DeviceEntity device = device("device-db", "AA:BB:CC", "agent-a", "workspace-a");
        AgentEntity agent = new AgentEntity();
        agent.setId("agent-a");
        agent.setWorkspaceId("workspace-a");
        when(deviceDao.selectOne(any())).thenReturn(device);
        when(deviceDao.selectByIdForUpdate("device-db")).thenReturn(device);
        when(agentDao.selectById("agent-a")).thenReturn(agent);

        assertSame(device, service.requireWorkspaceDeviceForUpdate("device-db", "workspace-a"));

        verify(deviceDao).selectOne(any());
        verify(deviceDao).selectByIdForUpdate("device-db");
    }

    @Test
    void crossWorkspaceAccessIsRejectedBeforeTakingDeviceLock() {
        when(deviceDao.selectOne(any())).thenReturn(null);

        assertThrows(RenException.class,
                () -> service.requireWorkspaceDeviceForUpdate("device-db", "workspace-a"));

        verify(deviceDao, never()).selectByIdForUpdate(any());
    }

    @Test
    void lockedWorkspaceDeviceIsRecheckedAfterOwnershipChanges() {
        when(deviceDao.selectOne(any()))
                .thenReturn(device("device-db", "AA:BB:CC", "agent-a", "workspace-a"));
        when(deviceDao.selectByIdForUpdate("device-db"))
                .thenReturn(device("device-db", "AA:BB:CC", "agent-a", "workspace-b"));

        assertThrows(RenException.class,
                () -> service.requireWorkspaceDeviceForUpdate("device-db", "workspace-a"));

        verify(deviceDao).selectByIdForUpdate("device-db");
    }

    @Test
    void lockedWorkspaceDeviceBindingIsRecheckedAfterAgentChanges() {
        when(deviceDao.selectOne(any()))
                .thenReturn(device("device-db", "AA:BB:CC", "agent-a", "workspace-a"));
        when(deviceDao.selectByIdForUpdate("device-db"))
                .thenReturn(device("device-db", "AA:BB:CC", "agent-b", "workspace-a"));
        AgentEntity movedAgent = new AgentEntity();
        movedAgent.setId("agent-b");
        movedAgent.setWorkspaceId("workspace-b");
        when(agentDao.selectById("agent-b")).thenReturn(movedAgent);

        assertThrows(RenException.class,
                () -> service.requireWorkspaceDeviceForUpdate("device-db", "workspace-a"));

        verify(deviceDao).selectByIdForUpdate("device-db");
        verify(agentDao).selectById("agent-b");
    }

    @Test
    void rejectsDeviceWhoseAgentBelongsToAnotherWorkspace() {
        DeviceEntity device = device("device-db", "AA:BB:CC", "agent-a", "workspace-a");
        AgentEntity agent = new AgentEntity();
        agent.setId("agent-a");
        agent.setWorkspaceId("workspace-b");
        when(deviceDao.selectOne(any())).thenReturn(device);
        when(agentDao.selectById("agent-a")).thenReturn(agent);

        assertThrows(RenException.class,
                () -> service.requireRuntimeDeviceForUpdate("agent-a", "AA:BB:CC"));
    }

    @Test
    void rejectsDeviceOutsideRequestedWorkspace() {
        when(deviceDao.selectOne(any())).thenReturn(null);

        assertThrows(RenException.class,
                () -> service.requireWorkspaceDevice("device-db", "workspace-a"));
    }

    private DeviceEntity device(String id, String mac, String agentId, String workspaceId) {
        DeviceEntity device = new DeviceEntity();
        device.setId(id);
        device.setMacAddress(mac);
        device.setAgentId(agentId);
        device.setWorkspaceId(workspaceId);
        return device;
    }
}
