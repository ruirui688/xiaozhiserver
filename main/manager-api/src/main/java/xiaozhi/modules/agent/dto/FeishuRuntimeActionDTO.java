package xiaozhi.modules.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class FeishuRuntimeActionDTO {
    @NotBlank
    @Size(max = 64)
    private String macAddress;

    @NotBlank
    @Size(max = 32)
    private String action;

    @Size(max = 30000)
    private String content;

    @Size(max = 200)
    private String title;

    @Size(max = 4000)
    private String description;

    private Long dueTimestamp;
    private Long startTimestamp;
    private Long endTimestamp;

    @Size(max = 64)
    private String timezone;
}
