package xiaozhi.modules.maintenance.event;

/** Starts connector synchronization only after the approval transaction is durable. */
public record MaintenanceKnowledgeSourceApprovedEvent(
        String proposalId, String runId, String datasetId, String sourceId) { }
