package xiaozhi.modules.memory.service.impl.maintenance;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import xiaozhi.modules.agent.dto.AgentChatHistoryDTO;
import xiaozhi.modules.memory.entity.MemorySourceEventEntity;
import xiaozhi.modules.memory.utils.MemoryHelper;
import xiaozhi.modules.memory.utils.MemorySpeakerUtils;
import xiaozhi.modules.memory.utils.MemorySpeakerUtils.Registry;
import xiaozhi.modules.memory.utils.MemorySpeakerUtils.SpeakerIdentity;

/**
 * 将事件或聊天历史转换成带可信声纹标签的维护模型上下文。
 */
@Component
public class MemorySpeakerConversationBuilder {

    public record Conversation(String text, Registry speakers) {
    }

    public Conversation fromEvents(List<MemorySourceEventEntity> events) {
        Registry speakers = new Registry();
        if (events == null || events.isEmpty()) {
            return new Conversation("", speakers);
        }
        StringBuilder builder = new StringBuilder();
        for (MemorySourceEventEntity event : events) {
            String text = MemorySpeakerUtils.contentFromChatPayload(event.getContentText());
            if (StringUtils.isBlank(text)) {
                continue;
            }
            String role = switch (event.getChatType() == null ? 0 : event.getChatType()) {
                case 1 -> {
                    SpeakerIdentity speaker = MemorySpeakerUtils.fromEvent(event);
                    speakers.observe(speaker);
                    yield MemorySpeakerUtils.userRole(speaker);
                }
                case 2 -> "助手";
                case 3 -> "工具";
                default -> "事件";
            };
            builder.append(role).append("：").append(text).append("\n");
        }
        return new Conversation(
                StringUtils.abbreviate(builder.toString(), MemoryHelper.SESSION_MAX_CHARS), speakers);
    }

    public String combineHistories(List<List<AgentChatHistoryDTO>> histories) {
        if (histories == null || histories.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (List<AgentChatHistoryDTO> history : histories) {
            String conversation = fromHistory(history);
            if (StringUtils.isNotBlank(conversation)) {
                builder.append("【会话】\n").append(conversation).append("\n");
            }
        }
        return StringUtils.abbreviate(builder.toString(), MemoryHelper.REBUILD_MAX_TOTAL_CHARS);
    }

    public Registry registryFromHistories(List<List<AgentChatHistoryDTO>> histories) {
        Registry speakers = new Registry();
        if (histories == null) {
            return speakers;
        }
        for (List<AgentChatHistoryDTO> history : histories) {
            if (history == null) {
                continue;
            }
            for (AgentChatHistoryDTO item : history) {
                if (item != null && item.getChatType() != null && item.getChatType() == 1) {
                    speakers.observe(MemorySpeakerUtils.fromChatContent(item.getContent()));
                }
            }
        }
        return speakers;
    }

    private String fromHistory(List<AgentChatHistoryDTO> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (AgentChatHistoryDTO item : history) {
            if (item == null || item.getChatType() == null || item.getChatType() == 3) {
                continue;
            }
            String content = MemorySpeakerUtils.contentFromChatPayload(item.getContent());
            if (StringUtils.isBlank(content)) {
                continue;
            }
            String role = item.getChatType() == 1
                    ? MemorySpeakerUtils.userRole(MemorySpeakerUtils.fromChatContent(item.getContent()))
                    : "助手";
            builder.append(role).append(": ").append(content).append("\n");
        }
        return StringUtils.abbreviate(builder.toString(), MemoryHelper.SESSION_MAX_CHARS);
    }
}
