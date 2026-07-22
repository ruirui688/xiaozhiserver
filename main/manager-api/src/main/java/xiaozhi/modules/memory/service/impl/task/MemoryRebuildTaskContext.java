package xiaozhi.modules.memory.service.impl.task;

import java.util.function.Supplier;

public final class MemoryRebuildTaskContext {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private MemoryRebuildTaskContext() {
    }

    public static <T> T execute(long deadlineEpochMs, ProgressReporter reporter, Supplier<T> work) {
        State previous = CURRENT.get();
        CURRENT.set(new State(deadlineEpochMs, reporter));
        try {
            checkActive();
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void checkpoint(int progress, String stage, String message) {
        checkActive();
        State state = CURRENT.get();
        if (state != null && state.reporter() != null) {
            state.reporter().report(progress, stage, message);
        }
    }

    public static void checkActive() {
        State state = CURRENT.get();
        if (Thread.currentThread().isInterrupted()
                || (state != null && System.currentTimeMillis() >= state.deadlineEpochMs())) {
            throw new MemoryRebuildTaskTimeoutException("记忆重建任务已超过执行时限");
        }
    }

    @FunctionalInterface
    public interface ProgressReporter {
        void report(int progress, String stage, String message);
    }

    private record State(long deadlineEpochMs, ProgressReporter reporter) {
    }
}
