package xiaozhi.modules.reminder.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import xiaozhi.common.exception.RenException;

class ReminderTimeSupportTest {
    @Test
    void parsesOffsetTimeAndKeepsIanaTimezone() {
        var parsed = ReminderTimeSupport.parse("2026-10-14T14:00:00+08:00", "Asia/Shanghai");

        assertEquals(Instant.parse("2026-10-14T06:00:00Z"), parsed.instant());
        assertEquals("Asia/Shanghai", parsed.zoneId().getId());
    }

    @Test
    void rejectsLocalTimeAndInvalidTimezone() {
        assertThrows(RenException.class,
                () -> ReminderTimeSupport.parse("2026-10-14T14:00:00", "Asia/Shanghai"));
        assertThrows(RenException.class,
                () -> ReminderTimeSupport.parse("2026-10-14T14:00:00+08:00", "Shanghai"));
    }

    @Test
    void enforcesFiveSecondAndOneYearWindow() {
        Instant now = Instant.parse("2026-10-13T00:00:00Z");

        assertThrows(RenException.class,
                () -> ReminderTimeSupport.requireCreationWindow(now.plusSeconds(4), now));
        assertDoesNotThrow(
                () -> ReminderTimeSupport.requireCreationWindow(now.plusSeconds(5), now));
        assertThrows(RenException.class,
                () -> ReminderTimeSupport.requireCreationWindow(now.plusSeconds(366L * 24 * 3600), now));
    }
}
