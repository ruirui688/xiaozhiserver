package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class DoubaoRealtimeMigrationTest {

    @Test
    void migrationIsRegisteredAndSeedsDisabledCredentialSafeModel() throws IOException {
        String master = readResource("db/changelog/db.changelog-master.yaml");
        String migration = readResource("db/changelog/202608041430.sql");

        assertTrue(master.contains("id: 202608041430"));
        assertTrue(master.contains("classpath:db/changelog/202608041430.sql"));
        assertTrue(migration.contains("'doubao_realtime'"));
        assertTrue(migration.contains("'LLM_DOUBAO_REALTIME'"));
        assertTrue(migration.contains("wss://openspeech.bytedance.com/api/v3/realtime/dialogue"));
        assertTrue(migration.contains("JSON_ARRAY('1.2.1.1', '2.2.0.0')"));
        assertTrue(migration.contains("'conversation_mode', 'realtime'"));
        assertTrue(migration.contains("`is_enabled` = 0"));
        assertTrue(migration.contains("INSERT INTO `ai_model_config_secret`"));
        assertTrue(migration.contains("JSON_EXTRACT(`config_json`, '$.api_key')"));
        assertTrue(migration.contains("AND `secret_key` <> 'api_key'"));
        assertTrue(migration.contains("'app_id', '', 'string', 'credentials'"));
        assertFalse(migration.contains("X-Api-Access-Key"));
        assertFalse(migration.contains("access-key-value"));
    }

    private String readResource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "missing classpath resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
