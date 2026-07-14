package xiaozhi.modules.agent.dto;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AgentVoicePrintRegistrationResult {
    private String voicePrintId;
    private Map<String, Object> registrationResult;
}
