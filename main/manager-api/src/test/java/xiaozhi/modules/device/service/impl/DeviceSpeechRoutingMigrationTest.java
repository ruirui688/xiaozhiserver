package xiaozhi.modules.device.service.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class DeviceSpeechRoutingMigrationTest {

    @Test
    void migrationAddsConstrainedDefaultServerModesAndIsRegistered() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/changelog/202608061730.sql"));
        String master = Files.readString(Path.of("src/main/resources/db/changelog/db.changelog-master.yaml"));

        assertTrue(migration.contains("`speech_input_mode` VARCHAR(16) NOT NULL DEFAULT 'server'"));
        assertTrue(migration.contains("`speech_output_mode` VARCHAR(16) NOT NULL DEFAULT 'server'"));
        assertTrue(migration.contains("IN ('server', 'device', 'auto')"));
        assertTrue(master.contains("id: 202608061730"));
        assertTrue(master.contains("classpath:db/changelog/202608061730.sql"));
    }
}
