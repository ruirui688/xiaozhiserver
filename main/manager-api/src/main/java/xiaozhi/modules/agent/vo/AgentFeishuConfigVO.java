package xiaozhi.modules.agent.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentFeishuConfigVO {
    private String appId;
    private boolean secretConfigured;
    private String defaultChatId;
    private String calendarId;
    private String taskAssigneeOpenId;
    private boolean enabled;
    private boolean meetingReportEnabled;
    private boolean messageSendEnabled;
    private boolean messageReadEnabled;
    private boolean messageBroadcastEnabled;
    private boolean eventVerificationTokenConfigured;
    private boolean taskReadEnabled;
    private boolean taskCreateEnabled;
    private boolean calendarCreateEnabled;
}
