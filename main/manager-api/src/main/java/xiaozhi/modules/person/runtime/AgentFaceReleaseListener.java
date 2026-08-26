package xiaozhi.modules.person.runtime;

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
public class AgentFaceReleaseListener {
    private final SysParamsService sysParamsService;
    private final Environment environment;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void notifyOnlineDevices(AgentFaceReleaseEvent event) {
        if (event == null || event.agentIds().isEmpty()) {
            return;
        }
        try {
            String secret = sysParamsService.getValue(Constant.SERVER_SECRET, true);
            if (StringUtils.isBlank(secret) || StringUtils.equalsIgnoreCase(secret, "null")) {
                log.warn("跳过在线人脸库发布通知: server.secret未配置, agentCount={}", event.agentIds().size());
                return;
            }
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
            factory.setReadTimeout(Duration.ofSeconds(10));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(secret);
            JSONObject payload = new JSONObject();
            payload.set("agentIds", event.agentIds());
            new RestTemplate(factory).exchange(
                    resolveXiaozhiServerHttpBaseUrl() + "/device/face-profiles/release",
                    HttpMethod.POST, new HttpEntity<>(payload.toString(), headers), String.class);
            log.info("在线人脸库发布通知已提交: agentCount={}", event.agentIds().size());
        } catch (Exception error) {
            // 发布事实已经提交；离线设备或通知失败设备会在下次版本协商时补齐。
            log.warn("在线人脸库发布通知失败: agentCount={}, errorType={}",
                    event.agentIds().size(), error.getClass().getSimpleName());
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
