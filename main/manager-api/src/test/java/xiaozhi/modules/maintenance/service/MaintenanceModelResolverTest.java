package xiaozhi.modules.maintenance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import cn.hutool.json.JSONObject;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.maintenance.dto.HarnessModelConfig;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.impl.ModelRuntimeAssembler;

class MaintenanceModelResolverTest {
    @Test
    void resolvesTestedManagedDeepSeekCredentials() {
        ModelConfigDao dao = mock(ModelConfigDao.class);
        ModelRuntimeAssembler assembler = mock(ModelRuntimeAssembler.class);
        ModelConfigEntity model = model("DeepSeek API", 1, 1);
        model.setEndpointUrl("https://api.deepseek.com");
        model.setUpstreamModelId("deepseek-chat");
        JSONObject config = new JSONObject();
        config.set("api_key", "managed-secret");
        config.set("base_url", "https://api.deepseek.com");
        config.set("model_name", "deepseek-chat");
        when(dao.selectById("model-1")).thenReturn(model);
        when(assembler.buildTypedConfig(model)).thenReturn(config);

        HarnessModelConfig resolved = new MaintenanceModelResolver(dao, assembler).resolve("model-1");

        assertEquals("managed-secret", resolved.getApiKey());
        assertEquals("https://api.deepseek.com", resolved.getBaseUrl());
        assertEquals("deepseek-chat", resolved.getModel());
    }

    @Test
    void rejectsDisabledOrNonDeepSeekModels() {
        ModelConfigDao dao = mock(ModelConfigDao.class);
        ModelRuntimeAssembler assembler = mock(ModelRuntimeAssembler.class);
        ModelConfigEntity disabled = model("DeepSeek API", 0, 1);
        when(dao.selectById("disabled")).thenReturn(disabled);
        assertThrows(RenException.class,
                () -> new MaintenanceModelResolver(dao, assembler).resolve("disabled"));

        ModelConfigEntity other = model("Qwen", 1, 1);
        JSONObject config = new JSONObject();
        config.set("api_key", "managed-secret");
        config.set("model_name", "qwen-plus");
        when(dao.selectById("other")).thenReturn(other);
        when(assembler.buildTypedConfig(other)).thenReturn(config);
        assertThrows(RenException.class,
                () -> new MaintenanceModelResolver(dao, assembler).resolve("other"));
    }

    private ModelConfigEntity model(String name, int enabled, int tested) {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setId("model-1");
        model.setModelType("LLM");
        model.setModelName(name);
        model.setIsEnabled(enabled);
        model.setTestStatus(tested);
        return model;
    }
}
