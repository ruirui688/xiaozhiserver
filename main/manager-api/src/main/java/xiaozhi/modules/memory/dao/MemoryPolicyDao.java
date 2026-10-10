package xiaozhi.modules.memory.dao;

import org.apache.ibatis.annotations.Mapper;
import xiaozhi.common.dao.BaseDao;
import xiaozhi.modules.memory.entity.MemoryPolicyEntity;

@Mapper
public interface MemoryPolicyDao extends BaseDao<MemoryPolicyEntity> {
}
