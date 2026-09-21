package xiaozhi.modules.config.support;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Scoped, payload-free timing for the three voice critical-path config APIs.
 * Root durations include child stages and must not be added to them.
 */
public final class ConfigLatency {
    public static final String HEADER = "X-Xiaozhi-Trace-Id";
    private static final Logger LOG = LoggerFactory.getLogger(ConfigLatency.class);
    private static final ThreadLocal<String> TRACE = new ThreadLocal<>();

    private ConfigLatency() { }

    public static <T> T request(String stage, Supplier<T> action) {
        String previous = TRACE.get();
        var attributes = RequestContextHolder.getRequestAttributes();
        String incoming = attributes instanceof ServletRequestAttributes servlet
                ? servlet.getRequest().getHeader(HEADER) : null;
        String trace = incoming != null && incoming.matches("[0-9a-fA-F]{32}")
                ? incoming.toLowerCase(Locale.ROOT) : UUID.randomUUID().toString().replace("-", "");
        TRACE.set(trace);
        try {
            if (attributes instanceof ServletRequestAttributes servlet && servlet.getResponse() != null) {
                try {
                    servlet.getResponse().setHeader(HEADER, trace);
                } catch (RuntimeException ignored) {
                    // Correlation is optional and must not change business results.
                }
            }
            return measure(stage, action);
        } finally {
            if (previous == null) {
                TRACE.remove();
            } else {
                TRACE.set(previous);
            }
        }
    }

    public static <T> T measure(String stage, Supplier<T> action) {
        String trace = TRACE.get();
        if (trace == null) {
            return action.get();
        }
        long start = System.nanoTime();
        String errorType = "none";
        try {
            return action.get();
        } catch (RuntimeException | Error error) {
            errorType = error.getClass().getSimpleName();
            throw error;
        } finally {
            try {
                LOG.info("config_latency trace_id={} stage={} elapsed_ms={} status={} error_type={}",
                        trace, stage, (System.nanoTime() - start) / 1_000_000.0,
                        errorType.equals("none") ? "ok" : "error", errorType);
            } catch (RuntimeException ignored) {
                // An unavailable log sink must not override a result or original failure.
            }
        }
    }
}
