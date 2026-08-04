package xiaozhi.modules.model.service.impl.benchmark;

import java.time.Duration;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.sys.service.SysParamsService;

@Component
@AllArgsConstructor
public class PythonBenchmarkClient {

    private final SysParamsService sysParamsService;
    private final Environment environment;
    private final BenchmarkRequestAuth benchmarkRequestAuth;

    public JSONObject runBenchmark(JSONObject payload) {
        return execute(payload, Duration.ofMinutes(10), "专业评测");
    }

    public JSONObject runRuntimeTest(JSONObject payload, Duration timeout) {
        return execute(payload, timeout, "运行时真实测试");
    }

    private JSONObject execute(JSONObject payload, Duration timeout, String operation) {
        String baseUrl = resolveXiaozhiServerHttpBaseUrl();
        String url = baseUrl + "/benchmark/models";
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(timeout);
        RestTemplate benchmarkRestTemplate = createRestTemplate(factory);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        benchmarkRequestAuth.apply(headers);
        ResponseEntity<String> response;
        try {
            response = benchmarkRestTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(payload.toString(), headers), String.class);
        } catch (ResourceAccessException e) {
            throw new RenException("无法连接 xiaozhi-server " + operation + "接口：" + url
                    + "。请先确认 xiaozhi-server 已启动且 HTTP 端口可访问：执行 `bash scripts/dev status`，"
                    + "确认 server 的 8003 端口为 listening；如果 manager-api 与 xiaozhi-server 不在同一主机或容器，"
                    + "请配置 `xiaozhi.server.http-base-url`，例如 http://xiaozhi-esp32-server:8003。原始错误："
                    + e.getMessage(), e);
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RenException("xiaozhi-server " + operation + "接口返回异常：" + response.getStatusCode());
        }
        return JSONUtil.parseObj(response.getBody());
    }

    protected RestTemplate createRestTemplate(JdkClientHttpRequestFactory factory) {
        return new RestTemplate(factory);
    }

    private String resolveXiaozhiServerHttpBaseUrl() {
        String configured = environment.getProperty("xiaozhi.server.http-base-url");
        if (StringUtils.isNotBlank(configured)) {
            return StringUtils.removeEnd(configured.trim(), "/");
        }
        return "http://127.0.0.1:" + resolveXiaozhiServerHttpPort();
    }

    private int resolveXiaozhiServerHttpPort() {
        String value = sysParamsService.getValue("server.http_port", true);
        if (StringUtils.isNumeric(value)) {
            return Integer.parseInt(value);
        }
        return 8003;
    }
}
