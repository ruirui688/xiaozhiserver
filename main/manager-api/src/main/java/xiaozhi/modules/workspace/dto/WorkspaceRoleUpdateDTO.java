package xiaozhi.modules.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WorkspaceRoleUpdateDTO {
    @NotBlank(message = "角色不能为空")
    private String role;
}
