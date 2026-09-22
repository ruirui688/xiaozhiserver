package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
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

    /**
     * 线上索引同时装着多个知识库。判定“全库级词”（品牌名）必须只看本次检索的数据集，
     * 否则大库会把小库的品牌词比例稀释掉，品牌词又会把主题表挤出结果窗口。
     *
     * <p>这里刻意在一个索引里放两个数据集：目标库 54 条（品牌文档 30 条 + 库存表 24 条），
     * 另一个大库 500 条。按整个索引统计时品牌词只占约 5%，判定失效；按数据集统计才成立。
     */
    @Test
    void libraryLevelBrandTermsAreJudgedWithinTheQueriedDataset() {
        LocalKnowledgeLuceneIndex index = new LocalKnowledgeLuceneIndex(temporaryDirectory.resolve("multi-index"));
        List<KnowledgeChunkEntity> brandChunks = new ArrayList<>();
        for (int position = 0; position < 30; position++) {
            brandChunks.add(chunk("brand-" + position,
                    "来源：问答知识库 第" + position + "行\n分类：业态介绍/慧苑居\n"
                            + "标准问题：慧苑民宿第" + position + "项说明是什么？\n"
                            + "标准回答：慧苑居的说明" + position + "，请以慧苑现场为准。"));
        }
        index.replaceDocument("brand-dataset", "doc-brand", "慧苑问答知识库.xlsx", brandChunks, 0L);

        List<KnowledgeChunkEntity> drinkChunks = new ArrayList<>();
        for (int position = 0; position < 24; position++) {
            drinkChunks.add(chunk("drink-" + position,
                    "来源：Sheet1 第" + position + "行\n酒水名称：饮品" + position + "\n品类：软饮\n售价：" + (10 + position)));
        }
        index.replaceDocument("brand-dataset", "doc-drinks", "酒水库存明细表.xlsx", drinkChunks, 0L);

        List<KnowledgeChunkEntity> otherChunks = new ArrayList<>();
        for (int position = 0; position < 500; position++) {
            otherChunks.add(chunk("other-" + position, "游戏公告第" + position + "条：版本更新说明。"));
        }
        index.replaceDocument("other-dataset", "doc-other", "游戏公告.xlsx", otherChunks, 0L);

        List<LocalKnowledgeLuceneIndex.SearchHit> hits = index.search(
                List.of("慧苑 酒水"), List.of("brand-dataset"), null, 10);

        assertFalse(hits.isEmpty());
        assertEquals("doc-drinks", hits.get(0).chunk().getDocumentId());
        assertTrue(hits.stream().noneMatch(hit -> "doc-brand".equals(hit.chunk().getDocumentId())),
                "品牌词必须按数据集范围判定为全库级词，只剩主题词的品牌切片不应再占窗口");
    }

    private KnowledgeChunkEntity chunk(String chunkId, String content) {
        KnowledgeChunkEntity chunk = new KnowledgeChunkEntity();
        chunk.setChunkId(chunkId);
        chunk.setContent(content);
        return chunk;
    }
}
