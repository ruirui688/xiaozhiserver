package xiaozhi.modules.device.runtime;

import java.time.Duration;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestTemplate;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.constant.Constant;
import xiaozhi.modules.sys.service.SysParamsService;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceRuntimeConfigRefreshListener {
    private final SysParamsService sysParamsService;
    private final Environment environment;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void refreshOnlineDevice(DeviceRuntimeConfigRefreshEvent event) {
        if (event == null || StringUtils.isBlank(event.deviceId())) {
            return;
        }
        try {
            String serverSecret = sysParamsService.getValue(Constant.SERVER_SECRET, true);
            if (StringUtils.isBlank(serverSecret) || StringUtils.equalsIgnoreCase(serverSecret, "null")) {
                log.warn("跳过设备场景刷新: server.secret未配置, deviceId={}", event.deviceId());
                return;
            }
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
            factory.setReadTimeout(Duration.ofSeconds(10));
            RestTemplate client = new RestTemplate(factory);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(serverSecret);

            JSONObject switchRequest = new JSONObject();
            switchRequest.set("deviceId", event.deviceId());
            switchRequest.set("fromAgentId", event.fromAgentId());
            switchRequest.set("toAgentId", event.toAgentId());
            switchRequest.set("switchRequestId", event.switchRequestId());
            switchRequest.set("configVersion", event.configVersion());
            JSONObject payload = new JSONObject();
            payload.set("deviceIds", java.util.List.of(event.deviceId()));
            payload.set("reason", event.reason());
            payload.set("sceneSwitch", switchRequest);
            client.exchange(resolveXiaozhiServerHttpBaseUrl() + "/device/runtime-config/refresh",
                    HttpMethod.POST, new HttpEntity<>(payload.toString(), headers), String.class);
            log.info("设备场景刷新已提交: deviceId={}, requestId={}, configVersion={}",
                    event.deviceId(), event.switchRequestId(), event.configVersion());
        } catch (Exception error) {
            log.warn("设备场景刷新通知失败: deviceId={}, requestId={}, errorType={}",
                    event.deviceId(), event.switchRequestId(), error.getClass().getSimpleName());
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
