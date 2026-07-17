package xiaozhi.modules.userplugin.dto;

import java.util.Map;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UserPluginConfigSaveDTO {
    @NotNull(message = "插件配置不能为空")
    private Map<String, Object> config;
}
