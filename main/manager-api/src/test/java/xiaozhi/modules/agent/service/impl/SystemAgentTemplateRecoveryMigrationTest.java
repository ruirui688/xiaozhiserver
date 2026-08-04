package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class SystemAgentTemplateRecoveryMigrationTest {

    @Test
    void migrationIsRegisteredAndSeedsCredentialFreeTemplateOnlyWhenMissing() throws IOException {
        String master = readResource("db/changelog/db.changelog-master.yaml");
        String migration = readResource("db/changelog/202608041800.sql");

        assertTrue(master.contains("id: 202608041800"));
        assertTrue(master.contains("classpath:db/changelog/202608041800.sql"));
        assertTrue(migration.contains("WHERE NOT EXISTS"));
        assertTrue(migration.contains("`workspace_id` IS NULL"));
        assertTrue(migration.contains("'system_default_template_20260804'"));
        assertTrue(migration.contains("NULL,\n  NULL,\n  'AGT_SYSTEM_DEFAULT'"));
        assertFalse(migration.contains("FROM `ai_agent_template` `t`"));
        assertFalse(migration.toLowerCase().contains("secret"));
        assertFalse(migration.toLowerCase().contains("voice_print"));
    }

    private String readResource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "missing classpath resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
