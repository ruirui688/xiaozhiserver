package xiaozhi.modules.memory.vo;

import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import xiaozhi.modules.memory.entity.MemoryCandidateEntity;

@Data
@Schema(description = "长期记忆清理或重建结果")
public class MemoryRebuildResultVO {
    private int sessionLimit;
    private int sessionsRead;
    private int factsCleared;
    private int factsAdded;
    private int factsUpdated;
    private int factsArchived;
    private int factsDeleted;
    private int candidatesCleared;
    private int tombstonesCleared;
    private int tombstonesCreated;
    private int skippedLowQuality;
    private int actionsProposed;
    private int factsChanged;
    private int candidatesCreated;
    private boolean dryRun;
    private String mode;
    private List<String> sessionIds = new ArrayList<>();
    private List<MemoryCandidateEntity> candidates = new ArrayList<>();
}
