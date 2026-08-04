package xiaozhi.modules.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DeviceSceneSwitchResultDTO {
    @NotBlank
    private String deviceId;
    @NotBlank
    private String switchRequestId;
    @NotBlank
    private String agentId;
    @NotNull
    private Long configVersion;
    @NotBlank
    private String status;
    private String failureCode;
}
