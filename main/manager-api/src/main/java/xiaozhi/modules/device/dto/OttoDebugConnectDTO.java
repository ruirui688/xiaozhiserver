package xiaozhi.modules.device.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OttoDebugConnectDTO {
    @NotBlank(message = "设备IP不能为空")
    private String host;
}
