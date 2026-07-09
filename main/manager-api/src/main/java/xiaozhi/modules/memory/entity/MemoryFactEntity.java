package xiaozhi.modules.memory.entity;

import java.math.BigDecimal;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_memory_fact")
public class MemoryFactEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String memoryId;
    private String agentId;
    private Long userId;
    private String workspaceId;
    private String macAddress;
    private String scopeType;
    private String scopeId;
    private String memoryType;
    private String category;
    private String canonicalText;
    @TableField("payload")
    private String payload;
    private BigDecimal importance;
    private BigDecimal confidence;
    private String sensitivity;
    private String confirmationState;
    private String status;
    private Date validFrom;
    private Date validTo;
    private Date firstSeenAt;
    private Date lastSeenAt;
    private Date expiresAt;
    private Date archivedAt;
    private String sourceEventId;
    private String sourceHash;
    private String extractorVersion;
    @TableField("embedding_json")
    private String embeddingJson;
    @TableField("importance_dimensions")
    private String importanceDimensions;
    @TableField("memory_tier")
    private String memoryTier;
    private Date createdAt;
    private Date updatedAt;
}
