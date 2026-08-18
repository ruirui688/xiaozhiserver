package xiaozhi.modules.reception.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_reception_appointment")
public class ReceptionAppointmentEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String personId;
    private Date startAt;
    private Date endAt;
    private Integer partySize;
    private String status;
    private String seatRequest;
    private String notes;
    private Long creator;
    private Long updater;
    private Date createDate;
    private Date updateDate;
}
