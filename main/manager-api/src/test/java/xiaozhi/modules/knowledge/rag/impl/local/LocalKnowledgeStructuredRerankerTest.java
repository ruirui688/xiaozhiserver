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

    private record Row(String content, float score) {
    }
}
