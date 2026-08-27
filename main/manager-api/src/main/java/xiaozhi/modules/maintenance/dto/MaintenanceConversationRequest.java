package xiaozhi.modules.maintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MaintenanceConversationRequest {
    @NotBlank
    @Size(max = 100)
    private String title;
}
