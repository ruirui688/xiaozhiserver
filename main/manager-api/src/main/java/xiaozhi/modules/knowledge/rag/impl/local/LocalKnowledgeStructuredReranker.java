package xiaozhi.modules.knowledge.rag.impl.local;

import java.util.Comparator;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/** 对表格切片中的明确数值极值请求做确定性排序，避免让 LLM 在不完整候选中猜测。 */
final class LocalKnowledgeStructuredReranker {
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?:价格|售价|单价|现价|金额|价钱)\\s*[：:=]?\\s*[￥¥]?\\s*(\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    private LocalKnowledgeStructuredReranker() {
    }

    static <T> Comparator<T> comparator(String query, Function<T, String> content,
            ToDoubleFunction<T> fallbackScore) {
        Direction direction = direction(query);
        Comparator<T> scoreOrder = Comparator.comparingDouble(fallbackScore).reversed();
        Comparator<T> entityOrder = Comparator.<T>comparingInt(
                item -> LocalKnowledgeStructuredIntent.primaryEntityMatch(query, content.apply(item))).reversed();
        Comparator<T> attributeOrder = Comparator.<T>comparingInt(
                item -> LocalKnowledgeStructuredIntent.hasPriceField(content.apply(item)) ? 1 : 0).reversed();
        if (direction == Direction.NONE) {
            Comparator<T> ordering = entityOrder;
            if (LocalKnowledgeStructuredIntent.requestsPrice(query)) {
                ordering = ordering.thenComparing(attributeOrder);
            }
            return ordering.thenComparing(scoreOrder);
        }
        return entityOrder
                .thenComparingInt(item -> extractPrice(content.apply(item), direction).isPresent() ? 0 : 1)
                .thenComparingDouble(item -> orderedPrice(extractPrice(content.apply(item), direction), direction))
                .thenComparing(scoreOrder);
    }

    private static OptionalDouble extractPrice(String content, Direction direction) {
        Matcher matcher = PRICE_PATTERN.matcher(StringUtils.defaultString(content));
        OptionalDouble value = OptionalDouble.empty();
        while (matcher.find()) {
            double current = Double.parseDouble(matcher.group(1));
            if (value.isEmpty()
                    || (direction == Direction.LOWEST && current < value.getAsDouble())
                    || (direction == Direction.HIGHEST && current > value.getAsDouble())) {
                value = OptionalDouble.of(current);
            }
        }
        return value;
    }

    private static double orderedPrice(OptionalDouble price, Direction direction) {
        if (price.isEmpty()) {
            return 0D;
        }
        return direction == Direction.LOWEST ? price.getAsDouble() : -price.getAsDouble();
    }

    private static Direction direction(String query) {
        String value = StringUtils.defaultString(query);
        if (StringUtils.containsAny(value, "最便宜", "最低价", "价格最低", "低价")) {
            return Direction.LOWEST;
        }
        if (StringUtils.containsAny(value, "最贵", "最高价", "价格最高")) {
            return Direction.HIGHEST;
        }
        return Direction.NONE;
    }

    private enum Direction {
        NONE,
        LOWEST,
        HIGHEST
    }
}
