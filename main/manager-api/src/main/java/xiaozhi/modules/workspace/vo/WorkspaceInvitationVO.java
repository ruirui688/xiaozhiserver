package xiaozhi.modules.workspace.vo;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkspaceInvitationVO {
    private String id;
    private String code;
    private String workspaceId;
    private String workspaceName;
    private String inviteeUsername;
    private String invitationType;
    private Boolean linkInvitation;
    private String role;
    private String status;
    private Date expiresAt;
}
