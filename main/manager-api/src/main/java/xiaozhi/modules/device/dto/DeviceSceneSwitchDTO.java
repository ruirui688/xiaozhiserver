package xiaozhi.modules.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DeviceSceneSwitchDTO {
    @NotBlank(message = "目标场景不能为空")
    private String bindingId;
    @Size(max = 64, message = "切换请求ID不能超过64个字符")
    private String switchRequestId;
}
