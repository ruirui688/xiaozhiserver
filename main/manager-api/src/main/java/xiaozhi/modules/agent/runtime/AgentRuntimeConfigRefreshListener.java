package xiaozhi.modules.agent.runtime;

import java.time.Duration;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Async;
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
public class AgentRuntimeConfigRefreshListener {
    private static final int MAX_AGENT_IDS_PER_REQUEST = 500;

    private final SysParamsService sysParamsService;
    private final Environment environment;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void refreshOnlineConnections(AgentRuntimeConfigRefreshEvent event) {
        if (event == null || event.agentIds().isEmpty()) {
            return;
        }
        try {
            String serverSecret = sysParamsService.getValue(Constant.SERVER_SECRET, true);
            if (StringUtils.isBlank(serverSecret) || StringUtils.equalsIgnoreCase(serverSecret, "null")) {
                log.warn("跳过在线智能体配置刷新: server.secret未配置, agentCount={}", event.agentIds().size());
                return;
            }

            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
            factory.setReadTimeout(Duration.ofSeconds(10));
            RestTemplate client = new RestTemplate(factory);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(serverSecret);
            String refreshUrl = resolveXiaozhiServerHttpBaseUrl() + "/device/runtime-config/refresh";
            for (int start = 0; start < event.agentIds().size(); start += MAX_AGENT_IDS_PER_REQUEST) {
                int end = Math.min(start + MAX_AGENT_IDS_PER_REQUEST, event.agentIds().size());
                JSONObject payload = new JSONObject();
                payload.set("agentIds", event.agentIds().subList(start, end));
                payload.set("reason", event.reason());
                client.exchange(
                        refreshUrl,
                        HttpMethod.POST,
                        new HttpEntity<>(payload.toString(), headers),
                        String.class);
            }
            log.info("在线智能体配置刷新已提交: agentCount={}, reason={}",
                    event.agentIds().size(), event.reason());
        } catch (Exception error) {
            // 数据库事实已经提交；Python还会在下一轮开始前主动检查配置。
            log.warn("在线智能体配置刷新通知失败: agentCount={}, errorType={}",
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
