package xiaozhi.modules.knowledge.rag.impl.local;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

/**
 * 为中文口语查询补充少量确定性等价表达。扩展只参与召回，不改写用户问题，
 * 避免“一个人/两个人/喝点什么”等口语与表格字段表达不同而漏召回。
 */
public final class LocalKnowledgeQueryExpander {
    private LocalKnowledgeQueryExpander() {
    }

    public static List<String> expand(String rawQuestion, String rewrittenQuestion) {
        Set<String> queries = new LinkedHashSet<>();
        add(queries, rawQuestion);
        add(queries, rewrittenQuestion);

        String combined = normalize(String.join(" ", queries));
        if (containsAny(combined, "一个人", "一人", "1人", "一位", "单人", "独自", "独饮")) {
            queries.add("单人 一人 1人 一位 独享 独饮");
        }
        if (containsAny(combined, "两个人", "两人", "2人", "二人", "两位", "双人")) {
            queries.add("双人 两人 2人 二人 两位");
        }
        if (containsAny(combined, "最便宜", "最低价", "价格最低", "低价")) {
            queries.add("价格 售价 单价 金额 价钱");
        }
        if (containsAny(combined, "最贵", "最高价", "价格最高")) {
            queries.add("价格 售价 单价 金额 价钱");
        }
        if (containsAny(combined, "价格", "多少钱", "价钱", "售价", "单价", "现价", "金额", "怎么卖", "什么价")) {
            queries.add("价格 售价 单价 现价 金额 价钱");
        }
        if (containsAny(combined, "单独", "单点", "单杯", "一杯", "零售")) {
            queries.add("单品 单点 单独 零售 杯 单杯");
        }
        // “有什么酒/喝的”这类品类问法，表格里记的是“酒水名称：… 品类：洋酒/葡萄酒/软饮/啤酒”，
        // 单靠原话里的“酒”不足以召回；补充类别词只参与召回，不改写用户问题。
        if (containsAny(combined, "酒", "喝的", "喝点什么", "饮品", "饮料")) {
            queries.add("酒水 洋酒 葡萄酒 啤酒 白酒 鸡尾酒 特调 饮品 饮料");
        }
        // “有什么吃的”在表格里对应的是“餐品名称/分类：主食/前菜/汤品…”，同样需要类别词补齐。
        if (containsAny(combined, "吃的", "有什么吃", "吃点什么", "餐品", "菜品")) {
            queries.add("餐品 菜品 主食 前菜 汤品 小吃 甜点 水果盘");
        }
        return new ArrayList<>(queries);
    }

    private static void add(Set<String> queries, String value) {
        String normalized = normalize(value);
        if (StringUtils.isNotBlank(normalized)) {
            queries.add(normalized);
        }
    }

    private static boolean containsAny(String value, String... candidates) {
        return StringUtils.containsAny(value, candidates);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(StringUtils.defaultString(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}
