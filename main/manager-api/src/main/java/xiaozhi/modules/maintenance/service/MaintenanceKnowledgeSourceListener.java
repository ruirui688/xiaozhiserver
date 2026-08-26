package xiaozhi.modules.maintenance.service;

import java.util.Date;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.AllArgsConstructor;
import xiaozhi.modules.knowledge.source.event.KnowledgeSourceSyncProgressEvent;
import xiaozhi.modules.knowledge.source.service.KnowledgeSourceSyncService;
import xiaozhi.modules.maintenance.dao.MaintenanceProposalDao;
import xiaozhi.modules.maintenance.entity.MaintenanceProposalEntity;
import xiaozhi.modules.maintenance.event.MaintenanceKnowledgeSourceApprovedEvent;

/** Bridges reviewed discovery proposals to the connector and reflects its durable outcome. */
@Component
@AllArgsConstructor
public class MaintenanceKnowledgeSourceListener {
    private final KnowledgeSourceSyncService sourceSyncService;
    private final MaintenanceProposalDao proposalDao;
    private final MaintenanceRunEventService eventService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void startApprovedSource(MaintenanceKnowledgeSourceApprovedEvent event) {
        MaintenanceProposalEntity proposal = proposalDao.selectById(event.proposalId());
        String subject = subject(proposal);
        eventService.record(event.runId(), "PROPOSAL", "INFO", subject + "已批准，立即开始抓取网页来源",
                Map.of("proposalId", event.proposalId(), "sourceId", event.sourceId()));
        try {
            sourceSyncService.trigger(event.datasetId(), event.sourceId(), "APPROVAL");
        } catch (Exception ex) {
            failProposal(event.proposalId(), safeError(ex));
            eventService.record(event.runId(), "SOURCE_SYNC", "ERROR", safeError(ex));
        }
    }

    @EventListener
    public void reflectSyncProgress(KnowledgeSourceSyncProgressEvent event) {
        MaintenanceProposalEntity proposal = proposalDao.selectOne(new QueryWrapper<MaintenanceProposalEntity>()
                .eq("resource_type", "KNOWLEDGE").eq("resource_id", event.datasetId())
                .eq("target_id", event.sourceId()).in("status", "APPLYING", "APPLIED", "FAILED")
                .orderByDesc("created_at").last("LIMIT 1"));
        if (proposal == null) return;
        String level = switch (event.status()) {
            case "INDEXED", "UNCHANGED" -> "SUCCESS";
            case "FAILED" -> "ERROR";
            default -> "INFO";
        };
        eventService.record(proposal.getRunId(), "SOURCE_SYNC", level,
                subject(proposal) + StringUtils.defaultIfBlank(event.error(), event.message()), Map.of(
                        "sourceId", event.sourceId(),
                        "syncRunId", event.syncRunId(),
                        "status", event.status(),
                        "documentId", StringUtils.defaultString(event.documentId()),
                        "chunkCount", event.chunkCount() == null ? 0 : event.chunkCount()));
        if ("INDEXED".equals(event.status()) || "UNCHANGED".equals(event.status())) {
            proposalDao.update(null, new UpdateWrapper<MaintenanceProposalEntity>()
                    .set("status", "APPLIED").set("error", null).set("updated_at", new Date())
                    .eq("id", proposal.getId()).eq("status", "APPLYING"));
        } else if ("FAILED".equals(event.status())) {
            failProposal(proposal.getId(), StringUtils.defaultIfBlank(event.error(), "网页同步失败"));
        }
    }

    private void failProposal(String proposalId, String error) {
        proposalDao.update(null, new UpdateWrapper<MaintenanceProposalEntity>()
                .set("status", "FAILED").set("error", error).set("updated_at", new Date())
                .eq("id", proposalId).eq("status", "APPLYING"));
    }

    private String safeError(Throwable throwable) {
        String message = StringUtils.defaultIfBlank(throwable.getMessage(), "网页同步启动失败")
                .replaceAll("(?i)Bearer\\s+[^\\s,;，；]+", "Bearer ***")
                .replaceAll("(?i)(api[_-]?key|authorization|token|secret|password)\\s*[:=]\\s*(?:Bearer\\s+)?[^\\s,;，；]+", "$1=***");
        return StringUtils.abbreviate(message, 500);
    }

    private String subject(MaintenanceProposalEntity proposal) {
        if (proposal == null || StringUtils.isBlank(proposal.getTitle())) return "网页来源：";
        return "《" + StringUtils.abbreviate(proposal.getTitle(), 80) + "》：";
    }
}
