package xiaozhi.modules.knowledge.source.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_rag_knowledge_source")
public class KnowledgeSourceEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String datasetId;
    private String workspaceId;
    private String name;
    private String sourceType;
    private String sourceUri;
    private Integer refreshIntervalMinutes;
    private Integer enabled;
    private String syncStatus;
    private String etag;
    private String lastModified;
    private String contentHash;
    private String currentDocumentId;
    private Integer currentRevision;
    private Date lastSyncAt;
    private Date lastSuccessAt;
    private Date nextSyncAt;
    private String lastError;
    private Integer consecutiveFailures;
    private Long creator;
    private Date createdAt;
    private Date updatedAt;
}
