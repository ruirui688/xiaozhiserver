package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
    void qwenOmniRealtimeIsSelectableWithCompleteRealtimeContract() throws Exception {
        ModelCatalogDTO template = staticCatalog().stream()
                .filter(item -> "LLM_ALI_QWEN_OMNI_REALTIME".equals(item.getTemplateCode()))
                .findFirst()
                .orElseThrow();

        assertEquals("qwen_omni_realtime", template.getProviderCode());
        assertEquals("SUPPORTED", template.getSupportedStatus());
        assertTrue(template.getSelectable());
        assertTrue(template.getSetupHints().stream().anyMatch(item -> item.contains("绕过传统 ASR、VAD、LLM、TTS")));
        assertTrue(template.getTestContract().contains("真实 DashScope Realtime WebSocket"));

        List<String> fieldKeys = template.getFieldSchemas().stream()
                .map(ModelFieldSchemaDTO::getKey)
                .toList();
        assertEquals(List.of(
                "api_key", "base_url", "model_name", "conversation_mode", "input_sample_rate",
                "output_sample_rate", "voice", "turn_detection_type", "turn_detection_threshold",
                "prefix_padding_ms", "silence_duration_ms", "enable_input_audio_transcription",
                "input_audio_transcription_model", "connect_timeout_seconds", "response_timeout_seconds",
                "max_event_bytes"), fieldKeys);

        ModelFieldSchemaDTO apiKey = field(template, "api_key");
        assertTrue(apiKey.getSecret());
        assertEquals("password", apiKey.getInputType());
        assertEquals("", apiKey.getDefaultValue());

        ModelFieldSchemaDTO baseUrl = field(template, "base_url");
        assertEquals("text", baseUrl.getInputType());
        assertEquals("", baseUrl.getDefaultValue());
        assertEquals(List.of(), baseUrl.getOptions());

        ModelFieldSchemaDTO modelName = field(template, "model_name");
        assertEquals("qwen3.5-omni-flash-realtime", modelName.getDefaultValue());
        assertEquals(List.of(
                "qwen3.5-omni-plus-realtime",
                "qwen3.5-omni-flash-realtime",
                "qwen3-omni-flash-realtime"), modelName.getOptions());

        assertEquals("realtime", field(template, "conversation_mode").getDefaultValue());
        assertEquals(16000, field(template, "input_sample_rate").getDefaultValue());
        assertEquals(24000, field(template, "output_sample_rate").getDefaultValue());
        assertEquals("Ethan", field(template, "voice").getDefaultValue());
        assertEquals(List.of("server_vad", "manual"), field(template, "turn_detection_type").getOptions());
        assertEquals(120, field(template, "response_timeout_seconds").getDefaultValue());
        assertEquals(10485760, field(template, "max_event_bytes").getDefaultValue());
    }

    @Test
    void doubaoSeedTtsExposesOnlyUserEditableFields() throws Exception {
        ModelCatalogDTO ttsTemplate = staticCatalog().stream()
                .filter(item -> "TTS_DOUBAO_SEED_TTS_2_STREAM".equals(item.getTemplateCode()))
                .findFirst()
                .orElseThrow();

        List<String> fieldKeys = ttsTemplate.getFieldSchemas().stream()
                .map(ModelFieldSchemaDTO::getKey)
                .toList();

        assertEquals(List.of("api_key", "speaker", "speech_rate", "loudness_rate", "pitch"), fieldKeys);
        assertFalse(fieldKeys.contains("ws_url"));
        assertFalse(fieldKeys.contains("resource_id"));
        assertFalse(fieldKeys.contains("enable_ws_reuse"));

        ModelFieldSchemaDTO speakerField = ttsTemplate.getFieldSchemas().stream()
                .filter(field -> "speaker".equals(field.getKey()))
                .findFirst()
                .orElseThrow();
        assertEquals("select", speakerField.getInputType());
        assertFalse(speakerField.getOptions().isEmpty());
        assertEquals("zh_female_sophie_uranus_bigtts", speakerField.getDefaultValue());

        ModelFieldSchemaDTO speechRateField = ttsTemplate.getFieldSchemas().stream()
                .filter(field -> "speech_rate".equals(field.getKey()))
                .findFirst()
                .orElseThrow();
        assertEquals("语速(-50~100)", speechRateField.getLabel());
    }

    @Test
    void doubaoBigTtsExposesVoiceOptionsAndVisibleParameterRanges() throws Exception {
        ModelCatalogDTO ttsTemplate = staticCatalog().stream()
                .filter(item -> "TTS_DOUBAO_BIGTTS_STREAM".equals(item.getTemplateCode()))
                .findFirst()
                .orElseThrow();

        ModelFieldSchemaDTO speakerField = ttsTemplate.getFieldSchemas().stream()
                .filter(field -> "speaker".equals(field.getKey()))
                .findFirst()
                .orElseThrow();
        assertEquals("select", speakerField.getInputType());
        assertFalse(speakerField.getOptions().isEmpty());
        assertEquals("zh_female_wanwanxiaohe_moon_bigtts", speakerField.getDefaultValue());

        List<String> parameterLabels = ttsTemplate.getFieldSchemas().stream()
                .filter(field -> List.of("speech_rate", "loudness_rate", "pitch").contains(field.getKey()))
                .map(ModelFieldSchemaDTO::getLabel)
                .toList();
        assertEquals(List.of("语速(-50~100)", "音量(-50~100)", "音高(-12~12)"), parameterLabels);
    }

    @Test
    void doubaoTtsCatalogContainsAllSupportedVariants() throws Exception {
        List<String> templateCodes = staticCatalog().stream()
                .filter(item -> "TTS".equals(item.getModelType()) && "火山引擎".equals(item.getVendorName()))
                .map(ModelCatalogDTO::getTemplateCode)
                .toList();

        assertEquals(List.of(
                "TTS_DOUBAO_BIGTTS_STREAM",
                "TTS_DOUBAO_SEED_TTS_2_STREAM"), templateCodes);
    }

    @Test
    void slmCatalogViewReusesLlmTemplateButKeepsSlmType() throws Exception {
        ModelCatalogDTO llmTemplate = staticCatalog().stream()
                .filter(item -> "LLM_OPENAI_COMPATIBLE".equals(item.getTemplateCode()))
                .findFirst()
                .orElseThrow();

        ModelCatalogDTO slmTemplate = toSlmCatalogView(llmTemplate);

        assertEquals("SLM_OPENAI_COMPATIBLE", slmTemplate.getTemplateCode());
        assertEquals("SLM", slmTemplate.getModelType());
        assertEquals("openai", slmTemplate.getProviderCode());
        assertEquals(llmTemplate.getFieldSchemas(), slmTemplate.getFieldSchemas());
        assertEquals("必须执行真实对话调用；主对话模型还应跑真实设备智能体评测。", slmTemplate.getTestContract());
    }

    @SuppressWarnings("unchecked")
    private List<ModelCatalogDTO> staticCatalog() throws Exception {
        Field catalogField = ModelCatalogServiceImpl.class.getDeclaredField("CATALOG");
        catalogField.setAccessible(true);
        return (List<ModelCatalogDTO>) catalogField.get(null);
    }

    private ModelCatalogDTO toSlmCatalogView(ModelCatalogDTO llmTemplate) throws Exception {
        ModelCatalogServiceImpl service = new ModelCatalogServiceImpl(null, null, null);
        Method method = ModelCatalogServiceImpl.class.getDeclaredMethod("toSlmCatalogView", ModelCatalogDTO.class);
        method.setAccessible(true);
        return (ModelCatalogDTO) method.invoke(service, llmTemplate);
    }

    private ModelFieldSchemaDTO field(ModelCatalogDTO template, String key) {
        return template.getFieldSchemas().stream()
                .filter(item -> key.equals(item.getKey()))
                .findFirst()
                .orElseThrow();
    }
}
