package xiaozhi.modules.reminder.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReminderRuntimeActionDTO {
    @NotBlank
    @Size(max = 32)
    private String action;

    @NotBlank
    @Size(max = 64)
    @JsonAlias("macAddress")
    private String deviceId;

    @Size(max = 128)
    private String requestId;

    @Size(max = 32)
    private String reminderId;

    @Size(max = 200)
    private String title;

    @Size(max = 500)
    private String spokenText;

    @Size(max = 64)
    private String remindAt;

    @Size(max = 64)
    private String timezone;
}
