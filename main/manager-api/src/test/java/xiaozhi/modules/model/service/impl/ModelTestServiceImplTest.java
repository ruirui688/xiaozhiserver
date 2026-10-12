package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import cn.hutool.json.JSONObject;
import org.mockito.ArgumentCaptor;
import xiaozhi.common.constant.Constant;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.dto.ModelTestResultDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.impl.benchmark.BenchmarkRequestAuth;
import xiaozhi.modules.model.service.impl.benchmark.PythonBenchmarkClient;
import xiaozhi.modules.model.service.impl.tester.IntentModelTester;
import xiaozhi.modules.model.service.impl.tester.QwenOmniRealtimeModelTester;
import xiaozhi.modules.sys.service.SysParamsService;

class ModelTestServiceImplTest {

    private final ModelConfigDao modelConfigDao = mock(ModelConfigDao.class);
    private final ModelRuntimeAssembler modelRuntimeAssembler = mock(ModelRuntimeAssembler.class);
    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final IntentModelTester intentModelTester = new IntentModelTester(
            modelConfigDao,
            modelRuntimeAssembler,
            restTemplate);
    private final ModelTestServiceImpl service = new ModelTestServiceImpl(
            modelConfigDao,
            modelRuntimeAssembler,
            restTemplate,
            mock(Environment.class),
            mock(BenchmarkRequestAuth.class),
            java.util.List.of(intentModelTester));

    @org.junit.jupiter.api.BeforeEach
    void installMeteringBoundary() {
        xiaozhi.common.llm.MeteredHttpTestSupport.install(service);
        xiaozhi.common.llm.MeteredHttpTestSupport.install(intentModelTester);
    }

    @Test
    void intentExecutorOnlyClaimsSupportedIntentProviders() {
        assertTrue(intentModelTester.supports("INTENT", "intent_llm"));
        assertTrue(intentModelTester.supports("intent", "function_call"));
        assertFalse(intentModelTester.supports("LLM", "intent_llm"));
        assertFalse(intentModelTester.supports("INTENT", "unknown"));
    }

    @Test
    void noIntentIsVerifiedAsRuntimeConfig() {
        ModelTestResultDTO result = service.testModel(intentModel("Intent_nointent", "nointent"));

        assertTrue(result.getSuccess());
        assertTrue(result.getVerified());
        assertEquals("RUNTIME_CONFIG", result.getTestLevel());
        assertEquals("运行时配置", result.getTestLevelText());
    }

    @Test
    void functionCallIntentIsVerifiedAsRuntimeConfig() {
        ModelTestResultDTO result = service.testModel(intentModel("Intent_function_call", "function_call"));

        assertTrue(result.getSuccess());
        assertTrue(result.getVerified());
        assertEquals("RUNTIME_CONFIG", result.getTestLevel());
        assertEquals("运行时配置", result.getTestLevelText());
    }

    @Test
    void intentLlmRunsRealJsonClassificationThroughReferencedOpenAiLlm() {
        ModelConfigEntity intent = intentModel("Intent_intent_llm", "intent_llm");
        intent.getConfigJson().set("llm", "llm-1");
        ModelConfigEntity llm = new ModelConfigEntity();
        llm.setId("llm-1");
        llm.setModelType("LLM");
        llm.setConfigJson(new JSONObject().set("type", "openai"));
        JSONObject runtimeConfig = new JSONObject()
                .set("type", "openai")
                .set("base_url", "https://example.com/v1")
                .set("model_name", "intent-test-model")
                .set("api_key", "sk-test");
        when(modelConfigDao.selectById("llm-1")).thenReturn(llm);
        when(modelRuntimeAssembler.buildTypedConfig(llm)).thenReturn(runtimeConfig);
        when(restTemplate.exchange(
                eq("https://example.com/v1/chat/completions"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        {"choices":[{"message":{"content":"{\\"function_call\\":{\\"name\\":\\"continue_chat\\"}}"}}]}
                        """));

        ModelTestResultDTO result = service.testModel(intent);

        assertTrue(result.getSuccess());
        assertTrue(result.getVerified());
        assertEquals("REAL_INFERENCE", result.getTestLevel());
        assertEquals("外挂大模型意图识别真实调用通过", result.getMessage());
    }

    @Test
    void intentLlmFailsWhenReferencedLlmReturnsInvalidIntentJson() {
        ModelConfigEntity intent = intentModel("Intent_intent_llm", "intent_llm");
        intent.getConfigJson().set("llm", "llm-1");
        ModelConfigEntity llm = new ModelConfigEntity();
        llm.setId("llm-1");
        llm.setModelType("LLM");
        llm.setConfigJson(new JSONObject().set("type", "openai"));
        JSONObject runtimeConfig = new JSONObject()
                .set("type", "openai")
                .set("base_url", "https://example.com/v1")
                .set("model_name", "intent-test-model")
                .set("api_key", "sk-test");
        when(modelConfigDao.selectById("llm-1")).thenReturn(llm);
        when(modelRuntimeAssembler.buildTypedConfig(llm)).thenReturn(runtimeConfig);
        when(restTemplate.exchange(
                eq("https://example.com/v1/chat/completions"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        {"choices":[{"message":{"content":"你好，我是小智"}}]}
                        """));

        ModelTestResultDTO result = service.testModel(intent);

        assertFalse(result.getSuccess());
        assertFalse(result.getVerified());
        assertEquals("REAL_INFERENCE", result.getTestLevel());
       assertTrue(result.getMessage().startsWith("外挂大模型意图识别未返回合法 JSON function_call"));
    }

    @Test
    void intentLlmFailsWithClearMessageWhenApiKeyIsPlaceholder() {
        ModelConfigEntity intent = intentModel("Intent_intent_llm", "intent_llm");
        intent.getConfigJson().set("llm", "llm-1");
        JSONObject runtimeConfig = new JSONObject()
                .set("type", "openai")
                .set("base_url", "https://open.bigmodel.cn/api/paas/v4")
                .set("model_name", "glm-4-flash")
                .set("api_key", "你的api_key");
        referencedOpenAiLlm("llm-1", runtimeConfig);

        ModelTestResultDTO result = service.testModel(intent);

        assertFalse(result.getSuccess());
        assertFalse(result.getVerified());
        assertEquals("REAL_INFERENCE", result.getTestLevel());
        assertTrue(result.getMessage().contains("api_key"));
        assertTrue(result.getMessage().contains("占位符"));
        assertTrue(result.getTestInput().contains("glm-4-flash"));
        assertTrue(result.getTestInput().contains("占位符，需填写真实密钥"));
        assertFalse(result.getTestInput().contains("你的api_key"));
    }

    @Test
    void intentLlmFailsWithClearMessageWhenBaseUrlMissing() {
        ModelConfigEntity intent = intentModel("Intent_intent_llm", "intent_llm");
        intent.getConfigJson().set("llm", "llm-1");
        JSONObject runtimeConfig = new JSONObject()
                .set("type", "openai")
                .set("model_name", "glm-4-flash")
                .set("api_key", "sk-real-key");
        referencedOpenAiLlm("llm-1", runtimeConfig);

        ModelTestResultDTO result = service.testModel(intent);

        assertFalse(result.getSuccess());
        assertTrue(result.getMessage().contains("base_url"));
        assertFalse(result.getTestInput().contains("sk-real-key"));
    }

    @Test
    void localKnowledgeRagIsVerifiedAsRuntimeConfig() {
        ModelConfigEntity rag = new ModelConfigEntity();
        rag.setId("RAG_LocalKnowledge");
        rag.setModelType("RAG");
        rag.setConfigJson(new JSONObject()
                .set("type", "local_knowledge")
                .set("max_chunk_chars", 1200));

        ModelTestResultDTO result = service.testModel(rag);

        assertTrue(result.getSuccess());
        assertTrue(result.getVerified());
        assertEquals("RUNTIME_CONFIG", result.getTestLevel());
        assertEquals("运行时配置", result.getTestLevelText());
        assertEquals("本地知识库运行时配置通过；数据存储和召回由 manager-api 知识库模块执行。",
                result.getMessage());
    }

    @Test
    void qwenOmniRealtimeUsesRuntimeBenchmarkAndRequiresRealOutput() {
        AtomicReference<JSONObject> capturedPayload = new AtomicReference<>();
        JSONObject response = new JSONObject().set("results", List.of(
                new JSONObject().set("samples", List.of(
                        new JSONObject()
                                .set("success", true)
                                .set("measured", true)
                                .set("latency_ms", 321)
                                .set("ttft_ms", 95)
                                .set("first_audio_ms", 143)
                                .set("output_detail", new JSONObject()
                                        .set("assistant_text", "OK")
                                        .set("audio_bytes", 4096))))));
        ModelTestServiceImpl runtimeService = runtimeBenchmarkService(response, capturedPayload);

        ModelTestResultDTO result = runtimeService.testModel(qwenOmniRealtimeModel(
                "wss://workspace-a.cn-beijing.maas.aliyuncs.com:443/api-ws/v1/realtime"));

        assertTrue(result.getSuccess());
        assertTrue(result.getVerified());
        assertEquals("REAL_INFERENCE", result.getTestLevel());
        assertEquals(321L, result.getResponseTimeMs());
        assertEquals(95L, result.getFirstTextMs());
        assertEquals(143L, result.getFirstAudioMs());
        assertEquals("OK", result.getTestOutput());
        assertEquals("qwen_omni_realtime",
                capturedPayload.get().getJSONArray("models").getJSONObject(0).getStr("provider_type"));
        assertEquals("请只回复 OK",
                capturedPayload.get().getJSONArray("benchmark_cases").getJSONObject(0)
                        .getJSONArray("turns").getStr(0));
    }

    @Test
    void qwenOmniRealtimeRejectsNonDashScopeEndpointBeforeSendingSecret() {
        AtomicReference<JSONObject> capturedPayload = new AtomicReference<>();
        ModelTestServiceImpl runtimeService = runtimeBenchmarkService(new JSONObject(), capturedPayload);

        ModelTestResultDTO result = runtimeService.testModel(
                qwenOmniRealtimeModel("wss://attacker.example/api-ws/v1/realtime"));

        assertFalse(result.getSuccess());
        assertFalse(result.getVerified());
        assertEquals("REAL_INFERENCE", result.getTestLevel());
        assertTrue(result.getMessage().contains("仅允许阿里云百炼官方 DashScope 地址"));
        assertEquals(null, capturedPayload.get());
        assertFalse(result.getTestOutput().contains("sk-test"));
    }

    @Test
    void realtimeConversationWithOpenAiAdapterIsRejectedBeforeHttpRequest() {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setId("LLM_QwenOmniRealtime");
        model.setModelType("LLM");
        model.setTemplateCode("LLM_ALI_QWEN_OPENAI");
        model.setConfigJson(new JSONObject()
                .set("type", "openai")
                .set("api_key", "credential-must-not-leave")
                .set("base_url", "https://dashscope.aliyuncs.com/compatible-mode/v1")
                .set("model_name", "qwen3.5-omni-flash-realtime")
                .set("conversation_mode", "realtime"));

        ModelTestResultDTO result = service.testModel(model);

        assertFalse(result.getSuccess());
        assertFalse(result.getVerified());
        assertEquals("REAL_INFERENCE", result.getTestLevel());
        assertTrue(result.getMessage().contains("Realtime 配置不一致"));
        assertFalse(result.getTestInput().contains("credential-must-not-leave"));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void qwenOmniRealtimeDoesNotTreatHandshakeOnlySampleAsPassed() {
        JSONObject response = new JSONObject().set("results", List.of(
                new JSONObject().set("samples", List.of(
                        new JSONObject()
                                .set("success", true)
                                .set("measured", false)
                                .set("message", "仅建立连接")))));

        ModelTestResultDTO result = runtimeBenchmarkService(response, new AtomicReference<>())
                .testModel(qwenOmniRealtimeModel("wss://dashscope.aliyuncs.com/api-ws/v1/realtime"));

        assertFalse(result.getSuccess());
        assertFalse(result.getVerified());
        assertEquals("REAL_INFERENCE", result.getTestLevel());
        assertEquals("仅建立连接", result.getMessage());
    }

    @Test
    void realtimeRuntimeBenchmarkTimeoutIncludesConnectResponseAndGrace() {
        JSONObject config = qwenOmniRealtimeModel("wss://dashscope.aliyuncs.com/api-ws/v1/realtime")
                .getConfigJson();
        assertEquals(140, QwenOmniRealtimeModelTester.resolveTimeoutSeconds(config));

        config.set("connect_timeout_seconds", 60);
        config.set("response_timeout_seconds", 300);
        assertEquals(330, QwenOmniRealtimeModelTester.resolveTimeoutSeconds(config));
    }

    @Test
    void runtimeBenchmarkSendsServerSecretBearerHeader() {
        SysParamsService sysParamsService = mock(SysParamsService.class);
        when(sysParamsService.getValue(Constant.SERVER_SECRET, true)).thenReturn("server-test-secret");
        BenchmarkRequestAuth auth = new BenchmarkRequestAuth(sysParamsService);
        RestTemplate benchmarkRestTemplate = mock(RestTemplate.class);
        when(benchmarkRestTemplate.exchange(
                eq("http://127.0.0.1:8003/benchmark/models"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"results\":[]}"));
        ModelTestServiceImpl runtimeService = new ModelTestServiceImpl(
                modelConfigDao,
                modelRuntimeAssembler,
                restTemplate,
                mock(Environment.class),
                auth,
                java.util.List.of(intentModelTester)) {
            @Override
            protected RestTemplate createRuntimeBenchmarkRestTemplate(JdkClientHttpRequestFactory factory) {
                return benchmarkRestTemplate;
            }
        };

        runtimeService.callRuntimeBenchmark(new JSONObject().set("models", List.of()));

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(benchmarkRestTemplate).exchange(
                eq("http://127.0.0.1:8003/benchmark/models"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(String.class));
        assertEquals("Bearer server-test-secret",
                entityCaptor.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }

    private ModelConfigEntity intentModel(String id, String type) {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setId(id);
        model.setModelType("Intent");
        model.setConfigJson(new JSONObject().set("type", type));
        return model;
    }
    private ModelConfigEntity referencedOpenAiLlm(String llmId, JSONObject runtimeConfig) {
        ModelConfigEntity llm = new ModelConfigEntity();
        llm.setId(llmId);
        llm.setModelType("LLM");
        llm.setConfigJson(new JSONObject().set("type", "openai"));
        when(modelConfigDao.selectById(llmId)).thenReturn(llm);
        when(modelRuntimeAssembler.buildTypedConfig(llm)).thenReturn(runtimeConfig);
        return llm;
    }

    private ModelConfigEntity qwenOmniRealtimeModel(String baseUrl) {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setId("LLM_QwenOmniRealtime");
        model.setModelType("LLM");
        model.setAdapterCode("qwen_omni_realtime");
        model.setTemplateCode("LLM_ALI_QWEN_OMNI_REALTIME");
        model.setConfigJson(new JSONObject()
                .set("type", "qwen_omni_realtime")
                .set("api_key", "sk-test")
                .set("base_url", baseUrl)
                .set("model_name", "qwen3.5-omni-flash-realtime")
                .set("conversation_mode", "realtime")
                .set("connect_timeout_seconds", 10)
                .set("response_timeout_seconds", 120));
        return model;
    }

    private ModelTestServiceImpl runtimeBenchmarkService(JSONObject response,
            AtomicReference<JSONObject> capturedPayload) {
        PythonBenchmarkClient benchmarkClient = mock(PythonBenchmarkClient.class);
        when(benchmarkClient.runRuntimeTest(any(JSONObject.class), any(Duration.class)))
                .thenAnswer(invocation -> {
                    capturedPayload.set(invocation.getArgument(0));
                    return response;
                });
        QwenOmniRealtimeModelTester realtimeTester = new QwenOmniRealtimeModelTester(benchmarkClient);
        return new ModelTestServiceImpl(
                modelConfigDao,
                modelRuntimeAssembler,
                restTemplate,
                mock(Environment.class),
                mock(BenchmarkRequestAuth.class),
                java.util.List.of(intentModelTester, realtimeTester));
    }
}
