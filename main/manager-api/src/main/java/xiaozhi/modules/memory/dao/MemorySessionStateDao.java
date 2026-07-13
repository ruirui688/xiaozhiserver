package xiaozhi.modules.memory.dao;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import xiaozhi.common.dao.BaseDao;
import xiaozhi.modules.memory.entity.MemorySessionStateEntity;

@Mapper
public interface MemorySessionStateDao extends BaseDao<MemorySessionStateEntity> {

    @Insert("""
            INSERT INTO ai_memory_session_state
                (agent_id, session_id, last_event_id, last_user_event_id, last_event_at,
                 processed_through_event_id, created_at, updated_at)
            VALUES
                (#{agentId}, #{sessionId}, #{eventId},
                 CASE WHEN #{userEvent} THEN #{eventId} ELSE 0 END,
                 #{eventAt}, 0, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                last_event_id = GREATEST(last_event_id, #{eventId}),
                last_user_event_id = CASE WHEN #{userEvent}
                    THEN GREATEST(last_user_event_id, #{eventId})
                    ELSE last_user_event_id END,
                last_event_at = GREATEST(last_event_at, #{eventAt}),
                updated_at = NOW()
            """)
    int touchSession(@Param("agentId") String agentId,
                     @Param("sessionId") String sessionId,
                     @Param("eventId") Long eventId,
                     @Param("eventAt") Date eventAt,
                     @Param("userEvent") boolean userEvent);

    @Select("""
            SELECT * FROM ai_memory_session_state
            WHERE last_event_at <= #{idleBefore}
              AND processed_through_event_id < last_user_event_id
              AND (processing_started_at IS NULL OR processing_started_at < #{staleBefore})
            ORDER BY last_event_at ASC
            LIMIT #{limit}
            """)
    List<MemorySessionStateEntity> findIdleSessions(@Param("idleBefore") Date idleBefore,
                                                    @Param("staleBefore") Date staleBefore,
                                                    @Param("limit") int limit);

    @Update("""
            UPDATE ai_memory_session_state
            SET processing_through_event_id = #{eventId},
                processing_started_at = NOW(),
                updated_at = NOW()
            WHERE id = #{id}
              AND last_event_id = #{eventId}
              AND processed_through_event_id < last_user_event_id
              AND last_event_at <= #{idleBefore}
              AND (processing_started_at IS NULL OR processing_started_at < #{staleBefore})
            """)
    int claimIdle(@Param("id") Long id,
                  @Param("eventId") Long eventId,
                  @Param("idleBefore") Date idleBefore,
                  @Param("staleBefore") Date staleBefore);

    @Update("""
            UPDATE ai_memory_session_state
            SET processing_through_event_id = #{eventId},
                processing_started_at = NOW(),
                updated_at = NOW()
            WHERE id = #{id}
              AND last_event_id = #{eventId}
              AND processed_through_event_id < last_user_event_id
              AND (processing_started_at IS NULL OR processing_started_at < #{staleBefore})
            """)
    int claimNow(@Param("id") Long id,
                 @Param("eventId") Long eventId,
                 @Param("staleBefore") Date staleBefore);

    @Update("""
            UPDATE ai_memory_session_state
            SET processed_through_event_id = GREATEST(processed_through_event_id, #{eventId}),
                processing_through_event_id = NULL,
                processing_started_at = NULL,
                updated_at = NOW()
            WHERE id = #{id} AND processing_through_event_id = #{eventId}
            """)
    int complete(@Param("id") Long id, @Param("eventId") Long eventId);

    @Update("""
            UPDATE ai_memory_session_state
            SET processing_through_event_id = NULL,
                processing_started_at = NULL,
                updated_at = NOW()
            WHERE id = #{id} AND processing_through_event_id = #{eventId}
            """)
    int release(@Param("id") Long id, @Param("eventId") Long eventId);
}
