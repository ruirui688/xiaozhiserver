package xiaozhi.modules.billing.service;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import xiaozhi.common.exception.RenException;

/** Fixed, read-only official billing endpoint. No model API key or caller-controlled URL. */
@Component
public class VolcengineBillingClient {
    private final ObjectMapper mapper;
    private final boolean configured;
    private final String accessKey, secretKey, accountRef, region;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public VolcengineBillingClient(ObjectMapper mapper,
            @Value("${xiaozhi.billing.volcengine.enabled:false}") boolean configured,
            @Value("${xiaozhi.billing.volcengine.access-key:}") String accessKey,
            @Value("${xiaozhi.billing.volcengine.secret-key:}") String secretKey,
            @Value("${xiaozhi.billing.volcengine.account-ref:}") String accountRef,
            @Value("${xiaozhi.billing.volcengine.region:cn-north-1}") String region) {
        this.mapper=mapper; this.configured=configured; this.accessKey=accessKey; this.secretKey=secretKey;
        this.accountRef=accountRef; this.region=region;
    }
    public boolean enabled() { return configured && !accessKey.isBlank() && !secretKey.isBlank() && !accountRef.isBlank() && accountRef.length()<=128; }
    public String accountRef() { return accountRef; }

    public List<JsonNode> fetch(String action, String billPeriod) {
        if (!enabled()) throw new RenException("账单同步未配置（需要独立的只读账单 AK/SK）");
        if (!Set.of("ListBill", "ListBillDetail").contains(action)) throw new RenException("不支持的账单接口");
        validatePeriod(billPeriod);
        List<JsonNode> rows = new ArrayList<>();
        long expectedTotal=-1;
        for (int offset=0; offset<100_000; offset+=100) {
            Map<String, Object> body = new TreeMap<>(); body.put("BillPeriod", billPeriod); body.put("Limit",100); body.put("Offset",offset);
            body.put("NeedRecordNum", 1);
            // Explicitly include zero-amount bills so the list and total use the same scope.
            body.put("IgnoreZero", 0);
            if (action.equals("ListBillDetail")) { body.put("GroupTerm",0); body.put("GroupPeriod",2); }
            JsonNode response = request(action, body);
            JsonNode list = response.path("Result").path("List");
            if (!list.isArray()) throw new RenException("账单接口返回格式无效");
            long total=response.path("Result").path("Total").asLong(-1);
            if(total<0 || (expectedTotal>=0 && total!=expectedTotal))throw new RenException("账单分页总数缺失或同步中发生变化，请重新同步");
            expectedTotal=total;
            list.forEach(rows::add);
            if(rows.size()==expectedTotal)return rows;
            if(rows.size()>expectedTotal || list.size()!=100)throw new RenException("账单分页不完整，保留既有快照");
            try { Thread.sleep(220); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RenException("账单同步已中断"); }
        }
        throw new RenException("账单超过单次同步上限，请分账号处理");
    }

    public List<JsonNode> fetchResourcePackages() {
        if (!enabled()) throw new RenException("资源包查询未配置只读凭据");
        List<JsonNode> rows = new ArrayList<>();
        Set<String> tokens = new HashSet<>();
        String token = "";
        int pages = 0;
        do {
            if (++pages > 1000) throw new RenException("资源包分页超过限制");
            Map<String, Object> body = new TreeMap<>();
            body.put("ResourceType", "Package"); body.put("MaxResults", "20");
            if (!token.isBlank()) body.put("NextToken", token);
            JsonNode result = request("ListResourcePackages", body).path("Result");
            if (!result.path("List").isArray()) throw new RenException("资源包接口返回格式无效");
            // The official contract uses an empty list as EOF, even if the last cursor is repeated.
            if (result.path("List").isEmpty()) return rows;
            result.path("List").forEach(rows::add);
            token = result.path("NextToken").asText("");
            if (rows.size() > 100_000 || (!token.isBlank() && !tokens.add(token)))
                throw new RenException("资源包分页超出限制或游标重复");
        } while (!token.isBlank());
        return rows;
    }

    private JsonNode request(String action, Map<String, Object> body) {
        return request(action, body, "2022-01-01", "billing");
    }

    public List<JsonNode> fetchSpeechPackages(String project, List<String> resourceIds) {
        if (!enabled() || project == null || project.isBlank() || resourceIds == null || resourceIds.isEmpty())
            throw new RenException("语音资源包查询需要配置项目名称和服务标识");
        if (resourceIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 256))
            throw new RenException("语音资源包服务标识格式无效");
        List<String> requestedResources = List.copyOf(new LinkedHashSet<>(resourceIds));
        List<JsonNode> rows = new ArrayList<>();
        long expected = -1;
        for (int page = 1; page <= 1000; page++) {
            var result = request("ResourcePacksStatus", Map.of("ProjectName", project,
                    "ResourceIDs", requestedResources, "Types", List.of("access", "quota", "prepaid"),
                    "PageSize", 100, "PageNumber", page),
                    "2025-05-20", "speech_saas_prod").path("Result");
            if (!result.path("Packs").isArray() || !result.path("TotalCount").isIntegralNumber()
                    || !result.path("TotalCount").canConvertToLong())
                throw new RenException("语音资源包返回格式无效");
            if (result.has("PageNumber") && (!result.path("PageNumber").isIntegralNumber()
                    || result.path("PageNumber").asLong() != page))
                throw new RenException("语音资源包返回页码不匹配");
            long total = result.path("TotalCount").asLong();
            if (total < 0 || total > 100_000 || expected >= 0 && expected != total)
                throw new RenException("语音资源包分页不完整，请重新同步");
            expected = total; result.path("Packs").forEach(rows::add);
            if (rows.size() == expected) return rows;
            if (result.path("Packs").isEmpty() || rows.size() > expected) throw new RenException("语音资源包分页不完整");
        }
        throw new RenException("语音资源包超过查询上限");
    }

    JsonNode request(String action, Map<String, Object> body, String version, String service) {
        try {
            String payload=mapper.writeValueAsString(body), query="Action="+action+"&Version="+version;
            Map<String, String> headers=sign(payload, query, Instant.now(), accessKey, secretKey, region, service);
            HttpRequest.Builder request=HttpRequest.newBuilder(URI.create("https://open.volcengineapi.com/?"+query)).timeout(Duration.ofSeconds(45))
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            headers.forEach(request::header);
            HttpResponse<byte[]> response=BillingHttpResponses.send(http, request.build(), 8*1024*1024);
            return parseResponse(response.statusCode(), response.body());
        } catch (RenException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RenException("账单同步已中断"); }
        catch (Exception e) { throw new RenException("账单接口暂不可用"); }
    }

    JsonNode parseResponse(int status, byte[] body) {
        JsonNode value;
        try {
            value = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(body);
        } catch (Exception e) {
            throw new RenException("供应商查询响应格式无效（HTTP " + status + "）");
        }
        if (value == null || !value.isObject()) throw new RenException("供应商查询响应格式无效");
        JsonNode error = value.path("ResponseMetadata").path("Error");
        if (!error.isMissingNode() && !error.isNull()) throw supplierError(error.path("Code").asText(""), status);
        if (status != 200) throw supplierError("", status);
        return value;
    }

    private static RenException supplierError(String code, int status) {
        // Only known public error codes may be echoed. Supplier Message can contain credentials.
        String reason = switch (code) {
            case "AccessDenied", "Forbidden", "ForbiddenAccess" -> "供应商拒绝访问，请检查该查询接口的只读权限";
            case "MissingParameter", "InvalidParameter", "InvalidParam", "InvalidAction", "InvalidVersion", "InvalidActionOrVersion", "ServiceNotFound" ->
                    "供应商拒绝查询参数，请检查项目、服务标识、接口版本和请求参数";
            case "InvalidAccessKey", "SignatureDoesNotMatch", "InvalidAuthorization", "InvalidCredential", "InvalidSecretToken", "MissingRequestInfo", "InvalidTimestamp" ->
                    "供应商身份验证失败，请检查凭据、签名、区域和系统时间";
            case "FlowLimitExceeded" -> "供应商查询限流，请稍后重试";
            case "InternalError", "InternalServiceTimeout", "ServiceUnavailableTemp", "InternalServiceError" ->
                    "供应商查询服务暂不可用，请稍后重试";
            default -> null;
        };
        return new RenException(reason == null ? "供应商查询失败（HTTP " + status + "）"
                : reason + "（" + code + "）");
    }

    static Map<String, String> sign(String payload, String query, Instant now, String accessKey, String secretKey, String region) {
        return sign(payload, query, now, accessKey, secretKey, region, "billing");
    }

    static Map<String, String> sign(String payload, String query, Instant now, String accessKey, String secretKey, String region, String service) {
        String timestamp=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now);
        String date=timestamp.substring(0,8), digest=BillingSupport.hash(payload);
        String names="content-type;host;x-content-sha256;x-date";
        String canonical="POST\n/\n"+query+"\ncontent-type:application/json\nhost:open.volcengineapi.com\nx-content-sha256:"+digest+"\nx-date:"+timestamp+"\n\n"+names+"\n"+digest;
        String scope=date+"/"+region+"/"+service+"/request";
        String stringToSign="HMAC-SHA256\n"+timestamp+"\n"+scope+"\n"+BillingSupport.hash(canonical);
        byte[] key=hmac(secretKey.getBytes(StandardCharsets.UTF_8),date); key=hmac(key,region); key=hmac(key,service); key=hmac(key,"request");
        String authorization="HMAC-SHA256 Credential="+accessKey+"/"+scope+", SignedHeaders="+names+", Signature="+HexFormat.of().formatHex(hmac(key,stringToSign));
        return Map.of("Content-Type","application/json","X-Date",timestamp,"X-Content-Sha256",digest,"Authorization",authorization);
    }
    private static byte[] hmac(byte[] key, String text) {
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(text.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException("无法签名账单请求"); }
    }
    static void validatePeriod(String period) {
        try {
            YearMonth month=YearMonth.parse(period), current=YearMonth.now(ZoneId.of("Asia/Shanghai"));
            if (month.isAfter(current) || month.isBefore(current.minusMonths(24))) throw new IllegalArgumentException();
        } catch (Exception e) { throw new RenException("账期应为最近24个月内的 yyyy-MM"); }
    }
}
