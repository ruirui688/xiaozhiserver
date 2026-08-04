package xiaozhi.modules.person.entity;

import java.math.BigDecimal;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_person_recognition_event")
public class PersonRecognitionEventEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String deviceId;
    private String personId;
    private String deviceEventId;
    private BigDecimal confidence;
    private Date occurredAt;
    private String actionType;
    @TableField(fill = FieldFill.INSERT)
    private Date createDate;
}
