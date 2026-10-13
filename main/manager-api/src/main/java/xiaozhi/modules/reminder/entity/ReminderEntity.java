package xiaozhi.modules.reminder.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_device_reminder")
public class ReminderEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String deviceId;
    private String agentId;
    private String title;
    private String spokenText;
    private Date remindAt;
    private String timezone;
    private String recurrenceJson;
    private Date scheduleAnchorAt;
    private Integer scheduleVersion;
    private String status;
    private String source;
    private String sourceRequestId;
    private String requestHash;
    private Integer attemptCount;
    private Date nextAttemptAt;
    private Date lastAttemptAt;
    private Date deliveredAt;
    private String lastErrorCode;
    private String leaseToken;
    private Date leaseUntil;

    @TableField(fill = FieldFill.INSERT)
    private Long creator;

    @TableField(fill = FieldFill.INSERT)
    private Date createDate;

    @TableField(fill = FieldFill.UPDATE)
    private Long updater;

    @TableField(fill = FieldFill.UPDATE)
    private Date updateDate;
}
