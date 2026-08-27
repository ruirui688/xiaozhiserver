package xiaozhi.modules.maintenance.dto;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class HarnessMaintenanceRequest {
    private String runId;
    private String conversationId;
    private String taskType;
    private String resourceId;
    private Integer maxProposals;
    private String customPrompt;
    private String userInstruction;
    private List<HarnessConversationMessage> conversationHistory;
    private Map<String, Object> input;
    private HarnessModelConfig modelConfig;
}
