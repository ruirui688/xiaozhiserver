package xiaozhi.modules.reception.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.person.dao.PersonAgentBindingDao;
import xiaozhi.modules.person.dao.PersonProfileDao;
import xiaozhi.modules.person.entity.PersonAgentBindingEntity;
import xiaozhi.modules.person.entity.PersonProfileEntity;
import xiaozhi.modules.reception.dao.ReceptionAppointmentDao;
import xiaozhi.modules.reception.dao.ReceptionCustomerProfileDao;
import xiaozhi.modules.reception.dao.ReceptionWorkspaceConfigDao;
import xiaozhi.modules.reception.dto.ReceptionContextRequestDTO;
import xiaozhi.modules.reception.entity.ReceptionAppointmentEntity;
import xiaozhi.modules.reception.entity.ReceptionCustomerProfileEntity;
import xiaozhi.modules.reception.entity.ReceptionWorkspaceConfigEntity;
import xiaozhi.modules.reception.service.ReceptionContextService;
import xiaozhi.modules.reception.vo.ReceptionContextVO;

@Service
@RequiredArgsConstructor
public class ReceptionContextServiceImpl implements ReceptionContextService {
    private final DeviceDao deviceDao;
    private final PersonProfileDao personDao;
    private final PersonAgentBindingDao bindingDao;
    private final ReceptionCustomerProfileDao customerDao;
    private final ReceptionAppointmentDao appointmentDao;
    private final ReceptionWorkspaceConfigDao workspaceConfigDao;

    @Override
    public ReceptionContextVO resolve(ReceptionContextRequestDTO request) {
        DeviceEntity device = requireDevice(request.getMacAddress());
        Set<String> personIds = new LinkedHashSet<>(request.getPersonIds());
        Date now = new Date();
        List<ReceptionContextVO.PersonContext> people = new ArrayList<>();
        for (String personId : personIds) {
            people.add(resolvePerson(device, personId, now));
        }
        ReceptionWorkspaceConfigEntity config = workspaceConfigDao.selectOne(
                new LambdaQueryWrapper<ReceptionWorkspaceConfigEntity>()
                        .eq(ReceptionWorkspaceConfigEntity::getWorkspaceId, device.getWorkspaceId())
                        .eq(ReceptionWorkspaceConfigEntity::getStatus, 1)
                        .last("LIMIT 1"));
        return ReceptionContextVO.builder()
                .deviceId(device.getId())
                .workspaceId(device.getWorkspaceId())
                .generatedAt(now)
                .companyName(config == null ? null : config.getCompanyName())
                .companyIntroText(config == null ? null : config.getCompanyIntroText())
                .productsJson(config == null ? null : config.getProductsJson())
                .faqJson(config == null ? null : config.getFaqJson())
                .seatsJson(config == null ? null : config.getSeatsJson())
                .stageShowJson(config == null ? null : config.getStageShowJson())
                .orderQrContent(config == null ? null : config.getOrderQrContent())
                .serviceContactText(config == null ? null : config.getServiceContactText())
                .configVersion(config == null || config.getConfigVersion() == null ? 0L : config.getConfigVersion())
                .people(people)
                .build();
    }

    private ReceptionContextVO.PersonContext resolvePerson(
            DeviceEntity device, String personId, Date now) {
        PersonProfileEntity person = personDao.selectById(personId);
        if (person == null || !Objects.equals(person.getWorkspaceId(), device.getWorkspaceId())
                || !Objects.equals(person.getStatus(), 1)) {
            throw new RenException("人物档案不可用");
        }
        long bound = bindingDao.selectCount(new LambdaQueryWrapper<PersonAgentBindingEntity>()
                .eq(PersonAgentBindingEntity::getAgentId, device.getAgentId())
                .eq(PersonAgentBindingEntity::getPersonId, personId)
                .eq(PersonAgentBindingEntity::getPublishedActive, 1)
                .eq(PersonAgentBindingEntity::getWorkspaceId, device.getWorkspaceId()));
        if (bound != 1L) {
            throw new RenException("当前智能体未发布此人物档案");
        }
        ReceptionCustomerProfileEntity customer = customerDao.selectOne(
                new LambdaQueryWrapper<ReceptionCustomerProfileEntity>()
                        .eq(ReceptionCustomerProfileEntity::getPersonId, personId)
                        .eq(ReceptionCustomerProfileEntity::getWorkspaceId, device.getWorkspaceId())
                        .eq(ReceptionCustomerProfileEntity::getStatus, 1)
                        .last("LIMIT 1"));
        ReceptionAppointmentEntity appointment = appointmentDao.selectOne(
                new LambdaQueryWrapper<ReceptionAppointmentEntity>()
                        .eq(ReceptionAppointmentEntity::getPersonId, personId)
                        .eq(ReceptionAppointmentEntity::getWorkspaceId, device.getWorkspaceId())
                        .eq(ReceptionAppointmentEntity::getStatus, "CONFIRMED")
                        .le(ReceptionAppointmentEntity::getStartAt, now)
                        .ge(ReceptionAppointmentEntity::getEndAt, now)
                        .orderByAsc(ReceptionAppointmentEntity::getStartAt)
                        .last("LIMIT 1"));
        boolean receptionAllowed = hasConsent(customer, "RECEPTION");
        boolean sensitiveAllowed = hasConsent(customer, "SENSITIVE");
        return ReceptionContextVO.PersonContext.builder()
                .personId(person.getId())
                .personName(person.getName())
                .gender(StringUtils.defaultIfBlank(person.getGender(), "UNKNOWN"))
                .ageGroup(ageGroup(person))
                .customerStage(customer == null ? "NEW" : customer.getCustomerStage())
                .companyName(receptionAllowed ? customer.getCompanyName() : null)
                .hobbiesJson(receptionAllowed ? customer.getHobbiesJson() : null)
                .foodPreferencesJson(receptionAllowed ? customer.getFoodPreferencesJson() : null)
                .seatPreferencesJson(receptionAllowed ? customer.getSeatPreferencesJson() : null)
                .receptionNotes(receptionAllowed ? customer.getReceptionNotes() : null)
                .sensitiveNotes(sensitiveAllowed ? customer.getSensitiveNotes() : null)
                .visitCount(customer == null || customer.getVisitCount() == null ? 0 : customer.getVisitCount())
                .appointment(appointment != null)
                .appointmentId(appointment == null ? null : appointment.getId())
                .appointmentPartySize(appointment == null || appointment.getPartySize() == null
                        ? 0 : appointment.getPartySize())
                .appointmentSeatRequest(appointment == null ? null : appointment.getSeatRequest())
                .build();
    }

    private DeviceEntity requireDevice(String macAddress) {
        DeviceEntity device = deviceDao.selectOne(new LambdaQueryWrapper<DeviceEntity>()
                .eq(DeviceEntity::getMacAddress, macAddress.trim())
                .last("LIMIT 1"));
        if (device == null || StringUtils.isBlank(device.getWorkspaceId())
                || StringUtils.isBlank(device.getAgentId())) {
            throw new RenException("设备不存在或尚未绑定智能体");
        }
        return device;
    }

    private boolean hasConsent(ReceptionCustomerProfileEntity customer, String scope) {
        if (customer == null || StringUtils.isBlank(customer.getConsentScope())) {
            return false;
        }
        for (String value : customer.getConsentScope().toUpperCase().split(",")) {
            if (scope.equals(value.trim())) {
                return true;
            }
        }
        return false;
    }

    private String ageGroup(PersonProfileEntity person) {
        if (person.getBirthday() == null) {
            return "UNKNOWN";
        }
        LocalDate birthday = person.getBirthday() instanceof java.sql.Date sqlDate
                ? sqlDate.toLocalDate()
                : person.getBirthday().toInstant()
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .toLocalDate();
        int age = Period.between(
                birthday,
                LocalDate.now(ZoneId.of("Asia/Shanghai"))).getYears();
        if (age < 0) {
            return "UNKNOWN";
        }
        if (age < 18) {
            return "CHILD";
        }
        return age >= 60 ? "SENIOR" : "ADULT";
    }
}
