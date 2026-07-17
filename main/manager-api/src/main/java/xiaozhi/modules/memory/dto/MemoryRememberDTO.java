package xiaozhi.modules.memory.dto;

import lombok.Data;

@Data
public class MemoryRememberDTO {
    private String macAddress;
    private String agentId;
    private String text;
    private String category;
    private String speakerId;
    private String speakerName;
    private Boolean shared;
}
