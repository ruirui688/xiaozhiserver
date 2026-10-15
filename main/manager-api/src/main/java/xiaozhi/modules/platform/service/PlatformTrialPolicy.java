package xiaozhi.modules.platform.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Explicit deployment overrides remain authoritative; no configuration can create issuance-pool credit. */
@Component
@ConfigurationProperties(prefix = "xiaozhi.platform")
@Getter @Setter
public class PlatformTrialPolicy {
    private boolean trialEnabled = true;
    private String trialAmount = "100";
    private String trialPolicy = "trial-v1";
    private long trialMaxUsers = 0;
}
