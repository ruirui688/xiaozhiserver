package xiaozhi.modules.memory.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_memory_session_task")
public class MemorySessionTaskEntity {
    @TableId(type = IdType.INPUT)
    private String taskId;
    private String agentId;
    private String deviceId;
    private String sessionId;
    private String requestHash;
    private String payloadJson;
    private String status;
    private Integer attempts;
    private Integer factsChanged;
    private String lastError;
    private String leaseToken;
    private Date leaseUntil;
    private Date nextAttemptAt;
    private Date createdAt;
    private Date updatedAt;
}
