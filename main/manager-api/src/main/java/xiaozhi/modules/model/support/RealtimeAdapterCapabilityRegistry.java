package xiaozhi.modules.model.support;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Central capability metadata for every supported end-to-end Realtime adapter. */
public final class RealtimeAdapterCapabilityRegistry {
    public record Capabilities(
            boolean nativeFunctionCalling,
            boolean naturalBargeIn,
            boolean contextReset,
            boolean sessionVoice,
            boolean customVoiceIds) {
    }

    private static final Map<String, Capabilities> CAPABILITIES = Map.of(
            "qwen_omni_realtime", new Capabilities(true, true, true, true, false),
            "doubao_realtime", new Capabilities(false, true, true, true, true));

    private RealtimeAdapterCapabilityRegistry() {
    }

    public static Optional<Capabilities> find(String adapterCode) {
        return Optional.ofNullable(CAPABILITIES.get(normalize(adapterCode)));
    }

    public static boolean supportsNativeFunctionCalling(String adapterCode) {
        return find(adapterCode).map(Capabilities::nativeFunctionCalling).orElse(false);
    }

    private static String normalize(String adapterCode) {
        return adapterCode == null
                ? ""
                : adapterCode.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
