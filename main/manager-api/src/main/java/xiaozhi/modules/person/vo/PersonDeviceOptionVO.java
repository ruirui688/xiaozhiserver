package xiaozhi.modules.person.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PersonDeviceOptionVO {
    private String id;
    private String name;
    private String macAddress;
    private String board;
}
