package xiaozhi.modules.reminder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReminderSaveDTO {
    @NotBlank
    @Size(max = 32)
    private String deviceId;

    @NotBlank
    @Size(max = 200)
    private String title;

    @NotBlank
    @Size(max = 64)
    private String remindAt;

    @NotBlank
    @Size(max = 64)
    private String timezone;
}
