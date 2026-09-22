package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xiaozhi.modules.knowledge.dao.DocumentDao;
import xiaozhi.modules.knowledge.dao.KnowledgeChunkDao;
import xiaozhi.modules.knowledge.dto.document.RetrievalDTO;
import xiaozhi.modules.knowledge.entity.DocumentEntity;
import xiaozhi.modules.knowledge.entity.KnowledgeChunkEntity;

/**
 * 线上“慧苑”知识库召回跑偏的自我匹配回归（2026-09-22）。
 *
 * <p>同一个知识库里既有带“套餐名称”主实体字段的表格切片，也有不含主实体字段的问答切片。
 * 用户问题带库名/品牌前缀（“慧苑”）时，命中实体字段的表格行不能把真正回答问题的切片挤出召回窗口。
 * 语料规模与线上一致（约 327 条切片，其中 15 行套餐明细、仅 15 条正文含“慧苑”），
 * 否则“慧苑”的 IDF 会被放大成噪声，无法复现线上排名。
 */
class LocalKnowledgeRecallRankingTest {
    private static final String DATASET = "dataset-huiyuan";
    private static final int RESULT_LIMIT = 10;
    private static final int FILLER_ROWS = 310;

    @TempDir Path directory;

    private final List<KnowledgeChunkEntity> rows = new ArrayList<>();
    private final List<DocumentEntity> documents = new ArrayList<>();

    @Test
    void brandedQuestionReturnsTheAnswerChunkInsteadOfPackageTableRows() {
        huiyuanKnowledgeBase();

        List<RetrievalDTO.HitVO> hits = retrieve("慧苑演出几点开始");

        assertEquals(RESULT_LIMIT, hits.size());
        assertTrue(hits.get(0).getContent().contains("19:30"),
                "“慧苑演出几点开始”的第一条必须是演出时间切片，实际：" + hits.get(0).getContent());
        assertTrue(hits.stream().anyMatch(hit -> "慧苑问答知识库_民宿消费演出咖啡.xlsx".equals(hit.getDocumentName())));
    }

    @Test
    void brandPrefixDoesNotDecideBetweenTopics() {
        huiyuanKnowledgeBase();

        // 同一事实，加不加库名前缀都必须命中同一条问答切片。
        assertTrue(retrieve("慧苑民宿几点退房").get(0).getContent().contains("下午2点前退房"));
        assertTrue(retrieve("退房时间").get(0).getContent().contains("下午2点前退房"));
        assertTrue(retrieve("慧苑最低消费").get(0).getContent().contains("没有最低消费"));
        assertTrue(retrieve("慧苑拿铁").get(0).getContent().contains("浓缩咖啡"));
    }

    @Test
    void genuinePackageQuestionStillPrefersThePackageRecord() {
        huiyuanKnowledgeBase();

        List<RetrievalDTO.HitVO> hits = retrieve("慧苑VIP4人套餐都有什么菜");

        assertTrue(hits.get(0).getContent().contains("套餐名称：慧苑VIP4人套餐"),
                "具体套餐问法仍应优先返回该套餐记录，实际：" + hits.get(0).getContent());
    }

    @Test
    void categoryQuestionStillReachesTheInventoryDocumentWithBrandPrefix() {
        inventoryKnowledgeBase();

        List<RetrievalDTO.HitVO> hits = retrieve("慧苑有什么酒水");
        List<String> topFiveDocs = hits.stream().limit(5)
                .map(RetrievalDTO.HitVO::getDocumentName).toList();

        // “酒水库存明细表”正文里没有品牌词，必须靠“文档级词表（酒水名称→酒水、品类→洋酒/软饮）”
        // 加全库级品牌词降权才能进窗口；这条用例锁定该行为。
        assertTrue(topFiveDocs.contains("酒水库存明细表.xlsx"),
                "带品牌的品类问法必须召回酒水库存表，实际 Top5：" + topFiveDocs);
    }

    @Test
    void entityPriorityIsDisabledOnceEntityRowsWouldFillTheWholeWindow() {
        List<KnowledgeChunkEntity> sameEntityRows = new ArrayList<>();
        for (int index = 0; index < RESULT_LIMIT; index++) {
            sameEntityRows.add(chunkOf("商品名称：可口可乐\n规格：单杯\n售价：" + (10 + index) + "元"));
        }

        assertFalse(LocalKnowledgeRetrievalEngine.entityMatchDiscriminates(
                "可口可乐多少钱", sameEntityRows, RESULT_LIMIT),
                "同一实体的行数已占满结果窗口，实体优先必须让位给相关度");
        assertTrue(LocalKnowledgeRetrievalEngine.entityMatchDiscriminates(
                "可口可乐多少钱", sameEntityRows.subList(0, RESULT_LIMIT - 1), RESULT_LIMIT));
    }

    private List<RetrievalDTO.HitVO> retrieve(String question) {
        var chunkDao = mock(KnowledgeChunkDao.class);
        var documentDao = mock(DocumentDao.class);
        when(documentDao.selectList(any())).thenAnswer(call -> documents);
        when(chunkDao.selectList(any())).thenAnswer(call -> rows);
        when(chunkDao.selectIndexableChunks(anyList(), anyList())).thenAnswer(call -> {
            List<String> documentIds = call.getArgument(1);
            return rows.stream().filter(row -> documentIds.contains(row.getDocumentId())).toList();
        });
        var request = new RetrievalDTO.TestReq();
        request.setDatasetIds(List.of(DATASET));
        request.setQuestion(question);
        request.setRawQuestion(question);
        var engine = new LocalKnowledgeRetrievalEngine(chunkDao, documentDao,
                new LocalKnowledgeLuceneIndex(directory.resolve("index")));
        return engine.retrieve(request,
                new LocalKnowledgeRetrievalEngine.Options(4, RESULT_LIMIT, 200, 0.05F)).getChunks();
    }

    /** 按线上慧苑知识库的形状构造：15 行带“套餐名称”的套餐明细 + 2 条问答切片 + 同规模无关切片。 */
    private void huiyuanKnowledgeBase() {
        packageRow("慧苑VIP4人套餐", "前菜", "中式卤牛腱×1 / 香料烤翅×1");
        packageRow("慧苑VIP4人套餐", "汤水", "中式时令例汤×4");
        packageRow("慧苑VIP4人套餐", "主食", "台式鸡丁饭 / 牛肋条黑松露炒饭");
        packageRow("慧苑VIP4人套餐", "水果盘", "水果拼盘×1");
        packageRow("慧苑VIP4人套餐", "小吃", "日式香草炸鸡×1");
        packageRow("慧苑单人餐", "汤品", "中式时令例汤×1");
        packageRow("慧苑单人餐", "主食", "港风春笋卤肉饭（四选一）");
        packageRow("慧苑单人餐", "小吃", "香茅炸鸡×1");
        packageRow("慧苑单人餐", "饮品", "鲜榨橙汁×1");
        packageRow("慧苑单人餐", "甜品", "杨枝甘露×1");
        packageRow("慧苑三人套餐", "小吃", "日式香草炸鸡×1");
        packageRow("慧苑三人套餐", "汤品", "法式南瓜汤×2");
        packageRow("慧苑三人套餐", "主食", "牛肋条黑松露炒饭×2");
        packageRow("慧苑三人套餐", "前菜", "中式卤牛腱×1");
        packageRow("慧苑三人套餐", "饮品", "鲜榨果汁×3");
        qaRow("来源：问答知识库 第10行\n分类：演出活动\n主题：演出时间\n标准问题：慧苑演出几点开始？\n"
                + "标准回答：节目通常在晚上7:30开始，具体时间可能根据当天现场情况有所调整。\n"
                + "关键词：演出、节目、晚上7点半、19:30");
        qaRow("来源：问答知识库 第18行\n分类：民宿\n主题：退房时间\n标准问题：慧苑民宿几点退房？\n"
                + "标准回答：可在第二天下午2点前退房。\n关键词：退房、14:00、下午2点、民宿");
        qaRow("来源：问答知识库 第7行\n分类：消费规则\n主题：最低消费\n标准问题：慧苑最低消费是多少？\n"
                + "标准回答：目前没有最低消费要求，入座后可以根据需要点饮品或小食。");
        qaRow("来源：问答知识库 第4行\n分类：咖啡\n主题：拿铁组成\n标准问题：慧苑的拿铁咖啡主要由哪两种原料搭配而成？\n"
                + "标准回答：慧苑拿铁由浓缩咖啡搭配绵密牛奶制成。");
        fillerRows();
    }

    private void fillerRows() {
        String[] documents = {"酒水库存明细表.xlsx", "门店排班表.xlsx", "供应商报价单.xlsx"};
        for (int index = 0; index < FILLER_ROWS; index++) {
            addRow("doc-filler-" + (index % documents.length), documents[index % documents.length],
                    "来源：库存明细 第" + index + "行\n商品名称：青梅酒" + index + "\n规格：500ml\n库存："
                            + (index + 3) + "瓶\n备注：常温存放");
        }
    }

    private void packageRow(String packageName, String section, String content) {
        addRow("doc-package", "慧苑套餐推荐知识库.xlsx",
                "来源：套餐明细 第" + (rows.size() + 1) + "行\n套餐名称：" + packageName + "\n栏目：" + section
                        + "\n数量规则：1/1\n可选菜品/内容：" + content);
    }

    private void qaRow(String content) {
        addRow("doc-qa", "慧苑问答知识库_民宿消费演出咖啡.xlsx", content);
    }

    /**
     * 线上形状的知识库：一张没有品牌词的酒水库存表 + 两个名字带品牌的文档。
     * 品牌出现在多数文档名里，必须被判为全库级词降权，否则库存表永远进不了窗口。
     */
    private void inventoryKnowledgeBase() {
        String[] drinks = {"雪碧", "红牛", "屈臣氏汤力汽水", "干红葡萄酒", "精酿啤酒", "青梅酒"};
        String[] categories = {"软饮", "软饮", "软饮", "葡萄酒", "啤酒", "洋酒"};
        for (int index = 0; index < 24; index++) {
            addRow("doc-drinks", "酒水库存明细表.xlsx",
                    "来源：Sheet1 第" + (index + 2) + "行\n酒水名称：" + drinks[index % drinks.length]
                            + "\n品类：" + categories[index % categories.length]
                            + "\n规格：330ml\n售价：" + (10 + index));
        }
        for (int index = 0; index < 18; index++) {
            addRow("doc-brand-qa", "慧苑问答知识库.xlsx",
                    "来源：问答知识库 第" + (index + 2) + "行\n分类：酒水\n主题：特调酒款与价格\n"
                            + "标准问题：慧苑有哪些特调酒？分别多少钱？\n"
                            + "标准回答：当前资料未提供具体特调酒款、价格和容量信息" + index + "。");
        }
        for (int index = 0; index < 14; index++) {
            addRow("doc-brand-intro", "慧苑公司介绍.xlsx",
                    "来源：公司知识库切片 第" + (index + 2) + "行\n切片ID：HY-KB-0" + index
                            + "\n分类：业态介绍/慧苑居\n主题：慧苑居科技民宿定位\n"
                            + "核心介绍：慧苑居是慧苑的智能居住业态，主打古城加科技的住宿体验" + index + "。");
        }
    }

    private void addRow(String documentId, String documentName, String content) {
        if (documents.stream().noneMatch(document -> document.getDocumentId().equals(documentId))) {
            var document = new DocumentEntity();
            document.setId(documentId + "-row");
            document.setDatasetId(DATASET);
            document.setDocumentId(documentId);
            document.setName(documentName);
            document.setEnabled(1);
            document.setRun("DONE");
            document.setContentVersion(0L);
            documents.add(document);
        }
        var chunk = new KnowledgeChunkEntity();
        chunk.setId(documentId + "-row-" + rows.size());
        chunk.setChunkId(documentId + "-chunk-" + rows.size());
        chunk.setDatasetId(DATASET);
        chunk.setDocumentId(documentId);
        chunk.setContent(content);
        chunk.setEnabled(1);
        rows.add(chunk);
    }

    private static KnowledgeChunkEntity chunkOf(String content) {
        var chunk = new KnowledgeChunkEntity();
        chunk.setId("row");
        chunk.setChunkId("chunk");
        chunk.setDatasetId(DATASET);
        chunk.setDocumentId("document");
        chunk.setContent(content);
        chunk.setEnabled(1);
        return chunk;
    }
}
