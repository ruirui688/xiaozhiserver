package xiaozhi.modules.memory.service.impl;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import cn.hutool.json.JSONObject;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
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
import xiaozhi.modules.memory.service.MemoryMaintenanceService;
import xiaozhi.modules.memory.service.MemoryQueryService;
import xiaozhi.modules.memory.service.MemoryService;
import xiaozhi.modules.memory.utils.MemoryHelper;
import xiaozhi.modules.memory.utils.MemoryTierHelper;
import xiaozhi.modules.memory.vo.MemoryDashboardVO;
import xiaozhi.modules.memory.vo.MemoryQueryVO;
import xiaozhi.modules.memory.vo.MemoryRebuildResultVO;
import xiaozhi.modules.security.user.SecurityUser;

@Service
@AllArgsConstructor
@Slf4j
public class MemoryServiceImpl implements MemoryService {

    private final MemorySourceEventDao sourceEventDao;
    private final MemoryCandidateDao candidateDao;
    private final MemoryFactDao factDao;
    private final MemoryRevisionDao revisionDao;
    private final MemoryProfileDao profileDao;
    private final MemoryToolBehaviorDao toolBehaviorDao;
    private final MemoryForgetTombstoneDao tombstoneDao;
    private final DeviceDao deviceDao;
    private final AgentDao agentDao;

    private final MemoryQueryService memoryQueryService;
    private final MemoryMaintenanceService memoryMaintenanceService;

    // ===================== 公开方法 — 直接实现 =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordEvent(MemoryEventDTO dto) {
        AgentScope scope = resolveScope(dto.getAgentId(), dto.getMacAddress());
        if (scope.agent == null) {
            return;
        }
        String content = MemoryHelper.normalizeContent(dto.getContentText(), dto.getContentJson());
        String eventId = StringUtils.defaultIfBlank(dto.getEventId(), MemoryHelper.uuid());
        if (sourceEventDao.selectCount(new QueryWrapper<MemorySourceEventEntity>().eq("event_id", eventId)) > 0) {
            return;
        }
        MemorySourceEventEntity event = new MemorySourceEventEntity();
        event.setEventId(eventId);
        event.setAgentId(scope.agent.getId());
        event.setUserId(scope.agent.getUserId());
        event.setMacAddress(dto.getMacAddress());
        event.setSessionId(dto.getSessionId());
        event.setScopeType(MemoryHelper.SCOPE_DEVICE);
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
            runAfterCommit(() -> memoryMaintenanceService
                    .createCandidatesWithMaintenanceModel(scope.agent, scope.scopeId, event.getEventId(), content));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remember(MemoryRememberDTO dto, String actorType, String actorId) {
        AgentScope scope = resolveScope(dto.getAgentId(), dto.getMacAddress());
        if (scope.agent == null || StringUtils.isBlank(dto.getText())) {
            return;
        }
        memoryMaintenanceService.applyMaintenanceInstruction(scope.agent, scope.scopeId, dto.getText(), actorType, actorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forget(MemoryForgetDTO dto, String actorType, String actorId) {
        AgentScope scope = resolveScope(dto.getAgentId(), dto.getMacAddress());
        if (scope.agent == null || StringUtils.isBlank(dto.getText())) {
            return;
        }
        memoryMaintenanceService.applyMaintenanceInstruction(scope.agent, scope.scopeId, dto.getText(), actorType, actorId);
    }

    @Override
    public MemoryDashboardVO dashboard(String agentId) {
        assertAgentOwner(agentId);
        MemoryDashboardVO vo = new MemoryDashboardVO();
        QueryWrapper<MemoryFactEntity> factWrapper = new QueryWrapper<>();
        factWrapper.eq("agent_id", agentId).ne("status", MemoryHelper.STATUS_DELETED)
                .orderByDesc("updated_at").last("LIMIT 200");
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
        return createFactInternal(scope,
                dto.getCanonicalText(),
                StringUtils.defaultIfBlank(dto.getCategory(), "manual"),
                StringUtils.defaultIfBlank(dto.getMemoryType(), "profile"),
                null, actorType, actorId);
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
        updateStatus(agentId, memoryId, MemoryHelper.STATUS_ARCHIVED, actorType, actorId, "归档记忆");
    }

    @Override
    public void deleteFact(String agentId, String memoryId, String actorType, String actorId) {
        updateStatus(agentId, memoryId, MemoryHelper.STATUS_DELETED, actorType, actorId, "删除记忆");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmCandidate(String agentId, String candidateId, String actorType, String actorId) {
        assertAgentOwner(agentId);
        MemoryCandidateEntity candidate = getCandidate(agentId, candidateId);
        if (!MemoryHelper.STATUS_PENDING.equals(candidate.getStatus())) {
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
        candidate.setStatus(MemoryHelper.STATUS_REJECTED);
        candidate.setUpdatedAt(new Date());
        candidateDao.updateById(candidate);
        writeRevision(agentId, null, "reject_candidate", JsonUtils.toJsonString(candidate), null,
                actorType, actorId, "候选记忆拒绝");
    }

    // ===================== 公开方法 — 委托给子服务 =====================

    @Override
    public MemoryQueryVO query(MemoryQueryDTO dto) {
        return memoryQueryService.query(dto);
    }

    @Override
    public int processSessionMemories(String sessionId, String agentId) {
        return memoryMaintenanceService.processSessionMemories(sessionId, agentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemoryRebuildResultVO clearMemories(String agentId, String actorType, String actorId) {
        return memoryMaintenanceService.clearMemories(agentId, actorType, actorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemoryRebuildResultVO rebuildFromRecentChatHistory(String agentId, MemoryRebuildRequestDTO dto,
                                                              String actorType, String actorId) {
        return memoryMaintenanceService.rebuildFromRecentChatHistory(agentId, dto, actorType, actorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemoryRebuildResultVO reviewActiveMemories(String agentId, boolean dryRun, String actorType, String actorId) {
        assertAgentOwner(agentId);
        return memoryMaintenanceService.reviewActiveMemories(agentId, dryRun, actorType, actorId);
    }

    // ===================== 私有辅助方法 =====================

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

    private boolean shouldRunMaintenanceExtraction(MemoryEventDTO dto, String content) {
        return dto != null
                && Integer.valueOf(1).equals(dto.getChatType())
                && StringUtils.isNotBlank(content);
    }

    private String applyCandidateToFact(AgentScope scope, MemoryCandidateEntity candidate, String actorType, String actorId) {
        JSONObject payload = MemoryHelper.parsePayload(candidate.getPayload());
        String action = MemoryHelper.normalizeCandidateAction(payload.getStr("action"));
        String relatedMemoryId = payload.getStr("related_memory_id");
        String tier = resolveMemoryTier(payload.getStr("memory_tier"), candidate.getImportance());
        MemoryFactEntity relatedFact = findRelatedFact(scope.agent.getId(), relatedMemoryId);
        if (("ARCHIVE".equals(action) || "DELETE".equals(action)) && relatedFact != null) {
            String before = JsonUtils.toJsonString(relatedFact);
            relatedFact.setStatus("ARCHIVE".equals(action)
                    ? MemoryHelper.STATUS_ARCHIVED : MemoryHelper.STATUS_DELETED);
            relatedFact.setValidTo(new Date());
            if ("ARCHIVE".equals(action)) {
                relatedFact.setArchivedAt(new Date());
            }
            relatedFact.setUpdatedAt(new Date());
            factDao.updateById(relatedFact);
            writeRevision(scope.agent.getId(), relatedFact.getMemoryId(),
                    "ARCHIVE".equals(action) ? "archive_from_candidate" : "delete_from_candidate",
                    before, JsonUtils.toJsonString(relatedFact), actorType, actorId, "候选记忆生命周期动作");
            return relatedFact.getMemoryId();
        }
        if ("UPDATE".equals(action) && relatedFact != null) {
            String before = JsonUtils.toJsonString(relatedFact);
            relatedFact.setMemoryType(MemoryHelper.normalizeMemoryLabel(candidate.getMemoryType(), "长期记忆"));
            relatedFact.setCategory(MemoryHelper.normalizeMemoryLabel(candidate.getCategory(), "未分类"));
            relatedFact.setCanonicalText(MemoryHelper.cleanupMemoryText(candidate.getCanonicalText()));
            relatedFact.setConfidence(MemoryHelper.max(relatedFact.getConfidence(), candidate.getConfidence()));
            relatedFact.setImportance(MemoryHelper.max(relatedFact.getImportance(), candidate.getImportance()));
            relatedFact.setSensitivity(MemoryHelper.normalizeSensitivity(candidate.getSensitivity()));
            relatedFact.setMemoryTier(tier);
            relatedFact.setExpiresAt(resolveExpiresAt(tier));
            relatedFact.setStatus(MemoryHelper.STATUS_ACTIVE);
            relatedFact.setLastSeenAt(new Date());
            relatedFact.setUpdatedAt(new Date());
            relatedFact.setPayload(mergeFactPayload(relatedFact.getPayload(), candidate.getPayload()));
            factDao.updateById(relatedFact);
            writeRevision(scope.agent.getId(), relatedFact.getMemoryId(), "update_from_candidate", before,
                    JsonUtils.toJsonString(relatedFact), actorType, actorId, "候选记忆更新已有事实");
            return relatedFact.getMemoryId();
        }
        return createFactInternal(scope, candidate.getCanonicalText(),
                MemoryHelper.normalizeMemoryLabel(candidate.getCategory(), "未分类"),
                MemoryHelper.normalizeMemoryLabel(candidate.getMemoryType(), "长期记忆"),
                candidate.getSourceEventId(), actorType, actorId, tier);
    }

    private String createFactInternal(AgentScope scope, String text, String category, String memoryType,
                                      String sourceEventId, String actorType, String actorId) {
        return createFactInternal(scope, text, category, memoryType, sourceEventId, actorType, actorId, null);
    }

    private String createFactInternal(AgentScope scope, String text, String category, String memoryType,
                                      String sourceEventId, String actorType, String actorId, String memoryTier) {
        String canonical = MemoryHelper.cleanupMemoryText(text);
        if (StringUtils.isBlank(canonical)) {
            return null;
        }
        String hash = MemoryHelper.sha256(scope.agent.getId() + "|" + scope.scopeId + "|" + canonical);
        QueryWrapper<MemoryFactEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("agent_id", scope.agent.getId())
                .eq("scope_type", MemoryHelper.SCOPE_DEVICE)
                .eq("scope_id", scope.scopeId)
                .eq("source_hash", hash)
                .ne("status", MemoryHelper.STATUS_DELETED)
                .last("LIMIT 1");
        MemoryFactEntity existing = factDao.selectOne(wrapper);
        if (existing != null) {
            refreshFactEvidence(existing, MemoryHelper.DEFAULT_SCORE, actorType, actorId, "重复证据刷新");
            return existing.getMemoryId();
        }
        MemoryFactEntity fact = new MemoryFactEntity();
        fact.setMemoryId(MemoryHelper.uuid());
        fact.setAgentId(scope.agent.getId());
        fact.setUserId(scope.agent.getUserId());
        fact.setMacAddress(scope.scopeId);
        fact.setScopeType(MemoryHelper.SCOPE_DEVICE);
        fact.setScopeId(scope.scopeId);
        fact.setMemoryType(MemoryHelper.normalizeMemoryLabel(memoryType, "长期记忆"));
        fact.setCategory(MemoryHelper.normalizeMemoryLabel(category, "未分类"));
        fact.setCanonicalText(canonical);
        fact.setPayload("{}");
        fact.setImportance(MemoryHelper.DEFAULT_SCORE);
        fact.setConfidence(MemoryHelper.DEFAULT_SCORE);
        fact.setSensitivity("normal");
        fact.setConfirmationState("auto");
        fact.setStatus(MemoryHelper.STATUS_ACTIVE);
        if (StringUtils.isNotBlank(memoryTier)) {
            fact.setMemoryTier(memoryTier);
            fact.setExpiresAt(resolveExpiresAt(memoryTier));
        }
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

    private void updateStatus(String agentId, String memoryId, String status, String actorType, String actorId, String reason) {
        assertAgentOwner(agentId);
        MemoryFactEntity fact = getFact(agentId, memoryId);
        String before = JsonUtils.toJsonString(fact);
        fact.setStatus(status);
        fact.setUpdatedAt(new Date());
        if (MemoryHelper.STATUS_ARCHIVED.equals(status)) {
            fact.setArchivedAt(new Date());
        }
        if (MemoryHelper.STATUS_DELETED.equals(status)) {
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

    private record AgentScope(AgentEntity agent, String scopeId) {
    }
}
