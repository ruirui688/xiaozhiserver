package xiaozhi.modules.knowledge.source.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_rag_knowledge_sync_run")
public class KnowledgeSyncRunEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String sourceId;
    private String datasetId;
    private String triggerType;
    private String status;
    private Integer revision;
    private Long bytesFetched;
    private Integer documentsUpdated;
    private Integer documentsSkipped;
    private String error;
    private Date startedAt;
    private Date finishedAt;
}
