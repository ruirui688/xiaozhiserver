package xiaozhi.modules.maintenance.dao;

import org.apache.ibatis.annotations.Mapper;
import xiaozhi.common.dao.BaseDao;
import xiaozhi.modules.maintenance.entity.MaintenanceMessageEntity;

@Mapper
public interface MaintenanceMessageDao extends BaseDao<MaintenanceMessageEntity> { }
