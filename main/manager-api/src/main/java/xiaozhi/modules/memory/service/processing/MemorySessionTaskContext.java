package xiaozhi.modules.memory.service.processing;

import java.util.function.Supplier;

/** Carry the durable task's ownership check into the fact-writing transaction. */
public final class MemorySessionTaskContext {
    private static final ThreadLocal<Runnable> FENCE = new ThreadLocal<>();

    private MemorySessionTaskContext() {}

    public static <T> T within(Runnable fence, Supplier<T> action) {
        Runnable previous = FENCE.get();
        FENCE.set(fence);
        try {
            return action.get();
        } finally {
            if (previous == null) FENCE.remove();
            else FENCE.set(previous);
        }
    }

    public static void checkBeforeCommit() {
        Runnable fence = FENCE.get();
        if (fence != null) fence.run();
    }
}
