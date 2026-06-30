package xiaozhi.modules.agent.service;

/**
 * 智能体聊天标题生成服务。
 */
public interface AgentChatTitleGenerationService {

    /**
     * 根据会话ID生成聊天标题并保存。
     *
     * @param sessionId 会话ID
     * @return 是否成功
     */
    boolean generateAndSaveChatTitle(String sessionId);
}
