package xiaozhi.modules.agent.skill.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SemanticSkillTriggerRuleDTO {
    @NotBlank
    private String type;

    @NotEmpty
    @Size(max = 8)
    private List<@NotBlank @Size(min = 2, max = 100) String> terms = new ArrayList<>();
}
