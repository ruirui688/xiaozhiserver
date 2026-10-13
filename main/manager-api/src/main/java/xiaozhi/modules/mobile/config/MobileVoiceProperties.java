package xiaozhi.modules.mobile.config;

import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Persistent phone identities, not live calls. Existing identities remain usable at capacity. */
@Component
@ConfigurationProperties(prefix = "xiaozhi.mobile-voice")
@Setter
public class MobileVoiceProperties {
    private int maxDevicesPerUserWorkspace = 10;

    public int getMaxDevicesPerUserWorkspace() {
        return Math.max(1, Math.min(50, maxDevicesPerUserWorkspace));
    }
}
