package xiaozhi.modules.device.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.utils.ConvertUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.dto.DeviceUpdateDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.runtime.DeviceRuntimeConfigRefreshEvent;
import xiaozhi.modules.device.service.DeviceSceneService;
import xiaozhi.modules.device.service.OtaService;
import xiaozhi.modules.device.vo.UserShowDeviceListVO;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.sys.service.SysUserUtilService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;
import xiaozhi.modules.workspace.service.WorkspaceService;

class DeviceSpeechRoutingUpdateTest {

    @Test
    void deviceListViewRetainsAutoUpdateAndSpeechRoutingFields() {
        DeviceEntity device = new DeviceEntity();
        device.setAutoUpdate(1);
        device.setSpeechInputMode("device");
        device.setSpeechOutputMode("auto");

        UserShowDeviceListVO view = ConvertUtils.sourceToTarget(device, UserShowDeviceListVO.class);

        assertEquals(1, view.getAutoUpdate());
        assertEquals("device", view.getSpeechInputMode());
        assertEquals("auto", view.getSpeechOutputMode());
    }

    @Test
    void updateKeepsWorkspaceWriteGuardAndPublishesDeviceRefresh() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        WorkspacePermissionService permissionService = mock(WorkspacePermissionService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        DeviceServiceImpl service = newService(deviceDao, permissionService, publisher);

        DeviceEntity existing = new DeviceEntity();
        existing.setId("device-a");
        existing.setWorkspaceId("workspace-a");
        existing.setAgentId("agent-a");
        when(deviceDao.selectById("device-a")).thenReturn(existing);

        DeviceUpdateDTO dto = new DeviceUpdateDTO();
        dto.setSpeechInputMode("device");
        dto.setSpeechOutputMode("auto");
        service.updateDeviceInfo("device-a", dto);

        verify(permissionService).requireWrite("workspace-a");
        ArgumentCaptor<DeviceEntity> updateCaptor = ArgumentCaptor.forClass(DeviceEntity.class);
        verify(deviceDao).updateById(updateCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("device", updateCaptor.getValue().getSpeechInputMode());
        org.junit.jupiter.api.Assertions.assertEquals("auto", updateCaptor.getValue().getSpeechOutputMode());

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        DeviceRuntimeConfigRefreshEvent event = (DeviceRuntimeConfigRefreshEvent) eventCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("device-a", event.deviceId());
        org.junit.jupiter.api.Assertions.assertEquals("agent-a", event.toAgentId());
        org.junit.jupiter.api.Assertions.assertEquals("device_speech_routing_updated", event.reason());
    }

    @Test
    void deniedWorkspaceWriteDoesNotUpdateOrRefresh() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        WorkspacePermissionService permissionService = mock(WorkspacePermissionService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        DeviceServiceImpl service = newService(deviceDao, permissionService, publisher);

        DeviceEntity existing = new DeviceEntity();
        existing.setId("device-a");
        existing.setWorkspaceId("workspace-a");
        when(deviceDao.selectById("device-a")).thenReturn(existing);
        doThrow(new IllegalStateException("denied")).when(permissionService).requireWrite("workspace-a");

        assertThrows(IllegalStateException.class,
                () -> service.updateDeviceInfo("device-a", new DeviceUpdateDTO()));
        verify(deviceDao, never()).updateById(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void authenticatedRuntimeUpdateChangesOnlyInputPolicyAndPublishesRefresh() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        WorkspacePermissionService permissionService = mock(WorkspacePermissionService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        DeviceServiceImpl service = newService(deviceDao, permissionService, publisher);

        DeviceEntity existing = new DeviceEntity();
        existing.setId("device-a");
        existing.setMacAddress("AA:BB:CC:DD:EE:FF");
        existing.setAgentId("agent-a");
        when(deviceDao.selectOne(any())).thenReturn(existing);

        service.updateSpeechInputModeFromRuntime("AA:BB:CC:DD:EE:FF", "auto");

        ArgumentCaptor<DeviceEntity> updateCaptor = ArgumentCaptor.forClass(DeviceEntity.class);
        verify(deviceDao).updateById(updateCaptor.capture());
        assertEquals("device-a", updateCaptor.getValue().getId());
        assertEquals("auto", updateCaptor.getValue().getSpeechInputMode());
        assertEquals(null, updateCaptor.getValue().getSpeechOutputMode());
        verify(permissionService, never()).requireWrite(any());
        verify(publisher).publishEvent(any(DeviceRuntimeConfigRefreshEvent.class));
    }

    @Test
    void runtimeUpdateRejectsUnknownPolicyBeforeDatabaseAccess() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        DeviceServiceImpl service = newService(
                deviceDao,
                mock(WorkspacePermissionService.class),
                mock(ApplicationEventPublisher.class));

        assertThrows(RuntimeException.class,
                () -> service.updateSpeechInputModeFromRuntime("AA:BB", "fallback"));

        verify(deviceDao, never()).selectOne(any());
        verify(deviceDao, never()).updateById(any());
    }

    private DeviceServiceImpl newService(
            DeviceDao deviceDao,
            WorkspacePermissionService permissionService,
            ApplicationEventPublisher publisher) {
        DeviceServiceImpl service = new DeviceServiceImpl(
                deviceDao,
                mock(SysUserUtilService.class),
                mock(SysParamsService.class),
                mock(RedisUtils.class),
                mock(OtaService.class),
                mock(AgentDao.class),
                mock(WorkspaceService.class),
                permissionService,
                mock(DeviceSceneService.class),
                publisher);
        ReflectionTestUtils.setField(service, "baseDao", deviceDao);
        return service;
    }
}
