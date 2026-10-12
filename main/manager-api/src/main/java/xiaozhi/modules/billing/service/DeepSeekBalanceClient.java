package xiaozhi.modules.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.model.service.ModelConfigService;

/** Reads account credit from the fixed official endpoint, using an existing managed model key. */
@Component
public class DeepSeekBalanceClient {
    private final ObjectMapper mapper;
    private final ModelConfigService models;
    private final String modelId, accountRef;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public DeepSeekBalanceClient(ObjectMapper mapper, ModelConfigService models,
            @Value("${xiaozhi.billing.deepseek.model-id:}") String modelId,
            @Value("${xiaozhi.billing.deepseek.account-ref:}") String accountRef) {
        this.mapper = mapper; this.models = models; this.modelId = modelId;
        this.accountRef = accountRef.isBlank() ? modelId : accountRef;
    }

    public boolean enabled() { return !modelId.isBlank() && !accountRef.isBlank() && accountRef.length() <= 128; }
    public String accountRef() { return accountRef; }

    public List<Map<String, Object>> fetch() {
        if (!enabled()) throw new RenException("未配置 DeepSeek 余额查询模型");
        var model = models.getModelByIdFromCache(modelId);
        if (model == null || model.getConfigJson() == null) throw new RenException("DeepSeek 模型配置不存在");
        var config = model.getConfigJson();
        String key = config.getStr("api_key");
        String base = config.getStr("base_url", model.getEndpointUrl());
        if (key == null || key.isBlank() || !official(base)) throw new RenException("余额查询必须使用官方直连 DeepSeek 模型配置");
        try {
            var request = HttpRequest.newBuilder(URI.create("https://api.deepseek.com/user/balance"))
                    .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + key).GET().build();
            HttpResponse<byte[]> response = BillingHttpResponses.send(http, request, 64 * 1024);
                if (response.statusCode() != 200) throw new RenException("DeepSeek 余额查询失败（HTTP " + response.statusCode() + "）");
                return parse(mapper.readTree(response.body()), accountRef);
        } catch (RenException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RenException("余额查询已中断"); }
        catch (Exception e) { throw new RenException("DeepSeek 余额接口暂不可用"); }
    }

    static boolean official(String base) {
        try { URI uri = URI.create(base); return "https".equals(uri.getScheme()) && "api.deepseek.com".equalsIgnoreCase(uri.getHost())
                && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443); }
        catch (RuntimeException e) { return false; }
    }

    static List<Map<String, Object>> parse(JsonNode root, String account) {
        if (!root.path("balance_infos").isArray() || !root.path("is_available").isBoolean())
            throw new RenException("DeepSeek 余额响应格式无效");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : root.path("balance_infos")) {
            String currency = item.path("currency").asText();
            if (!Set.of("CNY", "USD").contains(currency)) throw new RenException("DeepSeek 余额币种无效");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("provider", "DEEPSEEK"); row.put("accountRef", account); row.put("currency", currency);
            row.put("totalBalance", amount(item, "total_balance"));
            row.put("toppedUpBalance", amount(item, "topped_up_balance"));
            row.put("grantedBalance", amount(item, "granted_balance"));
            row.put("isAvailable", root.path("is_available").asBoolean()); rows.add(row);
        }
        return rows;
    }

    private static String amount(JsonNode item, String name) {
        var amount = BillingBillService.amount(item.hasNonNull(name) ? item.get(name).asText() : null);
        return amount == null ? null : amount.toPlainString();
    }
}
