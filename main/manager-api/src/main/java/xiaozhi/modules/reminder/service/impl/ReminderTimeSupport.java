package xiaozhi.modules.reminder.service.impl;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import org.apache.commons.lang3.StringUtils;

import xiaozhi.common.exception.RenException;

final class ReminderTimeSupport {
    private ReminderTimeSupport() {
    }

    static ParsedTime parse(String raw, String timezone) {
        try {
            ZoneId zoneId = ZoneId.of(StringUtils.trim(timezone));
            Instant instant = OffsetDateTime.parse(StringUtils.trim(raw)).toInstant();
            return new ParsedTime(instant, zoneId);
        } catch (DateTimeException | NullPointerException error) {
            throw new RenException("提醒时间必须是带时区偏移的ISO-8601时间，且timezone必须是有效IANA时区");
        }
    }

    static void requireCreationWindow(Instant remindAt, Instant now) {
        if (remindAt.isBefore(now.plusSeconds(5))) {
            throw new RenException("提醒时间至少需要晚于当前时间5秒");
        }
        if (remindAt.isAfter(now.plus(365, ChronoUnit.DAYS))) {
            throw new RenException("提醒时间不能超过未来1年");
        }
    }

    static String format(Date value, String timezone) {
        if (value == null) {
            return null;
        }
        return value.toInstant().atZone(ZoneId.of(timezone)).toOffsetDateTime().toString();
    }

    record ParsedTime(Instant instant, ZoneId zoneId) {
    }
}
