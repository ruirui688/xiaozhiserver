package xiaozhi.modules.model.service.impl.benchmark;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

@Component
public class BenchmarkTaskExecutor {
    private static final int DEFAULT_WORKERS = 2;
    private static final int DEFAULT_QUEUE_CAPACITY = 32;

    private final ThreadPoolExecutor executor;

    public BenchmarkTaskExecutor() {
        this(DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY);
    }

    BenchmarkTaskExecutor(int workers, int queueCapacity) {
        AtomicInteger sequence = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "model-benchmark-" + sequence.incrementAndGet());
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public boolean submit(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
