package xiaozhi.modules.agent.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.agent.dto.AgentChatHistoryDTO;

class AgentChatHistoryOrderTest {
    @Test
    void ordersUserToolsAndInterruptedAssistantByTurnMetadata() {
        AgentChatHistoryDTO userOne = message(
                (byte) 1, "user-1", 1000,
                "{\"turn_id\":\"turn-1\",\"started_at_epoch_ms\":100,\"message_role\":\"user\",\"message_sequence\":1}");
        AgentChatHistoryDTO userTwo = message(
                (byte) 1, "user-2", 2000,
                "{\"turn_id\":\"turn-2\",\"started_at_epoch_ms\":200,\"message_role\":\"user\",\"message_sequence\":1}");
        AgentChatHistoryDTO assistantOne = message(
                (byte) 2, "assistant-1", 2200,
                "{\"turn_id\":\"turn-1\",\"started_at_epoch_ms\":100,\"message_role\":\"assistant\",\"message_sequence\":4}");
        AgentChatHistoryDTO toolCall = message(
                (byte) 3, "tool-call", 1400,
                "{\"turn_id\":\"turn-1\",\"started_at_epoch_ms\":100,\"message_role\":\"tool\",\"message_sequence\":2}");
        AgentChatHistoryDTO toolResult = message(
                (byte) 3, "tool-result", 1500,
                "{\"turn_id\":\"turn-1\",\"started_at_epoch_ms\":100,\"message_role\":\"tool\",\"message_sequence\":3}");

        List<AgentChatHistoryDTO> sorted = AgentChatHistoryOrder.sort(List.of(
                userOne, userTwo, assistantOne, toolCall, toolResult));

        assertEquals(
                List.of("user-1", "tool-call", "tool-result", "assistant-1", "user-2"),
                sorted.stream().map(AgentChatHistoryDTO::getContent).toList());
    }

    @Test
    void keepsLegacyRowsInCreatedAtOrderWhenMetricsAreMissingOrInvalid() {
        AgentChatHistoryDTO later = message((byte) 2, "later", 2000, "not-json");
        AgentChatHistoryDTO earlier = message((byte) 1, "earlier", 1000, null);

        List<AgentChatHistoryDTO> sorted = AgentChatHistoryOrder.sort(List.of(later, earlier));

        assertEquals(
                List.of("earlier", "later"),
                sorted.stream().map(AgentChatHistoryDTO::getContent).toList());
    }

    private AgentChatHistoryDTO message(
            byte chatType, String content, long createdAt, String metricsJson) {
        AgentChatHistoryDTO message = new AgentChatHistoryDTO();
        message.setChatType(chatType);
        message.setContent(content);
        message.setCreatedAt(new Date(createdAt));
        message.setMetricsJson(metricsJson);
        return message;
    }
}
