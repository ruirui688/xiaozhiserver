package xiaozhi.modules.maintenance.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_maintenance_conversation")
public class MaintenanceConversationEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String policyId;
    private String workspaceId;
    private String resourceType;
    private String resourceId;
    private String originType;
    private String title;
    private Integer archived;
    private Long creator;
    private Date lastMessageAt;
    private Date deletedAt;
    private Date createdAt;
    private Date updatedAt;
}
