package xiaozhi.modules.maintenance.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_maintenance_proposal")
public class MaintenanceProposalEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String runId;
    private String policyId;
    private String workspaceId;
    private String resourceType;
    private String resourceId;
    private String actionType;
    private String targetId;
    private String sourceUri;
    private String title;
    private String rationale;
    private String payloadJson;
    private String evidenceJson;
    private String status;
    private Long reviewer;
    private Date reviewedAt;
    private String error;
    private Date createdAt;
    private Date updatedAt;
}
