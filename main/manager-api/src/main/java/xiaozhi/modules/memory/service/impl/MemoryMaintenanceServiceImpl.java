package xiaozhi.modules.memory.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.page.PageData;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.common.utils.SpringContextUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.dto.AgentChatHistoryDTO;
import xiaozhi.modules.agent.dto.AgentChatSessionDTO;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.llm.service.LLMService;
import xiaozhi.modules.memory.dao.MemoryCandidateDao;
import xiaozhi.modules.memory.dao.MemoryFactDao;
import xiaozhi.modules.memory.dao.MemoryForgetTombstoneDao;
import xiaozhi.modules.memory.dao.MemoryRevisionDao;
import xiaozhi.modules.memory.dto.MemoryRebuildRequestDTO;
import xiaozhi.modules.memory.entity.MemoryCandidateEntity;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.entity.MemoryForgetTombstoneEntity;
import xiaozhi.modules.memory.entity.MemoryRevisionEntity;
import xiaozhi.modules.memory.prompt.PowerMemPrompts;
import xiaozhi.modules.memory.service.MemoryMaintenanceService;
import xiaozhi.modules.memory.service.MemorySettingsService;
import xiaozhi.modules.memory.service.impl.maintenance.MemoryMaintenanceOutputParser;
import xiaozhi.modules.memory.service.impl.maintenance.MemorySpeakerConversationBuilder;
import xiaozhi.modules.memory.utils.MemoryHelper;
import xiaozhi.modules.memory.utils.MemorySpeakerUtils;
import xiaozhi.modules.memory.utils.MemorySpeakerUtils.Registry;
import xiaozhi.modules.memory.utils.MemorySpeakerUtils.SpeakerIdentity;
import xiaozhi.modules.memory.vo.MemoryRebuildResultVO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.impl.ModelRuntimeAssembler;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.workspace.service.WorkspaceService;

@Service
@AllArgsConstructor
@Slf4j
public class MemoryMaintenanceServiceImpl implements MemoryMaintenanceService {

    private final MemoryCandidateDao candidateDao;
    private final MemoryFactDao factDao;
    private final MemoryRevisionDao revisionDao;
    private final MemoryForgetTombstoneDao tombstoneDao;
    private final DeviceDao deviceDao;
    private final AgentDao agentDao;
    private final LLMService llmService;
    private final ModelConfigService modelConfigService;
    private final ModelRuntimeAssembler modelRuntimeAssembler;
    private final AgentChatHistoryService agentChatHistoryService;
    private final MemoryMaintenanceOutputParser outputParser;
    private final MemorySpeakerConversationBuilder conversationBuilder;
    private final TransactionTemplate transactionTemplate;
    private final MemorySettingsService memorySettingsService;

    // ===================== 公开方法 =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemoryRebuildResultVO clearMemories(String agentId, String actorType, String actorId) {
        AgentEntity agent = assertAgentOwner(agentId);
        lockAgentForMemoryUpdate(agent.getId());
        MemoryRebuildResultVO result = new MemoryRebuildResultVO();
        result.setMode("clear");

        List<MemoryFactEntity> facts = factDao.selectList(new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agent.getId()).ne("status", MemoryHelper.STATUS_DELETED));
        for (MemoryFactEntity fact : facts) {
            String before = JsonUtils.toJsonString(fact);
            fact.setStatus(MemoryHelper.STATUS_DELETED);
            fact.setValidTo(new Date());
            fact.setUpdatedAt(new Date());
            factDao.updateById(fact);
            writeRevision(agent.getId(), fact.getMemoryId(), "clear_delete", before, JsonUtils.toJsonString(fact),
                    actorType, actorId, "清空记忆");
        }
        result.setFactsCleared(facts.size());

        List<MemoryCandidateEntity> candidates = candidateDao.selectList(new QueryWrapper<MemoryCandidateEntity>()
                .eq("agent_id", agent.getId()).eq("status", MemoryHelper.STATUS_PENDING));
        for (MemoryCandidateEntity candidate : candidates) {
            candidate.setStatus(MemoryHelper.STATUS_REJECTED);
            candidate.setUpdatedAt(new Date());
            candidateDao.updateById(candidate);
        }
        result.setCandidatesCleared(candidates.size());

        List<MemoryForgetTombstoneEntity> tombstones = tombstoneDao.selectList(
                new QueryWrapper<MemoryForgetTombstoneEntity>()
                        .eq("agent_id", agent.getId()).eq("status", MemoryHelper.STATUS_ACTIVE));
        for (MemoryForgetTombstoneEntity tombstone : tombstones) {
            tombstone.setStatus(MemoryHelper.STATUS_INACTIVE);
            tombstone.setUpdatedAt(new Date());
            tombstoneDao.updateById(tombstone);
        }
        result.setTombstonesCleared(tombstones.size());
        writeRevision(agent.getId(), null, "clear_memory", null, JsonUtils.toJsonString(result),
                actorType, actorId, "清空当前智能体记忆");
        return result;
    }

    @Override
    public MemoryRebuildResultVO rebuildFromRecentChatHistory(String agentId, MemoryRebuildRequestDTO dto,
                                                              String actorType, String actorId) {
        AgentEntity agent = assertAgentOwner(agentId);
        MemoryRebuildRequestDTO request = dto == null ? new MemoryRebuildRequestDTO() : dto;
        int limit = request.getSessionLimit() == null ? 15 : Math.max(1, Math.min(request.getSessionLimit(), 50));
        boolean dryRun = request.getDryRun() == null || request.getDryRun();
        String mode = StringUtils.defaultIfBlank(request.getMode(), "merge");

        MemoryRebuildResultVO result = new MemoryRebuildResultVO();
        result.setSessionLimit(limit);
        result.setDryRun(dryRun);
        result.setMode(mode);

        List<AgentChatSessionDTO> sessions = recentSessions(agent.getId(), limit);
        result.setSessionsRead(sessions.size());
        List<List<AgentChatHistoryDTO>> histories = new ArrayList<>();
        String scopeId = agent.getId();
        for (int i = sessions.size() - 1; i >= 0; i--) {
            AgentChatSessionDTO session = sessions.get(i);
            result.getSessionIds().add(session.getSessionId());
            List<AgentChatHistoryDTO> history = agentChatHistoryService.getChatHistoryBySessionId(
                    agent.getId(), session.getSessionId());
            histories.add(history);
            String sessionScopeId = firstScopeId(history, "");
            if (StringUtils.isNotBlank(sessionScopeId)) {
                scopeId = sessionScopeId;
            }
        }

        List<JSONObject> actions = buildRebuildActions(agent, scopeId, histories, mode);
        List<JSONObject> applicableActions = new ArrayList<>();
        for (JSONObject action : actions) {
            if (applyMaintenanceDecision(agent, scopeId, null, action, getMaintenanceLlmModelId(agent), true, result)) {
                applicableActions.add(action);
                result.setActionsProposed(result.getActionsProposed() + 1);
                result.setCandidatesCreated(result.getCandidatesCreated() + 1);
            }
        }
        if (dryRun || applicableActions.isEmpty()) {
            return result;
        }

        String maintenanceModelId = getMaintenanceLlmModelId(agent);
        boolean clearThenRebuild = "clear_then_rebuild".equalsIgnoreCase(mode);
        String targetScopeId = scopeId;
        MemoryRebuildResultVO appliedResult = transactionTemplate.execute(status -> {
            lockAgentForMemoryUpdate(agent.getId());
            if (clearThenRebuild) {
                MemoryRebuildResultVO cleared = clearMemories(agentId, actorType, actorId);
                result.setFactsCleared(cleared.getFactsCleared());
                result.setCandidatesCleared(cleared.getCandidatesCleared());
                result.setTombstonesCleared(cleared.getTombstonesCleared());
            }

            int applied = 0;
            for (JSONObject action : applicableActions) {
                boolean changed = executeMemoryAction(
                        agent, targetScopeId, null, action, maintenanceModelId, result);
                if (!changed) {
                    throw new IllegalStateException(
                            "重建动作应用失败，已回滚并保留事务开始前的记忆");
                }
                applied++;
            }
            result.setFactsChanged(applied);
            writeRevision(agent.getId(), null, "rebuild_memory", null, JsonUtils.toJsonString(result),
                    actorType, actorId, "从聊天记录重建记忆");
            return result;
        });
        if (appliedResult == null) {
            throw new IllegalStateException("重建事务未返回结果");
        }
        return appliedResult;
    }

    @Override
    public MemoryRebuildResultVO reviewActiveMemories(String agentId, boolean dryRun, String actorType, String actorId) {
        AgentEntity agent = assertAgentOwner(agentId);
        String maintenanceModelId = getMaintenanceLlmModelId(agent);
        MemoryRebuildResultVO result = new MemoryRebuildResultVO();
        result.setDryRun(dryRun);
        result.setMode("review_active");
        if (StringUtils.isBlank(maintenanceModelId)) {
            return result;
        }
        List<MemoryFactEntity> facts = factDao.selectList(new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agentId)
                .eq("status", MemoryHelper.STATUS_ACTIVE)
                .orderByDesc("importance", "last_seen_at")
                .last("LIMIT 200"));
        if (facts.isEmpty()) {
            return result;
        }
        String prompt = PowerMemPrompts.ACTIVE_FACTS_REVIEW_PROMPT
                .replace("{active_memories}", buildActiveFactsReviewContext(facts));
        String output = llmService.generateJson(prompt, maintenanceModelId);
        JSONArray actions = outputParser.parseActionsResult(output);
        if ((actions == null || actions.isEmpty()) && StringUtils.isNotBlank(output)) {
            String repaired = llmService.generateJson(
                    PowerMemPrompts.JSON_REPAIR_PROMPT.replace("{raw_output}", output),
                    maintenanceModelId);
            actions = outputParser.parseActionsResult(repaired);
        }
        if (actions == null || actions.isEmpty()) {
            return result;
        }
        result.setActionsProposed(actions.size());
        Map<String, MemoryFactEntity> factsById = new LinkedHashMap<>();
        for (MemoryFactEntity fact : facts) {
            factsById.put(fact.getMemoryId(), fact);
        }
        int changed = 0;
        for (Object item : actions) {
            JSONObject action = MemoryHelper.toJsonObject(item);
            if (action == null) continue;
            MemoryFactEntity relatedFact = factsById.get(action.getStr("related_memory_id"));
            if (relatedFact != null) {
                MemorySpeakerUtils.applyInternalIdentity(action, MemorySpeakerUtils.fromFact(relatedFact));
            }
            if (applyMaintenanceDecision(agent, agentId, null, action, maintenanceModelId, dryRun, result)) {
                changed++;
            }
        }
        result.setFactsChanged(changed);
        if (!dryRun) {
            writeRevision(agentId, null, "review_active_memories", null,
                    JsonUtils.toJsonString(result), actorType, actorId, "维护模型重审生效记忆");
        }
        return result;
    }

    @Override
    public boolean applyRememberInstruction(AgentEntity agent, String scopeId, String text, String category,
                                            String speakerId, String speakerName, boolean shared,
                                            String actorType, String actorId) {
        String maintenanceModelId = getMaintenanceLlmModelId(agent);
        if (StringUtils.isBlank(text)) {
            return false;
        }
        if (StringUtils.isBlank(maintenanceModelId)) {
            throw new IllegalStateException("未配置记忆维护模型");
        }
        SpeakerIdentity speaker = instructionSpeaker(speakerId, speakerName, shared);
        String existingMemories = buildInstructionMemoryContext(agent.getId(), speaker);
        String prompt = PowerMemPrompts.REMEMBER_COMMAND_PROMPT
                .replace("{text}", text)
                .replace("{category}", StringUtils.defaultString(category))
                .replace("{existing_memories}", existingMemories);
        String output = llmService.generateJson(prompt, maintenanceModelId);
        JSONObject decision = outputParser.parseRememberDecision(output, text);
        decision.set("_manual_instruction", true);
        MemorySpeakerUtils.applyInternalIdentity(decision, speaker);
        boolean changed = applyMaintenanceDecision(agent, scopeId, null, decision, maintenanceModelId, false, null);
        writeRevision(agent.getId(), null, "remember_instruction",
                JsonUtils.toJsonString(Map.of("text", text, "category", StringUtils.defaultString(category))),
                JsonUtils.toJsonString(decision), actorType, actorId, "维护模型处理显式记住");
        if (!changed) {
            throw new IllegalStateException("记忆维护模型未执行主模型的保存决定");
        }
        return changed;
    }

    @Override
    public boolean applyForgetInstruction(AgentEntity agent, String scopeId, String text, String patternType,
                                          String reason, String speakerId, String speakerName, boolean shared,
                                          String actorType, String actorId) {
        String maintenanceModelId = getMaintenanceLlmModelId(agent);
        if (StringUtils.isBlank(text)) {
            return false;
        }
        if (StringUtils.isBlank(maintenanceModelId)) {
            throw new IllegalStateException("未配置记忆维护模型");
        }
        SpeakerIdentity speaker = instructionSpeaker(speakerId, speakerName, shared);
        String existingMemories = buildInstructionMemoryContext(agent.getId(), speaker);
        String prompt = PowerMemPrompts.FORGET_COMMAND_PROMPT
                .replace("{text}", text)
                .replace("{reason}", StringUtils.defaultString(reason))
                .replace("{pattern_type}", StringUtils.defaultIfBlank(patternType, "semantic"))
                .replace("{existing_memories}", existingMemories);
        String output = llmService.generateJson(prompt, maintenanceModelId);
        JSONObject decision = outputParser.parseDecisionResult(output);
        if (decision == null) {
            decision = new JSONObject();
            decision.set("action", "NONE");
        }
        decision.set("canonical_text", StringUtils.defaultIfBlank(decision.getStr("canonical_text"), text));
        decision.set("tombstone_pattern", StringUtils.defaultIfBlank(decision.getStr("tombstone_pattern"), text));
        decision.set("pattern_type", StringUtils.defaultIfBlank(decision.getStr("pattern_type"),
                StringUtils.defaultIfBlank(patternType, "semantic")));
        decision.set("reason", StringUtils.defaultIfBlank(decision.getStr("reason"), reason));
        MemorySpeakerUtils.applyInternalIdentity(decision, speaker);

        String action = MemoryHelper.normalizeAction(decision.getStr("action"));
        boolean changed = applyMaintenanceDecision(agent, scopeId, null, decision, maintenanceModelId, false, null);
        if (("NONE".equals(action) || !changed) && !speaker.recognized()) {
            String tombstonePattern = StringUtils.defaultIfBlank(decision.getStr("tombstone_pattern"), text);
            String tombstoneReason = StringUtils.defaultIfBlank(decision.getStr("reason"), "显式忘记");
            MemoryForgetTombstoneEntity tombstone = transactionTemplate.execute(status -> {
                lockAgentForMemoryUpdate(agent.getId());
                return createForgetTombstone(agent, scopeId, tombstonePattern, tombstoneReason,
                        null, actorType, actorId);
            });
            changed = tombstone != null || changed;
        }
        writeRevision(agent.getId(), null, "forget_instruction",
                JsonUtils.toJsonString(Map.of("text", text,
                        "patternType", StringUtils.defaultIfBlank(patternType, "semantic"),
                        "reason", StringUtils.defaultString(reason))),
                JsonUtils.toJsonString(decision), actorType, actorId, "维护模型处理显式忘记");
        return changed;
    }

    // ===================== 管线私有方法 =====================

    private List<MemoryFactEntity> loadMaintenanceFacts(String agentId) {
        QueryWrapper<MemoryFactEntity> wrapper = new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agentId)
                .eq("status", MemoryHelper.STATUS_ACTIVE)
                .and(w -> w.isNull("expires_at").or().gt("expires_at", new Date()));
        return factDao.selectList(wrapper.orderByDesc("importance", "last_seen_at"));
    }

    private List<MemoryFactEntity> loadMaintenanceFactsForSpeaker(String agentId, String speakerId) {
        return loadMaintenanceFacts(agentId).stream()
                .filter(fact -> MemorySpeakerUtils.isVisibleToSpeaker(fact, speakerId))
                .toList();
    }

    private String mergeFactPayload(String existingPayload, JSONObject action, String modelId) {
        JSONObject payload = MemorySpeakerUtils.parseObject(existingPayload);
        payload.set("maintenance_llm", modelId);
        payload.set("source", MemoryHelper.MAINTENANCE_EXTRACTOR_VERSION);
        payload.set("reason", StringUtils.defaultString(action.getStr("reason")));
        MemorySpeakerUtils.applyPayloadIdentity(payload, action);
        return payload.toString();
    }

    private String speakerHashKey(JSONObject action) {
        return StringUtils.defaultIfBlank(MemorySpeakerUtils.actionSpeakerId(action), "shared");
    }

    private void attachUserEvidence(JSONObject action, String evidenceContext) {
        if (action == null || StringUtils.isBlank(evidenceContext)) {
            return;
        }
        String userEvidence = extractUserEvidenceText(evidenceContext);
        if (StringUtils.isNotBlank(userEvidence)) {
            action.set("_user_evidence_text", userEvidence);
        }
    }

    private String extractUserEvidenceText(String evidenceContext) {
        if (StringUtils.isBlank(evidenceContext)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String line : evidenceContext.split("\\R")) {
            String trimmed = StringUtils.trimToEmpty(line);
            if (trimmed.startsWith("用户")) {
                int colon = firstColonIndex(trimmed);
                if (colon >= 0 && colon + 1 < trimmed.length()) {
                    builder.append(trimmed.substring(colon + 1).trim()).append("\n");
                }
            }
        }
        return StringUtils.abbreviate(builder.toString(), MemoryHelper.SESSION_MAX_CHARS);
    }

    private int firstColonIndex(String line) {
        int ascii = line.indexOf(':');
        int full = line.indexOf('：');
        if (ascii < 0) {
            return full;
        }
        if (full < 0) {
            return ascii;
        }
        return Math.min(ascii, full);
    }

    private int totalMemoryChars(List<MemoryFactEntity> facts) {
        if (facts == null || facts.isEmpty()) {
            return 0;
        }
        return facts.stream()
                .filter(fact -> fact != null && MemoryHelper.STATUS_ACTIVE.equals(fact.getStatus()))
                .map(MemoryFactEntity::getCanonicalText)
                .filter(StringUtils::isNotEmpty)
                .mapToInt(MemoryHelper::characterCount)
                .sum();
    }

    private boolean applyMaintenanceDecision(AgentEntity agent, String scopeId, String sourceEventId, JSONObject json,
                                             String modelId, boolean dryRun, MemoryRebuildResultVO result) {
        String action = MemoryHelper.normalizeAction(json.getStr("action"));
        if ("NONE".equals(action)) {
            return false;
        }
        if (dryRun) {
            MemoryCandidateEntity preview = buildCandidate(agent, scopeId, sourceEventId, json, modelId, action);
            if (preview != null && result != null) {
                result.getCandidates().add(preview);
            }
            return preview != null;
        }
        return Boolean.TRUE.equals(transactionTemplate.execute(status ->
                executeMemoryAction(agent, scopeId, sourceEventId, json, modelId, result)));
    }

    private MemoryCandidateEntity buildCandidate(AgentEntity agent, String scopeId, String sourceEventId, JSONObject json,
                                                 String modelId, String action) {
        String canonical = MemoryHelper.cleanupMemoryText(json.getStr("canonical_text"));
        if (StringUtils.isBlank(canonical) && !"DELETE".equals(action) && !"ARCHIVE".equals(action)) {
            return null;
        }
        BigDecimal confidence = MemoryHelper.score(json.get("confidence"), MemoryHelper.DEFAULT_SCORE);
        BigDecimal importance = MemoryHelper.score(json.get("importance"), MemoryHelper.DEFAULT_SCORE);
        JSONObject payload = new JSONObject();
        payload.set("action", action);
        payload.set("related_memory_id", StringUtils.trimToEmpty(json.getStr("related_memory_id")));
        payload.set("reason", StringUtils.defaultString(json.getStr("reason")));
        payload.set("source", MemoryHelper.MAINTENANCE_EXTRACTOR_VERSION);
        payload.set("maintenance_llm", modelId);
        payload.set("memory_tier", resolveMemoryTier(json.getStr("memory_tier")));
        payload.set("importance_dimensions", json.get("importance_dimensions"));
        MemorySpeakerUtils.applyPayloadIdentity(payload, json);

        MemoryCandidateEntity candidate = new MemoryCandidateEntity();
        candidate.setCandidateId(MemoryHelper.uuid());
        candidate.setAgentId(agent.getId());
        candidate.setUserId(agent.getUserId());
        candidate.setWorkspaceId(agent.getWorkspaceId());
        candidate.setScopeType(MemoryHelper.SCOPE_DEVICE);
        candidate.setScopeId(scopeId);
        candidate.setMemoryType(MemoryHelper.normalizeMemoryLabel(json.getStr("memory_type"), "长期记忆"));
        candidate.setCategory(MemoryHelper.normalizeMemoryLabel(json.getStr("category"), "未分类"));
        candidate.setCanonicalText(canonical);
        candidate.setPayload(payload.toString());
        candidate.setConfidence(confidence);
        candidate.setImportance(importance);
        candidate.setSensitivity(resolveSensitivity(json, canonical));
        candidate.setStatus(MemoryHelper.STATUS_PENDING);
        candidate.setSourceEventId(sourceEventId);
        candidate.setExtractorVersion(MemoryHelper.MAINTENANCE_EXTRACTOR_VERSION);
        candidate.setCreatedAt(new Date());
        candidate.setUpdatedAt(new Date());
        return candidate;
    }

    private boolean executeMemoryAction(AgentEntity agent, String scopeId, String sourceEventId, JSONObject json,
                                        String modelId, MemoryRebuildResultVO result) {
        String action = MemoryHelper.normalizeAction(json.getStr("action"));
        if ("NONE".equals(action)) return false;
        lockAgentForMemoryUpdate(agent.getId());
        String actionSpeakerId = MemorySpeakerUtils.actionSpeakerId(json);

        String canonical = MemoryHelper.cleanupMemoryText(json.getStr("canonical_text"));
        if (StringUtils.isBlank(canonical) && !"DELETE".equals(action) && !"ARCHIVE".equals(action)) {
            return false;
        }

        if ("DELETE".equals(action) || "ARCHIVE".equals(action)) {
            String relatedId = StringUtils.trimToEmpty(json.getStr("related_memory_id"));
            String tombstonePattern = StringUtils.defaultIfBlank(json.getStr("tombstone_pattern"), canonical);
            boolean tombstoneCreated = false;
            // Tombstones are currently agent-wide. A speaker-specific lifecycle action
            // must not block another speaker from storing the same fact.
            if (StringUtils.isBlank(actionSpeakerId) && StringUtils.isNotBlank(tombstonePattern)) {
                tombstoneCreated = createForgetTombstone(agent, scopeId, tombstonePattern,
                        StringUtils.defaultIfBlank(json.getStr("reason"), "维护模型生命周期动作"),
                        sourceEventId, "system", modelId) != null;
                if (tombstoneCreated && result != null) {
                    result.setTombstonesCreated(result.getTombstonesCreated() + 1);
                }
            }
            if (StringUtils.isNotBlank(relatedId)) {
                MemoryFactEntity fact = factDao.selectOne(new QueryWrapper<MemoryFactEntity>()
                        .eq("agent_id", agent.getId()).eq("memory_id", relatedId)
                        .eq("status", MemoryHelper.STATUS_ACTIVE).last("LIMIT 1"));
                if (fact != null && MemorySpeakerUtils.isVisibleToSpeaker(fact, actionSpeakerId)) {
                    String before = JsonUtils.toJsonString(fact);
                    fact.setStatus("ARCHIVE".equals(action)
                            ? MemoryHelper.STATUS_ARCHIVED : MemoryHelper.STATUS_DELETED);
                    fact.setValidTo(new Date());
                    if ("ARCHIVE".equals(action)) {
                        fact.setArchivedAt(new Date());
                    }
                    fact.setUpdatedAt(new Date());
                    factDao.updateById(fact);
                    if (result != null) {
                        if ("ARCHIVE".equals(action)) {
                            result.setFactsArchived(result.getFactsArchived() + 1);
                        } else {
                            result.setFactsDeleted(result.getFactsDeleted() + 1);
                        }
                    }
                    writeRevision(agent.getId(), fact.getMemoryId(),
                            "ARCHIVE".equals(action) ? "archive_from_maintenance" : "delete_from_maintenance", before,
                            JsonUtils.toJsonString(fact), "system", modelId,
                            "维护模型生命周期动作: " + StringUtils.defaultString(json.getStr("reason")));
                    log.info("维护模型{}事实, memoryId={}, text={}", action, fact.getMemoryId(), fact.getCanonicalText());
                    return true;
                }
            }
            return tombstoneCreated;
        }

        String memoryType = MemoryHelper.normalizeMemoryLabel(json.getStr("memory_type"), "长期记忆");
        String category = MemoryHelper.normalizeMemoryLabel(json.getStr("category"), "未分类");
        BigDecimal confidence = MemoryHelper.score(json.get("confidence"), MemoryHelper.DEFAULT_SCORE);
        BigDecimal importance = MemoryHelper.score(json.get("importance"), MemoryHelper.DEFAULT_SCORE);
        String relatedId = StringUtils.trimToEmpty(json.getStr("related_memory_id"));
        String sensitivity = resolveSensitivity(json, canonical);
        String tier = resolveMemoryTier(json.getStr("memory_tier"));
        String dimensionsJson = extractDimensionsJson(json);

        if ("UPDATE".equals(action) && StringUtils.isNotBlank(relatedId)) {
            MemoryFactEntity fact = findRelatedFact(agent.getId(), relatedId);
            if (fact != null && MemorySpeakerUtils.isVisibleToSpeaker(fact, actionSpeakerId)) {
                // Check if the new canonical_text already exists as a different fact
                // (i.e. the LLM is trying to update this fact to match another one).
                MemoryFactEntity existingDuplicate = findExactFact(
                        agent.getId(), canonical, actionSpeakerId, relatedId);
                if (existingDuplicate != null) {
                    // Merge: boost the existing fact's importance and confidence,
                    // then delete the one being updated (it's a duplicate).
                    log.warn("UPDATE检测到重复canonical_text: newText={}, duplicateMemoryId={}, "
                            + "原memoryId={}将被合并删除",
                            canonical, existingDuplicate.getMemoryId(), fact.getMemoryId());
                    existingDuplicate.setImportance(MemoryHelper.max(existingDuplicate.getImportance(), importance));
                    existingDuplicate.setConfidence(MemoryHelper.max(existingDuplicate.getConfidence(), confidence));
                    existingDuplicate.setMemoryTier(tier);
                    existingDuplicate.setExpiresAt(null);
                    existingDuplicate.setLastSeenAt(new Date());
                    existingDuplicate.setUpdatedAt(new Date());
                    factDao.updateById(existingDuplicate);

                    // Delete the old fact being updated (now a duplicate)
                    String before = JsonUtils.toJsonString(fact);
                    fact.setStatus(MemoryHelper.STATUS_DELETED);
                    fact.setValidTo(new Date());
                    fact.setUpdatedAt(new Date());
                    factDao.updateById(fact);
                    if (result != null) {
                        result.setFactsDeleted(result.getFactsDeleted() + 1);
                    }
                    writeRevision(agent.getId(), fact.getMemoryId(), "update_merged_as_duplicate", before,
                            JsonUtils.toJsonString(fact), "system", modelId,
                            "合并到已有事实: " + existingDuplicate.getMemoryId());
                    return true;
                }

                List<MemoryFactEntity> capacityEvictions = resolveCapacityEvictions(
                        agent.getId(), json, loadActiveFactsForCapacity(agent.getId()), relatedId,
                        MemoryHelper.characterCount(canonical) - MemoryHelper.characterCount(fact.getCanonicalText()),
                        actionSpeakerId);
                if (capacityEvictions == null) {
                    log.warn("维护模型 UPDATE 超出智能体记忆容量且未提供有效驱逐方案，已拒绝: agentId={}, relatedMemoryId={}",
                            agent.getId(), relatedId);
                    return false;
                }
                archiveFactsForCapacity(capacityEvictions, modelId, result);

                String before = JsonUtils.toJsonString(fact);
                fact.setMemoryType(memoryType);
                fact.setCategory(category);
                fact.setCanonicalText(canonical);
                fact.setConfidence(MemoryHelper.max(fact.getConfidence(), confidence));
                fact.setImportance(MemoryHelper.max(fact.getImportance(), importance));
                fact.setSensitivity(sensitivity);
                if (StringUtils.isNotBlank(dimensionsJson)) fact.setImportanceDimensions(dimensionsJson);
                fact.setMemoryTier(tier);
                fact.setExpiresAt(null);
                fact.setPayload(mergeFactPayload(fact.getPayload(), json, modelId));
                fact.setStatus(MemoryHelper.STATUS_ACTIVE);
                fact.setLastSeenAt(new Date());
                fact.setUpdatedAt(new Date());
                factDao.updateById(fact);
                if (result != null) {
                    result.setFactsUpdated(result.getFactsUpdated() + 1);
                }
                writeRevision(agent.getId(), fact.getMemoryId(), "update_from_maintenance", before,
                        JsonUtils.toJsonString(fact), "system", modelId, "维护模型更新事实");
                log.info("维护模型更新事实, memoryId={}, text={}", fact.getMemoryId(), canonical);
                return true;
            }
            log.warn("维护模型 UPDATE 指向无效或越界的 related_memory_id，已拒绝执行: agentId={}, relatedMemoryId={}",
                    agent.getId(), relatedId);
            return false;
        }
        if ("UPDATE".equals(action)) {
            log.warn("维护模型 UPDATE 缺少 related_memory_id，已拒绝执行: agentId={}", agent.getId());
            return false;
        }

        // ADD
        String hash = MemoryHelper.sha256(
                agent.getId() + "|" + speakerHashKey(json) + "|" + canonical);
        MemoryFactEntity existingExact = findExactFact(agent.getId(), canonical, actionSpeakerId, null);
        if (existingExact != null) {
            existingExact.setImportance(MemoryHelper.max(existingExact.getImportance(), importance));
            refreshFactEvidence(existingExact, confidence, "system", modelId, "跨设备重复事实刷新");
            return true;
        }

        List<MemoryFactEntity> capacityEvictions = resolveCapacityEvictions(
                agent.getId(), json, loadActiveFactsForCapacity(agent.getId()), null,
                MemoryHelper.characterCount(canonical), actionSpeakerId);
        if (capacityEvictions == null) {
            log.warn("维护模型 ADD 超出智能体记忆容量且未提供有效驱逐方案，已拒绝: agentId={}", agent.getId());
            return false;
        }
        archiveFactsForCapacity(capacityEvictions, modelId, result);

        MemoryFactEntity fact = new MemoryFactEntity();
        fact.setMemoryId(MemoryHelper.uuid());
        fact.setAgentId(agent.getId());
        fact.setUserId(agent.getUserId());
        fact.setWorkspaceId(agent.getWorkspaceId());
        fact.setMacAddress(scopeId);
        fact.setScopeType(MemoryHelper.SCOPE_DEVICE);
        fact.setScopeId(scopeId);
        fact.setMemoryType(memoryType);
        fact.setCategory(category);
        fact.setCanonicalText(canonical);
        fact.setPayload(mergeFactPayload(null, json, modelId));
        fact.setImportance(importance);
        fact.setConfidence(confidence);
        fact.setSensitivity(sensitivity);
        fact.setConfirmationState("auto");
        fact.setStatus(MemoryHelper.STATUS_ACTIVE);
        fact.setMemoryTier(tier);
        fact.setExpiresAt(null);
        if (StringUtils.isNotBlank(dimensionsJson)) fact.setImportanceDimensions(dimensionsJson);
        fact.setValidFrom(new Date());
        fact.setFirstSeenAt(new Date());
        fact.setLastSeenAt(new Date());
        fact.setSourceEventId(sourceEventId);
        fact.setSourceHash(hash);
        fact.setExtractorVersion(MemoryHelper.MAINTENANCE_EXTRACTOR_VERSION);
        fact.setCreatedAt(new Date());
        fact.setUpdatedAt(new Date());
        factDao.insert(fact);
        if (result != null) {
            result.setFactsAdded(result.getFactsAdded() + 1);
        }
        writeRevision(agent.getId(), fact.getMemoryId(), "create_from_maintenance", null,
                JsonUtils.toJsonString(fact), "system", modelId, "维护模型创建事实");
        log.info("维护模型创建事实, memoryId={}, text={}", fact.getMemoryId(), canonical);
        return true;
    }

    /**
     * 根据维护模型返回的真实记忆 ID 生成容量驱逐计划。
     * 返回 null 表示方案缺失、越界或释放容量不足；空列表表示无需驱逐。
     */
    private List<MemoryFactEntity> resolveCapacityEvictions(String agentId, JSONObject json,
                                                            List<MemoryFactEntity> activeFacts,
                                                            String protectedMemoryId, int contentDelta,
                                                            String speakerId) {
        int projectedChars = totalMemoryChars(activeFacts) + contentDelta;
        int maxActiveMemoryChars = memorySettingsService.activeContextMaxChars();
        if (projectedChars <= maxActiveMemoryChars) {
            return List.of();
        }
        if (contentDelta > maxActiveMemoryChars) {
            return null;
        }

        JSONArray evictionIds = json.getJSONArray("evict_memory_ids");
        if (evictionIds == null || evictionIds.isEmpty()) {
            return null;
        }
        Map<String, MemoryFactEntity> allowedFacts = new LinkedHashMap<>();
        for (MemoryFactEntity fact : activeFacts) {
            if (fact != null && agentId.equals(fact.getAgentId())
                    && MemoryHelper.STATUS_ACTIVE.equals(fact.getStatus())
                    && MemorySpeakerUtils.isVisibleToSpeaker(fact, speakerId)
                    && StringUtils.isNotBlank(fact.getMemoryId())) {
                allowedFacts.put(fact.getMemoryId(), fact);
            }
        }

        Set<String> uniqueIds = new LinkedHashSet<>();
        List<MemoryFactEntity> evictions = new ArrayList<>();
        int freedChars = 0;
        for (Object value : evictionIds) {
            String memoryId = value == null ? null : StringUtils.trimToNull(String.valueOf(value));
            if (memoryId == null || memoryId.equals(protectedMemoryId) || !uniqueIds.add(memoryId)) {
                return null;
            }
            MemoryFactEntity fact = allowedFacts.get(memoryId);
            if (fact == null) {
                return null;
            }
            evictions.add(fact);
            freedChars += MemoryHelper.characterCount(fact.getCanonicalText());
        }
        return projectedChars - freedChars <= maxActiveMemoryChars ? evictions : null;
    }

    private List<MemoryFactEntity> loadActiveFactsForCapacity(String agentId) {
        // 锁定智能体行，使同一智能体的并发记忆写入串行化，防止分别校验后合计超限。
        lockAgentForMemoryUpdate(agentId);
        return loadMaintenanceFacts(agentId);
    }

    private void lockAgentForMemoryUpdate(String agentId) {
        agentDao.selectOne(new QueryWrapper<AgentEntity>()
                .eq("id", agentId)
                .last("FOR UPDATE"));
    }

    private void archiveFactsForCapacity(List<MemoryFactEntity> facts, String modelId, MemoryRebuildResultVO result) {
        for (MemoryFactEntity fact : facts) {
            String before = JsonUtils.toJsonString(fact);
            Date now = new Date();
            fact.setStatus(MemoryHelper.STATUS_ARCHIVED);
            fact.setArchivedAt(now);
            fact.setValidTo(now);
            fact.setUpdatedAt(now);
            factDao.updateById(fact);
            if (result != null) {
                result.setFactsArchived(result.getFactsArchived() + 1);
            }
            writeRevision(fact.getAgentId(), fact.getMemoryId(), "archive_for_capacity", before,
                    JsonUtils.toJsonString(fact), "system", modelId,
                    "智能体 active 记忆容量超过 " + memorySettingsService.activeContextMaxChars() + " 字符");
        }
    }

    private MemoryForgetTombstoneEntity createForgetTombstone(AgentEntity agent, String scopeId, String patternText,
                                                              String reason, String sourceEventId,
                                                              String actorType, String actorId) {
        String pattern = MemoryHelper.cleanupMemoryText(patternText);
        if (StringUtils.isBlank(pattern)) {
            return null;
        }
        QueryWrapper<MemoryForgetTombstoneEntity> existingWrapper = new QueryWrapper<>();
        existingWrapper.eq("agent_id", agent.getId())
                .eq("pattern_text", pattern)
                .eq("status", MemoryHelper.STATUS_ACTIVE)
                .last("LIMIT 1");
        MemoryForgetTombstoneEntity existing = tombstoneDao.selectOne(existingWrapper);
        if (existing != null) {
            return existing;
        }
        MemoryForgetTombstoneEntity tombstone = new MemoryForgetTombstoneEntity();
        tombstone.setTombstoneId(MemoryHelper.uuid());
        tombstone.setAgentId(agent.getId());
        tombstone.setUserId(agent.getUserId());
        tombstone.setWorkspaceId(agent.getWorkspaceId());
        tombstone.setScopeType(MemoryHelper.SCOPE_DEVICE);
        tombstone.setScopeId(scopeId);
        tombstone.setPatternText(pattern);
        tombstone.setReason(StringUtils.defaultIfBlank(reason, "显式忘记"));
        tombstone.setStatus(MemoryHelper.STATUS_ACTIVE);
        tombstone.setSourceEventId(sourceEventId);
        tombstone.setCreatedAt(new Date());
        tombstone.setUpdatedAt(new Date());
        tombstoneDao.insert(tombstone);
        writeRevision(agent.getId(), null, "create_forget_tombstone", null,
                JsonUtils.toJsonString(tombstone), actorType, actorId, "创建忘记规则");
        return tombstone;
    }

    private String resolveSensitivity(JSONObject json, String canonicalText) {
        return MemoryHelper.normalizeSensitivity(json == null ? null : json.getStr("sensitivity"));
    }

    private String extractDimensionsJson(JSONObject json) {
        Object dimsRaw = json.get("importance_dimensions");
        if (dimsRaw == null) return null;
        try {
            return dimsRaw instanceof String s ? s : JSONUtil.toJsonStr(dimsRaw);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveMemoryTier(String rawTier) {
        String tier = MemoryHelper.normalizeMemoryTier(rawTier);
        return StringUtils.defaultIfBlank(tier, "long_term");
    }

    private String buildActiveFactsReviewContext(List<MemoryFactEntity> facts) {
        StringBuilder builder = new StringBuilder();
        for (MemoryFactEntity fact : facts) {
            builder.append("- id=").append(fact.getMemoryId())
                    .append(" speaker_id=").append(MemorySpeakerUtils.payloadSpeakerId(fact))
                    .append(" tier=").append(StringUtils.defaultString(fact.getMemoryTier(), ""))
                    .append(" importance=").append(fact.getImportance())
                    .append(" confidence=").append(fact.getConfidence())
                    .append(" type=").append(fact.getMemoryType())
                    .append(" category=").append(fact.getCategory())
                    .append(" text=").append(fact.getCanonicalText())
                    .append("\n");
        }
        return builder.toString();
    }

    // ===================== 重建方法 =====================

    private List<JSONObject> buildRebuildActions(AgentEntity agent, String scopeId,
                                                 List<List<AgentChatHistoryDTO>> histories, String mode) {
        String maintenanceModelId = getMaintenanceLlmModelId(agent);
        if (StringUtils.isBlank(maintenanceModelId)) {
            return List.of();
        }
        String conversation = conversationBuilder.combineHistories(histories);
        Registry speakers = conversationBuilder.registryFromHistories(histories);
        if (StringUtils.isBlank(conversation)) {
            return List.of();
        }
        String prompt = PowerMemPrompts.REBUILD_ACTIONS_PROMPT
                .replace("{mode}", StringUtils.defaultIfBlank(mode, "merge"))
                .replace("{existing_memories}", buildRebuildExistingMemoryContext(agent.getId(), mode))
                .replace("{conversation}", conversation);
        String output = llmService.generateJson(prompt, maintenanceModelId);
        JSONArray arr = outputParser.parseActionsResult(output);
        if ((arr == null || arr.isEmpty()) && StringUtils.isNotBlank(output)) {
            String repaired = llmService.generateJson(
                    PowerMemPrompts.JSON_REPAIR_PROMPT.replace("{raw_output}", output),
                    maintenanceModelId);
            arr = outputParser.parseActionsResult(repaired);
        }
        if (arr == null || arr.isEmpty()) {
            return List.of();
        }
        JSONArray reviewed = reviewRebuildActions(arr, mode, conversation, maintenanceModelId);
        if (reviewed != null && !reviewed.isEmpty()) {
            arr = reviewed;
        }
        List<JSONObject> actions = new ArrayList<>();
        boolean clearThenRebuild = "clear_then_rebuild".equalsIgnoreCase(mode);
        for (Object item : arr) {
            JSONObject action = MemoryHelper.toJsonObject(item);
            if (action == null) {
                continue;
            }
            String rawAction = StringUtils.trimToEmpty(action.getStr("action"));
            String actionType;
            if (clearThenRebuild) {
                actionType = rawAction.toUpperCase(java.util.Locale.ROOT);
                if (!"ADD".equals(actionType) && !"NONE".equals(actionType)) {
                    throw new IllegalStateException(
                            "清空重建仅允许 ADD 或 NONE 动作，维护模型返回了 "
                                    + StringUtils.defaultIfBlank(rawAction, "<缺失>"));
                }
            } else {
                actionType = MemoryHelper.normalizeAction(rawAction);
            }
            if ("NONE".equals(actionType)) {
                continue;
            }
            attachUserEvidence(action, conversation);
            SpeakerIdentity speaker = speakers.resolve(action);
            if (speaker == null) {
                log.warn("历史重建动作缺少可验证的speaker_id或人物归属不明确，已跳过: agentId={}",
                        agent.getId());
                continue;
            }
            MemorySpeakerUtils.applyInternalIdentity(action, speaker);
            actions.add(action);
        }
        return actions;
    }

    private String buildRebuildExistingMemoryContext(String agentId, String mode) {
        if ("clear_then_rebuild".equalsIgnoreCase(mode)) {
            return "本次是清空后重建，不使用旧 memory_id；请只输出最终应生效的 ADD 事实。";
        }
        return buildMaintenanceMemoryContext(agentId);
    }

    private JSONArray reviewRebuildActions(JSONArray actions, String mode, String evidenceContext,
                                           String maintenanceModelId) {
        String prompt = PowerMemPrompts.ACTIONS_REVIEW_PROMPT
                .replace("{mode}", StringUtils.defaultIfBlank(mode, "merge"))
                .replace("{evidence_context}", StringUtils.defaultIfBlank(evidenceContext, "无额外证据"))
                .replace("{actions_json}", JSONUtil.toJsonStr(Map.of("actions", actions)));
        String output = llmService.generateJson(prompt, maintenanceModelId);
        JSONArray reviewed = outputParser.parseActionsResult(output);
        if ((reviewed == null || reviewed.isEmpty()) && StringUtils.isNotBlank(output)) {
            String repaired = llmService.generateJson(
                    PowerMemPrompts.JSON_REPAIR_PROMPT.replace("{raw_output}", output),
                    maintenanceModelId);
            reviewed = outputParser.parseActionsResult(repaired);
        }
        return reviewed;
    }

    private List<AgentChatSessionDTO> recentSessions(String agentId, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("agentId", agentId);
        params.put(Constant.PAGE, 1);
        params.put(Constant.LIMIT, limit);
        PageData<AgentChatSessionDTO> page = agentChatHistoryService.getSessionListByAgentId(params);
        return page == null || page.getList() == null ? List.of() : page.getList();
    }

    private String firstScopeId(List<AgentChatHistoryDTO> history, String defaultScopeId) {
        if (history != null) {
            for (AgentChatHistoryDTO item : history) {
                if (item != null && StringUtils.isNotBlank(item.getMacAddress())) {
                    return item.getMacAddress();
                }
            }
        }
        return defaultScopeId;
    }

    // ===================== 共享辅助方法 =====================

    private String buildMaintenanceMemoryContext(String agentId) {
        QueryWrapper<MemoryFactEntity> wrapper = new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agentId)
                .eq("status", MemoryHelper.STATUS_ACTIVE);
        return formatMaintenanceMemoryContext(factDao.selectList(wrapper
                .orderByDesc("importance", "last_seen_at")
                .last("LIMIT 30")));
    }

    private String buildInstructionMemoryContext(String agentId, SpeakerIdentity speaker) {
        List<MemoryFactEntity> visibleFacts = speaker.recognized()
                ? loadMaintenanceFactsForSpeaker(agentId, speaker.id())
                : loadMaintenanceFacts(agentId).stream()
                        .filter(fact -> StringUtils.isBlank(MemorySpeakerUtils.payloadSpeakerId(fact)))
                        .toList();
        List<MemoryFactEntity> facts = visibleFacts.stream().limit(30).toList();
        return formatMaintenanceMemoryContext(facts);
    }

    private String formatMaintenanceMemoryContext(List<MemoryFactEntity> facts) {
        if (facts.isEmpty()) {
            return "无已有长期记忆";
        }
        StringBuilder builder = new StringBuilder();
        for (MemoryFactEntity fact : facts) {
            builder.append("- id=").append(fact.getMemoryId())
                    .append(" type=").append(fact.getMemoryType())
                    .append(" category=").append(fact.getCategory())
                    .append(" text=").append(fact.getCanonicalText())
                    .append("\n");
        }
        return builder.toString();
    }

    private SpeakerIdentity instructionSpeaker(String speakerId, String speakerName, boolean shared) {
        if (shared) {
            return SpeakerIdentity.shared();
        }
        String normalizedId = StringUtils.trimToEmpty(speakerId);
        if (StringUtils.isBlank(normalizedId)) {
            throw new IllegalArgumentException("私有记忆缺少可信说话人身份");
        }
        return new SpeakerIdentity(normalizedId, StringUtils.trimToEmpty(speakerName));
    }

    private String getMaintenanceLlmModelId(AgentEntity agent) {
        if (agent == null || StringUtils.isBlank(agent.getMemModelId())) {
            return null;
        }
        try {
            ModelConfigEntity memoryConfig = modelConfigService.getModelByIdFromCache(agent.getMemModelId());
            if (memoryConfig == null) {
                return null;
            }
            JSONObject config = modelRuntimeAssembler.buildTypedConfig(memoryConfig);
            return StringUtils.defaultIfBlank(config.getStr(MemoryHelper.MAINTENANCE_LLM_FIELD),
                    config.getStr("memory_maintenance_llm"));
        } catch (Exception e) {
            log.warn("读取记忆维护模型配置失败，agentId: {}, memModelId: {}, error: {}",
                    agent.getId(), agent.getMemModelId(), e.getMessage());
            return null;
        }
    }

    private void writeRevision(String agentId, String memoryId, String action, String before, String after,
                               String actorType, String actorId, String reason) {
        MemoryRevisionEntity revision = new MemoryRevisionEntity();
        revision.setRevisionId(MemoryHelper.uuid());
        revision.setMemoryId(memoryId);
        revision.setAgentId(agentId);
        AgentEntity agent = agentDao.selectById(agentId);
        if (agent != null) {
            revision.setWorkspaceId(agent.getWorkspaceId());
        }
        revision.setRevisionAction(action);
        revision.setBeforePayload(before);
        revision.setAfterPayload(after);
        revision.setActorType(StringUtils.defaultIfBlank(actorType, "system"));
        revision.setActorId(actorId);
        revision.setReason(reason);
        revision.setCreatedAt(new Date());
        revisionDao.insert(revision);
    }

    private MemoryFactEntity findRelatedFact(String agentId, String relatedMemoryId) {
        if (StringUtils.isBlank(relatedMemoryId)) {
            return null;
        }
        QueryWrapper<MemoryFactEntity> wrapper = new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agentId)
                .eq("memory_id", relatedMemoryId)
                .eq("status", MemoryHelper.STATUS_ACTIVE);
        return factDao.selectOne(wrapper.last("LIMIT 1"));
    }

    private MemoryFactEntity findExactFact(String agentId, String canonical, String speakerId,
                                           String excludedMemoryId) {
        QueryWrapper<MemoryFactEntity> wrapper = new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agentId)
                .eq("canonical_text", canonical)
                .ne("status", MemoryHelper.STATUS_DELETED)
                .and(w -> {
                    if (StringUtils.isBlank(speakerId)) {
                        w.isNull("payload")
                                .or().apply("JSON_EXTRACT(payload, '$.speaker_id') IS NULL")
                                .or().apply("JSON_UNQUOTE(JSON_EXTRACT(payload, '$.speaker_id')) = ''");
                    } else {
                        w.apply("JSON_UNQUOTE(JSON_EXTRACT(payload, '$.speaker_id')) = {0}", speakerId);
                    }
                });
        if (StringUtils.isNotBlank(excludedMemoryId)) {
            wrapper.ne("memory_id", excludedMemoryId);
        }
        return factDao.selectOne(wrapper.last("LIMIT 1"));
    }

    private void refreshFactEvidence(MemoryFactEntity fact, BigDecimal confidence, String actorType, String actorId, String reason) {
        fact.setLastSeenAt(new Date());
        fact.setConfidence(MemoryHelper.max(fact.getConfidence(), confidence == null ? MemoryHelper.DEFAULT_SCORE : confidence));
        fact.setStatus(MemoryHelper.STATUS_ACTIVE);
        fact.setUpdatedAt(new Date());
        factDao.updateById(fact);
        writeRevision(fact.getAgentId(), fact.getMemoryId(), "refresh", null,
                JsonUtils.toJsonString(fact), actorType, actorId, reason);
    }

    private String mergeFactPayload(String factPayload, String candidatePayload) {
        JSONObject merged = MemoryHelper.parsePayload(factPayload);
        JSONObject candidate = MemoryHelper.parsePayload(candidatePayload);
        merged.set("last_candidate_action", candidate.getStr("action"));
        merged.set("last_candidate_reason", candidate.getStr("reason"));
        merged.set("maintenance_source", candidate.getStr("source"));
        return merged.toString();
    }

    private AgentEntity assertAgentOwner(String agentId) {
        AgentEntity agent = agentDao.selectById(agentId);
        if (agent == null) {
            throw new IllegalArgumentException("智能体不存在");
        }
        UserDetail user = SecurityUser.getUser();
        if (user != null && user.getSuperAdmin() != null && user.getSuperAdmin() != 1
                && !workspaceService().canRead(agent.getWorkspaceId(), user.getId())) {
            throw new IllegalArgumentException("无权访问该智能体记忆");
        }
        return agent;
    }

    private WorkspaceService workspaceService() {
        return SpringContextUtils.getBean(WorkspaceService.class);
    }

    private AgentScope resolveScope(String agentId, String macAddress) {
        AgentEntity agent = null;
        String scopeId = StringUtils.defaultIfBlank(macAddress, agentId);
        if (StringUtils.isNotBlank(macAddress)) {
            DeviceEntity device = deviceDao.selectOne(
                    new QueryWrapper<DeviceEntity>().eq("mac_address", macAddress).last("LIMIT 1"));
            if (device != null && StringUtils.isNotBlank(device.getAgentId())) {
                agent = agentDao.selectById(device.getAgentId());
                scopeId = macAddress;
            }
        }
        if (agent == null && StringUtils.isNotBlank(agentId)) {
            agent = agentDao.selectById(agentId);
        }
        return new AgentScope(agent, scopeId);
    }

    private record AgentScope(AgentEntity agent, String scopeId) {
    }
}
