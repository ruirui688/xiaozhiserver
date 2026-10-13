package xiaozhi.modules.reminder.service;

import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

/** Fixed labels only; never includes user, device, title or arbitrary response status. */
@Component
@Slf4j
public class ReminderTaskMetrics {
    private final LongAdder claimed = new LongAdder();
    private final LongAdder throttled = new LongAdder();
    private final LongAdder accepted = new LongAdder();
    private final LongAdder retried = new LongAdder();
    private final LongAdder missed = new LongAdder();
    private final LongAdder expired = new LongAdder();
    private final LongAdder deliveryCalls = new LongAdder();
    private final LongAdder deliveryMillis = new LongAdder();
    private final java.util.concurrent.atomic.AtomicLong maxDeliveryMillis = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong oldestReadyLagMillis = new java.util.concurrent.atomic.AtomicLong();
    public void claimed() { claimed.increment(); }
    public void throttled() { throttled.increment(); }
    public void accepted() { accepted.increment(); }
    public void retried() { retried.increment(); }
    public void missed() { missed.increment(); }
    public void expired() { expired.increment(); }
    public void selectedLag(long millis) { oldestReadyLagMillis.set(Math.max(0, millis)); }
    public void deliveryDuration(long millis) {
        deliveryCalls.increment();
        deliveryMillis.add(millis);
        maxDeliveryMillis.accumulateAndGet(millis, Math::max);
    }
    public Map<String, Long> snapshot() {
        return Map.of("claimed", claimed.sum(), "throttled", throttled.sum(), "accepted", accepted.sum(),
                "retried", retried.sum(), "missed", missed.sum(), "expired", expired.sum(),
                "deliveryCalls", deliveryCalls.sum(), "deliveryMillis", deliveryMillis.sum(),
                "maxDeliveryMillis", maxDeliveryMillis.get(), "oldestReadyLagMillis", oldestReadyLagMillis.get());
    }
    public void report(int activeWorkers) { log.info("reminder_task_center counters={} activeWorkers={}", snapshot(), activeWorkers); }
}
