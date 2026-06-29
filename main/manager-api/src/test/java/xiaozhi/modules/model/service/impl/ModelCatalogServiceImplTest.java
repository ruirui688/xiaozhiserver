package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.model.dto.ModelCatalogDTO;
import xiaozhi.modules.model.dto.ModelFieldSchemaDTO;

class ModelCatalogServiceImplTest {

    @Test
    void qwenTextModelOptionsAreInitializedInStaticCatalog() throws Exception {
        ModelCatalogDTO qwenTemplate = staticCatalog().stream()
                .filter(item -> "LLM_ALI_QWEN_OPENAI".equals(item.getTemplateCode()))
                .findFirst()
                .orElseThrow();

        ModelFieldSchemaDTO modelNameField = qwenTemplate.getFieldSchemas().stream()
                .filter(field -> "model_name".equals(field.getKey()))
                .findFirst()
                .orElseThrow();

        assertEquals("select", modelNameField.getInputType());
        assertNotNull(modelNameField.getOptions());
        assertFalse(modelNameField.getOptions().isEmpty());
        assertEquals("qwen3.7-max", modelNameField.getDefaultValue());
    }

    @Test
    void doubaoSeedTtsKeepsWebsocketReuseEnabledByDefault() throws Exception {
        ModelCatalogDTO ttsTemplate = staticCatalog().stream()
                .filter(item -> "TTS_DOUBAO_SEED_TTS_2_STREAM".equals(item.getTemplateCode()))
                .findFirst()
                .orElseThrow();

        ModelFieldSchemaDTO wsReuseField = ttsTemplate.getFieldSchemas().stream()
                .filter(field -> "enable_ws_reuse".equals(field.getKey()))
                .findFirst()
                .orElseThrow();

        assertEquals(true, wsReuseField.getDefaultValue());
    }

    @SuppressWarnings("unchecked")
    private List<ModelCatalogDTO> staticCatalog() throws Exception {
        Field catalogField = ModelCatalogServiceImpl.class.getDeclaredField("CATALOG");
        catalogField.setAccessible(true);
        return (List<ModelCatalogDTO>) catalogField.get(null);
    }
}
