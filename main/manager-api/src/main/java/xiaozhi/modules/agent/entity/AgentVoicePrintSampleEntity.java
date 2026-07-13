package xiaozhi.modules.agent.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 智能体声纹注册样本表。
 */
@TableName(value = "ai_agent_voice_print_sample")
@Data
public class AgentVoicePrintSampleEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String voicePrintId;
    private String agentId;
    private String audioId;
    private Long durationMs;
    @TableField(fill = FieldFill.INSERT)
    private Date createDate;
}
