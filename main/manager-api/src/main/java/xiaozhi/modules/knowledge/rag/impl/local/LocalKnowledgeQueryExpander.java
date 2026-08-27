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
