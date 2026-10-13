package xiaozhi.modules.reminder.service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.reminder.dto.ReminderRecurrenceDTO;
import xiaozhi.modules.reminder.entity.ReminderEntity;

/** Calendar arithmetic jumps to the current period; downtime never expands into a backlog. */
public final class ReminderRecurrence {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FREQUENCIES = Set.of("ONCE", "DAILY", "WEEKLY", "MONTHLY", "YEARLY");
    private ReminderRecurrence() { }

    public static String normalize(ReminderRecurrenceDTO input, Instant anchor) {
        ReminderRecurrenceDTO rule = input == null ? new ReminderRecurrenceDTO() : input;
        String frequency = rule.getFrequency();
        if (frequency == null || !FREQUENCIES.contains(frequency)) {
            throw new RenException("不支持的重复频率");
        }
        int interval = rule.getInterval() == null ? 1 : rule.getInterval();
        if (interval < 1 || interval > 365) throw new RenException("重复间隔必须为1到365");
        if ("ONCE".equals(frequency) && (interval != 1 || rule.getUntil() != null)) {
            throw new RenException("ONCE不支持重复间隔或结束时间");
        }
        if (rule.getWeekdays() != null && (!"WEEKLY".equals(frequency)
                || rule.getWeekdays().isEmpty() || rule.getWeekdays().size() > 7
                || rule.getWeekdays().stream().anyMatch(day -> day == null || day < 1 || day > 7))) {
            throw new RenException("weekdays仅支持WEEKLY，取值1到7");
        }
        if (rule.getUntil() != null) {
            try {
                if (OffsetDateTime.parse(rule.getUntil()).toInstant().isBefore(anchor)) {
                    throw new RenException("重复结束时间不能早于首次提醒");
                }
            } catch (DateTimeException error) { throw new RenException("重复结束时间必须包含时区偏移"); }
        }
        ReminderRecurrenceDTO canonical = new ReminderRecurrenceDTO();
        canonical.setFrequency(frequency);
        canonical.setInterval(interval);
        canonical.setUntil(rule.getUntil());
        canonical.setWeekdays(rule.getWeekdays() == null ? null : rule.getWeekdays().stream().distinct().sorted().toList());
        try { return JSON.writeValueAsString(canonical); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    public static ReminderRecurrenceDTO read(String json) {
        if (json == null || json.isBlank()) return new ReminderRecurrenceDTO();
        try { return JSON.readValue(json, ReminderRecurrenceDTO.class); }
        catch (Exception error) { throw new IllegalStateException("Invalid persisted recurrence", error); }
    }

    public static void validateAnchor(String json, Instant anchor, ZoneId zone) {
        ReminderRecurrenceDTO rule = read(json);
        if ("WEEKLY".equals(rule.getFrequency()) && rule.getWeekdays() != null
                && !rule.getWeekdays().contains(anchor.atZone(zone).getDayOfWeek().getValue())) {
            throw new RenException("首次提醒日期必须属于选定的星期");
        }
    }

    public static Instant next(ReminderEntity schedule, Instant after) {
        ReminderRecurrenceDTO rule = read(schedule.getRecurrenceJson());
        if ("ONCE".equals(rule.getFrequency())) return null;
        ZoneId zone = ZoneId.of(schedule.getTimezone());
        ZonedDateTime anchor = Objects.requireNonNull(schedule.getScheduleAnchorAt()).toInstant().atZone(zone);
        LocalDate start = anchor.toLocalDate();
        LocalDate current = after.atZone(zone).toLocalDate();
        int interval = rule.getInterval();
        long elapsed = switch (rule.getFrequency()) {
            case "DAILY" -> ChronoUnit.DAYS.between(start, current);
            case "WEEKLY" -> ChronoUnit.WEEKS.between(monday(start), monday(current));
            case "MONTHLY" -> ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(current));
            case "YEARLY" -> current.getYear() - start.getYear();
            default -> throw new IllegalStateException("Unknown recurrence");
        };
        long period = Math.max(0, Math.floorDiv(elapsed, interval));
        for (int guard = 0; guard < 400; guard++, period++) {
            List<LocalDate> dates = new ArrayList<>();
            try {
                long distance = Math.multiplyExact(period, interval);
                switch (rule.getFrequency()) {
                    case "DAILY" -> dates.add(start.plusDays(distance));
                    case "WEEKLY" -> {
                        LocalDate week = monday(start).plusWeeks(distance);
                        List<Integer> days = rule.getWeekdays() == null ? List.of(start.getDayOfWeek().getValue()) : rule.getWeekdays();
                        for (int day : days) dates.add(week.plusDays(day - 1L));
                    }
                    case "MONTHLY" -> {
                        YearMonth month = YearMonth.from(start).plusMonths(distance);
                        if (month.isValidDay(start.getDayOfMonth())) dates.add(month.atDay(start.getDayOfMonth()));
                    }
                    case "YEARLY" -> {
                        int year = Math.toIntExact(start.getYear() + distance);
                        MonthDay day = MonthDay.from(start);
                        if (day.isValidYear(year)) dates.add(day.atYear(year));
                    }
                    default -> throw new IllegalStateException("Unknown recurrence");
                }
            } catch (DateTimeException | ArithmeticException exhausted) { return null; }
            for (LocalDate date : dates) {
                // java.time shifts gaps forward and uses the earlier offset on overlaps: one occurrence only.
                Instant candidate = date.atTime(anchor.toLocalTime()).atZone(zone).toInstant();
                if (!candidate.isBefore(anchor.toInstant()) && candidate.isAfter(after)) {
                    if (rule.getUntil() != null && candidate.isAfter(OffsetDateTime.parse(rule.getUntil()).toInstant())) return null;
                    return candidate;
                }
            }
        }
        return null; // No valid calendar date in a complete Gregorian cycle.
    }

    private static LocalDate monday(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
