package xiaozhi.modules.memory.service.impl.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;

class MemoryMaintenanceOutputParserTest {

    private final MemoryMaintenanceOutputParser parser = new MemoryMaintenanceOutputParser();

    @Test
    void parsesFactsFromFencedJson() {
        JSONArray facts = parser.parseExtractionResult("""
                ```json
                {"facts":[{"text":"用户喜欢喝茶"}]}
                ```
                """);

        assertEquals(1, facts.size());
        assertEquals("用户喜欢喝茶", facts.getJSONObject(0).getStr("text"));
    }

    @Test
    void wrapsSingleExtractionAndActionObjects() {
        JSONArray facts = parser.parseExtractionResult("模型输出：{\"canonical_text\":\"用户住在上海\"}");
        JSONArray actions = parser.parseActionsResult("{\"action\":\"ADD\",\"canonical_text\":\"用户住在上海\"}");

        assertEquals(1, facts.size());
        assertEquals("用户住在上海", facts.getJSONObject(0).getStr("canonical_text"));
        assertEquals(1, actions.size());
        assertEquals("ADD", actions.getJSONObject(0).getStr("action"));
    }

    @Test
    void returnsNullForInvalidOrStructurallyUnrelatedOutput() {
        assertNull(parser.parseDecisionResult("not-json"));
        assertNull(parser.parseExtractionResult("{\"facts_missing\":[]}"));
        assertNull(parser.parseActionsResult("{\"message\":\"no action\"}"));
    }

    @Test
    void fillsMissingDecisionFieldsWithoutOverwritingModelDecision() {
        JSONObject dimensions = new JSONObject().set("relevance", 0.9);
        JSONObject extraction = new JSONObject()
                .set("text", "用户喜欢喝茶")
                .set("category", "偏好")
                .set("memory_type", "用户偏好")
                .set("source_role", "user")
                .set("evidence_quote", "我喜欢喝茶")
                .set("importance_dimensions", dimensions);
        JSONObject decision = new JSONObject()
                .set("action", "ADD")
                .set("category", "饮食偏好");

        parser.mergeExtractionIntoDecision(extraction, decision);

        assertEquals("用户喜欢喝茶", decision.getStr("canonical_text"));
        assertEquals("饮食偏好", decision.getStr("category"));
        assertEquals("用户偏好", decision.getStr("memory_type"));
        assertEquals("user", decision.getStr("source_role"));
        assertEquals("我喜欢喝茶", decision.getStr("evidence_quote"));
        assertEquals(0.9, decision.getJSONObject("importance_dimensions").getDouble("relevance"));
    }

    @Test
    void rememberDecisionAcceptsSingleActionWrapper() {
        JSONObject decision = parser.parseRememberDecision("""
                {"actions":[{"action":"UPDATE","related_memory_id":"fact-1",
                "canonical_text":"用户喜欢喝乌龙茶"}]}
                """, "用户喜欢喝茶");

        assertEquals("UPDATE", decision.getStr("action"));
        assertEquals("fact-1", decision.getStr("related_memory_id"));
        assertEquals("用户喜欢喝乌龙茶", decision.getStr("canonical_text"));
    }

    @Test
    void rememberDecisionCannotBeCancelledByInvalidMaintenanceOutput() {
        JSONObject none = parser.parseRememberDecision(
                "{\"action\":\"NONE\",\"reason\":\"不建议保存\"}", "用户喜欢喝茶");
        JSONObject invalid = parser.parseRememberDecision("not-json", "用户住在上海");

        assertEquals("ADD", none.getStr("action"));
        assertEquals("用户喜欢喝茶", none.getStr("canonical_text"));
        assertEquals("ADD", invalid.getStr("action"));
        assertEquals("用户住在上海", invalid.getStr("canonical_text"));
    }
}
