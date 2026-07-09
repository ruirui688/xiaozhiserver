package xiaozhi.modules.workspace.vo;

import java.util.Date;

import lombok.Data;

@Data
public class WorkspaceVO {
    private String id;
    private String name;
    private Long ownerUserId;
    private Integer defaultWorkspace;
    private String currentUserRole;
    private Boolean canWrite;
    private Date createdAt;
}
