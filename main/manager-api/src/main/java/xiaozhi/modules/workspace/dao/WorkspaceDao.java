package xiaozhi.modules.workspace.dao;

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
}
