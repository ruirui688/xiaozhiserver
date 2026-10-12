package xiaozhi.common.llm;

import java.util.function.Supplier;

/** Immutable attribution installed inside the worker that actually invokes a model. */
public final class LlmUsageContext implements AutoCloseable {
    public record Identity(String workspaceId, String agentId, String deviceId,
                           String sessionId, String modelId, String purpose) {
        public static Identity agent(String workspaceId, String agentId, String purpose) {
            return new Identity(workspaceId, agentId, null, null, null, purpose);
        }

        public static Identity platform(String modelId, String purpose) {
            return new Identity(null, null, null, null, modelId, purpose);
        }
    }

    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();
    private final Identity previous;

    private LlmUsageContext(Identity identity) {
        previous = CURRENT.get();
        CURRENT.set(identity);
    }

    public static LlmUsageContext open(Identity identity) {
        return new LlmUsageContext(identity);
    }

    public static Identity current() {
        return CURRENT.get();
    }

    public static LlmUsageContext model(String modelId) {
        Identity current = current();
        return open(current == null ? Identity.platform(modelId, "BACKGROUND")
                : new Identity(current.workspaceId(), current.agentId(), current.deviceId(),
                        current.sessionId(), modelId, current.purpose()));
    }

    public static <T> T call(Identity identity, Supplier<T> operation) {
        try (var ignored = open(identity)) {
            return operation.get();
        }
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
