package xiaozhi.modules.config.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.impl.ModelRuntimeAssembler;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.userplugin.service.UserPluginConfigService;
import xiaozhi.modules.voiceclone.service.VoiceCloneService;

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
    void applyManagedVadParamsReadsSysParamsAndClampsValues() throws Exception {
        SysParamsService sysParamsService = Mockito.mock(SysParamsService.class);
        when(sysParamsService.getValue(Constant.SERVER_VAD_THRESHOLD, true)).thenReturn(null);
        when(sysParamsService.getValue(Constant.SERVER_VAD_THRESHOLD_LOW, true)).thenReturn("1.2");
        when(sysParamsService.getValue(Constant.SERVER_VAD_MIN_SILENCE_DURATION_MS, true)).thenReturn("50");

        ConfigServiceImpl service = new ConfigServiceImpl(sysParamsService, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        Map<String, Object> runtimeConfig = new HashMap<>();

        service.applyManagedVadParams(runtimeConfig);

        assertEquals(0.5, runtimeConfig.get("threshold"));
        assertEquals(1.0, runtimeConfig.get("threshold_low"));
        assertEquals(100, runtimeConfig.get("min_silence_duration_ms"));
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
                semanticSkillService);

        Map<String, Object> runtime = service.getAgentRuntimeConfig("agent-a");
        Map<String, Object> plugins = (Map<String, Object>) runtime.get("plugins");

        assertEquals(
                "{\"provider\":\"tavily\",\"api_key\":\"secret\",\"max_results\":\"9\"}",
                plugins.get("web_search"));
        verify(userPluginConfigService).mergeRuntimeConfig(
                eq(7L), eq("web_search"), eq("{\"max_results\":\"9\"}"));
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
                semanticSkillService);

        Map<String, Object> runtime = service.getAgentRuntimeConfig("agent-skill");
        List<Map<String, Object>> skills = (List<Map<String, Object>>) runtime.get("semantic_skills");

        assertEquals(1, skills.size());
        assertEquals("惠易公司介绍", skills.get(0).get("name"));
        assertEquals("固定播报", skills.get(0).get("response_text"));
        assertEquals(List.of(rule), skills.get(0).get("trigger_rules"));
        assertEquals(false, runtime.containsKey("plugins"));
    }
}
