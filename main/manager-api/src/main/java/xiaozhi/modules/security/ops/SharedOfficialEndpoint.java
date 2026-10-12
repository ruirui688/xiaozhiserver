package xiaozhi.modules.security.ops;

import java.lang.annotation.*;

/** Only for endpoints whose resource service distinguishes OFFICIAL from tenant-owned resources. */
@Target({ElementType.TYPE,ElementType.METHOD}) @Retention(RetentionPolicy.RUNTIME)
public @interface SharedOfficialEndpoint {}
