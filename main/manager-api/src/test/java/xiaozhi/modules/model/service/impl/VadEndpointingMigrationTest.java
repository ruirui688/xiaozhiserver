package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class VadEndpointingMigrationTest {

    @Test
    void migrationRepairsAuthoritativeVadValueAndSafelySelectsStreamingAsr() throws IOException {
        String master = readResource("db/changelog/db.changelog-master.yaml");
        String migration = readResource("db/changelog/202608181500.sql");

        assertTrue(master.contains("id: 202608181500"));
        assertTrue(master.contains("classpath:db/changelog/202608181500.sql"));

        assertTrue(migration.contains("UPDATE `ai_model_config_param`"));
        assertTrue(migration.contains("'VAD_SileroVAD'"));
        assertTrue(migration.contains("'min_silence_duration_ms'"));
        assertTrue(migration.contains("SET `param_value` = '200'"));
        assertTrue(migration.contains("IN ('', '700', '700.0')"));

        assertTrue(migration.contains("'ASR_DoubaoRecordingASR2'"));
        assertTrue(migration.contains("'ASR_DoubaoStreamASRV2'"));
        assertTrue(migration.contains("NULLIF(TRIM(stream_secret.`secret_value`), '') IS NOT NULL"));
        assertFalse(migration.contains("ON DUPLICATE KEY UPDATE `secret_value`"),
                "an existing streaming API key must not be overwritten");
    }

    private String readResource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "missing classpath resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
