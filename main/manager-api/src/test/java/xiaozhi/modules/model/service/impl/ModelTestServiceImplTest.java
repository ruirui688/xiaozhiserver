package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import cn.hutool.json.JSONObject;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.dto.ModelTestResultDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;

class ModelTestServiceImplTest {

    private final ModelConfigDao modelConfigDao = mock(ModelConfigDao.class);
    private final ModelRuntimeAssembler modelRuntimeAssembler = mock(ModelRuntimeAssembler.class);
    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final ModelTestServiceImpl service = new ModelTestServiceImpl(
            modelConfigDao,
            modelRuntimeAssembler,
            restTemplate,
            mock(Environment.class));

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
}
