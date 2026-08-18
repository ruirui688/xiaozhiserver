package xiaozhi.modules.reception.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.reception.dto.ReceptionAppointmentSaveDTO;
import xiaozhi.modules.reception.dto.ReceptionCustomerProfileSaveDTO;
import xiaozhi.modules.reception.dto.ReceptionWorkspaceConfigSaveDTO;
import xiaozhi.modules.reception.entity.ReceptionAppointmentEntity;
import xiaozhi.modules.reception.entity.ReceptionCustomerProfileEntity;
import xiaozhi.modules.reception.entity.ReceptionWorkspaceConfigEntity;
import xiaozhi.modules.reception.service.ReceptionManagementService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/resource/reception")
public class ReceptionManagementController {
    private final ReceptionManagementService receptionManagementService;

    @GetMapping("/persons/{personId}")
    @RequiresPermissions("sys:role:normal")
    public Result<ReceptionCustomerProfileEntity> profile(@PathVariable String personId) {
        return new Result<ReceptionCustomerProfileEntity>().ok(
                receptionManagementService.getProfile(personId));
    }

    @PutMapping("/persons/{personId}")
    @RequiresPermissions("sys:role:normal")
    public Result<ReceptionCustomerProfileEntity> saveProfile(
            @PathVariable String personId,
            @Valid @RequestBody ReceptionCustomerProfileSaveDTO dto) {
        return new Result<ReceptionCustomerProfileEntity>().ok(
                receptionManagementService.saveProfile(personId, dto));
    }

    @GetMapping("/persons/{personId}/appointments")
    @RequiresPermissions("sys:role:normal")
    public Result<List<ReceptionAppointmentEntity>> appointments(@PathVariable String personId) {
        return new Result<List<ReceptionAppointmentEntity>>().ok(
                receptionManagementService.listAppointments(personId));
    }

    @PostMapping("/persons/{personId}/appointments")
    @RequiresPermissions("sys:role:normal")
    public Result<ReceptionAppointmentEntity> createAppointment(
            @PathVariable String personId,
            @Valid @RequestBody ReceptionAppointmentSaveDTO dto) {
        return new Result<ReceptionAppointmentEntity>().ok(
                receptionManagementService.createAppointment(personId, dto));
    }

    @PutMapping("/appointments/{appointmentId}")
    @RequiresPermissions("sys:role:normal")
    public Result<ReceptionAppointmentEntity> updateAppointment(
            @PathVariable String appointmentId,
            @Valid @RequestBody ReceptionAppointmentSaveDTO dto) {
        return new Result<ReceptionAppointmentEntity>().ok(
                receptionManagementService.updateAppointment(appointmentId, dto));
    }

    @DeleteMapping("/appointments/{appointmentId}")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> deleteAppointment(@PathVariable String appointmentId) {
        receptionManagementService.deleteAppointment(appointmentId);
        return new Result<>();
    }

    @GetMapping("/workspace-config")
    @RequiresPermissions("sys:role:normal")
    public Result<ReceptionWorkspaceConfigEntity> workspaceConfig() {
        return new Result<ReceptionWorkspaceConfigEntity>().ok(
                receptionManagementService.getWorkspaceConfig());
    }

    @PutMapping("/workspace-config")
    @RequiresPermissions("sys:role:normal")
    public Result<ReceptionWorkspaceConfigEntity> saveWorkspaceConfig(
            @Valid @RequestBody ReceptionWorkspaceConfigSaveDTO dto) {
        return new Result<ReceptionWorkspaceConfigEntity>().ok(
                receptionManagementService.saveWorkspaceConfig(dto));
    }
}
