package xiaozhi.modules.maintenance.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_maintenance_run")
public class MaintenanceRunEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String policyId;
    private String conversationId;
    private String workspaceId;
    private String resourceType;
    private String resourceId;
    private String triggerType;
    private String userInstruction;
    private String status;
    private Integer proposalCount;
    private Integer inputChars;
    private Integer outputChars;
    private String error;
    private Date startedAt;
    private Date finishedAt;
    private Date createdAt;
}
