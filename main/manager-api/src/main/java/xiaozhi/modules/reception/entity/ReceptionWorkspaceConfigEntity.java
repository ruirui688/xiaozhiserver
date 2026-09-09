package xiaozhi.modules.reception.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_reception_workspace_config")
public class ReceptionWorkspaceConfigEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String companyName;
    private String companyIntroText;
    private String faqJson;
    private String productsJson;
    private String seatsJson;
    private String stageShowJson;
    private String serviceContactText;
    private Long configVersion;
    private Integer status;
    private Long creator;
    private Long updater;
    private Date createDate;
    private Date updateDate;
}
