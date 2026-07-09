package xiaozhi.modules.device.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;

class OttoDebugServiceImplTest {
    private DeviceService deviceService;
    private OttoDebugServiceImpl service;

    @BeforeEach
    void setUp() {
        deviceService = Mockito.mock(DeviceService.class);
        service = new OttoDebugServiceImpl(deviceService);
    }

    @Test
    void rejectsNonOttoDevice() {
        DeviceEntity device = device("device-1", 1L, "bread-compact");
        when(deviceService.selectById("device-1")).thenReturn(device);

        assertThrows(RenException.class, () -> service.snapshot(1L, "device-1"));
    }

    @Test
    void rejectsInvalidHostBeforeUdpSend() {
        DeviceEntity device = device("device-1", 1L, "otto-robot");
        when(deviceService.selectById("device-1")).thenReturn(device);

        assertThrows(RenException.class, () -> service.connect(1L, "device-1", "not-a-host"));
    }

    @Test
    void rejectsPublicHostBeforeUdpSend() {
        DeviceEntity device = device("device-1", 1L, "otto-robot");
        when(deviceService.selectById("device-1")).thenReturn(device);

        assertThrows(RenException.class, () -> service.connect(1L, "device-1", "8.8.8.8"));
    }

    @Test
    void requiresConnectBeforeSnapshot() {
        DeviceEntity device = device("device-1", 1L, "otto-robot");
        when(deviceService.selectById("device-1")).thenReturn(device);

        assertThrows(RenException.class, () -> service.snapshot(1L, "device-1"));
    }

    private DeviceEntity device(String id, Long userId, String board) {
        DeviceEntity device = new DeviceEntity();
        device.setId(id);
        device.setUserId(userId);
        device.setBoard(board);
        return device;
    }
}
