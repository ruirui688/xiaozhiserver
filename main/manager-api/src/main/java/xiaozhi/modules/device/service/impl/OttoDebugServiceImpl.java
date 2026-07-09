package xiaozhi.modules.device.service.impl;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.device.service.OttoDebugService;

@Service
@AllArgsConstructor
public class OttoDebugServiceImpl implements OttoDebugService {
    private static final int OTTO_DEBUG_PORT = 8765;
    private static final int MAX_PACKET_BYTES = 65535;
    private static final int DEFAULT_TIMEOUT_MS = 4500;
    private static final int TOOL_CALL_TIMEOUT_MS = 7000;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private final DeviceService deviceService;
    private final Map<String, InetSocketAddress> sessions = new ConcurrentHashMap<>();

    @Override
    public Object connect(Long userId, String deviceId, String host) {
        DeviceEntity device = requireOttoDevice(userId, deviceId);
        InetAddress address = parseLanAddress(host);
        sessions.put(sessionKey(userId, device.getId()), new InetSocketAddress(address, OTTO_DEBUG_PORT));
        return sendCommand(address, OTTO_DEBUG_PORT, command("hello"), DEFAULT_TIMEOUT_MS);
    }

    @Override
    public Object snapshot(Long userId, String deviceId) {
        return sendToSession(userId, deviceId, command("snapshot"), DEFAULT_TIMEOUT_MS);
    }

    @Override
    public Object listTools(Long userId, String deviceId, String cursor, Integer limit) {
        Map<String, Object> command = command("tools/list");
        command.put("cursor", StringUtils.defaultString(cursor));
        int safeLimit = limit == null ? 8 : Math.max(1, Math.min(limit, 8));
        command.put("limit", safeLimit);
        return sendToSession(userId, deviceId, command, DEFAULT_TIMEOUT_MS);
    }

    @Override
    public Object callTool(Long userId, String deviceId, String name, Map<String, Object> arguments) {
        Map<String, Object> command = toolCallCommand(name, arguments);
        return sendToSession(userId, deviceId, command, TOOL_CALL_TIMEOUT_MS);
    }

    @Override
    public Object callToolFast(Long userId, String deviceId, String name, Map<String, Object> arguments) {
        InetSocketAddress target = requireSession(userId, deviceId);
        Map<String, Object> command = toolCallCommand(name, arguments);
        int id = sendWithoutWaiting(target, command);
        return Map.of("type", "debug_queued", "id", id, "success", true);
    }

    @Override
    public Object getConfig(Long userId, String deviceId) {
        return sendToSession(userId, deviceId, command("config/get"), DEFAULT_TIMEOUT_MS);
    }

    @Override
    public Object setConfig(Long userId, String deviceId, String key, String value) {
        if (!"head_prompt".equals(key) && !"body_prompt".equals(key)) {
            throw new RenException("不支持的配置项");
        }
        String safeValue = StringUtils.defaultString(value);
        if (safeValue.length() > 1200) {
            throw new RenException("提示词不能超过1200字符");
        }
        Map<String, Object> command = command("config/set");
        command.put("key", key);
        command.put("value", safeValue);
        return sendToSession(userId, deviceId, command, DEFAULT_TIMEOUT_MS);
    }

    private Object sendToSession(Long userId, String deviceId, Map<String, Object> command, int timeoutMs) {
        requireOttoDevice(userId, deviceId);
        InetSocketAddress target = requireSession(userId, deviceId);
        return sendCommand(target.getAddress(), target.getPort(), command, timeoutMs);
    }

    private InetSocketAddress requireSession(Long userId, String deviceId) {
        InetSocketAddress target = sessions.get(sessionKey(userId, deviceId));
        if (target == null) {
            throw new RenException("请先输入设备IP并连接");
        }
        return target;
    }

    private DeviceEntity requireOttoDevice(Long userId, String deviceId) {
        DeviceEntity device = deviceService.selectById(deviceId);
        if (device == null || !userId.equals(device.getUserId())) {
            throw new RenException("设备不存在");
        }
        String board = StringUtils.defaultString(device.getBoard()).toLowerCase();
        if (!board.contains("otto")) {
            throw new RenException("该设备不支持舞蹈调试");
        }
        return device;
    }

    private InetAddress parseLanAddress(String host) {
        String value = StringUtils.defaultString(host).trim();
        if (!value.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            throw new RenException("设备IP必须是IPv4地址");
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            if (!(address instanceof Inet4Address)
                    || address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isMulticastAddress()
                    || !address.isSiteLocalAddress()) {
                throw new RenException("设备IP必须是局域网IPv4地址");
            }
            return address;
        } catch (RenException e) {
            throw e;
        } catch (Exception e) {
            throw new RenException("设备IP无效");
        }
    }

    private Map<String, Object> command(String type) {
        Map<String, Object> command = new HashMap<>();
        command.put("type", type);
        return command;
    }

    private Map<String, Object> toolCallCommand(String name, Map<String, Object> arguments) {
        if (StringUtils.isBlank(name)) {
            throw new RenException("工具名称不能为空");
        }
        Map<String, Object> command = command("tools/call");
        command.put("name", name);
        command.put("arguments", arguments == null ? new HashMap<>() : arguments);
        return command;
    }

    private Object sendCommand(InetAddress host, int port, Map<String, Object> command, int timeoutMs) {
        int id = NEXT_ID.getAndUpdate(current -> current >= Integer.MAX_VALUE ? 1 : current + 1);
        command.put("id", id);
        byte[] payload = JSONUtil.toJsonStr(command).getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            socket.send(new DatagramPacket(payload, payload.length, host, port));

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket response = new DatagramPacket(new byte[MAX_PACKET_BYTES], MAX_PACKET_BYTES);
                socket.receive(response);
                String text = new String(response.getData(), response.getOffset(), response.getLength(),
                        StandardCharsets.UTF_8);
                Object data = parsePayload(text);
                if (matchesResponseId(data, id)) {
                    return envelope(response, data);
                }
            }
            throw new RenException("设备调试端未在超时时间内响应");
        } catch (RenException e) {
            throw e;
        } catch (java.net.SocketTimeoutException e) {
            throw new RenException("设备调试端未响应，请检查设备IP、固件调试功能和UDP 8765端口");
        } catch (Exception e) {
            throw new RenException("设备调试UDP通信失败: " + e.getMessage());
        }
    }

    private int sendWithoutWaiting(InetSocketAddress target, Map<String, Object> command) {
        int id = NEXT_ID.getAndUpdate(current -> current >= Integer.MAX_VALUE ? 1 : current + 1);
        command.put("id", id);
        byte[] payload = JSONUtil.toJsonStr(command).getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.send(new DatagramPacket(payload, payload.length, target.getAddress(), target.getPort()));
            return id;
        } catch (Exception e) {
            throw new RenException("设备调试UDP发送失败: " + e.getMessage());
        }
    }

    private Object parsePayload(String text) {
        String trimmed = StringUtils.defaultString(text).trim();
        if (trimmed.startsWith("{")) {
            return JSONUtil.parseObj(trimmed);
        }
        if (trimmed.startsWith("[")) {
            return JSONUtil.parseArray(trimmed);
        }
        return Map.of("raw", text);
    }

    private boolean matchesResponseId(Object data, int id) {
        if (data instanceof cn.hutool.json.JSONObject json) {
            Object responseId = json.get("id");
            return String.valueOf(id).equals(String.valueOf(responseId));
        }
        return false;
    }

    private Map<String, Object> envelope(DatagramPacket packet, Object data) {
        return Map.of(
                "time", Instant.now().getEpochSecond(),
                "from", packet.getAddress().getHostAddress() + ":" + packet.getPort(),
                "data", data);
    }

    private String sessionKey(Long userId, String deviceId) {
        return userId + ":" + deviceId;
    }
}
