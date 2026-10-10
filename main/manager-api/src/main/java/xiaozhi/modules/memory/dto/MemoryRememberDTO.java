package xiaozhi.modules.memory.dto;

import lombok.Data;

@Data
public class MemoryRememberDTO {
    @com.fasterxml.jackson.annotation.JsonAlias("deviceId")
    private String macAddress;
    private String agentId;
    private String text;
    private String category;
    private String speakerId;
    private String speakerName;
    private Boolean shared;
}
