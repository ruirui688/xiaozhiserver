package xiaozhi.modules.agent.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.agent.dto.AgentTagDTO;
import xiaozhi.modules.agent.dto.AgentTemplateApplyDTO;
import xiaozhi.modules.agent.dto.AgentTemplateSaveAsDTO;
import xiaozhi.modules.agent.dto.AgentTemplateSnapshotDTO;
import xiaozhi.modules.agent.dto.AgentUpdateDTO;
import xiaozhi.modules.agent.dto.ContextProviderDTO;
import xiaozhi.modules.agent.entity.AgentTemplateEntity;
import xiaozhi.modules.agent.runtime.AgentRuntimeConfigRefreshEvent;
import xiaozhi.modules.agent.service.AgentFeishuService;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.service.AgentTagService;
import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.agent.service.AgentWorkspaceTemplateService;
import xiaozhi.modules.agent.service.AgentVoicePrintService;
import xiaozhi.modules.agent.skill.dto.AgentSemanticSkillSaveDTO;
import xiaozhi.modules.agent.skill.service.AgentSemanticSkillService;
import xiaozhi.modules.agent.skill.vo.AgentSemanticSkillVO;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.agent.vo.AgentTemplateApplyResultVO;
import xiaozhi.modules.agent.vo.AgentTemplateSaveResultVO;
import xiaozhi.modules.agent.vo.AgentTemplateSummaryVO;
import xiaozhi.modules.correctword.dao.CorrectWordFileDao;
import xiaozhi.modules.correctword.entity.CorrectWordFileEntity;
import xiaozhi.modules.knowledge.entity.KnowledgeBaseEntity;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.model.dao.ModelProviderDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.entity.ModelProviderEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.timbre.entity.TimbreEntity;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@Service
@RequiredArgsConstructor
public class AgentWorkspaceTemplateServiceImpl implements AgentWorkspaceTemplateService {
    private static final int SNAPSHOT_SCHEMA_VERSION = 2;
    private static final int MAX_CONTEXT_PROVIDERS = 20;
    private static final int MAX_CONTEXT_HEADERS = 20;
    private static final Set<String> SENSITIVE_HEADER_NAMES = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "api-key", "x-api-key", "apikey", "access-token", "x-access-token",
            "token", "secret", "x-auth-token");

    private final AgentTemplateService templateService;
    private final AgentService agentService;
    private final AgentTagService tagService;
    private final AgentSemanticSkillService skillService;
    private final AgentVoicePrintService voicePrintService;
    private final AgentFeishuService feishuService;
    private final ModelConfigService modelConfigService;
    private final ModelProviderDao modelProviderDao;
    private final KnowledgeBaseService knowledgeBaseService;
    private final CorrectWordFileDao correctWordFileDao;
    private final TimbreService timbreService;
    private final WorkspacePermissionService workspacePermissionService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public List<AgentTemplateSummaryVO> listAccessible() {
        String workspaceId = workspacePermissionService.currentWorkspaceId();
        workspacePermissionService.requireRead(workspaceId);
        return templateService.list(new LambdaQueryWrapper<AgentTemplateEntity>()
                        .and(q -> q.isNull(AgentTemplateEntity::getWorkspaceId)
                                .or().eq(AgentTemplateEntity::getWorkspaceId, workspaceId))
                        .orderByAsc(AgentTemplateEntity::getWorkspaceId)
                        .orderByAsc(AgentTemplateEntity::getSort)
                        .orderByAsc(AgentTemplateEntity::getCreatedAt))
                .stream().map(this::toSummary).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTemplateSaveResultVO saveAs(String agentId, AgentTemplateSaveAsDTO dto) {
        AgentInfoVO source = requireAgentWrite(agentId);
        String workspaceId = source.getWorkspaceId();
        String templateName = StringUtils.trim(dto.getTemplateName());
        long duplicateCount = templateService.count(new LambdaQueryWrapper<AgentTemplateEntity>()
                .eq(AgentTemplateEntity::getWorkspaceId, workspaceId)
                .eq(AgentTemplateEntity::getTemplateName, templateName));
        if (duplicateCount > 0) {
            throw new RenException("当前工作空间已存在同名角色模板");
        }

        List<String> warnings = new ArrayList<>();
        AgentUpdateDTO config = deepCopy(dto.getConfig(), AgentUpdateDTO.class);
        config.setContextProviders(sanitizeContextProviders(config.getContextProviders(), warnings));
        config.setFunctions(filterCapabilityMappings(config.getFunctions(), workspaceId, warnings));
        config.setCorrectWordFileIds(filterCorrectWordFilesForCreate(
                config.getCorrectWordFileIds(), source.getCorrectWordFileIds(), warnings));

        AgentTemplateEntity entity = new AgentTemplateEntity();
        entity.setId(UUID.randomUUID().toString().replace("-", ""));

        AgentTemplateSnapshotDTO snapshot = new AgentTemplateSnapshotDTO();
        snapshot.setSchemaVersion(SNAPSHOT_SCHEMA_VERSION);
        snapshot.setConfig(config);
        snapshot.setTagNames(normalizeTagNames(dto.getTagNames()));
        snapshot.setSemanticSkills(skillService.list(agentId).stream().map(this::toSkillDTO).toList());
        snapshot.setVoicePrintIds(filterVoicePrintBindings(
                voicePrintService.getBoundVoicePrintIds(agentId), workspaceId, warnings));
        snapshot.setFeishuConfig(feishuService.exportForTemplate(agentId, entity.getId()));

        entity.setWorkspaceId(workspaceId);
        entity.setTemplateName(templateName);
        entity.setSourceAgentId(agentId);
        entity.setSnapshotJson(snapshot);
        entity.setAgentName(config.getAgentName());
        entity.setAgentCode(config.getAgentCode());
        copyLegacyColumns(config, entity);
        entity.setSort(nextWorkspaceSort(workspaceId));
        entity.setCreator(SecurityUser.getUserId());
        entity.setCreatedAt(new Date());
        entity.setUpdater(SecurityUser.getUserId());
        entity.setUpdatedAt(new Date());
        templateService.save(entity);

        AgentTemplateSaveResultVO result = new AgentTemplateSaveResultVO();
        result.setTemplate(toSummary(entity));
        result.setWarnings(warnings);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTemplateApplyResultVO apply(
            String agentId, String templateId, AgentTemplateApplyDTO request) {
        AgentInfoVO target = requireAgentWrite(agentId);
        AgentTemplateEntity template = templateService.getById(templateId);
        requireTemplateReadable(template, target.getWorkspaceId());
        if (!request.getExpectedVersion().equals(template.getVersion())) {
            throw new RenException("模板已更新，请刷新页面后重试");
        }

        boolean workspaceSnapshot = template.getSnapshotJson() != null;
        AgentTemplateSnapshotDTO snapshot = workspaceSnapshot
                ? template.getSnapshotJson()
                : legacySnapshot(template);
        if (snapshot.getSchemaVersion() == null || snapshot.getSchemaVersion() > SNAPSHOT_SCHEMA_VERSION) {
            throw new RenException("模板快照版本暂不受支持");
        }

        List<String> skipped = new ArrayList<>();
        AgentUpdateDTO desired = deepCopy(snapshot.getConfig(), AgentUpdateDTO.class);
        filterModels(desired, target, skipped);
        desired.setFunctions(filterCapabilityMappings(desired.getFunctions(), target.getWorkspaceId(), skipped));
        desired.setCorrectWordFileIds(filterExistingCorrectWordFiles(desired.getCorrectWordFileIds(), skipped));
        desired.setContextProviders(sanitizeContextProviders(desired.getContextProviders(), skipped));

        agentService.updateAgentById(agentId, desired, false);
        if (workspaceSnapshot) {
            tagService.saveAgentTags(agentId, List.of(), normalizeTagNames(snapshot.getTagNames()));
            skillService.replaceAll(agentId, snapshot.getSemanticSkills(), SecurityUser.getUserId(), false);
            if (snapshot.getSchemaVersion() >= 2) {
                List<String> voicePrintIds = filterVoicePrintBindings(
                        snapshot.getVoicePrintIds(), target.getWorkspaceId(), skipped);
                voicePrintService.replaceBindings(agentId, voicePrintIds, SecurityUser.getUserId());
                feishuService.replaceFromTemplate(
                        agentId, templateId, snapshot.getFeishuConfig(), SecurityUser.getUserId());
            }
        }
        eventPublisher.publishEvent(AgentRuntimeConfigRefreshEvent.forAgent(agentId, "workspace_template_applied"));

        AgentTemplateApplyResultVO result = new AgentTemplateApplyResultVO();
        result.setAgent(agentService.getAgentById(agentId));
        result.setTagNames(tagService.getTagsByAgentId(agentId).stream().map(AgentTagDTO::getTagName).toList());
        result.setSkippedItems(skipped);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String templateId) {
        AgentTemplateEntity template = templateService.getById(templateId);
        if (template == null || StringUtils.isBlank(template.getWorkspaceId())) {
            throw new RenException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        workspacePermissionService.requireWrite(template.getWorkspaceId());
        templateService.removeById(templateId);
    }

    private AgentInfoVO requireAgentWrite(String agentId) {
        if (!agentService.checkAgentPermission(agentId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.AGENT_NOT_FOUND);
        }
        AgentInfoVO agent = agentService.getAgentById(agentId);
        if (StringUtils.isBlank(agent.getWorkspaceId())) {
            throw new RenException("历史智能体尚未迁移到工作空间，不能使用共享模板");
        }
        workspacePermissionService.requireWrite(agent.getWorkspaceId());
        return agent;
    }

    private void requireTemplateReadable(AgentTemplateEntity template, String targetWorkspaceId) {
        if (template == null) {
            throw new RenException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (StringUtils.isBlank(template.getWorkspaceId())) {
            return;
        }
        if (!StringUtils.equals(template.getWorkspaceId(), targetWorkspaceId)) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        workspacePermissionService.requireRead(template.getWorkspaceId());
    }

    private List<ContextProviderDTO> sanitizeContextProviders(
            List<ContextProviderDTO> providers, List<String> warnings) {
        if (providers == null) {
            return null;
        }
        if (providers.size() > MAX_CONTEXT_PROVIDERS) {
            throw new RenException("上下文源数量不能超过 " + MAX_CONTEXT_PROVIDERS);
        }
        List<ContextProviderDTO> safeProviders = new ArrayList<>();
        boolean removedSensitiveHeader = false;
        for (ContextProviderDTO provider : providers) {
            if (provider == null) {
                continue;
            }
            ContextProviderDTO safe = new ContextProviderDTO();
            safe.setUrl(provider.getUrl());
            Map<String, Object> headers = provider.getHeaders();
            if (headers != null && headers.size() > MAX_CONTEXT_HEADERS) {
                throw new RenException("单个上下文源请求头数量不能超过 " + MAX_CONTEXT_HEADERS);
            }
            Map<String, Object> safeHeaders = new LinkedHashMap<>();
            if (headers != null) {
                for (Map.Entry<String, Object> header : headers.entrySet()) {
                    if (isSensitiveHeader(header.getKey())) {
                        removedSensitiveHeader = true;
                    } else {
                        safeHeaders.put(header.getKey(), header.getValue());
                    }
                }
            }
            safe.setHeaders(safeHeaders);
            safeProviders.add(safe);
        }
        if (removedSensitiveHeader) {
            warnings.add("上下文源中的认证或密钥请求头未写入模板，应用后需要重新配置凭据");
        }
        return safeProviders;
    }

    private boolean isSensitiveHeader(String name) {
        if (StringUtils.isBlank(name)) {
            return false;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return SENSITIVE_HEADER_NAMES.contains(normalized)
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("credential")
                || normalized.contains("password")
                || normalized.contains("api-key");
    }

    private List<AgentUpdateDTO.FunctionInfo> filterCapabilityMappings(
            List<AgentUpdateDTO.FunctionInfo> mappings,
            String workspaceId,
            List<String> warnings) {
        if (mappings == null) {
            return null;
        }
        List<AgentUpdateDTO.FunctionInfo> filtered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AgentUpdateDTO.FunctionInfo mapping : mappings) {
            if (mapping == null || StringUtils.isBlank(mapping.getPluginId()) || !seen.add(mapping.getPluginId())) {
                continue;
            }
            ModelProviderEntity plugin = modelProviderDao.selectById(mapping.getPluginId());
            if (plugin != null && StringUtils.equalsIgnoreCase(plugin.getModelType(), "Plugin")) {
                filtered.add(mapping);
                continue;
            }
            KnowledgeBaseEntity knowledge = knowledgeBaseService.selectById(mapping.getPluginId());
            if (knowledge != null
                    && StringUtils.equals(workspaceId, knowledge.getWorkspaceId())
                    && !Integer.valueOf(0).equals(knowledge.getStatus())) {
                filtered.add(mapping);
                continue;
            }
            warnings.add("已跳过不可用的功能或知识库：" + mapping.getPluginId());
        }
        return filtered;
    }

    private List<String> filterCorrectWordFilesForCreate(
            List<String> requested, List<String> sourceFileIds, List<String> warnings) {
        if (requested == null) {
            return null;
        }
        Set<String> sourceIds = new LinkedHashSet<>(sourceFileIds == null ? List.of() : sourceFileIds);
        List<String> filtered = new ArrayList<>();
        for (String id : new LinkedHashSet<>(requested)) {
            CorrectWordFileEntity file = correctWordFileDao.selectById(id);
            if (file != null && (sourceIds.contains(id) || SecurityUser.getUserId().equals(file.getCreator()))) {
                filtered.add(id);
            } else {
                warnings.add("已跳过无权访问的替换词文件：" + id);
            }
        }
        return filtered;
    }

    private List<String> filterExistingCorrectWordFiles(List<String> requested, List<String> warnings) {
        if (requested == null) {
            return null;
        }
        List<String> filtered = new ArrayList<>();
        for (String id : new LinkedHashSet<>(requested)) {
            if (correctWordFileDao.selectById(id) != null) {
                filtered.add(id);
            } else {
                warnings.add("已跳过已删除的替换词文件：" + id);
            }
        }
        return filtered;
    }

    private List<String> filterVoicePrintBindings(
            List<String> requested, String workspaceId, List<String> warnings) {
        if (requested == null) {
            return List.of();
        }
        List<String> filtered = new ArrayList<>();
        for (String voicePrintId : new LinkedHashSet<>(requested)) {
            String voicePrintWorkspaceId = voicePrintService.getWorkspaceIdByVoicePrintId(voicePrintId);
            if (StringUtils.equals(workspaceId, voicePrintWorkspaceId)) {
                filtered.add(voicePrintId);
            } else {
                warnings.add("已跳过不存在或跨工作空间的声纹绑定：" + voicePrintId);
            }
        }
        return filtered;
    }

    private void filterModels(AgentUpdateDTO desired, AgentInfoVO current, List<String> skipped) {
        desired.setVadModelId(validModelOrNull("VAD", desired.getVadModelId(), skipped));
        String requestedAsr = desired.getAsrModelId();
        String asr = validModelOrNull("ASR", desired.getAsrModelId(), skipped);
        desired.setAsrModelId(asr);
        if (invalidCandidate(requestedAsr, asr)) {
            desired.setAsrHotwordId(null);
            desired.setAsrHotwordIds(null);
        } else if (asr != null && asr.isBlank()) {
            desired.setAsrHotwordId("");
            desired.setAsrHotwordIds(List.of());
        }
        desired.setSlmModelId(validModelOrNull("SLM", desired.getSlmModelId(), skipped));
        desired.setVllmModelId(validModelOrNull("VLLM", desired.getVllmModelId(), skipped));
        String requestedMemory = desired.getMemModelId();
        String memory = validModelOrNull("Memory", requestedMemory, skipped);
        desired.setMemModelId(memory);
        if (invalidCandidate(requestedMemory, memory)) {
            desired.setChatHistoryConf(null);
            desired.setSummaryMemory(null);
        }

        String requestedLlm = desired.getLlmModelId();
        String requestedIntent = desired.getIntentModelId();
        String llm = validModelOrNull("LLM", requestedLlm, skipped);
        String intent = validModelOrNull("Intent", requestedIntent, skipped);
        String effectiveLlm = llm == null ? current.getLlmModelId() : llm;
        String effectiveIntent = intent == null ? current.getIntentModelId() : intent;
        boolean compatible = isLlmIntentCompatible(effectiveLlm, effectiveIntent);
        if (invalidCandidate(requestedLlm, llm)
                || invalidCandidate(requestedIntent, intent)
                || !compatible) {
            desired.setLlmModelId(null);
            desired.setIntentModelId(null);
            if (!compatible) {
                skipped.add("主语言模型与意图模型不兼容，已保留当前组合");
            }
        } else {
            desired.setLlmModelId(llm);
            desired.setIntentModelId(intent);
        }

        String requestedTts = desired.getTtsModelId();
        String tts = validModelOrNull("TTS", requestedTts, skipped);
        if (StringUtils.isNotBlank(tts) && StringUtils.isNotBlank(desired.getTtsVoiceId())) {
            TimbreEntity voice = timbreService.selectById(desired.getTtsVoiceId());
            if (voice == null || !StringUtils.equals(tts, voice.getTtsModelId())) {
                skipped.add("语音合成模型或音色不可用，已保留当前语音配置");
                tts = null;
            }
        }
        desired.setTtsModelId(tts);
        if (invalidCandidate(requestedTts, tts)) {
            desired.setTtsVoiceId(null);
            desired.setTtsLanguage(null);
            desired.setTtsVolume(null);
            desired.setTtsRate(null);
            desired.setTtsPitch(null);
        } else if (tts != null && tts.isBlank()) {
            desired.setTtsVoiceId("");
            desired.setTtsLanguage("");
        }
    }

    private String validModelOrNull(String type, String id, List<String> skipped) {
        if (id == null) {
            return null;
        }
        if (StringUtils.isBlank(id)) {
            return "";
        }
        ModelConfigEntity model = modelConfigService.getModelByIdFromCache(id);
        if (model != null && Integer.valueOf(1).equals(model.getIsEnabled())
                && StringUtils.equalsIgnoreCase(type, model.getModelType())) {
            return id;
        }
        skipped.add("已跳过不可用的" + type + "模型：" + id);
        return null;
    }

    private boolean invalidCandidate(String requested, String resolved) {
        return StringUtils.isNotBlank(requested) && resolved == null;
    }

    private boolean isLlmIntentCompatible(String llmId, String intentId) {
        if (StringUtils.isBlank(llmId) || !"Intent_function_call".equals(intentId)) {
            return true;
        }
        ModelConfigEntity model = modelConfigService.getModelByIdFromCache(llmId);
        JSONObject config = model == null ? null : model.getConfigJson();
        String type = config == null ? null : config.getStr("type");
        if (StringUtils.isBlank(type) && model != null) {
            type = model.getAdapterCode();
        }
        return StringUtils.equalsAnyIgnoreCase(type, "openai", "ollama");
    }

    private AgentTemplateSnapshotDTO legacySnapshot(AgentTemplateEntity template) {
        AgentUpdateDTO config = new AgentUpdateDTO();
        config.setAgentCode(template.getAgentCode());
        config.setAgentName(template.getAgentName());
        config.setAsrModelId(template.getAsrModelId());
        config.setAsrHotwordId(template.getAsrHotwordId());
        config.setAsrHotwordIds(template.getAsrHotwordIds());
        config.setVadModelId(template.getVadModelId());
        config.setLlmModelId(template.getLlmModelId());
        config.setSlmModelId(template.getSlmModelId());
        config.setVllmModelId(template.getVllmModelId());
        config.setTtsModelId(template.getTtsModelId());
        config.setTtsVoiceId(template.getTtsVoiceId());
        config.setTtsLanguage(template.getTtsLanguage());
        config.setTtsVolume(template.getTtsVolume());
        config.setTtsRate(template.getTtsRate());
        config.setTtsPitch(template.getTtsPitch());
        config.setMemModelId(template.getMemModelId());
        config.setIntentModelId(template.getIntentModelId());
        config.setChatHistoryConf(template.getChatHistoryConf());
        config.setSystemPrompt(template.getSystemPrompt());
        config.setSummaryMemory(template.getSummaryMemory());
        config.setLangCode(template.getLangCode());
        config.setLanguage(template.getLanguage());
        AgentTemplateSnapshotDTO snapshot = new AgentTemplateSnapshotDTO();
        snapshot.setConfig(config);
        return snapshot;
    }

    private void copyLegacyColumns(AgentUpdateDTO config, AgentTemplateEntity entity) {
        entity.setAsrModelId(config.getAsrModelId());
        entity.setAsrHotwordId(config.getAsrHotwordId());
        entity.setAsrHotwordIds(config.getAsrHotwordIds());
        entity.setVadModelId(config.getVadModelId());
        entity.setLlmModelId(config.getLlmModelId());
        entity.setSlmModelId(config.getSlmModelId());
        entity.setVllmModelId(config.getVllmModelId());
        entity.setTtsModelId(config.getTtsModelId());
        entity.setTtsVoiceId(config.getTtsVoiceId());
        entity.setTtsLanguage(config.getTtsLanguage());
        entity.setTtsVolume(config.getTtsVolume());
        entity.setTtsRate(config.getTtsRate());
        entity.setTtsPitch(config.getTtsPitch());
        entity.setMemModelId(config.getMemModelId());
        entity.setIntentModelId(config.getIntentModelId());
        entity.setChatHistoryConf(config.getChatHistoryConf());
        entity.setSystemPrompt(config.getSystemPrompt());
        entity.setSummaryMemory(config.getSummaryMemory());
        entity.setLangCode(config.getLangCode());
        entity.setLanguage(config.getLanguage());
    }

    private AgentSemanticSkillSaveDTO toSkillDTO(AgentSemanticSkillVO skill) {
        AgentSemanticSkillSaveDTO dto = new AgentSemanticSkillSaveDTO();
        dto.setSkillName(skill.getSkillName());
        dto.setEnabled(skill.getEnabled());
        dto.setTriggerRules(skill.getTriggerRules());
        dto.setResponseText(skill.getResponseText());
        dto.setPriority(skill.getPriority());
        dto.setSort(skill.getSort());
        return dto;
    }

    private List<String> normalizeTagNames(List<String> names) {
        if (names == null) {
            return new ArrayList<>();
        }
        return names.stream().filter(StringUtils::isNotBlank).map(StringUtils::trim)
                .distinct().limit(50).toList();
    }

    private int nextWorkspaceSort(String workspaceId) {
        return templateService.list(new LambdaQueryWrapper<AgentTemplateEntity>()
                        .eq(AgentTemplateEntity::getWorkspaceId, workspaceId))
                .stream().map(AgentTemplateEntity::getSort).filter(value -> value != null)
                .max(Integer::compareTo).orElse(0) + 1;
    }

    private AgentTemplateSummaryVO toSummary(AgentTemplateEntity entity) {
        AgentTemplateSummaryVO vo = new AgentTemplateSummaryVO();
        vo.setId(entity.getId());
        vo.setTemplateName(StringUtils.defaultIfBlank(entity.getTemplateName(), entity.getAgentName()));
        vo.setAgentName(entity.getAgentName());
        vo.setVersion(entity.getVersion() == null ? 1 : entity.getVersion());
        vo.setScope(StringUtils.isBlank(entity.getWorkspaceId()) ? "SYSTEM" : "WORKSPACE");
        vo.setDeletable(StringUtils.isNotBlank(entity.getWorkspaceId())
                && workspacePermissionService.canWrite(entity.getWorkspaceId()));
        return vo;
    }

    private <T> T deepCopy(Object source, Class<T> type) {
        return JsonUtils.parseObject(JsonUtils.toJsonString(source), type);
    }
}
