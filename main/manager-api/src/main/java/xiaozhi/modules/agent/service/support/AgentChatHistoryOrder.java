package xiaozhi.modules.agent.service.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.agent.dto.AgentChatHistoryDTO;

/** Orders delayed chat reports by their original conversation turn. */
public final class AgentChatHistoryOrder {
    private AgentChatHistoryOrder() {
    }

    public static List<AgentChatHistoryDTO> sort(List<AgentChatHistoryDTO> messages) {
        if (messages == null || messages.size() < 2) {
            return messages;
        }
        List<OrderedMessage> ordered = new ArrayList<>(messages.size());
        for (int index = 0; index < messages.size(); index++) {
            ordered.add(OrderedMessage.from(messages.get(index), index));
        }
        ordered.sort(Comparator
                .comparingLong(OrderedMessage::logicalStartedAt)
                .thenComparing((left, right) -> compareWithinTurn(left, right))
                .thenComparingInt(OrderedMessage::originalIndex));
        return ordered.stream().map(OrderedMessage::message).toList();
    }

    private static int compareWithinTurn(OrderedMessage left, OrderedMessage right) {
        if (StringUtils.isBlank(left.turnId()) || !left.turnId().equals(right.turnId())) {
            return 0;
        }
        if (left.messageSequence() > 0 && right.messageSequence() > 0) {
            int sequenceOrder = Long.compare(left.messageSequence(), right.messageSequence());
            if (sequenceOrder != 0) {
                return sequenceOrder;
            }
        }
        return Integer.compare(left.roleOrder(), right.roleOrder());
    }

    private record OrderedMessage(
            AgentChatHistoryDTO message,
            int originalIndex,
            long logicalStartedAt,
            String turnId,
            long messageSequence,
            int roleOrder) {

        private static OrderedMessage from(AgentChatHistoryDTO message, int originalIndex) {
            Map<String, Object> metrics = parseMetrics(message == null ? null : message.getMetricsJson());
            long createdAt = dateMillis(message == null ? null : message.getCreatedAt());
            long logicalStartedAt = positiveLong(metrics.get("started_at_epoch_ms"), createdAt);
            String turnId = String.valueOf(metrics.getOrDefault("turn_id", ""));
            long messageSequence = positiveLong(metrics.get("message_sequence"), 0L);
            return new OrderedMessage(
                    message,
                    originalIndex,
                    logicalStartedAt,
                    turnId,
                    messageSequence,
                    roleOrder(message, metrics));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> parseMetrics(String rawMetrics) {
            if (StringUtils.isBlank(rawMetrics)) {
                return Map.of();
            }
            try {
                Object parsed = JsonUtils.parseObject(rawMetrics, Object.class);
                return parsed instanceof Map<?, ?> map
                        ? (Map<String, Object>) map
                        : Map.of();
            } catch (RuntimeException ignored) {
                return Map.of();
            }
        }

        private static long positiveLong(Object value, long fallback) {
            if (value instanceof Number number) {
                long result = number.longValue();
                return result > 0 ? result : fallback;
            }
            try {
                long result = Long.parseLong(String.valueOf(value));
                return result > 0 ? result : fallback;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static int roleOrder(AgentChatHistoryDTO message, Map<String, Object> metrics) {
            String role = String.valueOf(metrics.getOrDefault("message_role", ""));
            if ("user".equals(role) || chatType(message) == 1) {
                return 1;
            }
            if ("tool".equals(role) || chatType(message) == 3) {
                return 2;
            }
            return 3;
        }

        private static int chatType(AgentChatHistoryDTO message) {
            return message == null || message.getChatType() == null
                    ? 0
                    : message.getChatType().intValue();
        }

        private static long dateMillis(Date value) {
            return value == null ? Long.MAX_VALUE : value.getTime();
        }
    }
}
