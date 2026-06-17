package xiaozhi.modules.device.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "设备对话调试连接信息")
public class DeviceDebugInfoDTO {

    @Schema(description = "设备ID")
    private String deviceId;

    @Schema(description = "设备MAC地址")
    private String macAddress;

    @Schema(description = "客户端ID")
    private String clientId;

    @Schema(description = "WebSocket地址")
    private String websocketUrl;

    @Schema(description = "WebSocket认证Token")
    private String token;
}
