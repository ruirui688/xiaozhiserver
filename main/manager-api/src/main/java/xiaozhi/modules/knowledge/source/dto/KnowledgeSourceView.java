package xiaozhi.modules.knowledge.source.dto;

import java.util.Date;

import lombok.Data;

@Data
public class KnowledgeSourceView {
    private String id;
    private String datasetId;
    private String name;
    private String sourceType;
    private String sourceUri;
    private Integer refreshIntervalMinutes;
    private Boolean enabled;
    private String syncStatus;
    private Integer currentRevision;
    private Date lastSyncAt;
    private Date lastSuccessAt;
    private Date nextSyncAt;
    private String lastError;
    private Integer consecutiveFailures;
    private Date createdAt;
    private Date updatedAt;
}
