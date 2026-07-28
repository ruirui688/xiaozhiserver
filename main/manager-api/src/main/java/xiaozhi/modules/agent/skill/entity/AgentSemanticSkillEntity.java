package xiaozhi.modules.agent.skill.entity;

import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import lombok.Data;
import xiaozhi.modules.agent.skill.dto.SemanticSkillTriggerRuleDTO;

@Data
@TableName(value = "ai_agent_semantic_skill", autoResultMap = true)
public class AgentSemanticSkillEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String agentId;
    private String skillName;
    private Boolean enabled;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<SemanticSkillTriggerRuleDTO> triggerRules;

    private String responseText;
    private Integer priority;
    private Integer sort;
    private Long creator;
    private Date createdAt;
    private Long updater;
    private Date updatedAt;
}
