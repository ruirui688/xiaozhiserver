package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.knowledge.entity.KnowledgeChunkEntity;

class LocalKnowledgeLexicalScorerTest {

    private final LocalKnowledgeLexicalScorer scorer = new LocalKnowledgeLexicalScorer(4);

    @Test
    void structuredProductRowsRemainDistinctDuringScoring() {
        KnowledgeChunkEntity cola = chunk("商品名称：可乐\n核心风味：甜、气泡、冰爽");
        KnowledgeChunkEntity sprite = chunk("商品名称：雪碧\n核心风味：清爽、柠檬味");
        Set<String> terms = scorer.tokenize("推荐一款柠檬味雪碧");

        assertTrue(scorer.score("推荐一款柠檬味雪碧", sprite, terms)
                > scorer.score("推荐一款柠檬味雪碧", cola, terms));
        assertEquals(0F, scorer.score("推荐一款柠檬味雪碧", cola, terms));
    }

    @Test
    void booleanQueryUsesOptionalTermsInsteadOfRequiringEveryTerm() {
        String query = scorer.buildFulltextQuery(List.of("雪碧", "柠檬味"));

        assertEquals("雪碧 柠檬味", query);
        assertFalse(query.contains("+"));
        assertNull(scorer.buildFulltextQuery(List.of()));
    }

    private KnowledgeChunkEntity chunk(String content) {
        KnowledgeChunkEntity chunk = new KnowledgeChunkEntity();
        chunk.setContent(content);
        return chunk;
    }
}
