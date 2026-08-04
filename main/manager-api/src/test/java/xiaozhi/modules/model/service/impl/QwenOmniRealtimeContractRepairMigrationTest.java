package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class QwenOmniRealtimeContractRepairMigrationTest {

    @Test
    void migrationRepairsPollutedRealtimeMetadataWithoutReplacingApiKey() throws IOException {
        String master = readResource("db/changelog/db.changelog-master.yaml");
        String migration = readResource("db/changelog/202608041520.sql");

        assertTrue(master.contains("id: 202608041520"));
        assertTrue(master.contains("classpath:db/changelog/202608041520.sql"));
        assertTrue(migration.contains("`c`.`adapter_code` = 'qwen_omni_realtime'"));
        assertTrue(migration.contains("`c`.`template_code` = 'LLM_ALI_QWEN_OMNI_REALTIME'"));
        assertTrue(migration.contains("'DashScope Realtime WebSocket'"));
        assertTrue(migration.contains("THEN 'qwen3.5-omni-flash-realtime'"));
        assertTrue(migration.contains("ELSE NULL"));
        assertTrue(migration.contains("`c`.`is_enabled` = 0"));
        assertTrue(migration.contains("`c`.`test_status` = 0"));
        assertTrue(migration.contains("`c`.`test_message` = NULL"));
        assertTrue(migration.contains("`c`.`test_time` = NULL"));
        assertTrue(migration.contains("INSERT INTO `ai_model_config_secret`"));
        assertTrue(migration.contains("JSON_EXTRACT(`c`.`config_json`, '$.api_key')"));
        assertTrue(migration.contains("WHEN NULLIF(TRIM(`secret_value`), '') IS NULL"));
        assertTrue(migration.contains("WHERE `s`.`secret_key` <> 'api_key'"));
        assertTrue(migration.contains("WHEN `param_key` = 'conversation_mode' THEN 'realtime'"));
        assertFalse(migration.contains("compatible-mode/v1/chat/completions"));
        assertFalse(migration.contains("sk-"));
    }

    private String readResource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "missing classpath resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
