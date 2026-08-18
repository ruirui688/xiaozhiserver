package xiaozhi.modules.config.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DeviceSpeechInputRouteDTO {
    @NotBlank
    @Size(max = 128)
    private String macAddress;

    @NotBlank
    @Pattern(regexp = "server|device|auto", message = "语音输入路由仅支持server、device或auto")
    private String inputPolicy;
}
