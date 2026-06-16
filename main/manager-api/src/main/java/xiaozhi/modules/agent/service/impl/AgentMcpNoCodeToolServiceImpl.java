package xiaozhi.modules.agent.service.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import xiaozhi.common.service.impl.BaseServiceImpl;
import xiaozhi.modules.agent.dao.AgentMcpNoCodeToolDao;
import xiaozhi.modules.agent.dto.McpNoCodeToolDTO;
import xiaozhi.modules.agent.entity.AgentMcpNoCodeToolEntity;
import xiaozhi.modules.agent.service.AgentMcpNoCodeToolService;

@Service
public class AgentMcpNoCodeToolServiceImpl
        extends BaseServiceImpl<AgentMcpNoCodeToolDao, AgentMcpNoCodeToolEntity>
        implements AgentMcpNoCodeToolService {

    @Override
    public List<McpNoCodeToolDTO> getToolsByAgentId(String agentId) {
        AgentMcpNoCodeToolEntity entity = baseDao.selectOne(
                new QueryWrapper<AgentMcpNoCodeToolEntity>().eq("agent_id", agentId));
        if (entity == null || entity.getTools() == null) {
            return new ArrayList<>();
        }
        return entity.getTools();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveToolsByAgentId(String agentId, List<McpNoCodeToolDTO> tools) {
        AgentMcpNoCodeToolEntity entity = baseDao.selectOne(
                new QueryWrapper<AgentMcpNoCodeToolEntity>().eq("agent_id", agentId));
        if (entity == null) {
            entity = new AgentMcpNoCodeToolEntity();
            entity.setAgentId(agentId);
            entity.setTools(tools == null ? new ArrayList<>() : tools);
            insert(entity);
            return;
        }

        entity.setTools(tools == null ? new ArrayList<>() : tools);
        updateById(entity);
    }

    @Override
    public void deleteByAgentId(String agentId) {
        baseDao.delete(new QueryWrapper<AgentMcpNoCodeToolEntity>().eq("agent_id", agentId));
    }
}
