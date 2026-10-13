package xiaozhi.modules.workspace.dao;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import xiaozhi.common.dao.BaseDao;
import xiaozhi.modules.workspace.entity.WorkspaceInvitationEntity;

@Mapper
public interface WorkspaceInvitationDao extends BaseDao<WorkspaceInvitationEntity> {
    @Select("SELECT * FROM sys_workspace_invitation WHERE id = #{invitationId} LIMIT 1 FOR UPDATE")
    WorkspaceInvitationEntity selectByIdForUpdate(@Param("invitationId") String invitationId);

    @Select("SELECT * FROM sys_workspace_invitation "
            + "WHERE workspace_id = #{workspaceId} AND invitee_user_id = #{inviteeUserId} "
            + "AND invitation_type = 'TARGETED' AND status = 'PENDING' AND expires_at > #{now} "
            + "ORDER BY created_at DESC LIMIT 1 FOR UPDATE")
    WorkspaceInvitationEntity selectPendingTargetedForUpdate(
            @Param("workspaceId") String workspaceId,
            @Param("inviteeUserId") Long inviteeUserId,
            @Param("now") Date now);

    @Select("SELECT * FROM sys_workspace_invitation "
            + "WHERE workspace_id = #{workspaceId} AND status = 'PENDING' AND expires_at > #{now} "
            + "ORDER BY created_at, id FOR UPDATE")
    List<WorkspaceInvitationEntity> selectPendingByWorkspaceForUpdate(
            @Param("workspaceId") String workspaceId,
            @Param("now") Date now);

    /**
     * Discover every workspace whose pending invitation must be revoked during
     * account deletion. This intentionally does not lock invitation rows: callers
     * first lock the user, then lock the returned workspaces in sorted order, and
     * only then update invitations to preserve the global lock hierarchy.
     */
    @Select("SELECT DISTINCT workspace_id FROM sys_workspace_invitation "
            + "WHERE status = 'PENDING' AND (creator = #{userId} OR "
            + "(invitation_type = 'TARGETED' AND invitee_user_id = #{userId})) "
            + "ORDER BY workspace_id")
    List<String> selectPendingWorkspaceIdsRelatedToUser(@Param("userId") Long userId);

    /**
     * Discover workspaces containing pending invitations created by a user.
     * Callers lock all affected users first and all returned workspaces second.
     */
    @Select("SELECT DISTINCT workspace_id FROM sys_workspace_invitation "
            + "WHERE status = 'PENDING' AND creator = #{userId} ORDER BY workspace_id")
    List<String> selectPendingWorkspaceIdsCreatedByUser(@Param("userId") Long userId);
}
