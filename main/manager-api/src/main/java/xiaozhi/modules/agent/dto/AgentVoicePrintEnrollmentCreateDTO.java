package xiaozhi.modules.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AgentVoicePrintEnrollmentCreateDTO {
    @NotBlank
    private String agentId;

    @NotBlank
    @Size(max = 50)
    private String sourceName;

    @Size(max = 200)
    private String introduce;

    @NotBlank
    @Size(max = 64)
    private String deviceId;

    private Integer requiredSamples;
}
