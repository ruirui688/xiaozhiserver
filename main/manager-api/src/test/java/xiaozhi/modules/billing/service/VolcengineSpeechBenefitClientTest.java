package xiaozhi.modules.billing.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import xiaozhi.common.exception.RenException;

class VolcengineSpeechBenefitClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void structuredHarvestTakesPrecedenceOverLocalizedDisplayStrings() throws Exception {
        var row = VolcengineSpeechBenefitClient.pack(mapper.readTree("""
                {"InstanceNumber":"", "Code":"Free_API_Package_example", "ResourceID":"volc.service_type.example",
                 "PurchasedAmount":"20,000 字数", "CurrentUsage":"5,982 字数", "Expires":"-",
                 "Harvest":{"Unit":"字数","PurchasedAmount":20000,"CurrentUsage":5982},"IsShareable":false}
                """), "test-account", "default");
        assertNull(row.get("instanceNo"));
        assertEquals("Free_API_Package_example", row.get("code"));
        assertEquals("20000", row.get("purchasedAmount"));
        assertEquals("5982", row.get("currentUsage"));
        assertEquals("字数", row.get("unit"));
        assertEquals("-", row.get("expiryTime"));
        assertEquals(false, row.get("isShareable"));
        assertFalse(row.containsKey("availableAmount"));
        assertFalse(row.containsKey("totalAmount"));
        assertTrue(BillingBenefitService.alerts(row, Instant.now()).isEmpty());
    }

    @Test void missingMeasurementsStayUnknownAndOfficialZeroSurvives() throws Exception {
        var unknown = VolcengineSpeechBenefitClient.pack(mapper.readTree("{}"), "account", "default");
        assertNull(unknown.get("purchasedAmount"));
        assertNull(unknown.get("currentUsage"));
        assertNull(unknown.get("unit"));
        assertNull(unknown.get("isShareable"));
        var zero = VolcengineSpeechBenefitClient.pack(mapper.readTree("""
                {"PurchasedAmount":"unknown","CurrentUsage":"unknown",
                 "Harvest":{"PurchasedAmount":0,"CurrentUsage":0}}
                """), "account", "default");
        assertEquals("0", zero.get("purchasedAmount"));
        assertEquals("0", zero.get("currentUsage"));
    }

    @Test void missingHarvestRetainsSupplierTextWithoutParsingOrCalculatingRemainder() throws Exception {
        var row = VolcengineSpeechBenefitClient.pack(mapper.readTree("""
                {"PurchasedAmount":"20,000 字数","CurrentUsage":"无限制","Harvest":null}
                """), "account", "default");
        assertEquals("20,000 字数", row.get("purchasedAmount"));
        assertEquals("无限制", row.get("currentUsage"));
        assertNull(row.get("unit"));
        assertFalse(row.containsKey("availableAmount"));
    }

    @Test void malformedFieldsCannotBecomeUnknownValuesOrInstances() throws Exception {
        for (String source : List.of("[]", "{\"Harvest\":[]}", "{\"Harvest\":{\"PurchasedAmount\":{}}}",
                "{\"InstanceNumber\":{}}", "{\"InstanceNumber\":true}", "{\"Harvest\":{\"CurrentUsage\":true}}")) {
            var value = mapper.readTree(source);
            assertThrows(RenException.class, () -> VolcengineSpeechBenefitClient.pack(value, "account", "default"));
        }
    }

    @Test void emptyResourceSelectionDisablesQueriesInsteadOfAssumingAllResources() {
        var client = mock(VolcengineBillingClient.class);
        when(client.enabled()).thenReturn(true);
        when(client.accountRef()).thenReturn("account");
        var benefits = new VolcengineSpeechBenefitClient(client, "default", " , ");
        assertFalse(benefits.enabled());
        assertThrows(RenException.class, benefits::fetch);
        verify(client, never()).fetchSpeechPackages(anyString(), anyList());
    }

    @Test void configuredResourcesAreExplicitAndAnonymousPacksHaveDistinctDisplayKeys() throws Exception {
        var client = mock(VolcengineBillingClient.class);
        when(client.enabled()).thenReturn(true);
        when(client.accountRef()).thenReturn("account");
        var resources = List.of("volc.service_type.example", "volc.example.tts");
        when(client.fetchSpeechPackages("default", resources)).thenReturn(List.of(
                mapper.readTree("{\"Code\":\"free-product\",\"InstanceNumber\":\"\"}"),
                mapper.readTree("{\"Code\":\"free-product\"}")));
        var benefits = new VolcengineSpeechBenefitClient(client, "default",
                " volc.service_type.example,volc.example.tts,volc.service_type.example ");
        assertTrue(benefits.enabled());
        var rows = benefits.fetch();
        assertEquals(2, rows.size());
        assertNotEquals(rows.get(0).get("rowKey"), rows.get(1).get("rowKey"));
        assertNull(rows.get(0).get("instanceNo"));
        assertNull(rows.get(1).get("instanceNo"));
        verify(client).fetchSpeechPackages("default", resources);
    }
}
