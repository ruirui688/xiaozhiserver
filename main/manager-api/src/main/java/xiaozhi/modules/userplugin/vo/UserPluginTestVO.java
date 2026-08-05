package xiaozhi.modules.userplugin.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserPluginTestVO {
    private String providerCode;
    private boolean success;
    private String message;
}
