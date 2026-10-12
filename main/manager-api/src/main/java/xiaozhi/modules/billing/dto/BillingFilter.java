package xiaozhi.modules.billing.dto;

import java.time.*;
import lombok.Data;
import xiaozhi.common.exception.RenException;

@Data
public class BillingFilter {
    private String startDate, endDate, provider, serviceType, agentId, deviceId, modelId, keyword, board, purpose, level, billId, accountRef;
    private int page = 1;
    private int limit = 20;
    private boolean unattributed;
    public Instant from() { return date(startDate, LocalDate.now(ZoneId.of("Asia/Shanghai")).withDayOfMonth(1)).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(); }
    public Instant to() { return date(endDate, LocalDate.now(ZoneId.of("Asia/Shanghai"))).plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(); }
    private LocalDate date(String value, LocalDate fallback) {
        try { return value == null || value.isBlank() ? fallback : LocalDate.parse(value); }
        catch (DateTimeException e) { throw new RenException("日期格式必须为 yyyy-MM-dd"); }
    }
    public void validate() {
        if (page < 1 || limit < 1 || limit > 100 || page > 1_000_000) throw new RenException("分页参数无效");
        if (!from().isBefore(to()) || Duration.between(from(), to()).toDays() > 732) throw new RenException("日期范围无效，最多查询两年");
        if (level!=null && !java.util.Set.of("BILL","DETAIL").contains(level)) throw new RenException("账单层级无效");
        if (billId!=null && billId.length()>256) throw new RenException("账单标识过长");
        for (String value : new String[]{provider, serviceType, agentId, deviceId, modelId, keyword, board,purpose,accountRef})
            if (value != null && value.length() > 128) throw new RenException("筛选参数过长");
    }
}
