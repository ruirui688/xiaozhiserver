package xiaozhi.modules.device.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dto.DeviceAudioPreviewTicketDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@Service
public class DeviceAudioPreviewTicketService {
    static final int TICKET_EXPIRES_IN_SECONDS = 60;
    static final String CLIENT_ID_PREFIX = "web-audio-preview-";
    static final String PREVIEW_PATH = "/xiaozhi/audio-preview/";

    private final DeviceService deviceService;
    private final SysParamsService sysParamsService;
    private final WorkspacePermissionService workspacePermissionService;

    public DeviceAudioPreviewTicketService(DeviceService deviceService, SysParamsService sysParamsService,
            WorkspacePermissionService workspacePermissionService) {
        this.deviceService = deviceService;
        this.sysParamsService = sysParamsService;
        this.workspacePermissionService = workspacePermissionService;
    }

    public DeviceAudioPreviewTicketDTO issue(String deviceRecordId) {
        DeviceEntity device = deviceService.selectById(deviceRecordId);
        if (device == null) {
            throw new RenException("设备不存在");
        }
        workspacePermissionService.requireRead(device.getWorkspaceId());
        if (StringUtils.isBlank(device.getMacAddress())) {
            throw new RenException("设备连接标识缺失");
        }

        String configuredWebsocket = sysParamsService.getValue(Constant.SERVER_WEBSOCKET, true);
        String target = firstConfiguredWebsocket(configuredWebsocket);
        String previewUrl = buildPreviewUrl(target);
        String clientId = CLIENT_ID_PREFIX + UUID.randomUUID();
        String token;
        try {
            token = deviceService.generateWebSocketToken(clientId, device.getMacAddress());
        } catch (Exception e) {
            throw new RenException("生成真实音频预览票据失败", e);
        }
        return new DeviceAudioPreviewTicketDTO(
                previewUrl,
                device.getMacAddress(),
                clientId,
                token,
                TICKET_EXPIRES_IN_SECONDS);
    }

    static String firstConfiguredWebsocket(String configuredWebsocket) {
        if (StringUtils.isBlank(configuredWebsocket)) {
            throw new RenException("WebSocket服务地址未配置");
        }
        return Arrays.stream(configuredWebsocket.split(";"))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElseThrow(() -> new RenException("WebSocket服务地址未配置"));
    }

    static String buildPreviewUrl(String websocketUrl) {
        try {
            URI source = new URI(websocketUrl);
            String scheme = source.getScheme();
            if (!("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme))
                    || StringUtils.isBlank(source.getHost())) {
                throw new RenException("WebSocket服务地址格式无效");
            }
            return new URI(
                    scheme.toLowerCase(),
                    source.getUserInfo(),
                    source.getHost(),
                    source.getPort(),
                    PREVIEW_PATH,
                    null,
                    null).toString();
        } catch (URISyntaxException e) {
            throw new RenException("WebSocket服务地址格式无效", e);
        }
    }
}
