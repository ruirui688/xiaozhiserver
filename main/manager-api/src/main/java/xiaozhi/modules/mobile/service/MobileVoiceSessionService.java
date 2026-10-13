package xiaozhi.modules.mobile.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.device.dto.DeviceManualAddDTO;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.entity.SysUserEntity;
import xiaozhi.modules.mobile.config.MobileVoiceProperties;
import xiaozhi.modules.mobile.dao.MobileVoiceDao;
import xiaozhi.modules.workspace.dao.WorkspaceDao;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

/** Authenticated control-plane provisioning. No physical device identity is accepted from the client. */
@Service
@RequiredArgsConstructor
public class MobileVoiceSessionService {
    public static final String DEVICE_PREFIX = "mobile-";
    public static final String CLIENT_PREFIX = "mobile-voice-";
    public static final String BOARD = "mobile-voice";
    public static final int CONNECT_TTL_SECONDS = 120;
    public static final int MAX_SESSION_SECONDS = 1800;
    private final AgentService agents;
    private final DeviceService devices;
    private final SysParamsService params;
    private final WorkspacePermissionService permissions;
    private final SysUserDao users;
    private final WorkspaceDao workspaces;
    private final DeviceDao deviceDao;
    private final MobileVoiceProperties limits;
    private final MobileVoiceDao mobileDao;

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED, timeout = 10)
    public Ticket issue(String agentId, String installationId, long userId) {
        if (agentId == null || !agentId.matches("[A-Za-z0-9_-]{1,64}") || userId <= 0) {
            throw new RenException("手机会话参数无效");
        }
        try {
            if (!UUID.fromString(installationId).toString().equalsIgnoreCase(installationId)) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new RenException("手机安装标识无效");
        }
        AgentEntity agent = agents.selectById(agentId);
        if (agent == null) throw new RenException("智能体不存在");
        // Provisioning creates a workspace device and can consume its payer's allowance.
        permissions.requireWrite(agent.getWorkspaceId());
        if (!Objects.equals(permissions.currentWorkspaceId(), agent.getWorkspaceId())) {
            throw new RenException("请切换到智能体所属的工作空间");
        }
        String url = websocketUrl(params.getValue(Constant.SERVER_WEBSOCKET, true));

        // Match account lifecycle lock order: user -> workspace -> device. A JVM monitor
        // would neither serialize other replicas nor roll back partial provisioning.
        SysUserEntity user = users.selectByIdForUpdate(userId);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw new RenException("用户不可用");
        }
        if (workspaces.lockByIdForAgentQuota(agent.getWorkspaceId()) == null) {
            throw new RenException("工作空间不可用");
        }
        // Clear the ORM's pre-lock snapshot; READ_COMMITTED then sees current authorization.
        AgentEntity currentAgent = mobileDao.currentAgent(agentId);
        if (currentAgent == null || !Objects.equals(currentAgent.getWorkspaceId(), agent.getWorkspaceId())) {
            throw new RenException("智能体归属已变化，请重试");
        }
        permissions.requireWrite(agent.getWorkspaceId());
        String deviceId = deviceId(userId, agentId, installationId.toLowerCase(Locale.ROOT));
        DeviceEntity existing = deviceDao.selectByIdForUpdate(deviceId);
        if (existing == null) {
            int limit = limits.getMaxDevicesPerUserWorkspace();
            // Never load an unbounded device list or count the entire physical fleet.
            int count = deviceDao.selectList(new QueryWrapper<DeviceEntity>().select("id")
                    .eq("workspace_id", agent.getWorkspaceId()).eq("user_id", userId)
                    .eq("board", BOARD).last("LIMIT " + limit)).size();
            if (count >= limit) {
                throw new RenException("手机语音设备数量已达上限（" + limit + "），请在设备管理中删除不再使用的手机");
            }
            DeviceManualAddDTO input = new DeviceManualAddDTO();
            input.setAgentId(agentId);
            input.setMacAddress(deviceId);
            input.setBoard(BOARD);
            input.setAppVersion("mobile-0.6");
            devices.manualAddDevice(userId, input);
            DeviceEntity display = new DeviceEntity();
            display.setId(deviceId);
            display.setAlias("手机语音调试");
            display.setAutoUpdate(0);
            if (!devices.updateById(display)) {
                throw new RenException("手机语音设备初始化失败");
            }
        } else if (!Objects.equals(existing.getUserId(), userId)
                || !Objects.equals(existing.getMacAddress(), deviceId)
                || !Objects.equals(existing.getAgentId(), agentId)
                || !Objects.equals(existing.getWorkspaceId(), agent.getWorkspaceId())
                || !BOARD.equals(existing.getBoard())) {
            throw new RenException("手机调试设备归属不匹配");
        }
        String clientId = CLIENT_PREFIX + UUID.randomUUID();
        try {
            return new Ticket(url, deviceId, clientId, devices.generateWebSocketToken(clientId, deviceId),
                    CONNECT_TTL_SECONDS, MAX_SESSION_SECONDS);
        } catch (Exception e) {
            // Do not expose the signing exception or server parameters to the caller.
            throw new RenException("手机语音凭证签发失败，请联系管理员");
        }
    }

    static String deviceId(long user, String agent, String installation) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (user + "|" + agent + "|" + installation).getBytes(StandardCharsets.UTF_8));
            return DEVICE_PREFIX + HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    static String websocketUrl(String configured) {
        try {
            String first = java.util.Arrays.stream(configured.split(";")).map(String::trim)
                    .filter(value -> !value.isEmpty()).findFirst().orElseThrow();
            URI uri = new URI(first);
            if (!"wss".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            return uri.toString();
        } catch (Exception e) { throw new RenException("请先配置安全的 WSS 语音服务地址"); }
    }

    public record Ticket(String websocketUrl, String deviceId, String clientId, String token,
                         int expiresInSeconds, int maxSessionSeconds) {
        @Override public String toString() { return "MobileVoiceTicket[redacted]"; }
    }
}
