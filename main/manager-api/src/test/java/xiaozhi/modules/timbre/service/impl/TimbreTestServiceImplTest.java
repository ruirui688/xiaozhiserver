package xiaozhi.modules.timbre.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cn.hutool.json.JSONObject;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.model.dto.ModelTestResultDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.ModelTestService;
import xiaozhi.modules.timbre.dao.TimbreDao;
import xiaozhi.modules.timbre.entity.TimbreEntity;

class TimbreTestServiceImplTest {

    private final TimbreDao timbreDao = mock(TimbreDao.class);
    private final ModelConfigService modelConfigService = mock(ModelConfigService.class);
    private final ModelTestService modelTestService = mock(ModelTestService.class);
    private final TimbreTestServiceImpl service = new TimbreTestServiceImpl(
            timbreDao,
            modelConfigService,
            modelTestService);

    @Test
    void testUsesSelectedVoiceWithoutMutatingSharedModelConfig() {
        TimbreEntity timbre = new TimbreEntity();
        timbre.setId("voice-1");
        timbre.setTtsModelId("tts-1");
        timbre.setName("魅力女友");
        timbre.setTtsVoice("zh_female_meilinvyou_emo_v2_mars_bigtts");
        timbre.setLanguages("普通话、英语");
        when(timbreDao.selectById("voice-1")).thenReturn(timbre);

        ModelConfigEntity model = new ModelConfigEntity();
        model.setId("tts-1");
        model.setModelType("TTS");
        model.setModelName("豆包语音合成大模型");
        model.setConfigJson(new JSONObject()
                .set("type", "huoshan_double_stream")
                .set("resource_id", "volc.service_type.10029")
                .set("speaker", "zh_female_wanwanxiaohe_moon_bigtts"));
        when(modelConfigService.getModelByIdFromCache("tts-1")).thenReturn(model);

        ModelTestResultDTO providerResult = new ModelTestResultDTO();
        providerResult.setSuccess(true);
        providerResult.setVerified(true);
        providerResult.setMessage("TTS 真实合成测试通过");
        providerResult.setTestAudioDataUrl("data:audio/wav;base64,UklGRg==");
        when(modelTestService.testModel(any())).thenReturn(providerResult);
        when(modelTestService.isVerifiedModelTest(providerResult)).thenReturn(true);

        ModelTestResultDTO result = service.test("voice-1");

        ArgumentCaptor<ModelConfigEntity> captor = ArgumentCaptor.forClass(ModelConfigEntity.class);
        verify(modelTestService).testModel(captor.capture());
        assertEquals(
                "zh_female_meilinvyou_emo_v2_mars_bigtts",
                captor.getValue().getConfigJson().getStr("private_voice"));
        assertEquals("普通话", captor.getValue().getConfigJson().getStr("language"));
        assertFalse(model.getConfigJson().containsKey("private_voice"));
        assertTrue(result.getVerified());
        assertEquals(1, result.getTestStatus());
        assertTrue(result.getMessage().startsWith("音色真实合成测试通过"));
    }

    @Test
    void testRejectsVoiceBoundToNonTtsModel() {
        TimbreEntity timbre = new TimbreEntity();
        timbre.setId("voice-2");
        timbre.setTtsModelId("llm-1");
        timbre.setTtsVoice("invalid-voice");
        when(timbreDao.selectById("voice-2")).thenReturn(timbre);

        ModelConfigEntity model = new ModelConfigEntity();
        model.setId("llm-1");
        model.setModelType("LLM");
        when(modelConfigService.getModelByIdFromCache("llm-1")).thenReturn(model);

        RenException error = assertThrows(RenException.class, () -> service.test("voice-2"));
        assertEquals("音色只能关联 TTS 模型", error.getMessage());
    }

    @Test
    void testRejectsProviderSuccessWithoutPlayableAudio() {
        TimbreEntity timbre = new TimbreEntity();
        timbre.setId("voice-3");
        timbre.setTtsModelId("tts-1");
        timbre.setName("无预览音频");
        timbre.setTtsVoice("voice-without-preview");
        when(timbreDao.selectById("voice-3")).thenReturn(timbre);

        ModelConfigEntity model = new ModelConfigEntity();
        model.setId("tts-1");
        model.setModelType("TTS");
        model.setModelName("测试 TTS");
        model.setConfigJson(new JSONObject().set("type", "openai").set("voice", "alloy"));
        when(modelConfigService.getModelByIdFromCache("tts-1")).thenReturn(model);

        ModelTestResultDTO providerResult = new ModelTestResultDTO();
        providerResult.setSuccess(true);
        providerResult.setVerified(true);
        providerResult.setMessage("厂商调用成功");
        when(modelTestService.testModel(any())).thenReturn(providerResult);
        when(modelTestService.isVerifiedModelTest(providerResult)).thenReturn(true);

        ModelTestResultDTO result = service.test("voice-3");

        assertFalse(result.getSuccess());
        assertFalse(result.getVerified());
        assertEquals(2, result.getTestStatus());
        assertTrue(result.getMessage().contains("厂商未返回可播放的测试音频"));
    }
}
