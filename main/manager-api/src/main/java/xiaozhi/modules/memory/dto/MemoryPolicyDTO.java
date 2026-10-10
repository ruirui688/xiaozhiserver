package xiaozhi.modules.memory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** Per-agent storage and injection policy. Harness policy is deliberately separate. */
@Data
public class MemoryPolicyDTO {
    @NotNull @Pattern(regexp = "AGENT|DEVICE")
    private String memoryScope = "AGENT";
    @NotNull @Pattern(regexp = "SHARED|IGNORE")
    private String unknownSpeakerPolicy = "SHARED";
    @NotNull @Pattern(regexp = "AUTO|REVIEW|OFF")
    private String maintenanceMode = "AUTO";
    @NotNull @Min(100) @Max(20000)
    private Integer activeMaxChars = 1000;
    @NotNull @Min(200) @Max(4000)
    private Integer contextMaxChars = 4000;
    @NotNull @Min(1) @Max(30)
    private Integer contextMaxItems = 30;
    @NotNull @Min(0) @Max(36500)
    private Integer retentionDays = 0;
}
