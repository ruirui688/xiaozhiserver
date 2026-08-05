package xiaozhi.modules.device.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "在线设备短期上下文清空结果")
public record DeviceConversationContextResetDTO(
        boolean online,
        boolean reset,
        String mode,
        int removedMessages,
        boolean upstreamResetScheduled,
        boolean upstreamResetCompleted) {
}
