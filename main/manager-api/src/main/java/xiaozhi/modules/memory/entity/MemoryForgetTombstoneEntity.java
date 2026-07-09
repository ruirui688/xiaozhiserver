package xiaozhi.modules.memory.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_memory_forget_tombstone")
public class MemoryForgetTombstoneEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tombstoneId;
    private String agentId;
    private Long userId;
    private String workspaceId;
    private String scopeType;
    private String scopeId;
    private String patternText;
    private String reason;
    private String status;
    private String sourceEventId;
    private Date createdAt;
    private Date updatedAt;
}
