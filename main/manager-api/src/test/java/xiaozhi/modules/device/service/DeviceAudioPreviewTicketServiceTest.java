package xiaozhi.modules.device.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dto.DeviceAudioPreviewTicketDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

class DeviceAudioPreviewTicketServiceTest {

    @Test
    void issuesWorkspaceScopedShortLivedTicket() throws Exception {
        DeviceService deviceService = mock(DeviceService.class);
        SysParamsService sysParamsService = mock(SysParamsService.class);
        WorkspacePermissionService permissionService = mock(WorkspacePermissionService.class);
        DeviceEntity device = new DeviceEntity();
        device.setId("record-a");
        device.setWorkspaceId("workspace-a");
        device.setMacAddress("68:ee:00:00:72:18");
        when(deviceService.selectById("record-a")).thenReturn(device);
        when(sysParamsService.getValue(Constant.SERVER_WEBSOCKET, true))
                .thenReturn("wss://yh.wejoy.link/xiaozhi/v1/;ws://backup:8000/xiaozhi/v1/");
        when(deviceService.generateWebSocketToken(startsWith("web-audio-preview-"),
                org.mockito.ArgumentMatchers.eq(device.getMacAddress())))
                .thenReturn("signed.ticket");

        DeviceAudioPreviewTicketService service = new DeviceAudioPreviewTicketService(
                deviceService, sysParamsService, permissionService);
        DeviceAudioPreviewTicketDTO ticket = service.issue("record-a");

        assertEquals("wss://yh.wejoy.link/xiaozhi/audio-preview/", ticket.getWebsocketUrl());
        assertEquals(device.getMacAddress(), ticket.getDeviceId());
        assertTrue(ticket.getClientId().startsWith("web-audio-preview-"));
        assertEquals("signed.ticket", ticket.getToken());
        assertEquals(60, ticket.getExpiresInSeconds());
        verify(permissionService).requireRead("workspace-a");
    }

    @Test
    void rejectsMissingDeviceAndInvalidWebsocketAddress() {
        DeviceService deviceService = mock(DeviceService.class);
        SysParamsService sysParamsService = mock(SysParamsService.class);
        WorkspacePermissionService permissionService = mock(WorkspacePermissionService.class);
        DeviceAudioPreviewTicketService service = new DeviceAudioPreviewTicketService(
                deviceService, sysParamsService, permissionService);

        assertThrows(RenException.class, () -> service.issue("missing"));
        assertThrows(RenException.class,
                () -> DeviceAudioPreviewTicketService.buildPreviewUrl("https://example.com/xiaozhi/v1/"));
        assertThrows(RenException.class,
                () -> DeviceAudioPreviewTicketService.firstConfiguredWebsocket(" ; "));
    }
}
