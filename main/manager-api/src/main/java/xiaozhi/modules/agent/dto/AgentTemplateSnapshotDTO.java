package xiaozhi.modules.agent.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import xiaozhi.modules.agent.skill.dto.AgentSemanticSkillSaveDTO;

@Data
public class AgentTemplateSnapshotDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer schemaVersion = 2;
    private AgentUpdateDTO config;
    private List<String> tagNames = new ArrayList<>();
    private List<AgentSemanticSkillSaveDTO> semanticSkills = new ArrayList<>();
    private List<String> voicePrintIds = new ArrayList<>();
    private AgentFeishuTemplateDTO feishuConfig;
}
