package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import xiaozhi.modules.knowledge.entity.KnowledgeChunkEntity;

class LocalKnowledgeLuceneIndexTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void springCreatesIndexUsingConfiguredRoot() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "localKnowledgeLuceneIndexTest",
                    Map.of("knowledge.local-index-root", temporaryDirectory.resolve("index").toString())));
            context.register(LocalKnowledgeLuceneIndex.class);
            context.refresh();

            assertNotNull(context.getBean(LocalKnowledgeLuceneIndex.class));
        }
    }

    @Test
    void retrievesStructuredRowsForNaturalLanguagePeopleAndMealQueries() {
        LocalKnowledgeLuceneIndex index = new LocalKnowledgeLuceneIndex(temporaryDirectory.resolve("index"));
        index.replaceDocument("dataset-1", "document-1", "慧苑套餐推荐知识库.xlsx", List.of(
                chunk("chunk-1", "套餐名称：单人小酌套餐\n适用人数：1人\n类别：酒水饮品"),
                chunk("chunk-2", "套餐名称：双人海鲜套餐\n适用人数：2人\n类别：菜品套餐")));

        List<LocalKnowledgeLuceneIndex.SearchHit> drinkHits = index.search(
                LocalKnowledgeQueryExpander.expand("我一个人喝点什么", "适合一人独饮的酒水推荐"),
                List.of("dataset-1"), null, 5);
        List<LocalKnowledgeLuceneIndex.SearchHit> mealHits = index.search(
                LocalKnowledgeQueryExpander.expand("我们两个人来吃饭", "两个人就餐的菜品推荐"),
                List.of("dataset-1"), null, 5);

        assertFalse(drinkHits.isEmpty());
        assertFalse(mealHits.isEmpty());
        assertEquals("chunk-1", drinkHits.get(0).chunk().getChunkId());
        assertEquals("chunk-2", mealHits.get(0).chunk().getChunkId());
        assertTrue(index.containsAll(List.of("dataset-1"), null));
        assertTrue(index.containsAll(List.of("dataset-1"), List.of("document-1")));
        assertFalse(index.containsAll(List.of("dataset-1"), List.of("document-missing")));
    }

    @Test
    void primaryStructuredNameOutranksIncidentalMentionsForProductPriceQuery() {
        LocalKnowledgeLuceneIndex index = new LocalKnowledgeLuceneIndex(temporaryDirectory.resolve("product-index"));
        index.replaceDocument("dataset-1", "document-1", "商品知识库.xlsx", List.of(
                chunk("chunk-pairing", "菜品名称：香辣鸡翅\n推荐搭配饮品：可乐"),
                chunk("chunk-dessert", "商品名称：桃胶五白饮\n食用建议：适合单独饮用\n售价：26元"),
                chunk("chunk-cola", "商品名称：可口可乐\n规格：单杯\n售价：15元")));

        List<LocalKnowledgeLuceneIndex.SearchHit> hits = index.search(
                LocalKnowledgeQueryExpander.expand(
                        "我就想喝一杯单独的可乐，多少钱", "可乐单独点单价格"),
                List.of("dataset-1"), null, 10);

        assertFalse(hits.isEmpty());
        assertEquals("chunk-cola", hits.get(0).chunk().getChunkId());

        List<LocalKnowledgeLuceneIndex.SearchHit> pairingHits = index.search(
                LocalKnowledgeQueryExpander.expand(
                        "推荐搭配可乐的菜", "适合搭配可乐的菜品"),
                List.of("dataset-1"), null, 10);
        assertFalse(pairingHits.isEmpty());
        assertEquals("chunk-pairing", pairingHits.get(0).chunk().getChunkId());
    }

    private KnowledgeChunkEntity chunk(String chunkId, String content) {
        KnowledgeChunkEntity chunk = new KnowledgeChunkEntity();
        chunk.setChunkId(chunkId);
        chunk.setContent(content);
        return chunk;
    }
}
