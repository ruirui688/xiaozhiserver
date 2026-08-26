package xiaozhi.modules.maintenance.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.AllArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.maintenance.dto.HarnessMaintenanceResponse;
import xiaozhi.modules.maintenance.entity.MaintenanceRunEntity;
import xiaozhi.modules.maintenance.service.MaintenanceService;
import jakarta.validation.Valid;

/** Server-secret protected callback used only by the isolated Harness tool. */
@Hidden
@RestController
@AllArgsConstructor
@RequestMapping("/config/maintenance/runs")
public class MaintenanceInternalController {
    private final MaintenanceService maintenanceService;

    @PostMapping("/{run_id}/complete")
    public Result<MaintenanceRunEntity> complete(@PathVariable("run_id") String runId,
            @Valid @RequestBody HarnessMaintenanceResponse response) {
        return new Result<MaintenanceRunEntity>().ok(maintenanceService.completeConsole(runId, response));
    }
}
