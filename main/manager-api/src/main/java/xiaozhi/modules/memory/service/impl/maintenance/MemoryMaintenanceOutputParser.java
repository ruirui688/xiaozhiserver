package xiaozhi.modules.memory.service.impl.maintenance;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.modules.memory.utils.MemoryHelper;

/**
 * Parses and normalizes structured output returned by the memory maintenance LLM.
 */
@Component
@Slf4j
public class MemoryMaintenanceOutputParser {

    public JSONArray parseExtractionResult(String output) {
        JSONObject root = parseObject(output, "Stage 1 提取结果");
        if (root == null) {
            return null;
        }
        JSONArray facts = root.getJSONArray("facts");
        if (facts != null) {
            return facts;
        }
        if (StringUtils.isNotBlank(root.getStr("text"))
                || StringUtils.isNotBlank(root.getStr("canonical_text"))) {
            return singleton(root);
        }
        return null;
    }

    public JSONObject parseDecisionResult(String output) {
        return parseObject(output, "Stage 2 决策结果");
    }

    /**
     * The remember decision is authoritative upstream: maintenance may structure it
     * as ADD/UPDATE, but must not turn it into a no-op. Some providers also wrap a
     * single decision in the actions array used by the batch prompts.
     */
    public JSONObject parseRememberDecision(String output, String instructionText) {
        JSONObject root = parseObject(output, "显式记住决策");
        JSONObject decision = unwrapSingleAction(root);
        if (decision == null) {
            decision = new JSONObject();
        }
        String action = MemoryHelper.normalizeAction(decision.getStr("action"));
        if (!"ADD".equals(action) && !"UPDATE".equals(action)) {
            decision.set("action", "ADD");
            decision.remove("related_memory_id");
        }
        if (StringUtils.isBlank(decision.getStr("canonical_text"))) {
            decision.set("canonical_text", StringUtils.trimToEmpty(instructionText));
        }
        return decision;
    }

    public JSONArray parseActionsResult(String output) {
        JSONObject root = parseObject(output, "维护动作列表");
        if (root == null) {
            return null;
        }
        JSONArray actions = root.getJSONArray("actions");
        if (actions != null) {
            return actions;
        }
        return StringUtils.isNotBlank(root.getStr("action")) ? singleton(root) : null;
    }

    public void mergeExtractionIntoDecision(JSONObject extraction, JSONObject decision) {
        if (StringUtils.isBlank(decision.getStr("canonical_text"))) {
            decision.set("canonical_text", extraction.getStr("text", extraction.getStr("canonical_text")));
        }
        if (StringUtils.isBlank(decision.getStr("category"))) {
            decision.set("category", extraction.getStr("category"));
        }
        if (StringUtils.isBlank(decision.getStr("memory_type"))) {
            decision.set("memory_type", extraction.getStr("memory_type"));
        }
        if (StringUtils.isBlank(decision.getStr("speaker_id"))) {
            decision.set("speaker_id", extraction.getStr("speaker_id"));
        }
        if (decision.get("importance_dimensions") == null && extraction.get("importance_dimensions") != null) {
            decision.set("importance_dimensions", extraction.get("importance_dimensions"));
        }
        copyIfBlank(extraction, decision, "source_role");
        copyIfBlank(extraction, decision, "evidence_source_role");
        copyIfBlank(extraction, decision, "evidence_quote");
        if (decision.get("evidence") == null && extraction.get("evidence") != null) {
            decision.set("evidence", extraction.get("evidence"));
        }
    }

    private void copyIfBlank(JSONObject source, JSONObject target, String key) {
        if (StringUtils.isBlank(target.getStr(key)) && StringUtils.isNotBlank(source.getStr(key))) {
            target.set(key, source.getStr(key));
        }
    }

    private JSONObject parseObject(String output, String resultType) {
        String jsonText = MemoryHelper.extractJsonObject(output);
        if (StringUtils.isBlank(jsonText)) {
            return null;
        }
        try {
            return JSONUtil.parseObj(jsonText);
        } catch (Exception e) {
            log.warn("解析{}失败: {}", resultType, e.getMessage());
            return null;
        }
    }

    private JSONObject unwrapSingleAction(JSONObject root) {
        if (root == null) {
            return null;
        }
        if (StringUtils.isNotBlank(root.getStr("action"))) {
            return root;
        }
        JSONArray actions = root.getJSONArray("actions");
        if (actions == null || actions.size() != 1) {
            return root;
        }
        return MemoryHelper.toJsonObject(actions.get(0));
    }

    private JSONArray singleton(JSONObject value) {
        JSONArray result = new JSONArray();
        result.add(value);
        return result;
    }
}
