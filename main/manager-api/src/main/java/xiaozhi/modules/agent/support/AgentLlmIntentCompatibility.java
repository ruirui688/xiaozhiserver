package xiaozhi.modules.agent.support;

import java.util.Locale;
import java.util.Set;

import xiaozhi.modules.model.support.RealtimeAdapterCapabilityRegistry;

/** Provider capability gate for agent intent selections. */
public final class AgentLlmIntentCompatibility {
    private static final String FUNCTION_CALL_INTENT_ID = "Intent_function_call";
    private static final Set<String> NATIVE_FUNCTION_CALL_PROVIDERS = Set.of(
            "openai",
            "ollama");

    private AgentLlmIntentCompatibility() {
    }

    public static boolean supportsIntent(String providerType, String intentModelId) {
        if (!FUNCTION_CALL_INTENT_ID.equals(intentModelId)) {
            return true;
        }
        String normalizedType = providerType == null
                ? ""
                : providerType.trim().toLowerCase(Locale.ROOT);
        return NATIVE_FUNCTION_CALL_PROVIDERS.contains(normalizedType)
                || RealtimeAdapterCapabilityRegistry.supportsNativeFunctionCalling(normalizedType);
    }
}
