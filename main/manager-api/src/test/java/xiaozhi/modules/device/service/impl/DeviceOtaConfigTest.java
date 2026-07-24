package xiaozhi.modules.device.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.dto.DeviceReportRespDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.OtaService;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.sys.service.SysUserUtilService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;
import xiaozhi.modules.workspace.service.WorkspaceService;

class DeviceOtaConfigTest {

    @Test
    void buildsDeviceConfigFromBoundAgentName() {
        AgentDao agentDao = mock(AgentDao.class);
        DeviceServiceImpl service = newService(agentDao);
        DeviceEntity device = new DeviceEntity();
        device.setAgentId("agent-a");

        AgentEntity agent = new AgentEntity();
        agent.setId("agent-a");
        agent.setAgentName("小慧");
        agent.setUpdatedAt(new Date(1753351200000L));
        when(agentDao.selectById("agent-a")).thenReturn(agent);

        DeviceReportRespDTO.DeviceConfig config = ReflectionTestUtils.invokeMethod(
                service, "buildDeviceConfig", device);

        assertEquals(1, config.getVersion());
        assertEquals(1753351200000L, config.getRevision());
        assertEquals("小慧", config.getAssistant_name());
        assertEquals(java.util.List.of("小慧"), config.getWake_words());
        verify(agentDao).selectById("agent-a");
    }

    @Test
    void omitsDeviceConfigWhenDeviceHasNoBoundAgent() {
        AgentDao agentDao = mock(AgentDao.class);
        DeviceServiceImpl service = newService(agentDao);
        DeviceEntity device = new DeviceEntity();

        DeviceReportRespDTO.DeviceConfig config = ReflectionTestUtils.invokeMethod(
                service, "buildDeviceConfig", device);

        assertNull(config);
        verifyNoInteractions(agentDao);
    }

    @Test
    void serializesDeviceConfigWithOtaContractFieldNamesAndOmitsItWhenAbsent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        DeviceReportRespDTO response = new DeviceReportRespDTO();
        DeviceReportRespDTO.DeviceConfig config = new DeviceReportRespDTO.DeviceConfig();
        config.setVersion(1);
        config.setRevision(1753351200000L);
        config.setAssistant_name("小慧");
        config.setWake_words(java.util.List.of("小慧"));
        response.setDevice_config(config);

        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertEquals("小慧", root.path("device_config").path("assistant_name").asText());
        assertEquals("小慧", root.path("device_config").path("wake_words").get(0).asText());

        response.setDevice_config(null);
        JsonNode responseWithoutConfig = objectMapper.readTree(objectMapper.writeValueAsString(response));
        assertFalse(responseWithoutConfig.has("device_config"));
    }

    private DeviceServiceImpl newService(AgentDao agentDao) {
        return new DeviceServiceImpl(
                mock(DeviceDao.class),
                mock(SysUserUtilService.class),
                mock(SysParamsService.class),
                mock(RedisUtils.class),
                mock(OtaService.class),
                agentDao,
                mock(WorkspaceService.class),
                mock(WorkspacePermissionService.class));
    }
}
