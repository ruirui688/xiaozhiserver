package xiaozhi.modules.reminder.dao;

import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.*;
import xiaozhi.modules.reminder.entity.ReminderEntity;
import xiaozhi.modules.reminder.vo.ReminderExecutionVO;

@Mapper
public interface ReminderTaskDao {
    @Update("UPDATE ai_reminder_dispatch_gate SET next_scan_at = TIMESTAMPADD(SECOND, #{seconds}, UTC_TIMESTAMP(3)) WHERE id = #{id} AND next_scan_at <= UTC_TIMESTAMP(3)")
    @Options(timeout = 3)
    int claimMaintenance(@Param("id") int id, @Param("seconds") int seconds);
    @Update("UPDATE ai_reminder_dispatch_gate SET next_scan_at = TIMESTAMPADD(MICROSECOND, #{scanMs} * 1000, UTC_TIMESTAMP(3)) WHERE id = 1 AND next_scan_at <= UTC_TIMESTAMP(3)")
    @Options(timeout = 3)
    int claimScan(@Param("scanMs") int scanMs);

    // One global fixed window, using the database clock. Failed row claims consume capacity conservatively.
    @Update("UPDATE ai_reminder_dispatch_gate SET permits = IF(window_second = FLOOR(UNIX_TIMESTAMP()), permits + 1, 1), window_second = FLOOR(UNIX_TIMESTAMP()) WHERE id = 2 AND (window_second <> FLOOR(UNIX_TIMESTAMP()) OR permits < #{limit})")
    @Options(timeout = 3)
    int acquirePermit(@Param("limit") int limit);

    @Insert("INSERT INTO ai_reminder_execution (reminder_id, schedule_version, scheduled_at, status, attempt_count, last_error_code, finished_at) VALUES (#{reminder.id}, COALESCE(#{reminder.scheduleVersion}, 1), #{reminder.remindAt}, #{status}, COALESCE(#{reminder.attemptCount}, 0), #{error}, #{finished}) ON DUPLICATE KEY UPDATE status = VALUES(status), attempt_count = VALUES(attempt_count), last_error_code = VALUES(last_error_code), finished_at = VALUES(finished_at)")
    int record(@Param("reminder") ReminderEntity reminder, @Param("status") String status,
               @Param("error") String error, @Param("finished") Date finished);

    @Select("SELECT scheduled_at, schedule_version, status, attempt_count, last_error_code, finished_at FROM ai_reminder_execution WHERE reminder_id = #{id} ORDER BY scheduled_at DESC, schedule_version DESC LIMIT #{limit}")
    @Options(timeout = 3)
    List<ReminderExecutionVO> history(@Param("id") String id, @Param("limit") int limit);

    @Delete("DELETE FROM ai_reminder_execution WHERE finished_at < #{cutoff} ORDER BY finished_at LIMIT #{limit}")
    @Options(timeout = 3)
    int cleanup(@Param("cutoff") Date cutoff, @Param("limit") int limit);
}
