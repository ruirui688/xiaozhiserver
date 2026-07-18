package xiaozhi.modules.agent.service;

import java.util.List;
import java.util.Map;

import xiaozhi.modules.agent.dto.AgentFeishuConfigSaveDTO;
import xiaozhi.modules.agent.dto.FeishuRuntimeActionDTO;
import xiaozhi.modules.agent.vo.AgentFeishuConfigVO;
import xiaozhi.modules.agent.vo.FeishuChatVO;

public interface AgentFeishuService {
    AgentFeishuConfigVO getConfig(String agentId);

    void saveConfig(String agentId, AgentFeishuConfigSaveDTO dto, Long operatorId);

    void deleteConfig(String agentId);

    List<FeishuChatVO> testConnection(String agentId, AgentFeishuConfigSaveDTO dto);

    List<FeishuChatVO> listChats(String agentId);

    Map<String, Object> sendTestMessage(String agentId);

    Map<String, Object> executeRuntimeAction(String agentId, FeishuRuntimeActionDTO dto);

    Map<String, Object> getRuntimePluginConfig(String agentId);

    Map<String, Object> handleEvent(String agentId, Map<String, Object> payload);
}
