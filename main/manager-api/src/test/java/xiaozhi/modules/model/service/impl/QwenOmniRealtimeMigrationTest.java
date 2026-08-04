package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class QwenOmniRealtimeMigrationTest {

    @Test
    void migrationIsRegisteredAndSeedsSupportedRealtimeContractWithoutSecret() throws IOException {
        String master = readResource("db/changelog/db.changelog-master.yaml");
        String migration = readResource("db/changelog/202608031130.sql");

        assertTrue(master.contains("id: 202608031130"));
        assertTrue(master.contains("classpath:db/changelog/202608031130.sql"));
        assertTrue(migration.contains("'qwen_omni_realtime'"));
        assertTrue(migration.contains("'SUPPORTED'"));
        assertTrue(migration.contains("'qwen3.5-omni-flash-realtime'"));
        assertTrue(migration.contains("'voice', 'Ethan'"));
        assertFalse(migration.contains("Cherry"));
        assertTrue(migration.contains("JSON_ARRAY('server_vad', 'semantic_vad', 'manual')"));
        assertTrue(migration.contains("'conversation_mode', 'realtime'"));
        assertTrue(migration.contains("'response_timeout_seconds', '120'"));
        assertTrue(migration.contains("`upstream_model_id` = COALESCE(NULLIF(`upstream_model_id`, '')"));
        assertTrue(migration.contains("`endpoint_url` = COALESCE(NULLIF(`endpoint_url`, '')"));
        assertTrue(migration.contains("cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime"));
        assertTrue(migration.contains("`is_enabled` = 0"));
        assertTrue(migration.contains("INSERT INTO `ai_model_config_secret`"));
        assertTrue(migration.contains("JSON_EXTRACT(`config_json`, '$.api_key')"));
        assertTrue(migration.contains("AND `secret_key` <> 'api_key'"));
        assertTrue(migration.contains("AND `param_key` NOT IN"));
        assertFalse(migration.contains("sk-"));
    }

    private String readResource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "missing classpath resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
