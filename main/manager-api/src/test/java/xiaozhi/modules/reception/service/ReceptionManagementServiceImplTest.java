package xiaozhi.modules.reception.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.person.dao.PersonProfileDao;
import xiaozhi.modules.person.entity.PersonProfileEntity;
import xiaozhi.modules.reception.dao.ReceptionAppointmentDao;
import xiaozhi.modules.reception.dao.ReceptionCustomerProfileDao;
import xiaozhi.modules.reception.dao.ReceptionWorkspaceConfigDao;
import xiaozhi.modules.reception.dto.ReceptionAppointmentSaveDTO;
import xiaozhi.modules.reception.entity.ReceptionAppointmentEntity;
import xiaozhi.modules.reception.service.impl.ReceptionManagementServiceImpl;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

class ReceptionManagementServiceImplTest {
    @Test
    void newAppointmentDefaultsToPending() {
        Fixture fixture = new Fixture();
        ReceptionAppointmentSaveDTO dto = fixture.dto();
        dto.setStatus(null);

        var result = fixture.service.createAppointment("person-a", dto);

        assertEquals("PENDING", result.getStatus());
    }

    @Test
    void overlappingActiveAppointmentIsRejected() {
        Fixture fixture = new Fixture();
        when(fixture.appointmentDao.selectCount(any())).thenReturn(1L);

        assertThrows(RenException.class, () -> fixture.service.createAppointment("person-a", fixture.dto()));
    }

    @Test
    void terminalAppointmentCannotReturnToConfirmed() {
        Fixture fixture = new Fixture();
        ReceptionAppointmentEntity existing = new ReceptionAppointmentEntity();
        existing.setId("appointment-a");
        existing.setWorkspaceId("workspace-a");
        existing.setPersonId("person-a");
        existing.setStatus("COMPLETED");
        when(fixture.appointmentDao.selectById("appointment-a")).thenReturn(existing);
        ReceptionAppointmentSaveDTO dto = fixture.dto();
        dto.setStatus("CONFIRMED");

        assertThrows(RenException.class, () -> fixture.service.updateAppointment("appointment-a", dto));
    }

    private static final class Fixture {
        final PersonProfileDao personDao = mock(PersonProfileDao.class);
        final ReceptionCustomerProfileDao customerDao = mock(ReceptionCustomerProfileDao.class);
        final ReceptionAppointmentDao appointmentDao = mock(ReceptionAppointmentDao.class);
        final ReceptionWorkspaceConfigDao configDao = mock(ReceptionWorkspaceConfigDao.class);
        final WorkspacePermissionService permission = mock(WorkspacePermissionService.class);
        final ReceptionManagementServiceImpl service = new ReceptionManagementServiceImpl(
                personDao, customerDao, appointmentDao, configDao, permission, new ObjectMapper());

        Fixture() {
            PersonProfileEntity person = new PersonProfileEntity();
            person.setId("person-a");
            person.setWorkspaceId("workspace-a");
            when(personDao.selectById("person-a")).thenReturn(person);
            when(appointmentDao.selectCount(any())).thenReturn(0L);
        }

        ReceptionAppointmentSaveDTO dto() {
            ReceptionAppointmentSaveDTO dto = new ReceptionAppointmentSaveDTO();
            dto.setStartAt(new Date(System.currentTimeMillis() + 3_600_000L));
            dto.setEndAt(new Date(System.currentTimeMillis() + 7_200_000L));
            dto.setPartySize(2);
            dto.setStatus("PENDING");
            return dto;
        }
    }
}
