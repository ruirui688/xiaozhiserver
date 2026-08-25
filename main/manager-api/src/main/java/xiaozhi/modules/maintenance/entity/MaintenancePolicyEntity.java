package xiaozhi.modules.maintenance.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_maintenance_policy")
public class MaintenancePolicyEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String resourceType;
    private String resourceId;
    private String mode;
    private String topicsJson;
    private String trustedDomainsJson;
    private Integer scheduleMinutes;
    private Integer maxProposals;
    private Integer maxMonthlyRuns;
    private Integer enabled;
    private Date lastRunAt;
    private Date nextRunAt;
    private Long creator;
    private Date createdAt;
    private Date updatedAt;
}
