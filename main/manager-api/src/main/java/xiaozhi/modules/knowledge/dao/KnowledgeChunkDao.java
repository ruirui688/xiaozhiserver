package xiaozhi.modules.knowledge.dao;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.knowledge.entity.KnowledgeChunkEntity;

@Mapper
public interface KnowledgeChunkDao extends BaseMapper<KnowledgeChunkEntity> {
}
