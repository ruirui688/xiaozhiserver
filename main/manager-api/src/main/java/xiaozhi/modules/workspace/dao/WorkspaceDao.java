package xiaozhi.modules.workspace.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import xiaozhi.common.dao.BaseDao;
import xiaozhi.modules.workspace.entity.WorkspaceEntity;

@Mapper
public interface WorkspaceDao extends BaseDao<WorkspaceEntity> {
    /** 锁定工作空间行，串行化智能体数量额度检查。 */
    @Select("SELECT id FROM sys_workspace WHERE id = #{workspaceId} FOR UPDATE")
    String lockByIdForAgentQuota(@Param("workspaceId") String workspaceId);

    /** 锁定并返回工作空间当前值，避免 REPEATABLE READ 下继续读取锁前快照。 */
    @Select("SELECT * FROM sys_workspace WHERE id = #{workspaceId} FOR UPDATE")
    WorkspaceEntity lockByIdForInvitation(@Param("workspaceId") String workspaceId);

    /** 删除账号前锁定其固定负责的工作空间；固定负责人不允许删除。 */
    @Select("SELECT * FROM sys_workspace WHERE owner_user_id = #{userId} ORDER BY id FOR UPDATE")
    List<WorkspaceEntity> selectOwnedByUserForUpdate(@Param("userId") Long userId);
}
