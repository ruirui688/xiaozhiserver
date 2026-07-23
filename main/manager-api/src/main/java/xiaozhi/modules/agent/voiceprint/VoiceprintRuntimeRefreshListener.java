package xiaozhi.modules.agent.voiceprint;

import java.time.Duration;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
public class VoiceprintRuntimeRefreshListener {
    private final SysParamsService sysParamsService;
    private final Environment environment;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void refreshOnlineDevice(VoiceprintRegisteredEvent event) {
        if (event == null || StringUtils.isBlank(event.deviceId())) {
            return;
        }
        try {
            String serverSecret = sysParamsService.getValue(Constant.SERVER_SECRET, true);
            if (StringUtils.isBlank(serverSecret) || StringUtils.equalsIgnoreCase(serverSecret, "null")) {
                log.warn("跳过在线声纹配置刷新: server.secret未配置, deviceId={}", event.deviceId());
                return;
            }

            JSONObject payload = new JSONObject();
            payload.set("deviceId", event.deviceId());
            payload.set("agentId", event.agentId());
            payload.set("voicePrintId", event.voicePrintId());

            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
            factory.setReadTimeout(Duration.ofSeconds(10));
            RestTemplate client = new RestTemplate(factory);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(serverSecret);
            client.exchange(
                    resolveXiaozhiServerHttpBaseUrl() + "/device/voiceprint/refresh",
                    HttpMethod.POST,
                    new HttpEntity<>(payload.toString(), headers),
                    String.class);
            log.info("在线声纹配置刷新完成: agentId={}, deviceId={}", event.agentId(), event.deviceId());
        } catch (Exception error) {
            // 声纹已经持久化成功；在线刷新失败只影响当前连接，设备下次重连仍会读取最新配置。
            log.warn(
                    "在线声纹配置刷新失败: agentId={}, deviceId={}, errorType={}",
                    event.agentId(),
                    event.deviceId(),
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
