package xiaozhi.modules.security.ops;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Login requirements come only from deployment configuration, never request fields. */
@Component
public class OpsLoginPolicy {
    private final boolean mfaRequired;
    private final boolean captchaRequired;

    public OpsLoginPolicy(
            @Value("${xiaozhi.security.ops-login.mfa-required:false}") boolean mfaRequired,
            @Value("${xiaozhi.security.ops-login.captcha-required:false}") boolean captchaRequired) {
        this.mfaRequired = mfaRequired;
        this.captchaRequired = captchaRequired;
    }

    public boolean mfaRequired() { return mfaRequired; }
    public boolean captchaRequired() { return captchaRequired; }
}
