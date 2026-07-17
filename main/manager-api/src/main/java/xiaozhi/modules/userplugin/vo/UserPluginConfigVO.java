package xiaozhi.modules.userplugin.vo;

import java.util.Map;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserPluginConfigVO {
    private String providerCode;
    private String pluginName;
    private boolean configured;
    private boolean secretConfigured;
    private Map<String, Object> config;
}
