package xiaozhi.modules.memory.service.impl;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
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
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
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
import xiaozhi.modules.memory.entity.MemoryCandidateEntity;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.entity.MemoryForgetTombstoneEntity;
import xiaozhi.modules.memory.entity.MemoryProfileEntity;
import xiaozhi.modules.memory.entity.MemoryRevisionEntity;
import xiaozhi.modules.memory.entity.MemorySourceEventEntity;
import xiaozhi.modules.memory.entity.MemoryToolBehaviorEntity;
import xiaozhi.modules.memory.service.MemoryService;
import xiaozhi.modules.memory.vo.MemoryDashboardVO;
import xiaozhi.modules.memory.vo.MemoryQueryVO;
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
    private static final String SCOPE_DEVICE = "device";
    private static final BigDecimal DEFAULT_SCORE = new BigDecimal("0.700");
    private static final BigDecimal MIN_SCORE = new BigDecimal("0.000");
    private static final BigDecimal MAX_SCORE = new BigDecimal("1.000");
    private static final int MAX_MAINTENANCE_INPUT_CHARS = 2000;
    private static final String MAINTENANCE_LLM_FIELD = "maintenance_llm";
    private static final String MAINTENANCE_EXTRACTOR_VERSION = "maintenance-llm-v3-powerMem";
    private static final String MAINTENANCE_PROMPT = """
            你是小智服务器的长期记忆维护助手。你的任务不是保存聊天记录，而是像 PowerMem 一样维护一份干净、可复用、可自进化的长期事实库。

            已有长期记忆（含 id，用于 UPDATE/DELETE 引用）：
            {history_memory}

            本次完整对话：
            {conversation}

            维护规则：
            1. 从完整对话中抽取所有有长期价值的稳定事实，而不是逐条处理单次发言。
            2. 对每条提取的事实，判断与已有记忆的关系：
               - 全新信息 → action=ADD
               - 修正/补充已有事实 → action=UPDATE，必须填写正确的 related_memory_id
               - 与已有记忆矛盾且新信息更可信 → action=UPDATE（覆盖旧事实）
               - 涉及"忘记""不再""不需要"等否定意图的内容 → action=DELETE，填写 related_memory_id
               - 与已有记忆完全重复 → action=NONE（跳过）
            3. 跳过噪声：天气、时间、寒暄、一次性请求、工具结果、设备控制。
            4. 不保存高敏感内容：密码、Token、API Key、身份证、银行卡、精确住址。
            5. canonical_text 是一句完整中文事实，不带引号、口头禅、对话语气或"用户说/他说"等前缀。
            6. category 自行归类，能复用已有分类就复用，不能复用时生成稳定简短的中文分类。
            7. confidence(0-1) 表示事实可信度，importance(0-1) 表示长期价值。普通偏好 0.5-0.7，身份/关系/长期项目 0.7-0.9。
            8. 没有可维护内容时返回 {"memories":[]}。
            9. 只返回 JSON，不要解释，不要 Markdown。

            JSON 格式：
            {
              "memories": [
                {
                  "action": "ADD|UPDATE|DELETE|NONE",
                  "related_memory_id": "仅 UPDATE/DELETE 时填写已有记忆 ID，否则为空",
                  "memory_type": "稳定、简短的记忆类型",
                  "category": "稳定、简短的中文分类",
                  "canonical_text": "可直接写入记忆的中文事实",
                  "confidence": 0.0,
                  "importance": 0.0,
                  "sensitivity": "normal|personal|sensitive",
                  "reason": "决策原因，少于30字"
                }
              ]
            }
            """;
    private static final int SESSION_MAX_CHARS = 8000;
    private static final double DECAY_RATE = 0.01;
    private static final double INITIAL_RETENTION = 0.9;
    private static final double REINFORCEMENT_FACTOR = 0.1;
    private static final double FORGET_THRESHOLD = 0.15;

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
    private final ModelConfigService modelConfigService;
    private final ModelRuntimeAssembler modelRuntimeAssembler;

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
        String rememberText = extractRemember(content);
        if (StringUtils.isNotBlank(rememberText)) {
            createFactInternal(scope, rememberText, "explicit", "preference", event.getEventId(), "system", null);
            return;
        }
        String forgetText = extractForget(content);
        if (StringUtils.isNotBlank(forgetText)) {
            forgetInternal(scope, forgetText, "用户显式要求忘记", event.getEventId(), "system", null);
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
        String historyContext = buildMaintenanceMemoryContext(agentId, agentId);
        String output = llmService.generateSummaryWithHistory(conversation, historyContext, MAINTENANCE_PROMPT, maintenanceModelId);
        if (StringUtils.isBlank(output) || output.contains("不可用") || output.contains("失败")) {
            log.warn("processSessionMemories: 维护模型返回不可用, agentId={}", agentId);
            return 0;
        }
        JSONArray memories = parseMaintenanceMemories(output);
        if (memories == null || memories.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Object item : memories) {
            JSONObject json = toJsonObject(item);
            if (json == null) continue;
            if (executeMemoryAction(agent, agentId, json, maintenanceModelId)) count++;
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

    private boolean executeMemoryAction(AgentEntity agent, String scopeId, JSONObject json, String modelId) {
        String action = normalizeAction(json.getStr("action"));
        if ("NONE".equals(action)) return false;

        String canonical = cleanupMemoryText(json.getStr("canonical_text"));
        if (isLowQualityMemoryText(canonical) && !"DELETE".equals(action)) return false;

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
        if (confidence.compareTo(new BigDecimal("0.450")) < 0 || importance.compareTo(new BigDecimal("0.300")) < 0)
            return false;
        String relatedId = StringUtils.trimToEmpty(json.getStr("related_memory_id"));
        String sensitivity = normalizeSensitivity(json.getStr("sensitivity"));

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

        // ADD: check duplicates then create
        MemoryFactEntity relatedFact = findRelatedFact(agent.getId(), scopeId, relatedId, canonical, memoryType, category);
        if (relatedFact != null && isEquivalentMemory(relatedFact.getCanonicalText(), canonical)) {
            refreshFactEvidence(relatedFact, confidence, "system", modelId, "维护模型重复证据刷新");
            return false;
        }
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
        String action = StringUtils.defaultIfBlank(value, "ADD").trim().toUpperCase();
        return List.of("ADD", "UPDATE", "DELETE", "NONE").contains(action) ? action : "ADD";
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

        QueryWrapper<MemoryFactEntity> factWrapper = new QueryWrapper<>();
        factWrapper.eq("agent_id", scope.agent.getId())
                .eq("scope_type", SCOPE_DEVICE)
                .and(w -> w.eq("scope_id", scope.scopeId).or().eq("scope_id", scope.agent.getId()))
                .eq("status", STATUS_ACTIVE);
        String keyword = compactQuery(dto.getQuery());
        if (StringUtils.isNotBlank(keyword)) {
            factWrapper.and(w -> w.like("canonical_text", keyword).or().like("category", keyword));
        }
        factWrapper.orderByDesc("importance", "last_seen_at").last("LIMIT " + limit);
        List<MemoryFactEntity> facts = factDao.selectList(factWrapper);
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
        applyDecayToFacts(vo.getFacts());
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
        createFactInternal(scope, dto.getText(), StringUtils.defaultIfBlank(dto.getCategory(), "explicit"),
                "preference", null, actorType, actorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forget(MemoryForgetDTO dto, String actorType, String actorId) {
        AgentScope scope = resolveScope(dto.getAgentId(), dto.getMacAddress());
        if (scope.agent == null || StringUtils.isBlank(dto.getText())) {
            return;
        }
        forgetInternal(scope, dto.getText(), StringUtils.defaultIfBlank(dto.getReason(), "手动忘记"), null, actorType, actorId);
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
        applyDecayToFacts(vo.getFacts());
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

    private void forgetInternal(AgentScope scope, String text, String reason, String sourceEventId,
                                String actorType, String actorId) {
        String pattern = cleanupMemoryText(text);
        MemoryForgetTombstoneEntity tombstone = new MemoryForgetTombstoneEntity();
        tombstone.setTombstoneId(uuid());
        tombstone.setAgentId(scope.agent.getId());
        tombstone.setUserId(scope.agent.getUserId());
        tombstone.setScopeType(SCOPE_DEVICE);
        tombstone.setScopeId(scope.scopeId);
        tombstone.setPatternText(pattern);
        tombstone.setReason(reason);
        tombstone.setStatus(STATUS_ACTIVE);
        tombstone.setSourceEventId(sourceEventId);
        tombstone.setCreatedAt(new Date());
        tombstone.setUpdatedAt(new Date());
        tombstoneDao.insert(tombstone);

        if (StringUtils.isNotBlank(pattern)) {
            List<MemoryFactEntity> matched = factDao.selectList(new QueryWrapper<MemoryFactEntity>()
                    .eq("agent_id", scope.agent.getId())
                    .eq("scope_type", SCOPE_DEVICE)
                    .eq("scope_id", scope.scopeId)
                    .eq("status", STATUS_ACTIVE)
                    .like("canonical_text", pattern));
            for (MemoryFactEntity fact : matched) {
                String before = JsonUtils.toJsonString(fact);
                fact.setStatus(STATUS_DELETED);
                fact.setValidTo(new Date());
                fact.setUpdatedAt(new Date());
                factDao.updateById(fact);
                writeRevision(scope.agent.getId(), fact.getMemoryId(), "delete", before, JsonUtils.toJsonString(fact),
                        actorType, actorId, "忘记规则命中");
            }
        }
        writeRevision(scope.agent.getId(), null, "forget", null, JsonUtils.toJsonString(tombstone),
                actorType, actorId, reason);
    }

    private boolean shouldRunMaintenanceExtraction(MemoryEventDTO dto, String content) {
        return dto != null
                && Integer.valueOf(1).equals(dto.getChatType())
                && StringUtils.isNotBlank(content)
                && content.length() >= 4;
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

    private void createCandidatesWithMaintenanceModel(AgentEntity agent, String scopeId, String sourceEventId, String content) {
        String maintenanceModelId = getMaintenanceLlmModelId(agent);
        if (StringUtils.isBlank(maintenanceModelId)) {
            return;
        }
        String promptInput = StringUtils.abbreviate(content, MAX_MAINTENANCE_INPUT_CHARS);
        String memoryContext = buildMaintenanceMemoryContext(agent.getId(), scopeId);
        String output = llmService.generateSummaryWithHistory(promptInput, memoryContext, MAINTENANCE_PROMPT, maintenanceModelId);
        if (StringUtils.isBlank(output) || output.contains("不可用") || output.contains("失败")) {
            log.warn("长期记忆维护模型返回不可用结果，agentId: {}, modelId: {}", agent.getId(), maintenanceModelId);
            return;
        }
        JSONArray memories = parseMaintenanceMemories(output);
        if (memories == null || memories.isEmpty()) {
            return;
        }
        int saved = 0;
        for (Object item : memories) {
            if (saved >= 5) {
                break;
            }
            JSONObject candidateJson = toJsonObject(item);
            if (candidateJson == null) {
                continue;
            }
            if (createFactFromMaintenance(agent, scopeId, sourceEventId, maintenanceModelId, candidateJson)) {
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

    private boolean createFactFromMaintenance(AgentEntity agent, String scopeId, String sourceEventId,
                                              String maintenanceModelId, JSONObject factJson) {
        String action = normalizeCandidateAction(factJson.getStr("action"));
        if ("IGNORE".equals(action)) {
            return false;
        }
        String canonical = cleanupMemoryText(factJson.getStr("canonical_text"));
        if (isLowQualityMemoryText(canonical)) {
            return false;
        }
        String memoryType = normalizeMemoryLabel(factJson.getStr("memory_type"), "长期记忆");
        String category = normalizeMemoryLabel(factJson.getStr("category"), "未分类");
        BigDecimal confidence = score(factJson.get("confidence"), DEFAULT_SCORE);
        BigDecimal importance = score(factJson.get("importance"), DEFAULT_SCORE);
        if (confidence.compareTo(new BigDecimal("0.500")) < 0 || importance.compareTo(new BigDecimal("0.350")) < 0) {
            return false;
        }
        String relatedMemoryId = StringUtils.trimToEmpty(factJson.getStr("related_memory_id"));
        String sensitivity = normalizeSensitivity(factJson.getStr("sensitivity"));

        // Handle UPDATE: find and update existing fact directly
        if ("UPDATE".equals(action) && StringUtils.isNotBlank(relatedMemoryId)) {
            MemoryFactEntity existingFact = factDao.selectOne(new QueryWrapper<MemoryFactEntity>()
                    .eq("agent_id", agent.getId())
                    .eq("scope_type", SCOPE_DEVICE)
                    .eq("scope_id", scopeId)
                    .eq("memory_id", relatedMemoryId)
                    .ne("status", STATUS_DELETED)
                    .last("LIMIT 1"));
            if (existingFact != null) {
                String before = JsonUtils.toJsonString(existingFact);
                existingFact.setMemoryType(memoryType);
                existingFact.setCategory(category);
                existingFact.setCanonicalText(canonical);
                existingFact.setConfidence(max(existingFact.getConfidence(), confidence));
                existingFact.setImportance(max(existingFact.getImportance(), importance));
                existingFact.setSensitivity(sensitivity);
                existingFact.setStatus(STATUS_ACTIVE);
                existingFact.setLastSeenAt(new Date());
                existingFact.setUpdatedAt(new Date());
                factDao.updateById(existingFact);
                writeRevision(agent.getId(), existingFact.getMemoryId(), "update_from_maintenance", before,
                        JsonUtils.toJsonString(existingFact), "system", maintenanceModelId, "维护模型更新事实");
                log.info("维护模型更新事实，memoryId: {}, text: {}", existingFact.getMemoryId(), canonical);
                return true;
            }
        }

        // For ADD (or UPDATE without a valid related_memory_id): check duplicates and create fact directly
        MemoryFactEntity relatedFact = findRelatedFact(agent.getId(), scopeId, relatedMemoryId,
                canonical, memoryType, category);
        if (relatedFact != null && isEquivalentMemory(relatedFact.getCanonicalText(), canonical)) {
            refreshFactEvidence(relatedFact, confidence, "system", maintenanceModelId, "维护模型重复证据刷新");
            log.info("维护模型刷新已有事实，memoryId: {}, text: {}", relatedFact.getMemoryId(), canonical);
            return false;
        }

        String hash = sha256(agent.getId() + "|" + scopeId + "|" + canonical);
        if (factDao.selectCount(new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agent.getId())
                .eq("scope_type", SCOPE_DEVICE)
                .eq("scope_id", scopeId)
                .eq("source_hash", hash)
                .ne("status", STATUS_DELETED)) > 0) {
            return false;
        }

        // Create fact directly (not a candidate)
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
        fact.setPayload(JSONUtil.toJsonStr(Map.of(
                "maintenance_llm", maintenanceModelId,
                "source", MAINTENANCE_EXTRACTOR_VERSION,
                "reason", StringUtils.defaultString(factJson.getStr("reason")))));
        fact.setImportance(importance);
        fact.setConfidence(confidence);
        fact.setSensitivity(sensitivity);
        fact.setConfirmationState("auto");
        fact.setStatus(STATUS_ACTIVE);
        fact.setValidFrom(new Date());
        fact.setFirstSeenAt(new Date());
        fact.setLastSeenAt(new Date());
        fact.setSourceEventId(sourceEventId);
        fact.setSourceHash(hash);
        fact.setExtractorVersion(MAINTENANCE_EXTRACTOR_VERSION);
        fact.setCreatedAt(new Date());
        fact.setUpdatedAt(new Date());
        factDao.insert(fact);
        writeRevision(agent.getId(), fact.getMemoryId(), "create_from_maintenance", null,
                JsonUtils.toJsonString(fact), "system", maintenanceModelId, "维护模型创建事实");
        log.info("维护模型创建事实，memoryId: {}, text: {}", fact.getMemoryId(), canonical);
        return true;
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
        if (StringUtils.isNotBlank(relatedMemoryId)) {
            MemoryFactEntity byId = factDao.selectOne(new QueryWrapper<MemoryFactEntity>()
                    .eq("agent_id", agentId)
                    .eq("scope_type", SCOPE_DEVICE)
                    .eq("scope_id", scopeId)
                    .eq("memory_id", relatedMemoryId)
                    .ne("status", STATUS_DELETED)
                    .last("LIMIT 1"));
            if (byId != null) {
                return byId;
            }
        }
        String normalizedCategory = normalizeMemoryLabel(category, "未分类");
        List<MemoryFactEntity> facts = factDao.selectList(new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agentId)
                .eq("scope_type", SCOPE_DEVICE)
                .eq("scope_id", scopeId)
                .eq("status", STATUS_ACTIVE)
                .eq("category", normalizedCategory)
                .orderByDesc("updated_at")
                .last("LIMIT 30"));
        for (MemoryFactEntity fact : facts) {
            if (isEquivalentMemory(fact.getCanonicalText(), canonical)) {
                return fact;
            }
        }
        return null;
    }

    private boolean isEquivalentMemory(String left, String right) {
        String normalizedLeft = normalizeForCompare(left);
        String normalizedRight = normalizeForCompare(right);
        if (StringUtils.isBlank(normalizedLeft) || StringUtils.isBlank(normalizedRight)) {
            return false;
        }
        return normalizedLeft.equals(normalizedRight)
                || normalizedLeft.contains(normalizedRight)
                || normalizedRight.contains(normalizedLeft);
    }

    private String normalizeForCompare(String text) {
        return StringUtils.defaultString(text)
                .replaceAll("[\\s，。！？、；：,.!?;:\"'“”‘’（）()《》<>\\[\\]【】]", "")
                .replace("用户的", "")
                .replace("用户", "")
                .replace("本人", "")
                .trim();
    }

    private boolean isLowQualityMemoryText(String canonical) {
        String text = cleanupMemoryText(canonical);
        if (StringUtils.isBlank(text) || text.length() < 6 || text.length() > 220) {
            return true;
        }
        return false;
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

    private void applyDecayToFacts(List<MemoryFactEntity> facts) {
        if (facts == null || facts.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (MemoryFactEntity fact : facts) {
            if (!STATUS_ACTIVE.equals(fact.getStatus())) continue;
            if (fact.getLastSeenAt() == null) {
                fact.setLastSeenAt(fact.getCreatedAt() != null ? fact.getCreatedAt() : new Date());
            }
            long daysSinceLastSeen = Math.max(0, (now - fact.getLastSeenAt().getTime()) / 86400000L);
            double retention = INITIAL_RETENTION * Math.exp(-DECAY_RATE * daysSinceLastSeen);
            double reinforced = Math.min(1.0, retention + REINFORCEMENT_FACTOR);
            BigDecimal newConfidence = new BigDecimal(String.format("%.3f", reinforced));
            if (newConfidence.compareTo(MAX_SCORE) > 0) newConfidence = MAX_SCORE;
            if (newConfidence.compareTo(new BigDecimal(String.valueOf(FORGET_THRESHOLD))) < 0) {
                fact.setStatus(STATUS_DELETED);
                fact.setValidTo(new Date());
                fact.setUpdatedAt(new Date());
                factDao.updateById(fact);
                writeRevision(fact.getAgentId(), fact.getMemoryId(), "decay_forget", null, null,
                        "system", "ebbinghaus", String.format("自动衰减遗忘: retention=%.3f, days=%d", retention, daysSinceLastSeen));
                log.info("艾宾浩斯衰减自动遗忘, memoryId={}, retention={:.3f}, days={}", fact.getMemoryId(), retention, daysSinceLastSeen);
            } else if (Math.abs(newConfidence.doubleValue() - (fact.getConfidence() != null ? fact.getConfidence().doubleValue() : INITIAL_RETENTION)) > 0.05) {
                fact.setConfidence(newConfidence);
                fact.setUpdatedAt(new Date());
                factDao.updateById(fact);
            }
        }
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

    private String extractRemember(String text) {
        String[] markers = {"记住", "帮我记住", "请记住", "你要记住"};
        return extractAfterMarker(text, markers);
    }

    private String extractForget(String text) {
        String[] markers = {"忘记", "删掉记忆", "删除记忆", "不要记得", "别记住"};
        return extractAfterMarker(text, markers);
    }

    private String extractAfterMarker(String text, String[] markers) {
        for (String marker : markers) {
            int index = text.indexOf(marker);
            if (index >= 0) {
                return cleanupMemoryText(text.substring(index + marker.length()));
            }
        }
        return null;
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
