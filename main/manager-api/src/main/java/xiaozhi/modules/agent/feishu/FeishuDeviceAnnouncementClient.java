package xiaozhi.modules.agent.feishu;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.constant.Constant;
import xiaozhi.modules.sys.service.SysParamsService;

@Component
@RequiredArgsConstructor
@Slf4j
public class FeishuDeviceAnnouncementClient {
    private final RestTemplate restTemplate;
    private final SysParamsService sysParamsService;
    private final Environment environment;

    @Async
    public void broadcast(String agentId, String content) {
        String serverSecret = sysParamsService.getValue(Constant.SERVER_SECRET, true);
        if (StringUtils.isBlank(serverSecret) || StringUtils.equalsIgnoreCase(serverSecret, "null")) {
            log.warn("飞书群消息未播报: server.secret未配置, agentId={}", agentId);
            return;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(serverSecret);
        try {
            restTemplate.exchange(
                    resolveXiaozhiServerHttpBaseUrl() + "/device/notifications/broadcast",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("agentId", agentId, "content", content), headers),
                    Map.class);
        } catch (RestClientException error) {
            log.warn(
                    "飞书群消息设备播报请求失败: agentId={}, errorType={}",
                    agentId,
                    error.getClass().getSimpleName());
        }
    }

    private String resolveXiaozhiServerHttpBaseUrl() {
        String configured = environment.getProperty("xiaozhi.server.http-base-url");
        if (StringUtils.isNotBlank(configured)) {
            return StringUtils.removeEnd(configured.trim(), "/");
        }
        String value = sysParamsService.getValue("server.http_port", true);
        int port = StringUtils.isNumeric(value) ? Integer.parseInt(value) : 8003;
        return "http://127.0.0.1:" + port;
    }
}
