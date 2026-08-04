package xiaozhi.modules.person.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ServerFaceRecognitionCapabilityVO {
    private boolean supported;
    private String mode;
    private String message;
}
