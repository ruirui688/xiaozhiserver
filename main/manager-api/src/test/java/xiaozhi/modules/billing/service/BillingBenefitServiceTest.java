package xiaozhi.modules.billing.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.security.user.SecurityUser;

class BillingBenefitServiceTest {
    JdbcTemplate jdbc; BillingBenefitService service; VolcengineBillingClient volc; DeepSeekBalanceClient deepseek;
    ObjectMapper mapper; MockedStatic<SecurityUser> security; UserDetail user;
    VolcengineSpeechBenefitClient speech;

    @BeforeEach void setUp() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/changelog/model-billing-benefits.sql"))
                .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", ""));
        jdbc.execute("CREATE TABLE ai_billing_binding(provider VARCHAR,account_ref VARCHAR,workspace_id VARCHAR,agent_id VARCHAR,device_id VARCHAR,scope_type VARCHAR,scope_value VARCHAR,valid_from TIMESTAMP,valid_to TIMESTAMP)");
        mapper = new ObjectMapper().findAndRegisterModules();
        var queries = mock(BillingQueryService.class); when(queries.workspace()).thenReturn("workspace-a");
        volc = mock(VolcengineBillingClient.class); deepseek = mock(DeepSeekBalanceClient.class);
        when(volc.enabled()).thenReturn(true); when(volc.accountRef()).thenReturn("volc-account");
        when(deepseek.accountRef()).thenReturn("deepseek-account");
        when(volc.fetchResourcePackages()).thenReturn(List.of(mapper.readTree("""
                {"InstanceNo":"package-1","InstanceName":"Official pack","TotalAmount":"1000",
                 "AvailableAmount":"25.000000001","Unit":"characters","Status":"Effective"}
                """)));
        speech = mock(VolcengineSpeechBenefitClient.class); when(speech.snapshotRef()).thenReturn("speech-account");
        service = new BillingBenefitService(jdbc, new BillingSupport(mapper), queries, volc, deepseek, speech);
        user = new UserDetail(); user.setSuperAdmin(1);
        security = mockStatic(SecurityUser.class); security.when(SecurityUser::getUser).thenReturn(user);
    }
    @AfterEach void close() { security.close(); }

    @Test void sharedAccountPackDoesNotLeakIntoWorkspaceAndExactResourceBindingDoes() {
        service.sync();
        assertTrue(list(service.overview(false), "packages").isEmpty());
        assertEquals(1, list(service.overview(true), "packages").size());
        bind("PROJECT", "default", "workspace-a");
        assertTrue(list(service.overview(false), "packages").isEmpty());
        bind("RESOURCE", "package-1", "workspace-b");
        assertTrue(list(service.overview(false), "packages").isEmpty());
        jdbc.update("DELETE FROM ai_billing_binding"); bind("RESOURCE", "package-1", "workspace-a");
        var packages = list(service.overview(false), "packages");
        assertEquals(1, packages.size()); assertEquals("25.000000001", packages.getFirst().get("availableAmount"));
    }

    @Test void nonAdminCannotReadAccountBalancesOrTriggerSupplierQuery() {
        service.sync(); user.setSuperAdmin(0);
        assertThrows(RenException.class, () -> service.overview(true));
        assertThrows(RenException.class, service::sync);
        assertTrue(list(service.overview(false), "balances").isEmpty());
        verify(volc, times(1)).fetchResourcePackages();
    }

    @Test void failedRefreshPreservesLastSuccessfulSnapshotAndReportsFailure() {
        service.sync();
        when(volc.fetchResourcePackages()).thenThrow(new RenException("supplier unavailable"));
        service.sync();
        var overview = service.overview(true);
        assertEquals(1, list(overview, "packages").size());
        var source = list(overview, "sources").getFirst();
        assertEquals("SYNC_FAILED", source.get("status")); assertNotNull(source.get("lastSyncedAt"));
    }

    @Test void duplicatePackIdsDoNotReplaceSuccessfulSnapshot() throws Exception {
        service.sync();
        var duplicate = mapper.readTree("{\"InstanceNo\":\"other\"}");
        when(volc.fetchResourcePackages()).thenReturn(List.of(duplicate, duplicate)); service.sync();
        assertEquals("package-1", list(service.overview(true), "packages").getFirst().get("instanceNo"));
    }

    @Test void successfulEmptyResponseClearsOldPacksInsteadOfKeepingPhantomCredit() {
        service.sync(); when(volc.fetchResourcePackages()).thenReturn(List.of()); service.sync();
        assertTrue(list(service.overview(true), "packages").isEmpty());
        assertEquals("READY", list(service.overview(true), "sources").getFirst().get("status"));
    }

    @Test void anonymousSpeechPacksRemainDistinctAndCannotUseDisplayKeysForOwnership() {
        when(volc.fetchResourcePackages()).thenReturn(List.of());
        when(speech.enabled()).thenReturn(true);
        Map<String, Object> first = new LinkedHashMap<>(Map.of("accountRef", "volc-account", "rowKey", "speech-0", "product", "tts-test"));
        first.put("instanceNo", null);
        Map<String, Object> second = new LinkedHashMap<>(first); second.put("rowKey", "speech-1");
        when(speech.fetch()).thenReturn(List.of(first, second));
        service.sync();
        assertEquals(2, list(service.overview(true), "packages").size());
        assertEquals("READY", list(service.overview(true), "sources").get(1).get("status"));
        bind("RESOURCE", "speech-0", "workspace-a");
        assertTrue(list(service.overview(false), "packages").isEmpty());
        jdbc.update("DELETE FROM ai_billing_binding"); bind("ACCOUNT", "volc-account", "workspace-a");
        assertEquals(2, list(service.overview(false), "packages").size());
        assertNull(list(service.overview(false), "packages").getFirst().get("instanceNo"));
    }

    @Test void alertsUseOfficialQuantityWithoutCreatingMoneyOrLifetimeConsumption() {
        Instant now = Instant.parse("2026-10-12T00:00:00Z");
        var alerts = BillingBenefitService.alerts(Map.of("availableAmount", "5", "totalAmount", "100",
                "expiryTime", "2026-10-15T00:00:00Z", "resetPeriod", "month"), now);
        assertEquals(Set.of("EXPIRING", "LOW_BALANCE", "PERIODIC_RESET"),
                new HashSet<>(alerts.stream().map(row -> row.get("code")).toList()));
        assertTrue(BillingBenefitService.alerts(Map.of("availableAmount", "Unlimited", "totalAmount", "100"), now).isEmpty());
        assertEquals("EXPIRED", BillingBenefitService.alerts(Map.of("status", "Expired", "availableAmount", "5"), now).getFirst().get("code"));
        var speech = BillingBenefitService.alerts(Map.of("expiryTime", "2026-10-15T00:00:00Z", "currentUsage", "20"), now);
        assertEquals("EXPIRING", speech.getFirst().get("code"));
        assertTrue(speech.getFirst().get("message").contains("未返回独立剩余额度"));
    }

    @Test void deepSeekCreditRemainsDistinctFromExpenseAndSupportsUnknownComponent() throws Exception {
        var rows = DeepSeekBalanceClient.parse(mapper.readTree("""
                {"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"12.000000001",
                 "topped_up_balance":"10.000000001","granted_balance":"2"}]}
                """), "account");
        assertEquals("12.000000001", rows.getFirst().get("totalBalance"));
        assertEquals("2", rows.getFirst().get("grantedBalance"));
        assertFalse(rows.getFirst().containsKey("cost"));
        assertFalse(DeepSeekBalanceClient.official("https://api.deepseek.com.attacker.invalid"));
        assertFalse(DeepSeekBalanceClient.official("http://api.deepseek.com"));
        assertTrue(DeepSeekBalanceClient.official("https://api.deepseek.com/v1"));
        assertThrows(RenException.class, () -> DeepSeekBalanceClient.parse(mapper.readTree("{}"), "account"));
    }

    @Test void speechPackRetainsOfficialMeasuresAndNeverInventsRemainingBalance() throws Exception {
        var pack = VolcengineSpeechBenefitClient.pack(mapper.readTree("""
                {"Code":"pack-code","InstanceNumber":"pack-instance","ResourceID":"seed-tts-2.0",
                 "PurchasedAmount":"1000000","CurrentUsage":"2500.125","IsShareable":true,
                 "Expires":"supplier-specific-time","Harvest":{"Unit":"字符"}}
                """), "account", "default");
        assertEquals("pack-instance", pack.get("instanceNo"));
        assertEquals("seed-tts-2.0", pack.get("product"));
        assertEquals("2500.125", pack.get("currentUsage"));
        assertEquals("字符", pack.get("unit")); assertEquals(true, pack.get("isShareable"));
        assertFalse(pack.containsKey("availableAmount"));
        assertTrue(BillingBenefitService.alerts(pack, Instant.now()).isEmpty());
    }

    private void bind(String type, String value, String workspace) {
        jdbc.update("INSERT INTO ai_billing_binding(provider,account_ref,workspace_id,scope_type,scope_value,valid_from) VALUES ('VOLCENGINE','volc-account',?,?,?,?)",
                workspace, type, value, BillingSupport.time(Instant.now().minusSeconds(60)));
    }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> list(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }
}
