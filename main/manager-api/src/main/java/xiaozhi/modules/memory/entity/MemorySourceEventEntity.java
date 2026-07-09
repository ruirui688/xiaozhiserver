package xiaozhi.modules.memory.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_memory_source_event")
public class MemorySourceEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String agentId;
    private Long userId;
    private String workspaceId;
    private String macAddress;
    private String sessionId;
    private String scopeType;
    private String scopeId;
    private String speakerId;
    private String eventType;
    private Integer chatType;
    private String contentText;
    @TableField("content_json")
    private String contentJson;
    private String piiLevel;
    private Date sourceTimestamp;
    private Date createdAt;
}
