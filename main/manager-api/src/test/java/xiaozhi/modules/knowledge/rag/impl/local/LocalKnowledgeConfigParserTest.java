package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class LocalKnowledgeConfigParserTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesNestedRetrievalConfigFromMapAndJson() {
        LocalKnowledgeConfigParser mapParser = parser(Map.of(
                "retrieval_config", Map.of("semantic_top_k", "25", "rerank_enabled", "yes")));
        Map<String, Object> mapConfig = mapParser.getMap("retrieval_config");

        assertEquals(25, mapParser.getInt(mapConfig, "semantic_top_k", 10, 1, 50));
        assertTrue(mapParser.getBoolean(mapConfig, "rerank_enabled", false));

        LocalKnowledgeConfigParser jsonParser = parser(Map.of(
                "retrieval_config", "{\"mode\":\"exhibition\",\"semantic_threshold\":1.5}"));
        Map<String, Object> jsonConfig = jsonParser.getMap("retrieval_config");

        assertEquals("exhibition", jsonParser.getString(jsonConfig, "mode", "normal"));
        assertEquals(1.0F, jsonParser.getFloat(jsonConfig, "semantic_threshold", 0.05F, 0F, 1F));
    }

    @Test
    void preservesLegacyBooleanAliasesAndDefaults() {
        LocalKnowledgeConfigParser parser = parser(Map.of(
                "enabled", "on",
                "disabled", "0",
                "invalid", "sometimes"));

        assertTrue(parser.getBoolean("enabled", false));
        assertFalse(parser.getBoolean("disabled", true));
        assertTrue(parser.getBoolean("invalid", true));
        assertFalse(parser.getBoolean("missing", false));
    }

    @Test
    void clampsNumbersAndFallsBackForInvalidValues() {
        LocalKnowledgeConfigParser parser = parser(Map.of(
                "too_small", "-5",
                "too_large", 100,
                "invalid", "many"));

        assertEquals(1, parser.getInt("too_small", 10, 1, 50));
        assertEquals(50, parser.getInt("too_large", 10, 1, 50));
        assertEquals(10, parser.getInt("invalid", 10, 1, 50));
    }

    @Test
    void ignoresInvalidNestedJsonAndCopiesInputConfig() {
        Map<String, Object> source = new HashMap<>();
        source.put("mode", "normal");
        source.put("retrieval_config", "not-json");
        LocalKnowledgeConfigParser parser = parser(source);
        source.put("mode", "exhibition");

        assertTrue(parser.getMap("retrieval_config").isEmpty());
        assertEquals("normal", parser.getString("mode", "fallback"));
    }

    @Test
    void identifiesOnlyKnownPlaceholderSecrets() {
        assertTrue(LocalKnowledgeConfigParser.isPlaceholderSecret("your_api_key"));
        assertTrue(LocalKnowledgeConfigParser.isPlaceholderSecret("你的知识库 API Key"));
        assertFalse(LocalKnowledgeConfigParser.isPlaceholderSecret("configured-secret"));
    }

    private LocalKnowledgeConfigParser parser(Map<String, Object> config) {
        return new LocalKnowledgeConfigParser(config, objectMapper);
    }
}
