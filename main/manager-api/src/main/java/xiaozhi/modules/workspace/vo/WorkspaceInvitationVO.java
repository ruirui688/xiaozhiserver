package xiaozhi.modules.workspace.vo;

import java.util.Date;

import lombok.Data;

@Data
public class WorkspaceInvitationVO {
    private String id;
    private String code;
    private String workspaceId;
    private String workspaceName;
    private String inviteeUsername;
    private String role;
    private String status;
    private Date expiresAt;
}
