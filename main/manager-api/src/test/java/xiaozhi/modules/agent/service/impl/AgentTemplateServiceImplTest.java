package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import xiaozhi.modules.agent.dto.AgentFeishuTemplateDTO;
import xiaozhi.modules.agent.dto.AgentTemplateSnapshotDTO;
import xiaozhi.modules.agent.dto.AgentUpdateDTO;
import xiaozhi.modules.agent.dto.ContextProviderDTO;
import xiaozhi.modules.agent.entity.AgentTemplateEntity;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.timbre.entity.TimbreEntity;
import xiaozhi.modules.timbre.service.TimbreService;

class AgentTemplateServiceImplTest {

    @Test
    void officialTemplateKeepsValidatedSpeechButRemovesPrivateBindings() {
        ModelConfigService modelService = mock(ModelConfigService.class);
        TimbreService timbreService = mock(TimbreService.class);
        AgentTemplateServiceImpl service = new AgentTemplateServiceImpl(modelService, timbreService);

        ModelConfigEntity tts = new ModelConfigEntity();
        tts.setId("tts-1");
        tts.setModelType("TTS");
        tts.setIsEnabled(1);
        tts.setTestStatus(1);
        when(modelService.getModelByIdFromCache("tts-1")).thenReturn(tts);
        TimbreEntity voice = new TimbreEntity();
        voice.setId("voice-1");
        voice.setTtsModelId("tts-1");
        when(timbreService.selectById("voice-1")).thenReturn(voice);

        ContextProviderDTO provider = new ContextProviderDTO();
        LinkedHashMap<String, Object> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer should-not-persist");
        headers.put("X-Trace-Id", "safe");
        provider.setHeaders(headers);
        AgentUpdateDTO config = new AgentUpdateDTO();
        config.setTtsModelId("tts-1");
        config.setTtsVoiceId("voice-1");
        config.setCorrectWordFileIds(List.of("private-words"));
        config.setContextProviders(List.of(provider));
        AgentTemplateSnapshotDTO snapshot = new AgentTemplateSnapshotDTO();
        snapshot.setConfig(config);
        snapshot.setVoicePrintIds(List.of("private-voiceprint"));
        snapshot.setFeishuConfig(new AgentFeishuTemplateDTO());
        AgentTemplateEntity entity = new AgentTemplateEntity();
        entity.setWorkspaceId(null);
        entity.setSnapshotJson(snapshot);

        ReflectionTestUtils.invokeMethod(service, "sanitizeTemplateModelIds", entity);

        assertEquals("tts-1", entity.getTtsModelId());
        assertEquals("voice-1", entity.getTtsVoiceId());
        assertTrue(snapshot.getVoicePrintIds().isEmpty());
        assertEquals(null, snapshot.getFeishuConfig());
        assertTrue(config.getCorrectWordFileIds().isEmpty());
        assertFalse(config.getContextProviders().get(0).getHeaders().containsKey("Authorization"));
        assertEquals("safe", config.getContextProviders().get(0).getHeaders().get("X-Trace-Id"));
    }

    @Test
    void officialTemplateDropsVoiceWhenItDoesNotBelongToTheTtsModel() {
        ModelConfigService modelService = mock(ModelConfigService.class);
        TimbreService timbreService = mock(TimbreService.class);
        AgentTemplateServiceImpl service = new AgentTemplateServiceImpl(modelService, timbreService);
        ModelConfigEntity tts = new ModelConfigEntity();
        tts.setId("tts-1");
        tts.setModelType("TTS");
        tts.setIsEnabled(1);
        tts.setTestStatus(1);
        when(modelService.getModelByIdFromCache("tts-1")).thenReturn(tts);
        TimbreEntity voice = new TimbreEntity();
        voice.setId("voice-other");
        voice.setTtsModelId("tts-other");
        when(timbreService.selectById("voice-other")).thenReturn(voice);

        AgentUpdateDTO config = new AgentUpdateDTO();
        config.setTtsModelId("tts-1");
        config.setTtsVoiceId("voice-other");
        AgentTemplateSnapshotDTO snapshot = new AgentTemplateSnapshotDTO();
        snapshot.setConfig(config);
        AgentTemplateEntity entity = new AgentTemplateEntity();
        entity.setSnapshotJson(snapshot);

        ReflectionTestUtils.invokeMethod(service, "sanitizeTemplateModelIds", entity);

        assertEquals("tts-1", config.getTtsModelId());
        assertEquals(null, config.getTtsVoiceId());
    }
}
