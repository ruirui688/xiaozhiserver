package xiaozhi.modules.config.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.redis.RedisKeys;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.utils.ConvertUtils;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.agent.dao.AgentVoicePrintDao;
import xiaozhi.modules.agent.entity.AgentContextProviderEntity;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.entity.AgentPluginMapping;
import xiaozhi.modules.agent.entity.AgentTemplateEntity;
import xiaozhi.modules.agent.entity.AgentVoicePrintEntity;
import xiaozhi.modules.agent.service.AgentContextProviderService;
import xiaozhi.modules.agent.service.AgentMcpAccessPointService;
import xiaozhi.modules.agent.service.AgentPluginMappingService;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.agent.voiceprint.VoiceprintApiClient;
import xiaozhi.modules.correctword.service.CorrectWordFileService;
import xiaozhi.modules.correctword.vo.CorrectWordSimpleVO;
import xiaozhi.modules.config.service.ConfigService;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.impl.ModelRuntimeAssembler;
import xiaozhi.modules.sys.dto.SysParamsDTO;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.timbre.vo.TimbreDetailsVO;
import xiaozhi.modules.voiceclone.entity.VoiceCloneEntity;
import xiaozhi.modules.voiceclone.service.VoiceCloneService;

@Service
@AllArgsConstructor
@Slf4j
public class ConfigServiceImpl implements ConfigService {
    private final SysParamsService sysParamsService;
    private final DeviceService deviceService;
    private final ModelConfigService modelConfigService;
    private final ModelRuntimeAssembler modelRuntimeAssembler;
    private final AgentService agentService;
    private final AgentTemplateService agentTemplateService;
    private final RedisUtils redisUtils;
    private final TimbreService timbreService;
    private final AgentPluginMappingService agentPluginMappingService;
    private final AgentMcpAccessPointService agentMcpAccessPointService;
    private final AgentContextProviderService agentContextProviderService;
    private final VoiceCloneService cloneVoiceService;
    private final AgentVoicePrintDao agentVoicePrintDao;
    private final VoiceprintApiClient voiceprintApiClient;
    private final CorrectWordFileService correctWordFileService;

    @Override
    public Object getConfig(Boolean isCache) {
        if (isCache) {
            // 先从Redis获取配置
            Object cachedConfig = redisUtils.get(RedisKeys.getServerConfigKey());
            if (cachedConfig != null) {
                return cachedConfig;
            }
        }

        // 构建配置信息
        Map<String, Object> result = new HashMap<>();
        buildConfig(result);

        // 查询默认智能体
        AgentTemplateEntity agent = agentTemplateService.getDefaultTemplate();
        if (agent == null) {
            throw new RenException(ErrorCode.AGENT_TEMPLATE_NOT_FOUND);
        }

        // 构建模块配置
        buildModuleConfig(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                agent.getVadModelId(),
                agent.getAsrModelId(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                result,
                isCache);

        // 将配置存入Redis
        // TODO: The full config including API keys and secrets is cached in Redis.
        // Consider encrypting sensitive values before caching (e.g. AES-GCM with a
        // per-deployment key stored as an env var or Vault secret).
        redisUtils.set(RedisKeys.getServerConfigKey(), result);

        return result;
    }

    @Override
    public Map<String, Object> getAgentModels(String macAddress, Map<String, String> selectedModule) {
        // 检查是否为管理控制台请求
        String redisKey = RedisKeys.getTmpRegisterMacKey(macAddress);
        Object isAdminRequest = redisUtils.get(redisKey);

        if (isAdminRequest != null && "true".equals(isAdminRequest)) {
            // 管理控制台请求，返回getConfig的结果
            redisUtils.delete(redisKey); // 使用后清理
            return (Map<String, Object>) getConfig(true);
        }
        // 根据MAC地址查找设备
        DeviceEntity device = deviceService.getDeviceByMacAddress(macAddress);
        if (device == null) {
            // 如果设备，去redis里看看有没有需要连接的设备
            String cachedCode = deviceService.geCodeByDeviceId(macAddress);
            if (StringUtils.isNotBlank(cachedCode)) {
                throw new RenException(ErrorCode.OTA_DEVICE_NEED_BIND, cachedCode);
            }
            throw new RenException(ErrorCode.OTA_DEVICE_NOT_FOUND);
        }

        // 获取智能体信息
        AgentEntity agent = agentService.getAgentById(device.getAgentId());
        if (agent == null) {
            throw new RenException(ErrorCode.AGENT_NOT_FOUND);
        }
        return buildAgentRuntimeConfig(agent);
    }

    @Override
    public Map<String, Object> getAgentRuntimeConfig(String agentId) {
        if (StringUtils.isBlank(agentId)) {
            throw new RenException(ErrorCode.AGENT_NOT_FOUND);
        }
        AgentEntity agent = agentService.getAgentById(agentId);
        if (agent == null) {
            throw new RenException(ErrorCode.AGENT_NOT_FOUND);
        }
        return buildAgentRuntimeConfig(agent);
    }

    @Override
    public List<String> getCorrectWords(String macAddress) {
        DeviceEntity device = deviceService.getDeviceByMacAddress(macAddress);
        if (device == null) {
            return Collections.emptyList();
        }
        List<CorrectWordSimpleVO> items = correctWordFileService.getAllItemsByAgentId(device.getAgentId());
        return items.stream()
                .map(item -> item.getSourceWord() + "|" + item.getTargetWord())
                .collect(Collectors.toList());
    }

    private Map<String, Object> buildAgentRuntimeConfig(AgentEntity agent) {
        String voice = null;
        String referenceAudio = null;
        String referenceText = null;
        String language = null;
        TimbreDetailsVO timbre = timbreService.get(agent.getTtsVoiceId());
        if (timbre != null) {
            voice = timbre.getTtsVoice();
            referenceAudio = timbre.getReferenceAudio();
            referenceText = timbre.getReferenceText();
            if (StringUtils.isNotBlank(agent.getTtsLanguage())) {
                language = agent.getTtsLanguage();
            } else if (StringUtils.isNotBlank(timbre.getLanguages())) {
                language = timbre.getLanguages().split("、")[0].trim();
            }
        } else {
            VoiceCloneEntity voicePrint = cloneVoiceService.selectById(agent.getTtsVoiceId());
            if (voicePrint != null) {
                voice = voicePrint.getVoiceId();
                language = StringUtils.isNotBlank(agent.getTtsLanguage()) ? agent.getTtsLanguage() : "普通话";
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("agent_id", agent.getId());
        result.put("agent_name", agent.getAgentName());
        String deviceMaxOutputSize = sysParamsService.getValue("device_max_output_size", true);
        result.put("device_max_output_size", deviceMaxOutputSize);

        Integer chatHistoryConf = agent.getChatHistoryConf();
        if (agent.getMemModelId() != null && agent.getMemModelId().equals(Constant.MEMORY_NO_MEM)) {
            chatHistoryConf = Constant.ChatHistoryConfEnum.IGNORE.getCode();
        } else if (agent.getMemModelId() != null
                && !agent.getMemModelId().equals(Constant.MEMORY_NO_MEM)
                && agent.getChatHistoryConf() == null) {
            chatHistoryConf = Constant.ChatHistoryConfEnum.RECORD_TEXT_AUDIO.getCode();
        }
        result.put("chat_history_conf", chatHistoryConf);

        if (!Objects.equals(agent.getIntentModelId(), "Intent_nointent")) {
            List<AgentPluginMapping> pluginMappings = agentPluginMappingService.agentPluginParamsByAgentId(agent.getId());
            if (pluginMappings != null && !pluginMappings.isEmpty()) {
                Map<String, Object> pluginParams = new HashMap<>();
                for (AgentPluginMapping pluginMapping : pluginMappings) {
                    pluginParams.put(pluginMapping.getProviderCode(), pluginMapping.getParamInfo());
                }
                result.put("plugins", pluginParams);
            }
        }

        String mcpEndpoint = agentMcpAccessPointService.getAgentMcpAccessAddress(agent.getId());
        if (StringUtils.isNotBlank(mcpEndpoint) && mcpEndpoint.startsWith("ws")) {
            result.put("mcp_endpoint", mcpEndpoint.replace("/mcp/", "/call/"));
        }

        AgentContextProviderEntity contextProviderEntity = agentContextProviderService.getByAgentId(agent.getId());
        if (contextProviderEntity != null && contextProviderEntity.getContextProviders() != null
                && !contextProviderEntity.getContextProviders().isEmpty()) {
            result.put("context_providers", contextProviderEntity.getContextProviders());
        }

        buildVoiceprintConfig(agent.getId(), result);
        buildModuleConfig(
                agent.getAgentName(),
                agent.getSystemPrompt(),
                agent.getSummaryMemory(),
                voice,
                referenceAudio,
                referenceText,
                language,
                agent.getTtsVolume(),
                agent.getTtsRate(),
                agent.getTtsPitch(),
                agent.getVadModelId(),
                agent.getAsrModelId(),
                agent.getLlmModelId(),
                agent.getVllmModelId(),
                agent.getSlmModelId(),
                agent.getTtsModelId(),
                agent.getMemModelId(),
                agent.getIntentModelId(),
                null,
                result,
                true);
        return result;
    }

    /**
     * 构建配置信息
     * 
     * @param config 系统参数列表
     * @return 配置信息
     */
    private Object buildConfig(Map<String, Object> config) {

        // 查询所有系统参数
        List<SysParamsDTO> paramsList = sysParamsService.list(new HashMap<>());

        for (SysParamsDTO param : paramsList) {
            String[] keys = param.getParamCode().split("\\.");
            Map<String, Object> current = config;

            // 遍历除最后一个key之外的所有key
            for (int i = 0; i < keys.length - 1; i++) {
                String key = keys[i];
                if (!current.containsKey(key)) {
                    current.put(key, new HashMap<String, Object>());
                }
                current = (Map<String, Object>) current.get(key);
            }

            // 处理最后一个key
            String lastKey = keys[keys.length - 1];
            String value = param.getParamValue();

            // 根据valueType转换值
            switch (param.getValueType().toLowerCase()) {
                case "number":
                    try {
                        double doubleValue = Double.parseDouble(value);
                        // 如果数值是整数形式，则转换为Integer
                        if (doubleValue == (int) doubleValue) {
                            current.put(lastKey, (int) doubleValue);
                        } else {
                            current.put(lastKey, doubleValue);
                        }
                    } catch (NumberFormatException e) {
                        current.put(lastKey, value);
                    }
                    break;
                case "boolean":
                    current.put(lastKey, Boolean.parseBoolean(value));
                    break;
                case "array":
                    // 将分号分隔的字符串转换为数字数组
                    List<String> list = new ArrayList<>();
                    for (String num : value.split(";")) {
                        if (StringUtils.isNotBlank(num)) {
                            list.add(num.trim());
                        }
                    }
                    current.put(lastKey, list);
                    break;
                case "json":
                    try {
                        current.put(lastKey, JsonUtils.parseObject(value, Object.class));
                    } catch (Exception e) {
                        current.put(lastKey, value);
                    }
                    break;
                default:
                    current.put(lastKey, value);
            }
        }

        return config;
    }

    /**
     * 构建声纹配置信息
     * 
     * @param agentId 智能体ID
     * @param result  结果Map
     */
    private void buildVoiceprintConfig(String agentId, Map<String, Object> result) {
        try {
            // 获取智能体关联的声纹信息（不需要用户权限验证）
            List<AgentVoicePrintEntity> voiceprints = getVoiceprintsByAgentId(agentId);
            if (voiceprints == null || voiceprints.isEmpty()) {
                return;
            }
            VoiceprintApiClient.Endpoint endpoint = voiceprintApiClient.getConfiguredEndpoint();

            // 使用结构化字段，避免姓名或描述中的逗号破坏运行时解析。
            List<Map<String, String>> speakers = new ArrayList<>();
            for (AgentVoicePrintEntity voiceprint : voiceprints) {
                Map<String, String> speaker = new HashMap<>();
                speaker.put("speaker_id", voiceprint.getId());
                speaker.put("name", voiceprint.getSourceName());
                speaker.put("description", StringUtils.defaultString(voiceprint.getIntroduce()));
                speaker.put(
                        "user_id",
                        StringUtils.defaultIfBlank(
                                voiceprint.getRemoteUserId(), VoiceprintApiClient.LEGACY_USER_ID));
                speakers.add(speaker);
            }

            // 构建声纹配置
            Map<String, Object> voiceprintConfig = new HashMap<>();
            voiceprintConfig.put("url", endpoint.apiRoot().toString());
            voiceprintConfig.put("token", endpoint.token());
            voiceprintConfig.put("user_id", agentId);
            voiceprintConfig.put("speakers", speakers);
            voiceprintConfig.put("timeout_seconds", 5);
            voiceprintConfig.put(
                    "sample_seconds",
                    readBoundedDoubleParam(Constant.SERVER_VOICEPRINT_SAMPLE_SECONDS, 1.0, 0.5, 5.0));
            voiceprintConfig.put("result_wait_ms", 300);

            // 获取声纹识别相似度阈值，默认0.4
            String thresholdStr = sysParamsService.getValue("server.voiceprint_similarity_threshold", true);
            if (StringUtils.isNotBlank(thresholdStr) && !"null".equals(thresholdStr)) {
                try {
                    double threshold = Double.parseDouble(thresholdStr);
                    voiceprintConfig.put("similarity_threshold", threshold);
                } catch (NumberFormatException e) {
                    // 如果解析失败，使用默认值0.4
                    voiceprintConfig.put("similarity_threshold", 0.4);
                }
            } else {
                voiceprintConfig.put("similarity_threshold", 0.4);
            }

            result.put("voiceprint", voiceprintConfig);
        } catch (Exception e) {
            // 声纹配置获取失败时不影响其他功能
            log.warn("获取声纹配置失败: agentId={}, errorType={}", agentId, e.getClass().getSimpleName());
        }
    }

    /**
     * 获取智能体关联的声纹信息
     * 
     * @param agentId 智能体ID
     * @return 声纹信息列表
     */
    private List<AgentVoicePrintEntity> getVoiceprintsByAgentId(String agentId) {
        LambdaQueryWrapper<AgentVoicePrintEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AgentVoicePrintEntity::getAgentId, agentId);
        queryWrapper.orderByAsc(AgentVoicePrintEntity::getCreateDate);
        return agentVoicePrintDao.selectList(queryWrapper);
    }

    private double readBoundedDoubleParam(String paramCode, double defaultValue, double minimum, double maximum) {
        String value = sysParamsService.getValue(paramCode, true);
        if (StringUtils.isBlank(value) || "null".equals(value)) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(value);
            return Math.min(maximum, Math.max(minimum, parsed));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 构建模块配置
     * 
     * @param prompt         提示词
     * @param voice          音色
     * @param referenceAudio 参考音频路径
     * @param referenceText  参考文本
     * @param vadModelId     VAD模型ID
     * @param asrModelId     ASR模型ID
     * @param llmModelId     LLM模型ID
     * @param ttsModelId     TTS模型ID
     * @param memModelId     记忆模型ID
     * @param intentModelId  意图模型ID
     * @param result         结果Map
     */
    private void buildModuleConfig(
            String assistantName,
            String prompt,
            String summaryMemory,
            String voice,
            String referenceAudio,
            String referenceText,
            String language,
            Integer ttsVolume,
            Integer ttsRate,
            Integer ttsPitch,
            String vadModelId,
            String asrModelId,
            String llmModelId,
            String vllmModelId,
            String slmModelId,
            String ttsModelId,
            String memModelId,
            String intentModelId,
            String ragModelId,
            Map<String, Object> result,
            boolean isCache) {
        Map<String, String> selectedModule = new HashMap<>();
        Map<String, Object> modelConfig = new HashMap<>();

        String[] modelTypes = { "VAD", "ASR", "TTS", "Memory", "Intent", "LLM", "VLLM", "SLM", "RAG" };
        String[] modelIds = { vadModelId, asrModelId, ttsModelId, memModelId, intentModelId, llmModelId, vllmModelId,
                slmModelId, ragModelId };
        String intentLLMModelId = null;
        String memLocalShortLLMModelId = null;

        for (int i = 0; i < modelIds.length; i++) {
            ModelConfigEntity model = resolveUsableModel(modelTypes[i], modelIds[i]);
            if (model == null) {
                continue;
            }
            Map<String, Object> modelRuntime = buildModelConfig(model);
            Map<String, Object> runtimeConfig = typedConfig(modelRuntime);
            if (runtimeConfig != null) {
                // 如果是TTS类型，添加private_voice属性
                if ("TTS".equals(modelTypes[i])) {
                    if (voice != null)
                        runtimeConfig.put("private_voice", voice);
                    if (referenceAudio != null)
                        runtimeConfig.put("ref_audio", referenceAudio);
                    if (referenceText != null)
                        runtimeConfig.put("ref_text", referenceText);
                    if (language != null)
                        runtimeConfig.put("language", language);
                    if (ttsVolume != null)
                        runtimeConfig.put("ttsVolume", ttsVolume);
                    if (ttsRate != null)
                        runtimeConfig.put("ttsRate", ttsRate);
                    if (ttsPitch != null)
                        runtimeConfig.put("ttsPitch", ttsPitch);

                    // 火山引擎声音克隆需要替换resource_id
                    if (Constant.VOICE_CLONE_HUOSHAN_DOUBLE_STREAM.equals(runtimeConfig.get("type"))) {
                        // 如果voice是”S_”开头的，使用seed-icl-1.0
                        if (voice != null && voice.startsWith("S_")) {
                            runtimeConfig.put("resource_id", "seed-icl-1.0");
                        }
                    }
                }
                // 如果是Intent类型，且type=intent_llm，则给他添加附加模型
                if ("Intent".equals(modelTypes[i])) {
                    if ("intent_llm".equals(runtimeConfig.get("type"))) {
                        intentLLMModelId = (String) runtimeConfig.get("llm");
                        if (StringUtils.isNotBlank(intentLLMModelId) && intentLLMModelId.equals(llmModelId)) {
                            intentLLMModelId = null;
                        }
                    }
                    if (runtimeConfig.get("functions") != null) {
                        String functionStr = (String) runtimeConfig.get("functions");
                        if (StringUtils.isNotBlank(functionStr)) {
                            String[] functions = functionStr.split(";");
                            runtimeConfig.put("functions", functions);
                        }
                    }
                }
                if ("Memory".equals(modelTypes[i])) {
                    if ("mem_local_short".equals(runtimeConfig.get("type"))) {
                        memLocalShortLLMModelId = (String) runtimeConfig.get("llm");
                        if (StringUtils.isNotBlank(memLocalShortLLMModelId)
                                && memLocalShortLLMModelId.equals(llmModelId)) {
                            memLocalShortLLMModelId = null;
                        }
                    }
                }
                modelConfig.put(model.getId(), modelRuntime);
                // 如果是LLM类型，且intentLLMModelId不为空，则添加附加模型
                if ("LLM".equals(modelTypes[i])) {
                    if (StringUtils.isNotBlank(intentLLMModelId)) {
                        if (!modelConfig.containsKey(intentLLMModelId)) {
                            // 修改这里：添加isMaskSensitive=false参数
                            ModelConfigEntity intentLLM = modelConfigService.getModelByIdFromCache(intentLLMModelId);
                            modelConfig.put(intentLLM.getId(), buildModelConfig(intentLLM));
                        }
                    }
                    if (StringUtils.isNotBlank(memLocalShortLLMModelId)) {
                        if (!modelConfig.containsKey(memLocalShortLLMModelId)) {
                            // 修改这里：添加isMaskSensitive=false参数
                            ModelConfigEntity memLocalShortLLM = modelConfigService
                                    .getModelByIdFromCache(memLocalShortLLMModelId);
                            modelConfig.put(memLocalShortLLM.getId(), buildModelConfig(memLocalShortLLM));
                        }
                    }
                    // LLM也返回所选的SLM，如果同名id则不重复显示
                    if (StringUtils.isNotBlank(slmModelId) && !slmModelId.equals(llmModelId)) {
                        if (!modelConfig.containsKey(slmModelId)) {
                            ModelConfigEntity slmModel = modelConfigService.getModelByIdFromCache(slmModelId);
                            if (slmModel != null && slmModel.getConfigJson() != null) {
                                modelConfig.put(slmModel.getId(), buildModelConfig(slmModel));
                            }
                        }
                    }
                }
            }

            selectedModule.put(modelTypes[i], model.getId());
        }

        result.put("selected_module", selectedModule);
        result.put("model_config", modelConfig);
        if (StringUtils.isNotBlank(prompt)) {
            prompt = prompt.replace("{{assistant_name}}", StringUtils.isBlank(assistantName) ? "小智" : assistantName);
        }
        result.put("prompt", prompt);
        result.put("summaryMemory", summaryMemory);
    }

    private Map<String, Object> buildModelConfig(ModelConfigEntity model) {
        Map<String, Object> config = modelRuntimeAssembler.buildRuntimeModel(model);
        config.put("typed_config", mergeRuntimeTypedConfig(model, config.get("typed_config")));
        return config;
    }

    static Map<String, Object> mergeRuntimeTypedConfig(ModelConfigEntity model, Object assembledTypedConfig) {
        Map<String, Object> merged = new HashMap<>();
        if (model != null && model.getConfigJson() != null) {
            merged.putAll(model.getConfigJson());
        }
        if (assembledTypedConfig instanceof Map<?, ?> typedConfig) {
            for (Map.Entry<?, ?> entry : typedConfig.entrySet()) {
                if (entry.getKey() != null) {
                    merged.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> typedConfig(Map<String, Object> modelRuntime) {
        Object typedConfig = modelRuntime.get("typed_config");
        if (typedConfig instanceof Map<?, ?>) {
            return (Map<String, Object>) typedConfig;
        }
        Map<String, Object> emptyConfig = new HashMap<>();
        modelRuntime.put("typed_config", emptyConfig);
        return emptyConfig;
    }

    private ModelConfigEntity resolveUsableModel(String modelType, String modelId) {
        if (StringUtils.isBlank(modelId)) {
            return null;
        }

        ModelConfigEntity model = modelConfigService.getModelByIdFromCache(modelId);
        if (isUsableModel(modelType, model)) {
            return model;
        }
        return null;
    }

    private boolean isUsableModel(String modelType, ModelConfigEntity model) {
        return model != null
                && modelType.equalsIgnoreCase(model.getModelType())
                && model.getIsEnabled() != null
                && model.getIsEnabled() == 1
                && model.getTestStatus() != null
                && model.getTestStatus() == 1;
    }
}
