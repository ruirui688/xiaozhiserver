package xiaozhi.modules.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AgentFeishuConfigSaveDTO {
    @NotBlank
    @Pattern(regexp = "^cli_[A-Za-z0-9]+$", message = "飞书App ID格式无效")
    @Size(max = 128)
    private String appId;

    @Size(max = 512)
    private String appSecret;

    @Size(max = 128)
    private String defaultChatId;

    @Size(max = 255)
    private String calendarId;

    @Size(max = 128)
    private String taskAssigneeOpenId;

    private Boolean enabled = false;
    private Boolean meetingReportEnabled = false;
    private Boolean messageSendEnabled = false;
    private Boolean messageReadEnabled = false;
    private Boolean taskReadEnabled = false;
    private Boolean taskCreateEnabled = false;
    private Boolean calendarCreateEnabled = false;
}
