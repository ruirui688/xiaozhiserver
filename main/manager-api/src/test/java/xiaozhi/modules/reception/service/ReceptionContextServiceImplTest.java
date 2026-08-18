package xiaozhi.modules.reception.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Date;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.person.dao.PersonDeviceBindingDao;
import xiaozhi.modules.person.dao.PersonProfileDao;
import xiaozhi.modules.person.entity.PersonProfileEntity;
import xiaozhi.modules.reception.dao.ReceptionAppointmentDao;
import xiaozhi.modules.reception.dao.ReceptionCustomerProfileDao;
import xiaozhi.modules.reception.dao.ReceptionWorkspaceConfigDao;
import xiaozhi.modules.reception.dto.ReceptionContextRequestDTO;
import xiaozhi.modules.reception.entity.ReceptionAppointmentEntity;
import xiaozhi.modules.reception.entity.ReceptionCustomerProfileEntity;
import xiaozhi.modules.reception.entity.ReceptionWorkspaceConfigEntity;
import xiaozhi.modules.reception.service.impl.ReceptionContextServiceImpl;

class ReceptionContextServiceImplTest {
    @Test
    void returnsOnlyConsentedCustomerFactsAndActiveAppointment() {
        Fixture fixture = new Fixture();
        ReceptionCustomerProfileEntity customer = new ReceptionCustomerProfileEntity();
        customer.setCustomerStage("LONG_TERM");
        customer.setCompanyName("示例公司");
        customer.setHobbiesJson("[\"咖啡\"]");
        customer.setSensitiveNotes("需要无障碍座位");
        customer.setConsentScope("RECEPTION,SENSITIVE,RECEPTION");
        customer.setVisitCount(6);
        ReceptionAppointmentEntity appointment = new ReceptionAppointmentEntity();
        appointment.setId("appointment-a");
        appointment.setPartySize(3);
        appointment.setSeatRequest("安静区域");
        ReceptionWorkspaceConfigEntity config = new ReceptionWorkspaceConfigEntity();
        config.setCompanyName("机器人公司");
        config.setCompanyIntroText("我们提供机器人服务。");
        config.setConfigVersion(4L);
        when(fixture.customerDao.selectOne(any())).thenReturn(customer);
        when(fixture.appointmentDao.selectOne(any())).thenReturn(appointment);
        when(fixture.configDao.selectOne(any())).thenReturn(config);

        var result = fixture.service.resolve(fixture.request());

        assertEquals("机器人公司", result.getCompanyName());
        assertEquals(4L, result.getConfigVersion());
        assertEquals("LONG_TERM", result.getPeople().get(0).getCustomerStage());
        assertEquals("MALE", result.getPeople().get(0).getGender());
        assertEquals("SENIOR", result.getPeople().get(0).getAgeGroup());
        assertEquals("示例公司", result.getPeople().get(0).getCompanyName());
        assertEquals("需要无障碍座位", result.getPeople().get(0).getSensitiveNotes());
        assertEquals(3, result.getPeople().get(0).getAppointmentPartySize());
    }

    @Test
    void hidesProfileDetailsWithoutReceptionConsent() {
        Fixture fixture = new Fixture();
        ReceptionCustomerProfileEntity customer = new ReceptionCustomerProfileEntity();
        customer.setCustomerStage("RETURNING");
        customer.setCompanyName("不应下发");
        customer.setSensitiveNotes("不应下发");
        customer.setConsentScope("MEMORY");
        when(fixture.customerDao.selectOne(any())).thenReturn(customer);
        when(fixture.appointmentDao.selectOne(any())).thenReturn(null);
        when(fixture.configDao.selectOne(any())).thenReturn(null);

        var person = fixture.service.resolve(fixture.request()).getPeople().get(0);

        assertEquals("RETURNING", person.getCustomerStage());
        assertNull(person.getCompanyName());
        assertNull(person.getSensitiveNotes());
    }

    private static final class Fixture {
        final DeviceDao deviceDao = mock(DeviceDao.class);
        final PersonProfileDao personDao = mock(PersonProfileDao.class);
        final PersonDeviceBindingDao bindingDao = mock(PersonDeviceBindingDao.class);
        final ReceptionCustomerProfileDao customerDao = mock(ReceptionCustomerProfileDao.class);
        final ReceptionAppointmentDao appointmentDao = mock(ReceptionAppointmentDao.class);
        final ReceptionWorkspaceConfigDao configDao = mock(ReceptionWorkspaceConfigDao.class);
        final ReceptionContextServiceImpl service = new ReceptionContextServiceImpl(
                deviceDao, personDao, bindingDao, customerDao, appointmentDao, configDao);

        Fixture() {
            DeviceEntity device = new DeviceEntity();
            device.setId("device-a");
            device.setWorkspaceId("workspace-a");
            device.setAgentId("agent-a");
            when(deviceDao.selectOne(any())).thenReturn(device);
            PersonProfileEntity person = new PersonProfileEntity();
            person.setId("person-a");
            person.setWorkspaceId("workspace-a");
            person.setName("王先生");
            person.setGender("MALE");
            person.setBirthday(Date.from(
                    LocalDate.now(ZoneId.of("Asia/Shanghai"))
                            .minusYears(65)
                            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                            .toInstant()));
            person.setStatus(1);
            when(personDao.selectById("person-a")).thenReturn(person);
            when(bindingDao.selectCount(any())).thenReturn(1L);
        }

        ReceptionContextRequestDTO request() {
            ReceptionContextRequestDTO request = new ReceptionContextRequestDTO();
            request.setMacAddress("AA:BB");
            request.setPersonIds(List.of("person-a"));
            return request;
        }
    }
}
