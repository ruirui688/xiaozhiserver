package xiaozhi.modules.memory.service.impl;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.page.PageData;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.agent.dto.AgentChatHistoryDTO;
import xiaozhi.modules.agent.dto.AgentChatSessionDTO;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.llm.service.EmbeddingService;
import xiaozhi.modules.llm.service.LLMService;
import xiaozhi.modules.memory.dao.MemoryCandidateDao;
import xiaozhi.modules.memory.dao.MemoryFactDao;
import xiaozhi.modules.memory.dao.MemoryForgetTombstoneDao;
import xiaozhi.modules.memory.dao.MemoryProfileDao;
import xiaozhi.modules.memory.dao.MemoryRevisionDao;
import xiaozhi.modules.memory.dao.MemorySourceEventDao;
import xiaozhi.modules.memory.dao.MemoryToolBehaviorDao;
import xiaozhi.modules.memory.dto.MemoryEventDTO;
import xiaozhi.modules.memory.dto.MemoryFactDTO;
import xiaozhi.modules.memory.dto.MemoryForgetDTO;
import xiaozhi.modules.memory.dto.MemoryQueryDTO;
import xiaozhi.modules.memory.dto.MemoryRememberDTO;
import xiaozhi.modules.memory.dto.MemoryRebuildRequestDTO;
import xiaozhi.modules.memory.entity.MemoryCandidateEntity;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.entity.MemoryForgetTombstoneEntity;
import xiaozhi.modules.memory.entity.MemoryProfileEntity;
import xiaozhi.modules.memory.entity.MemoryRevisionEntity;
import xiaozhi.modules.memory.entity.MemorySourceEventEntity;
import xiaozhi.modules.memory.entity.MemoryToolBehaviorEntity;
import xiaozhi.modules.memory.prompt.PowerMemPrompts;
import xiaozhi.modules.memory.service.MemoryService;
import xiaozhi.modules.memory.utils.MemorySimilarityUtils;
import xiaozhi.modules.memory.vo.MemoryDashboardVO;
import xiaozhi.modules.memory.vo.MemoryQueryVO;
import xiaozhi.modules.memory.vo.MemoryRebuildResultVO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.impl.ModelRuntimeAssembler;
import xiaozhi.modules.security.user.SecurityUser;

@Service
@AllArgsConstructor
@Slf4j
public class MemoryServiceImpl implements MemoryService {
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_ARCHIVED = "archived";
    private static final String STATUS_DELETED = "deleted";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_REJECTED = "rejected";
    private static final String STATUS_INACTIVE = "inactive";
    private static final String SCOPE_DEVICE = "device";
    private static final BigDecimal DEFAULT_SCORE = new BigDecimal("0.700");
    private static final BigDecimal MIN_SCORE = new BigDecimal("0.000");
    private static final BigDecimal MAX_SCORE = new BigDecimal("1.000");
    private static final int MAX_MAINTENANCE_INPUT_CHARS = 2000;
    private static final String MAINTENANCE_LLM_FIELD = "maintenance_llm";
    private static final String MAINTENANCE_EXTRACTOR_VERSION = "maintenance-llm-v3-powerMem";
    private static final int SESSION_MAX_CHARS = 8000;

    private final MemorySourceEventDao sourceEventDao;
    private final MemoryCandidateDao candidateDao;
    private final MemoryFactDao factDao;
    private final MemoryRevisionDao revisionDao;
    private final MemoryProfileDao profileDao;
    private final MemoryToolBehaviorDao toolBehaviorDao;
    private final MemoryForgetTombstoneDao tombstoneDao;
    private final DeviceDao deviceDao;
    private final AgentDao agentDao;
    private final LLMService llmService;
    private final EmbeddingService embeddingService;
    private final ModelConfigService modelConfigService;
    private final ModelRuntimeAssembler modelRuntimeAssembler;
    private final AgentChatHistoryService agentChatHistoryService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordEvent(MemoryEventDTO dto) {
        AgentScope scope = resolveScope(dto.getAgentId(), dto.getMacAddress());
        if (scope.agent == null) {
            return;
        }
        String content = normalizeContent(dto.getContentText(), dto.getContentJson());
        MemorySourceEventEntity event = new MemorySourceEventEntity();
        event.setEventId(uuid());
        event.setAgentId(scope.agent.getId());
        event.setUserId(scope.agent.getUserId());
        event.setMacAddress(dto.getMacAddress());
        event.setSessionId(dto.getSessionId());
        event.setScopeType(SCOPE_DEVICE);
        event.setScopeId(scope.scopeId);
        event.setSpeakerId(dto.getSpeakerId());
        event.setEventType(StringUtils.defaultIfBlank(dto.getEventType(), "chat"));
        event.setChatType(dto.getChatType());
        event.setContentText(content);
        event.setContentJson(dto.getContentJson() == null ? null : JsonUtils.toJsonString(dto.getContentJson()));
        event.setPiiLevel("normal");
        event.setSourceTimestamp(dto.getSourceTimestamp() == null ? new Date() : new Date(dto.getSourceTimestamp()));
        event.setCreatedAt(new Date());
        sourceEventDao.insert(event);

        if (StringUtils.isBlank(content)) {
            return;
        }
        if (shouldRunMaintenanceExtraction(dto, content)) {
            runAfterCommit(() -> createCandidatesWithMaintenanceModel(scope.agent, scope.scopeId, event.getEventId(), content));
        }
    }

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
                StringUtils.abbreviate(conversation, SESSION_MAX_CHARS));
        String extractionOutput = llmService.generateSummary(extractionPrompt, maintenanceModelId);
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
            JSONObject factJson = toJsonObject(item);
            if (factJson == null) continue;

            String factText = factJson.getStr("text", factJson.getStr("canonical_text"));

            // Stage 2: 语义搜索 + 决策
            List<MemoryFactEntity> similarFacts = findSimilarFacts(agentId, agentId,
                    factText + "\n" + conversation, maintenanceModelId);
            String decisionPrompt = buildDecisionPrompt(factJson, similarFacts);
            String decisionOutput = llmService.generateSummary(decisionPrompt, maintenanceModelId);
            if (StringUtils.isBlank(decisionOutput)) continue;

            JSONObject decision = parseDecisionResult(decisionOutput);
            if (decision == null) continue;
            mergeExtractionIntoDecision(factJson, decision);
            if (applyMaintenanceDecision(agent, agentId, null, decision, maintenanceModelId, false, null)) count++;
        }
        log.info("processSessionMemories: agentId={}, sessionId={}, factsChanged={}", agentId, sessionId, count);
        return count;
    }

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
        return StringUtils.abbreviate(sb.toString(), SESSION_MAX_CHARS);
    }

    // ===================== 两阶段管线辅助方法 =====================

    /**
     * 解析 Stage 1 提取结果的 JSON，返回 facts 数组。
     */
    private JSONArray parseExtractionResult(String output) {
        String jsonText = extractJsonObject(output);
        if (StringUtils.isBlank(jsonText)) return null;
        try {
            JSONObject root = JSONUtil.parseObj(jsonText);
            JSONArray arr = root.getJSONArray("facts");
            if (arr != null) return arr;
            // 兼容单条事实格式
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

    /**
     * 解析 Stage 2 决策结果的 JSON。
     */
    private JSONObject parseDecisionResult(String output) {
        String jsonText = extractJsonObject(output);
        if (StringUtils.isBlank(jsonText)) return null;
        try {
            return JSONUtil.parseObj(jsonText);
        } catch (Exception e) {
            log.warn("解析 Stage 2 决策结果失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将 Stage 1 提取信息合并到 Stage 2 决策结果中（补充可能缺失的字段）。
     */
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

    /**
     * 构建 Stage 2 决策提示词，包含目标事实和语义相似的已有记忆。
     */
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

    /**
     * 向量语义搜索：找到与新事实最相似的已有活跃记忆。
     * <p>如果嵌入服务不可用，把最近记忆交给维护模型决策。</p>
     */
    private List<MemoryFactEntity> findSimilarFacts(String agentId, String scopeId, String text, String modelId) {
        float[] queryEmbedding = generateEmbedding(text, modelId);
        List<MemoryFactEntity> allActive = factDao.selectList(new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agentId)
                .eq("scope_type", SCOPE_DEVICE)
                .eq("scope_id", scopeId)
                .eq("status", STATUS_ACTIVE)
                .orderByDesc("importance", "last_seen_at")
                .last("LIMIT 100"));

        if (allActive.isEmpty()) return List.of();

        if (queryEmbedding != null) {
            // 向量相似度排序，取 top-3
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

        // 回退：不给内容做规则判断，把最近记忆一并交给维护模型决策。
        return allActive.size() > 10 ? allActive.subList(0, 10) : allActive;
    }

    /**
     * 为文本生成嵌入向量（从 LLM 模型配置获取 API 配置）。
     */
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
        String action = normalizeAction(json.getStr("action"));
        if ("NONE".equals(action)) {
            return false;
        }
        if ("UPDATE".equals(action) || "DELETE".equals(action)) {
            if (dryRun) {
                MemoryCandidateEntity preview = buildCandidate(agent, scopeId, sourceEventId, json, modelId, action);
                if (preview != null && result != null) {
                    result.getCandidates().add(preview);
                }
                return preview != null;
            }
            return executeMemoryAction(agent, scopeId, json, modelId);
        }

        MemoryCandidateEntity candidate = buildCandidate(agent, scopeId, sourceEventId, json, modelId, action);
        if (candidate == null) {
            return false;
        }
        if (result != null) {
            result.getCandidates().add(candidate);
        }
        if (dryRun) {
            return true;
        }
        candidateDao.insert(candidate);
        writeRevision(agent.getId(), null, "create_candidate", null, JsonUtils.toJsonString(candidate),
                "system", modelId, "维护模型生成候选记忆");
        return true;
    }

    private MemoryCandidateEntity buildCandidate(AgentEntity agent, String scopeId, String sourceEventId, JSONObject json,
                                                 String modelId, String action) {
        String canonical = cleanupMemoryText(json.getStr("canonical_text"));
        BigDecimal confidence = score(json.get("confidence"), DEFAULT_SCORE);
        BigDecimal importance = score(json.get("importance"), DEFAULT_SCORE);
        JSONObject payload = new JSONObject();
        payload.set("action", action);
        payload.set("related_memory_id", StringUtils.trimToEmpty(json.getStr("related_memory_id")));
        payload.set("reason", StringUtils.defaultString(json.getStr("reason")));
        payload.set("source", MAINTENANCE_EXTRACTOR_VERSION);
        payload.set("maintenance_llm", modelId);
        payload.set("importance_dimensions", json.get("importance_dimensions"));

        MemoryCandidateEntity candidate = new MemoryCandidateEntity();
        candidate.setCandidateId(uuid());
        candidate.setAgentId(agent.getId());
        candidate.setUserId(agent.getUserId());
        candidate.setScopeType(SCOPE_DEVICE);
        candidate.setScopeId(scopeId);
        candidate.setMemoryType(normalizeMemoryLabel(json.getStr("memory_type"), "长期记忆"));
        candidate.setCategory(normalizeMemoryLabel(json.getStr("category"), "未分类"));
        candidate.setCanonicalText(canonical);
        candidate.setPayload(payload.toString());
        candidate.setConfidence(confidence);
        candidate.setImportance(importance);
        candidate.setSensitivity(normalizeSensitivity(json.getStr("sensitivity")));
        candidate.setStatus(STATUS_PENDING);
        candidate.setSourceEventId(sourceEventId);
        candidate.setExtractorVersion(MAINTENANCE_EXTRACTOR_VERSION);
        candidate.setCreatedAt(new Date());
        candidate.setUpdatedAt(new Date());
        return candidate;
    }

    private boolean executeMemoryAction(AgentEntity agent, String scopeId, JSONObject json, String modelId) {
        String action = normalizeAction(json.getStr("action"));
        if ("NONE".equals(action)) return false;

        String canonical = cleanupMemoryText(json.getStr("canonical_text"));

        if ("DELETE".equals(action)) {
            String relatedId = StringUtils.trimToEmpty(json.getStr("related_memory_id"));
            if (StringUtils.isNotBlank(relatedId)) {
                MemoryFactEntity fact = factDao.selectOne(new QueryWrapper<MemoryFactEntity>()
                        .eq("agent_id", agent.getId()).eq("memory_id", relatedId)
                        .eq("status", STATUS_ACTIVE).last("LIMIT 1"));
                if (fact != null) {
                    String before = JsonUtils.toJsonString(fact);
                    fact.setStatus(STATUS_DELETED);
                    fact.setValidTo(new Date());
                    fact.setUpdatedAt(new Date());
                    factDao.updateById(fact);
                    writeRevision(agent.getId(), fact.getMemoryId(), "delete_from_maintenance", before,
                            JsonUtils.toJsonString(fact), "system", modelId, "维护模型自动遗忘: " + StringUtils.defaultString(json.getStr("reason")));
                    log.info("维护模型删除事实, memoryId={}, text={}", fact.getMemoryId(), fact.getCanonicalText());
                    return true;
                }
            }
            return false;
        }

        String memoryType = normalizeMemoryLabel(json.getStr("memory_type"), "长期记忆");
        String category = normalizeMemoryLabel(json.getStr("category"), "未分类");
        BigDecimal confidence = score(json.get("confidence"), DEFAULT_SCORE);
        BigDecimal importance = score(json.get("importance"), DEFAULT_SCORE);
        String relatedId = StringUtils.trimToEmpty(json.getStr("related_memory_id"));
        String sensitivity = normalizeSensitivity(json.getStr("sensitivity"));
        String tier = normalizeMemoryTier(json.getStr("memory_tier"));
        String dimensionsJson = extractDimensionsJson(json);

        if ("UPDATE".equals(action) && StringUtils.isNotBlank(relatedId)) {
            MemoryFactEntity fact = factDao.selectOne(new QueryWrapper<MemoryFactEntity>()
                    .eq("agent_id", agent.getId()).eq("memory_id", relatedId)
                    .eq("scope_type", SCOPE_DEVICE).eq("scope_id", scopeId)
                    .ne("status", STATUS_DELETED).last("LIMIT 1"));
            if (fact != null) {
                String before = JsonUtils.toJsonString(fact);
                fact.setMemoryType(memoryType);
                fact.setCategory(category);
                fact.setCanonicalText(canonical);
                fact.setConfidence(max(fact.getConfidence(), confidence));
                fact.setImportance(max(fact.getImportance(), importance));
                fact.setSensitivity(sensitivity);
                if (StringUtils.isNotBlank(dimensionsJson)) fact.setImportanceDimensions(dimensionsJson);
                if (StringUtils.isNotBlank(tier)) fact.setMemoryTier(tier);
                fact.setStatus(STATUS_ACTIVE);
                fact.setLastSeenAt(new Date());
                fact.setUpdatedAt(new Date());
                factDao.updateById(fact);
                writeRevision(agent.getId(), fact.getMemoryId(), "update_from_maintenance", before,
                        JsonUtils.toJsonString(fact), "system", modelId, "维护模型更新事实");
                log.info("维护模型更新事实, memoryId={}, text={}", fact.getMemoryId(), canonical);
                return true;
            }
        }

        // ADD: 维护模型已经完成语义去重/更新判断；这里仅避免完全相同的来源哈希重复写入。
        String hash = sha256(agent.getId() + "|" + scopeId + "|" + canonical);
        if (factDao.selectCount(new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agent.getId()).eq("scope_type", SCOPE_DEVICE)
                .eq("scope_id", scopeId).eq("source_hash", hash)
                .ne("status", STATUS_DELETED)) > 0) return false;

        MemoryFactEntity fact = new MemoryFactEntity();
        fact.setMemoryId(uuid());
        fact.setAgentId(agent.getId());
        fact.setUserId(agent.getUserId());
        fact.setMacAddress(scopeId);
        fact.setScopeType(SCOPE_DEVICE);
        fact.setScopeId(scopeId);
        fact.setMemoryType(memoryType);
        fact.setCategory(category);
        fact.setCanonicalText(canonical);
        fact.setPayload(JSONUtil.toJsonStr(Map.of("maintenance_llm", modelId, "source", MAINTENANCE_EXTRACTOR_VERSION,
                "reason", StringUtils.defaultString(json.getStr("reason")))));
        fact.setImportance(importance);
        fact.setConfidence(confidence);
        fact.setSensitivity(sensitivity);
        fact.setConfirmationState("auto");
        fact.setStatus(STATUS_ACTIVE);
        if (StringUtils.isNotBlank(tier)) fact.setMemoryTier(tier);
        if (StringUtils.isNotBlank(dimensionsJson)) fact.setImportanceDimensions(dimensionsJson);
        // 异步生成嵌入向量（不阻塞事实写入）
        float[] embedding = generateEmbedding(canonical, modelId);
        if (embedding != null) {
            fact.setEmbeddingJson(MemorySimilarityUtils.encodeEmbedding(embedding));
        }
        fact.setValidFrom(new Date());
        fact.setFirstSeenAt(new Date());
        fact.setLastSeenAt(new Date());
        fact.setSourceEventId(null);
        fact.setSourceHash(hash);
        fact.setExtractorVersion(MAINTENANCE_EXTRACTOR_VERSION);
        fact.setCreatedAt(new Date());
        fact.setUpdatedAt(new Date());
        factDao.insert(fact);
        writeRevision(agent.getId(), fact.getMemoryId(), "create_from_maintenance", null,
                JsonUtils.toJsonString(fact), "system", modelId, "维护模型创建事实");
        log.info("维护模型创建事实, memoryId={}, text={}", fact.getMemoryId(), canonical);
        return true;
    }

    private String normalizeAction(String value) {
        String action = StringUtils.defaultIfBlank(value, "NONE").trim().toUpperCase();
        return List.of("ADD", "UPDATE", "DELETE", "NONE").contains(action) ? action : "NONE";
    }

    /**
     * 提取维度 JSON 字符串用于存储。
     */
    private String extractDimensionsJson(JSONObject json) {
        Object dimsRaw = json.get("importance_dimensions");
        if (dimsRaw == null) return null;
        try {
            return dimsRaw instanceof String s ? s : JSONUtil.toJsonStr(dimsRaw);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public MemoryQueryVO query(MemoryQueryDTO dto) {
        AgentScope scope = resolveScope(dto.getAgentId(), dto.getMacAddress());
        MemoryQueryVO vo = new MemoryQueryVO();
        if (scope.agent == null) {
            vo.setContext("");
            return vo;
        }
        int limit = dto.getLimit() == null ? 8 : Math.max(1, Math.min(dto.getLimit(), 20));

        QueryWrapper<MemoryProfileEntity> profileWrapper = new QueryWrapper<>();
        profileWrapper.eq("agent_id", scope.agent.getId()).eq("scope_type", SCOPE_DEVICE)
                .and(w -> w.eq("scope_id", scope.scopeId).or().eq("scope_id", scope.agent.getId()))
                .orderByDesc("updated_at")
                .last("LIMIT 1");
        MemoryProfileEntity profile = profileDao.selectOne(profileWrapper);
        if (profile != null) {
            vo.setProfile(StringUtils.defaultIfBlank(profile.getProfileSummary(), profile.getProfileJson()));
        }

        String keyword = compactQuery(dto.getQuery());
        List<MemoryFactEntity> facts;

        // 尝试向量语义搜索（如果有查询且有嵌入配置）
        float[] queryEmbedding = null;
        if (StringUtils.isNotBlank(keyword)) {
            String memModelId = getMaintenanceLlmModelId(scope.agent);
            if (StringUtils.isNotBlank(memModelId)) {
                queryEmbedding = generateEmbedding(dto.getQuery(), memModelId);
            }
        }

        if (queryEmbedding != null) {
            // 向量语义搜索：检索所有活跃事实，按相似度重新排序
            List<MemoryFactEntity> allActive = factDao.selectList(new QueryWrapper<MemoryFactEntity>()
                    .eq("agent_id", scope.agent.getId())
                    .eq("scope_type", SCOPE_DEVICE)
                    .and(w -> w.eq("scope_id", scope.scopeId).or().eq("scope_id", scope.agent.getId()))
                    .eq("status", STATUS_ACTIVE)
                    .orderByDesc("importance", "last_seen_at")
                    .last("LIMIT 200"));
            List<Map.Entry<MemoryFactEntity, Double>> scored = new ArrayList<>();
            for (MemoryFactEntity fact : allActive) {
                float[] factEmb = MemorySimilarityUtils.parseEmbedding(fact.getEmbeddingJson());
                if (factEmb != null) {
                    double sim = MemorySimilarityUtils.cosineSimilarity(queryEmbedding, factEmb);
                    double combined = 0.6 * sim + 0.4 * (fact.getImportance() != null
                            ? fact.getImportance().doubleValue() : 0.5);
                    scored.add(Map.entry(fact, combined));
                } else {
                    // 无嵌入的事实使用关键词回退
                    if (StringUtils.containsIgnoreCase(fact.getCanonicalText(), keyword)
                            || StringUtils.containsIgnoreCase(fact.getCategory(), keyword)) {
                        scored.add(Map.entry(fact, 0.3 + (fact.getImportance() != null
                                ? fact.getImportance().doubleValue() : 0.5) * 0.4));
                    }
                }
            }
            scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            facts = new ArrayList<>();
            for (int i = 0; i < Math.min(limit, scored.size()); i++) {
                facts.add(scored.get(i).getKey());
            }
        } else {
            // 关键词 LIKE 搜索（原有逻辑）
            QueryWrapper<MemoryFactEntity> factWrapper = new QueryWrapper<>();
            factWrapper.eq("agent_id", scope.agent.getId())
                    .eq("scope_type", SCOPE_DEVICE)
                    .and(w -> w.eq("scope_id", scope.scopeId).or().eq("scope_id", scope.agent.getId()))
                    .eq("status", STATUS_ACTIVE);
            if (StringUtils.isNotBlank(keyword)) {
                factWrapper.and(w -> w.like("canonical_text", keyword).or().like("category", keyword));
            }
            factWrapper.orderByDesc("importance", "last_seen_at").last("LIMIT " + limit);
            facts = factDao.selectList(factWrapper);
            if (facts.isEmpty() && StringUtils.isNotBlank(keyword)) {
                QueryWrapper<MemoryFactEntity> fallback = new QueryWrapper<>();
                fallback.eq("agent_id", scope.agent.getId())
                        .eq("scope_type", SCOPE_DEVICE)
                        .and(w -> w.eq("scope_id", scope.scopeId).or().eq("scope_id", scope.agent.getId()))
                        .eq("status", STATUS_ACTIVE)
                        .orderByDesc("importance", "last_seen_at")
                        .last("LIMIT " + limit);
                facts = factDao.selectList(fallback);
            }
        }
        vo.setFacts(facts);

        QueryWrapper<MemoryToolBehaviorEntity> toolWrapper = new QueryWrapper<>();
        toolWrapper.eq("agent_id", scope.agent.getId())
                .eq("scope_type", SCOPE_DEVICE)
                .and(w -> w.eq("scope_id", scope.scopeId).or().eq("scope_id", scope.agent.getId()))
                .orderByDesc("updated_at")
                .last("LIMIT 5");
        vo.setToolBehaviors(toolBehaviorDao.selectList(toolWrapper));

        QueryWrapper<MemoryForgetTombstoneEntity> tombstoneWrapper = new QueryWrapper<>();
        tombstoneWrapper.eq("agent_id", scope.agent.getId())
                .eq("scope_type", SCOPE_DEVICE)
                .and(w -> w.eq("scope_id", scope.scopeId).or().eq("scope_id", scope.agent.getId()))
                .eq("status", STATUS_ACTIVE)
                .orderByDesc("created_at")
                .last("LIMIT 10");
        vo.setBlockedPatterns(tombstoneDao.selectList(tombstoneWrapper).stream()
                .map(MemoryForgetTombstoneEntity::getPatternText)
                .toList());
        vo.setContext(buildContext(vo));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remember(MemoryRememberDTO dto, String actorType, String actorId) {
        AgentScope scope = resolveScope(dto.getAgentId(), dto.getMacAddress());
        if (scope.agent == null || StringUtils.isBlank(dto.getText())) {
            return;
        }
        applyMaintenanceInstruction(scope.agent, scope.scopeId, dto.getText(), actorType, actorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forget(MemoryForgetDTO dto, String actorType, String actorId) {
        AgentScope scope = resolveScope(dto.getAgentId(), dto.getMacAddress());
        if (scope.agent == null || StringUtils.isBlank(dto.getText())) {
            return;
        }
        applyMaintenanceInstruction(scope.agent, scope.scopeId, dto.getText(), actorType, actorId);
    }

    @Override
    public MemoryDashboardVO dashboard(String agentId) {
        assertAgentOwner(agentId);
        MemoryDashboardVO vo = new MemoryDashboardVO();
        QueryWrapper<MemoryFactEntity> factWrapper = new QueryWrapper<>();
        factWrapper.eq("agent_id", agentId).ne("status", STATUS_DELETED).orderByDesc("updated_at").last("LIMIT 200");
        vo.setFacts(factDao.selectList(factWrapper));

        QueryWrapper<MemoryCandidateEntity> candidateWrapper = new QueryWrapper<>();
        candidateWrapper.eq("agent_id", agentId).orderByDesc("updated_at").last("LIMIT 100");
        vo.setCandidates(candidateDao.selectList(candidateWrapper));

        QueryWrapper<MemoryToolBehaviorEntity> toolWrapper = new QueryWrapper<>();
        toolWrapper.eq("agent_id", agentId).orderByDesc("updated_at").last("LIMIT 100");
        vo.setToolBehaviors(toolBehaviorDao.selectList(toolWrapper));

        QueryWrapper<MemoryForgetTombstoneEntity> tombstoneWrapper = new QueryWrapper<>();
        tombstoneWrapper.eq("agent_id", agentId).orderByDesc("updated_at").last("LIMIT 100");
        vo.setTombstones(tombstoneDao.selectList(tombstoneWrapper));

        QueryWrapper<MemoryRevisionEntity> revisionWrapper = new QueryWrapper<>();
        revisionWrapper.eq("agent_id", agentId).orderByDesc("created_at").last("LIMIT 100");
        vo.setRevisions(revisionDao.selectList(revisionWrapper));

        QueryWrapper<MemoryProfileEntity> profileWrapper = new QueryWrapper<>();
        profileWrapper.eq("agent_id", agentId).orderByDesc("updated_at").last("LIMIT 1");
        vo.setProfile(profileDao.selectOne(profileWrapper));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createFact(String agentId, MemoryFactDTO dto, String actorType, String actorId) {
        AgentEntity agent = assertAgentOwner(agentId);
        AgentScope scope = new AgentScope(agent, agentId);
        return createFactInternal(scope, dto.getCanonicalText(), StringUtils.defaultIfBlank(dto.getCategory(), "manual"),
                StringUtils.defaultIfBlank(dto.getMemoryType(), "profile"), null, actorType, actorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateFact(String agentId, String memoryId, MemoryFactDTO dto, String actorType, String actorId) {
        assertAgentOwner(agentId);
        MemoryFactEntity fact = getFact(agentId, memoryId);
        String before = JsonUtils.toJsonString(fact);
        if (StringUtils.isNotBlank(dto.getCanonicalText())) {
            fact.setCanonicalText(dto.getCanonicalText().trim());
        }
        if (StringUtils.isNotBlank(dto.getMemoryType())) {
            fact.setMemoryType(dto.getMemoryType());
        }
        if (StringUtils.isNotBlank(dto.getCategory())) {
            fact.setCategory(dto.getCategory());
        }
        if (dto.getImportance() != null) {
            fact.setImportance(dto.getImportance());
        }
        if (dto.getConfidence() != null) {
            fact.setConfidence(dto.getConfidence());
        }
        if (StringUtils.isNotBlank(dto.getSensitivity())) {
            fact.setSensitivity(dto.getSensitivity());
        }
        if (dto.getPayload() != null) {
            fact.setPayload(dto.getPayload());
        }
        fact.setLastSeenAt(new Date());
        fact.setUpdatedAt(new Date());
        factDao.updateById(fact);
        writeRevision(agentId, memoryId, "update", before, JsonUtils.toJsonString(fact), actorType, actorId, "更新记忆");
    }

    @Override
    public void archiveFact(String agentId, String memoryId, String actorType, String actorId) {
        updateStatus(agentId, memoryId, STATUS_ARCHIVED, actorType, actorId, "归档记忆");
    }

    @Override
    public void deleteFact(String agentId, String memoryId, String actorType, String actorId) {
        updateStatus(agentId, memoryId, STATUS_DELETED, actorType, actorId, "删除记忆");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmCandidate(String agentId, String candidateId, String actorType, String actorId) {
        assertAgentOwner(agentId);
        MemoryCandidateEntity candidate = getCandidate(agentId, candidateId);
        if (!STATUS_PENDING.equals(candidate.getStatus())) {
            return;
        }
        AgentEntity agent = agentDao.selectById(agentId);
        AgentScope scope = new AgentScope(agent, candidate.getScopeId());
        String memoryId = applyCandidateToFact(scope, candidate, actorType, actorId);
        candidate.setStatus("confirmed");
        candidate.setUpdatedAt(new Date());
        candidateDao.updateById(candidate);
        writeRevision(agentId, memoryId, "confirm_candidate", JsonUtils.toJsonString(candidate), null,
                actorType, actorId, "候选记忆确认");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectCandidate(String agentId, String candidateId, String actorType, String actorId) {
        assertAgentOwner(agentId);
        MemoryCandidateEntity candidate = getCandidate(agentId, candidateId);
        candidate.setStatus(STATUS_REJECTED);
        candidate.setUpdatedAt(new Date());
        candidateDao.updateById(candidate);
        writeRevision(agentId, null, "reject_candidate", JsonUtils.toJsonString(candidate), null,
                actorType, actorId, "候选记忆拒绝");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemoryRebuildResultVO clearMemories(String agentId, String actorType, String actorId) {
        AgentEntity agent = assertAgentOwner(agentId);
        MemoryRebuildResultVO result = new MemoryRebuildResultVO();
        result.setMode("clear");

        List<MemoryFactEntity> facts = factDao.selectList(new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agent.getId()).ne("status", STATUS_DELETED));
        for (MemoryFactEntity fact : facts) {
            String before = JsonUtils.toJsonString(fact);
            fact.setStatus(STATUS_DELETED);
            fact.setValidTo(new Date());
            fact.setUpdatedAt(new Date());
            factDao.updateById(fact);
            writeRevision(agent.getId(), fact.getMemoryId(), "clear_delete", before, JsonUtils.toJsonString(fact),
                    actorType, actorId, "清空记忆");
        }
        result.setFactsCleared(facts.size());

        List<MemoryCandidateEntity> candidates = candidateDao.selectList(new QueryWrapper<MemoryCandidateEntity>()
                .eq("agent_id", agent.getId()).eq("status", STATUS_PENDING));
        for (MemoryCandidateEntity candidate : candidates) {
            candidate.setStatus(STATUS_REJECTED);
            candidate.setUpdatedAt(new Date());
            candidateDao.updateById(candidate);
        }
        result.setCandidatesCleared(candidates.size());

        List<MemoryForgetTombstoneEntity> tombstones = tombstoneDao.selectList(new QueryWrapper<MemoryForgetTombstoneEntity>()
                .eq("agent_id", agent.getId()).eq("status", STATUS_ACTIVE));
        for (MemoryForgetTombstoneEntity tombstone : tombstones) {
            tombstone.setStatus(STATUS_INACTIVE);
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

        if (!dryRun && "clear_then_rebuild".equalsIgnoreCase(mode)) {
            MemoryRebuildResultVO cleared = clearMemories(agentId, actorType, actorId);
            result.setFactsCleared(cleared.getFactsCleared());
            result.setCandidatesCleared(cleared.getCandidatesCleared());
            result.setTombstonesCleared(cleared.getTombstonesCleared());
        }

        List<AgentChatSessionDTO> sessions = recentSessions(agent.getId(), limit);
        result.setSessionsRead(sessions.size());
        for (int i = sessions.size() - 1; i >= 0; i--) {
            AgentChatSessionDTO session = sessions.get(i);
            result.getSessionIds().add(session.getSessionId());
            List<AgentChatHistoryDTO> history = agentChatHistoryService.getChatHistoryBySessionId(agent.getId(), session.getSessionId());
            String conversation = buildConversationForMaintenance(history);
            if (StringUtils.isBlank(conversation)) {
                continue;
            }
            String scopeId = firstScopeId(history, agent.getId());
            rebuildConversation(agent, scopeId, conversation, dryRun, result);
        }
        if (!dryRun) {
            writeRevision(agent.getId(), null, "rebuild_memory", null, JsonUtils.toJsonString(result),
                    actorType, actorId, "从聊天记录重建记忆");
        }
        return result;
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
        return StringUtils.abbreviate(builder.toString(), SESSION_MAX_CHARS);
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
        String extractionOutput = llmService.generateSummary(extractionPrompt, maintenanceModelId);
        JSONArray facts = parseExtractionResult(extractionOutput);
        if (facts == null || facts.isEmpty()) {
            return;
        }
        for (Object item : facts) {
            JSONObject factJson = toJsonObject(item);
            if (factJson == null) continue;
            String factText = factJson.getStr("text", factJson.getStr("canonical_text"));

            List<MemoryFactEntity> similarFacts = findSimilarFacts(agent.getId(), scopeId,
                    factText + "\n" + conversation, maintenanceModelId);
            String decisionOutput = llmService.generateSummary(buildDecisionPrompt(factJson, similarFacts), maintenanceModelId);
            JSONObject decision = parseDecisionResult(decisionOutput);
            if (decision == null) continue;
            mergeExtractionIntoDecision(factJson, decision);
            String action = normalizeAction(decision.getStr("action"));
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

    private String applyCandidateToFact(AgentScope scope, MemoryCandidateEntity candidate, String actorType, String actorId) {
        JSONObject payload = parsePayload(candidate.getPayload());
        String action = normalizeCandidateAction(payload.getStr("action"));
        String relatedMemoryId = payload.getStr("related_memory_id");
        MemoryFactEntity relatedFact = findRelatedFact(scope.agent.getId(), scope.scopeId, relatedMemoryId,
                candidate.getCanonicalText(), candidate.getMemoryType(), candidate.getCategory());
        if ("UPDATE".equals(action) && relatedFact != null) {
            String before = JsonUtils.toJsonString(relatedFact);
            relatedFact.setMemoryType(normalizeMemoryLabel(candidate.getMemoryType(), "长期记忆"));
            relatedFact.setCategory(normalizeMemoryLabel(candidate.getCategory(), "未分类"));
            relatedFact.setCanonicalText(cleanupMemoryText(candidate.getCanonicalText()));
            relatedFact.setConfidence(max(relatedFact.getConfidence(), candidate.getConfidence()));
            relatedFact.setImportance(max(relatedFact.getImportance(), candidate.getImportance()));
            relatedFact.setSensitivity(normalizeSensitivity(candidate.getSensitivity()));
            relatedFact.setStatus(STATUS_ACTIVE);
            relatedFact.setLastSeenAt(new Date());
            relatedFact.setUpdatedAt(new Date());
            relatedFact.setPayload(mergeFactPayload(relatedFact.getPayload(), candidate.getPayload()));
            factDao.updateById(relatedFact);
            writeRevision(scope.agent.getId(), relatedFact.getMemoryId(), "update_from_candidate", before,
                    JsonUtils.toJsonString(relatedFact), actorType, actorId, "候选记忆更新已有事实");
            return relatedFact.getMemoryId();
        }
        return createFactInternal(scope, candidate.getCanonicalText(),
                normalizeMemoryLabel(candidate.getCategory(), "未分类"),
                normalizeMemoryLabel(candidate.getMemoryType(), "长期记忆"), candidate.getSourceEventId(), actorType, actorId);
    }

    private String createFactInternal(AgentScope scope, String text, String category, String memoryType,
                                      String sourceEventId, String actorType, String actorId) {
        String canonical = cleanupMemoryText(text);
        if (StringUtils.isBlank(canonical)) {
            return null;
        }
        String hash = sha256(scope.agent.getId() + "|" + scope.scopeId + "|" + canonical);
        QueryWrapper<MemoryFactEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("agent_id", scope.agent.getId())
                .eq("scope_type", SCOPE_DEVICE)
                .eq("scope_id", scope.scopeId)
                .eq("source_hash", hash)
                .ne("status", STATUS_DELETED)
                .last("LIMIT 1");
        MemoryFactEntity existing = factDao.selectOne(wrapper);
        if (existing != null) {
            refreshFactEvidence(existing, DEFAULT_SCORE, actorType, actorId, "重复证据刷新");
            return existing.getMemoryId();
        }
        MemoryFactEntity fact = new MemoryFactEntity();
        fact.setMemoryId(uuid());
        fact.setAgentId(scope.agent.getId());
        fact.setUserId(scope.agent.getUserId());
        fact.setMacAddress(scope.scopeId);
        fact.setScopeType(SCOPE_DEVICE);
        fact.setScopeId(scope.scopeId);
        fact.setMemoryType(normalizeMemoryLabel(memoryType, "长期记忆"));
        fact.setCategory(normalizeMemoryLabel(category, "未分类"));
        fact.setCanonicalText(canonical);
        fact.setPayload("{}");
        fact.setImportance(DEFAULT_SCORE);
        fact.setConfidence(DEFAULT_SCORE);
        fact.setSensitivity("normal");
        fact.setConfirmationState("auto");
        fact.setStatus(STATUS_ACTIVE);
        fact.setValidFrom(new Date());
        fact.setFirstSeenAt(new Date());
        fact.setLastSeenAt(new Date());
        fact.setSourceEventId(sourceEventId);
        fact.setSourceHash(hash);
        fact.setExtractorVersion("explicit-v1");
        fact.setCreatedAt(new Date());
        fact.setUpdatedAt(new Date());
        factDao.insert(fact);
        writeRevision(scope.agent.getId(), fact.getMemoryId(), "create", null, JsonUtils.toJsonString(fact),
                actorType, actorId, "创建记忆");
        return fact.getMemoryId();
    }

    private boolean applyMaintenanceInstruction(AgentEntity agent, String scopeId, String instruction,
                                                String actorType, String actorId) {
        String maintenanceModelId = getMaintenanceLlmModelId(agent);
        if (StringUtils.isBlank(instruction)) {
            return false;
        }
        if (StringUtils.isBlank(maintenanceModelId)) {
            throw new IllegalStateException("未配置记忆维护模型");
        }
        String prompt = PowerMemPrompts.COMMAND_DECISION_PROMPT
                .replace("{instruction}", instruction)
                .replace("{existing_memories}", buildMaintenanceMemoryContext(agent.getId(), scopeId));
        String output = llmService.generateSummary(prompt, maintenanceModelId);
        JSONObject decision = parseDecisionResult(output);
        if (decision == null) {
            throw new IllegalStateException("记忆维护模型未返回有效决策");
        }
        boolean changed = applyMaintenanceDecision(agent, scopeId, null, decision, maintenanceModelId, false, null);
        writeRevision(agent.getId(), null, "maintenance_instruction", instruction, JsonUtils.toJsonString(decision),
                actorType, actorId, "维护模型处理管理端指令");
        return changed;
    }

    private boolean shouldRunMaintenanceExtraction(MemoryEventDTO dto, String content) {
        return dto != null
                && Integer.valueOf(1).equals(dto.getChatType())
                && StringUtils.isNotBlank(content);
    }

    private void runAfterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeRunMaintenanceTask(task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeRunMaintenanceTask(task);
            }
        });
    }

    private void safeRunMaintenanceTask(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.warn("长期记忆维护模型抽取失败: {}", e.getMessage());
        }
    }

    /**
     * PowerMem 两阶段管线：Stage 1 提取事实 → Stage 2 语义搜索 + 决策执行。
     * <p>当未配置 embedding 模型时，回退到旧版单阶段维护管线。</p>
     */
    private void createCandidatesWithMaintenanceModel(AgentEntity agent, String scopeId, String sourceEventId, String content) {
        String maintenanceModelId = getMaintenanceLlmModelId(agent);
        if (StringUtils.isBlank(maintenanceModelId)) {
            return;
        }
        String promptInput = StringUtils.abbreviate(content, MAX_MAINTENANCE_INPUT_CHARS);

        // Stage 1: 提取事实（不看已有记忆）
        String extractionPrompt = PowerMemPrompts.EXTRACTION_PROMPT.replace("{conversation}", promptInput);
        String extractionOutput = llmService.generateSummary(extractionPrompt, maintenanceModelId);
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
            JSONObject factJson = toJsonObject(item);
            if (factJson == null) continue;

            String factText = factJson.getStr("text", factJson.getStr("canonical_text"));

            // Stage 2a: 语义搜索 — 找到 top-3 相似已有记忆
            List<MemoryFactEntity> similarFacts = findSimilarFacts(agent.getId(), scopeId,
                    factText + "\n" + content, maintenanceModelId);

            // Stage 2b: 决策 — LLM 判断 ADD/UPDATE/DELETE/NONE
            String decisionPrompt = buildDecisionPrompt(factJson, similarFacts);
            String decisionOutput = llmService.generateSummary(decisionPrompt, maintenanceModelId);
            if (StringUtils.isBlank(decisionOutput)) {
                continue;
            }
            JSONObject decision = parseDecisionResult(decisionOutput);
            if (decision == null) continue;

            // 合并 Stage 1 提取信息到决策结果
            mergeExtractionIntoDecision(factJson, decision);

            if (applyMaintenanceDecision(agent, scopeId, sourceEventId, decision, maintenanceModelId, false, null)) {
                saved++;
            }
        }
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
            // buildTypedConfig reads the latest params from ai_model_config_param table,
            // avoiding stale configJson from Redis cache or the DB config_json column
            JSONObject config = modelRuntimeAssembler.buildTypedConfig(memoryConfig);
            return StringUtils.defaultIfBlank(config.getStr(MAINTENANCE_LLM_FIELD),
                    config.getStr("memory_maintenance_llm"));
        } catch (Exception e) {
            log.warn("读取记忆维护模型配置失败，agentId: {}, memModelId: {}, error: {}",
                    agent.getId(), agent.getMemModelId(), e.getMessage());
            return null;
        }
    }

    private JSONArray parseMaintenanceMemories(String output) {
        String jsonText = extractJsonObject(output);
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

    private String extractJsonObject(String output) {
        String text = StringUtils.trimToEmpty(output);
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return text.substring(start, end + 1);
    }

    private JSONObject toJsonObject(Object item) {
        if (item instanceof JSONObject jsonObject) {
            return jsonObject;
        }
        if (item instanceof Map<?, ?> map) {
            return JSONUtil.parseObj(map);
        }
        if (item instanceof String text && StringUtils.isNotBlank(text)) {
            return JSONUtil.parseObj(text);
        }
        return null;
    }

    private String buildMaintenanceMemoryContext(String agentId, String scopeId) {
        List<MemoryFactEntity> facts = factDao.selectList(new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agentId)
                .eq("scope_type", SCOPE_DEVICE)
                .eq("scope_id", scopeId)
                .eq("status", STATUS_ACTIVE)
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

    private JSONObject parsePayload(String payload) {
        if (StringUtils.isBlank(payload)) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(payload);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private String mergeFactPayload(String factPayload, String candidatePayload) {
        JSONObject merged = parsePayload(factPayload);
        JSONObject candidate = parsePayload(candidatePayload);
        merged.set("last_candidate_action", candidate.getStr("action"));
        merged.set("last_candidate_reason", candidate.getStr("reason"));
        merged.set("maintenance_source", candidate.getStr("source"));
        return merged.toString();
    }

    private void refreshFactEvidence(MemoryFactEntity fact, BigDecimal confidence, String actorType, String actorId, String reason) {
        fact.setLastSeenAt(new Date());
        fact.setConfidence(max(fact.getConfidence(), confidence == null ? DEFAULT_SCORE : confidence));
        fact.setStatus(STATUS_ACTIVE);
        fact.setUpdatedAt(new Date());
        factDao.updateById(fact);
        writeRevision(fact.getAgentId(), fact.getMemoryId(), "refresh", null,
                JsonUtils.toJsonString(fact), actorType, actorId, reason);
    }

    private MemoryFactEntity findRelatedFact(String agentId, String scopeId, String relatedMemoryId,
                                             String canonical, String memoryType, String category) {
        if (StringUtils.isBlank(relatedMemoryId)) {
            return null;
        }
        return factDao.selectOne(new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agentId)
                .eq("scope_type", SCOPE_DEVICE)
                .eq("scope_id", scopeId)
                .eq("memory_id", relatedMemoryId)
                .ne("status", STATUS_DELETED)
                .last("LIMIT 1"));
    }

    private String normalizeCandidateAction(String value) {
        String action = StringUtils.defaultIfBlank(value, "ADD").trim().toUpperCase();
        if (List.of("ADD", "UPDATE", "IGNORE").contains(action)) {
            return action;
        }
        return "ADD";
    }

    private String normalizeMemoryLabel(String value, String defaultValue) {
        String label = cleanupMemoryText(value);
        if (StringUtils.isBlank(label)) {
            return defaultValue;
        }
        return StringUtils.abbreviate(label, 40);
    }

    private String normalizeSensitivity(String value) {
        String sensitivity = StringUtils.defaultIfBlank(value, "normal").trim();
        if (List.of("normal", "personal", "sensitive").contains(sensitivity)) {
            return sensitivity;
        }
        return "normal";
    }

    private String normalizeMemoryTier(String value) {
        String tier = StringUtils.trimToEmpty(value).toLowerCase();
        return List.of("working", "short_term", "long_term").contains(tier) ? tier : "";
    }

    private BigDecimal score(Object value, BigDecimal defaultValue) {
        BigDecimal score = defaultValue;
        if (value instanceof Number number) {
            score = new BigDecimal(number.toString());
        } else if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
            try {
                score = new BigDecimal(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                score = defaultValue;
            }
        }
        if (score.compareTo(MIN_SCORE) < 0) {
            return MIN_SCORE;
        }
        if (score.compareTo(MAX_SCORE) > 0) {
            return MAX_SCORE;
        }
        return score;
    }

    private void updateStatus(String agentId, String memoryId, String status, String actorType, String actorId, String reason) {
        assertAgentOwner(agentId);
        MemoryFactEntity fact = getFact(agentId, memoryId);
        String before = JsonUtils.toJsonString(fact);
        fact.setStatus(status);
        fact.setUpdatedAt(new Date());
        if (STATUS_ARCHIVED.equals(status)) {
            fact.setArchivedAt(new Date());
        }
        if (STATUS_DELETED.equals(status)) {
            fact.setValidTo(new Date());
        }
        factDao.updateById(fact);
        writeRevision(agentId, memoryId, status, before, JsonUtils.toJsonString(fact), actorType, actorId, reason);
    }

    private MemoryFactEntity getFact(String agentId, String memoryId) {
        MemoryFactEntity fact = factDao.selectOne(new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agentId).eq("memory_id", memoryId).last("LIMIT 1"));
        if (fact == null) {
            throw new IllegalArgumentException("记忆不存在");
        }
        return fact;
    }

    private MemoryCandidateEntity getCandidate(String agentId, String candidateId) {
        MemoryCandidateEntity candidate = candidateDao.selectOne(new QueryWrapper<MemoryCandidateEntity>()
                .eq("agent_id", agentId).eq("candidate_id", candidateId).last("LIMIT 1"));
        if (candidate == null) {
            throw new IllegalArgumentException("候选记忆不存在");
        }
        return candidate;
    }

    private void writeRevision(String agentId, String memoryId, String action, String before, String after,
                               String actorType, String actorId, String reason) {
        MemoryRevisionEntity revision = new MemoryRevisionEntity();
        revision.setRevisionId(uuid());
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

    private AgentScope resolveScope(String agentId, String macAddress) {
        AgentEntity agent = null;
        String scopeId = StringUtils.defaultIfBlank(macAddress, agentId);
        if (StringUtils.isNotBlank(macAddress)) {
            DeviceEntity device = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("mac_address", macAddress).last("LIMIT 1"));
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

    private String buildContext(MemoryQueryVO vo) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.isNotBlank(vo.getProfile())) {
            builder.append("【用户画像】\n").append(vo.getProfile()).append("\n\n");
        }
        if (!vo.getFacts().isEmpty()) {
            builder.append("【长期记忆】\n");
            for (MemoryFactEntity fact : vo.getFacts()) {
                builder.append("- [").append(fact.getCategory()).append("] ")
                        .append(fact.getCanonicalText()).append("\n");
            }
            builder.append("\n");
        }
        if (!vo.getToolBehaviors().isEmpty()) {
            builder.append("【工具使用经验】\n");
            for (MemoryToolBehaviorEntity tool : vo.getToolBehaviors()) {
                builder.append("- ").append(tool.getToolName()).append(": 成功 ")
                        .append(tool.getSuccessCount()).append(" 次，失败 ")
                        .append(tool.getFailureCount()).append(" 次\n");
            }
            builder.append("\n");
        }
        if (!vo.getBlockedPatterns().isEmpty()) {
            builder.append("【忘记名单】\n");
            for (String pattern : vo.getBlockedPatterns()) {
                builder.append("- 不要再主动记住或使用：").append(pattern).append("\n");
            }
        }
        return builder.toString().trim();
    }

    private String normalizeContent(String text, Map<String, Object> json) {
        if (StringUtils.isNotBlank(text)) {
            return text.trim();
        }
        if (json == null) {
            return "";
        }
        Object content = json.get("content");
        return content == null ? JsonUtils.toJsonString(json) : String.valueOf(content);
    }

    private String cleanupMemoryText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("^[：:，,。\\s]+", "").replaceAll("[。！!\\s]+$", "").trim();
    }

    private String compactQuery(String query) {
        if (query == null) {
            return "";
        }
        String compact = query.replaceAll("[\\p{Punct}\\s，。！？、；：]+", "");
        if (compact.length() > 24) {
            compact = compact.substring(0, 24);
        }
        return compact;
    }

    private BigDecimal max(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        return left.compareTo(right) >= 0 ? left : right;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record AgentScope(AgentEntity agent, String scopeId) {
    }
}
