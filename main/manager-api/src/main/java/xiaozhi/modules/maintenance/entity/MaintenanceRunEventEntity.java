package xiaozhi.modules.maintenance.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_maintenance_run_event")
public class MaintenanceRunEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String runId;
    private String phase;
    private String level;
    private String message;
    private String detailJson;
    private Date createdAt;
}
