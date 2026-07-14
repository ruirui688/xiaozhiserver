package xiaozhi.modules.config.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import cn.hutool.json.JSONObject;
import xiaozhi.common.constant.Constant;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.sys.service.SysParamsService;

class ConfigServiceImplTest {

    @Test
    void mergeRuntimeTypedConfigKeepsStandardRuntimeFieldsAheadOfLegacyJson() {
        ModelConfigEntity model = new ModelConfigEntity();
        JSONObject legacyConfig = new JSONObject();
        legacyConfig.set("type", "doubao");
        legacyConfig.set("api_key", "");
        legacyConfig.set("speaker", "BV001_streaming");
        legacyConfig.set("cluster", "volcano_tts");
        model.setConfigJson(legacyConfig);

        Map<String, Object> assembledConfig = new HashMap<>();
        assembledConfig.put("type", "huoshan_double_stream");
        assembledConfig.put("api_key", "real-api-key");
        assembledConfig.put("speaker", "zh_male_shaonianzixin_moon_bigtts");
        assembledConfig.put("resource_id", "volc.service_type.10029");
        assembledConfig.put("speech_rate", 0);

        Map<String, Object> merged = ConfigServiceImpl.mergeRuntimeTypedConfig(model, assembledConfig);

        assertEquals("huoshan_double_stream", merged.get("type"));
        assertEquals("real-api-key", merged.get("api_key"));
        assertEquals("zh_male_shaonianzixin_moon_bigtts", merged.get("speaker"));
        assertEquals("volc.service_type.10029", merged.get("resource_id"));
        assertEquals("volcano_tts", merged.get("cluster"));
        assertEquals(0, merged.get("speech_rate"));
    }

    @Test
    void applyManagedVadParamsReadsSysParamsAndClampsValues() throws Exception {
        SysParamsService sysParamsService = Mockito.mock(SysParamsService.class);
        when(sysParamsService.getValue(Constant.SERVER_VAD_THRESHOLD, true)).thenReturn(null);
        when(sysParamsService.getValue(Constant.SERVER_VAD_THRESHOLD_LOW, true)).thenReturn("1.2");
        when(sysParamsService.getValue(Constant.SERVER_VAD_MIN_SILENCE_DURATION_MS, true)).thenReturn("50");

        ConfigServiceImpl service = new ConfigServiceImpl(sysParamsService, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        Map<String, Object> runtimeConfig = new HashMap<>();

        service.applyManagedVadParams(runtimeConfig);

        assertEquals(0.5, runtimeConfig.get("threshold"));
        assertEquals(1.0, runtimeConfig.get("threshold_low"));
        assertEquals(100, runtimeConfig.get("min_silence_duration_ms"));
    }
}
