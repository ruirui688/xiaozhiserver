package xiaozhi.modules.model.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RealtimeAdapterCapabilityRegistryTest {

    @Test
    void capabilitiesAreAdapterDrivenAndAliasesAreNormalized() {
        assertTrue(RealtimeAdapterCapabilityRegistry.find("qwen-omni-realtime").orElseThrow().naturalBargeIn());
        assertTrue(RealtimeAdapterCapabilityRegistry.find("doubao-realtime").orElseThrow().contextReset());
        assertTrue(RealtimeAdapterCapabilityRegistry.find("doubao_realtime").orElseThrow().sessionVoice());
        assertTrue(RealtimeAdapterCapabilityRegistry.find("doubao_realtime").orElseThrow().customVoiceIds());
        assertFalse(RealtimeAdapterCapabilityRegistry.find("doubao_realtime").orElseThrow().nativeFunctionCalling());
        assertFalse(RealtimeAdapterCapabilityRegistry.find("qwen_realtime").isPresent());
        assertEquals("Ethan", RealtimeAdapterCapabilityRegistry.find("qwen_omni_realtime")
                .orElseThrow().defaultVoice());
        assertTrue(RealtimeAdapterCapabilityRegistry.isVoiceAllowed("qwen_omni_realtime", "Serena"));
        assertFalse(RealtimeAdapterCapabilityRegistry.isVoiceAllowed("qwen_omni_realtime", "unknown"));
        assertTrue(RealtimeAdapterCapabilityRegistry.isVoiceAllowed("doubao_realtime", "account_voice_id"));
    }
}
