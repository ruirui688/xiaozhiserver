package xiaozhi.modules.agent.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;
import xiaozhi.modules.agent.dto.AgentChatHistoryDTO;
import xiaozhi.modules.agent.entity.AgentChatHistoryEntity;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.agent.service.AgentChatTitleGenerationService;
import xiaozhi.modules.agent.service.AgentChatTitleService;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.llm.service.LLMService;
import xiaozhi.common.llm.LlmUsageContext;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;

@Service
@RequiredArgsConstructor
public class AgentChatTitleGenerationServiceImpl implements AgentChatTitleGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatTitleGenerationServiceImpl.class);
    private static final Pattern JSON_PATTERN = Pattern.compile("\\{.*?\\}", Pattern.DOTALL);
    private static final Pattern DEVICE_CONTROL_PATTERN = Pattern.compile("设备控制|设备操作|控制设备|设备状态",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WEATHER_PATTERN = Pattern.compile("天气|温度|湿度|降雨|气象", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_PATTERN = Pattern.compile("日期|时间|星期|月份|年份", Pattern.CASE_INSENSITIVE);

    private final AgentChatHistoryService agentChatHistoryService;
    private final AgentService agentService;
    private final AgentChatTitleService agentChatTitleService;
    private final LLMService llmService;
    private final ModelConfigService modelConfigService;

    @Override
    public boolean generateAndSaveChatTitle(String sessionId) {
        try {
            String agentId = findAgentIdBySessionId(sessionId);
            if (StringUtils.isBlank(agentId)) {
                log.warn("会话 {} 无法获取智能体信息，跳过标题生成", sessionId);
                return false;
            }

            List<AgentChatHistoryDTO> chatHistory = getChatHistoryBySessionId(agentId, sessionId);
            if (chatHistory == null || chatHistory.isEmpty()) {
                return false;
            }

            List<String> meaningfulMessages = extractMeaningfulMessages(chatHistory);
            if (meaningfulMessages.isEmpty()) {
                return false;
            }

            StringBuilder conversation = new StringBuilder();
            for (int i = 0; i < meaningfulMessages.size(); i++) {
                conversation.append("消息").append(i + 1).append(": ").append(meaningfulMessages.get(i)).append("\n");
            }

            String slmModelId = getSlmModelId(agentId);
            var agent = agentService.getAgentById(agentId);
            String title = LlmUsageContext.call(new LlmUsageContext.Identity(
                    agent == null ? null : agent.getWorkspaceId(), agentId, null,
                    sessionId, slmModelId, "CHAT_TITLE"),
                    () -> llmService.generateTitle(conversation.toString(), slmModelId));
            if (StringUtils.isBlank(title)) {
                return false;
            }

            agentChatTitleService.saveOrUpdateTitle(sessionId, title);
            log.info("成功保存会话 {} 的标题: {}", sessionId, title);
            return true;
        } catch (Exception e) {
            log.error("生成会话 {} 的标题时发生错误: {}", sessionId, e.getMessage());
            return false;
        }
    }

    private List<AgentChatHistoryDTO> getChatHistoryBySessionId(String agentId, String sessionId) {
        try {
            return agentChatHistoryService.getChatHistoryBySessionId(agentId, sessionId);
        } catch (Exception e) {
            log.error("获取会话 {} 的聊天记录失败: {}", sessionId, e.getMessage());
            return null;
        }
    }

    private String findAgentIdBySessionId(String sessionId) {
        try {
            QueryWrapper<AgentChatHistoryEntity> wrapper = new QueryWrapper<>();
            wrapper.select("agent_id")
                    .eq("session_id", sessionId)
                    .last("LIMIT 1");

            AgentChatHistoryEntity entity = agentChatHistoryService.getOne(wrapper);
            return entity != null ? entity.getAgentId() : null;
        } catch (Exception e) {
            log.error("根据会话ID {} 查找智能体ID失败: {}", sessionId, e.getMessage());
            return null;
        }
    }

    private List<String> extractMeaningfulMessages(List<AgentChatHistoryDTO> chatHistory) {
        List<String> meaningfulMessages = new ArrayList<>();
        for (AgentChatHistoryDTO message : chatHistory) {
            if (message.getChatType() != null && message.getChatType() == 1) {
                String content = extractContentFromMessage(message);
                if (isMeaningfulMessage(content)) {
                    meaningfulMessages.add(content);
                }
            }
        }
        return meaningfulMessages;
    }

    private String extractContentFromMessage(AgentChatHistoryDTO message) {
        String content = message.getContent();
        if (StringUtils.isBlank(content)) {
            return "";
        }

        Matcher matcher = JSON_PATTERN.matcher(content);
        if (matcher.find()) {
            return extractTextFromJson(matcher.group());
        }
        return content;
    }

    private String extractTextFromJson(String jsonContent) {
        Pattern contentPattern = Pattern.compile("\"content\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = contentPattern.matcher(jsonContent);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return jsonContent;
    }

    private boolean isMeaningfulMessage(String content) {
        if (StringUtils.isBlank(content)) {
            return false;
        }
        if (DEVICE_CONTROL_PATTERN.matcher(content).find()) {
            return false;
        }
        if (WEATHER_PATTERN.matcher(content).find() || DATE_PATTERN.matcher(content).find()) {
            return false;
        }
        return content.length() >= 5;
    }

    private String getSlmModelId(String agentId) {
        try {
            if (StringUtils.isBlank(agentId)) {
                return null;
            }

            AgentInfoVO agentInfo = agentService.getAgentById(agentId);
            if (agentInfo == null) {
                return null;
            }

            String slmModelId = agentInfo.getSlmModelId();
            if (StringUtils.isNotBlank(slmModelId)) {
                return slmModelId;
            }

            ModelConfigEntity defaultLlmConfig = getDefaultLLMConfig();
            if (defaultLlmConfig != null) {
                return defaultLlmConfig.getId();
            }
            return agentInfo.getLlmModelId();
        } catch (Exception e) {
            log.error("获取智能体SLM模型ID失败，agentId: {}, 错误: {}", agentId, e.getMessage());
            return null;
        }
    }

    private ModelConfigEntity getDefaultLLMConfig() {
        try {
            List<ModelConfigEntity> llmConfigs = modelConfigService.getEnabledModelsByType("LLM");
            if (llmConfigs == null || llmConfigs.isEmpty()) {
                return null;
            }
            for (ModelConfigEntity config : llmConfigs) {
                if (config.getIsDefault() != null && config.getIsDefault() == 1) {
                    return config;
                }
            }
            return llmConfigs.get(0);
        } catch (Exception e) {
            log.error("获取默认LLM配置失败: {}", e.getMessage());
            return null;
        }
    }
}
