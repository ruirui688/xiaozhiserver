package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class LocalKnowledgeStructuredRerankerTest {
    @Test
    void ordersExplicitLowestAndHighestPriceRequestsWithoutDomainSpecificProductRules() {
        List<Row> rows = List.of(
                new Row("名称：甲；售价：88元", 0.9F),
                new Row("名称：乙；价格：18.5元", 0.5F),
                new Row("名称：丙；单价：36元", 0.8F));

        List<Row> lowest = new ArrayList<>(rows);
        lowest.sort(LocalKnowledgeStructuredReranker.comparator(
                "最便宜的是什么", Row::content, Row::score));
        List<Row> highest = new ArrayList<>(rows);
        highest.sort(LocalKnowledgeStructuredReranker.comparator(
                "价格最高的是什么", Row::content, Row::score));

        assertEquals("名称：乙；价格：18.5元", lowest.get(0).content());
        assertEquals("名称：甲；售价：88元", highest.get(0).content());
    }

    @Test
    void directStructuredEntityBeatsIncidentalPairingForAttributeQueries() {
        List<Row> rows = new ArrayList<>(List.of(
                new Row("菜品名称：香辣鸡翅；推荐搭配饮品：可乐", 0.91F),
                new Row("商品名称：桃胶五白饮；食用建议：适合单独饮用；售价：26元", 0.88F),
                new Row("商品名称：可口可乐；规格：单杯；售价：15元", 0.24F)));

        rows.sort(LocalKnowledgeStructuredReranker.comparator(
                "我就想喝一杯单独的可乐，可乐多少钱", Row::content, Row::score));

        assertEquals("商品名称：可口可乐；规格：单杯；售价：15元", rows.get(0).content());
    }

    @Test
    void explicitPairingQuestionKeepsRelationshipResultsAheadOfProductRecord() {
        List<Row> rows = new ArrayList<>(List.of(
                new Row("菜品名称：香辣鸡翅；推荐搭配饮品：可乐", 0.91F),
                new Row("商品名称：可口可乐；规格：单杯；售价：15元", 0.60F)));

        rows.sort(LocalKnowledgeStructuredReranker.comparator(
                "推荐搭配可乐的菜", Row::content, Row::score));

        assertEquals("菜品名称：香辣鸡翅；推荐搭配饮品：可乐", rows.get(0).content());
    }

    private record Row(String content, float score) {
    }
}
