package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import cn.hutool.json.JSONObject;
import xiaozhi.modules.model.dto.ModelTestResultDTO;
import xiaozhi.modules.model.service.impl.benchmark.PythonBenchmarkClient;
import xiaozhi.modules.model.service.impl.tester.DoubaoRealtimeModelTester;

class DoubaoRealtimeModelTesterTest {

    @Test
    void sendsRealRuntimeBenchmarkAndRequiresMeasuredOutput() {
        PythonBenchmarkClient benchmarkClient = mock(PythonBenchmarkClient.class);
        AtomicReference<JSONObject> captured = new AtomicReference<>();
        JSONObject response = new JSONObject().set("results", List.of(
                new JSONObject().set("samples", List.of(
                        new JSONObject()
                                .set("success", true)
                                .set("measured", true)
                                .set("latency_ms", 280)
                                .set("ttft_ms", 70)
                                .set("first_audio_ms", 110)
                                .set("output_detail", new JSONObject()
                                        .set("assistant_text", "OK")
                                        .set("audio_bytes", 2048))))));
        when(benchmarkClient.runRuntimeTest(any(JSONObject.class), any(Duration.class)))
                .thenAnswer(invocation -> {
                    captured.set(invocation.getArgument(0));
                    return response;
                });

        ModelTestResultDTO result = new DoubaoRealtimeModelTester(benchmarkClient)
                .test(validConfig(), "doubao_realtime");

        assertTrue(result.getSuccess());
        assertEquals(280L, result.getResponseTimeMs());
        assertEquals(70L, result.getFirstTextMs());
        assertEquals(110L, result.getFirstAudioMs());
        assertEquals("OK", result.getTestOutput());
        assertEquals("doubao_realtime",
                captured.get().getJSONArray("models").getJSONObject(0).getStr("provider_type"));
        assertEquals("请只回复 OK",
                captured.get().getJSONArray("benchmark_cases").getJSONObject(0)
                        .getJSONArray("turns").getStr(0));
    }

    @Test
    void rejectsNonOfficialEndpointBeforeSendingCredential() {
        PythonBenchmarkClient benchmarkClient = mock(PythonBenchmarkClient.class);
        JSONObject config = validConfig().set("base_url", "wss://attacker.example/api/v3/realtime/dialogue");

        ModelTestResultDTO result = new DoubaoRealtimeModelTester(benchmarkClient)
                .test(config, "doubao_realtime");

        assertFalse(result.getSuccess());
        assertTrue(result.getMessage().contains("仅允许火山引擎官方地址"));
        assertFalse(result.getTestOutput().contains("access-key-value"));
        verifyNoInteractions(benchmarkClient);
    }

    @Test
    void rejectsHandshakeOnlyResult() {
        PythonBenchmarkClient benchmarkClient = mock(PythonBenchmarkClient.class);
        when(benchmarkClient.runRuntimeTest(any(JSONObject.class), any(Duration.class)))
                .thenReturn(new JSONObject().set("results", List.of(
                        new JSONObject().set("samples", List.of(
                                new JSONObject()
                                        .set("success", true)
                                        .set("measured", false)
                                        .set("message", "仅建立连接"))))));

        ModelTestResultDTO result = new DoubaoRealtimeModelTester(benchmarkClient)
                .test(validConfig(), "doubao_realtime");

        assertFalse(result.getSuccess());
        assertEquals("仅建立连接", result.getMessage());
        assertNull(result.getFirstTextMs());
    }

    private JSONObject validConfig() {
        return new JSONObject()
                .set("app_id", "123456789")
                .set("api_key", "access-key-value")
                .set("base_url", "wss://openspeech.bytedance.com/api/v3/realtime/dialogue")
                .set("model_name", "1.2.1.1")
                .set("conversation_mode", "realtime")
                .set("connect_timeout_seconds", 10)
                .set("response_timeout_seconds", 120);
    }
}
