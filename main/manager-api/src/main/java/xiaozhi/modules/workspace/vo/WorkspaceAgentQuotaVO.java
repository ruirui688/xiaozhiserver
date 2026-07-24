package xiaozhi.modules.workspace.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "当前工作空间智能体配额")
public class WorkspaceAgentQuotaVO {
    private String workspaceId;
    private long agentCount;
    private int agentLimit;
    private long agentStorageLimitBytes;
}
