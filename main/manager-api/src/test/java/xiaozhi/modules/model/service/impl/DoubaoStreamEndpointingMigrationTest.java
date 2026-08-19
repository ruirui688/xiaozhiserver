package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class DoubaoStreamEndpointingMigrationTest {

    @Test
    void migrationAddsUpstreamVadWindowWithoutOverwritingExistingModelValue() throws IOException {
        String master = readResource("db/changelog/db.changelog-master.yaml");
        String migration = readResource("db/changelog/202608191130.sql");

        assertTrue(master.contains("id: 202608191130"));
        assertTrue(master.contains("classpath:db/changelog/202608191130.sql"));
        assertTrue(migration.contains("'ASR_DOUBAO_SEED_ASR_2_STREAM'"));
        assertTrue(migration.contains("'ASR_DoubaoStreamASRV2'"));
        assertTrue(migration.contains("'end_window_size'"));
        assertTrue(migration.contains("'200'"));
        assertTrue(migration.contains("AND NOT EXISTS"));
    }

    private String readResource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "missing classpath resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
