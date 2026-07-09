package xiaozhi.modules.memory.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_memory_revision")
public class MemoryRevisionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String revisionId;
    private String memoryId;
    private String agentId;
    private String workspaceId;
    private String revisionAction;
    @TableField("before_payload")
    private String beforePayload;
    @TableField("after_payload")
    private String afterPayload;
    private String actorType;
    private String actorId;
    private String reason;
    private Date createdAt;
}
