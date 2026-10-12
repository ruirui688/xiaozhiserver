package xiaozhi.modules.billing.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import xiaozhi.common.exception.RenException;

class VolcengineBillPaginationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void includesZeroAmountBillsInBothCountAndRows() throws Exception {
        var client = spy(new VolcengineBillingClient(mapper, true, "test-access", "test-secret", "account", "cn-north-1"));
        doAnswer(invocation -> {
            Map<String, Object> body = invocation.getArgument(1);
            // The provider default can count only paid bills while returning free rows too.
            int total = Integer.valueOf(0).equals(body.get("IgnoreZero")) ? 2 : 1;
            return mapper.readTree("{\"Result\":{\"Total\":" + total
                    + ",\"List\":[{\"PaidAmount\":\"1.00\"},{\"PaidAmount\":\"0.00\"}]}}");
        }).when(client).request(eq("ListBill"), anyMap(), eq("2022-01-01"), eq("billing"));
        var rows = client.fetch("ListBill", "2026-10");
        assertEquals(2, rows.size());
        assertEquals("0.00", rows.get(1).path("PaidAmount").asText());
    }

    @Test void inconsistentSupplierCountStillFailsInsteadOfDroppingRows() throws Exception {
        var client = spy(new VolcengineBillingClient(mapper, true, "test-access", "test-secret", "account", "cn-north-1"));
        doReturn(mapper.readTree("{\"Result\":{\"Total\":1,\"List\":[{},{}]}}"))
                .when(client).request(eq("ListBill"), anyMap(), eq("2022-01-01"), eq("billing"));
        assertThrows(RenException.class, () -> client.fetch("ListBill", "2026-10"));
    }
}
