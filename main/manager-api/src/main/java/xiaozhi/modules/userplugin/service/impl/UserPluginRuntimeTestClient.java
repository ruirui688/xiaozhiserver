package xiaozhi.modules.userplugin.service.impl;

import java.time.Duration;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.userplugin.vo.UserPluginTestVO;

@Component
@RequiredArgsConstructor
public class UserPluginRuntimeTestClient {
    private final @Lazy SysParamsService sysParamsService;
    private final Environment environment;

    public UserPluginTestVO test(String providerCode, Map<String, Object> config) {
        String serverSecret = sysParamsService.getValue(Constant.SERVER_SECRET, true);
        if (StringUtils.isBlank(serverSecret)) {
            throw new RenException("server.secret 未配置，拒绝发送插件凭据进行测试");
        }

        JSONObject payload = new JSONObject()
                .set("providerCode", providerCode)
                .set("config", config);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(serverSecret);

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(30));
        RestTemplate client = new RestTemplate(factory);
        String url = resolveXiaozhiServerHttpBaseUrl() + "/plugins/test";
        try {
            ResponseEntity<String> response = client.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(payload.toString(), headers),
                    String.class);
            JSONObject body = JSONUtil.parseObj(response.getBody());
            return UserPluginTestVO.builder()
                    .providerCode(providerCode)
                    .success(Boolean.TRUE.equals(body.getBool("success")))
                    .message(StringUtils.defaultIfBlank(body.getStr("message"), "插件测试没有返回结果"))
                    .build();
        } catch (ResourceAccessException e) {
            throw new RenException("无法连接 xiaozhi-server 插件测试接口，请确认服务端 HTTP 端口可访问", e);
        } catch (HttpStatusCodeException e) {
            throw new RenException("xiaozhi-server 插件测试接口返回异常：" + e.getStatusCode(), e);
        }
    }

    private String resolveXiaozhiServerHttpBaseUrl() {
        String configured = environment.getProperty("xiaozhi.server.http-base-url");
        if (StringUtils.isNotBlank(configured)) {
            return StringUtils.removeEnd(configured.trim(), "/");
        }
        String port = sysParamsService.getValue("server.http_port", true);
        return "http://127.0.0.1:" + (StringUtils.isNumeric(port) ? port : "8003");
    }
}
