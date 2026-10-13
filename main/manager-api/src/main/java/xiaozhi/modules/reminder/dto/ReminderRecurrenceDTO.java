package xiaozhi.modules.reminder.dto;

import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Restricted calendar rule; deliberately not an arbitrary cron expression. */
@Data
public class ReminderRecurrenceDTO {
    private String frequency = "ONCE";
    @Min(1) @Max(365)
    private Integer interval = 1;
    @Size(max = 7)
    private List<@Min(1) @Max(7) Integer> weekdays;
    @Size(max = 64)
    private String until;
}
