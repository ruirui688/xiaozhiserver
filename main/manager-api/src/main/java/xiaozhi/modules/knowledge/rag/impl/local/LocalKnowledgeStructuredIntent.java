package xiaozhi.modules.knowledge.rag.impl.local;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * 从结构化切片中识别主实体字段，并区分“查询实体本身”与“查询搭配关系”。
 * 这里只做领域无关的字段角色判断，不维护具体商品、菜品或客户词表。
 */
final class LocalKnowledgeStructuredIntent {
    private static final Pattern PRIMARY_NAME_PATTERN = Pattern.compile(
            "(?:^|[\\n;；])\\s*(?:商品名称|产品名称|品名|饮品名称|酒水名称|餐品名称|菜品名称|套餐名称|服务名称|名称)"
                    + "\\s*[：:=]\\s*([^\\n;；,，]{1,80})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_FIELD_PATTERN = Pattern.compile(
            "(?:^|[\\n;；])\\s*(?:价格|售价|单价|现价|金额|价钱)\\s*[：:=]",
            Pattern.CASE_INSENSITIVE);
    /**
     * 主实体命中的最小长度。单字重合（“名称：甲”）没有区分度，不能当成实体命中。
     */
    private static final int MIN_ENTITY_MATCH_LENGTH = 2;
    private static final Set<String> WEAK_ENTITY_PARTS = Set.of(
            "什么", "怎么", "多少", "推荐", "商品", "产品", "名称", "菜品", "饮品", "套餐",
            "价格", "价钱", "售价", "单价", "金额", "一个", "一杯", "单杯", "单独", "单点",
            "零售", "可以", "想喝", "想要", "有没有", "有吗", "的是", "这个", "那个");

    private LocalKnowledgeStructuredIntent() {
    }

    static String primaryName(String content) {
        Matcher matcher = PRIMARY_NAME_PATTERN.matcher(StringUtils.defaultString(content));
        return matcher.find() ? StringUtils.trimToEmpty(matcher.group(1)) : "";
    }

    static int primaryEntityMatch(String query, String content) {
        if (!allowsPrimaryEntityPriority(query)) {
            return 0;
        }
        String normalizedQuery = normalize(query);
        String primary = normalize(primaryName(content));
        if (primary.length() < MIN_ENTITY_MATCH_LENGTH || normalizedQuery.isEmpty()) {
            return 0;
        }
        int maxLength = Math.min(12, primary.length());
        for (int length = maxLength; length >= MIN_ENTITY_MATCH_LENGTH; length--) {
            // 命中片段必须覆盖主实体名称的一半以上，否则只是品牌/品类前缀的巧合重合：
            // “慧苑”只占“慧苑VIP4人套餐”的 1/5，却会同时命中同库十几行表格数据。
            // 长度从大到小枚举，更短的片段覆盖率只会更低，一旦不满足即可结束。
            if (length * 2 < primary.length()) {
                break;
            }
            for (int start = 0; start + length <= primary.length(); start++) {
                String part = primary.substring(start, start + length);
                if (!WEAK_ENTITY_PARTS.contains(part) && normalizedQuery.contains(part)) {
                    return length;
                }
            }
        }
        return 0;
    }

    static boolean requestsPrice(String query) {
        return StringUtils.containsAny(normalize(query),
                "价格", "多少钱", "价钱", "售价", "单价", "现价", "金额", "怎么卖", "什么价");
    }

    static boolean hasPriceField(String content) {
        return PRICE_FIELD_PATTERN.matcher(StringUtils.defaultString(content)).find();
    }

    static boolean requestsAuxiliaryRelationship(String query) {
        return StringUtils.containsAny(normalize(query),
                "搭配", "适合配", "配什么", "配哪", "佐餐", "配菜");
    }

    static boolean allowsPrimaryEntityPriority(String query) {
        // A mentioned name may be an exclusion, not the requested product. Without semantic
        // constraint resolution, leave these queries to relevance ranking rather than force a name first.
        return !requestsAuxiliaryRelationship(query)
                && !StringUtils.containsAny(normalize(query), "不", "无", "过敏", "避免", "忌");
    }

    private static String normalize(String value) {
        return Normalizer.normalize(StringUtils.defaultString(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s,，。！？；;:：、]+", "");
    }
}
