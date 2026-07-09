package xiaozhi.modules.device.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OttoDebugConfigDTO {
    @NotBlank(message = "配置项不能为空")
    private String key;

    private String value;
}
