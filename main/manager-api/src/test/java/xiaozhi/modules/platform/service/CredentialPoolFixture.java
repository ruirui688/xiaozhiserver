package xiaozhi.modules.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.modules.billing.service.BillingSupport;

/** Uses the published schema for uniqueness and transaction boundary tests. */
final class CredentialPoolFixture {
    final JdbcTemplate jdbc;
    final TransactionTemplate tx;
    final BillingSupport json = new BillingSupport(new ObjectMapper());
    final CredentialCipher cipher = new CredentialCipher("credential-pool-test-only-master-key-32-characters");
    final PlatformCredentialService credentials;

    CredentialPoolFixture() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:credential-pool-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,username VARCHAR(200),status INT,"
                + "create_date TIMESTAMP,password VARCHAR(100),email VARCHAR(100),mobile VARCHAR(100))");
        String schema = Files.readString(Path.of("src/main/resources/db/changelog/202610121610.sql"));
        for (String table : new String[]{"ai_platform_account", "ai_platform_credential", "ai_platform_user_credential",
                "ai_platform_credential_fingerprint", "ai_platform_credential_operation"}) {
            var matcher = Pattern.compile("CREATE TABLE " + table + " \\([\\s\\S]*?;").matcher(schema);
            if (!matcher.find()) throw new IllegalStateException("Missing published table: " + table);
            jdbc.execute(matcher.group().replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", ""));
        }
        credentials = new PlatformCredentialService(jdbc, tx, json, cipher);
    }

    void user(long id, String username, int status) {
        jdbc.update("INSERT INTO sys_user VALUES(?,?,?,CURRENT_TIMESTAMP,'private-password','private-email','private-mobile')",
                id, username, status);
        jdbc.update("INSERT INTO ai_platform_account(user_id,updated_at) VALUES(?,CURRENT_TIMESTAMP)", id);
    }
}
