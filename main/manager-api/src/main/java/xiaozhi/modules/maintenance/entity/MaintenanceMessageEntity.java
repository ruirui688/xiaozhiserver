package xiaozhi.modules.maintenance.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_maintenance_message")
public class MaintenanceMessageEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String conversationId;
    private String runId;
    private String role;
    private String content;
    private Date createdAt;
}
