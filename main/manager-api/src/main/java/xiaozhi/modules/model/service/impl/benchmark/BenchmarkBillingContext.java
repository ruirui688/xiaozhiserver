package xiaozhi.modules.model.service.impl.benchmark;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import xiaozhi.common.llm.LlmUsageContext;
import xiaozhi.modules.billing.service.BillingContextService;

/** Billing identity for tests remains platform-owned even when a real device is used. */
@Component
public class BenchmarkBillingContext {
    private final BillingContextService contexts;

    public BenchmarkBillingContext(BillingContextService contexts) {
        this.contexts = contexts;
    }

    public void attach(JSONObject payload, String purpose) {
        Set<String> modelIds = new LinkedHashSet<>();
        var current = LlmUsageContext.current();
        JSONArray models = payload.getJSONArray("models");
        if (models != null) {
            for (int index = 0; index < models.size(); index++) {
                JSONObject model = models.getJSONObject(index);
                if ("MODEL_TEST".equals(purpose) && current != null && current.modelId() != null) {
                    model.set("id", current.modelId());
                    models.set(index, model);
                }
                add(modelIds, model.getStr("id"));
            }
        }
        JSONObject judge = payload.getJSONObject("judge_config");
        if (judge != null) add(modelIds, judge.getStr("model_id"));
        JSONObject agent = payload.getJSONObject("agent_context");
        JSONObject runtime = agent == null ? null : agent.getJSONObject("runtime_config");
        if (runtime != null && runtime.get("model_config") instanceof Map<?, ?> configs)
            configs.keySet().forEach(id -> add(modelIds, String.valueOf(id)));
        if (modelIds.isEmpty()) return;
        Map<String, Object> context = contexts.issuePlatform(modelIds, purpose);
        payload.set("billing_context", context);
        payload.set("billing_purpose", purpose);
        if (runtime != null) {
            runtime.set("billing_context", context);
            runtime.set("_billing_purpose", purpose);
            agent.set("runtime_config", runtime);
            payload.set("agent_context", agent);
        }
    }

    private static void add(Set<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value);
    }
}
