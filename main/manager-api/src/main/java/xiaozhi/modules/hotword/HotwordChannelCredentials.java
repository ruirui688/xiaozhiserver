package xiaozhi.modules.hotword;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.modules.platform.service.PlatformCatalogSecrets;

/** Java-only hotword management credential; never part of the public model runtime.
 * Read the current immutable credential in one join, without claiming a model
 * execution permit, caching plaintext, or falling back to retired model secrets.
 */
@Component
@RequiredArgsConstructor
class HotwordChannelCredentials {
    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;
    private final ObjectMapper json;

    String keyForModel(String modelId) {
        try {
            var rows = jdbc.queryForList(
                    "SELECT c.provider,c.service_type,c.status,k.id AS credential_id,k.secret_ciphertext "
                            + "FROM ai_platform_channel c JOIN ai_platform_channel_credential k "
                            + "ON k.id=c.credential_id AND k.channel_id=c.id WHERE c.model_id=?", modelId);
            if (rows.size() != 1) throw unavailable();
            var row = rows.getFirst();
            if (!"VOLCENGINE".equals(row.get("provider")) || !"ASR".equals(row.get("service_type"))
                    || !"ACTIVE".equals(row.get("status"))) throw unavailable();
            if (!(row.get("credential_id") instanceof String id) || id.isBlank()
                    || !(row.get("secret_ciphertext") instanceof String encrypted)
                    || !encrypted.startsWith("v1:")) throw unavailable();
            var value = json.readTree(cipher.decrypt("platform-channel-credential:" + id, encrypted)).path("api_key");
            if (!value.isTextual()) throw unavailable();
            String key = value.asText().trim();
            if (key.isEmpty() || key.length() > 4096 || PlatformCatalogSecrets.placeholder(key)
                    || key.contains("你的")) throw unavailable();
            return key;
        } catch (Exception unavailable) {
            // Decryption/JSON/SQL errors may contain secrets: no raw cause or logging.
            throw unavailable();
        }
    }

    private static RenException unavailable() {
        return new RenException("当前识别模型的平台语音通道或凭据不可用，请联系平台管理员检查");
    }
}
