package xiaozhi.modules.memory.service;

import xiaozhi.modules.memory.dto.MemoryEventDTO;
import xiaozhi.modules.memory.dto.MemoryFactDTO;
import xiaozhi.modules.memory.dto.MemoryForgetDTO;
import xiaozhi.modules.memory.dto.MemoryQueryDTO;
import xiaozhi.modules.memory.dto.MemoryRememberDTO;
import xiaozhi.modules.memory.dto.MemoryRebuildRequestDTO;
import xiaozhi.modules.memory.dto.MemorySessionProcessDTO;
import xiaozhi.modules.memory.vo.MemoryDashboardVO;
import xiaozhi.modules.memory.vo.MemoryQueryVO;
import xiaozhi.modules.memory.vo.MemoryRebuildResultVO;

public interface MemoryService {
    void recordEvent(MemoryEventDTO dto);

    MemoryQueryVO query(MemoryQueryDTO dto);

    void remember(MemoryRememberDTO dto, String actorType, String actorId);

    void forget(MemoryForgetDTO dto, String actorType, String actorId);

    int processSession(MemorySessionProcessDTO dto);

    MemoryDashboardVO dashboard(String agentId);

    String createFact(String agentId, MemoryFactDTO dto, String actorType, String actorId);

    void updateFact(String agentId, String memoryId, MemoryFactDTO dto, String actorType, String actorId);

    void archiveFact(String agentId, String memoryId, String actorType, String actorId);

    void deleteFact(String agentId, String memoryId, String actorType, String actorId);

    void confirmCandidate(String agentId, String candidateId, String actorType, String actorId);

    void rejectCandidate(String agentId, String candidateId, String actorType, String actorId);

    MemoryRebuildResultVO clearMemories(String agentId, String actorType, String actorId);

    MemoryRebuildResultVO rebuildFromRecentChatHistory(String agentId, MemoryRebuildRequestDTO dto,
                                                       String actorType, String actorId);

    MemoryRebuildResultVO reviewActiveMemories(String agentId, boolean dryRun, String actorType, String actorId);

}
