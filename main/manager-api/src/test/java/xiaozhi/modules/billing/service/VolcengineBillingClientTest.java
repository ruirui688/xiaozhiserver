package xiaozhi.modules.billing.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xiaozhi.common.exception.RenException;

class VolcengineBillingClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private VolcengineBillingClient client() {
        return new VolcengineBillingClient(mapper, true, "test-access-key", "test-secret-key", "test-account", "cn-north-1");
    }

    @Test void genericPackagesUseSupportedPageSizeAndFollowEveryCursor() throws Exception {
        var client = spy(client());
        doReturn(mapper.readTree("{\"Result\":{\"List\":[{\"InstanceNo\":\"one\"}],\"NextToken\":\"page-two\"}}"),
                mapper.readTree("{\"Result\":{\"List\":[{\"InstanceNo\":\"two\"}]}}"))
                .when(client).request(eq("ListResourcePackages"), anyMap(), eq("2022-01-01"), eq("billing"));
        assertEquals(2, client.fetchResourcePackages().size());
        var bodies = ArgumentCaptor.forClass(Map.class);
        verify(client, times(2)).request(eq("ListResourcePackages"), bodies.capture(), eq("2022-01-01"), eq("billing"));
        assertEquals("20", bodies.getAllValues().get(0).get("MaxResults"));
        assertEquals("Package", bodies.getAllValues().get(0).get("ResourceType"));
        assertFalse(bodies.getAllValues().get(0).containsKey("NextToken"));
        assertEquals("page-two", bodies.getAllValues().get(1).get("NextToken"));
    }

    @Test void repeatedGenericPackageCursorFailsInsteadOfReturningPartialSnapshot() throws Exception {
        var client = spy(client());
        doReturn(mapper.readTree("{\"Result\":{\"List\":[{\"InstanceNo\":\"one\"}],\"NextToken\":\"same-token\"}}"))
                .when(client).request(eq("ListResourcePackages"), anyMap(), eq("2022-01-01"), eq("billing"));
        assertThrows(RenException.class, client::fetchResourcePackages);
        verify(client, times(2)).request(eq("ListResourcePackages"), anyMap(), eq("2022-01-01"), eq("billing"));
    }

    @Test void emptyPackagePageTerminatesEvenWhenSupplierRetainsItsLastCursor() throws Exception {
        var client = spy(client());
        doReturn(mapper.readTree("{\"Result\":{\"List\":[{\"InstanceNo\":\"one\"}],\"NextToken\":\"last-token\"}}"),
                mapper.readTree("{\"Result\":{\"List\":[],\"NextToken\":\"last-token\"}}"))
                .when(client).request(eq("ListResourcePackages"), anyMap(), eq("2022-01-01"), eq("billing"));
        assertEquals(1, client.fetchResourcePackages().size());
        verify(client, times(2)).request(eq("ListResourcePackages"), anyMap(), eq("2022-01-01"), eq("billing"));
    }

    @Test void emptyPackagePageWithZeroCursorDoesNotRestartAtTheFirstPage() throws Exception {
        var client = spy(client());
        doReturn(mapper.readTree("{\"Result\":{\"List\":[{\"InstanceNo\":\"one\"}],\"NextToken\":\"last-token\"}}"),
                mapper.readTree("{\"Result\":{\"List\":[],\"NextToken\":\"0\"}}"))
                .when(client).request(eq("ListResourcePackages"), anyMap(), eq("2022-01-01"), eq("billing"));
        assertEquals(1, client.fetchResourcePackages().size());
        verify(client, times(2)).request(eq("ListResourcePackages"), anyMap(), eq("2022-01-01"), eq("billing"));
    }

    @Test void speechQuerySuppliesRequiredFiltersAndFetchesFollowingPage() throws Exception {
        var client = spy(client());
        doReturn(mapper.readTree("{\"Result\":{\"Packs\":[{\"InstanceNumber\":\"one\"}],\"TotalCount\":2,\"PageNumber\":1}}"),
                mapper.readTree("{\"Result\":{\"Packs\":[{\"InstanceNumber\":\"two\"}],\"TotalCount\":2,\"PageNumber\":2}}"))
                .when(client).request(eq("ResourcePacksStatus"), anyMap(), eq("2025-05-20"), eq("speech_saas_prod"));
        assertEquals(2, client.fetchSpeechPackages("project-a", List.of("volc.example.tts")).size());
        var bodies = ArgumentCaptor.forClass(Map.class);
        verify(client, times(2)).request(eq("ResourcePacksStatus"), bodies.capture(), eq("2025-05-20"), eq("speech_saas_prod"));
        var first = bodies.getAllValues().get(0);
        assertEquals("project-a", first.get("ProjectName"));
        assertEquals(List.of("volc.example.tts"), first.get("ResourceIDs"));
        assertEquals(List.of("access", "quota", "prepaid"), first.get("Types"));
        assertEquals(100, first.get("PageSize"));
        assertEquals(1, first.get("PageNumber"));
        assertEquals(2, bodies.getAllValues().get(1).get("PageNumber"));
    }

    @Test void missingSpeechResourcesNeverReachSupplier() {
        var client = spy(client());
        assertThrows(RenException.class, () -> client.fetchSpeechPackages("default", List.of()));
        assertThrows(RenException.class, () -> client.fetchSpeechPackages("default", null));
        assertThrows(RenException.class, () -> client.fetchSpeechPackages("default", List.of(" ")));
        verify(client, never()).request(anyString(), anyMap(), anyString(), anyString());
    }

    @Test void invalidSpeechPaginationDoesNotPublishPartialResults() throws Exception {
        for (String result : List.of("{\"Packs\":[],\"TotalCount\":0.5}",
                "{\"Packs\":[],\"TotalCount\":1}", "{\"Packs\":[],\"TotalCount\":0,\"PageNumber\":2}")) {
            var client = spy(client());
            doReturn(mapper.readTree("{\"Result\":" + result + "}"))
                    .when(client).request(eq("ResourcePacksStatus"), anyMap(), eq("2025-05-20"), eq("speech_saas_prod"));
            assertThrows(RenException.class, () -> client.fetchSpeechPackages("default", List.of("volc.example.tts")));
        }
    }

    @Test void supplierErrorsAreClassifiedWithoutEchoingSensitiveMessages() throws Exception {
        for (String code : List.of("InvalidParam", "InvalidParameter", "MissingParameter", "InvalidActionOrVersion")) {
            String message = error(code, 400);
            assertTrue(message.contains("参数"));
            assertFalse(message.contains("权限"));
            assertFalse(message.contains("test-secret-key"));
        }
        for (String code : List.of("InvalidAccessKey", "SignatureDoesNotMatch", "InvalidCredential", "InvalidTimestamp")) {
            String message = error(code, 401);
            assertTrue(message.contains("身份验证"));
            assertFalse(message.contains("权限"));
            assertFalse(message.contains("test-secret-key"));
        }
        assertTrue(error("AccessDenied", 403).contains("只读权限"));
        assertTrue(error("Forbidden", 403).contains("只读权限"));
        assertTrue(error("FlowLimitExceeded", 429).contains("限流"));
        assertTrue(error("InternalError", 500).contains("暂不可用"));
    }

    @Test void unknownErrorCodeAndMalformedResponseCannotLeakSupplierContentOrClaimPermissionFailure() throws Exception {
        for (String code : List.of("test-secret-key", "UnknownSupplierError", "AccessDenied\nAuthorization: secret")) {
            String message = error(code, 403);
            assertEquals("供应商查询失败（HTTP 403）", message);
        }
        var client = client();
        var invalid = assertThrows(RenException.class,
                () -> client.parseResponse(502, "private upstream error: test-secret-key".getBytes(StandardCharsets.UTF_8)));
        assertFalse(invalid.getMessage().contains("test-secret-key"));
        assertFalse(invalid.getMessage().contains("权限"));
        var noCode = assertThrows(RenException.class,
                () -> client.parseResponse(403, "{}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("供应商查询失败（HTTP 403）", noCode.getMessage());
    }

    @Test void responseParserPreservesSupplierDecimalPrecision() {
        var response = client().parseResponse(200,
                "{\"Result\":{\"Harvest\":{\"CurrentUsage\":0.123456789123456789}}}".getBytes(StandardCharsets.UTF_8));
        assertEquals("0.123456789123456789", response.path("Result").path("Harvest").path("CurrentUsage").decimalValue().toPlainString());
    }

    private String error(String code, int status) throws Exception {
        byte[] body = mapper.writeValueAsBytes(Map.of("ResponseMetadata",
                Map.of("Error", Map.of("Code", code, "Message", "Authorization: Bearer test-secret-key"))));
        return assertThrows(RenException.class, () -> client().parseResponse(status, body)).getMessage();
    }
}
