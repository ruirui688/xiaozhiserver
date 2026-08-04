package xiaozhi.modules.agent.dto;

import java.io.Serializable;

import lombok.Data;
import lombok.ToString;

/**
 * 角色模板中的飞书配置。密钥字段只能保存以模板 ID 为 AAD 重新加密后的密文。
 */
@Data
public class AgentFeishuTemplateDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String appId;
    @ToString.Exclude
    private String appSecretCiphertext;
    private String defaultChatId;
    private String calendarId;
    private String taskAssigneeOpenId;
    private Boolean enabled;
    private Boolean meetingReportEnabled;
    private Boolean messageSendEnabled;
    private Boolean messageReadEnabled;
    private Boolean messageBroadcastEnabled;
    @ToString.Exclude
    private String eventVerificationTokenCiphertext;
    private Boolean taskReadEnabled;
    private Boolean taskCreateEnabled;
    private Boolean calendarCreateEnabled;
}
