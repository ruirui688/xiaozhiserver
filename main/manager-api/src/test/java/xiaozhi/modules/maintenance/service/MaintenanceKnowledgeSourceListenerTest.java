package xiaozhi.modules.maintenance.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import xiaozhi.modules.knowledge.source.event.KnowledgeSourceSyncProgressEvent;
import xiaozhi.modules.knowledge.source.service.KnowledgeSourceSyncService;
import xiaozhi.modules.maintenance.dao.MaintenanceProposalDao;
import xiaozhi.modules.maintenance.entity.MaintenanceProposalEntity;
import xiaozhi.modules.maintenance.event.MaintenanceKnowledgeSourceApprovedEvent;

class MaintenanceKnowledgeSourceListenerTest {
    @Test
    void approvedKnowledgeProposalStartsConnectorImmediatelyAfterCommit() {
        KnowledgeSourceSyncService syncService = mock(KnowledgeSourceSyncService.class);
        MaintenanceRunEventService eventService = mock(MaintenanceRunEventService.class);
        MaintenanceKnowledgeSourceListener listener = new MaintenanceKnowledgeSourceListener(
                syncService, mock(MaintenanceProposalDao.class), eventService);

        listener.startApprovedSource(new MaintenanceKnowledgeSourceApprovedEvent(
                "proposal-1", "run-1", "dataset-1", "source-1"));

        verify(syncService).trigger("dataset-1", "source-1", "APPROVAL");
        verify(eventService).record(eq("run-1"), eq("PROPOSAL"), eq("INFO"), any(String.class), any());
    }

    @Test
    void proposalBecomesAppliedOnlyAfterDocumentPublication() {
        MaintenanceProposalDao proposalDao = mock(MaintenanceProposalDao.class);
        MaintenanceProposalEntity proposal = new MaintenanceProposalEntity();
        proposal.setId("proposal-1");
        proposal.setRunId("run-1");
        proposal.setStatus("APPLYING");
        when(proposalDao.selectOne(any())).thenReturn(proposal);
        MaintenanceRunEventService eventService = mock(MaintenanceRunEventService.class);
        MaintenanceKnowledgeSourceListener listener = new MaintenanceKnowledgeSourceListener(
                mock(KnowledgeSourceSyncService.class), proposalDao, eventService);

        listener.reflectSyncProgress(new KnowledgeSourceSyncProgressEvent(
                "dataset-1", "source-1", "sync-1", "INDEXED", "已发布", null, "document-1", 7));

        verify(proposalDao).update(eq(null), any());
        verify(eventService).record(eq("run-1"), eq("SOURCE_SYNC"), eq("SUCCESS"), eq("网页来源：已发布"), any());
    }
}
