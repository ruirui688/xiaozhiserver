package xiaozhi.modules.memory.service.impl.maintenance;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import xiaozhi.modules.agent.dto.AgentChatHistoryDTO;
import xiaozhi.modules.memory.dto.MemorySessionMessageDTO;
import xiaozhi.modules.memory.entity.MemorySourceEventEntity;
import xiaozhi.modules.memory.utils.MemoryHelper;
import xiaozhi.modules.memory.utils.MemorySpeakerUtils;
import xiaozhi.modules.memory.utils.MemorySpeakerUtils.Registry;
import xiaozhi.modules.memory.utils.MemorySpeakerUtils.SpeakerIdentity;
import xiaozhi.modules.memory.service.MemoryGovernanceService;

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

    public Conversation fromMessages(List<MemorySessionMessageDTO> messages) {
        Registry speakers = new Registry();
        if (messages == null || messages.isEmpty()) {
            return new Conversation("", speakers);
        }
        StringBuilder builder = new StringBuilder();
        for (MemorySessionMessageDTO message : messages) {
            if (message == null || Boolean.FALSE.equals(message.getMemoryEligible()) || StringUtils.isBlank(message.getContent())) {
                continue;
            }
            String role = StringUtils.lowerCase(StringUtils.trimToEmpty(message.getRole()));
            String content = MemorySpeakerUtils.contentFromChatPayload(message.getContent());
            if (StringUtils.isBlank(content)) {
                continue;
            }
            String displayRole = switch (role) {
                case "user" -> {
                    SpeakerIdentity speaker = new SpeakerIdentity(MemoryGovernanceService.speaker(message),
                            MemorySpeakerUtils.fromChatContent(message.getContent()).name());
                    speakers.observe(speaker);
                    yield MemorySpeakerUtils.userRole(speaker);
                }
                case "assistant" -> "助手";
                case "tool" -> "工具";
                default -> null;
            };
            if (displayRole != null) {
                builder.append(displayRole).append("：").append(content).append("\n");
            }
        }
        if (builder.length() > MemoryHelper.REBUILD_MAX_TOTAL_CHARS) {
            throw new IllegalArgumentException("会话分段超过 48000 字符，请按完整消息拆分后重试");
        }
        return new Conversation(builder.toString(), speakers);
    }

    public String combineHistories(List<List<AgentChatHistoryDTO>> histories) {
        if (histories == null || histories.isEmpty()) {
            return "";
        }
        java.util.List<String> sessions = new java.util.ArrayList<>();
        for (List<AgentChatHistoryDTO> history : histories) {
            String conversation = fromHistory(history);
            if (StringUtils.isNotBlank(conversation)) {
                sessions.add("【会话】\n" + conversation + "\n");
            }
        }
        return completeTail(sessions, MemoryHelper.REBUILD_MAX_TOTAL_CHARS);
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
                    MemorySessionMessageDTO message = historyMessage(item);
                    if (!Boolean.FALSE.equals(message.getMemoryEligible()))
                        speakers.observe(new SpeakerIdentity(MemoryGovernanceService.speaker(message),
                                MemorySpeakerUtils.fromChatContent(message.getContent()).name()));
                }
            }
        }
        return speakers;
    }

    public List<MemorySessionMessageDTO> messagesFromHistories(List<List<AgentChatHistoryDTO>> histories) {
        return histories.stream().flatMap(List::stream).filter(item -> item != null && item.getChatType() != null)
                .map(this::historyMessage).filter(message -> !Boolean.FALSE.equals(message.getMemoryEligible())).toList();
    }

    private MemorySessionMessageDTO historyMessage(AgentChatHistoryDTO item) {
        MemorySessionMessageDTO message = new MemorySessionMessageDTO();
        message.setRole(item.getChatType() == 1 ? "user" : item.getChatType() == 3 ? "tool" : "assistant");
        message.setContent(item.getContent());
        var metadata = MemorySpeakerUtils.parseObject(item.getMetricsJson());
        if (metadata.containsKey("memory_eligible")) message.setMemoryEligible(metadata.getBool("memory_eligible"));
        if (metadata.containsKey("speaker_id")) message.setSpeakerId(metadata.getStr("speaker_id"));
        return message;
    }

    private String fromHistory(List<AgentChatHistoryDTO> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        java.util.List<String> messages = new java.util.ArrayList<>();
        for (AgentChatHistoryDTO item : history) {
            if (item == null || item.getChatType() == null || item.getChatType() == 3) {
                continue;
            }
            MemorySessionMessageDTO message = historyMessage(item);
            if (Boolean.FALSE.equals(message.getMemoryEligible())) continue;
            String content = MemorySpeakerUtils.contentFromChatPayload(item.getContent());
            if (StringUtils.isBlank(content)) {
                continue;
            }
            String role = item.getChatType() == 1
                    ? MemorySpeakerUtils.userRole(new SpeakerIdentity(MemoryGovernanceService.speaker(message),
                            MemorySpeakerUtils.fromChatContent(item.getContent()).name()))
                    : "助手";
            messages.add(role + ": " + content + "\n");
        }
        return completeTail(messages, MemoryHelper.REBUILD_MAX_TOTAL_CHARS);
    }

    /** Preserve complete recent messages/sessions so late corrections survive bounded reconstruction. */
    private String completeTail(List<String> parts, int maxChars) {
        java.util.Deque<String> kept = new java.util.ArrayDeque<>();
        int size = 0;
        for (int index = parts.size() - 1; index >= 0; index--) {
            String part = parts.get(index);
            if (part.length() > maxChars) throw new IllegalArgumentException("单条历史消息超过重建预算，不能安全裁剪");
            if (size + part.length() > maxChars) break;
            kept.addFirst(part);
            size += part.length();
        }
        return String.join("", kept);
    }
}
