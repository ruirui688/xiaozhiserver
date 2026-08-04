package xiaozhi.modules.agent.skill.service;

import java.util.List;

import xiaozhi.modules.agent.skill.dto.AgentSemanticSkillOrderDTO;
import xiaozhi.modules.agent.skill.dto.AgentSemanticSkillSaveDTO;
import xiaozhi.modules.agent.skill.vo.AgentSemanticSkillVO;

public interface AgentSemanticSkillService {
    List<AgentSemanticSkillVO> list(String agentId);

    List<AgentSemanticSkillVO> listEnabled(String agentId);

    AgentSemanticSkillVO create(String agentId, AgentSemanticSkillSaveDTO dto, Long operatorId);

    AgentSemanticSkillVO update(String agentId, String skillId, AgentSemanticSkillSaveDTO dto, Long operatorId);

    void delete(String agentId, String skillId);

    void updateOrder(String agentId, AgentSemanticSkillOrderDTO dto, Long operatorId);

    void replaceAll(
            String agentId,
            List<AgentSemanticSkillSaveDTO> skills,
            Long operatorId,
            boolean publishRefresh);

    void deleteByAgentId(String agentId);

    void deleteByAgentIds(List<String> agentIds);
}
