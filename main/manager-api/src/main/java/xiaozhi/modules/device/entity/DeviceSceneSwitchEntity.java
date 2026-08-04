package xiaozhi.modules.device.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_device_scene_switch")
public class DeviceSceneSwitchEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String deviceId;
    private String bindingId;
    private String fromAgentId;
    private String toAgentId;
    private String switchRequestId;
    private Long configVersion;
    private String status;
    private String failureCode;
    private Long requestedBy;
    private Date requestedAt;
    private Date appliedAt;
    private Date updatedAt;
}
