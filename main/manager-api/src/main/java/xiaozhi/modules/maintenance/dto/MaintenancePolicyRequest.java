package xiaozhi.modules.maintenance.dto;

import java.util.ArrayList;
import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MaintenancePolicyRequest {
    @NotBlank
    @Size(max = 32)
    private String mode;
    @Size(max = 20)
    private List<@Size(max = 120) String> topics = new ArrayList<>();
    @Size(max = 4000)
    private String customPrompt;
    @Size(max = 50)
    private List<@Size(max = 253) String> trustedDomains = new ArrayList<>();
    @Min(60) @Max(10080)
    private Integer scheduleMinutes = 360;
    @Min(1) @Max(20)
    private Integer maxProposals = 8;
    @Min(1) @Max(1000)
    private Integer maxMonthlyRuns = 60;
    @Size(max = 32)
    private String modelConfigId;
    private Boolean enabled = false;
}
