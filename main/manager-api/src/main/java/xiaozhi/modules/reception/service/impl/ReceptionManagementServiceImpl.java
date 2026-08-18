package xiaozhi.modules.reception.service.impl;

import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.person.dao.PersonProfileDao;
import xiaozhi.modules.person.entity.PersonProfileEntity;
import xiaozhi.modules.reception.dao.ReceptionAppointmentDao;
import xiaozhi.modules.reception.dao.ReceptionCustomerProfileDao;
import xiaozhi.modules.reception.dao.ReceptionWorkspaceConfigDao;
import xiaozhi.modules.reception.dto.ReceptionAppointmentSaveDTO;
import xiaozhi.modules.reception.dto.ReceptionCustomerProfileSaveDTO;
import xiaozhi.modules.reception.dto.ReceptionWorkspaceConfigSaveDTO;
import xiaozhi.modules.reception.entity.ReceptionAppointmentEntity;
import xiaozhi.modules.reception.entity.ReceptionCustomerProfileEntity;
import xiaozhi.modules.reception.entity.ReceptionWorkspaceConfigEntity;
import xiaozhi.modules.reception.service.ReceptionManagementService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@Service
@RequiredArgsConstructor
public class ReceptionManagementServiceImpl implements ReceptionManagementService {
    private final PersonProfileDao personDao;
    private final ReceptionCustomerProfileDao customerDao;
    private final ReceptionAppointmentDao appointmentDao;
    private final ReceptionWorkspaceConfigDao workspaceConfigDao;
    private final WorkspacePermissionService workspacePermissionService;
    private final ObjectMapper objectMapper;

    @Override
    public ReceptionCustomerProfileEntity getProfile(String personId) {
        PersonProfileEntity person = requirePerson(personId, false);
        return customerDao.selectOne(new LambdaQueryWrapper<ReceptionCustomerProfileEntity>()
                .eq(ReceptionCustomerProfileEntity::getPersonId, person.getId())
                .eq(ReceptionCustomerProfileEntity::getWorkspaceId, person.getWorkspaceId())
                .last("LIMIT 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReceptionCustomerProfileEntity saveProfile(
            String personId, ReceptionCustomerProfileSaveDTO dto) {
        PersonProfileEntity person = requirePerson(personId, true);
        ReceptionCustomerProfileEntity entity = getProfile(personId);
        boolean create = entity == null;
        if (create) {
            entity = new ReceptionCustomerProfileEntity();
            entity.setWorkspaceId(person.getWorkspaceId());
            entity.setPersonId(person.getId());
            entity.setVisitCount(0);
        }
        entity.setCustomerStage(StringUtils.defaultIfBlank(dto.getCustomerStage(), "NEW"));
        entity.setCompanyName(StringUtils.trimToNull(dto.getCompanyName()));
        entity.setHobbiesJson(json(dto.getHobbies()));
        entity.setFoodPreferencesJson(json(dto.getFoodPreferences()));
        entity.setSeatPreferencesJson(json(dto.getSeatPreferences()));
        entity.setReceptionNotes(StringUtils.trimToNull(dto.getReceptionNotes()));
        entity.setSensitiveNotes(StringUtils.trimToNull(dto.getSensitiveNotes()));
        entity.setConsentScope(String.join(",", dto.getConsentScopes() == null ? List.of() : dto.getConsentScopes()));
        entity.setLastConfirmedAt(new Date());
        entity.setStatus(Objects.equals(dto.getStatus(), 0) ? 0 : 1);
        if (create) {
            customerDao.insert(entity);
        } else {
            customerDao.updateById(entity);
        }
        return entity;
    }

    @Override
    public List<ReceptionAppointmentEntity> listAppointments(String personId) {
        PersonProfileEntity person = requirePerson(personId, false);
        return appointmentDao.selectList(new LambdaQueryWrapper<ReceptionAppointmentEntity>()
                .eq(ReceptionAppointmentEntity::getPersonId, person.getId())
                .eq(ReceptionAppointmentEntity::getWorkspaceId, person.getWorkspaceId())
                .orderByDesc(ReceptionAppointmentEntity::getStartAt));
    }

    @Override
    public ReceptionAppointmentEntity createAppointment(
            String personId, ReceptionAppointmentSaveDTO dto) {
        PersonProfileEntity person = requirePerson(personId, true);
        ReceptionAppointmentEntity entity = new ReceptionAppointmentEntity();
        entity.setWorkspaceId(person.getWorkspaceId());
        entity.setPersonId(person.getId());
        applyAppointment(entity, dto);
        appointmentDao.insert(entity);
        return entity;
    }

    @Override
    public ReceptionAppointmentEntity updateAppointment(
            String appointmentId, ReceptionAppointmentSaveDTO dto) {
        ReceptionAppointmentEntity entity = requireAppointment(appointmentId, true);
        applyAppointment(entity, dto);
        appointmentDao.updateById(entity);
        return entity;
    }

    @Override
    public void deleteAppointment(String appointmentId) {
        ReceptionAppointmentEntity entity = requireAppointment(appointmentId, true);
        appointmentDao.deleteById(entity.getId());
    }

    @Override
    public ReceptionWorkspaceConfigEntity getWorkspaceConfig() {
        String workspaceId = workspacePermissionService.currentWorkspaceId();
        workspacePermissionService.requireRead(workspaceId);
        return workspaceConfigDao.selectOne(
                new LambdaQueryWrapper<ReceptionWorkspaceConfigEntity>()
                        .eq(ReceptionWorkspaceConfigEntity::getWorkspaceId, workspaceId)
                        .last("LIMIT 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReceptionWorkspaceConfigEntity saveWorkspaceConfig(
            ReceptionWorkspaceConfigSaveDTO dto) {
        String workspaceId = workspacePermissionService.currentWorkspaceId();
        workspacePermissionService.requireWrite(workspaceId);
        ReceptionWorkspaceConfigEntity entity = workspaceConfigDao.selectOne(
                new LambdaQueryWrapper<ReceptionWorkspaceConfigEntity>()
                        .eq(ReceptionWorkspaceConfigEntity::getWorkspaceId, workspaceId)
                        .last("LIMIT 1"));
        boolean create = entity == null;
        if (create) {
            entity = new ReceptionWorkspaceConfigEntity();
            entity.setWorkspaceId(workspaceId);
            entity.setConfigVersion(1L);
        } else {
            entity.setConfigVersion(Objects.requireNonNullElse(entity.getConfigVersion(), 0L) + 1L);
        }
        entity.setCompanyName(StringUtils.trimToNull(dto.getCompanyName()));
        entity.setCompanyIntroText(StringUtils.trimToNull(dto.getCompanyIntroText()));
        entity.setFaqJson(jsonObject(dto.getFaq()));
        entity.setProductsJson(jsonObject(dto.getProducts()));
        entity.setSeatsJson(jsonObject(dto.getSeats()));
        entity.setStageShowJson(jsonObject(dto.getStageShow()));
        entity.setOrderQrContent(StringUtils.trimToNull(dto.getOrderQrContent()));
        entity.setServiceContactText(StringUtils.trimToNull(dto.getServiceContactText()));
        entity.setStatus(Objects.equals(dto.getStatus(), 0) ? 0 : 1);
        if (create) {
            workspaceConfigDao.insert(entity);
        } else {
            workspaceConfigDao.updateById(entity);
        }
        return entity;
    }

    private PersonProfileEntity requirePerson(String personId, boolean write) {
        PersonProfileEntity person = personDao.selectById(personId);
        if (person == null) {
            throw new RenException("人物档案不存在");
        }
        if (write) {
            workspacePermissionService.requireWrite(person.getWorkspaceId());
        } else {
            workspacePermissionService.requireRead(person.getWorkspaceId());
        }
        return person;
    }

    private ReceptionAppointmentEntity requireAppointment(String id, boolean write) {
        ReceptionAppointmentEntity entity = appointmentDao.selectById(id);
        if (entity == null) {
            throw new RenException("预约不存在");
        }
        if (write) {
            workspacePermissionService.requireWrite(entity.getWorkspaceId());
        } else {
            workspacePermissionService.requireRead(entity.getWorkspaceId());
        }
        return entity;
    }

    private void applyAppointment(
            ReceptionAppointmentEntity entity, ReceptionAppointmentSaveDTO dto) {
        if (!dto.getEndAt().after(dto.getStartAt())) {
            throw new RenException("预约结束时间必须晚于开始时间");
        }
        entity.setStartAt(dto.getStartAt());
        entity.setEndAt(dto.getEndAt());
        entity.setPartySize(dto.getPartySize() == null ? 1 : dto.getPartySize());
        entity.setStatus(StringUtils.defaultIfBlank(dto.getStatus(), "CONFIRMED"));
        entity.setSeatRequest(StringUtils.trimToNull(dto.getSeatRequest()));
        entity.setNotes(StringUtils.trimToNull(dto.getNotes()));
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException error) {
            throw new RenException("接待档案格式无效");
        }
    }

    private String jsonObject(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException error) {
            throw new RenException("接待配置格式无效");
        }
    }
}
