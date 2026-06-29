package xiaozhi.modules.memory.dao;

import org.apache.ibatis.annotations.Mapper;

import xiaozhi.common.dao.BaseDao;
import xiaozhi.modules.memory.entity.MemoryProfileEntity;

@Mapper
public interface MemoryProfileDao extends BaseDao<MemoryProfileEntity> {
}
