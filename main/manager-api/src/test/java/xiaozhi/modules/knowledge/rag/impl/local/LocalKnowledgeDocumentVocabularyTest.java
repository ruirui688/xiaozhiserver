package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.knowledge.entity.KnowledgeChunkEntity;

class LocalKnowledgeDocumentVocabularyTest {
    private static KnowledgeChunkEntity chunk(String content) {
        var chunk = new KnowledgeChunkEntity();
        chunk.setContent(content);
        chunk.setEnabled(1);
        return chunk;
    }

    @Test
    void extractsTopicWordsFromNameLabelsAndCategoryValues() {
        Set<String> tokens = LocalKnowledgeDocumentVocabulary.tokens(List.of(
                chunk("来源：Sheet1 第2行\n酒水名称：雪碧\n品类：软饮\n规格：330ml\n售价：15"),
                chunk("来源：Sheet1 第3行\n酒水名称：干红葡萄酒\n品类：葡萄酒\n规格：750ml\n售价：88")));

        // 列头里的主题词（酒水名称 → 酒水）
        assertTrue(tokens.contains("酒水"), "词表应包含列头主题词，实际：" + tokens);
        // 品类取值（品类：葡萄酒）
        assertTrue(tokens.contains("软饮"));
        assertTrue(tokens.contains("葡萄酒"));
        // 通用列头不参与，否则任何价格提问都会把整张库存表拉进结果
        assertFalse(tokens.contains("售价"));
        assertFalse(tokens.contains("规格"));
    }

    @Test
    void extractsFoodCategoryValues() {
        Set<String> tokens = LocalKnowledgeDocumentVocabulary.tokens(List.of(
                chunk("来源：Sheet1 第4行\n餐品名称：M3西冷牛扒\n分类：主食\n售价/元：168"),
                chunk("来源：Sheet1 第5行\n餐品名称：杨枝甘露\n分类：甜品")));

        assertTrue(tokens.contains("餐品"));
        assertTrue(tokens.contains("主食"));
        assertTrue(tokens.contains("甜品"));
    }

    @Test
    void toleratesMissingAndEmptyContent() {
        assertTrue(LocalKnowledgeDocumentVocabulary.tokens(null).isEmpty());
        assertTrue(LocalKnowledgeDocumentVocabulary.tokens(List.of()).isEmpty());
        assertTrue(LocalKnowledgeDocumentVocabulary.tokens(List.of(chunk(null))).isEmpty());
        assertTrue(LocalKnowledgeDocumentVocabulary.tokens(List.of(chunk("普通正文，没有结构化列头。"))).isEmpty());
    }
}
