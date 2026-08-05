package xiaozhi.modules.device.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.runtime.DeviceRuntimeConfigRefreshEvent;
import xiaozhi.modules.device.service.DeviceSceneService;
import xiaozhi.modules.device.service.OtaService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.sys.service.SysUserUtilService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;
import xiaozhi.modules.workspace.service.WorkspaceService;

class DeviceActivationRuntimeRefreshTest {

    @Test
    void activationPublishesImmediateDeviceScopedRuntimeRefresh() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        RedisUtils redisUtils = mock(RedisUtils.class);
        AgentDao agentDao = mock(AgentDao.class);
        WorkspacePermissionService permissionService = mock(WorkspacePermissionService.class);
        DeviceSceneService sceneService = mock(DeviceSceneService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        DeviceServiceImpl service = new DeviceServiceImpl(
                deviceDao,
                mock(SysUserUtilService.class),
                mock(SysParamsService.class),
                redisUtils,
                mock(OtaService.class),
                agentDao,
                mock(WorkspaceService.class),
                permissionService,
                sceneService,
                publisher);
        ReflectionTestUtils.setField(service, "baseDao", deviceDao);

        Map<String, Object> activation = new HashMap<>();
        activation.put("activation_code", "123456");
        activation.put("mac_address", "dc:b4:d9:24:07:dc");
        activation.put("board", "esp32");
        activation.put("app_version", "1.0.0");
        when(redisUtils.get(anyString())).thenReturn("device-a", activation);
        when(deviceDao.selectById("device-a")).thenReturn(null);
        AgentEntity agent = new AgentEntity();
        agent.setId("agent-a");
        agent.setWorkspaceId("workspace-a");
        when(agentDao.selectById("agent-a")).thenReturn(agent);
        UserDetail user = new UserDetail();
        user.setId(1001L);

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(user);
            service.deviceActivation("agent-a", "123456");
        }

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        DeviceRuntimeConfigRefreshEvent event = (DeviceRuntimeConfigRefreshEvent) eventCaptor.getValue();
        assertEquals("device-a", event.deviceId());
        assertEquals("agent-a", event.toAgentId());
        assertEquals("device_activated", event.reason());
        verify(permissionService).requireWrite("workspace-a");
        verify(sceneService).ensureCurrentBinding(any(), any(), any());
        verify(redisUtils).delete(anyList());
    }
}
