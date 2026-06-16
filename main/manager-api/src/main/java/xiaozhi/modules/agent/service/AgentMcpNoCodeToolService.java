package xiaozhi.modules.agent.service;

import java.util.List;

import xiaozhi.common.service.BaseService;
import xiaozhi.modules.agent.dto.McpNoCodeToolDTO;
import xiaozhi.modules.agent.entity.AgentMcpNoCodeToolEntity;

public interface AgentMcpNoCodeToolService extends BaseService<AgentMcpNoCodeToolEntity> {
    List<McpNoCodeToolDTO> getToolsByAgentId(String agentId);

    void saveToolsByAgentId(String agentId, List<McpNoCodeToolDTO> tools);

    void deleteByAgentId(String agentId);
}
