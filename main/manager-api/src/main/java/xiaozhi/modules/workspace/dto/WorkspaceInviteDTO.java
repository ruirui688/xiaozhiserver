package xiaozhi.modules.workspace.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WorkspaceInviteDTO {
    @Size(max = 50, message = "用户名长度不能超过50个字符")
    private String username;
    private String role;
}
