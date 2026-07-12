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
        if (decision.get("importance_dimensions") == null && extraction.get("importance_dimensions") != null) {
            decision.set("importance_dimensions", extraction.get("importance_dimensions"));
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

    private JSONArray singleton(JSONObject value) {
        JSONArray result = new JSONArray();
        result.add(value);
        return result;
    }
}
