package xiaozhi.modules.person.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ServerFaceRecognitionRequestDTO {
    @NotBlank(message = "设备ID不能为空")
    @Size(max = 32, message = "设备ID格式不正确")
    private String deviceId;
}
