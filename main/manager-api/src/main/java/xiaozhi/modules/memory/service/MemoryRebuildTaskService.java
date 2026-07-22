package xiaozhi.modules.memory.service;

import xiaozhi.modules.memory.dto.MemoryRebuildRequestDTO;
import xiaozhi.modules.memory.vo.MemoryRebuildTaskVO;

public interface MemoryRebuildTaskService {

    MemoryRebuildTaskVO startRebuild(String agentId, MemoryRebuildRequestDTO request,
                                     String actorType, String actorId);

    MemoryRebuildTaskVO getLatestTask(String agentId);

    MemoryRebuildTaskVO getTask(String agentId, String taskId);
}
