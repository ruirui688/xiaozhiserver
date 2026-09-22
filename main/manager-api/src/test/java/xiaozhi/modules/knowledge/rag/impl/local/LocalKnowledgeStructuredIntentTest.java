package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LocalKnowledgeStructuredIntentTest {
    private static final String PACKAGE_ROW =
            "来源：套餐明细 第8行\n套餐名称：慧苑VIP4人套餐\n栏目：前菜\n数量规则：2/2\n可选菜品/内容：中式卤牛腱×1";

    @Test
    void prefixThatCoversOnlyASmallPartOfTheEntityNameIsNotAnEntityMatch() {
        // “慧苑”只占“慧苑VIP4人套餐”的一小部分，却会同时命中同库十几行表格数据。
        assertEquals(0, LocalKnowledgeStructuredIntent.primaryEntityMatch("慧苑演出几点开始", PACKAGE_ROW));
        assertEquals(0, LocalKnowledgeStructuredIntent.primaryEntityMatch("慧苑民宿一共有多少间客房", PACKAGE_ROW));
        assertEquals(0, LocalKnowledgeStructuredIntent.primaryEntityMatch("慧苑拿铁", PACKAGE_ROW));
    }

    @Test
    void entityMatchStillCoversMostOfTheEntityName() {
        assertTrue(LocalKnowledgeStructuredIntent.primaryEntityMatch("慧苑VIP4人套餐都有什么菜", PACKAGE_ROW) >= 4);
        assertTrue(LocalKnowledgeStructuredIntent.primaryEntityMatch("VIP4人套餐多少钱", PACKAGE_ROW) >= 4);
        // 覆盖名称一半的实体片段仍然保留，既有单品问法不回退。
        assertEquals(2, LocalKnowledgeStructuredIntent.primaryEntityMatch(
                "我就想喝一杯单独的可乐，可乐多少钱", "商品名称：可口可乐；规格：单杯；售价：15元"));
    }

    @Test
    void singleCharacterOverlapIsNotAnEntityMatch() {
        assertEquals(0, LocalKnowledgeStructuredIntent.primaryEntityMatch("最便宜的是什么", "名称：甲；售价：88元"));
    }

    @Test
    void auxiliaryRelationshipQueriesNeverCountAsEntityMatch() {
        assertEquals(0, LocalKnowledgeStructuredIntent.primaryEntityMatch(
                "推荐搭配可乐的菜", "商品名称：可口可乐；规格：单杯；售价：15元"));
    }
}
