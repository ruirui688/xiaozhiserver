package xiaozhi.modules.agent.dto;

import lombok.Data;

@Data
public class AgentStorageUsageDTO {
    private String agentId;
    private Long storageBytes;
}
