package xiaozhi.modules.agent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AgentTemplateApplyDTO {
    @NotNull
    @Min(1)
    private Integer expectedVersion;
}
