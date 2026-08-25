package xiaozhi.modules.maintenance.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.maintenance.dto.HarnessMaintenanceRequest;
import xiaozhi.modules.maintenance.dto.HarnessMaintenanceResponse;

@Service
public class HttpHarnessMaintenanceGateway implements HarnessMaintenanceGateway {
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final boolean enabled;
    private final URI endpoint;
    private final String secret;

    public HttpHarnessMaintenanceGateway(ObjectMapper objectMapper,
            @Value("${maintenance.harness.enabled:false}") boolean enabled,
            @Value("${maintenance.harness.base-url:http://xiaozhi-maintenance-agent:8010}") String baseUrl,
            @Value("${maintenance.harness.secret:}") String secret,
            @Value("${maintenance.harness.connect-timeout-seconds:5}") long connectTimeoutSeconds) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.secret = secret;
        URI base = URI.create(StringUtils.removeEnd(baseUrl, "/") + "/");
        if (!"http".equals(base.getScheme()) && !"https".equals(base.getScheme())) {
            throw new IllegalArgumentException("maintenance.harness.base-url 仅支持 HTTP/HTTPS");
        }
        this.endpoint = base.resolve("v1/maintenance/run");
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build();
    }

    @Override
    public HarnessMaintenanceResponse run(HarnessMaintenanceRequest request) {
        if (!enabled) throw new RenException("DeepSeek Harness 维护服务未启用");
        if (StringUtils.isBlank(secret)) throw new RenException("DeepSeek Harness 内部服务密钥未配置");
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + secret)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RenException("DeepSeek Harness 维护服务调用失败（HTTP " + response.statusCode() + "）");
            }
            return objectMapper.readValue(response.body(), HarnessMaintenanceResponse.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RenException("DeepSeek Harness 维护任务被中断");
        } catch (RenException e) {
            throw e;
        } catch (Exception e) {
            throw new RenException("DeepSeek Harness 维护服务不可用");
        }
    }
}
