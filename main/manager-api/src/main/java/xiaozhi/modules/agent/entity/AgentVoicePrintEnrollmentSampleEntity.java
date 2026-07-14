package xiaozhi.modules.agent.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@TableName(value = "ai_agent_voice_print_enrollment_sample")
@Data
public class AgentVoicePrintEnrollmentSampleEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String enrollmentId;
    private String agentId;
    private String audioId;

    @TableField(fill = FieldFill.INSERT)
    private Date createDate;
}
