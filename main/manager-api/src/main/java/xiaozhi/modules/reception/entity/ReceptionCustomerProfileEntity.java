package xiaozhi.modules.reception.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_reception_customer_profile")
public class ReceptionCustomerProfileEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String personId;
    private String customerStage;
    private String companyName;
    private String hobbiesJson;
    private String foodPreferencesJson;
    private String seatPreferencesJson;
    private String receptionNotes;
    private String sensitiveNotes;
    private String consentScope;
    private Integer visitCount;
    private Date firstVisitAt;
    private Date lastVisitAt;
    private Date lastConfirmedAt;
    private Integer status;
    private Long creator;
    private Long updater;
    private Date createDate;
    private Date updateDate;
}
