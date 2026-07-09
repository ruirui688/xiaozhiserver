package xiaozhi.modules.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WorkspaceCreateDTO {
    @NotBlank(message = "工作空间名称不能为空")
    private String name;
}
