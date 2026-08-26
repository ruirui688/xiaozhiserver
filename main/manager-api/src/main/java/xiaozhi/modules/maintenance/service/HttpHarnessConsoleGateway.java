package xiaozhi.modules.maintenance.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.maintenance.dto.HarnessConsoleLaunchRequest;
import xiaozhi.modules.maintenance.dto.HarnessConsoleLaunchResponse;

@Service
public class HttpHarnessConsoleGateway implements HarnessConsoleGateway {
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final boolean enabled;
    private final URI endpoint;
    private final URI publicBase;
    private final String secret;

    public HttpHarnessConsoleGateway(ObjectMapper objectMapper,
            @Value("${maintenance.harness.enabled:false}") boolean enabled,
            @Value("${maintenance.harness.base-url:http://xiaozhi-maintenance-agent:8010}") String baseUrl,
            @Value("${maintenance.harness.public-console-url:https://harness.yh.wejoy.link}") String publicConsoleUrl,
            @Value("${maintenance.harness.secret:}") String secret,
            @Value("${maintenance.harness.connect-timeout-seconds:5}") long connectTimeoutSeconds) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.secret = secret;
        URI base = URI.create(StringUtils.removeEnd(baseUrl, "/") + "/");
        if (!Set.of("http", "https").contains(base.getScheme())) {
            throw new IllegalArgumentException("maintenance.harness.base-url 仅支持 HTTP/HTTPS");
        }
        this.endpoint = base.resolve("v1/console/sessions");
        this.publicBase = URI.create(StringUtils.removeEnd(publicConsoleUrl, "/") + "/");
        if (!"https".equals(publicBase.getScheme()) || StringUtils.isBlank(publicBase.getHost())) {
            throw new IllegalArgumentException("maintenance.harness.public-console-url 必须是 HTTPS 地址");
        }
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build();
    }

    @Override
    public HarnessConsoleLaunchResponse launch(HarnessConsoleLaunchRequest request) {
        if (!enabled) throw new RenException("DeepSeek Harness 维护服务未启用");
        if (StringUtils.isBlank(secret)) throw new RenException("DeepSeek Harness 内部服务密钥未配置");
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + secret)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RenException("DeepSeek Harness 原生控制台启动失败（HTTP " + response.statusCode() + "）");
            }
            HarnessConsoleLaunchResponse result = objectMapper.readValue(response.body(), HarnessConsoleLaunchResponse.class);
            if (result == null || !StringUtils.startsWith(result.getLaunchPath(), "/maintenance-console/")) {
                throw new RenException("DeepSeek Harness 原生控制台返回地址无效");
            }
            result.setLaunchUrl(publicBase.resolve(result.getLaunchPath().substring(1)).toString());
            result.setLaunchPath(null);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RenException("DeepSeek Harness 原生控制台启动被中断");
        } catch (RenException e) {
            throw e;
        } catch (Exception e) {
            throw new RenException("DeepSeek Harness 原生控制台不可用");
        }
    }
}
