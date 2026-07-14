package xiaozhi.modules.agent.dto;

import lombok.Data;

@Data
public class AgentVoicePrintEnrollmentSampleDTO {
    private String audioId;

    private String audioBase64;

    private String macAddress;

    private String sessionId;

    private Integer durationMs;
}
