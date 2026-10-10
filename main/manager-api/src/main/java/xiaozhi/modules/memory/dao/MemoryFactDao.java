package xiaozhi.modules.memory.dao;

import org.apache.ibatis.annotations.Mapper;

import xiaozhi.common.dao.BaseDao;
import xiaozhi.modules.memory.entity.MemoryFactEntity;

@Mapper
public interface MemoryFactDao extends BaseDao<MemoryFactEntity> {
    @org.apache.ibatis.annotations.Select("""
            <script>
            SELECT ranked.* FROM (
              SELECT f.*, ROW_NUMBER() OVER (
                PARTITION BY COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.speaker_id')), '')
                ORDER BY importance DESC, last_seen_at DESC, memory_id ASC) AS memory_rank
              FROM ai_memory_fact f WHERE agent_id = #{agentId} AND status = 'active'
                AND (expires_at IS NULL OR expires_at &gt; CURRENT_TIMESTAMP)
                <if test="deviceId != null">AND scope_id = #{deviceId}</if>
                AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.speaker_id')), '') IN
                <foreach collection="speakers" item="speaker" open="(" separator="," close=")">#{speaker}</foreach>
            ) ranked WHERE memory_rank &lt;= #{perSpeakerLimit}
            ORDER BY importance DESC, last_seen_at DESC, memory_id ASC LIMIT 200
            </script>
            """)
    java.util.List<MemoryFactEntity> selectSnapshotCandidates(
            @org.apache.ibatis.annotations.Param("agentId") String agentId,
            @org.apache.ibatis.annotations.Param("deviceId") String deviceId,
            @org.apache.ibatis.annotations.Param("speakers") java.util.List<String> speakers,
            @org.apache.ibatis.annotations.Param("perSpeakerLimit") int perSpeakerLimit);
}
