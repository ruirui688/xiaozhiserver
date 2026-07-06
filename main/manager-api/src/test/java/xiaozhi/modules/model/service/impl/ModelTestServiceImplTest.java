package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;

import cn.hutool.json.JSONObject;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.dto.ModelTestResultDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;

class ModelTestServiceImplTest {

    private final ModelConfigDao modelConfigDao = mock(ModelConfigDao.class);
    private final ModelRuntimeAssembler modelRuntimeAssembler = mock(ModelRuntimeAssembler.class);
    private final ModelTestServiceImpl service = new ModelTestServiceImpl(
            modelConfigDao,
            modelRuntimeAssembler,
            mock(RestTemplate.class),
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
    void intentLlmRequiresReferencedLlmToBeTested() {
        ModelConfigEntity intent = intentModel("Intent_intent_llm", "intent_llm");
        intent.getConfigJson().set("llm", "llm-1");
        ModelConfigEntity llm = new ModelConfigEntity();
        llm.setId("llm-1");
        llm.setModelType("LLM");
        llm.setTestStatus(1);
        when(modelConfigDao.selectById("llm-1")).thenReturn(llm);
        when(modelRuntimeAssembler.buildTypedConfig(llm)).thenReturn(new JSONObject().set("type", "openai"));

        ModelTestResultDTO result = service.testModel(intent);

        assertTrue(result.getSuccess());
        assertTrue(result.getVerified());
        assertEquals("RUNTIME_CONFIG", result.getTestLevel());
        assertEquals("关联 LLM 已测试通过", result.getMessage());
    }

    @Test
    void intentLlmFailsWhenReferencedLlmIsNotTested() {
        ModelConfigEntity intent = intentModel("Intent_intent_llm", "intent_llm");
        intent.getConfigJson().set("llm", "llm-1");
        ModelConfigEntity llm = new ModelConfigEntity();
        llm.setId("llm-1");
        llm.setModelType("LLM");
        llm.setTestStatus(2);
        when(modelConfigDao.selectById("llm-1")).thenReturn(llm);
        when(modelRuntimeAssembler.buildTypedConfig(llm)).thenReturn(new JSONObject().set("type", "openai"));

        ModelTestResultDTO result = service.testModel(intent);

        assertFalse(result.getSuccess());
        assertFalse(result.getVerified());
        assertEquals("RUNTIME_CONFIG", result.getTestLevel());
        assertEquals("关联 LLM 尚未测试通过", result.getMessage());
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
}
