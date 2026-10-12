package xiaozhi.modules.billing.controller;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import xiaozhi.common.page.PageData;

class BillingPresentationTest {
    @Test void amountsRemainExactAndUnknownAmountsRemainNullAcrossPagination() {
        Map<String,Object> cost=new LinkedHashMap<>();cost.put("currency","CNY");
        cost.put("payableAmount",new BigDecimal("99999999999999999999.123456789"));cost.put("paidAmount",null);
        cost.put("startedAt",Timestamp.valueOf(LocalDateTime.parse("2026-10-01T00:00:00")));
        var response=(Map<?,?>)BillingController.present(new PageData<>(List.of(cost),1));
        var row=(Map<?,?>)((List<?>)response.get("list")).getFirst();
        assertEquals("99999999999999999999.123456789",row.get("payableAmount"));assertNull(row.get("paidAmount"));
        assertEquals("2026-10-01T00:00:00Z",row.get("startedAt"));assertEquals(1,response.get("total"));
    }
}
