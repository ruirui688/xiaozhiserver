package xiaozhi.modules.knowledge.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.knowledge.entity.KnowledgeChunkEntity;

@Mapper
public interface KnowledgeChunkDao extends BaseMapper<KnowledgeChunkEntity> {
    List<KnowledgeChunkEntity> selectRetrievalCandidates(
            @Param("datasetIds") List<String> datasetIds,
            @Param("documentIds") List<String> documentIds,
            @Param("terms") List<String> terms,
            @Param("fulltextQuery") String fulltextQuery,
            @Param("limit") int limit);

    /** 仅用于首次创建本地磁盘索引；正常召回不走该全量查询。 */
    List<KnowledgeChunkEntity> selectIndexableChunks(
            @Param("datasetIds") List<String> datasetIds,
            @Param("documentIds") List<String> documentIds);
}
