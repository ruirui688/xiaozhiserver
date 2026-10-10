package xiaozhi.modules.memory.dto;

import lombok.Data;

@Data
public class MemoryForgetDTO {
    @com.fasterxml.jackson.annotation.JsonAlias("deviceId")
    private String macAddress;
    private String agentId;
    private String text;
    private String patternType;
    private String reason;
    private String speakerId;
    private String speakerName;
    private Boolean shared;
}
