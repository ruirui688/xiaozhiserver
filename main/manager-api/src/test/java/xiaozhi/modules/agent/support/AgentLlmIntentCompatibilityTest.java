package xiaozhi.modules.agent.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentLlmIntentCompatibilityTest {

    @Test
    void qwenOmniRealtimeSupportsMainLlmFunctionCalling() {
        assertTrue(AgentLlmIntentCompatibility.supportsIntent(
                "qwen_omni_realtime", "Intent_function_call"));
        assertTrue(AgentLlmIntentCompatibility.supportsIntent(
                "qwen-omni-realtime", "Intent_function_call"));
    }

    @Test
    void doubaoRealtimeRemainsRejectedUntilItsProtocolCanReturnToolOutputs() {
        assertFalse(AgentLlmIntentCompatibility.supportsIntent(
                "doubao_realtime", "Intent_function_call"));
    }

    @Test
    void nonFunctionCallIntentIsProviderAgnostic() {
        assertTrue(AgentLlmIntentCompatibility.supportsIntent(
                "doubao_realtime", "Intent_nointent"));
    }
}
