package xiaozhi.modules.device.vo;

import java.util.Date;
import java.util.List;

import lombok.Data;

@Data
public class DeviceSceneVO {
    private String id;
    private String deviceId;
    private String agentId;
    private String agentName;
    private String sceneName;
    private List<String> switchAliases;
    private Integer enabled;
    private Integer sort;
    private String templateId;
    private Integer templateVersion;
    private boolean current;
    private Long configVersion;
    private String switchRequestId;
    private String switchStatus;
    private String failureCode;
    private Date requestedAt;
    private Date appliedAt;
}
