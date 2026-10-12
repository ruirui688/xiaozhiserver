package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.hutool.json.JSONObject;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.dao.ModelConfigParamDao;
import xiaozhi.modules.model.dao.ModelConfigSecretDao;
import xiaozhi.modules.model.dao.ModelTemplateDao;
import xiaozhi.modules.model.dao.ModelTemplateFieldDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.entity.ModelConfigParamEntity;
import xiaozhi.modules.model.entity.ModelConfigSecretEntity;
import xiaozhi.modules.model.entity.ModelTemplateEntity;
import xiaozhi.modules.model.entity.ModelTemplateFieldEntity;

class ModelRuntimeAssemblerTest {

    @Test
    void buildRuntimeModelMatchesPythonRuntimeContract() {
        ModelConfigParamDao paramDao = mock(ModelConfigParamDao.class);
        ModelConfigSecretDao secretDao = mock(ModelConfigSecretDao.class);
        ModelConfigDao configDao = mock(ModelConfigDao.class);
        ModelTemplateDao templateDao = mock(ModelTemplateDao.class);
        ModelTemplateFieldDao templateFieldDao = mock(ModelTemplateFieldDao.class);

        when(paramDao.selectList(any())).thenReturn(List.of());
        when(secretDao.selectList(any())).thenReturn(List.of());

        ModelRuntimeAssembler assembler = new ModelRuntimeAssembler(
                paramDao,
                secretDao,
                configDao,
                templateDao,
                templateFieldDao);

        ModelConfigEntity model = new ModelConfigEntity();
        model.setId("asr-openai");
        model.setModelType("asr");
        model.setModelName("OpenAI ASR");
        model.setModelCode("asr-openai");
        model.setAdapterCode("openai");
        model.setEndpointUrl("https://example.invalid/v1");
        model.setUpstreamModelId("whisper-1");

        Map<String, Object> runtime = assembler.buildRuntimeModel(model);

        assertEquals("ASR", runtime.get("model_type"));
        assertInstanceOf(JSONObject.class, runtime.get("typed_config"));
        JSONObject typedConfig = (JSONObject) runtime.get("typed_config");
        assertEquals("openai", typedConfig.getStr("type"));
        assertEquals("https://example.invalid/v1", typedConfig.getStr("base_url"));
        assertEquals("whisper-1", typedConfig.getStr("model_name"));
    }

    @Test
    void qwenOmniRealtimeTypedConfigPreservesRealtimeAudioAndTimeoutTypes() {
        ModelConfigParamDao paramDao = mock(ModelConfigParamDao.class);
        ModelConfigSecretDao secretDao = mock(ModelConfigSecretDao.class);
        ModelConfigDao configDao = mock(ModelConfigDao.class);
        ModelTemplateDao templateDao = mock(ModelTemplateDao.class);
        ModelTemplateFieldDao templateFieldDao = mock(ModelTemplateFieldDao.class);

        when(templateDao.selectOne(any())).thenReturn(template("LLM_ALI_QWEN_OMNI_REALTIME", "model_name"));
        when(templateFieldDao.selectList(any())).thenReturn(List.of(
                templateField("base_url"),
                templateField("model_name"),
                templateField("conversation_mode"),
                templateField("input_sample_rate"),
                templateField("enable_input_audio_transcription")));
        when(paramDao.selectList(any())).thenReturn(List.of(
                param("conversation_mode", "realtime", "string"),
                param("input_sample_rate", "16000", "number"),
                param("output_sample_rate", "24000", "number"),
                param("enable_input_audio_transcription", "true", "boolean"),
                param("response_timeout_seconds", "120", "number"),
                param("max_event_bytes", "10485760", "number")));
        ModelConfigSecretEntity secret = new ModelConfigSecretEntity();
        secret.setSecretKey("api_key");
        secret.setSecretValue("sk-test");
        when(secretDao.selectList(any())).thenReturn(List.of(secret));

        ModelRuntimeAssembler assembler = new ModelRuntimeAssembler(
                paramDao, secretDao, configDao, templateDao, templateFieldDao);
        ModelConfigEntity model = new ModelConfigEntity();
        model.setId("LLM_QwenOmniRealtime");
        model.setModelType("LLM");
        model.setModelName("Qwen3.5 Omni Flash Realtime");
        model.setModelCode("QwenOmniRealtime");
        model.setAdapterCode("qwen_omni_realtime");
        model.setTemplateCode("LLM_ALI_QWEN_OMNI_REALTIME");
        model.setEndpointUrl("wss://dashscope.aliyuncs.com/api-ws/v1/realtime");
        model.setUpstreamModelId("qwen3.5-omni-flash-realtime");

        JSONObject typedConfig = assembler.buildTypedConfig(model);

        assertEquals("qwen_omni_realtime", typedConfig.getStr("type"));
        assertEquals("wss://dashscope.aliyuncs.com/api-ws/v1/realtime", typedConfig.getStr("base_url"));
        assertEquals("qwen3.5-omni-flash-realtime", typedConfig.getStr("model_name"));
        assertEquals("realtime", typedConfig.getStr("conversation_mode"));
        assertEquals(16000.0, typedConfig.getDouble("input_sample_rate"));
        assertEquals(24000.0, typedConfig.getDouble("output_sample_rate"));
        assertEquals(true, typedConfig.getBool("enable_input_audio_transcription"));
        assertEquals(120.0, typedConfig.getDouble("response_timeout_seconds"));
        assertEquals(10485760.0, typedConfig.getDouble("max_event_bytes"));
        assertEquals("PLATFORM_MANAGED", typedConfig.getStr("api_key"));
        org.junit.jupiter.api.Assertions.assertFalse(typedConfig.toString().contains("sk-test"));
        assertEquals(true, typedConfig.getBool("_platform_managed"));
    }

    private ModelTemplateEntity template(String code, String upstreamModelField) {
        ModelTemplateEntity entity = new ModelTemplateEntity();
        entity.setTemplateCode(code);
        entity.setUpstreamModelField(upstreamModelField);
        return entity;
    }

    private ModelTemplateFieldEntity templateField(String key) {
        ModelTemplateFieldEntity entity = new ModelTemplateFieldEntity();
        entity.setFieldKey(key);
        entity.setRuntimeKey(key);
        return entity;
    }

    private ModelConfigParamEntity param(String key, String value, String type) {
        ModelConfigParamEntity entity = new ModelConfigParamEntity();
        entity.setParamKey(key);
        entity.setParamValue(value);
        entity.setValueType(type);
        return entity;
    }
}
