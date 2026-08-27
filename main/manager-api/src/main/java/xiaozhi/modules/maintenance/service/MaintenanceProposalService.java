package xiaozhi.modules.maintenance.service;

import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.knowledge.source.connector.PublicUrlGuard;
import xiaozhi.modules.knowledge.source.dto.KnowledgeSourceRequest;
import xiaozhi.modules.knowledge.source.service.KnowledgeSourceService;
import xiaozhi.modules.maintenance.dao.MaintenanceProposalDao;
import xiaozhi.modules.maintenance.dto.HarnessMaintenanceResponse;
import xiaozhi.modules.maintenance.entity.MaintenancePolicyEntity;
import xiaozhi.modules.maintenance.entity.MaintenanceProposalEntity;
import xiaozhi.modules.maintenance.entity.MaintenanceRunEntity;
import xiaozhi.modules.maintenance.event.MaintenanceKnowledgeSourceApprovedEvent;
import xiaozhi.modules.memory.dao.MemoryFactDao;
import xiaozhi.modules.memory.dto.MemoryFactDTO;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.service.MemoryService;
import xiaozhi.modules.security.user.SecurityUser;

@Service
@AllArgsConstructor
public class MaintenanceProposalService {
    private final MaintenanceProposalDao proposalDao;
    private final KnowledgeSourceService knowledgeSourceService;
    private final MemoryFactDao memoryFactDao;
    private final MemoryService memoryService;
    private final MaintenanceResourceAccessService resourceAccessService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final MaintenanceRunEventService eventService;

    public List<MaintenanceProposalEntity> list(String resourceType, String resourceId) {
        return proposalDao.selectList(new QueryWrapper<MaintenanceProposalEntity>()
                .eq("resource_type", resourceType).eq("resource_id", resourceId)
                .orderByDesc("created_at").last("LIMIT 100"));
    }

    public List<MaintenanceProposalEntity> listConversation(String conversationId) {
        return proposalDao.listByConversation(conversationId);
    }

    public MaintenanceProposalEntity get(String proposalId) {
        MaintenanceProposalEntity proposal = proposalDao.selectById(proposalId);
        if (proposal == null) throw new RenException("维护提案不存在");
        return proposal;
    }

    public int persist(MaintenancePolicyEntity policy, MaintenanceRunEntity run,
            HarnessMaintenanceResponse response) {
        if (response == null || response.getProposals() == null) return 0;
        int accepted = 0;
        for (Map<String, Object> raw : response.getProposals()) {
            if (accepted >= policy.getMaxProposals()) break;
            MaintenanceProposalEntity proposal = validate(policy, run, raw);
            if (proposal == null || isDuplicate(proposal)) continue;
            boolean auto = "KNOWLEDGE".equals(policy.getResourceType())
                    && "ADD_SOURCE".equals(proposal.getActionType())
                    && "DISCOVERY_AUTO".equals(policy.getMode())
                    && isTrusted(proposal.getSourceUri(), readList(policy.getTrustedDomainsJson()));
            if (auto) proposal.setStatus("APPLYING");
            proposalDao.insert(proposal);
            accepted++;
            if (auto) autoApply(proposal, policy.getCreator());
        }
        return accepted;
    }

    @Transactional(rollbackFor = Exception.class)
    public MaintenanceProposalEntity approve(String proposalId) {
        MaintenanceProposalEntity proposal = requirePending(proposalId);
        claimPending(proposalId);
        String sourceId = apply(proposal, SecurityUser.getUserId());
        proposal.setStatus(sourceId == null ? "APPLIED" : "APPLYING");
        proposal.setReviewer(SecurityUser.getUserId());
        proposal.setReviewedAt(new Date());
        proposal.setUpdatedAt(new Date());
        proposalDao.updateById(proposal);
        publishKnowledgeApproval(proposal, sourceId);
        if ("KNOWLEDGE".equals(proposal.getResourceType()) && "REMOVE_SOURCE".equals(proposal.getActionType())) {
            eventService.record(proposal.getRunId(), "PROPOSAL", "SUCCESS",
                    "《" + StringUtils.defaultIfBlank(proposal.getTitle(), "旧网页来源") + "》已从知识库移除");
        }
        return proposal;
    }

    @Transactional(rollbackFor = Exception.class)
    public MaintenanceProposalEntity reject(String proposalId) {
        MaintenanceProposalEntity proposal = requirePending(proposalId);
        claimPending(proposalId);
        proposal.setStatus("REJECTED");
        proposal.setReviewer(SecurityUser.getUserId());
        proposal.setReviewedAt(new Date());
        proposal.setUpdatedAt(new Date());
        proposalDao.updateById(proposal);
        eventService.record(proposal.getRunId(), "PROPOSAL", "INFO",
                "候选《" + StringUtils.defaultIfBlank(proposal.getTitle(), proposal.getActionType()) + "》已被拒绝");
        return proposal;
    }

    public void recoverInterrupted(Date stale, Date now) {
        proposalDao.update(null, new UpdateWrapper<MaintenanceProposalEntity>()
                .eq("status", "APPLYING").lt("updated_at", stale)
                .set("status", "FAILED").set("error", "提案应用进程中断").set("updated_at", now));
    }

    private MaintenanceProposalEntity validate(MaintenancePolicyEntity policy, MaintenanceRunEntity run,
            Map<String, Object> raw) {
        String action = text(raw.get("actionType"), 24);
        MaintenanceProposalEntity proposal = base(policy, run, action, raw);
        if ("KNOWLEDGE".equals(policy.getResourceType())) {
            if (!Set.of("ADD_SOURCE", "REMOVE_SOURCE").contains(action)) return null;
            if ("ADD_SOURCE".equals(action)) {
                String uri = text(raw.get("sourceUri"), 2048);
                PublicUrlGuard.requirePublicHttpUrl(uri);
                if (knowledgeSourceService.list(policy.getResourceId()).stream()
                        .anyMatch(item -> StringUtils.equalsIgnoreCase(item.getSourceUri(), uri))) return null;
                proposal.setSourceUri(uri);
                proposal.setTitle(text(raw.get("title"), 255));
            } else {
                String targetId = text(raw.get("targetId"), 64);
                var existing = knowledgeSourceService.list(policy.getResourceId()).stream()
                        .filter(item -> item.getId().equals(targetId)).findFirst().orElse(null);
                if (existing == null) return null;
                proposal.setTargetId(targetId);
                proposal.setSourceUri(existing.getSourceUri());
                proposal.setTitle(existing.getName());
            }
            proposal.setPayloadJson("{}");
            return proposal;
        }
        if (!Set.of("UPDATE", "ARCHIVE", "DELETE").contains(action)) return null;
        String targetId = text(raw.get("targetId"), 64);
        MemoryFactEntity fact = memoryFactDao.selectOne(new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", policy.getResourceId()).eq("memory_id", targetId).eq("status", "active"));
        if (fact == null) return null;
        proposal.setTargetId(targetId);
        if ("UPDATE".equals(action)) {
            String proposedText = text(raw.get("proposedText"), 2000);
            if (StringUtils.isBlank(proposedText) || proposedText.equals(fact.getCanonicalText())) return null;
            proposal.setPayloadJson(writeJson(Map.of("canonicalText", proposedText)));
        } else proposal.setPayloadJson("{}");
        return proposal;
    }

    private MaintenanceProposalEntity base(MaintenancePolicyEntity policy, MaintenanceRunEntity run,
            String action, Map<String, Object> raw) {
        MaintenanceProposalEntity proposal = new MaintenanceProposalEntity();
        proposal.setRunId(run.getId());
        proposal.setPolicyId(policy.getId());
        proposal.setWorkspaceId(policy.getWorkspaceId());
        proposal.setResourceType(policy.getResourceType());
        proposal.setResourceId(policy.getResourceId());
        proposal.setActionType(action);
        proposal.setRationale(text(raw.get("rationale"), 1000));
        if (StringUtils.isBlank(proposal.getRationale())) throw new RenException("维护提案缺少理由");
        proposal.setEvidenceJson(writeJson(cleanEvidence(raw.get("evidence"))));
        proposal.setStatus("PENDING");
        proposal.setCreatedAt(new Date());
        proposal.setUpdatedAt(new Date());
        return proposal;
    }

    private void autoApply(MaintenanceProposalEntity proposal, Long actorId) {
        try {
            String sourceId = apply(proposal, actorId);
            proposal.setStatus(sourceId == null ? "APPLIED" : "APPLYING");
            proposal.setReviewer(actorId);
            proposal.setReviewedAt(new Date());
            proposal.setUpdatedAt(new Date());
            proposalDao.updateById(proposal);
            publishKnowledgeApproval(proposal, sourceId);
            return;
        } catch (Exception ex) {
            proposal.setStatus("FAILED");
            proposal.setError(safeError(ex));
        }
        proposal.setUpdatedAt(new Date());
        proposalDao.updateById(proposal);
    }

    private String apply(MaintenanceProposalEntity proposal, Long actorId) {
        String workspace = resourceAccessService.resolveWorkspace(proposal.getResourceType(), proposal.getResourceId());
        if (!proposal.getWorkspaceId().equals(workspace)) throw new RenException("资源归属已变化，旧维护提案不能应用");
        if ("KNOWLEDGE".equals(proposal.getResourceType())) {
            if ("REMOVE_SOURCE".equals(proposal.getActionType())) {
                knowledgeSourceService.delete(proposal.getResourceId(), proposal.getTargetId());
                return null;
            }
            PublicUrlGuard.requirePublicHttpUrl(proposal.getSourceUri());
            KnowledgeSourceRequest request = new KnowledgeSourceRequest();
            request.setName(StringUtils.abbreviate(
                    StringUtils.defaultIfBlank(proposal.getTitle(), URI.create(proposal.getSourceUri()).getHost()), 100));
            request.setSourceType("WEB_PAGE");
            request.setSourceUri(proposal.getSourceUri());
            request.setRefreshIntervalMinutes(60);
            request.setEnabled(true);
            String sourceId = knowledgeSourceService.createManaged(proposal.getResourceId(), request, actorId).getId();
            proposal.setTargetId(sourceId);
            return sourceId;
        } else if ("UPDATE".equals(proposal.getActionType())) {
            MemoryFactDTO dto = new MemoryFactDTO();
            dto.setCanonicalText(text(readMap(proposal.getPayloadJson()).get("canonicalText"), 2000));
            memoryService.updateFact(proposal.getResourceId(), proposal.getTargetId(), dto,
                    "harness_approved", String.valueOf(actorId));
        } else if ("ARCHIVE".equals(proposal.getActionType())) {
            memoryService.archiveFact(proposal.getResourceId(), proposal.getTargetId(),
                    "harness_approved", String.valueOf(actorId));
        } else if ("DELETE".equals(proposal.getActionType())) {
            memoryService.deleteFact(proposal.getResourceId(), proposal.getTargetId(),
                    "harness_approved", String.valueOf(actorId));
        } else throw new RenException("不支持的维护提案动作");
        return null;
    }

    private void publishKnowledgeApproval(MaintenanceProposalEntity proposal, String sourceId) {
        if (sourceId == null) return;
        eventPublisher.publishEvent(new MaintenanceKnowledgeSourceApprovedEvent(
                proposal.getId(), proposal.getRunId(), proposal.getResourceId(), sourceId));
    }

    private MaintenanceProposalEntity requirePending(String id) {
        MaintenanceProposalEntity proposal = get(id);
        if (!"PENDING".equals(proposal.getStatus())) throw new RenException("该维护提案已处理");
        return proposal;
    }

    private void claimPending(String id) {
        int changed = proposalDao.update(null, new UpdateWrapper<MaintenanceProposalEntity>()
                .eq("id", id).eq("status", "PENDING").set("status", "APPLYING").set("updated_at", new Date()));
        if (changed != 1) throw new RenException("该维护提案正在处理或已处理");
    }

    private boolean isDuplicate(MaintenanceProposalEntity proposal) {
        QueryWrapper<MaintenanceProposalEntity> query = new QueryWrapper<MaintenanceProposalEntity>()
                .eq("resource_type", proposal.getResourceType()).eq("resource_id", proposal.getResourceId())
                .eq("action_type", proposal.getActionType()).in("status", "PENDING", "APPLYING", "APPLIED");
        if ("KNOWLEDGE".equals(proposal.getResourceType())) {
            if ("REMOVE_SOURCE".equals(proposal.getActionType())) query.eq("target_id", proposal.getTargetId());
            else query.eq("source_uri", proposal.getSourceUri());
        }
        else query.eq("target_id", proposal.getTargetId());
        return proposalDao.selectCount(query) > 0;
    }

    private boolean isTrusted(String uri, List<String> domains) {
        String host = StringUtils.lowerCase(URI.create(uri).getHost(), Locale.ROOT);
        return domains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
    }

    private List<String> cleanEvidence(Object raw) {
        if (!(raw instanceof List<?> values)) return List.of();
        return values.stream().map(String::valueOf).map(item -> StringUtils.abbreviate(item, 500)).limit(10).toList();
    }

    private List<String> readList(String json) {
        if (StringUtils.isBlank(json)) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<List<String>>() { }); }
        catch (Exception ex) { return List.of(); }
    }

    private Map<String, Object> readMap(String json) {
        try { return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { }); }
        catch (Exception ex) { throw new RenException("维护提案参数损坏"); }
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { throw new RenException("维护提案序列化失败"); }
    }

    private String text(Object value, int max) {
        return StringUtils.abbreviate(StringUtils.trimToEmpty(value == null ? null : String.valueOf(value)), max);
    }

    private String safeError(Throwable throwable) {
        String message = throwable instanceof RenException ? throwable.getMessage() : "提案应用失败";
        return StringUtils.abbreviate(StringUtils.defaultIfBlank(message, "提案应用失败")
                .replaceAll("(?i)(api[_-]?key|authorization|token|secret)\\s*[:=]\\s*\\S+", "$1=***"), 500);
    }
}
