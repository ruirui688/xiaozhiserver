package xiaozhi.modules.reception.service;

import java.util.List;

import xiaozhi.modules.reception.dto.ReceptionAppointmentSaveDTO;
import xiaozhi.modules.reception.dto.ReceptionCustomerProfileSaveDTO;
import xiaozhi.modules.reception.dto.ReceptionWorkspaceConfigSaveDTO;
import xiaozhi.modules.reception.entity.ReceptionAppointmentEntity;
import xiaozhi.modules.reception.entity.ReceptionCustomerProfileEntity;
import xiaozhi.modules.reception.entity.ReceptionWorkspaceConfigEntity;

public interface ReceptionManagementService {
    ReceptionCustomerProfileEntity getProfile(String personId);
    ReceptionCustomerProfileEntity saveProfile(String personId, ReceptionCustomerProfileSaveDTO dto);
    List<ReceptionAppointmentEntity> listAppointments(String personId);
    ReceptionAppointmentEntity createAppointment(String personId, ReceptionAppointmentSaveDTO dto);
    ReceptionAppointmentEntity updateAppointment(String appointmentId, ReceptionAppointmentSaveDTO dto);
    void deleteAppointment(String appointmentId);
    ReceptionWorkspaceConfigEntity getWorkspaceConfig();
    ReceptionWorkspaceConfigEntity saveWorkspaceConfig(ReceptionWorkspaceConfigSaveDTO dto);
}
