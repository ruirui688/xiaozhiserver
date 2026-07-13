package xiaozhi.modules.memory.service.impl;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
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
import xiaozhi.modules.memory.dao.MemorySessionStateDao;
import xiaozhi.modules.memory.dao.MemorySourceEventDao;
import xiaozhi.modules.memory.dto.MemoryRebuildRequestDTO;
import xiaozhi.modules.memory.entity.MemoryCandidateEntity;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.entity.MemoryForgetTombstoneEntity;
import xiaozhi.modules.memory.entity.MemoryRevisionEntity;
import xiaozhi.modules.memory.entity.MemorySessionStateEntity;
import xiaozhi.modules.memory.entity.MemorySourceEventEntity;
import xiaozhi.modules.memory.prompt.PowerMemPrompts;
import xiaozhi.modules.memory.service.MemoryMaintenanceService;
import xiaozhi.modules.memory.service.impl.maintenance.MemoryMaintenanceOutputParser;
import xiaozhi.modules.memory.service.impl.maintenance.MemorySpeakerConversationBuilder;
import xiaozhi.modules.memory.service.impl.maintenance.MemorySpeakerConversationBuilder.Conversation;
import xiaozhi.modules.memory.utils.MemoryHelper;
import xiaozhi.modules.memory.utils.MemorySpeakerUtils;
import xiaozhi.modules.memory.utils.MemorySpeakerUtils.Registry;
import xiaozhi.modules.memory.utils.MemorySpeakerUtils.SpeakerIdentity;
import xiaozhi.modules.memory.utils.MemoryTierHelper;
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

    private final MemorySourceEventDao sourceEventDao;
    private final MemoryCandidateDao candidateDao;
    private final MemoryFactDao factDao;
    private final MemoryRevisionDao revisionDao;
    private final MemoryForgetTombstoneDao tombstoneDao;
    private final MemorySessionStateDao sessionStateDao;
    private final DeviceDao deviceDao;
    private final AgentDao agentDao;
    private final LLMService llmService;
    private final ModelConfigService modelConfigService;
    private final ModelRuntimeAssembler modelRuntimeAssembler;
    private final AgentChatHistoryService agentChatHistoryService;
    private final MemoryMaintenanceOutputParser outputParser;
    private final MemorySpeakerConversationBuilder conversationBuilder;
    private final TransactionTemplate transactionTemplate;

    // ===================== 公开方法 =====================

    @Override
    public int processSessionMemories(String sessionId, String agentId) {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(agentId)) {
            return 0;
        }
        MemorySessionStateEntity state = sessionStateDao.selectOne(
                new QueryWrapper<MemorySessionStateEntity>()
                        .eq("agent_id", agentId)
                        .eq("session_id", sessionId)
                        .last("LIMIT 1"));
        if (state == null || state.getLastEventId() == null) {
            return 0;
        }
        Date staleBefore = Date.from(Instant.now().minus(
                MemoryHelper.SESSION_MEMORY_PROCESSING_STALE_MINUTES, ChronoUnit.MINUTES));
        if (sessionStateDao.claimNow(state.getId(), state.getLastEventId(), staleBefore) != 1) {
            return 0;
        }
        return processClaimedSession(state);
    }

    @Scheduled(fixedDelay = 10_000L, initialDelay = 10_000L)
    public void processIdleSessionMemories() {
        Date idleBefore = Date.from(Instant.now().minus(
                MemoryHelper.SESSION_MEMORY_IDLE_SECONDS, ChronoUnit.SECONDS));
        Date staleBefore = Date.from(Instant.now().minus(
                MemoryHelper.SESSION_MEMORY_PROCESSING_STALE_MINUTES, ChronoUnit.MINUTES));
        List<MemorySessionStateEntity> states = sessionStateDao.findIdleSessions(
                idleBefore, staleBefore, MemoryHelper.SESSION_MEMORY_IDLE_BATCH_SIZE);
        for (MemorySessionStateEntity state : states) {
            if (state == null || state.getId() == null || state.getLastEventId() == null) {
                continue;
            }
            if (sessionStateDao.claimIdle(state.getId(), state.getLastEventId(), idleBefore, staleBefore) != 1) {
                continue;
            }
            try {
                processClaimedSession(state);
            } catch (Exception e) {
                log.warn("空闲会话记忆整理失败, agentId={}, sessionId={}, error={}",
                        state.getAgentId(), state.getSessionId(), e.getMessage());
            }
        }
    }

    private int processClaimedSession(MemorySessionStateEntity state) {
        try {
            int count = processSessionMemoriesThrough(
                    state.getSessionId(), state.getAgentId(), state.getLastEventId());
            sessionStateDao.complete(state.getId(), state.getLastEventId());
            return count;
        } catch (Exception e) {
            sessionStateDao.release(state.getId(), state.getLastEventId());
            throw e;
        }
    }

    private int processSessionMemoriesThrough(String sessionId, String agentId, Long throughEventId) {
        AgentEntity agent = agentDao.selectById(agentId);
        if (agent == null) {
            log.warn("processSessionMemories: 智能体不存在, agentId={}", agentId);
            return 0;
        }
        String maintenanceModelId = getMaintenanceLlmModelId(agent);
        if (StringUtils.isBlank(maintenanceModelId)) {
            log.info("processSessionMemories: 未配置维护模型, agentId={}", agentId);
            return 0;
        }
        Conversation sessionConversation = conversationBuilder.fromEvents(sourceEventDao.selectList(
                new QueryWrapper<MemorySourceEventEntity>()
                        .eq("agent_id", agentId)
                        .eq("session_id", sessionId)
                        .le(throughEventId != null, "id", throughEventId)
                        .orderByAsc("created_at")));
        String conversation = sessionConversation.text();
        if (StringUtils.isBlank(conversation)) {
            return 0;
        }

        // Stage 1: 提取事实
        String extractionPrompt = PowerMemPrompts.EXTRACTION_PROMPT.replace("{conversation}",
                StringUtils.abbreviate(conversation, MemoryHelper.SESSION_MAX_CHARS));
        String extractionOutput = llmService.generateJson(extractionPrompt, maintenanceModelId);
        if (StringUtils.isBlank(extractionOutput)) {
            log.warn("processSessionMemories: Stage 1 提取失败, agentId={}", agentId);
            return 0;
        }
        JSONArray facts = outputParser.parseExtractionResult(extractionOutput);
        if (facts == null || facts.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (Object item : facts) {
            JSONObject factJson = MemoryHelper.toJsonObject(item);
            if (factJson == null) continue;

            SpeakerIdentity speaker = sessionConversation.speakers().resolve(factJson);
            if (speaker == null) {
                log.warn("会话记忆缺少可验证的speaker_id或人物归属不明确，已跳过: agentId={}, sessionId={}",
                        agentId, sessionId);
                continue;
            }
            MemorySpeakerUtils.applyInternalIdentity(factJson, speaker);

            // Stage 2: 将当前作用域内有界的文本事实交给维护 LLM 直接决策。
            List<MemoryFactEntity> existingFacts = loadMaintenanceFactsForSpeaker(
                    agentId, speaker.id());
            String decisionPrompt = buildDecisionPrompt(
                    factJson, existingFacts, conversation, totalMemoryChars(loadMaintenanceFacts(agentId)));
            String decisionOutput = llmService.generateJson(decisionPrompt, maintenanceModelId);
            if (StringUtils.isBlank(decisionOutput)) continue;

            JSONObject decision = outputParser.parseDecisionResult(decisionOutput);
            if (decision == null) continue;
            outputParser.mergeExtractionIntoDecision(factJson, decision);
            MemorySpeakerUtils.applyInternalIdentity(decision, speaker);
            if (applyMaintenanceDecision(agent, agentId, null, decision, maintenanceModelId, false, null)) count++;
        }
        log.info("processSessionMemories: agentId={}, sessionId={}, factsChanged={}", agentId, sessionId, count);
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemoryRebuildResultVO clearMemories(String agentId, String actorType, String actorId) {
        AgentEntity agent = assertAgentOwner(agentId);
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
    @Transactional(rollbackFor = Exception.class)
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

        if ("clear_then_rebuild".equalsIgnoreCase(mode)) {
            MemoryRebuildResultVO cleared = clearMemories(agentId, actorType, actorId);
            result.setFactsCleared(cleared.getFactsCleared());
            result.setCandidatesCleared(cleared.getCandidatesCleared());
            result.setTombstonesCleared(cleared.getTombstonesCleared());
        }

        String maintenanceModelId = getMaintenanceLlmModelId(agent);
        int applied = 0;
        for (JSONObject action : applicableActions) {
            if (applyMaintenanceDecision(agent, scopeId, null, action, maintenanceModelId, false, null)) {
                applied++;
            }
        }
        result.setFactsChanged(applied);
        if (!dryRun) {
            writeRevision(agent.getId(), null, "rebuild_memory", null, JsonUtils.toJsonString(result),
                    actorType, actorId, "从聊天记录重建记忆");
        }
        return result;
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

    /**
     * PowerMem 两阶段管线：Stage 1 提取事实 → Stage 2 语义搜索 + 决策执行。
     */
    @Override
    public void createCandidatesWithMaintenanceModel(AgentEntity agent, String scopeId, String sourceEventId, String content) {
        String maintenanceModelId = getMaintenanceLlmModelId(agent);
        if (StringUtils.isBlank(maintenanceModelId)) {
            return;
        }
        String promptInput = "用户: " + StringUtils.abbreviate(content, MemoryHelper.MAX_MAINTENANCE_INPUT_CHARS);

        // Stage 1: 提取事实
        String extractionPrompt = PowerMemPrompts.EXTRACTION_PROMPT.replace("{conversation}", promptInput);
        String extractionOutput = llmService.generateJson(extractionPrompt, maintenanceModelId);
        if (StringUtils.isBlank(extractionOutput)) {
            log.warn("Stage 1 事实提取失败，agentId: {}", agent.getId());
            return;
        }
        JSONArray facts = outputParser.parseExtractionResult(extractionOutput);
        if (facts == null || facts.isEmpty()) {
            return;
        }

        int saved = 0;
        for (Object item : facts) {
            JSONObject factJson = MemoryHelper.toJsonObject(item);
            if (factJson == null) continue;

            // Stage 2: 维护 LLM 直接对比当前作用域内的文本事实并决策。
            List<MemoryFactEntity> existingFacts = loadMaintenanceFacts(agent.getId());
            String decisionPrompt = buildDecisionPrompt(
                    factJson, existingFacts, promptInput, totalMemoryChars(loadMaintenanceFacts(agent.getId())));
            String decisionOutput = llmService.generateJson(decisionPrompt, maintenanceModelId);
            if (StringUtils.isBlank(decisionOutput)) {
                continue;
            }
            JSONObject decision = outputParser.parseDecisionResult(decisionOutput);
            if (decision == null) continue;

            outputParser.mergeExtractionIntoDecision(factJson, decision);

            if (applyMaintenanceDecision(agent, scopeId, sourceEventId, decision, maintenanceModelId, false, null)) {
                saved++;
            }
        }
    }

    @Override
    public boolean applyRememberInstruction(AgentEntity agent, String scopeId, String text, String category,
                                            String actorType, String actorId) {
        String maintenanceModelId = getMaintenanceLlmModelId(agent);
        if (StringUtils.isBlank(text)) {
            return false;
        }
        if (StringUtils.isBlank(maintenanceModelId)) {
            throw new IllegalStateException("未配置记忆维护模型");
        }
        String existingMemories = buildMaintenanceMemoryContext(agent.getId(), scopeId);
        String prompt = PowerMemPrompts.REMEMBER_COMMAND_PROMPT
                .replace("{text}", text)
                .replace("{category}", StringUtils.defaultString(category))
                .replace("{existing_memories}", existingMemories);
        String output = llmService.generateJson(prompt, maintenanceModelId);
        JSONObject decision = outputParser.parseDecisionResult(output);
        if (decision == null) {
            throw new IllegalStateException("记忆维护模型未返回有效决策");
        }
        JSONArray instructionActions = new JSONArray();
        instructionActions.add(decision);
        String evidenceContext = "显式记住：\n" + text + "\n\n已有记忆：\n" + existingMemories;
        JSONArray reviewed = reviewRebuildActions(instructionActions, "merge", evidenceContext, maintenanceModelId);
        if (reviewed != null && !reviewed.isEmpty()) {
            JSONObject reviewedDecision = MemoryHelper.toJsonObject(reviewed.get(0));
            if (reviewedDecision != null) {
                decision = reviewedDecision;
            }
        }
        boolean changed = applyMaintenanceDecision(agent, scopeId, null, decision, maintenanceModelId, false, null);
        writeRevision(agent.getId(), null, "remember_instruction",
                JsonUtils.toJsonString(Map.of("text", text, "category", StringUtils.defaultString(category))),
                JsonUtils.toJsonString(decision), actorType, actorId, "维护模型处理显式记住");
        return changed;
    }

    @Override
    public boolean applyForgetInstruction(AgentEntity agent, String scopeId, String text, String patternType,
                                          String reason, String actorType, String actorId) {
        String maintenanceModelId = getMaintenanceLlmModelId(agent);
        if (StringUtils.isBlank(text)) {
            return false;
        }
        if (StringUtils.isBlank(maintenanceModelId)) {
            throw new IllegalStateException("未配置记忆维护模型");
        }
        String existingMemories = buildMaintenanceMemoryContext(agent.getId(), scopeId);
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

        String action = MemoryHelper.normalizeAction(decision.getStr("action"));
        boolean changed = applyMaintenanceDecision(agent, scopeId, null, decision, maintenanceModelId, false, null);
        if ("NONE".equals(action) || !changed) {
            MemoryForgetTombstoneEntity tombstone = createForgetTombstone(agent, scopeId,
                    StringUtils.defaultIfBlank(decision.getStr("tombstone_pattern"), text),
                    StringUtils.defaultIfBlank(decision.getStr("reason"), "显式忘记"),
                    null, actorType, actorId);
            changed = tombstone != null || changed;
        }
        if (!changed) {
            changed = archiveFactsMatchingPattern(agent, scopeId, text, maintenanceModelId, actorType, actorId) > 0;
        }
        writeRevision(agent.getId(), null, "forget_instruction",
                JsonUtils.toJsonString(Map.of("text", text,
                        "patternType", StringUtils.defaultIfBlank(patternType, "semantic"),
                        "reason", StringUtils.defaultString(reason))),
                JsonUtils.toJsonString(decision), actorType, actorId, "维护模型处理显式忘记");
        return changed;
    }

    /**
     * 每日定时（凌晨 3:00）对激活记忆执行艾宾浩斯衰减。
     * 计算 retention = initialRetention * exp(-decayRate * days_since_last_seen)，
     * 低于 {@link MemoryTierHelper#FORGET_THRESHOLD} 的记忆自动归档。
     *
     * <p>参见 {@link MemoryTierHelper} 中各层级的衰减率与半衰期。</p>
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void applyDecayToActiveFacts() {
        List<MemoryFactEntity> facts = factDao.selectList(
                new QueryWrapper<MemoryFactEntity>()
                        .eq("status", MemoryHelper.STATUS_ACTIVE)
                        .and(w -> w.isNotNull("memory_tier").or().isNotNull("expires_at"))
        );
        if (facts.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        int archived = 0;
        for (MemoryFactEntity fact : facts) {
            try {
                boolean expired = fact.getExpiresAt() != null && fact.getExpiresAt().getTime() <= now;
                if (expired) {
                    String before = JsonUtils.toJsonString(fact);
                    fact.setStatus(MemoryHelper.STATUS_ARCHIVED);
                    fact.setValidTo(new Date(now));
                    fact.setArchivedAt(new Date(now));
                    fact.setUpdatedAt(new Date(now));
                    factDao.updateById(fact);
                    writeRevision(fact.getAgentId(), fact.getMemoryId(), "decay_archive", before,
                            JsonUtils.toJsonString(fact), "system", "scheduler",
                            "记忆到达生命周期过期时间，自动归档");
                    archived++;
                    continue;
                }
                String tierName = fact.getMemoryTier();
                if (StringUtils.isBlank(tierName)) continue;
                MemoryTierHelper.Tier tier = MemoryTierHelper.Tier.valueOf(tierName.toUpperCase());
                double decayRate = MemoryTierHelper.getDecayRate(tier);
                double initialRetention = MemoryTierHelper.getInitialRetention(tier);

                Date lastSeen = fact.getLastSeenAt();
                if (lastSeen == null) lastSeen = fact.getCreatedAt();
                if (lastSeen == null) continue;

                double daysSinceLastSeen = (now - lastSeen.getTime()) / (1000.0 * 60 * 60 * 24);
                double retention = initialRetention * Math.exp(-decayRate * daysSinceLastSeen);

                if (retention < MemoryTierHelper.FORGET_THRESHOLD) {
                    String before = JsonUtils.toJsonString(fact);
                    fact.setStatus(MemoryHelper.STATUS_ARCHIVED);
                    fact.setValidTo(new Date(now));
                    fact.setArchivedAt(new Date(now));
                    fact.setUpdatedAt(new Date(now));
                    factDao.updateById(fact);
                    writeRevision(fact.getAgentId(), fact.getMemoryId(), "decay_archive", before,
                            JsonUtils.toJsonString(fact), "system", "scheduler",
                            "艾宾浩斯衰减至阈值以下，自动归档: retention=" + String.format("%.4f", retention));
                    archived++;
                }
            } catch (Exception e) {
                log.warn("applyDecayToActiveFacts: 处理事实 {} 时异常: {}", fact.getMemoryId(), e.getMessage());
            }
        }
        if (archived > 0) {
            log.info("applyDecayToActiveFacts: 已归档 {} 条记忆", archived);
        }
    }

    // ===================== 管线私有方法 =====================

    private String buildDecisionPrompt(JSONObject factJson, List<MemoryFactEntity> existingFacts,
                                       String evidenceContext, int currentMemoryChars) {
        StringBuilder existingBlock = new StringBuilder();
        if (existingFacts == null || existingFacts.isEmpty()) {
            existingBlock.append("（当前作用域无已有长期记忆）");
        } else {
            for (int i = 0; i < existingFacts.size(); i++) {
                MemoryFactEntity f = existingFacts.get(i);
                existingBlock.append(i + 1).append(". id=").append(f.getMemoryId())
                        .append(" importance=").append(f.getImportance())
                        .append(" confidence=").append(f.getConfidence())
                        .append(" category=").append(f.getCategory())
                        .append(" text=").append(f.getCanonicalText())
                        .append("\n");
            }
        }

        return PowerMemPrompts.DECISION_PROMPT
                .replace("{fact_text}", factJson.getStr("text", factJson.getStr("canonical_text")))
                .replace("{fact_category}", StringUtils.defaultString(factJson.getStr("category"), "未分类"))
                .replace("{fact_memory_type}", StringUtils.defaultString(factJson.getStr("memory_type"), "长期记忆"))
                .replace("{fact_confidence}", String.valueOf(factJson.get("confidence") != null
                        ? factJson.get("confidence") : 0.7))
                .replace("{fact_importance}", String.valueOf(factJson.get("importance") != null
                        ? factJson.get("importance") : 0.7))
                .replace("{existing_count}", String.valueOf(existingFacts == null ? 0 : existingFacts.size()))
                .replace("{existing_memories}", existingBlock.toString())
                .replace("{current_memory_chars}", String.valueOf(currentMemoryChars))
                .replace("{max_memory_chars}", String.valueOf(MemoryHelper.MAX_ACTIVE_MEMORY_CHARS))
                .replace("{evidence_context}", StringUtils.defaultIfBlank(evidenceContext, "无可用对话证据"));
    }

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
        if ("ADD".equals(action) && !passesAutoWriteGate(json)) {
            log.info("维护模型输出低质量 ADD，跳过写入: text={}, importance={}, confidence={}",
                    MemoryHelper.cleanupMemoryText(json.getStr("canonical_text")),
                    json.get("importance"), json.get("confidence"));
            if (result != null) {
                result.setSkippedLowQuality(result.getSkippedLowQuality() + 1);
            }
            return false;
        }
        if ("ADD".equals(action) && isBlockedByTombstone(agent.getId(), scopeId, json.getStr("canonical_text"))) {
            log.info("维护模型 ADD 命中忘记规则，跳过写入: text={}",
                    MemoryHelper.cleanupMemoryText(json.getStr("canonical_text")));
            if (result != null) {
                result.setSkippedLowQuality(result.getSkippedLowQuality() + 1);
            }
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
        payload.set("memory_tier", resolveMemoryTier(json.getStr("memory_tier"), importance));
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
        String tier = resolveMemoryTier(json.getStr("memory_tier"), importance);
        String dimensionsJson = extractDimensionsJson(json);

        if ("UPDATE".equals(action) && StringUtils.isNotBlank(relatedId)) {
            MemoryFactEntity fact = findRelatedFact(agent.getId(), scopeId, relatedId);
            if (fact != null && MemorySpeakerUtils.isVisibleToSpeaker(fact, actionSpeakerId)) {
                // Check if the new canonical_text already exists as a different fact
                // (i.e. the LLM is trying to update this fact to match another one).
                String newHash = MemoryHelper.sha256(
                        agent.getId() + "|" + scopeId + "|" + speakerHashKey(json) + "|" + canonical);
                MemoryFactEntity existingDuplicate = factDao.selectOne(new QueryWrapper<MemoryFactEntity>()
                        .eq("agent_id", agent.getId()).eq("scope_type", MemoryHelper.SCOPE_DEVICE)
                        .eq("scope_id", scopeId).eq("source_hash", newHash)
                        .ne("status", MemoryHelper.STATUS_DELETED)
                        .ne("memory_id", relatedId) // exclude the fact being updated
                        .last("LIMIT 1"));
                if (existingDuplicate != null) {
                    // Merge: boost the existing fact's importance and confidence,
                    // then delete the one being updated (it's a duplicate).
                    log.warn("UPDATE检测到重复canonical_text: newText={}, duplicateMemoryId={}, "
                            + "原memoryId={}将被合并删除",
                            canonical, existingDuplicate.getMemoryId(), fact.getMemoryId());
                    existingDuplicate.setImportance(MemoryHelper.max(existingDuplicate.getImportance(), importance));
                    existingDuplicate.setConfidence(MemoryHelper.max(existingDuplicate.getConfidence(), confidence));
                    existingDuplicate.setMemoryTier(tier);
                    existingDuplicate.setExpiresAt(resolveExpiresAt(tier));
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
                fact.setExpiresAt(resolveExpiresAt(tier));
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
                agent.getId() + "|" + scopeId + "|" + speakerHashKey(json) + "|" + canonical);
        if (isBlockedByTombstone(agent.getId(), scopeId, canonical)) {
            log.info("维护模型创建事实命中忘记规则，已跳过: text={}", canonical);
            if (result != null) {
                result.setSkippedLowQuality(result.getSkippedLowQuality() + 1);
            }
            return false;
        }
        if (factDao.selectCount(new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agent.getId()).eq("scope_type", MemoryHelper.SCOPE_DEVICE)
                .eq("scope_id", scopeId).eq("source_hash", hash)
                .ne("status", MemoryHelper.STATUS_DELETED)) > 0) return false;

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
        fact.setExpiresAt(resolveExpiresAt(tier));
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
        if (projectedChars <= MemoryHelper.MAX_ACTIVE_MEMORY_CHARS) {
            return List.of();
        }
        if (contentDelta > MemoryHelper.MAX_ACTIVE_MEMORY_CHARS) {
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
        return projectedChars - freedChars <= MemoryHelper.MAX_ACTIVE_MEMORY_CHARS ? evictions : null;
    }

    private List<MemoryFactEntity> loadActiveFactsForCapacity(String agentId) {
        // 锁定智能体行，使同一智能体的并发记忆写入串行化，防止分别校验后合计超限。
        agentDao.selectOne(new QueryWrapper<AgentEntity>()
                .eq("id", agentId)
                .last("FOR UPDATE"));
        return loadMaintenanceFacts(agentId);
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
                    JsonUtils.toJsonString(fact), "system", modelId, "智能体 active 记忆容量超过 2000 字符");
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
                .eq("scope_type", MemoryHelper.SCOPE_DEVICE)
                .eq("scope_id", scopeId)
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

    private boolean isBlockedByTombstone(String agentId, String scopeId, String text) {
        String canonical = compactForTombstoneMatch(text);
        if (StringUtils.isBlank(canonical)) {
            return false;
        }
        List<MemoryForgetTombstoneEntity> tombstones = tombstoneDao.selectList(
                new QueryWrapper<MemoryForgetTombstoneEntity>()
                        .eq("agent_id", agentId)
                        .eq("scope_type", MemoryHelper.SCOPE_DEVICE)
                        .and(w -> w.eq("scope_id", scopeId).or().eq("scope_id", agentId))
                        .eq("status", MemoryHelper.STATUS_ACTIVE)
                        .orderByDesc("created_at")
                        .last("LIMIT 100"));
        for (MemoryForgetTombstoneEntity tombstone : tombstones) {
            if (matchesTombstone(canonical, tombstone)) {
                return true;
            }
        }
        return false;
    }

    private int archiveFactsMatchingPattern(AgentEntity agent, String scopeId, String pattern, String modelId,
                                            String actorType, String actorId) {
        String compactPattern = compactForTombstoneMatch(pattern);
        if (StringUtils.isBlank(compactPattern)) {
            return 0;
        }
        List<MemoryFactEntity> facts = factDao.selectList(new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agent.getId())
                .eq("scope_type", MemoryHelper.SCOPE_DEVICE)
                .and(w -> w.eq("scope_id", scopeId).or().eq("scope_id", agent.getId()))
                .eq("status", MemoryHelper.STATUS_ACTIVE)
                .orderByDesc("importance", "last_seen_at")
                .last("LIMIT 100"));
        int changed = 0;
        for (MemoryFactEntity fact : facts) {
            String factText = compactForTombstoneMatch(fact.getCanonicalText());
            if (!factText.contains(compactPattern) && !compactPattern.contains(factText)) {
                continue;
            }
            String before = JsonUtils.toJsonString(fact);
            fact.setStatus(MemoryHelper.STATUS_DELETED);
            fact.setValidTo(new Date());
            fact.setUpdatedAt(new Date());
            factDao.updateById(fact);
            writeRevision(agent.getId(), fact.getMemoryId(), "delete_from_forget_instruction", before,
                    JsonUtils.toJsonString(fact), actorType, StringUtils.defaultIfBlank(actorId, modelId),
                    "显式忘记匹配事实");
            changed++;
        }
        return changed;
    }

    private boolean matchesTombstone(String compactText, MemoryForgetTombstoneEntity tombstone) {
        if (tombstone == null) {
            return false;
        }
        String pattern = compactForTombstoneMatch(tombstone.getPatternText());
        return StringUtils.isNotBlank(pattern)
                && (compactText.contains(pattern) || pattern.contains(compactText));
    }

    private String compactForTombstoneMatch(String text) {
        return MemoryHelper.cleanupMemoryText(text)
                .replaceAll("[\\p{Punct}\\s，。！？、；：]+", "")
                .toLowerCase();
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

    private boolean passesAutoWriteGate(JSONObject json) {
        BigDecimal importance = MemoryHelper.score(json.get("importance"), MemoryHelper.DEFAULT_SCORE);
        BigDecimal confidence = MemoryHelper.score(json.get("confidence"), MemoryHelper.DEFAULT_SCORE);
        return importance.compareTo(MemoryHelper.MIN_AUTO_IMPORTANCE) >= 0
                && confidence.compareTo(MemoryHelper.MIN_AUTO_CONFIDENCE) >= 0;
    }

    private String resolveMemoryTier(String rawTier, BigDecimal importance) {
        String tier = MemoryHelper.normalizeMemoryTier(rawTier);
        if (StringUtils.isNotBlank(tier)) {
            return tier;
        }
        MemoryTierHelper.Tier classified = MemoryTierHelper.classifyTier(
                importance == null ? MemoryHelper.DEFAULT_SCORE.doubleValue() : importance.doubleValue());
        return classified.name().toLowerCase();
    }

    private Date resolveExpiresAt(String tier) {
        String normalized = MemoryHelper.normalizeMemoryTier(tier);
        if ("working".equals(normalized)) {
            return Date.from(Instant.now().plus(MemoryHelper.WORKING_MEMORY_TTL_HOURS, ChronoUnit.HOURS));
        }
        if ("short_term".equals(normalized)) {
            return Date.from(Instant.now().plus(MemoryHelper.SHORT_TERM_MEMORY_TTL_DAYS, ChronoUnit.DAYS));
        }
        return null;
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
                .replace("{existing_memories}", buildRebuildExistingMemoryContext(agent.getId(), scopeId, mode))
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
        for (Object item : arr) {
            JSONObject action = MemoryHelper.toJsonObject(item);
            if (action == null || "NONE".equals(MemoryHelper.normalizeAction(action.getStr("action")))) {
                continue;
            }
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

    private String buildRebuildExistingMemoryContext(String agentId, String scopeId, String mode) {
        if ("clear_then_rebuild".equalsIgnoreCase(mode)) {
            return "本次是清空后重建，不使用旧 memory_id；请只输出最终应生效的 ADD 事实。";
        }
        return buildMaintenanceMemoryContext(agentId, scopeId);
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

    private void rebuildConversation(AgentEntity agent, String scopeId, String conversation, boolean dryRun,
                                     MemoryRebuildResultVO result) {
        String maintenanceModelId = getMaintenanceLlmModelId(agent);
        if (StringUtils.isBlank(maintenanceModelId)) {
            return;
        }
        String extractionPrompt = PowerMemPrompts.EXTRACTION_PROMPT.replace("{conversation}", conversation);
        String extractionOutput = llmService.generateJson(extractionPrompt, maintenanceModelId);
        JSONArray facts = outputParser.parseExtractionResult(extractionOutput);
        if (facts == null || facts.isEmpty()) {
            return;
        }
        for (Object item : facts) {
            JSONObject factJson = MemoryHelper.toJsonObject(item);
            if (factJson == null) continue;
            List<MemoryFactEntity> existingFacts = loadMaintenanceFacts(agent.getId());
            String decisionOutput = llmService.generateJson(
                    buildDecisionPrompt(factJson, existingFacts, conversation,
                            totalMemoryChars(loadMaintenanceFacts(agent.getId()))), maintenanceModelId);
            JSONObject decision = outputParser.parseDecisionResult(decisionOutput);
            if (decision == null) continue;
            outputParser.mergeExtractionIntoDecision(factJson, decision);
            String action = MemoryHelper.normalizeAction(decision.getStr("action"));
            if (applyMaintenanceDecision(agent, scopeId, null, decision, maintenanceModelId, dryRun, result)) {
                if ("UPDATE".equals(action) || "DELETE".equals(action)) {
                    result.setFactsChanged(result.getFactsChanged() + 1);
                } else if ("ADD".equals(action)) {
                    result.setCandidatesCreated(result.getCandidatesCreated() + 1);
                }
            }
        }
    }

    // ===================== 共享辅助方法 =====================

    private String buildMaintenanceMemoryContext(String agentId, String scopeId) {
        QueryWrapper<MemoryFactEntity> wrapper = new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agentId)
                .eq("status", MemoryHelper.STATUS_ACTIVE);
        if (StringUtils.isNotBlank(scopeId)) {
            wrapper.and(w -> w.eq("scope_type", MemoryHelper.SCOPE_DEVICE).eq("scope_id", scopeId)
                    .or()
                    .eq("scope_type", MemoryHelper.SCOPE_DEVICE).eq("scope_id", agentId));
        }
        List<MemoryFactEntity> facts = factDao.selectList(wrapper
                .orderByDesc("importance", "last_seen_at")
                .last("LIMIT 30"));
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

    private MemoryFactEntity findRelatedFact(String agentId, String scopeId, String relatedMemoryId) {
        if (StringUtils.isBlank(relatedMemoryId)) {
            return null;
        }
        QueryWrapper<MemoryFactEntity> wrapper = new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agentId)
                .eq("memory_id", relatedMemoryId)
                .eq("status", MemoryHelper.STATUS_ACTIVE);
        if (StringUtils.isNotBlank(scopeId)) {
            wrapper.and(w -> w.eq("scope_type", MemoryHelper.SCOPE_DEVICE).eq("scope_id", scopeId)
                    .or()
                    .eq("scope_type", MemoryHelper.SCOPE_DEVICE).eq("scope_id", agentId));
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
