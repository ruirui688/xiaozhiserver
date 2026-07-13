package xiaozhi.modules.memory.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_memory_session_state")
public class MemorySessionStateEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String agentId;
    private String sessionId;
    private Long lastEventId;
    private Long lastUserEventId;
    private Date lastEventAt;
    private Long processedThroughEventId;
    private Long processingThroughEventId;
    private Date processingStartedAt;
    private Date createdAt;
    private Date updatedAt;
}
