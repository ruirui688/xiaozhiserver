package xiaozhi.modules.memory.service;

import xiaozhi.modules.memory.dto.MemoryRebuildRequestDTO;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.memory.vo.MemoryRebuildResultVO;

/**
 * 长期记忆维护服务：会话记忆处理、记忆清理与重建、维护模型提取与决策。
 */
public interface MemoryMaintenanceService {

    /**
     * PowerMem 两阶段管线：Stage 1 提取事实 → Stage 2 语义搜索 + 决策执行。
     * 在会话关闭时触发。
     *
     * @return 变更的事实数量
     */
    int processSessionMemories(String sessionId, String agentId);

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
     * 使用维护模型为单条内容创建候选记忆（异步管线入口）。
     */
    void createCandidatesWithMaintenanceModel(AgentEntity agent, String scopeId, String sourceEventId, String content);

    /**
     * 使用维护模型处理管理端指令（remember / forget）。
     *
     * @return 是否发生了变更
     */
    boolean applyMaintenanceInstruction(AgentEntity agent, String scopeId, String instruction,
                                        String actorType, String actorId);
}
