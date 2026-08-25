package xiaozhi.modules.knowledge.source.dao;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.knowledge.source.entity.KnowledgeSourceEntity;

@Mapper
public interface KnowledgeSourceDao extends BaseMapper<KnowledgeSourceEntity> {
}
