package xiaozhi.modules.workspace.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("sys_workspace_invitation")
public class WorkspaceInvitationEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String inviteeUsername;
    private Long inviteeUserId;
    private String invitationType;
    private String role;
    private String code;
    private String tokenHash;
    private String status;
    private Date expiresAt;
    private Date acceptedAt;
    private Long acceptedByUserId;
    @TableField(fill = FieldFill.INSERT)
    private Long creator;
    @TableField(fill = FieldFill.INSERT)
    private Date createdAt;
    @TableField(fill = FieldFill.UPDATE)
    private Long updater;
    @TableField(fill = FieldFill.UPDATE)
    private Date updatedAt;
}
