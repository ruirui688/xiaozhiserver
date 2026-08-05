package xiaozhi.modules.device.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dto.DeviceConversationContextResetDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

class DeviceConversationContextServiceTest {

    @Test
    void resetsOnlyWorkspaceAuthorizedBoundDevice() {
        DeviceService deviceService = mock(DeviceService.class);
        WorkspacePermissionService permissionService = mock(WorkspacePermissionService.class);
        SysParamsService sysParamsService = mock(SysParamsService.class);
        Environment environment = mock(Environment.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        DeviceEntity device = new DeviceEntity();
        device.setId("record-a");
        device.setWorkspaceId("workspace-a");
        device.setMacAddress("68:ee:00:00:72:18");
        device.setAgentId("agent-a");
        when(deviceService.selectById("record-a")).thenReturn(device);
        when(sysParamsService.getValue(Constant.SERVER_SECRET, true)).thenReturn("server-secret");
        when(environment.getProperty("xiaozhi.server.http-base-url"))
                .thenReturn("http://xiaozhi-server:8003/");
        when(restTemplate.exchange(
                eq("http://xiaozhi-server:8003/device/session/context/reset"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>(
                        "{\"success\":true,\"data\":{\"online\":true,\"reset\":true,"
                                + "\"mode\":\"realtime\",\"removedMessages\":6,"
                                + "\"upstreamResetScheduled\":false,"
                                + "\"upstreamResetCompleted\":true}}",
                        HttpStatus.OK));

        DeviceConversationContextService service = new DeviceConversationContextService(
                deviceService, permissionService, sysParamsService, environment, restTemplate);
        DeviceConversationContextResetDTO result = service.reset("record-a");

        assertEquals("realtime", result.mode());
        assertEquals(6, result.removedMessages());
        assertEquals(true, result.upstreamResetCompleted());
        verify(permissionService).requireWrite("workspace-a");
        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("http://xiaozhi-server:8003/device/session/context/reset"),
                eq(HttpMethod.POST),
                request.capture(),
                eq(String.class));
        assertEquals("Bearer server-secret",
                request.getValue().getHeaders().getFirst("Authorization"));
    }

    @Test
    void rejectsMissingDeviceOrServerSecretBeforeRuntimeCall() {
        DeviceService deviceService = mock(DeviceService.class);
        WorkspacePermissionService permissionService = mock(WorkspacePermissionService.class);
        SysParamsService sysParamsService = mock(SysParamsService.class);
        Environment environment = mock(Environment.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        DeviceConversationContextService service = new DeviceConversationContextService(
                deviceService, permissionService, sysParamsService, environment, restTemplate);

        assertThrows(RenException.class, () -> service.reset("missing"));

        DeviceEntity device = new DeviceEntity();
        device.setWorkspaceId("workspace-a");
        device.setMacAddress("68:ee:00:00:72:18");
        device.setAgentId("agent-a");
        when(deviceService.selectById("record-a")).thenReturn(device);
        when(sysParamsService.getValue(Constant.SERVER_SECRET, true)).thenReturn("");
        assertThrows(RenException.class, () -> service.reset("record-a"));
    }
}
