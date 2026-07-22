package xiaozhi.modules.memory.vo;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "长期记忆重建任务状态")
public class MemoryRebuildTaskVO {
    private String taskId;
    private String agentId;
    private Long ownerUserId;
    private String workspaceId;
    private String actorId;
    private String status;
    private int progress;
    private String stage;
    private String message;
    private String errorMessage;
    private int sessionLimit;
    private String mode;
    private Date createdAt;
    private Date startedAt;
    private Date updatedAt;
    private Date finishedAt;
    private Date deadlineAt;
    private Long durationMs;
    private MemoryRebuildResultVO result;

    @JsonIgnore
    public boolean isTerminal() {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "TIMED_OUT".equals(status);
    }
}
