package xiaozhi.modules.memory.dto;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "服务端记忆事件上报")
public class MemoryEventDTO {
    private String eventId;
    private String macAddress;
    private String agentId;
    private String sessionId;
    private String speakerId;
    private String eventType;
    private Integer chatType;
    private String contentText;
    private Map<String, Object> contentJson;
    private Long sourceTimestamp;
}
