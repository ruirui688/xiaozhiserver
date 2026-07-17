package xiaozhi.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.SecureRandom;

import org.junit.jupiter.api.Test;

import xiaozhi.common.exception.RenException;

class CredentialCipherTest {
    private static final String KEY = "test-key-with-at-least-thirty-two-characters";

    @Test
    void encryptsWithRandomIvAndScopeBoundAuthentication() {
        CredentialCipher cipher = new CredentialCipher(KEY, new SecureRandom());

        String first = cipher.encrypt("agent-feishu:agent-a", "app-secret");
        String second = cipher.encrypt("agent-feishu:agent-a", "app-secret");

        assertNotEquals(first, second);
        assertEquals("app-secret", cipher.decrypt("agent-feishu:agent-a", first));
        assertThrows(RenException.class, () -> cipher.decrypt("agent-feishu:agent-b", first));
    }

    @Test
    void rejectsMissingDeploymentKey() {
        CredentialCipher cipher = new CredentialCipher("short", new SecureRandom());
        assertThrows(RenException.class, () -> cipher.encrypt("scope", "secret"));
    }
}
