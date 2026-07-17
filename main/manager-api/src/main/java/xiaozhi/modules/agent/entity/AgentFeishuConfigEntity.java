package xiaozhi.modules.agent.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_agent_feishu_config")
public class AgentFeishuConfigEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String agentId;
    private String appId;
    private String appSecretCiphertext;
    private String defaultChatId;
    private String calendarId;
    private String taskAssigneeOpenId;
    private Boolean enabled;
    private Boolean meetingReportEnabled;
    private Boolean messageSendEnabled;
    private Boolean messageReadEnabled;
    private Boolean taskReadEnabled;
    private Boolean taskCreateEnabled;
    private Boolean calendarCreateEnabled;
    private Long creator;
    private Date createdAt;
    private Long updater;
    private Date updatedAt;
}
