package xiaozhi.modules.billing.dto;

import java.time.*;
import lombok.Data;
import xiaozhi.common.exception.RenException;

@Data
public class BillingAccountFilter {
    private String provider, accountRef, product, billId;
    private String billPeriod = YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();
    private String level = "BILL";
    private int page = 1, limit = 20;

    public void validate() {
        if (!java.util.Set.of("VOLCENGINE", "DEEPSEEK").contains(provider == null ? "" : provider)
                || accountRef == null || accountRef.isBlank() || accountRef.length() > 128)
            throw new RenException("请选择有效的供应商账号");
        try { YearMonth.parse(billPeriod); } catch (RuntimeException e) { throw new RenException("账期格式必须为 yyyy-MM"); }
        if (page < 1 || page > 1_000_000 || limit < 1 || limit > 100
                || !java.util.Set.of("BILL", "DETAIL").contains(level)) throw new RenException("账单查询参数无效");
        if (product != null && product.length() > 128 || billId != null && billId.length() > 256)
            throw new RenException("账单筛选参数过长");
    }
}
