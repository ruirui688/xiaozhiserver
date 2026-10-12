package xiaozhi.modules.billing.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.billing.dto.BillingAccountFilter;
import xiaozhi.modules.security.ops.OpsGrant;
import xiaozhi.modules.security.ops.OpsPermissionCatalog;
import xiaozhi.modules.security.user.SecurityUser;

/** Real SQL constraints, including the historical schema that rejected OPS employees. */
class BillingOpsActorTest {
    JdbcTemplate jdbc;
    VolcengineBillingClient client;
    BillingBillService bills;
    BillingSyncService service;
    UserDetail employee;
    MockedStatic<SecurityUser> security;
    String period=YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();

    @BeforeEach void setup() throws Exception {
        jdbc=BillingAccountServiceTest.database();
        client=mock(VolcengineBillingClient.class);bills=mock(BillingBillService.class);
        when(client.enabled()).thenReturn(true);when(client.accountRef()).thenReturn("account-a");
        when(client.fetch(anyString(),anyString())).thenReturn(List.of());
        service=new BillingSyncService(client,bills,jdbc);
        employee=new UserDetail();employee.setAudience("OPS");employee.setEmployeeId("finance-test");
        employee.setGrants(List.of(new OpsGrant("SUPPLIER_FINANCE","PLATFORM","platform-services",
                OpsPermissionCatalog.TEMPLATES.get("SUPPLIER_FINANCE").permissions())));
        security=mockStatic(SecurityUser.class);security.when(SecurityUser::getUser).thenReturn(employee);
    }

    @AfterEach void close(){security.close();}

    void migrate() throws Exception {
        String sql=Files.readString(Path.of("src/main/resources/db/changelog/model-billing-ops-actor.sql"));
        for(String statement:sql.split(";"))if(!statement.isBlank())jdbc.execute(statement);
    }

    void historicalRun() {
        var time=BillingSupport.time(Instant.now().minusSeconds(60));
        jdbc.update("INSERT INTO ai_billing_sync_run(id,provider,account_ref,bill_period,status,created_by,started_at,finished_at) VALUES('historic','VOLCENGINE','account-a',?,'SUCCESS',42,?,?)",period,time,time);
    }

    @Test void oldNotNullActorFailsAndMigrationPreservesHistoryWhileAllowingRealEmployee() throws Exception {
        historicalRun();
        assertNull(employee.getId());
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update(
                "INSERT INTO ai_billing_sync_run(id,provider,account_ref,bill_period,status,created_by,started_at) VALUES('old-ops','VOLCENGINE','account-a',?,'RUNNING',?,?)",
                period,employee.getId(),BillingSupport.time(Instant.now())));
        migrate();
        assertEquals("SUCCESS",service.sync(period,"VOLCENGINE","account-a").get("status"));
        var current=jdbc.queryForMap("SELECT * FROM ai_billing_sync_run WHERE id<>'historic'");
        assertNull(current.get("created_by"));assertEquals("finance-test",current.get("created_by_employee_id"));
        assertEquals("SUCCESS",current.get("status"));assertNotNull(current.get("finished_at"));
        var previous=jdbc.queryForMap("SELECT created_by,created_by_employee_id FROM ai_billing_sync_run WHERE id='historic'");
        assertEquals(42L,previous.get("created_by"));assertNull(previous.get("created_by_employee_id"));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update(
                "INSERT INTO ai_billing_sync_run(id,provider,status,started_at) VALUES('no-actor','VOLCENGINE','RUNNING',?)",BillingSupport.time(Instant.now())));
    }

    @Test void supplierFailureRecordsFailedEmployeeRunAndKeepsPreviousCompleteSnapshot() throws Exception {
        migrate();historicalRun();
        jdbc.update("INSERT INTO ai_billing_bill(id,provider,account_ref,bill_id,level,bill_period,expense_begin,expense_end,currency,payable_amount,attribution_level,source,evidence_hash,evidence_json,synced_at) VALUES('old-bill','VOLCENGINE','account-a','supplier-bill','BILL',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'CNY',12.34,'UNASSIGNED','VOLCENGINE_API',?,'{}',CURRENT_TIMESTAMP)",period,"0".repeat(64));
        var oldBill=jdbc.queryForMap("SELECT * FROM ai_billing_bill WHERE id='old-bill'");
        when(client.fetch("ListBillDetail",period)).thenThrow(new RenException("供应商查询服务暂不可用"));
        assertThrows(RenException.class,()->service.sync(period,"VOLCENGINE","account-a"));
        var failed=jdbc.queryForMap("SELECT * FROM ai_billing_sync_run WHERE id<>'historic'");
        assertEquals("FAILED",failed.get("status"));assertEquals("SYNC_FAILED",failed.get("error_code"));
        assertEquals("finance-test",failed.get("created_by_employee_id"));assertNull(failed.get("created_by"));
        assertNotNull(failed.get("finished_at"));assertEquals(oldBill,jdbc.queryForMap("SELECT * FROM ai_billing_bill WHERE id='old-bill'"));
        verify(bills,never()).replacePeriod(anyString(),anyString(),anyString(),anyList());
        var accounts=new BillingAccountService(jdbc,new BillingSupport(new ObjectMapper()),client,mock(DeepSeekBalanceClient.class));
        var filter=new BillingAccountFilter();filter.setProvider("VOLCENGINE");filter.setAccountRef("account-a");filter.setBillPeriod(period);
        var status=accounts.status(filter);
        assertEquals("FAILED",status.get("lastAttemptStatus"));assertNotNull(status.get("lastSuccessAt"));assertEquals(true,status.get("isStale"));
    }

    @Test void runCreationFailureNeverCallsSupplierOrClaimsARecordedAttempt() {
        var error=assertThrows(RenException.class,()->service.sync(period,"VOLCENGINE","account-a"));
        assertEquals("账单同步记录创建失败，尚未查询供应商",error.getMessage());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_sync_run",Integer.class));
        verify(client,never()).fetch(anyString(),anyString());verifyNoInteractions(bills);
    }

    @Test void missingEmployeeIdentityCannotWriteAnUnattributedRun() throws Exception {
        migrate();employee.setEmployeeId(" ");
        assertThrows(RenException.class,()->service.sync(period,"VOLCENGINE","account-a"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_sync_run",Integer.class));
        verifyNoInteractions(client,bills);
    }
}
