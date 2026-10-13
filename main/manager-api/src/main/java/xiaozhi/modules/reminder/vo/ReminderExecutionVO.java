package xiaozhi.modules.reminder.vo;

import java.util.Date;
import lombok.Data;

@Data
public class ReminderExecutionVO {
    private Date scheduledAt;
    private Integer scheduleVersion;
    private String status;
    private Integer attemptCount;
    private String lastErrorCode;
    private Date finishedAt;
}
