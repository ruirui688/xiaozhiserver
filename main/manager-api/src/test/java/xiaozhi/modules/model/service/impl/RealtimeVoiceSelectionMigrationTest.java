package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class RealtimeVoiceSelectionMigrationTest {

    @Test
    void migrationAddsSelectableVoiceFieldsForBothRealtimeAdapters() throws IOException {
        String master = readResource("db/changelog/db.changelog-master.yaml");
        String migration = readResource("db/changelog/202608051720.sql");

        assertTrue(master.contains("id: 202608051720"));
        assertTrue(master.contains("classpath:db/changelog/202608051720.sql"));
        assertTrue(migration.contains("LLM_ALI_QWEN_OMNI_REALTIME"));
        assertTrue(migration.contains("LLM_DOUBAO_REALTIME"));
        assertTrue(migration.contains("`input_type` = 'select'"));
        assertTrue(migration.contains("JSON_ARRAY('Cherry', 'Serena', 'Ethan', 'Chelsie')"));
    }

    private String readResource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "missing classpath resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
