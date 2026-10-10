package xiaozhi.modules.memory.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.memory.dao.MemorySourceEventDao;
import xiaozhi.modules.memory.entity.MemorySourceEventEntity;

class MemoryRequestScopeServiceTest {
    private final DeviceDao devices = mock(DeviceDao.class);
    private final MemorySourceEventDao events = mock(MemorySourceEventDao.class);
    private final MemoryRequestScopeService service = new MemoryRequestScopeService(devices, events);

    @Test
    void missingOrUnboundDevicesCannotFallBackToSuppliedAgent() {
        assertThrows(IllegalArgumentException.class, () -> service.require("agent", null, null));
        assertThrows(IllegalArgumentException.class, () -> service.require("agent", "foreign-device", null));
        verifyNoInteractions(events);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void historicalRoleFlushRequiresRecordedAgentDeviceAndSessionTogether() {
        when(events.selectCount(any())).thenReturn(1L);
        service.require("old-agent", "device", "old-session");
        ArgumentCaptor<QueryWrapper<MemorySourceEventEntity>> query = ArgumentCaptor.forClass((Class) QueryWrapper.class);
        verify(events).selectCount(query.capture());
        query.getValue().getSqlSegment();
        assertTrue(query.getValue().getParamNameValuePairs().values().containsAll(java.util.List.of("old-agent", "device", "old-session")));
        when(events.selectCount(any())).thenReturn(0L);
        assertThrows(IllegalArgumentException.class, () -> service.require("other-agent", "device", "old-session"));
    }
}
