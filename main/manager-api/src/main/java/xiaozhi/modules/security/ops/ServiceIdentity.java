package xiaozhi.modules.security.ops;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class ServiceIdentity {
    public static final String VERIFIED_ATTRIBUTE = ServiceIdentity.class.getName()+".verified";
    private ServiceIdentity() {}
    public static boolean isVerifiedRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && Boolean.TRUE.equals(attributes.getRequest().getAttribute(VERIFIED_ATTRIBUTE));
    }
}
