package xiaozhi.modules.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AgentTemplateReviewDTO {
    @NotBlank
    @Pattern(regexp = "APPROVED|REJECTED", message = "审核结果仅支持 APPROVED 或 REJECTED")
    private String decision;

    @Size(max = 500)
    private String note;
}
