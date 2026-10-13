package xiaozhi.modules.reminder.vo;

import java.util.Date;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ReminderDeviceVO {
    String deviceId;
    String deviceName;
    String agentId;
    String agentName;
    Date lastConnectedAt;
}
