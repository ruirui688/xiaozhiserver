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
}
