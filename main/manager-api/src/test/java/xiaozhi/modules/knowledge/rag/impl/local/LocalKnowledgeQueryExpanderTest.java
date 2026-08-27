package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class LocalKnowledgeQueryExpanderTest {
    @Test
    void expandsSinglePersonDrinkRecommendationWithoutDroppingOriginalQuestion() {
        List<String> queries = LocalKnowledgeQueryExpander.expand(
                "我今天一个人来慧苑，你推荐我喝点什么？",
                "适合一人独饮的酒水推荐");

        assertTrue(queries.stream().anyMatch(query -> query.contains("慧苑")));
        assertTrue(queries.stream().anyMatch(query -> query.contains("单人") && query.contains("1人")));
    }

    @Test
    void expandsTwoPersonMealRecommendation() {
        List<String> queries = LocalKnowledgeQueryExpander.expand(
                "我们两个人来吃饭",
                "两个人就餐的菜品推荐");

        assertTrue(queries.stream().anyMatch(query -> query.contains("双人") && query.contains("2人")));
    }

    @Test
    void expandsLowestPriceIntentIntoStructuredPriceFieldNames() {
        List<String> queries = LocalKnowledgeQueryExpander.expand("最便宜的酒水是什么", "最低价酒水");

        assertTrue(queries.stream().anyMatch(query -> query.contains("价格") && query.contains("售价")));
    }

    @Test
    void expandsOrdinaryPriceAndSingleItemLanguageIntoStructuredFields() {
        List<String> queries = LocalKnowledgeQueryExpander.expand(
                "我就想喝一杯单独的可乐，多少钱？", "可乐单独点单价格");

        assertTrue(queries.stream().anyMatch(query -> query.contains("售价") && query.contains("单价")));
        assertTrue(queries.stream().anyMatch(query -> query.contains("单品") && query.contains("零售")));
    }
}
