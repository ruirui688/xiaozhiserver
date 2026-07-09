package xiaozhi.modules.memory.entity;

import java.math.BigDecimal;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_memory_candidate")
public class MemoryCandidateEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String candidateId;
    private String agentId;
    private Long userId;
    private String workspaceId;
    private String scopeType;
    private String scopeId;
    private String memoryType;
    private String category;
    private String canonicalText;
    @TableField("payload")
    private String payload;
    private BigDecimal confidence;
    private BigDecimal importance;
    private String sensitivity;
    private String status;
    private String sourceEventId;
    private String extractorVersion;
    private Date createdAt;
    private Date updatedAt;
}
