package xiaozhi.modules.memory.service.impl;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.common.utils.SpringContextUtils;
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
import xiaozhi.modules.memory.dto.MemorySessionProcessDTO;
import xiaozhi.modules.memory.entity.MemoryCandidateEntity;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.entity.MemoryForgetTombstoneEntity;
import xiaozhi.modules.memory.entity.MemoryProfileEntity;
import xiaozhi.modules.memory.entity.MemoryRevisionEntity;
import xiaozhi.modules.memory.entity.MemorySourceEventEntity;
import xiaozhi.modules.memory.entity.MemoryToolBehaviorEntity;
import xiaozhi.modules.memory.service.MemoryMaintenanceService;
import xiaozhi.modules.memory.service.MemoryQueryService;
import xiaozhi.modules.memory.service.MemorySettingsService;
import xiaozhi.modules.memory.service.MemoryService;
import xiaozhi.modules.memory.service.MemoryRuntimeRefresh;
import xiaozhi.modules.memory.utils.MemoryHelper;
import xiaozhi.modules.memory.vo.MemoryDashboardVO;
import xiaozhi.modules.memory.vo.MemoryQueryVO;
import xiaozhi.modules.memory.vo.MemoryRebuildResultVO;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.workspace.service.WorkspaceService;

@Service
@RequiredArgsConstructor
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
    private final MemorySettingsService memorySettingsService;
    private final MemoryRuntimeRefresh memoryRuntimeRefresh;
    @org.springframework.beans.factory.annotation.Autowired
    private xiaozhi.modules.memory.service.MemoryPolicyService policyService;
    @org.springframework.beans.factory.annotation.Autowired
    private xiaozhi.modules.memory.service.MemoryGovernanceService governance;
    @org.springframework.beans.factory.annotation.Autowired
    private xiaozhi.modules.memory.service.MemoryRequestScopeService requestScopes;

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
        event.setWorkspaceId(scope.agent.getWorkspaceId());
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

    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remember(MemoryRememberDTO dto, String actorType, String actorId) {
        if ("user".equals(actorType)) assertAgentOwner(dto.getAgentId());
        validateInstructionScope(dto.getAgentId(), dto.getMacAddress(), dto.getSpeakerId());
        AgentScope scope = resolveScope(dto.getAgentId(), dto.getMacAddress());
        if (scope.agent == null || StringUtils.isBlank(dto.getText())) {
            return;
        }
        boolean shared = dto.getShared() != null
                ? dto.getShared()
                : StringUtils.isBlank(dto.getSpeakerId());
        memoryMaintenanceService.applyRememberInstruction(scope.agent, scope.scopeId, dto.getText(),
                dto.getCategory(), dto.getSpeakerId(), dto.getSpeakerName(), shared, actorType, actorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forget(MemoryForgetDTO dto, String actorType, String actorId) {
        if ("user".equals(actorType)) assertAgentOwner(dto.getAgentId());
        validateInstructionScope(dto.getAgentId(), dto.getMacAddress(), dto.getSpeakerId());
        AgentScope scope = resolveScope(dto.getAgentId(), dto.getMacAddress());
        if (scope.agent == null || StringUtils.isBlank(dto.getText())) {
            return;
        }
        boolean shared = dto.getShared() != null
                ? dto.getShared()
                : StringUtils.isBlank(dto.getSpeakerId());
        memoryMaintenanceService.applyForgetInstruction(scope.agent, scope.scopeId, dto.getText(),
                dto.getPatternType(), dto.getReason(), dto.getSpeakerId(), dto.getSpeakerName(), shared,
                actorType, actorId);
    }

    @Override
    public int processSession(MemorySessionProcessDTO dto) {
        if (requestScopes != null) requestScopes.require(dto.getAgentId(), dto.getMacAddress(), dto.getSessionId());
        AgentScope scope = new AgentScope(agentDao.selectById(dto.getAgentId()), dto.getMacAddress());
        if (scope.agent == null) {
            return 0;
        }
        return memoryMaintenanceService.processSessionConversation(
                scope.agent, scope.scopeId,
                StringUtils.isBlank(dto.getSegmentId()) ? dto.getSessionId()
                        : MemoryHelper.sha256(dto.getSessionId() + "|" + dto.getSegmentId()), dto.getMessages());
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
        lockAgentForMemoryUpdate(agentId);
        MemoryFactEntity identity = new MemoryFactEntity();
        identity.setScopeId(agentId);
        if (governance != null) governance.editIdentity(agentId, identity, dto);
        AgentScope scope = new AgentScope(agent, identity.getScopeId());
        return createFactInternal(scope,
                dto.getCanonicalText(),
                StringUtils.defaultIfBlank(dto.getCategory(), "manual"),
                StringUtils.defaultIfBlank(dto.getMemoryType(), "profile"),
                null, actorType, actorId, "long_term", StringUtils.defaultIfBlank(identity.getPayload(), "{}"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateFact(String agentId, String memoryId, MemoryFactDTO dto, String actorType, String actorId) {
        assertAgentOwner(agentId);
        lockAgentForMemoryUpdate(agentId);
        MemoryFactEntity fact = getFact(agentId, memoryId);
        String before = JsonUtils.toJsonString(fact);
        if (StringUtils.isNotBlank(dto.getCanonicalText())) {
            String canonical = dto.getCanonicalText().trim();
            if (MemoryHelper.STATUS_ACTIVE.equals(fact.getStatus())) {
                ensureActiveMemoryCapacity(agentId, fact, canonical);
            }
            fact.setCanonicalText(canonical);
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
        if (dto.getPayload() != null) throw new IllegalArgumentException("请使用人物和设备字段编辑归属，不接受原始 payload");
        if (governance != null) governance.editIdentity(agentId, fact, dto);
        if (governance != null) fact.setExpiresAt(governance.expiresAt(agentId));
        if (governance != null && governance.blocked(agentId, fact.getScopeId(),
                xiaozhi.modules.memory.utils.MemorySpeakerUtils.payloadSpeakerId(fact), fact.getCanonicalText()))
            throw new IllegalArgumentException("该事实已被遗忘规则阻止");
        fact.setSourceHash(MemoryHelper.sha256(agentId + "|" + fact.getScopeId() + "|"
                + xiaozhi.modules.memory.utils.MemorySpeakerUtils.payloadSpeakerId(fact) + "|" + fact.getCanonicalText()));
        fact.setLastSeenAt(new Date());
        fact.setUpdatedAt(new Date());
        factDao.updateById(fact);
        writeRevision(agentId, memoryId, "update", before, JsonUtils.toJsonString(fact), actorType, actorId, "更新记忆");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archiveFact(String agentId, String memoryId, String actorType, String actorId) {
        updateStatus(agentId, memoryId, MemoryHelper.STATUS_ARCHIVED, actorType, actorId, "归档记忆");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFact(String agentId, String memoryId, String actorType, String actorId) {
        updateStatus(agentId, memoryId, MemoryHelper.STATUS_DELETED, actorType, actorId, "删除记忆");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmCandidate(String agentId, String candidateId, String actorType, String actorId) {
        assertAgentOwner(agentId);
        lockAgentForMemoryUpdate(agentId);
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
        lockAgentForMemoryUpdate(agentId);
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
    @Transactional(rollbackFor = Exception.class)
    public MemoryRebuildResultVO clearMemories(String agentId, String actorType, String actorId) {
        return memoryMaintenanceService.clearMemories(agentId, actorType, actorId);
    }

    @Override
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
                if (StringUtils.isBlank(agentId) || !agentId.equals(device.getAgentId()))
                    throw new IllegalArgumentException("设备与智能体作用域不匹配");
                agent = agentDao.selectById(device.getAgentId());
                scopeId = macAddress;
            } else {
                throw new IllegalArgumentException("设备未绑定智能体");
            }
        }
        if (agent == null && StringUtils.isNotBlank(agentId)) {
            agent = agentDao.selectById(agentId);
        }
        return new AgentScope(agent, scopeId);
    }

    private void validateInstructionScope(String agentId, String deviceId, String speakerId) {
        if (policyService == null) return;
        policyService.requireSpeaker(agentId, speakerId);
        if (StringUtils.isNotBlank(deviceId) || "DEVICE".equals(policyService.get(agentId).getMemoryScope()))
            policyService.requireDevice(agentId, deviceId);
    }

    private AgentEntity assertAgentOwner(String agentId) {
        AgentEntity agent = agentDao.selectById(agentId);
        if (agent == null) {
            throw new IllegalArgumentException("智能体不存在");
        }
        UserDetail user = SecurityUser.getUser();
        if (!xiaozhi.modules.security.ops.OpsAccess.isUser(user)
                || !workspaceService().canRead(agent.getWorkspaceId(), user.getId())) {
            throw new IllegalArgumentException("无权访问该智能体记忆");
        }
        return agent;
    }

    private WorkspaceService workspaceService() {
        return SpringContextUtils.getBean(WorkspaceService.class);
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
        memoryRuntimeRefresh.changed(agentId);
    }

    private String applyCandidateToFact(AgentScope scope, MemoryCandidateEntity candidate, String actorType, String actorId) {
        JSONObject payload = MemoryHelper.parsePayload(candidate.getPayload());
        if (policyService != null) {
            policyService.requireSpeaker(scope.agent.getId(), payload.getStr("speaker_id"));
            if ("DEVICE".equals(policyService.get(scope.agent.getId()).getMemoryScope()))
                policyService.requireDevice(scope.agent.getId(), scope.scopeId);
        }
        String action = MemoryHelper.normalizeCandidateAction(payload.getStr("action"));
        String relatedMemoryId = payload.getStr("related_memory_id");
        String tier = resolveMemoryTier(payload.getStr("memory_tier"));
        MemoryFactEntity relatedFact = findRelatedFact(scope.agent.getId(), relatedMemoryId);
        if (List.of("UPDATE", "DELETE", "ARCHIVE").contains(action) && relatedFact == null)
            throw new IllegalArgumentException("候选动作引用的记忆已不存在");
        if (relatedFact != null && (!StringUtils.equals(xiaozhi.modules.memory.utils.MemorySpeakerUtils.payloadSpeakerId(relatedFact),
                StringUtils.defaultString(payload.getStr("speaker_id"))) || (governance != null
                && !governance.inScope(scope.agent.getId(), scope.scopeId, relatedFact.getScopeId()))))
            throw new IllegalArgumentException("候选动作人物或设备归属不匹配");
        if (("ARCHIVE".equals(action) || "DELETE".equals(action)) && relatedFact != null) {
            String before = JsonUtils.toJsonString(relatedFact);
            if (governance != null && "DELETE".equals(action)) governance.forgetFact(relatedFact, actorType, actorId);
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
            String canonical = MemoryHelper.cleanupMemoryText(candidate.getCanonicalText());
            if (governance != null && governance.blocked(scope.agent.getId(), relatedFact.getScopeId(),
                    xiaozhi.modules.memory.utils.MemorySpeakerUtils.payloadSpeakerId(relatedFact), canonical))
                throw new IllegalArgumentException("该事实已被遗忘规则阻止");
            ensureActiveMemoryCapacity(scope.agent.getId(), relatedFact, canonical);
            String before = JsonUtils.toJsonString(relatedFact);
            relatedFact.setMemoryType(MemoryHelper.normalizeMemoryLabel(candidate.getMemoryType(), "长期记忆"));
            relatedFact.setCategory(MemoryHelper.normalizeMemoryLabel(candidate.getCategory(), "未分类"));
            relatedFact.setCanonicalText(canonical);
            relatedFact.setConfidence(MemoryHelper.max(relatedFact.getConfidence(), candidate.getConfidence()));
            relatedFact.setImportance(MemoryHelper.max(relatedFact.getImportance(), candidate.getImportance()));
            relatedFact.setSensitivity(MemoryHelper.normalizeSensitivity(candidate.getSensitivity()));
            relatedFact.setMemoryTier(tier);
            relatedFact.setExpiresAt(governance == null ? null : governance.expiresAt(scope.agent.getId()));
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
                candidate.getSourceEventId(), actorType, actorId, tier, candidate.getPayload());
    }

    private String createFactInternal(AgentScope scope, String text, String category, String memoryType,
                                      String sourceEventId, String actorType, String actorId) {
        return createFactInternal(scope, text, category, memoryType, sourceEventId, actorType, actorId, null);
    }

    private String createFactInternal(AgentScope scope, String text, String category, String memoryType,
                                      String sourceEventId, String actorType, String actorId, String memoryTier) {
        return createFactInternal(scope, text, category, memoryType, sourceEventId, actorType, actorId, memoryTier, "{}");
    }

    private String createFactInternal(AgentScope scope, String text, String category, String memoryType,
                                      String sourceEventId, String actorType, String actorId, String memoryTier, String identityPayload) {
        String canonical = MemoryHelper.cleanupMemoryText(text);
        if (StringUtils.isBlank(canonical)) {
            return null;
        }
        String speakerId = StringUtils.defaultString(MemoryHelper.parsePayload(identityPayload).getStr("speaker_id"));
        if (policyService != null) policyService.requireSpeaker(scope.agent.getId(), speakerId);
        if (governance != null && governance.blocked(scope.agent.getId(), scope.scopeId, speakerId, canonical))
            throw new IllegalArgumentException("该事实已被遗忘规则阻止");
        String hash = MemoryHelper.sha256(scope.agent.getId() + "|" + scope.scopeId + "|" + speakerId + "|" + canonical);
        QueryWrapper<MemoryFactEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("agent_id", scope.agent.getId())
                .eq("scope_type", MemoryHelper.SCOPE_DEVICE)
                .eq(policyService == null || "DEVICE".equals(policyService.get(scope.agent.getId()).getMemoryScope()), "scope_id", scope.scopeId)
                .eq("canonical_text", canonical)
                .apply("COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.speaker_id')), '') = {0}", speakerId)
                .ne("status", MemoryHelper.STATUS_DELETED)
                .last("LIMIT 1");
        MemoryFactEntity existing = factDao.selectOne(wrapper);
        if (existing != null) {
            if (xiaozhi.modules.memory.service.MemoryGovernanceService.activeChars(existing) == 0) {
                ensureActiveMemoryCapacity(scope.agent.getId(), existing, existing.getCanonicalText());
            }
            refreshFactEvidence(existing, MemoryHelper.DEFAULT_SCORE, actorType, actorId, "重复证据刷新");
            return existing.getMemoryId();
        }
        ensureActiveMemoryCapacity(scope.agent.getId(), null, canonical);
        MemoryFactEntity fact = new MemoryFactEntity();
        fact.setMemoryId(MemoryHelper.uuid());
        fact.setAgentId(scope.agent.getId());
        fact.setUserId(scope.agent.getUserId());
        fact.setWorkspaceId(scope.agent.getWorkspaceId());
        fact.setMacAddress(scope.scopeId);
        fact.setScopeType(MemoryHelper.SCOPE_DEVICE);
        fact.setScopeId(scope.scopeId);
        fact.setMemoryType(MemoryHelper.normalizeMemoryLabel(memoryType, "长期记忆"));
        fact.setCategory(MemoryHelper.normalizeMemoryLabel(category, "未分类"));
        fact.setCanonicalText(canonical);
        fact.setPayload(identityPayload);
        fact.setImportance(MemoryHelper.DEFAULT_SCORE);
        fact.setConfidence(MemoryHelper.DEFAULT_SCORE);
        fact.setSensitivity("normal");
        fact.setConfirmationState("user".equalsIgnoreCase(actorType) ? "manual" : "auto");
        fact.setStatus(MemoryHelper.STATUS_ACTIVE);
        if (StringUtils.isNotBlank(memoryTier)) {
            fact.setMemoryTier(memoryTier);
        }
        fact.setExpiresAt(governance == null ? null : governance.expiresAt(scope.agent.getId()));
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
        lockAgentForMemoryUpdate(agentId);
        MemoryFactEntity fact = getFact(agentId, memoryId);
        String before = JsonUtils.toJsonString(fact);
        fact.setStatus(status);
        fact.setUpdatedAt(new Date());
        if (MemoryHelper.STATUS_ARCHIVED.equals(status)) {
            fact.setArchivedAt(new Date());
        }
        if (MemoryHelper.STATUS_DELETED.equals(status)) {
            if (governance != null) governance.forgetFact(fact, actorType, actorId);
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

    private void ensureActiveMemoryCapacity(String agentId, MemoryFactEntity replacedFact, String canonicalText) {
        // 人工新增/编辑/候选确认与自动维护共用同一智能体行锁。
        lockAgentForMemoryUpdate(agentId);
        int currentChars = factDao.selectList(new QueryWrapper<MemoryFactEntity>()
                        .eq("agent_id", agentId)
                        .eq("status", MemoryHelper.STATUS_ACTIVE)
                        .and(w -> w.isNull("expires_at").or().gt("expires_at", new Date())))
                .stream()
                .filter(fact -> fact != null && StringUtils.isNotEmpty(fact.getCanonicalText()))
                .mapToInt(fact -> MemoryHelper.characterCount(fact.getCanonicalText()))
                .sum();
        int replacedChars = xiaozhi.modules.memory.service.MemoryGovernanceService.activeChars(replacedFact);
        int projectedChars = currentChars - replacedChars + MemoryHelper.characterCount(canonicalText);
        int maxActiveMemoryChars = policyService == null ? memorySettingsService.activeContextMaxChars()
                : policyService.get(agentId).getActiveMaxChars();
        if (projectedChars > maxActiveMemoryChars) {
            throw new IllegalArgumentException("单个智能体的生效记忆总量不能超过 "
                    + maxActiveMemoryChars + " 字符，请先执行记忆审查/蒸馏或归档低价值记忆");
        }
    }

    private void lockAgentForMemoryUpdate(String agentId) {
        agentDao.selectOne(new QueryWrapper<AgentEntity>()
                .eq("id", agentId)
                .last("FOR UPDATE"));
    }

    private void refreshFactEvidence(MemoryFactEntity fact, BigDecimal confidence, String actorType, String actorId, String reason) {
        fact.setLastSeenAt(new Date());
        fact.setConfidence(MemoryHelper.max(fact.getConfidence(), confidence == null ? MemoryHelper.DEFAULT_SCORE : confidence));
        fact.setStatus(MemoryHelper.STATUS_ACTIVE);
        if (governance != null) fact.setExpiresAt(governance.expiresAt(fact.getAgentId()));
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

    private String resolveMemoryTier(String rawTier) {
        String tier = MemoryHelper.normalizeMemoryTier(rawTier);
        return StringUtils.defaultIfBlank(tier, "long_term");
    }

    private record AgentScope(AgentEntity agent, String scopeId) {
    }
}
