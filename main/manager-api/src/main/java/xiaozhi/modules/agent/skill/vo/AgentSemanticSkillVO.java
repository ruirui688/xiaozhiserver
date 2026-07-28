package xiaozhi.modules.agent.skill.vo;

import java.util.List;

import lombok.Data;
import xiaozhi.modules.agent.skill.dto.SemanticSkillTriggerRuleDTO;

@Data
public class AgentSemanticSkillVO {
    private String id;
    private String skillName;
    private Boolean enabled;
    private List<SemanticSkillTriggerRuleDTO> triggerRules;
    private String responseText;
    private Integer priority;
    private Integer sort;
}
