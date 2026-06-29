package xiaozhi.modules.memory.dto;

import lombok.Data;

@Data
public class MemoryForgetDTO {
    private String macAddress;
    private String agentId;
    private String text;
    private String reason;
}
