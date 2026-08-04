package xiaozhi.modules.device.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.dao.DeviceSceneBindingDao;
import xiaozhi.modules.device.dao.DeviceSceneSwitchDao;
import xiaozhi.modules.device.dto.DeviceSceneCreateDTO;
import xiaozhi.modules.device.dto.DeviceSceneSwitchDTO;
import xiaozhi.modules.device.dto.DeviceSceneSwitchResultDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.entity.DeviceSceneBindingEntity;
import xiaozhi.modules.device.entity.DeviceSceneSwitchEntity;
import xiaozhi.modules.device.runtime.DeviceRuntimeConfigRefreshEvent;
import xiaozhi.modules.device.vo.DeviceSceneVO;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

class DeviceSceneServiceImplTest {
    @Test
    void switchSceneUpdatesDeviceAndPublishesDeviceScopedRefresh() {
        Fixture fixture = new Fixture();
        DeviceEntity device = fixture.device("agent-a", 4L);
        DeviceSceneBindingEntity binding = fixture.binding("binding-b", "agent-b");
        AgentEntity agent = fixture.agent("agent-b");
        when(fixture.switchDao.selectOne(any())).thenReturn(null);
        when(fixture.deviceDao.selectByIdForUpdate("device-a")).thenReturn(device);
        when(fixture.bindingDao.selectOne(any())).thenReturn(binding);
        when(fixture.agentDao.selectById("agent-b")).thenReturn(agent);
        when(fixture.deviceDao.update(any(), any())).thenReturn(1);

        DeviceSceneSwitchDTO dto = new DeviceSceneSwitchDTO();
        dto.setBindingId("binding-b");
        dto.setSwitchRequestId("request-b");
        DeviceSceneVO result = fixture.service.switchActiveScene("device-a", dto);

        assertTrue(result.isCurrent());
        assertEquals(5L, result.getConfigVersion());
        assertEquals("REQUESTED", result.getSwitchStatus());
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(fixture.publisher).publishEvent(eventCaptor.capture());
        DeviceRuntimeConfigRefreshEvent event = (DeviceRuntimeConfigRefreshEvent) eventCaptor.getValue();
        assertEquals("device-a", event.deviceId());
        assertEquals("agent-b", event.toAgentId());
        assertEquals(5L, event.configVersion());
    }

    @Test
    void failedRuntimeActivationRollsBackOnlyMatchingDeviceVersion() {
        Fixture fixture = new Fixture();
        DeviceSceneSwitchEntity audit = new DeviceSceneSwitchEntity();
        audit.setDeviceId("device-a");
        audit.setFromAgentId("agent-a");
        audit.setToAgentId("agent-b");
        audit.setSwitchRequestId("request-b");
        audit.setConfigVersion(5L);
        audit.setStatus("SCHEDULED");
        when(fixture.switchDao.selectOne(any())).thenReturn(audit);
        when(fixture.deviceDao.update(any(), any())).thenReturn(1);

        DeviceSceneSwitchResultDTO dto = new DeviceSceneSwitchResultDTO();
        dto.setDeviceId("device-a");
        dto.setAgentId("agent-b");
        dto.setSwitchRequestId("request-b");
        dto.setConfigVersion(5L);
        dto.setStatus("FAILED");
        dto.setFailureCode("RuntimeError: upstream secret must not leak");
        fixture.service.recordRuntimeResult(dto);

        assertEquals("ROLLED_BACK", audit.getStatus());
        assertEquals("RuntimeError__upstream_secret_must_not_leak", audit.getFailureCode());
        verify(fixture.switchDao).updateById(audit);
    }

    @Test
    void referencedAgentCannotBeDeleted() {
        Fixture fixture = new Fixture();
        when(fixture.bindingDao.selectCount(any())).thenReturn(1L);

        RenException error = assertThrows(
                RenException.class,
                () -> fixture.service.assertAgentNotBound("agent-a"));

        assertTrue(error.getMessage().contains("设备场景引用"));
    }

    @Test
    void createSceneRejectsMoreThanTwentyBindingsWhileHoldingDeviceLock() {
        Fixture fixture = new Fixture();
        when(fixture.deviceDao.selectByIdForUpdate("device-a"))
                .thenReturn(fixture.device("agent-a", 1L));
        when(fixture.bindingDao.selectCount(any())).thenReturn(20L);
        DeviceSceneCreateDTO dto = new DeviceSceneCreateDTO();
        dto.setAgentId("agent-b");
        dto.setSceneName("英语老师");

        RenException error = assertThrows(
                RenException.class,
                () -> fixture.service.createScene("device-a", dto));

        assertTrue(error.getMessage().contains("最多可关联20个场景"));
        verify(fixture.deviceDao).selectByIdForUpdate("device-a");
    }

    private static class Fixture {
        final DeviceSceneBindingDao bindingDao = mock(DeviceSceneBindingDao.class);
        final DeviceSceneSwitchDao switchDao = mock(DeviceSceneSwitchDao.class);
        final DeviceDao deviceDao = mock(DeviceDao.class);
        final AgentDao agentDao = mock(AgentDao.class);
        final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        final RedisUtils redisUtils = mock(RedisUtils.class);
        final DeviceSceneServiceImpl service = new DeviceSceneServiceImpl(
                bindingDao,
                switchDao,
                deviceDao,
                agentDao,
                mock(ModelConfigService.class),
                mock(TimbreService.class),
                mock(WorkspacePermissionService.class),
                publisher,
                redisUtils);

        DeviceEntity device(String agentId, long version) {
            DeviceEntity value = new DeviceEntity();
            value.setId("device-a");
            value.setWorkspaceId("workspace-a");
            value.setAgentId(agentId);
            value.setConfigVersion(version);
            return value;
        }

        DeviceSceneBindingEntity binding(String id, String agentId) {
            DeviceSceneBindingEntity value = new DeviceSceneBindingEntity();
            value.setId(id);
            value.setDeviceId("device-a");
            value.setWorkspaceId("workspace-a");
            value.setAgentId(agentId);
            value.setSceneName("英语老师");
            value.setSwitchAliases(List.of("老师模式"));
            value.setEnabled(1);
            value.setSort(1);
            return value;
        }

        AgentEntity agent(String id) {
            AgentEntity value = new AgentEntity();
            value.setId(id);
            value.setWorkspaceId("workspace-a");
            value.setAgentName("Teacher");
            return value;
        }
    }
}
