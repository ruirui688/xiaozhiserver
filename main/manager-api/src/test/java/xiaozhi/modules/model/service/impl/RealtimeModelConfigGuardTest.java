package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import cn.hutool.json.JSONObject;
import xiaozhi.common.exception.RenException;

class RealtimeModelConfigGuardTest {
    @Test
    void duplexRejectsLegacyAdapterAndCredentialExfiltrationEndpoints() {
        String url = "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue";
        assertTrue(RealtimeModelConfigGuard.validationError("LLM", "LLM_DOUBAO_DUPLEX",
                "doubao_duplex", "realtime", url).isEmpty());
        for (String bad : new String[] {url + "?key=secret", url + "#fragment",
                "wss://attacker.example/api/v3/duplex/realtime/dialogue",
                "wss://openspeech.bytedance.com/api/v3/realtime/dialogue"}) {
            assertTrue(!RealtimeModelConfigGuard.validationError("LLM", "LLM_DOUBAO_DUPLEX",
                    "doubao_duplex", "realtime", bad).isEmpty());
        }
        assertTrue(!RealtimeModelConfigGuard.validationError("LLM", "LLM_DOUBAO_DUPLEX",
                "doubao_realtime", "realtime", url).isEmpty());
    }


    @Test
    void acceptsQwenOmniRealtimeContract() {
        assertDoesNotThrow(() -> RealtimeModelConfigGuard.validateForSave(
                "LLM",
                "qwen_omni_realtime",
                "LLM_ALI_QWEN_OMNI_REALTIME",
                "qwen_omni_realtime",
                realtimeConfig("qwen_omni_realtime",
                        "wss://workspace-a.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime"),
                null));
    }

    @Test
    void acceptsDoubaoRealtimeContract() {
        assertDoesNotThrow(() -> RealtimeModelConfigGuard.validateForSave(
                "LLM",
                "doubao_realtime",
                "LLM_DOUBAO_REALTIME",
                "doubao_realtime",
                realtimeConfig("doubao_realtime",
                        "wss://openspeech.bytedance.com/api/v3/realtime/dialogue"),
                null));
    }

    @Test
    void rejectsOpenAiAdapterWithRealtimeConversationMode() {
        String error = RealtimeModelConfigGuard.validationError(
                "LLM",
                "LLM_ALI_QWEN_OPENAI",
                "openai",
                "realtime",
                "https://dashscope.aliyuncs.com/compatible-mode/v1");

        assertTrue(error.contains("Realtime 配置不一致"));
        assertTrue(error.contains("不能使用普通 openai adapter"));
    }

    @Test
    void rejectsQwenRealtimeTemplateWithOpenAiAdapter() {
        String error = RealtimeModelConfigGuard.validationError(
                "LLM",
                "LLM_ALI_QWEN_OMNI_REALTIME",
                "openai",
                "realtime",
                "https://dashscope.aliyuncs.com/compatible-mode/v1");

        assertTrue(error.contains("必须使用 adapter=qwen_omni_realtime"));
    }

    @Test
    void rejectsRealtimeAdapterWithHttpEndpoint() {
        String error = RealtimeModelConfigGuard.validationError(
                "LLM",
                "LLM_ALI_QWEN_OMNI_REALTIME",
                "qwen_omni_realtime",
                "realtime",
                "https://dashscope.aliyuncs.com/compatible-mode/v1");

        assertTrue(error.contains("必须使用 wss://"));
        assertTrue(error.contains("不能使用 HTTP Chat Completions"));
    }

    @Test
    void rejectsProviderPathThatDoesNotMatchRealtimeAdapter() {
        RenException error = assertThrows(RenException.class,
                () -> RealtimeModelConfigGuard.validateForSave(
                        "LLM",
                        "openai",
                        "LLM_ALI_QWEN_OMNI_REALTIME",
                        "qwen_omni_realtime",
                        realtimeConfig("qwen_omni_realtime",
                                "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"),
                        null));

        assertTrue(error.getMessage().contains("请求路径 provider=openai"));
    }

    private JSONObject realtimeConfig(String type, String baseUrl) {
        return new JSONObject()
                .set("type", type)
                .set("conversation_mode", "realtime")
                .set("base_url", baseUrl);
    }
}
