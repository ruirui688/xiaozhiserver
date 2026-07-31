package xiaozhi.modules.device.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "设备真实音频预览短期票据")
public class DeviceAudioPreviewTicketDTO {
    @Schema(description = "真实音频预览WebSocket地址")
    private String websocketUrl;

    @Schema(description = "设备连接标识")
    private String deviceId;

    @Schema(description = "预览连接随机客户端标识")
    private String clientId;

    @Schema(description = "短期HMAC认证票据")
    private String token;

    @Schema(description = "票据有效期（秒）")
    private int expiresInSeconds;
}
