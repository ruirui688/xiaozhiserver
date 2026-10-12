package xiaozhi.modules.billing.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.billing.dto.BillingAccountFilter;
import xiaozhi.modules.security.user.SecurityUser;

class BillingAccountServiceTest {
    JdbcTemplate jdbc; BillingAccountService service; BillingSupport support;
    MockedStatic<SecurityUser> security; UserDetail user;
    VolcengineBillingClient volc; DeepSeekBalanceClient deepseek;

    @BeforeEach void setup() throws Exception {
        jdbc = database(); support = new BillingSupport(new ObjectMapper().findAndRegisterModules());
        volc = mock(VolcengineBillingClient.class); deepseek = mock(DeepSeekBalanceClient.class);
        when(volc.accountRef()).thenReturn("account-a"); when(volc.enabled()).thenReturn(true);
        when(deepseek.accountRef()).thenReturn("deepseek-account");
        service = new BillingAccountService(jdbc, support, volc, deepseek);
        user = new UserDetail(); user.setSuperAdmin(1);
        security = mockStatic(SecurityUser.class); security.when(SecurityUser::getUser).thenReturn(user);
    }
    @AfterEach void close() { if(security!=null)security.close(); }

    static JdbcTemplate database() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        var jdbc = new JdbcTemplate(ds);
        for(String file:List.of("model-billing-management.sql","model-billing-open-expense-end.sql","model-billing-read-model.sql")) {
            String schema=Files.readString(Path.of("src/main/resources/db/changelog",file))
                    .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replace("scope_value(128)","scope_value").replace("bill_id(128)","bill_id");
            try(var connection=ds.getConnection()) {
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ByteArrayResource(schema.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
        return jdbc;
    }

    @Test void accountTotalsCountParentsOnceAndKeepLegacyEvidenceWithoutWorkspaceBindings() {
        bill("parent","bill-one","BILL","36.29","CNY","CNY",1);
        bill("detail","bill-one","DETAIL","36.29","CNY","CNY",null);
        var result=service.overview(filter());
        assertDecimal("36.29",list(result,"costs").getFirst().get("paidAmount"));
        assertDecimal("36.29",list(result,"costs").getFirst().get("payableAmount"));
        assertEquals(1,result.get("billCount")); assertEquals(1,result.get("detailCount"));
        assertEquals("PARTIAL",map(result,"coverage").get("status"));
        assertEquals("LEGACY_SNAPSHOT",map(result,"sync").get("lastAttemptStatus"));
        assertEquals("MATCHED",map(result,"reconciliation").get("status"));
        success(); result=service.overview(filter());
        assertEquals("SNAPSHOT",map(result,"coverage").get("status"));
        assertEquals("BILL_PERIOD",result.get("timeBasis"));
        assertEquals("OFFICIAL_PARENT_BILLS",result.get("amountBasis"));
    }

    @Test void monthUsesBillPeriodIncludingOpenAndCrossMonthConsumptionAndExcludesOtherAccount() {
        bill("open","open-bill","BILL","2","CNY",null,0);
        bill("cross","cross-bill","BILL","3","CNY",null,0);
        bill("outside","other-period","BILL","100","CNY",null,0);
        bill("other-account","other-bill","BILL","1000","CNY",null,0);
        jdbc.update("UPDATE ai_billing_bill SET expense_begin='2026-09-01 00:00:00',expense_end='2026-11-01 00:00:00' WHERE id='cross'");
        jdbc.update("UPDATE ai_billing_bill SET bill_period='2026-09' WHERE id='outside'");
        jdbc.update("UPDATE ai_billing_bill SET account_ref='account-b' WHERE id='other-account'");
        var result=service.overview(filter());
        assertEquals(2,result.get("billCount"));assertDecimal("5",list(result,"costs").getFirst().get("paidAmount"));
    }

    @Test void crossCurrencyPaymentsRemainUnknownAndParentDetailMismatchCannotSilentlyPass() {
        bill("parent","bill-one","BILL","10","CNY","USD",1);
        bill("detail","bill-one","DETAIL","9","CNY","CNY",null);success();
        var result=service.overview(filter());var cost=list(result,"costs").getFirst();
        assertNull(cost.get("paidAmount"));assertNull(cost.get("unpaidAmount"));
        assertEquals(false,cost.get("paymentCurrencyVerified")); assertDecimal("10",cost.get("payableAmount"));
        assertEquals("PARTIAL",map(result,"coverage").get("status"));
        var page=service.bills(filter());
        assertEquals(1,page.getTotal());assertEquals("AMOUNT_MISMATCH",map(page.getList().getFirst(),"reconciliation").get("status"));
        assertNull(page.getList().getFirst().get("paidAmount"));
    }

    @Test void successfulEmptyIsDistinctFromNeverObtainedAndFailureDoesNotEraseSuccess() {
        assertEquals("NOT_OBTAINED",map(service.overview(filter()),"coverage").get("status"));
        success();var result=service.overview(filter());
        assertEquals("EMPTY_CONFIRMED",map(result,"coverage").get("status"));
        assertEquals("EMPTY_CONFIRMED",map(result,"reconciliation").get("status"));
        run("FAILED","account-a","2026-10",Instant.now());
        result=service.overview(filter());assertEquals("PARTIAL",map(result,"coverage").get("status"));
        assertNotNull(map(result,"sync").get("lastSuccessAt"));assertEquals(true,map(result,"sync").get("isStale"));
    }

    @Test void syncScopeDoesNotUseOtherAccountsOrOtherMonths() {
        run("SUCCESS","account-b","2026-10",Instant.now());run("SUCCESS","account-a","2026-09",Instant.now());
        assertNull(service.status(filter()).get("lastSuccessAt"));
        assertEquals("NOT_SYNCED",service.status(filter()).get("lastAttemptStatus"));
    }

    @Test void mysqlLocalDateTimeSnapshotsAndEmptySuccessfulRunsExpireAfterTwentyFourHours() {
        var mysql=BillingJdbcTimeFixture.localDateTimes(jdbc);
        service=new BillingAccountService(mysql,support,volc,deepseek);
        bill("old-parent","old-bill","BILL","2","CNY",null,0);
        jdbc.update("UPDATE ai_billing_bill SET synced_at=?",BillingSupport.time(Instant.now().minusSeconds(90000)));
        assertInstanceOf(LocalDateTime.class,mysql.queryForObject("SELECT MAX(synced_at) FROM ai_billing_bill",Object.class));
        assertEquals(true,service.status(filter()).get("isStale"));
        jdbc.update("DELETE FROM ai_billing_bill");run("SUCCESS","account-a","2026-10",Instant.now().minusSeconds(90000));
        assertEquals(true,service.status(filter()).get("isStale"));
        run("SUCCESS","account-a","2026-10",Instant.now());
        assertEquals(false,service.status(filter()).get("isStale"));
    }

    @Test void paginationAndDetailExpansionUseIndependentLayerAndParentId() {
        bill("parent-a","a","BILL","2","CNY",null,2);
        bill("detail-a1","a","DETAIL","1","CNY",null,null);
        bill("detail-a2","a","DETAIL","1","CNY",null,null);
        bill("parent-b","b","BILL","4","CNY",null,1);
        bill("detail-b","b","DETAIL","4","CNY",null,null);
        var f=filter();f.setLimit(1);f.setPage(2);
        var page=service.bills(f);assertEquals(2,page.getTotal());assertEquals("b",page.getList().getFirst().get("billId"));
        f.setLevel("DETAIL");f.setBillId("a");f.setPage(1);f.setLimit(100);
        page=service.bills(f);assertEquals(2,page.getTotal());
        assertTrue(page.getList().stream().allMatch(row->Boolean.FALSE.equals(row.get("aggregationIncluded"))));
        assertTrue(page.getList().stream().allMatch(row->"MATCHED".equals(map(row,"reconciliation").get("status"))));
    }

    @Test void platformAccountAccessRejectsOrdinaryWorkspaceAdminAndUnknownAccount() {
        var unknown=filter();unknown.setAccountRef("not-known");assertThrows(RenException.class,()->service.overview(unknown));
        user.setSuperAdmin(0);
        assertThrows(RenException.class,service::accounts);
        assertThrows(RenException.class,()->service.overview(filter()));
        assertThrows(RenException.class,()->service.bills(filter()));
        assertThrows(RenException.class,()->service.status(filter()));
    }

    private BillingAccountFilter filter(){var f=new BillingAccountFilter();f.setProvider("VOLCENGINE");f.setAccountRef("account-a");f.setBillPeriod("2026-10");return f;}
    private void success(){run("SUCCESS","account-a","2026-10",Instant.now().minusSeconds(2));}
    private void run(String status,String account,String month,Instant now){jdbc.update("INSERT INTO ai_billing_sync_run(id,provider,account_ref,bill_period,status,created_by,started_at,finished_at) VALUES (?,'VOLCENGINE',?,?,?,1,?,?)",UUID.randomUUID().toString(),account,month,status,BillingSupport.time(now),BillingSupport.time(now));}
    private void bill(String id,String parent,String level,String amount,String currency,String settlement,Integer detailCount) {
        var evidence=detailCount==null?Map.of():Map.of("supplier_usage",Map.of("BillDetailNum",detailCount));
        jdbc.update("INSERT INTO ai_billing_bill(id,provider,account_ref,bill_id,level,bill_period,expense_begin,expense_end,product,currency,settlement_currency,payable_amount,paid_amount,unpaid_amount,attribution_level,source,evidence_hash,evidence_json,synced_at) VALUES (?,'VOLCENGINE','account-a',?,?,'2026-10','2026-10-01 00:00:00',NULL,'TTS-SeedTTS2.0',?,?,?,?,0,'UNASSIGNED','VOLCENGINE_API',?,?,?)",id,parent,level,currency,settlement,new BigDecimal(amount),new BigDecimal(amount),BillingSupport.hash(id),support.json(evidence),BillingSupport.time(Instant.now()));
    }
    private static void assertDecimal(String expected,Object actual){assertNotNull(actual);assertEquals(0,new BigDecimal(expected).compareTo((BigDecimal)actual));}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Map<String,Object> value,String key){return (Map<String,Object>)value.get(key);}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Map<String,Object> value,String key){return (List<Map<String,Object>>)value.get(key);}
}
