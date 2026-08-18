package xiaozhi.modules.reception.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_reception_visit_event")
public class ReceptionVisitEventEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String deviceId;
    private String deviceEventId;
    private String groupSessionId;
    private Long sceneEpoch;
    private String eventType;
    private String selectedPersonIdsJson;
    private Integer presentCount;
    private String decisionReason;
    private Date capturedAt;
    private Date decisionAt;
    private Date createDate;
}
