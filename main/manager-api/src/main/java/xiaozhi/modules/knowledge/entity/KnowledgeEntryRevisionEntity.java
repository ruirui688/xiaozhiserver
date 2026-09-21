package xiaozhi.modules.knowledge.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("ai_rag_knowledge_entry_revision")
public class KnowledgeEntryRevisionEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String datasetId;
    private String documentId;
    private String entryId;
    private Long version;
    private String content;
    private String question;
    private String answer;
    private Integer enabled;
    private Long creator;
    private Date createdAt;
}
