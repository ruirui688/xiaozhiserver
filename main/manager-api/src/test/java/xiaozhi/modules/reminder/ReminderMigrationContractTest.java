package xiaozhi.modules.reminder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ReminderMigrationContractTest {
    @Test
    void migrationCreatesDurableReminderAndRegistersOptionalPlugin() throws Exception {
        String migration = Files.readString(
                Path.of("src/main/resources/db/changelog/device-reminders-20261013.sql"));
        String master = Files.readString(
                Path.of("src/main/resources/db/changelog/db.changelog-master.yaml"));
        String shiro = Files.readString(
                Path.of("src/main/java/xiaozhi/modules/security/config/ShiroConfig.java"));

        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS `ai_device_reminder`"));
        assertTrue(migration.contains("UNIQUE KEY `uk_device_reminder_request`"));
        assertTrue(migration.contains("KEY `idx_device_reminder_due` (`status`, `next_attempt_at`)"));
        assertTrue(migration.contains("KEY `idx_device_reminder_expiry` (`status`, `remind_at`)"));
        assertTrue(migration.contains(
                "KEY `idx_device_reminder_pending_hash` (`device_id`, `status`, `request_hash`)"));
        assertTrue(migration.contains(
                "KEY `idx_device_reminder_workspace_time` (`workspace_id`, `status`, `remind_at`)"));
        assertTrue(migration.contains(
                "KEY `idx_device_reminder_device_time` (`device_id`, `status`, `remind_at`)"));
        assertTrue(migration.contains("`lease_token` VARCHAR(36)"));
        assertTrue(migration.contains("'SYSTEM_PLUGIN_DEVICE_REMINDER'"));
        assertTrue(migration.contains("'device_reminder'"));
        assertTrue(migration.contains("普通讨论未来时间不得创建提醒"));
        assertTrue(migration.contains("只有工具成功返回后才可声称已创建或取消"));
        assertFalse(migration.contains("ai_agent_plugin_mapping"));
        assertTrue(master.contains("classpath:db/changelog/device-reminders-20261013.sql"));
        assertTrue(shiro.contains("filterMap.put(\"/reminders/**\", \"oauth2\")"));
    }
}
