package xiaozhi.modules.memory.service;

import xiaozhi.modules.memory.dto.MemoryRebuildRequestDTO;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.memory.vo.MemoryRebuildResultVO;

/**
 * 长期记忆维护服务：会话记忆处理、记忆清理与重建、维护模型提取与决策。
 */
public interface MemoryMaintenanceService {

    /**
     * 清空指定智能体的所有记忆。
     */
    MemoryRebuildResultVO clearMemories(String agentId, String actorType, String actorId);

    /**
     * 从最近聊天记录重建长期记忆。
     */
    MemoryRebuildResultVO rebuildFromRecentChatHistory(String agentId, MemoryRebuildRequestDTO dto,
                                                       String actorType, String actorId);

    /**
     * 使用维护模型按通用生命周期原则重审当前生效记忆。
     */
    MemoryRebuildResultVO reviewActiveMemories(String agentId, boolean dryRun, String actorType, String actorId);

    /**
     * 使用维护模型处理显式记住指令。
     *
     * @return 是否发生了变更
     */
    boolean applyRememberInstruction(AgentEntity agent, String scopeId, String text, String category,
                                     String speakerId, String speakerName, boolean shared,
                                     String actorType, String actorId);

    /**
     * 使用维护模型处理主模型发起的忘记指令，并记录可供主模型理解的 tombstone。
     *
     * @return 是否发生了变更
     */
    boolean applyForgetInstruction(AgentEntity agent, String scopeId, String text, String patternType, String reason,
                                   String speakerId, String speakerName, boolean shared,
                                   String actorType, String actorId);
}
