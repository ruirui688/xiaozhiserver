package xiaozhi.modules.sys.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import xiaozhi.common.dao.BaseDao;
import xiaozhi.modules.sys.entity.SysUserEntity;

/**
 * 系统用户
 */
@Mapper
public interface SysUserDao extends BaseDao<SysUserEntity> {
    /** 与账号删除串行，防止删除过程中重新领取工作空间邀请。 */
    @Select("SELECT * FROM sys_user WHERE id = #{userId} LIMIT 1 FOR UPDATE")
    SysUserEntity selectByIdForUpdate(@Param("userId") Long userId);
}
