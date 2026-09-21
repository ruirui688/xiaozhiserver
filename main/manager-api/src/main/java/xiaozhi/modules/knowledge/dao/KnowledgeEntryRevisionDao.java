package xiaozhi.modules.knowledge.dao;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import xiaozhi.modules.knowledge.entity.KnowledgeEntryRevisionEntity;

@Mapper
public interface KnowledgeEntryRevisionDao extends BaseMapper<KnowledgeEntryRevisionEntity> {
}
