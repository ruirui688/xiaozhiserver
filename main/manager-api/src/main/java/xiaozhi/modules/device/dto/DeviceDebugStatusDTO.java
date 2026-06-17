package xiaozhi.modules.device.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "设备对话调试状态")
public class DeviceDebugStatusDTO {

    @Schema(description = "设备ID")
    private String deviceId;

    @Schema(description = "运行时客户端ID")
    private String clientId;

    @Schema(description = "真实设备是否连接到运行时")
    private Boolean runtimeConnected;

    @Schema(description = "调试WebSocket是否连接")
    private Boolean debugConnected;

    @Schema(description = "设备MCP是否准备就绪")
    private Boolean mcpReady;

    @Schema(description = "状态: offline, online_no_mcp, mcp_initializing, mcp_ready, runtime_unavailable")
    private String status;

    @Schema(description = "状态说明")
    private String message;

    @Schema(description = "工具数量")
    private Integer toolsCount;

    @Schema(description = "状态来源")
    private String source;
}
