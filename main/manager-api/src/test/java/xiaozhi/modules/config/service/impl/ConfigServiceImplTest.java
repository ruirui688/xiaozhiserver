package xiaozhi.modules.config.service.impl;

import xiaozhi.modules.memory.service.ManagedMemorySnapshotService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import cn.hutool.json.JSONObject;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.agent.dao.AgentVoicePrintDao;
import xiaozhi.modules.agent.entity.AgentPluginMapping;
import xiaozhi.modules.agent.service.AgentContextProviderService;
import xiaozhi.modules.agent.service.AgentFeishuService;
import xiaozhi.modules.agent.service.AgentMcpAccessPointService;
import xiaozhi.modules.agent.service.AgentPluginMappingService;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.agent.skill.dto.SemanticSkillTriggerRuleDTO;
import xiaozhi.modules.agent.skill.service.AgentSemanticSkillService;
import xiaozhi.modules.agent.skill.vo.AgentSemanticSkillVO;
import xiaozhi.modules.agent.voiceprint.VoiceprintApiClient;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.correctword.service.CorrectWordFileService;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.impl.ModelRuntimeAssembler;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.timbre.entity.TimbreEntity;
import xiaozhi.modules.userplugin.service.UserPluginConfigService;
import xiaozhi.modules.voiceclone.service.VoiceCloneService;

class ConfigServiceImplTest {

    @Test
    void agentModelsAttachesRealManagedSnapshotUsingTypedModelAndBoundVoiceprints() {
        var params = mock(SysParamsService.class);
        when(params.getValue(Constant.SYSTEM_WEB_MENU, true))
                .thenReturn("{\"features\":{\"voiceprintRecognition\":{\"enabled\":true}}}");
        var devices = mock(DeviceService.class);
        var device = new DeviceEntity();
        device.setAgentId("agent-snapshot");
        when(devices.getDeviceByMacAddress("device-snapshot")).thenReturn(device);
        var agents = mock(AgentService.class);
        var agent = new AgentInfoVO();
        agent.setId("agent-snapshot");
        agent.setIntentModelId("Intent_nointent");
        agent.setMemModelId("memory-snapshot");
        when(agents.getAgentById("agent-snapshot")).thenReturn(agent);
        var models = mock(ModelConfigService.class);
        var model = new ModelConfigEntity();
        model.setId("memory-snapshot");
        model.setModelType("Memory");
        model.setIsEnabled(1);
        model.setTestStatus(1);
        when(models.getModelByIdFromCache("memory-snapshot")).thenReturn(model);
        var assembler = mock(ModelRuntimeAssembler.class);
        when(assembler.buildRuntimeModel(model)).thenAnswer(invocation ->
                new HashMap<>(Map.of("typed_config", Map.of("type", "managed_memory"))));
        var voices = mock(AgentVoicePrintDao.class);
        var voice = new xiaozhi.modules.agent.entity.AgentVoicePrintEntity();
        voice.setId("speaker-bound");
        voice.setSourceName("trusted speaker");
        when(voices.selectBoundByAgentId("agent-snapshot")).thenReturn(List.of(voice));
        var voiceApi = mock(VoiceprintApiClient.class);
        when(voiceApi.getConfiguredEndpoint()).thenReturn(new VoiceprintApiClient.Endpoint(
                java.net.URI.create("http://voiceprint.invalid"), "test-placeholder"));
        var facts = mock(xiaozhi.modules.memory.dao.MemoryFactDao.class);
        var privateFact = new xiaozhi.modules.memory.entity.MemoryFactEntity();
        privateFact.setAgentId("agent-snapshot");
        privateFact.setStatus("active");
        privateFact.setCanonicalText("trusted private fact");
        privateFact.setPayload("{\"speaker_id\":\"speaker-bound\"}");
        when(facts.selectList(any())).thenReturn(List.of(privateFact));
        var snapshots = new ManagedMemorySnapshotService(facts,
                mock(xiaozhi.modules.memory.dao.MemoryForgetTombstoneDao.class));
        var service = new ConfigServiceImpl(params, devices, models, assembler, agents,
                mock(AgentTemplateService.class), mock(RedisUtils.class), mock(TimbreService.class),
                mock(AgentPluginMappingService.class), mock(AgentMcpAccessPointService.class),
                mock(AgentContextProviderService.class), mock(AgentFeishuService.class),
                mock(VoiceCloneService.class), voices, voiceApi, mock(CorrectWordFileService.class),
                mock(UserPluginConfigService.class), mock(AgentSemanticSkillService.class), snapshots);

        Map<String, Object> runtime = service.getAgentModels("device-snapshot", Map.of());
        var snapshot = (Map<?, ?>) runtime.get("managed_memory_snapshot");
        assertEquals("ready", snapshot.get("status"));
        assertEquals("agent-snapshot", snapshot.get("agent_id"));
        assertEquals("device-snapshot", snapshot.get("device_id"));
        assertEquals("", snapshot.get("shared_context"));
        assertEquals(Map.of("speaker-bound", "- trusted private fact\n"), snapshot.get("speaker_contexts"));
        verify(voices).selectBoundByAgentId("agent-snapshot");
        verify(facts).selectList(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void devicePrivateConfigIncludesDeviceSpeechRoutingWhileAgentConfigUsesServerDefaults() {
        DeviceService deviceService = mock(DeviceService.class);
        AgentService agentService = mock(AgentService.class);
        DeviceEntity device = new DeviceEntity();
        device.setAgentId("agent-speech");
        device.setSpeechInputMode("device");
        device.setSpeechOutputMode("auto");
        when(deviceService.getDeviceByMacAddress("device-mac")).thenReturn(device);

        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-speech");
        agent.setIntentModelId("Intent_nointent");
        when(agentService.getAgentById("agent-speech")).thenReturn(agent);

        ConfigServiceImpl service = new ConfigServiceImpl(
                mock(SysParamsService.class), deviceService, mock(ModelConfigService.class),
                mock(ModelRuntimeAssembler.class), agentService, mock(AgentTemplateService.class),
                mock(RedisUtils.class), mock(TimbreService.class), mock(AgentPluginMappingService.class),
                mock(AgentMcpAccessPointService.class), mock(AgentContextProviderService.class),
                mock(AgentFeishuService.class), mock(VoiceCloneService.class), mock(AgentVoicePrintDao.class),
                mock(VoiceprintApiClient.class), mock(CorrectWordFileService.class),
                mock(UserPluginConfigService.class), mock(AgentSemanticSkillService.class), mock(ManagedMemorySnapshotService.class));

        Map<String, Object> deviceRuntime = service.getAgentModels("device-mac", Map.of());
        Map<String, String> deviceRouting = (Map<String, String>) deviceRuntime.get("speech_routing");
        assertEquals("device", deviceRouting.get("input"));
        assertEquals("auto", deviceRouting.get("output"));

        Map<String, Object> agentRuntime = service.getAgentRuntimeConfig("agent-speech");
        assertEquals(
                Map.of("input", "server", "output", "server"),
                agentRuntime.get("speech_routing"));
    }

    @Test
    void invalidOrMissingStoredSpeechModesFailClosedToServer() {
        DeviceEntity device = new DeviceEntity();
        device.setSpeechInputMode(null);
        device.setSpeechOutputMode("unsupported");

        assertEquals(
                Map.of("input", "server", "output", "server"),
                ConfigServiceImpl.buildSpeechRouting(device));
    }

    @Test
    @SuppressWarnings("unchecked")
    void baseConfigRemainsAvailableWhenSystemTemplateIsMissing() {
        SysParamsService sysParamsService = mock(SysParamsService.class);
        AgentTemplateService agentTemplateService = mock(AgentTemplateService.class);
        RedisUtils redisUtils = mock(RedisUtils.class);
        when(sysParamsService.list(any())).thenReturn(List.of());
        when(agentTemplateService.getDefaultTemplate()).thenReturn(null);

        ConfigServiceImpl service = new ConfigServiceImpl(
                sysParamsService,
                mock(DeviceService.class),
                mock(ModelConfigService.class),
                mock(ModelRuntimeAssembler.class),
                mock(AgentService.class),
                agentTemplateService,
                redisUtils,
                mock(TimbreService.class),
                mock(AgentPluginMappingService.class),
                mock(AgentMcpAccessPointService.class),
                mock(AgentContextProviderService.class),
                mock(AgentFeishuService.class),
                mock(VoiceCloneService.class),
                mock(AgentVoicePrintDao.class),
                mock(VoiceprintApiClient.class),
                mock(CorrectWordFileService.class),
                mock(UserPluginConfigService.class),
                mock(AgentSemanticSkillService.class), mock(ManagedMemorySnapshotService.class));

        Map<String, Object> config = (Map<String, Object>) service.getConfig(false);

        assertEquals(Map.of(), config.get("selected_module"));
        assertEquals(Map.of(), config.get("model_config"));
        verify(redisUtils).set(eq("server:config"), eq(config));
    }

    @Test
    @SuppressWarnings("unchecked")
    void runtimeConfigIncludesAvailableTtsVoiceCatalog() {
        AgentService agentService = mock(AgentService.class);
        TimbreService timbreService = mock(TimbreService.class);
        AgentVoicePrintDao voicePrintDao = mock(AgentVoicePrintDao.class);
        AgentPluginMappingService pluginMappingService = mock(AgentPluginMappingService.class);
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-voice-catalog");
        agent.setTtsModelId("TTS_demo");
        agent.setIntentModelId("Intent_nointent");
        when(agentService.getAgentById("agent-voice-catalog")).thenReturn(agent);
        when(voicePrintDao.selectList(any())).thenReturn(List.of());

        TimbreEntity voice = new TimbreEntity();
        voice.setTtsModelId("TTS_demo");
        voice.setTtsVoice("voice-demo");
        voice.setName("演示音色");
        voice.setLanguages("普通话");
        when(timbreService.listRuntimeVoices("TTS_demo")).thenReturn(List.of(voice));
        when(pluginMappingService.getToolPromptOverrides()).thenReturn(Map.of(
                "reset_conversation_context", "仅在当前用户明确要求忘记此前聊天时调用。"));

        ConfigServiceImpl service = new ConfigServiceImpl(
                mock(SysParamsService.class), mock(DeviceService.class), mock(ModelConfigService.class),
                mock(ModelRuntimeAssembler.class), agentService, mock(AgentTemplateService.class),
                mock(RedisUtils.class), timbreService, pluginMappingService,
                mock(AgentMcpAccessPointService.class), mock(AgentContextProviderService.class),
                mock(AgentFeishuService.class), mock(VoiceCloneService.class), voicePrintDao,
                mock(VoiceprintApiClient.class), mock(CorrectWordFileService.class),
                mock(UserPluginConfigService.class), mock(AgentSemanticSkillService.class), mock(ManagedMemorySnapshotService.class));

        Map<String, Object> runtime = service.getAgentRuntimeConfig("agent-voice-catalog");
        List<Map<String, String>> catalog = (List<Map<String, String>>) runtime.get("tts_voice_catalog");

        assertEquals(1, catalog.size());
        assertEquals("voice-demo", catalog.get(0).get("id"));
        assertEquals("演示音色", catalog.get(0).get("name"));
        assertEquals(
                "仅在当前用户明确要求忘记此前聊天时调用。",
                ((Map<String, String>) runtime.get("tool_prompt_overrides")).get("reset_conversation_context"));
    }

    @Test
    void systemWebFeatureGateFailsClosedForVoiceprint() {
        assertTrue(ConfigServiceImpl.isSystemWebFeatureEnabled(
                "{\"features\":{\"voiceprintRecognition\":{\"enabled\":true}}}",
                "voiceprintRecognition"));
        assertFalse(ConfigServiceImpl.isSystemWebFeatureEnabled(
                "{\"features\":{\"voiceprintRecognition\":{\"enabled\":false}}}",
                "voiceprintRecognition"));
        assertFalse(ConfigServiceImpl.isSystemWebFeatureEnabled(
                "{\"features\":{}}", "voiceprintRecognition"));
        assertFalse(ConfigServiceImpl.isSystemWebFeatureEnabled(
                "not-json", "voiceprintRecognition"));
        assertFalse(ConfigServiceImpl.isSystemWebFeatureEnabled(
                null, "voiceprintRecognition"));
    }

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
    void agentHotwordOverridesOnlyDoubaoSeedAsrRuntime() {
        Map<String, Object> doubaoRuntime = new HashMap<>();
        doubaoRuntime.put("type", "doubao_stream");
        doubaoRuntime.put("boosting_table_id", "model-default");

        ConfigServiceImpl.applyAgentAsrHotword(doubaoRuntime, " agent-hotword-id ");

        assertEquals("agent-hotword-id", doubaoRuntime.get("boosting_table_id"));

        ConfigServiceImpl.applyAgentAsrHotword(doubaoRuntime, "");
        assertEquals(false, doubaoRuntime.containsKey("boosting_table_id"));

        Map<String, Object> otherRuntime = new HashMap<>();
        otherRuntime.put("type", "fun_local");
        ConfigServiceImpl.applyAgentAsrHotword(otherRuntime, "agent-hotword-id");
        assertEquals(false, otherRuntime.containsKey("boosting_table_id"));
    }

    @Test
    void agentRealtimeVoiceOverridesOnlyCompatibleRealtimeAdapter() {
        Map<String, Object> qwenRuntime = new HashMap<>();
        qwenRuntime.put("type", "qwen_omni_realtime");
        qwenRuntime.put("voice", "Ethan");
        ConfigServiceImpl.applyAgentRealtimeVoice(qwenRuntime, " Serena ");
        assertEquals("Serena", qwenRuntime.get("voice"));

        ConfigServiceImpl.applyAgentRealtimeVoice(qwenRuntime, "unknown");
        assertEquals("Serena", qwenRuntime.get("voice"));

        Map<String, Object> doubaoRuntime = new HashMap<>();
        doubaoRuntime.put("type", "doubao_realtime");
        ConfigServiceImpl.applyAgentRealtimeVoice(doubaoRuntime, "account_voice_id");
        assertEquals("account_voice_id", doubaoRuntime.get("voice"));

        Map<String, Object> pipelineRuntime = new HashMap<>();
        pipelineRuntime.put("type", "openai");
        ConfigServiceImpl.applyAgentRealtimeVoice(pipelineRuntime, "Serena");
        assertFalse(pipelineRuntime.containsKey("voice"));
    }

    @Test
    void applyManagedVadParamsReadsSysParamsAndClampsValues() throws Exception {
        SysParamsService sysParamsService = Mockito.mock(SysParamsService.class);
        when(sysParamsService.getValue(Constant.SERVER_VAD_THRESHOLD, true)).thenReturn(null);
        when(sysParamsService.getValue(Constant.SERVER_VAD_THRESHOLD_LOW, true)).thenReturn("1.2");
        when(sysParamsService.getValue(Constant.SERVER_VAD_MIN_SILENCE_DURATION_MS, true)).thenReturn("50");

        ConfigServiceImpl service = new ConfigServiceImpl(sysParamsService, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
        Map<String, Object> runtimeConfig = new HashMap<>();

        service.applyManagedVadParams(runtimeConfig);

        assertEquals(0.5, runtimeConfig.get("threshold"));
        assertEquals(1.0, runtimeConfig.get("threshold_low"));
        assertEquals(100, runtimeConfig.get("min_silence_duration_ms"));
        assertEquals("shadow", ((Map<?, ?>) runtimeConfig.get("speech_gate")).get("mode"));

        runtimeConfig.put("speech_gate", new HashMap<>());
        service.applyManagedVadParams(runtimeConfig);
        assertEquals("shadow", ((Map<?, ?>) runtimeConfig.get("speech_gate")).get("mode"));
    }

    @Test
    void applyManagedVadParamsDoesNotOverwriteExplicitModelValues() throws Exception {
        SysParamsService sysParamsService = Mockito.mock(SysParamsService.class);
        ConfigServiceImpl service = new ConfigServiceImpl(sysParamsService, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
        Map<String, Object> runtimeConfig = new HashMap<>();
        runtimeConfig.put("threshold", 0.82);
        runtimeConfig.put("threshold_low", 0.18);
        runtimeConfig.put("min_silence_duration_ms", 350);
        runtimeConfig.put("speech_gate", Map.of("mode", "enforce", "start_snr_db", 10));

        service.applyManagedVadParams(runtimeConfig);

        assertEquals(0.82, runtimeConfig.get("threshold"));
        assertEquals(0.18, runtimeConfig.get("threshold_low"));
        assertEquals(350, runtimeConfig.get("min_silence_duration_ms"));
        assertEquals("enforce", ((Map<?, ?>) runtimeConfig.get("speech_gate")).get("mode"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void runtimeConfigMergesUserPluginConfigForAgentOwner() {
        SysParamsService sysParamsService = mock(SysParamsService.class);
        AgentService agentService = mock(AgentService.class);
        AgentPluginMappingService mappingService = mock(AgentPluginMappingService.class);
        AgentFeishuService feishuService = mock(AgentFeishuService.class);
        AgentVoicePrintDao voicePrintDao = mock(AgentVoicePrintDao.class);
        UserPluginConfigService userPluginConfigService = mock(UserPluginConfigService.class);
        AgentSemanticSkillService semanticSkillService = mock(AgentSemanticSkillService.class);

        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-a");
        agent.setUserId(7L);
        agent.setIntentModelId("Intent_function_call");
        when(agentService.getAgentById("agent-a")).thenReturn(agent);

        AgentPluginMapping mapping = new AgentPluginMapping();
        mapping.setProviderCode("web_search");
        mapping.setParamInfo("{\"max_results\":\"9\"}");
        when(mappingService.agentPluginParamsByAgentId("agent-a")).thenReturn(List.of(mapping));
        when(userPluginConfigService.mergeRuntimeConfig(
                7L, "web_search", "{\"max_results\":\"9\"}"))
                .thenReturn("{\"provider\":\"tavily\",\"api_key\":\"secret\",\"max_results\":\"9\"}");
        when(feishuService.getRuntimePluginConfig("agent-a")).thenReturn(Map.of());
        when(voicePrintDao.selectList(any())).thenReturn(List.of());

        ConfigServiceImpl service = new ConfigServiceImpl(
                sysParamsService,
                mock(DeviceService.class),
                mock(ModelConfigService.class),
                mock(ModelRuntimeAssembler.class),
                agentService,
                mock(AgentTemplateService.class),
                mock(RedisUtils.class),
                mock(TimbreService.class),
                mappingService,
                mock(AgentMcpAccessPointService.class),
                mock(AgentContextProviderService.class),
                feishuService,
                mock(VoiceCloneService.class),
                voicePrintDao,
                mock(VoiceprintApiClient.class),
                mock(CorrectWordFileService.class),
                userPluginConfigService,
                semanticSkillService, mock(ManagedMemorySnapshotService.class));

        Map<String, Object> runtime = service.getAgentRuntimeConfig("agent-a");
        Map<String, Object> plugins = (Map<String, Object>) runtime.get("plugins");

        assertEquals(
                "{\"provider\":\"tavily\",\"api_key\":\"secret\",\"max_results\":\"9\"}",
                plugins.get("web_search"));
        verify(userPluginConfigService).mergeRuntimeConfig(
                eq(7L), eq("web_search"), eq("{\"max_results\":\"9\"}"));
    }

    @Test
    void runtimeConfigPreservesTextOnlyChatHistorySelectionWhenMemoryIsEnabled() {
        AgentService agentService = mock(AgentService.class);
        AgentVoicePrintDao voicePrintDao = mock(AgentVoicePrintDao.class);
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-text-history");
        agent.setMemModelId("Memory_managed_memory");
        agent.setChatHistoryConf(Constant.ChatHistoryConfEnum.RECORD_TEXT.getCode());
        agent.setIntentModelId("Intent_nointent");
        when(agentService.getAgentById("agent-text-history")).thenReturn(agent);
        when(voicePrintDao.selectList(any())).thenReturn(List.of());

        ConfigServiceImpl service = new ConfigServiceImpl(
                mock(SysParamsService.class),
                mock(DeviceService.class),
                mock(ModelConfigService.class),
                mock(ModelRuntimeAssembler.class),
                agentService,
                mock(AgentTemplateService.class),
                mock(RedisUtils.class),
                mock(TimbreService.class),
                mock(AgentPluginMappingService.class),
                mock(AgentMcpAccessPointService.class),
                mock(AgentContextProviderService.class),
                mock(AgentFeishuService.class),
                mock(VoiceCloneService.class),
                voicePrintDao,
                mock(VoiceprintApiClient.class),
                mock(CorrectWordFileService.class),
                mock(UserPluginConfigService.class),
                mock(AgentSemanticSkillService.class), mock(ManagedMemorySnapshotService.class));

        Map<String, Object> runtime = service.getAgentRuntimeConfig("agent-text-history");

        assertEquals(Constant.ChatHistoryConfEnum.RECORD_TEXT.getCode(), runtime.get("chat_history_conf"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void semanticSkillsAreIncludedWhenIntentIsDisabled() {
        AgentService agentService = mock(AgentService.class);
        AgentSemanticSkillService semanticSkillService = mock(AgentSemanticSkillService.class);
        AgentVoicePrintDao voicePrintDao = mock(AgentVoicePrintDao.class);

        AgentInfoVO agent = new AgentInfoVO();
        agent.setId("agent-skill");
        agent.setIntentModelId("Intent_nointent");
        when(agentService.getAgentById("agent-skill")).thenReturn(agent);
        when(voicePrintDao.selectList(any())).thenReturn(List.of());

        SemanticSkillTriggerRuleDTO rule = new SemanticSkillTriggerRuleDTO();
        rule.setType("all_keywords");
        rule.setTerms(List.of("惠易公司", "介绍"));
        AgentSemanticSkillVO skill = new AgentSemanticSkillVO();
        skill.setId("skill-1");
        skill.setSkillName("惠易公司介绍");
        skill.setTriggerRules(List.of(rule));
        skill.setResponseText("固定播报");
        skill.setPriority(100);
        skill.setSort(1);
        when(semanticSkillService.listEnabled("agent-skill")).thenReturn(List.of(skill));

        ConfigServiceImpl service = new ConfigServiceImpl(
                mock(SysParamsService.class),
                mock(DeviceService.class),
                mock(ModelConfigService.class),
                mock(ModelRuntimeAssembler.class),
                agentService,
                mock(AgentTemplateService.class),
                mock(RedisUtils.class),
                mock(TimbreService.class),
                mock(AgentPluginMappingService.class),
                mock(AgentMcpAccessPointService.class),
                mock(AgentContextProviderService.class),
                mock(AgentFeishuService.class),
                mock(VoiceCloneService.class),
                voicePrintDao,
                mock(VoiceprintApiClient.class),
                mock(CorrectWordFileService.class),
                mock(UserPluginConfigService.class),
                semanticSkillService, mock(ManagedMemorySnapshotService.class));

        Map<String, Object> runtime = service.getAgentRuntimeConfig("agent-skill");
        List<Map<String, Object>> skills = (List<Map<String, Object>>) runtime.get("semantic_skills");

        assertEquals(1, skills.size());
        assertEquals("惠易公司介绍", skills.get(0).get("name"));
        assertEquals("固定播报", skills.get(0).get("response_text"));
        assertEquals(List.of(rule), skills.get(0).get("trigger_rules"));
        assertEquals(Map.of(), runtime.get("plugins"));
    }
}
