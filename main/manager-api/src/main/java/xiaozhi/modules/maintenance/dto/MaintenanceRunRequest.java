package xiaozhi.modules.maintenance.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** Optional operator message for one manual maintenance turn. */
@Data
public class MaintenanceRunRequest {
    @Size(max = 2000)
    private String instruction;
}
