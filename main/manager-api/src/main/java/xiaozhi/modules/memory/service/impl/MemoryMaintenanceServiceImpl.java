package xiaozhi.modules.memory.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import xiaozhi.modules.memory.dao.MemorySourceEventDao;
import xiaozhi.modules.memory.dto.MemoryRebuildRequestDTO;
import xiaozhi.modules.memory.entity.MemoryCandidateEntity;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.entity.MemoryForgetTombstoneEntity;
import xiaozhi.modules.memory.entity.MemoryRevisionEntity;
import xiaozhi.modules.memory.entity.MemorySourceEventEntity;
import xiaozhi.modules.memory.prompt.PowerMemPrompts;
import xiaozhi.modules.memory.service.MemoryMaintenanceService;
import xiaozhi.modules.memory.utils.MemoryHelper;
import xiaozhi.modules.memory.utils.MemorySimilarityUtils;
import xiaozhi.modules.memory.utils.MemoryTierHelper;
import xiaozhi.modules.memory.vo.MemoryRebuildResultVO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.impl.ModelRuntimeAssembler;
import xiaozhi.modules.security.user.SecurityUser;

@Service
@AllArgsConstructor
@Slf4j
public class MemoryMaintenanceServiceImpl implements MemoryMaintenanceService {

    private final MemorySourceEventDao sourceEventDao;
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

    // ===================== 公开方法 =====================

    @Override
    public int processSessionMemories(String sessionId, String agentId) {
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
        String conversation = buildSessionConversation(agentId, sessionId);
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
        JSONArray facts = parseExtractionResult(extractionOutput);
        if (facts == null || facts.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (Object item : facts) {
            JSONObject factJson = MemoryHelper.toJsonObject(item);
            if (factJson == null) continue;

            String factText = factJson.getStr("text", factJson.getStr("canonical_text"));

            // Stage 2: 语义搜索 + 决策
            List<MemoryFactEntity> similarFacts = findSimilarFacts(agentId, null,
                    factText + "\n" + conversation, maintenanceModelId);
            String decisionPrompt = buildDecisionPrompt(factJson, similarFacts);
            String decisionOutput = llmService.generateJson(decisionPrompt, maintenanceModelId);
            if (StringUtils.isBlank(decisionOutput)) continue;

            JSONObject decision = parseDecisionResult(decisionOutput);
            if (decision == null) continue;
            mergeExtractionIntoDecision(factJson, decision);
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
        for (JSONObject action : actions) {
            if (applyMaintenanceDecision(agent, scopeId, null, action, getMaintenanceLlmModelId(agent), true, result)) {
                result.setActionsProposed(result.getActionsProposed() + 1);
                result.setCandidatesCreated(result.getCandidatesCreated() + 1);
            }
        }
        if (dryRun || actions.isEmpty()) {
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
        for (JSONObject action : actions) {
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

    /**
     * PowerMem 两阶段管线：Stage 1 提取事实 → Stage 2 语义搜索 + 决策执行。
     */
    @Override
    public void createCandidatesWithMaintenanceModel(AgentEntity agent, String scopeId, String sourceEventId, String content) {
        String maintenanceModelId = getMaintenanceLlmModelId(agent);
        if (StringUtils.isBlank(maintenanceModelId)) {
            return;
        }
        String promptInput = StringUtils.abbreviate(content, MemoryHelper.MAX_MAINTENANCE_INPUT_CHARS);

        // Stage 1: 提取事实
        String extractionPrompt = PowerMemPrompts.EXTRACTION_PROMPT.replace("{conversation}", promptInput);
        String extractionOutput = llmService.generateJson(extractionPrompt, maintenanceModelId);
        if (StringUtils.isBlank(extractionOutput)) {
            log.warn("Stage 1 事实提取失败，agentId: {}", agent.getId());
            return;
        }
        JSONArray facts = parseExtractionResult(extractionOutput);
        if (facts == null || facts.isEmpty()) {
            return;
        }

        int saved = 0;
        for (Object item : facts) {
            JSONObject factJson = MemoryHelper.toJsonObject(item);
            if (factJson == null) continue;

            String factText = factJson.getStr("text", factJson.getStr("canonical_text"));

            // Stage 2a: 语义搜索
            List<MemoryFactEntity> similarFacts = findSimilarFacts(agent.getId(), null,
                    factText + "\n" + content, maintenanceModelId);

            // Stage 2b: 决策
            String decisionPrompt = buildDecisionPrompt(factJson, similarFacts);
            String decisionOutput = llmService.generateJson(decisionPrompt, maintenanceModelId);
            if (StringUtils.isBlank(decisionOutput)) {
                continue;
            }
            JSONObject decision = parseDecisionResult(decisionOutput);
            if (decision == null) continue;

            mergeExtractionIntoDecision(factJson, decision);

            if (applyMaintenanceDecision(agent, scopeId, sourceEventId, decision, maintenanceModelId, false, null)) {
                saved++;
            }
        }
    }

    @Override
    public boolean applyMaintenanceInstruction(AgentEntity agent, String scopeId, String instruction,
                                               String actorType, String actorId) {
        String maintenanceModelId = getMaintenanceLlmModelId(agent);
        if (StringUtils.isBlank(instruction)) {
            return false;
        }
        if (StringUtils.isBlank(maintenanceModelId)) {
            throw new IllegalStateException("未配置记忆维护模型");
        }
        String existingMemories = buildMaintenanceMemoryContext(agent.getId(), null);
        String prompt = PowerMemPrompts.COMMAND_DECISION_PROMPT
                .replace("{instruction}", instruction)
                .replace("{existing_memories}", existingMemories);
        String output = llmService.generateJson(prompt, maintenanceModelId);
        JSONObject decision = parseDecisionResult(output);
        if (decision == null) {
            throw new IllegalStateException("记忆维护模型未返回有效决策");
        }
        JSONArray instructionActions = new JSONArray();
        instructionActions.add(decision);
        String evidenceContext = "管理端指令：\n" + instruction + "\n\n已有记忆：\n" + existingMemories;
        JSONArray reviewed = reviewRebuildActions(instructionActions, "merge", evidenceContext, maintenanceModelId);
        if (reviewed != null && !reviewed.isEmpty()) {
            JSONObject reviewedDecision = MemoryHelper.toJsonObject(reviewed.get(0));
            if (reviewedDecision != null) {
                decision = reviewedDecision;
            }
        }
        boolean changed = applyMaintenanceDecision(agent, scopeId, null, decision, maintenanceModelId, false, null);
        writeRevision(agent.getId(), null, "maintenance_instruction",
                JsonUtils.toJsonString(Map.of("instruction", instruction)), JsonUtils.toJsonString(decision),
                actorType, actorId, "维护模型处理管理端指令");
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
                        .isNotNull("memory_tier")
        );
        if (facts.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        int archived = 0;
        for (MemoryFactEntity fact : facts) {
            try {
                String tierName = fact.getMemoryTier();
                if (StringUtils.isBlank(tierName)) continue;
                MemoryTierHelper.Tier tier = MemoryTierHelper.Tier.valueOf(tierName);
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

    private String buildSessionConversation(String agentId, String sessionId) {
        List<MemorySourceEventEntity> events = sourceEventDao.selectList(
                new QueryWrapper<MemorySourceEventEntity>()
                        .eq("agent_id", agentId)
                        .eq("session_id", sessionId)
                        .eq("chat_type", 1)
                        .orderByAsc("created_at"));
        if (events.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (MemorySourceEventEntity event : events) {
            String text = StringUtils.defaultString(event.getContentText());
            if (StringUtils.isNotBlank(text)) {
                sb.append(text).append("\n");
            }
        }
        return StringUtils.abbreviate(sb.toString(), MemoryHelper.SESSION_MAX_CHARS);
    }

    private JSONArray parseExtractionResult(String output) {
        String jsonText = MemoryHelper.extractJsonObject(output);
        if (StringUtils.isBlank(jsonText)) return null;
        try {
            JSONObject root = JSONUtil.parseObj(jsonText);
            JSONArray arr = root.getJSONArray("facts");
            if (arr != null) return arr;
            if (StringUtils.isNotBlank(root.getStr("text")) || StringUtils.isNotBlank(root.getStr("canonical_text"))) {
                JSONArray single = new JSONArray();
                single.add(root);
                return single;
            }
        } catch (Exception e) {
            log.warn("解析 Stage 1 提取结果失败: {}", e.getMessage());
        }
        return null;
    }

    private JSONObject parseDecisionResult(String output) {
        String jsonText = MemoryHelper.extractJsonObject(output);
        if (StringUtils.isBlank(jsonText)) return null;
        try {
            return JSONUtil.parseObj(jsonText);
        } catch (Exception e) {
            log.warn("解析 Stage 2 决策结果失败: {}", e.getMessage());
            return null;
        }
    }

    private JSONArray parseActionsResult(String output) {
        String jsonText = MemoryHelper.extractJsonObject(output);
        if (StringUtils.isBlank(jsonText)) return null;
        try {
            JSONObject root = JSONUtil.parseObj(jsonText);
            JSONArray actions = root.getJSONArray("actions");
            if (actions != null) {
                return actions;
            }
            if (StringUtils.isNotBlank(root.getStr("action"))) {
                JSONArray single = new JSONArray();
                single.add(root);
                return single;
            }
        } catch (Exception e) {
            log.warn("解析维护动作列表失败: {}", e.getMessage());
        }
        return null;
    }

    private void mergeExtractionIntoDecision(JSONObject extraction, JSONObject decision) {
        if (StringUtils.isBlank(decision.getStr("canonical_text"))) {
            decision.set("canonical_text", extraction.getStr("text", extraction.getStr("canonical_text")));
        }
        if (StringUtils.isBlank(decision.getStr("category"))) {
            decision.set("category", extraction.getStr("category"));
        }
        if (StringUtils.isBlank(decision.getStr("memory_type"))) {
            decision.set("memory_type", extraction.getStr("memory_type"));
        }
        if (decision.get("importance_dimensions") == null && extraction.get("importance_dimensions") != null) {
            decision.set("importance_dimensions", extraction.get("importance_dimensions"));
        }
    }

    private String buildDecisionPrompt(JSONObject factJson, List<MemoryFactEntity> similarFacts) {
        StringBuilder similarBlock = new StringBuilder();
        if (similarFacts == null || similarFacts.isEmpty()) {
            similarBlock.append("（无语义相似的已有记忆）");
        } else {
            for (int i = 0; i < similarFacts.size(); i++) {
                MemoryFactEntity f = similarFacts.get(i);
                similarBlock.append(i + 1).append(". id=").append(f.getMemoryId())
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
                .replace("{similar_count}", String.valueOf(similarFacts == null ? 0 : similarFacts.size()))
                .replace("{similar_memories}", similarBlock.toString());
    }

    private List<MemoryFactEntity> findSimilarFacts(String agentId, String scopeId, String text, String modelId) {
        float[] queryEmbedding = generateEmbedding(text, modelId);
        QueryWrapper<MemoryFactEntity> wrapper = new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agentId)
                .eq("status", MemoryHelper.STATUS_ACTIVE);
        if (StringUtils.isNotBlank(scopeId)) {
            wrapper.eq("scope_type", MemoryHelper.SCOPE_DEVICE).eq("scope_id", scopeId);
        }
        List<MemoryFactEntity> allActive = factDao.selectList(wrapper
                .orderByDesc("importance", "last_seen_at")
                .last("LIMIT 100"));

        if (allActive.isEmpty()) return List.of();

        if (queryEmbedding != null) {
            List<Map.Entry<MemoryFactEntity, Double>> scored = new ArrayList<>();
            for (MemoryFactEntity fact : allActive) {
                float[] factEmb = MemorySimilarityUtils.parseEmbedding(fact.getEmbeddingJson());
                if (factEmb != null) {
                    double sim = MemorySimilarityUtils.cosineSimilarity(queryEmbedding, factEmb);
                    scored.add(Map.entry(fact, sim));
                }
            }
            scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            List<MemoryFactEntity> top = new ArrayList<>();
            for (int i = 0; i < Math.min(10, scored.size()); i++) {
                top.add(scored.get(i).getKey());
            }
            return top;
        }

        // Embedding generation failed (e.g. upstream embedding model unavailable).
        // Return empty list to signal "no semantically similar facts found" so the
        // decision LLM treats the incoming fact as new rather than being biased by
        // top-N-by-importance results that are not actually similar.
        log.warn("findSimilarFacts: embedding generation returned null for agentId={}, returning empty list", agentId);
        return List.of();
    }

    private float[] generateEmbedding(String text, String modelId) {
        try {
            List<Double> vector = llmService.generateEmbedding(text, modelId);
            if (vector == null || vector.isEmpty()) return null;
            float[] result = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                result[i] = vector.get(i).floatValue();
            }
            return result;
        } catch (Exception e) {
            log.debug("生成嵌入向量失败（将回退到维护模型上下文决策）: {}", e.getMessage());
            return null;
        }
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
        return executeMemoryAction(agent, scopeId, json, modelId);
    }

    private MemoryCandidateEntity buildCandidate(AgentEntity agent, String scopeId, String sourceEventId, JSONObject json,
                                                 String modelId, String action) {
        String canonical = MemoryHelper.cleanupMemoryText(json.getStr("canonical_text"));
        if (StringUtils.isBlank(canonical) && !"DELETE".equals(action)) {
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
        payload.set("importance_dimensions", json.get("importance_dimensions"));

        MemoryCandidateEntity candidate = new MemoryCandidateEntity();
        candidate.setCandidateId(MemoryHelper.uuid());
        candidate.setAgentId(agent.getId());
        candidate.setUserId(agent.getUserId());
        candidate.setScopeType(MemoryHelper.SCOPE_DEVICE);
        candidate.setScopeId(scopeId);
        candidate.setMemoryType(MemoryHelper.normalizeMemoryLabel(json.getStr("memory_type"), "长期记忆"));
        candidate.setCategory(MemoryHelper.normalizeMemoryLabel(json.getStr("category"), "未分类"));
        candidate.setCanonicalText(canonical);
        candidate.setPayload(payload.toString());
        candidate.setConfidence(confidence);
        candidate.setImportance(importance);
        candidate.setSensitivity(MemoryHelper.normalizeSensitivity(json.getStr("sensitivity")));
        candidate.setStatus(MemoryHelper.STATUS_PENDING);
        candidate.setSourceEventId(sourceEventId);
        candidate.setExtractorVersion(MemoryHelper.MAINTENANCE_EXTRACTOR_VERSION);
        candidate.setCreatedAt(new Date());
        candidate.setUpdatedAt(new Date());
        return candidate;
    }

    private boolean executeMemoryAction(AgentEntity agent, String scopeId, JSONObject json, String modelId) {
        String action = MemoryHelper.normalizeAction(json.getStr("action"));
        if ("NONE".equals(action)) return false;

        String canonical = MemoryHelper.cleanupMemoryText(json.getStr("canonical_text"));
        if (StringUtils.isBlank(canonical) && !"DELETE".equals(action)) {
            return false;
        }

        if ("DELETE".equals(action)) {
            String relatedId = StringUtils.trimToEmpty(json.getStr("related_memory_id"));
            if (StringUtils.isNotBlank(relatedId)) {
                MemoryFactEntity fact = factDao.selectOne(new QueryWrapper<MemoryFactEntity>()
                        .eq("agent_id", agent.getId()).eq("memory_id", relatedId)
                        .eq("status", MemoryHelper.STATUS_ACTIVE).last("LIMIT 1"));
                if (fact != null) {
                    String before = JsonUtils.toJsonString(fact);
                    fact.setStatus(MemoryHelper.STATUS_DELETED);
                    fact.setValidTo(new Date());
                    fact.setUpdatedAt(new Date());
                    factDao.updateById(fact);
                    writeRevision(agent.getId(), fact.getMemoryId(), "delete_from_maintenance", before,
                            JsonUtils.toJsonString(fact), "system", modelId,
                            "维护模型自动遗忘: " + StringUtils.defaultString(json.getStr("reason")));
                    log.info("维护模型删除事实, memoryId={}, text={}", fact.getMemoryId(), fact.getCanonicalText());
                    return true;
                }
            }
            return false;
        }

        String memoryType = MemoryHelper.normalizeMemoryLabel(json.getStr("memory_type"), "长期记忆");
        String category = MemoryHelper.normalizeMemoryLabel(json.getStr("category"), "未分类");
        BigDecimal confidence = MemoryHelper.score(json.get("confidence"), MemoryHelper.DEFAULT_SCORE);
        BigDecimal importance = MemoryHelper.score(json.get("importance"), MemoryHelper.DEFAULT_SCORE);
        String relatedId = StringUtils.trimToEmpty(json.getStr("related_memory_id"));
        String sensitivity = MemoryHelper.normalizeSensitivity(json.getStr("sensitivity"));
        String tier = MemoryHelper.normalizeMemoryTier(json.getStr("memory_tier"));
        String dimensionsJson = extractDimensionsJson(json);

        if ("UPDATE".equals(action) && StringUtils.isNotBlank(relatedId)) {
            MemoryFactEntity fact = findRelatedFact(agent.getId(), relatedId);
            if (fact != null) {
                // Check if the new canonical_text already exists as a different fact
                // (i.e. the LLM is trying to update this fact to match another one).
                String newHash = MemoryHelper.sha256(agent.getId() + "|" + scopeId + "|" + canonical);
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
                    existingDuplicate.setLastSeenAt(new Date());
                    existingDuplicate.setUpdatedAt(new Date());
                    factDao.updateById(existingDuplicate);

                    // Delete the old fact being updated (now a duplicate)
                    String before = JsonUtils.toJsonString(fact);
                    fact.setStatus(MemoryHelper.STATUS_DELETED);
                    fact.setValidTo(new Date());
                    fact.setUpdatedAt(new Date());
                    factDao.updateById(fact);
                    writeRevision(agent.getId(), fact.getMemoryId(), "update_merged_as_duplicate", before,
                            JsonUtils.toJsonString(fact), "system", modelId,
                            "合并到已有事实: " + existingDuplicate.getMemoryId());
                    return true;
                }

                String before = JsonUtils.toJsonString(fact);
                fact.setMemoryType(memoryType);
                fact.setCategory(category);
                fact.setCanonicalText(canonical);
                fact.setConfidence(MemoryHelper.max(fact.getConfidence(), confidence));
                fact.setImportance(MemoryHelper.max(fact.getImportance(), importance));
                fact.setSensitivity(sensitivity);
                if (StringUtils.isNotBlank(dimensionsJson)) fact.setImportanceDimensions(dimensionsJson);
                if (StringUtils.isNotBlank(tier)) fact.setMemoryTier(tier);
                fact.setStatus(MemoryHelper.STATUS_ACTIVE);
                fact.setLastSeenAt(new Date());
                fact.setUpdatedAt(new Date());
                factDao.updateById(fact);
                writeRevision(agent.getId(), fact.getMemoryId(), "update_from_maintenance", before,
                        JsonUtils.toJsonString(fact), "system", modelId, "维护模型更新事实");
                log.info("维护模型更新事实, memoryId={}, text={}", fact.getMemoryId(), canonical);
                return true;
            }
        }

        // ADD
        String hash = MemoryHelper.sha256(agent.getId() + "|" + scopeId + "|" + canonical);
        if (factDao.selectCount(new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agent.getId()).eq("scope_type", MemoryHelper.SCOPE_DEVICE)
                .eq("scope_id", scopeId).eq("source_hash", hash)
                .ne("status", MemoryHelper.STATUS_DELETED)) > 0) return false;

        MemoryFactEntity fact = new MemoryFactEntity();
        fact.setMemoryId(MemoryHelper.uuid());
        fact.setAgentId(agent.getId());
        fact.setUserId(agent.getUserId());
        fact.setMacAddress(scopeId);
        fact.setScopeType(MemoryHelper.SCOPE_DEVICE);
        fact.setScopeId(scopeId);
        fact.setMemoryType(memoryType);
        fact.setCategory(category);
        fact.setCanonicalText(canonical);
        fact.setPayload(JSONUtil.toJsonStr(Map.of("maintenance_llm", modelId, "source", MemoryHelper.MAINTENANCE_EXTRACTOR_VERSION,
                "reason", StringUtils.defaultString(json.getStr("reason")))));
        fact.setImportance(importance);
        fact.setConfidence(confidence);
        fact.setSensitivity(sensitivity);
        fact.setConfirmationState("auto");
        fact.setStatus(MemoryHelper.STATUS_ACTIVE);
        if (StringUtils.isNotBlank(tier)) fact.setMemoryTier(tier);
        if (StringUtils.isNotBlank(dimensionsJson)) fact.setImportanceDimensions(dimensionsJson);
        float[] embedding = generateEmbedding(canonical, modelId);
        if (embedding != null) {
            fact.setEmbeddingJson(MemorySimilarityUtils.encodeEmbedding(embedding));
        }
        fact.setValidFrom(new Date());
        fact.setFirstSeenAt(new Date());
        fact.setLastSeenAt(new Date());
        fact.setSourceEventId(null);
        fact.setSourceHash(hash);
        fact.setExtractorVersion(MemoryHelper.MAINTENANCE_EXTRACTOR_VERSION);
        fact.setCreatedAt(new Date());
        fact.setUpdatedAt(new Date());
        factDao.insert(fact);
        writeRevision(agent.getId(), fact.getMemoryId(), "create_from_maintenance", null,
                JsonUtils.toJsonString(fact), "system", modelId, "维护模型创建事实");
        log.info("维护模型创建事实, memoryId={}, text={}", fact.getMemoryId(), canonical);
        return true;
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

    // ===================== 重建方法 =====================

    private List<JSONObject> buildRebuildActions(AgentEntity agent, String scopeId,
                                                 List<List<AgentChatHistoryDTO>> histories, String mode) {
        String maintenanceModelId = getMaintenanceLlmModelId(agent);
        if (StringUtils.isBlank(maintenanceModelId)) {
            return List.of();
        }
        String conversation = buildCombinedConversationForMaintenance(histories);
        if (StringUtils.isBlank(conversation)) {
            return List.of();
        }
        String prompt = PowerMemPrompts.REBUILD_ACTIONS_PROMPT
                .replace("{mode}", StringUtils.defaultIfBlank(mode, "merge"))
                .replace("{existing_memories}", buildRebuildExistingMemoryContext(agent.getId(), scopeId, mode))
                .replace("{conversation}", conversation);
        String output = llmService.generateJson(prompt, maintenanceModelId);
        JSONArray arr = parseActionsResult(output);
        if ((arr == null || arr.isEmpty()) && StringUtils.isNotBlank(output)) {
            String repaired = llmService.generateJson(
                    PowerMemPrompts.JSON_REPAIR_PROMPT.replace("{raw_output}", output),
                    maintenanceModelId);
            arr = parseActionsResult(repaired);
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
            if (action != null && !"NONE".equals(MemoryHelper.normalizeAction(action.getStr("action")))) {
                actions.add(action);
            }
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
        JSONArray reviewed = parseActionsResult(output);
        if ((reviewed == null || reviewed.isEmpty()) && StringUtils.isNotBlank(output)) {
            String repaired = llmService.generateJson(
                    PowerMemPrompts.JSON_REPAIR_PROMPT.replace("{raw_output}", output),
                    maintenanceModelId);
            reviewed = parseActionsResult(repaired);
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

    private String buildConversationForMaintenance(List<AgentChatHistoryDTO> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (AgentChatHistoryDTO item : history) {
            if (item == null || item.getChatType() == null || item.getChatType() == 3) {
                continue;
            }
            String content = normalizeChatHistoryContent(item.getContent());
            if (StringUtils.isBlank(content)) {
                continue;
            }
            builder.append(item.getChatType() == 1 ? "用户: " : "助手: ")
                    .append(content)
                    .append("\n");
        }
        return StringUtils.abbreviate(builder.toString(), MemoryHelper.SESSION_MAX_CHARS);
    }

    private String buildCombinedConversationForMaintenance(List<List<AgentChatHistoryDTO>> histories) {
        if (histories == null || histories.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (List<AgentChatHistoryDTO> history : histories) {
            String conversation = buildConversationForMaintenance(history);
            if (StringUtils.isBlank(conversation)) {
                continue;
            }
            builder.append("【会话】\n").append(conversation).append("\n");
        }
        return StringUtils.abbreviate(builder.toString(), MemoryHelper.REBUILD_MAX_TOTAL_CHARS);
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
        JSONArray facts = parseExtractionResult(extractionOutput);
        if (facts == null || facts.isEmpty()) {
            return;
        }
        for (Object item : facts) {
            JSONObject factJson = MemoryHelper.toJsonObject(item);
            if (factJson == null) continue;
            String factText = factJson.getStr("text", factJson.getStr("canonical_text"));

            List<MemoryFactEntity> similarFacts = findSimilarFacts(agent.getId(), scopeId,
                    factText + "\n" + conversation, maintenanceModelId);
            String decisionOutput = llmService.generateJson(buildDecisionPrompt(factJson, similarFacts), maintenanceModelId);
            JSONObject decision = parseDecisionResult(decisionOutput);
            if (decision == null) continue;
            mergeExtractionIntoDecision(factJson, decision);
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

    private String normalizeChatHistoryContent(String content) {
        if (StringUtils.isBlank(content)) {
            return "";
        }
        try {
            Map<?, ?> jsonMap = JsonUtils.parseObject(content, Map.class);
            if (jsonMap != null && jsonMap.containsKey("content")) {
                Object value = jsonMap.get("content");
                return value == null ? "" : String.valueOf(value).trim();
            }
        } catch (Exception ignored) {
        }
        return content.trim();
    }

    private JSONArray parseMaintenanceMemories(String output) {
        String jsonText = MemoryHelper.extractJsonObject(output);
        if (StringUtils.isBlank(jsonText)) {
            return null;
        }
        try {
            JSONObject root = JSONUtil.parseObj(jsonText);
            JSONArray memories = root.getJSONArray("memories");
            if (memories != null) {
                return memories;
            }
            if (StringUtils.isNotBlank(root.getStr("canonical_text"))) {
                JSONArray single = new JSONArray();
                single.add(root);
                return single;
            }
        } catch (Exception e) {
            log.warn("解析长期记忆维护模型 JSON 失败: {}", e.getMessage());
        }
        return null;
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
        return factDao.selectOne(new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agentId)
                .eq("memory_id", relatedMemoryId)
                .ne("status", MemoryHelper.STATUS_DELETED)
                .last("LIMIT 1"));
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
                && !user.getId().equals(agent.getUserId())) {
            throw new IllegalArgumentException("无权访问该智能体记忆");
        }
        return agent;
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
