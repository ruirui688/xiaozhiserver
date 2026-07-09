package xiaozhi.modules.memory.entity;

import java.math.BigDecimal;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_memory_tool_behavior")
public class MemoryToolBehaviorEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String behaviorId;
    private String agentId;
    private Long userId;
    private String workspaceId;
    private String scopeType;
    private String scopeId;
    private String toolName;
    private String scenarioKey;
    @TableField("preferred_params")
    private String preferredParams;
    private Integer successCount;
    private Integer failureCount;
    @TableField("failure_signatures")
    private String failureSignatures;
    private Date lastSuccessAt;
    private Date lastFailureAt;
    private BigDecimal confidence;
    private Date createdAt;
    private Date updatedAt;
}
