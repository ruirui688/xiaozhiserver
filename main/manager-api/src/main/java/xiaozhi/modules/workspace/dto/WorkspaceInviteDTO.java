package xiaozhi.modules.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WorkspaceInviteDTO {
    @NotBlank(message = "用户名不能为空")
    private String username;
    private String role;
}
