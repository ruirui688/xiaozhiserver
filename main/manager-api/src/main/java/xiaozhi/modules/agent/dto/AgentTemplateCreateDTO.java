package xiaozhi.modules.agent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AgentTemplateCreateDTO {
    @NotBlank(message = "智能体名称不能为空")
    @Size(max = 64, message = "智能体名称不能超过64个字符")
    private String agentName;

    @NotNull
    @Min(1)
    private Integer expectedVersion;
}
