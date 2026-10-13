package xiaozhi.modules.workspace.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import xiaozhi.common.dao.BaseDao;
import xiaozhi.modules.workspace.entity.WorkspaceMemberEntity;

@Mapper
public interface WorkspaceMemberDao extends BaseDao<WorkspaceMemberEntity> {
    @Select("SELECT * FROM sys_workspace_member "
            + "WHERE workspace_id = #{workspaceId} AND user_id = #{userId} LIMIT 1 FOR UPDATE")
    WorkspaceMemberEntity selectByWorkspaceAndUserForUpdate(
            @Param("workspaceId") String workspaceId,
            @Param("userId") Long userId);

    @Select("SELECT * FROM sys_workspace_member "
            + "WHERE workspace_id = #{workspaceId} AND status = 1 ORDER BY id FOR UPDATE")
    List<WorkspaceMemberEntity> selectActiveByWorkspaceForUpdate(@Param("workspaceId") String workspaceId);

    @Select("SELECT * FROM sys_workspace_member "
            + "WHERE user_id = #{userId} AND status = 1 ORDER BY workspace_id FOR UPDATE")
    List<WorkspaceMemberEntity> selectActiveByUserForUpdate(@Param("userId") Long userId);
}
