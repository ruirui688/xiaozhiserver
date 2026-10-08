package xiaozhi.modules.model.support;

import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Central capability metadata for every supported end-to-end Realtime adapter. */
public final class RealtimeAdapterCapabilityRegistry {
    public record Capabilities(
            boolean nativeFunctionCalling,
            boolean naturalBargeIn,
            boolean contextReset,
            boolean sessionVoice,
            boolean customVoiceIds,
            String defaultVoice,
            List<String> voiceOptions) {
    }

    private static final Map<String, Capabilities> CAPABILITIES = Map.of(
            "qwen_omni_realtime", new Capabilities(
                    true, true, true, true, false, "Ethan",
                    List.of("Cherry", "Serena", "Ethan", "Chelsie")),
            "doubao_duplex", new Capabilities(
                    true, true, true, true, true, "zh_female_vv_jupiter_bigtts",
                    List.of("zh_female_vv_jupiter_bigtts")),
            "doubao_realtime", new Capabilities(
                    false, true, true, true, true, "zh_female_vv_jupiter_bigtts",
                    List.of("zh_female_vv_jupiter_bigtts")));

    private RealtimeAdapterCapabilityRegistry() {
    }

    public static Optional<Capabilities> find(String adapterCode) {
        return Optional.ofNullable(CAPABILITIES.get(normalize(adapterCode)));
    }

    public static boolean supportsNativeFunctionCalling(String adapterCode) {
        return find(adapterCode).map(Capabilities::nativeFunctionCalling).orElse(false);
    }

    public static boolean supportsSessionVoice(String adapterCode) {
        return find(adapterCode).map(Capabilities::sessionVoice).orElse(false);
    }

    public static boolean isVoiceAllowed(String adapterCode, String voice) {
        if (voice == null || voice.isBlank()) {
            return true;
        }
        return find(adapterCode)
                .filter(Capabilities::sessionVoice)
                .map(capabilities -> capabilities.customVoiceIds()
                        || capabilities.voiceOptions().contains(voice.trim()))
                .orElse(false);
    }

    private static String normalize(String adapterCode) {
        return adapterCode == null
                ? ""
                : adapterCode.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
