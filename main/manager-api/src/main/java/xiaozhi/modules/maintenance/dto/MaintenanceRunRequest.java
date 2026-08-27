package xiaozhi.modules.maintenance.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** Operator message for one turn inside a maintenance conversation. */
@Data
public class MaintenanceRunRequest {
    @Size(max = 8000)
    private String instruction;
}
