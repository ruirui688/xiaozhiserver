package xiaozhi.modules.workspace.vo;

import java.util.Date;

import lombok.Data;

@Data
public class WorkspaceMemberVO {
    private Long userId;
    private String username;
    private String role;
    private Integer status;
    private Date joinedAt;
}
