package xiaozhi.modules.billing.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import xiaozhi.common.exception.RenException;

class BillingInputTest {
    ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    BillingBillService bills=new BillingBillService(mock(JdbcTemplate.class),new BillingSupport(mapper),mock(BillingBindingService.class));

    @Test void officialDetailUsesExactReturnedAmountAndLeavesAbsentPaidAmountUnknown() throws Exception {
        var source=mapper.readTree("""
            {"BillID":"Bill1","BillDetailId":"Detail1","BillPeriod":"2026-10","ExpenseBeginTime":"2026-10-01 00:00:00","ExpenseEndTime":"2026-10-01 01:00:00","Currency":"CNY","PayableAmount":"-0.123456789","Product":"BigTTS","Project":"project-a","Authorization":"must-not-store"}
            """);
        var row=bills.official(source,"account-a","2026-10",true);
        assertEquals(new BigDecimal("-0.123456789"),row.get("payable_amount"));assertNull(row.get("paid_amount"));
        assertEquals("TTS",row.get("service_type"));assertEquals(LocalDateTime.parse("2026-09-30T16:00:00"),row.get("expense_begin"));
        assertFalse(row.get("evidence_json").toString().contains("Authorization"));assertFalse(row.get("evidence_json").toString().contains("must-not-store"));
        assertEquals("Detail1",row.get("bill_detail_id"));
    }

    @Test void missingOfficialAmountsAreRejectedInsteadOfEstimatedFromUsage() throws Exception {
        var source=mapper.readTree("""
            {"BillID":"Bill1","BillPeriod":"2026-10","ExpenseBeginTime":"2026-10-01 00:00:00","ExpenseEndTime":"2026-10-01 01:00:00","Currency":"CNY","Count":"2000","Price":"0.5"}
            """);
        assertThrows(RenException.class,()->bills.official(source,"account-a","2026-10",false));
        assertThrows(RenException.class,()->BillingBillService.amount("0.0000000001"));
    }

    @Test void csvParserHandlesQuotedCommasAndRejectsMalformedOrExtraColumns() {
        assertEquals(List.of(List.of("a","b,c","d\"e")),BillingCsvService.records("a,\"b,c\",\"d\"\"e\"\r\n"));
        assertThrows(RenException.class,()->BillingCsvService.records("\"a\"unexpected,b"));
        assertThrows(RenException.class,()->BillingCsvService.records("a,\"unfinished"));
        BillingCsvService csv=new BillingCsvService(bills);
        assertThrows(RenException.class,()->csv.parse("bill_id,api_key\na,sensitive","DEEPSEEK","account-a"));
        assertThrows(RenException.class,()->csv.parse(String.join(",",BillingCsvService.HEADERS)+"\n","DEEPSEEK","account-a"));
    }

    @Test void signingIsDeterministicBodyBoundAndLimitedToBillingService() {
        var signed=VolcengineBillingClient.sign("{}","Action=ListBill&Version=2022-01-01",Instant.parse("2026-10-12T00:00:00Z"),"test-access","test-secret","cn-north-1");
        assertEquals("20261012T000000Z",signed.get("X-Date"));
        assertEquals(BillingSupport.hash("{}"),signed.get("X-Content-Sha256"));
        assertTrue(signed.get("Authorization").contains("/cn-north-1/billing/request"));
        assertFalse(signed.get("Authorization").contains("test-secret"));
        assertNotEquals(signed.get("Authorization"),VolcengineBillingClient.sign("{\"BillPeriod\":\"2026-10\"}","Action=ListBill&Version=2022-01-01",Instant.parse("2026-10-12T00:00:00Z"),"test-access","test-secret","cn-north-1").get("Authorization"));
    }

    @Test void unconfiguredClientFailsBeforeMakingAnExternalRequest() {
        var client=new VolcengineBillingClient(mapper,false,"","","","cn-north-1");
        assertFalse(client.enabled());assertThrows(RenException.class,()->client.fetch("ListBill","2026-10"));
    }
}
