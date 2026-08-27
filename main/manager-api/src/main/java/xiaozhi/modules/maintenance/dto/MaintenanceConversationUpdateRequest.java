package xiaozhi.modules.maintenance.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MaintenanceConversationUpdateRequest {
    @Size(max = 100)
    private String title;
    private Boolean archived;
}
