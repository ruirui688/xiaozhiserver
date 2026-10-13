package xiaozhi.modules.reminder.dao;

import org.apache.ibatis.annotations.Mapper;

import xiaozhi.common.dao.BaseDao;
import xiaozhi.modules.reminder.entity.ReminderEntity;

@Mapper
public interface ReminderDao extends BaseDao<ReminderEntity> {
}
