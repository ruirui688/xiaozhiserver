package xiaozhi.modules.device.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dto.DeviceConversationContextResetDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@Service
public class DeviceConversationContextService {
    static final String RESET_PATH = "/device/session/context/reset";

    private final DeviceService deviceService;
    private final WorkspacePermissionService workspacePermissionService;
    private final SysParamsService sysParamsService;
    private final Environment environment;
    private final RestTemplate restTemplate;

    public DeviceConversationContextService(
            DeviceService deviceService,
            WorkspacePermissionService workspacePermissionService,
            SysParamsService sysParamsService,
            Environment environment,
            RestTemplate restTemplate) {
        this.deviceService = deviceService;
        this.workspacePermissionService = workspacePermissionService;
        this.sysParamsService = sysParamsService;
        this.environment = environment;
        this.restTemplate = restTemplate;
    }

    public DeviceConversationContextResetDTO reset(String deviceRecordId) {
        DeviceEntity device = deviceService.selectById(deviceRecordId);
        if (device == null) {
            throw new RenException("设备不存在");
        }
        workspacePermissionService.requireWrite(device.getWorkspaceId());
        if (StringUtils.isAnyBlank(device.getMacAddress(), device.getAgentId())) {
            throw new RenException("设备连接标识或智能体绑定缺失");
        }

        String serverSecret = sysParamsService.getValue(Constant.SERVER_SECRET, true);
        if (StringUtils.isBlank(serverSecret) || StringUtils.equalsIgnoreCase(serverSecret, "null")) {
            throw new RenException("server.secret未配置，无法控制在线设备");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(serverSecret);
        JSONObject payload = new JSONObject();
        payload.set("deviceId", device.getMacAddress());
        payload.set("agentId", device.getAgentId());

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    resolveXiaozhiServerHttpBaseUrl() + RESET_PATH,
                    HttpMethod.POST,
                    new HttpEntity<>(payload.toString(), headers),
                    String.class);
            return parseResult(response.getBody());
        } catch (RestClientResponseException error) {
            throw new RenException(responseMessage(error.getResponseBodyAsString(),
                    "在线设备拒绝清空上下文"));
        } catch (RenException error) {
            throw error;
        } catch (Exception error) {
            throw new RenException("无法连接实时语音服务，请确认设备在线后重试", error);
        }
    }

    private DeviceConversationContextResetDTO parseResult(String body) {
        if (StringUtils.isBlank(body)) {
            throw new RenException("实时语音服务返回为空");
        }
        JSONObject root;
        try {
            root = JSONUtil.parseObj(body);
        } catch (Exception error) {
            throw new RenException("实时语音服务返回格式无效", error);
        }
        if (!root.getBool("success", false)) {
            throw new RenException(root.getStr("message", "在线设备未能清空上下文"));
        }
        JSONObject data = root.getJSONObject("data");
        if (data == null || !data.getBool("reset", false)) {
            throw new RenException("在线设备未确认上下文已清空");
        }
        return new DeviceConversationContextResetDTO(
                data.getBool("online", false),
                data.getBool("reset", false),
                data.getStr("mode", "pipeline"),
                data.getInt("removedMessages", 0),
                data.getBool("upstreamResetScheduled", false),
                data.getBool("upstreamResetCompleted", false));
    }

    private String responseMessage(String body, String fallback) {
        if (StringUtils.isBlank(body)) {
            return fallback;
        }
        try {
            return JSONUtil.parseObj(body).getStr("message", fallback);
        } catch (Exception ignored) {
            return fallback;
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
