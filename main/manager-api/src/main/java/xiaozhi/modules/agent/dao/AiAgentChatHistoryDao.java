package xiaozhi.modules.agent.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.agent.entity.AgentChatHistoryEntity;
import xiaozhi.modules.agent.dto.AgentStorageUsageDTO;

/**
 * {@link AgentChatHistoryEntity} 智能体聊天历史记录Dao对象
 *
 * @author Goody
 * @version 1.0, 2025/4/30
 * @since 1.0.0
 */
@Mapper
public interface AiAgentChatHistoryDao extends BaseMapper<AgentChatHistoryEntity> {

    /**
     * 根据智能体ID删除聊天历史记录
     *
     * @param agentId 智能体ID
     */
    void deleteHistoryByAgentId(String agentId);

    /**
     * 根据智能体ID删除音频ID
     *
     * @param agentId 智能体ID
     */
    void deleteAudioIdByAgentId(String agentId);

    /**
     * 根据智能体ID获取所有音频ID列表
     *
     * @param agentId 智能体ID
     * @return 音频ID列表
     */
    List<String> getAudioIdsByAgentId(String agentId);

    /**
     * 批量删除音频
     *
     * @param audioIds 音频ID列表
     */
    void deleteAudioByIds(@Param("audioIds") List<String> audioIds);

    /**
     * 批量删除未被聊天记录引用的音频
     *
     * @param audioIds 音频ID列表
     */
    int deleteUnreferencedAudioByIds(@Param("audioIds") List<String> audioIds);

    /** 查询单个智能体的聊天记录存储用量 */
    Long getStorageUsageBytes(@Param("agentId") String agentId);

    /** 按创建时间查询最旧聊天记录，用于容量滚动淘汰。 */
    List<AgentChatHistoryEntity> getOldestForStorageEviction(
            @Param("agentId") String agentId,
            @Param("limit") int limit);

    /** 批量删除容量滚动淘汰选中的聊天记录。 */
    int deleteByStorageEvictionIds(@Param("historyIds") List<Long> historyIds);

    /** 批量查询智能体聊天记录存储用量 */
    List<AgentStorageUsageDTO> getStorageUsageByAgentIds(@Param("agentIds") List<String> agentIds);
}
