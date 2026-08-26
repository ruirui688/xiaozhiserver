package xiaozhi.modules.maintenance.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.maintenance.dao.MaintenanceRunEventDao;
import xiaozhi.modules.maintenance.entity.MaintenanceRunEventEntity;

class MaintenanceRunEventServiceTest {
    @Test
    void persistsOnlySanitizedBoundedOperationalEvents() {
        MaintenanceRunEventDao dao = mock(MaintenanceRunEventDao.class);
        MaintenanceRunEventService service = new MaintenanceRunEventService(dao, new ObjectMapper());

        service.record("run-1", "source_sync", "error",
                "Authorization=Bearer secret-value token=private-value", Map.of("chunkCount", 5));

        ArgumentCaptor<MaintenanceRunEventEntity> captor = ArgumentCaptor.forClass(MaintenanceRunEventEntity.class);
        verify(dao).insert(captor.capture());
        MaintenanceRunEventEntity event = captor.getValue();
        assertTrue(event.getMessage().contains("Authorization=***"));
        assertTrue(event.getMessage().contains("token=***"));
        assertFalse(event.getMessage().contains("secret-value"));
        assertTrue(event.getDetailJson().contains("chunkCount"));
    }
}
