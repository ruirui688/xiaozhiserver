package xiaozhi.modules.reminder.config;

import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Hard safety bounds apply even to accidentally oversized operator configuration. */
@Component("reminderTaskProperties")
@ConfigurationProperties(prefix = "xiaozhi.reminder")
@Setter
public class ReminderTaskProperties {
    private int scanMs = 1000;
    private int batchSize = 20;
    private int workers = 2;
    private int dispatchPerSecond = 4;
    private int maxAttempts = 8;
    private int graceSeconds = 600;
    private int retentionDays = 30;
    private int cleanupBatch = 200;
    public int getScanMs() { return bound(scanMs, 1000, 60000); }
    public int getBatchSize() { return bound(batchSize, 1, 100); }
    public int getWorkers() { return bound(workers, 1, 8); }
    public int getDispatchPerSecond() { return bound(dispatchPerSecond, 1, 20); }
    public int getMaxAttempts() { return bound(maxAttempts, 1, 20); }
    public int getGraceSeconds() { return bound(graceSeconds, 30, 1800); }
    public int getRetentionDays() { return bound(retentionDays, 7, 365); }
    public int getCleanupBatch() { return bound(cleanupBatch, 1, 1000); }
    private static int bound(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
