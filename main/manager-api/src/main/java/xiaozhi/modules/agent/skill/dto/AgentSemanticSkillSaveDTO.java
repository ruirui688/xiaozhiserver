package xiaozhi.modules.agent.skill.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AgentSemanticSkillSaveDTO {
    @NotBlank
    @Size(max = 64)
    private String skillName;

    private Boolean enabled = true;

    @Valid
    @NotEmpty
    @Size(max = 20)
    private List<SemanticSkillTriggerRuleDTO> triggerRules = new ArrayList<>();

    @NotBlank
    @Size(max = 3000)
    private String responseText;

    @Min(0)
    @Max(1_000_000)
    private Integer priority = 0;

    @Min(0)
    @Max(1_000_000)
    private Integer sort = 0;
}
