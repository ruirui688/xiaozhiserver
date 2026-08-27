package xiaozhi.modules.maintenance.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.sys.service.SysParamsService;

class MaintenanceRunCapabilityServiceTest {
    @Test
    void capabilityIsBoundToOneRun() {
        SysParamsService params = mock(SysParamsService.class);
        when(params.getValue(Constant.SERVER_SECRET, true)).thenReturn("test-server-secret");
        MaintenanceRunCapabilityService service = new MaintenanceRunCapabilityService(params);

        String first = service.issue("run-1");
        String second = service.issue("run-2");

        assertNotEquals(first, second);
        assertDoesNotThrow(() -> service.verify("run-1", first));
        assertThrows(RenException.class, () -> service.verify("run-2", first));
    }
}
