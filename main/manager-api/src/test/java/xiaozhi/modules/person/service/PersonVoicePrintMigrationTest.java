package xiaozhi.modules.person.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;

import xiaozhi.modules.security.config.ShiroConfig;
import xiaozhi.modules.sys.service.SysParamsService;

class PersonVoicePrintMigrationTest {

    @Test
    void migrationAddsOneToOnePersonOwnershipAndDeleteProtection() throws Exception {
        String migration = Files.readString(
                Path.of("src/main/resources/db/changelog/person-voiceprint-link-20261013.sql"));
        String master = Files.readString(
                Path.of("src/main/resources/db/changelog/db.changelog-master.yaml"));

        assertTrue(migration.contains("ADD COLUMN `person_id` VARCHAR(32) NULL"));
        assertTrue(migration.contains("UNIQUE KEY `uk_agent_voice_print_person` (`person_id`)"));
        assertTrue(migration.contains("FOREIGN KEY (`person_id`) REFERENCES `ai_person_profile` (`id`)"));
        assertTrue(migration.contains("ON DELETE RESTRICT"));
        assertTrue(master.contains("id: person-voiceprint-link-20261013"));
        assertTrue(master.contains("classpath:db/changelog/person-voiceprint-link-20261013.sql"));
    }

    @Test
    void personOptionQueryRestrictsWorkspaceAndOwnership() throws Exception {
        String mapper = Files.readString(
                Path.of("src/main/resources/mapper/agent/AgentVoicePrintDao.xml"));

        assertTrue(mapper.contains("WHERE workspace_id = #{workspaceId}"));
        assertTrue(mapper.contains("AND (person_id IS NULL OR person_id = #{personId})"));
        assertTrue(mapper.contains("selectByIdForUpdate"));
        assertTrue(mapper.contains("selectByPersonForUpdate"));
        assertTrue(mapper.contains("FOR UPDATE"));
    }

    @Test
    void shiroExplicitlyAuthenticatesPersonBiometricResources() {
        ShiroFilterFactoryBean filter = new ShiroConfig().shirFilter(
                mock(SecurityManager.class), mock(SysParamsService.class));

        assertEquals("oauth2", filter.getFilterChainDefinitionMap().get("/resource/persons/**"));
    }
}
