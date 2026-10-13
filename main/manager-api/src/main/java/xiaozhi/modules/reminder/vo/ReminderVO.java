package xiaozhi.modules.reminder.vo;

import java.util.Date;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ReminderVO {
    String id;
    String deviceId;
    String deviceName;
    String agentId;
    String title;
    String remindAt;
    String timezone;
    String status;
    String source;
    String deliveredAt;
    String lastErrorCode;
    Date createDate;
}
