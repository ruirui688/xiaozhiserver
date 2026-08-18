package xiaozhi.modules.reception.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.person.dao.PersonDeviceBindingDao;
import xiaozhi.modules.person.dao.PersonProfileDao;
import xiaozhi.modules.person.entity.PersonProfileEntity;
import xiaozhi.modules.reception.dao.ReceptionAppointmentDao;
import xiaozhi.modules.reception.dao.ReceptionCustomerProfileDao;
import xiaozhi.modules.reception.dao.ReceptionVisitEventDao;
import xiaozhi.modules.reception.dto.ReceptionVisitEventDTO;
import xiaozhi.modules.reception.entity.ReceptionAppointmentEntity;
import xiaozhi.modules.reception.entity.ReceptionCustomerProfileEntity;
import xiaozhi.modules.reception.entity.ReceptionVisitEventEntity;
import xiaozhi.modules.reception.service.impl.ReceptionEventServiceImpl;

class ReceptionEventServiceImplTest {
    @Test
    void fifthVisitAcrossThirtyDaysBecomesLongTermAndMarksAppointmentArrived() {
        Fixture fixture = new Fixture();
        ReceptionCustomerProfileEntity customer = new ReceptionCustomerProfileEntity();
        customer.setCustomerStage("RETURNING");
        customer.setVisitCount(4);
        customer.setFirstVisitAt(new Date(System.currentTimeMillis() - 31L * 24L * 60L * 60L * 1000L));
        ReceptionAppointmentEntity appointment = new ReceptionAppointmentEntity();
        appointment.setStatus("CONFIRMED");
        when(fixture.customerDao.selectOne(any())).thenReturn(customer);
        when(fixture.appointmentDao.selectList(any())).thenReturn(List.of(appointment));
        when(fixture.eventDao.selectOne(any())).thenReturn(null);

        assertTrue(fixture.service.recordVisit(fixture.request()));
        assertEquals(5, customer.getVisitCount());
        assertEquals("LONG_TERM", customer.getCustomerStage());
        assertEquals("ARRIVED", appointment.getStatus());
    }

    @Test
    void duplicateDeviceEventDoesNotIncrementMemoryTwice() {
        Fixture fixture = new Fixture();
        when(fixture.eventDao.selectOne(any())).thenReturn(new ReceptionVisitEventEntity());
        assertFalse(fixture.service.recordVisit(fixture.request()));
    }

    private static final class Fixture {
        final DeviceDao deviceDao = mock(DeviceDao.class);
        final PersonProfileDao personDao = mock(PersonProfileDao.class);
        final PersonDeviceBindingDao bindingDao = mock(PersonDeviceBindingDao.class);
        final ReceptionCustomerProfileDao customerDao = mock(ReceptionCustomerProfileDao.class);
        final ReceptionAppointmentDao appointmentDao = mock(ReceptionAppointmentDao.class);
        final ReceptionVisitEventDao eventDao = mock(ReceptionVisitEventDao.class);
        final ReceptionEventServiceImpl service = new ReceptionEventServiceImpl(
                deviceDao,
                personDao,
                bindingDao,
                customerDao,
                appointmentDao,
                eventDao,
                new ObjectMapper());

        Fixture() {
            DeviceEntity device = new DeviceEntity();
            device.setId("device-a");
            device.setWorkspaceId("workspace-a");
            device.setAgentId("agent-a");
            when(deviceDao.selectOne(any())).thenReturn(device);
            PersonProfileEntity person = new PersonProfileEntity();
            person.setId("person-a");
            person.setWorkspaceId("workspace-a");
            person.setStatus(1);
            when(personDao.selectById("person-a")).thenReturn(person);
            when(bindingDao.selectCount(any())).thenReturn(1L);
        }

        ReceptionVisitEventDTO request() {
            ReceptionVisitEventDTO dto = new ReceptionVisitEventDTO();
            dto.setMacAddress("AA:BB");
            dto.setEventId("event-a");
            dto.setGroupSessionId("group-a");
            dto.setSelectedPersonIds(List.of("person-a"));
            dto.setPresentCount(1);
            return dto;
        }
    }
}
