package xiaozhi.modules.person.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DeviceFaceRecognitionActionVO {
    private String eventId;
    private String personId;
    private String personName;
    private String description;
    private String actionType;
    private String text;
    private boolean duplicate;
}
