package xiaozhi.modules.device.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DeviceSceneCreateDTO {
    @NotBlank(message = "场景智能体不能为空")
    private String agentId;
    @NotBlank(message = "场景名称不能为空")
    @Size(max = 64, message = "场景名称不能超过64个字符")
    private String sceneName;
    @Size(max = 10, message = "场景切换别名不能超过10个")
    private List<@Size(max = 32, message = "单个切换别名不能超过32个字符") String> switchAliases;
    private Integer enabled;
    private Integer sort;
    @Size(max = 32, message = "模板ID不能超过32个字符")
    private String templateId;
    private Integer templateVersion;
}
