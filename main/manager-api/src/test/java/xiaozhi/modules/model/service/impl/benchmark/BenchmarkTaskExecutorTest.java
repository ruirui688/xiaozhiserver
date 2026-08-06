package xiaozhi.modules.model.service.impl.benchmark;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class BenchmarkTaskExecutorTest {

    @Test
    void rejectsWorkWhenWorkersAndQueueAreFull() throws Exception {
        BenchmarkTaskExecutor executor = new BenchmarkTaskExecutor(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertTrue(executor.submit(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(executor.submit(() -> { }));
            assertFalse(executor.submit(() -> { }));
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
