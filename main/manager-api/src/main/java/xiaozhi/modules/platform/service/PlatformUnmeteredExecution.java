package xiaozhi.modules.platform.service;

import java.net.URI;
import java.util.Locale;
import xiaozhi.common.exception.RenException;

/** Explicitly unavailable paid adapters; storage and metadata do not require a model permit. */
public final class PlatformUnmeteredExecution {
    private PlatformUnmeteredExecution() {}
    public static void deny(String capability) {throw new RenException("ADAPTER_ADMISSION_UNSUPPORTED:"+capability);}
}
