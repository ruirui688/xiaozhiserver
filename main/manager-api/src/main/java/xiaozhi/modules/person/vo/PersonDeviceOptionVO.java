package xiaozhi.modules.person.vo;

import java.util.Date;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PersonDeviceOptionVO {
    private String id;
    private String name;
    private String alias;
    private String macAddress;
    private String board;
    private String appVersion;
    private String agentId;
    private String agentName;
    private Date lastConnectedAt;
}
