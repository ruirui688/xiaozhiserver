package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void expandsDrinkCategoryIntoInventoryFieldValues() {
        // 用户只说“有什么酒”，表格里记的是“酒水名称：… 品类：洋酒/葡萄酒/啤酒”。
        List<String> queries = LocalKnowledgeQueryExpander.expand("你们有什么酒推荐", "你们有什么酒推荐");

        assertTrue(queries.stream().anyMatch(query -> query.contains("酒水") && query.contains("洋酒")
                && query.contains("葡萄酒") && query.contains("啤酒")));
    }

    @Test
    void expandsWhatToEatIntoFoodCategoryFields() {
        List<String> queries = LocalKnowledgeQueryExpander.expand("那你有什么吃的", "慧苑有什么吃的");

        assertTrue(queries.stream().anyMatch(query -> query.contains("餐品") && query.contains("主食")
                && query.contains("前菜")));
    }

    @Test
    void categoryExpansionIgnoresIncidentalMentionsInTheRawQuestion() {
        // 用户原话里的“我不喝酒了”只是否定和顺带提及，品类扩展必须只看改写后的意图，
        // 否则酒类词表会被塞进查询，把“沙拉”这个真正的主题挤掉。
        List<String> queries = LocalKnowledgeQueryExpander.expand(
                "你给我弄点沙拉吧，我不喝酒了", "慧苑有什么沙拉");

        assertFalse(queries.stream().anyMatch(query -> query.contains("洋酒") || query.contains("葡萄酒")));
    }

    @Test
    void categoryExpansionIsSuppressedForNegatedDrinkRequests() {
        List<String> queries = LocalKnowledgeQueryExpander.expand("我不喝酒了", "慧苑有什么不喝酒的推荐");

        assertFalse(queries.stream().anyMatch(query -> query.contains("洋酒")));
    }

    @Test
    void keepsUnrelatedQuestionsUntouched() {
        List<String> queries = LocalKnowledgeQueryExpander.expand("慧苑演出几点开始", "慧苑演出几点开始");

        assertEquals(1, queries.size());
        assertEquals("慧苑演出几点开始", queries.get(0));
    }
}
