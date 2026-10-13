package xiaozhi.modules.reminder.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.reminder.dto.ReminderRecurrenceDTO;
import xiaozhi.modules.reminder.entity.ReminderEntity;

class ReminderRecurrenceTest {
    private ReminderEntity schedule(String frequency, String anchor, String zone) {
        ReminderEntity result = new ReminderEntity();
        result.setScheduleAnchorAt(Date.from(Instant.parse(anchor)));
        result.setRemindAt(result.getScheduleAnchorAt());
        result.setTimezone(zone);
        ReminderRecurrenceDTO rule = new ReminderRecurrenceDTO();
        rule.setFrequency(frequency);
        result.setRecurrenceJson(ReminderRecurrence.normalize(rule, Instant.parse(anchor)));
        return result;
    }

    @Test void monthEndSkipsInvalidDatesWithoutDriftingAnchor() {
        ReminderEntity rule = schedule("MONTHLY", "2026-01-31T06:00:00Z", "Asia/Shanghai");
        assertEquals(Instant.parse("2026-03-31T06:00:00Z"), ReminderRecurrence.next(rule, Instant.parse("2026-02-01T00:00:00Z")));
        rule.setRemindAt(Date.from(Instant.parse("2026-03-31T06:00:00Z")));
        assertEquals(Instant.parse("2026-05-31T06:00:00Z"), ReminderRecurrence.next(rule, rule.getRemindAt().toInstant()));
    }

    @Test void leapDaySkipsUntilNextLeapYear() {
        ReminderEntity rule = schedule("YEARLY", "2024-02-29T06:00:00Z", "UTC");
        assertEquals(Instant.parse("2028-02-29T06:00:00Z"), ReminderRecurrence.next(rule, Instant.parse("2025-01-01T00:00:00Z")));
    }

    @Test void dstGapMovesOnlyThatOccurrenceForwardAndOverlapOccursOnce() {
        ReminderEntity gap = schedule("DAILY", "2026-03-07T07:30:00Z", "America/New_York");
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), ReminderRecurrence.next(gap, gap.getScheduleAnchorAt().toInstant()));
        assertEquals(Instant.parse("2026-03-09T06:30:00Z"), ReminderRecurrence.next(gap, Instant.parse("2026-03-08T07:30:00Z")));
        ReminderEntity overlap = schedule("DAILY", "2026-10-31T05:30:00Z", "America/New_York");
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), ReminderRecurrence.next(overlap, overlap.getScheduleAnchorAt().toInstant()));
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), ReminderRecurrence.next(overlap, Instant.parse("2026-11-01T05:30:00Z")));
    }

    @Test void decadeDowntimeJumpsDirectlyToFuture() {
        ReminderEntity rule = schedule("DAILY", "2010-01-01T06:00:00Z", "UTC");
        assertTimeout(Duration.ofMillis(100), () -> assertEquals(Instant.parse("2040-01-01T06:00:00Z"),
                ReminderRecurrence.next(rule, Instant.parse("2040-01-01T05:00:00Z"))));
    }

    @Test void selectedWeekdaysAndUntilAreEnforced() {
        ReminderEntity rule = schedule("WEEKLY", "2026-10-12T06:00:00Z", "UTC");
        ReminderRecurrenceDTO dto = ReminderRecurrence.read(rule.getRecurrenceJson());
        dto.setWeekdays(List.of(5, 1, 3));
        dto.setUntil("2026-10-14T06:00:00Z");
        rule.setRecurrenceJson(ReminderRecurrence.normalize(dto, rule.getScheduleAnchorAt().toInstant()));
        assertEquals(Instant.parse("2026-10-14T06:00:00Z"), ReminderRecurrence.next(rule, rule.getScheduleAnchorAt().toInstant()));
        assertNull(ReminderRecurrence.next(rule, Instant.parse("2026-10-14T06:00:00Z")));
        assertThrows(RenException.class, () -> ReminderRecurrence.validateAnchor(rule.getRecurrenceJson(), Instant.parse("2026-10-13T06:00:00Z"), ZoneId.of("UTC")));
    }

    @Test void invalidAndMeaninglessRulesFailExplicitly() {
        ReminderRecurrenceDTO dto = new ReminderRecurrenceDTO();
        dto.setInterval(2);
        assertThrows(RenException.class, () -> ReminderRecurrence.normalize(dto, Instant.now()));
        dto.setFrequency("DAILY");
        dto.setWeekdays(List.of(1));
        assertThrows(RenException.class, () -> ReminderRecurrence.normalize(dto, Instant.now()));
    }

    @Test void retryBackoffIsBoundedAndGrowsInsteadOfPollingConstantly() {
        for (int i = 0; i < 100; i++) {
            long first = ReminderDeliveryExecutor.retryDelayMillis(1);
            long last = ReminderDeliveryExecutor.retryDelayMillis(20);
            assertTrue(first >= 15_000 && first <= 18_750);
            assertTrue(last >= 240_000 && last <= 300_000);
        }
    }
}
