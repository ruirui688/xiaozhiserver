package xiaozhi.modules.reception.service.impl;

import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.person.dao.PersonDeviceBindingDao;
import xiaozhi.modules.person.dao.PersonProfileDao;
import xiaozhi.modules.person.entity.PersonDeviceBindingEntity;
import xiaozhi.modules.person.entity.PersonProfileEntity;
import xiaozhi.modules.reception.dao.ReceptionAppointmentDao;
import xiaozhi.modules.reception.dao.ReceptionCustomerProfileDao;
import xiaozhi.modules.reception.dao.ReceptionVisitEventDao;
import xiaozhi.modules.reception.dto.ReceptionVisitEventDTO;
import xiaozhi.modules.reception.entity.ReceptionAppointmentEntity;
import xiaozhi.modules.reception.entity.ReceptionCustomerProfileEntity;
import xiaozhi.modules.reception.entity.ReceptionVisitEventEntity;
import xiaozhi.modules.reception.service.ReceptionEventService;

@Service
@RequiredArgsConstructor
public class ReceptionEventServiceImpl implements ReceptionEventService {
    private static final long LONG_TERM_SPAN_MS = 30L * 24L * 60L * 60L * 1000L;

    private final DeviceDao deviceDao;
    private final PersonProfileDao personDao;
    private final PersonDeviceBindingDao bindingDao;
    private final ReceptionCustomerProfileDao customerDao;
    private final ReceptionAppointmentDao appointmentDao;
    private final ReceptionVisitEventDao eventDao;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = DuplicateKeyException.class)
    public boolean recordVisit(ReceptionVisitEventDTO dto) {
        DeviceEntity device = requireDevice(dto.getMacAddress());
        ReceptionVisitEventEntity existing = eventDao.selectOne(
                new LambdaQueryWrapper<ReceptionVisitEventEntity>()
                        .eq(ReceptionVisitEventEntity::getDeviceId, device.getId())
                        .eq(ReceptionVisitEventEntity::getDeviceEventId, dto.getEventId())
                        .last("LIMIT 1"));
        if (existing != null) {
            return false;
        }
        Date now = new Date();
        for (String personId : dto.getSelectedPersonIds()) {
            requireBoundPerson(device, personId);
        }
        ReceptionVisitEventEntity event = new ReceptionVisitEventEntity();
        event.setWorkspaceId(device.getWorkspaceId());
        event.setDeviceId(device.getId());
        event.setDeviceEventId(dto.getEventId());
        event.setGroupSessionId(dto.getGroupSessionId());
        event.setSceneEpoch(Objects.requireNonNullElse(dto.getSceneEpoch(), 0L));
        event.setEventType("GROUP_IDENTIFIED");
        event.setSelectedPersonIdsJson(json(dto.getSelectedPersonIds()));
        event.setPresentCount(Objects.requireNonNullElse(dto.getPresentCount(), 1));
        event.setDecisionReason(dto.getPresentCount() != null && dto.getPresentCount() > 3
                ? "GROUP_OVERFLOW_REPRESENTATIVE" : "GROUP_WITHIN_FULL_LIMIT");
        event.setCapturedAt(dto.getCapturedAt());
        event.setDecisionAt(now);
        try {
            eventDao.insert(event);
        } catch (DuplicateKeyException error) {
            return false;
        }
        for (String personId : dto.getSelectedPersonIds()) {
            updateCustomerVisit(device.getWorkspaceId(), personId, now);
            markAppointmentArrived(device.getWorkspaceId(), personId, now);
        }
        return true;
    }

    private void updateCustomerVisit(String workspaceId, String personId, Date now) {
        ReceptionCustomerProfileEntity customer = customerDao.selectOne(
                new LambdaQueryWrapper<ReceptionCustomerProfileEntity>()
                        .eq(ReceptionCustomerProfileEntity::getWorkspaceId, workspaceId)
                        .eq(ReceptionCustomerProfileEntity::getPersonId, personId)
                        .last("LIMIT 1"));
        if (customer == null) {
            customer = new ReceptionCustomerProfileEntity();
            customer.setWorkspaceId(workspaceId);
            customer.setPersonId(personId);
            customer.setCustomerStage("NEW");
            customer.setConsentScope("");
            customer.setVisitCount(1);
            customer.setFirstVisitAt(now);
            customer.setLastVisitAt(now);
            customer.setStatus(1);
            customerDao.insert(customer);
            return;
        }
        int visits = Objects.requireNonNullElse(customer.getVisitCount(), 0) + 1;
        Date first = customer.getFirstVisitAt() == null ? now : customer.getFirstVisitAt();
        customer.setVisitCount(visits);
        customer.setFirstVisitAt(first);
        customer.setLastVisitAt(now);
        if (!"VIP".equals(customer.getCustomerStage())) {
            customer.setCustomerStage(
                    visits >= 5 && now.getTime() - first.getTime() >= LONG_TERM_SPAN_MS
                            ? "LONG_TERM" : "RETURNING");
        }
        customerDao.updateById(customer);
    }

    private void markAppointmentArrived(String workspaceId, String personId, Date now) {
        List<ReceptionAppointmentEntity> appointments = appointmentDao.selectList(
                new LambdaQueryWrapper<ReceptionAppointmentEntity>()
                        .eq(ReceptionAppointmentEntity::getWorkspaceId, workspaceId)
                        .eq(ReceptionAppointmentEntity::getPersonId, personId)
                        .eq(ReceptionAppointmentEntity::getStatus, "CONFIRMED")
                        .le(ReceptionAppointmentEntity::getStartAt, now)
                        .ge(ReceptionAppointmentEntity::getEndAt, now));
        for (ReceptionAppointmentEntity appointment : appointments) {
            appointment.setStatus("ARRIVED");
            appointmentDao.updateById(appointment);
        }
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

    private void requireBoundPerson(DeviceEntity device, String personId) {
        PersonProfileEntity person = personDao.selectById(personId);
        if (person == null || !Objects.equals(person.getWorkspaceId(), device.getWorkspaceId())) {
            throw new RenException("人物档案不可用");
        }
        long count = bindingDao.selectCount(new LambdaQueryWrapper<PersonDeviceBindingEntity>()
                .eq(PersonDeviceBindingEntity::getDeviceId, device.getId())
                .eq(PersonDeviceBindingEntity::getPersonId, personId)
                .eq(PersonDeviceBindingEntity::getWorkspaceId, device.getWorkspaceId()));
        if (count != 1L) {
            throw new RenException("该设备未绑定此人物档案");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new RenException("接待事件格式无效");
        }
    }
}
