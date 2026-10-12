package xiaozhi.modules.model.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import xiaozhi.modules.model.dto.ModelProviderDTO;

/** Static catalog facts, never a claim that a connected device registered a tool. */
public final class PluginCatalogContract {
    private static final Set<String> RETIRED = Set.of("set_session_voice", "manage_memory");
    private static final Set<String> ACTIVITIES = Set.of(
            "idiom_chain", "riddle", "standup", "crosstalk", "guess_what_i_say",
            "xiaozhi_thinks_you_guess", "guess_xiaozhi_word", "i_think_you_guess");
    private static final Set<String> BUILTINS = Set.of(
            "handle_exit_intent", "get_lunar", "reset_conversation_context", "end_interaction");
    private static final Set<String> CATALOG_FIELDS = Set.of(
            "description", "custom_plugin", "catalog_only", "runtime_builtin",
            "tool_source", "activation_hint", "parameters_schema");
    private static final List<String> PROMPT_FIELDS = List.of("instructions", "opening_message");

    private PluginCatalogContract() { }

    public static boolean isRetired(String code) {
        return RETIRED.contains(code == null ? "" : code);
    }

    public static Map<String, Object> defaults(String fieldsJson) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (fieldsJson == null || fieldsJson.isBlank()) {
            return result;
        }
        try {
            for (Object value : JSONUtil.parseArray(fieldsJson)) {
                if (value instanceof JSONObject field && field.containsKey("default")) {
                    String key = field.getStr("key");
                    if (key != null) result.put(key, field.get("default"));
                }
            }
        } catch (RuntimeException ignored) {
            // Invalid historical rows must not inject unparsed metadata.
        }
        return result;
    }

    public static boolean flag(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    public static ModelProviderDTO decorate(ModelProviderDTO provider) {
        if (provider == null || !"Plugin".equals(provider.getModelType())) return provider;
        Map<String, Object> fields = defaults(provider.getFields());
        String code = provider.getProviderCode();
        boolean retired = isRetired(code);
        boolean routing = "direct_answer".equals(code);
        boolean activity = ACTIVITIES.contains(code == null ? "" : code) || flag(fields.get("custom_plugin"));
        boolean builtin = BUILTINS.contains(code == null ? "" : code);
        String activation = retired ? "unavailable" : routing || builtin ? "runtime"
                : "change_role".equals(code) ? "intent_allowlist"
                : flag(fields.get("catalog_only")) ? "binding" : "agent_selection";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("lifecycle", retired ? "retired" : "active");
        metadata.put("capabilityType", activity ? "activity" : routing ? "routing" : "tool");
        metadata.put("injectionMode", retired ? "none" : activity ? "on_demand_activity"
                : routing ? "routing_schema" : "tool_schema");
        metadata.put("activationMode", activation);
        metadata.put("modelPaths", retired ? List.of() : routing ? List.of("function_call")
                : List.of("function_call", "intent_llm", "qwen_realtime", "doubao_duplex"));
        metadata.put("catalogScope", "static");
        metadata.put("runtimeAvailability", "not_evaluated");
        metadata.put("selectable", !retired && !routing && !builtin && !flag(fields.get("catalog_only")));
        metadata.put("inheritablePromptFields", activity ? PROMPT_FIELDS : List.of());
        provider.setCatalogMetadata(metadata);
        if (retired) {
            // Compatibility clients must not display the historical 'always available' flag.
            JSONArray definitions = new JSONArray();
            try { definitions = JSONUtil.parseArray(provider.getFields()); } catch (RuntimeException ignored) { }
            for (Object value : definitions) {
                if (value instanceof JSONObject field && "runtime_builtin".equals(field.getStr("key"))) {
                    field.set("default", false);
                }
            }
            provider.setFields(definitions.toString());
        }
        return provider;
    }

    public static List<ModelProviderDTO> decorate(List<ModelProviderDTO> providers) {
        if (providers == null) return List.of();
        providers.forEach(PluginCatalogContract::decorate);
        return providers;
    }

    /** Missing prompt fields inherit; present fields (including empty strings) override. */
    public static Map<String, Object> runtimeParams(String fieldsJson, Map<String, Object> roleParams) {
        Map<String, Object> defaults = defaults(fieldsJson);
        Map<String, Object> result = roleParams(roleParams, fieldsJson);
        for (String key : PROMPT_FIELDS) {
            if (!result.containsKey(key) && defaults.containsKey(key)) result.put(key, defaults.get(key));
        }
        for (String key : CATALOG_FIELDS) {
            if (!"description".equals(key) && defaults.containsKey(key)) result.put(key, defaults.get(key));
        }
        return result;
    }

    /** Catalog metadata and tool descriptions are not role-specific settings. */
    public static Map<String, Object> roleParams(Map<String, Object> submitted) {
        return roleParams(submitted, null);
    }

    public static Map<String, Object> roleParams(Map<String, Object> submitted, String fieldsJson) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (submitted != null) result.putAll(submitted);
        Map<String, Object> defaults = defaults(fieldsJson);
        // Old declarative activities used description as rules when neither
        // role nor catalog supplied instructions. Preserve only that fallback
        // as an explicit role rule; description itself remains catalog-owned.
        if (!result.containsKey("instructions") && !defaults.containsKey("instructions")
                && (flag(defaults.get("custom_plugin")) || flag(result.get("custom_plugin")))
                && result.get("description") instanceof String legacy && !legacy.isBlank()) {
            result.put("instructions", legacy);
        }
        CATALOG_FIELDS.forEach(result::remove);
        result.remove("catalogMetadata");
        return result;
    }
}
