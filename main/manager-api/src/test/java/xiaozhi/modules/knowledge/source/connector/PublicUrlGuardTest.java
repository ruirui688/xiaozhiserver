package xiaozhi.modules.knowledge.source.connector;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PublicUrlGuardTest {
    @Test
    void acceptsPublicHttpsUrl() {
        assertDoesNotThrow(() -> PublicUrlGuard.requirePublicHttpUrl("https://example.com/patch-notes"));
    }

    @Test
    void blocksLoopbackPrivateAndCloudMetadataTargets() {
        assertThrows(RuntimeException.class,
                () -> PublicUrlGuard.requirePublicHttpUrl("http://127.0.0.1/admin"));
        assertThrows(RuntimeException.class,
                () -> PublicUrlGuard.requirePublicHttpUrl("http://192.168.1.10/internal"));
        assertThrows(RuntimeException.class,
                () -> PublicUrlGuard.requirePublicHttpUrl("http://169.254.169.254/latest/meta-data"));
    }

    @Test
    void blocksCredentialsAndSecretsInUrl() {
        assertThrows(RuntimeException.class,
                () -> PublicUrlGuard.requirePublicHttpUrl("https://user:password@example.com/data"));
        assertThrows(RuntimeException.class,
                () -> PublicUrlGuard.requirePublicHttpUrl("https://example.com/data?token=secret"));
    }
}
