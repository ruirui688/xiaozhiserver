package xiaozhi.modules.maintenance.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MaintenanceConversationMigrationTest {
    @Test
    void migrationCreatesConversationMessagesAndGroupsSystemHistory() throws Exception {
        String sql = resource("/db/changelog/202608271030.sql");
        String master = resource("/db/changelog/db.changelog-master.yaml");

        assertTrue(sql.contains("CREATE TABLE `ai_maintenance_conversation`"));
        assertTrue(sql.contains("CREATE TABLE `ai_maintenance_message`"));
        assertTrue(sql.contains("同一资源的旧手动/定时运行分别归并到稳定的系统会话"));
        assertTrue(sql.contains("GROUP BY r.`policy_id`"));
        assertTrue(sql.contains("ON DELETE SET NULL"));
        assertTrue(master.contains("id: 202608271030"));
        assertTrue(master.contains("classpath:db/changelog/202608271030.sql"));
    }

    private String resource(String path) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
