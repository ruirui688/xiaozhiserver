package xiaozhi.modules.knowledge.source.event;

/** Safe operational progress emitted by the connector pipeline; never contains page content or credentials. */
public record KnowledgeSourceSyncProgressEvent(
        String datasetId,
        String sourceId,
        String syncRunId,
        String status,
        String message,
        String error,
        String documentId,
        Integer chunkCount) { }
