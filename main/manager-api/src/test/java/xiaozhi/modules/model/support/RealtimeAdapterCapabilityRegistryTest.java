package xiaozhi.modules.model.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    }
}
