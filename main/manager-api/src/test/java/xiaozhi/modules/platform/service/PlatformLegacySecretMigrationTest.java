package xiaozhi.modules.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.modules.billing.service.BillingSupport;

class PlatformLegacySecretMigrationTest {
    @Test
    @SuppressWarnings("unchecked")
    void startupArchivesAndScrubsSecretsInsideJsonArraysIdempotently() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:legacy-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE ai_model_config_secret(model_id VARCHAR(64),secret_key VARCHAR(64),secret_value TEXT,masked_value VARCHAR(16))");
        jdbc.execute("CREATE TABLE ai_model_config(id VARCHAR(64),config_json TEXT)");
        jdbc.execute("CREATE TABLE ai_model_config_param(id VARCHAR(64),model_id VARCHAR(64),param_key VARCHAR(64),param_value TEXT,value_type VARCHAR(32))");
        jdbc.execute("CREATE TABLE ai_platform_legacy_secret_archive(source_key VARCHAR(128) PRIMARY KEY,ciphertext TEXT,archived_at TIMESTAMP)");
        String legacy = "[{\"name\":\"speech\",\"headers\":{\"Authorization\":\"Bearer fake-legacy-key\"}}]";
        jdbc.update("INSERT INTO ai_model_config_param VALUES(?,?,?,?,?)", "array", "model", "backends", legacy, "json");
        var cipher = new CredentialCipher("test-legacy-migration-master-key-32-chars");
        RedisTemplate<String,Object> redis = mock(RedisTemplate.class);
        Cursor<String> cursor = mock(Cursor.class);
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        var service = new PlatformLegacySecretMigration(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), cipher,
                new BillingSupport(new ObjectMapper()), redis);

        service.run(null);
        String clean = jdbc.queryForObject("SELECT param_value FROM ai_model_config_param WHERE id='array'", String.class);
        assertEquals("[{\"name\":\"speech\",\"headers\":{}}]", clean);
        String archive = jdbc.queryForObject("SELECT ciphertext FROM ai_platform_legacy_secret_archive", String.class);
        assertFalse(archive.contains("fake-legacy-key"));
        assertEquals(legacy, cipher.decrypt("legacy-archive:model-param:array", archive));
        service.run(null);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_legacy_secret_archive", Integer.class));
        assertEquals(archive, jdbc.queryForObject("SELECT ciphertext FROM ai_platform_legacy_secret_archive", String.class));
    }
}
