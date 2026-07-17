package xiaozhi.modules.userplugin.dao;

import org.apache.ibatis.annotations.Mapper;

import xiaozhi.common.dao.BaseDao;
import xiaozhi.modules.userplugin.entity.UserPluginConfigEntity;

@Mapper
public interface UserPluginConfigDao extends BaseDao<UserPluginConfigEntity> {
}
