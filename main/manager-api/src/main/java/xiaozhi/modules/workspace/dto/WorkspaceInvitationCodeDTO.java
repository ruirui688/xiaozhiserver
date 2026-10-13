package xiaozhi.modules.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WorkspaceInvitationCodeDTO {
    @NotBlank(message = "邀请码不能为空")
    @Size(min = 20, max = 63, message = "邀请码格式不正确")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "邀请码格式不正确")
    private String code;
}
