package xiaozhi.modules.device.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.OtaService;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.sys.service.SysUserUtilService;
import xiaozhi.modules.workspace.entity.WorkspaceEntity;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;
import xiaozhi.modules.workspace.service.WorkspaceService;

class DeviceWorkspaceIsolationTest {

    @Test
    void getUserDevicesFiltersByCurrentWorkspaceAndAgent() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId("workspace-a");
        when(workspaceService.getCurrentWorkspaceEntity()).thenReturn(workspace);

        DeviceEntity device = new DeviceEntity();
        device.setId("device-a");
        device.setWorkspaceId("workspace-a");
        device.setAgentId("agent-a");
        when(deviceDao.selectList(any(Wrapper.class))).thenReturn(List.of(device));

        DeviceServiceImpl service = new DeviceServiceImpl(
                deviceDao,
                mock(SysUserUtilService.class),
                mock(SysParamsService.class),
                mock(RedisUtils.class),
                mock(OtaService.class),
                mock(AgentDao.class),
                workspaceService,
                mock(WorkspacePermissionService.class),
                mock(xiaozhi.modules.device.service.DeviceSceneService.class));
        ReflectionTestUtils.setField(service, "baseDao", deviceDao);

        List<DeviceEntity> result = service.getUserDevices(1001L, "agent-a");

        ArgumentCaptor<Wrapper<DeviceEntity>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(deviceDao).selectList(wrapperCaptor.capture());
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
        assertTrue(sqlSegment.contains("workspace_id"));
        assertTrue(sqlSegment.contains("agent_id"));
        verify(workspaceService).getCurrentWorkspaceEntity();
        assertEquals(1, result.size());
        assertEquals("device-a", result.get(0).getId());
    }
}
