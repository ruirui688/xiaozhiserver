package xiaozhi.modules.billing.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.security.user.SecurityUser;

class BillingSyncServiceTest {
    VolcengineBillingClient client; BillingBillService bills; JdbcTemplate jdbc; BillingSyncService service;
    MockedStatic<SecurityUser> security; UserDetail user;
    String period=YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();

    @BeforeEach void setup() {
        client=mock(VolcengineBillingClient.class);bills=mock(BillingBillService.class);jdbc=mock(JdbcTemplate.class);
        service=new BillingSyncService(client,bills,jdbc);user=new UserDetail();user.setId(null);user.setAudience("OPS");user.setEmployeeId("finance-test");user.setGrants(java.util.List.of(new xiaozhi.modules.security.ops.OpsGrant("SUPPLIER_FINANCE","PLATFORM","platform-services",xiaozhi.modules.security.ops.OpsPermissionCatalog.TEMPLATES.get("SUPPLIER_FINANCE").permissions())));
        security=mockStatic(SecurityUser.class);security.when(SecurityUser::getUser).thenReturn(user);
    }
    @AfterEach void close(){security.close();}

    @Test void deepSeekSyncIsRejectedBeforeAnySupplierOrDatabaseAction() {
        var error=assertThrows(RenException.class,()->service.sync(period,"DEEPSEEK","deepseek-account"));
        assertTrue(error.getMessage().contains("DeepSeek"));verifyNoInteractions(client,bills,jdbc);
    }
    @Test void wrongOrIncompleteAccountTargetCannotSyncTheConfiguredAccount() {
        when(client.accountRef()).thenReturn("configured-account");
        assertThrows(RenException.class,()->service.sync(period,"VOLCENGINE","different-account"));
        assertThrows(RenException.class,()->service.sync(period,"VOLCENGINE",null));
        assertThrows(RenException.class,()->service.sync(period,null,"configured-account"));
        assertThrows(RenException.class,()->service.sync(period,"UNKNOWN","configured-account"));
        verify(client,never()).fetch(anyString(),anyString());verifyNoInteractions(bills,jdbc);
    }
    @Test void legacyPeriodOnlyRequestStillSyncsVolcengine() {
        configured();assertEquals("SUCCESS",service.sync(period).get("status"));
        verify(client).fetch("ListBill",period);verify(client).fetch("ListBillDetail",period);
        verify(bills).replacePeriod("VOLCENGINE","configured-account",period,List.of());
    }
    @Test void explicitConfiguredAccountSyncUsesOnlyThatAccount() {
        configured();assertEquals("SUCCESS",service.sync(period,"VOLCENGINE","configured-account").get("status"));
        verify(client).fetch("ListBill",period);verify(client).fetch("ListBillDetail",period);
        verify(bills).replacePeriod("VOLCENGINE","configured-account",period,List.of());
    }
    @Test void workspaceAdminCannotTriggerAccountSync() {
        user.setAudience("USER");user.setId(1L);user.setEmployeeId(null);user.setGrants(java.util.List.of());assertThrows(RenException.class,()->service.sync(period));
        verifyNoInteractions(client,bills,jdbc);
    }
    private void configured(){when(client.enabled()).thenReturn(true);when(client.accountRef()).thenReturn("configured-account");when(client.fetch(anyString(),anyString())).thenReturn(List.of());}
}
