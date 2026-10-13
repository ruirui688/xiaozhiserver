package xiaozhi.modules.agent.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.agent.entity.AgentChatHistoryEntity;
import xiaozhi.modules.agent.entity.AgentVoicePrintEntity;

/**
 * {@link AgentChatHistoryEntity} 智能体聊天历史记录Dao对象
 *
 * @author Goody
 * @version 1.0, 2025/4/30
 * @since 1.0.0
 */
@Mapper
public interface AgentVoicePrintDao extends BaseMapper<AgentVoicePrintEntity> {

    List<AgentVoicePrintEntity> selectBoundByAgentId(@Param("agentId") String agentId);

    List<String> selectBoundIdsByAgentId(@Param("agentId") String agentId);

    List<String> selectBoundAgentIdsByVoicePrintId(@Param("voicePrintId") String voicePrintId);

    Long countBindingsByAgentId(@Param("agentId") String agentId);

    int insertBinding(
            @Param("agentId") String agentId,
            @Param("voicePrintId") String voicePrintId,
            @Param("creator") Long creator);

    int deleteBindingsByAgentId(@Param("agentId") String agentId);

    int deleteBindingsByVoicePrintId(@Param("voicePrintId") String voicePrintId);

    int bindPerson(
            @Param("voicePrintId") String voicePrintId,
            @Param("workspaceId") String workspaceId,
            @Param("personId") String personId,
            @Param("updater") Long updater);

    int unbindPerson(
            @Param("voicePrintId") String voicePrintId,
            @Param("workspaceId") String workspaceId,
            @Param("personId") String personId,
            @Param("updater") Long updater);

    List<AgentVoicePrintEntity> selectPersonOptions(
            @Param("workspaceId") String workspaceId,
            @Param("personId") String personId);

    AgentVoicePrintEntity selectByIdForUpdate(
            @Param("voicePrintId") String voicePrintId,
            @Param("workspaceId") String workspaceId);

    AgentVoicePrintEntity selectByPersonForUpdate(
            @Param("workspaceId") String workspaceId,
            @Param("personId") String personId);

}
