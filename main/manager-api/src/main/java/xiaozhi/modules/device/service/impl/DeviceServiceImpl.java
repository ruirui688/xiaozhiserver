package xiaozhi.modules.device.service.impl;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.StringUtils;
import org.springframework.aop.framework.AopContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.common.redis.RedisKeys;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.service.impl.BaseServiceImpl;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.ConvertUtils;
import xiaozhi.common.utils.DateUtils;
import xiaozhi.common.utils.ToolUtil;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.dto.DeviceManualAddDTO;
import xiaozhi.modules.device.dto.DevicePageUserDTO;
import xiaozhi.modules.device.dto.DeviceReportReqDTO;
import xiaozhi.modules.device.dto.DeviceReportRespDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.entity.OtaEntity;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.device.service.DeviceSceneService;
import xiaozhi.modules.device.service.OtaService;
import xiaozhi.modules.device.runtime.DeviceRuntimeConfigRefreshEvent;
import xiaozhi.modules.device.vo.UserShowDeviceListVO;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.sys.service.SysUserUtilService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;
import xiaozhi.modules.workspace.service.WorkspaceService;

@Slf4j
@Service
@AllArgsConstructor
public class DeviceServiceImpl extends BaseServiceImpl<DeviceDao, DeviceEntity> implements DeviceService {
    private final DeviceDao deviceDao;
    private final SysUserUtilService sysUserUtilService;
    private final SysParamsService sysParamsService;
    private final RedisUtils redisUtils;
    private final OtaService otaService;
    private final AgentDao agentDao;
    private final WorkspaceService workspaceService;
    private final WorkspacePermissionService workspacePermissionService;
    private final DeviceSceneService deviceSceneService;
    private final ApplicationEventPublisher eventPublisher;

    @Async
    public void updateDeviceConnectionInfo(String agentId, String deviceId, String appVersion) {
        try {
            DeviceEntity device = new DeviceEntity();
            device.setId(deviceId);
            device.setLastConnectedAt(new Date());
            if (StringUtils.isNotBlank(appVersion)) {
                device.setAppVersion(appVersion);
            }
            deviceDao.updateById(device);
            if (StringUtils.isNotBlank(agentId)) {
                redisUtils.set(RedisKeys.getAgentDeviceLastConnectedAtById(agentId), new Date());
            }
        } catch (Exception e) {
            log.error("异步更新设备连接信息失败", e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deviceActivation(String agentId, String activationCode) {
        if (StringUtils.isBlank(activationCode)) {
            throw new RenException(ErrorCode.ACTIVATION_CODE_EMPTY);
        }
        String deviceKey = RedisKeys.getOtaActivationCode(activationCode);
        Object cacheDeviceId = redisUtils.get(deviceKey);
        if (ToolUtil.isEmpty(cacheDeviceId)) {
            throw new RenException(ErrorCode.ACTIVATION_CODE_ERROR);
        }
        String deviceId = (String) cacheDeviceId;
        String safeDeviceId = deviceId.replace(":", "_").toLowerCase();
        String cacheDeviceKey = RedisKeys.getOtaDeviceActivationInfo(safeDeviceId);
        Map<String, Object> cacheMap = (Map<String, Object>) redisUtils.get(cacheDeviceKey);
        if (ToolUtil.isEmpty(cacheMap)) {
            throw new RenException(ErrorCode.ACTIVATION_CODE_ERROR);
        }
        String cachedCode = (String) cacheMap.get("activation_code");
        if (!activationCode.equals(cachedCode)) {
            throw new RenException(ErrorCode.ACTIVATION_CODE_ERROR);
        }
        // 检查设备有没有被激活
        if (selectById(deviceId) != null) {
            throw new RenException(ErrorCode.DEVICE_ALREADY_ACTIVATED);
        }

        String macAddress = (String) cacheMap.get("mac_address");
        String board = (String) cacheMap.get("board");
        String appVersion = (String) cacheMap.get("app_version");
        UserDetail user = SecurityUser.getUser();
        if (user.getId() == null) {
            throw new RenException(ErrorCode.USER_NOT_LOGIN);
        }
        AgentEntity agent = agentDao.selectById(agentId);
        if (agent == null) {
            throw new RenException(ErrorCode.AGENT_NOT_FOUND);
        }
        workspacePermissionService.requireWrite(agent.getWorkspaceId());

        Date currentTime = new Date();
        DeviceEntity deviceEntity = new DeviceEntity();
        deviceEntity.setId(deviceId);
        deviceEntity.setWorkspaceId(agent.getWorkspaceId());
        deviceEntity.setBoard(board);
        deviceEntity.setAgentId(agentId);
        deviceEntity.setAppVersion(appVersion);
        deviceEntity.setMacAddress(macAddress);
        deviceEntity.setUserId(user.getId());
        deviceEntity.setCreator(user.getId());
        deviceEntity.setAutoUpdate(1);
        deviceEntity.setCreateDate(currentTime);
        deviceEntity.setUpdater(user.getId());
        deviceEntity.setUpdateDate(currentTime);
        deviceEntity.setLastConnectedAt(currentTime);
        deviceDao.insert(deviceEntity);
        deviceSceneService.ensureCurrentBinding(deviceEntity, agent, user.getId());
        eventPublisher.publishEvent(DeviceRuntimeConfigRefreshEvent.forActivation(deviceId, agentId));

        // 清理redis缓存、清除智能体设备数量缓存
        redisUtils.delete(List.of(cacheDeviceKey, deviceKey, RedisKeys.getAgentDeviceCountById(agentId)));
        return true;
    }

    /**
     * 获取设备在线数据
     */
    @Override
    public String getDeviceOnlineData(String agentId) {
        // 从系统参数中获取MQTT网关地址
        String mqttGatewayUrl = getMqttManagerApiBase();
        if (StringUtils.isBlank(mqttGatewayUrl)) {
            return "";
        }
        // 构建完整的URL
        String url = mqttGatewayUrl + "/api/devices/status";

        // 获取当前用户的设备列表
        UserDetail user = SecurityUser.getUser();
        List<DeviceEntity> devices = getUserDevices(user.getId(), agentId);

        // 构建deviceIds数组
        Set<String> deviceIds = devices.stream().map(o -> {
            String macAddress = Optional.ofNullable(o.getMacAddress()).orElse("unknown").replace(":", "_");
            String groupId = Optional.ofNullable(o.getBoard()).orElse("GID_default").replace(":", "_");
            return StrUtil.format("{}@@@{}@@@{}", groupId, macAddress, macAddress);
        }).collect(Collectors.toSet());

        // 构建请求入参
        Map<String, Set<String>> params = MapUtil
                .builder(new HashMap<String, Set<String>>())
                .put("clientIds", deviceIds).build();

        if (ToolUtil.isNotEmpty(deviceIds)) {
            try {
                return HttpRequest.post(url)
                        .header(Header.CONTENT_TYPE, ContentType.JSON.getValue())
                        .header(Header.AUTHORIZATION, "Bearer " + generateBearerToken())
                        .body(JSONUtil.toJsonStr(params))
                        .timeout(10000) // 超时，毫秒
                        .execute().body();
            } catch (Exception e) {
                log.warn("MQTT网关设备在线状态查询失败: {}", url, e);
                return "";
            }
        }
        // 返回响应
        return "";
    }

    @Override
    public DeviceReportRespDTO checkDeviceActive(String macAddress, String clientId, DeviceReportReqDTO deviceReport) {
        DeviceReportRespDTO response = new DeviceReportRespDTO();
        response.setServer_time(buildServerTime());

        DeviceEntity deviceById = getDeviceByMacAddress(macAddress);
        if (deviceById != null) {
            response.setDevice_config(buildDeviceConfig(deviceById));
        }

        // 设备未绑定，则返回当前上传的固件信息（不更新）以此兼容旧固件版本
        if (deviceById == null) {
            DeviceReportRespDTO.Firmware firmware = new DeviceReportRespDTO.Firmware();
            firmware.setVersion(deviceReport.getApplication().getVersion());
            firmware.setUrl(Constant.INVALID_FIRMWARE_URL);
            response.setFirmware(firmware);
        } else {
            // 只有在设备已绑定且autoUpdate不为0的情况下才返回固件升级信息
            if (deviceById.getAutoUpdate() != 0) {
                String type = deviceReport.getBoard() == null ? null : deviceReport.getBoard().getType();
                DeviceReportRespDTO.Firmware firmware = buildFirmwareInfo(type,
                        deviceReport.getApplication() == null ? null : deviceReport.getApplication().getVersion());
                response.setFirmware(firmware);
            }
        }

        // 添加WebSocket配置
        DeviceReportRespDTO.Websocket websocket = new DeviceReportRespDTO.Websocket();
        // 从系统参数获取WebSocket URL，如果未配置则使用默认值
        String wsUrl = sysParamsService.getValue(Constant.SERVER_WEBSOCKET, true);

        // 检查是否启用认证并生成token
        String authEnabled = sysParamsService.getValue(Constant.SERVER_AUTH_ENABLED, true);
        if ("true".equalsIgnoreCase(authEnabled)) {
            try {
                // 生成token
                String token = generateWebSocketToken(clientId, macAddress);
                websocket.setToken(token);
            } catch (Exception e) {
                log.error("生成WebSocket token失败: {}", e.getMessage());
                websocket.setToken("");
            }
        } else {
            websocket.setToken("");
        }

        if (StringUtils.isBlank(wsUrl) || wsUrl.equals("null")) {
            log.error("WebSocket地址未配置，请登录智控台，在参数管理找到【server.websocket】配置");
            wsUrl = "ws://xiaozhi.server.com:8000/xiaozhi/v1/";
            websocket.setUrl(wsUrl);
        } else {
            String[] wsUrls = wsUrl.split("\\;");
            if (wsUrls.length > 0) {
                // 随机选择一个WebSocket URL
                websocket.setUrl(wsUrls[RandomUtil.randomInt(0, wsUrls.length)]);
            } else {
                log.error("WebSocket地址未配置，请登录智控台，在参数管理找到【server.websocket】配置");
                websocket.setUrl("ws://xiaozhi.server.com:8000/xiaozhi/v1/");
            }
        }

        response.setWebsocket(websocket);
        log.info("OTA下发WebSocket: deviceId={}, clientId={}, websocketUrl={}, authEnabled={}, tokenPresent={}",
                macAddress, clientId, websocket.getUrl(), authEnabled, StringUtils.isNotBlank(websocket.getToken()));

        // 添加MQTT UDP配置
        // 从系统参数获取MQTT Gateway地址，仅在配置有效时使用
        String mqttUdpConfig = sysParamsService.getValue(Constant.SERVER_MQTT_GATEWAY, true);
        if (mqttUdpConfig != null && !mqttUdpConfig.equals("null") && !mqttUdpConfig.isEmpty()) {
            try {
                String groupId = deviceById != null && deviceById.getBoard() != null ? deviceById.getBoard()
                        : "GID_default";
                DeviceReportRespDTO.MQTT mqtt = buildMqttConfig(macAddress, groupId);
                if (mqtt != null) {
                    mqtt.setEndpoint(mqttUdpConfig);
                    response.setMqtt(mqtt);
                    log.info("OTA下发MQTT: deviceId={}, endpoint={}, clientId={}", macAddress,
                            mqtt.getEndpoint(), mqtt.getClient_id());
                }
            } catch (Exception e) {
                log.error("生成MQTT配置失败: {}", e.getMessage());
            }
        }

        if (deviceById != null) {
            // 如果设备存在，则异步更新上次连接时间和版本信息
            String appVersion = deviceReport.getApplication() != null ? deviceReport.getApplication().getVersion()
                    : null;
            // 通过Spring代理调用异步方法
            ((DeviceServiceImpl) AopContext.currentProxy()).updateDeviceConnectionInfo(deviceById.getAgentId(),
                    deviceById.getId(), appVersion);
        } else {
            // 如果设备不存在，则生成激活码
            DeviceReportRespDTO.Activation code = buildActivation(macAddress, deviceReport);
            response.setActivation(code);
        }

        return response;
    }

    @Override
    public List<DeviceEntity> getUserDevices(Long userId, String agentId) {
        String workspaceId = workspaceService.getCurrentWorkspaceEntity().getId();
        QueryWrapper<DeviceEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("workspace_id", workspaceId);
        wrapper.eq("agent_id", agentId);
        return baseDao.selectList(wrapper);
    }

    @Override
    public List<UserShowDeviceListVO> getUserDeviceList(Long userId, String agentId) {
        List<DeviceEntity> devices = getUserDevices(userId, agentId);
        return devices.stream().map(device -> {
            UserShowDeviceListVO vo = ConvertUtils.sourceToTarget(device, UserShowDeviceListVO.class);
            vo.setDeviceType(device.getBoard());
            // 设置UTC时间戳供前端使用时区转换
            if (device.getLastConnectedAt() != null) {
                vo.setLastConnectedAtTimestamp(device.getLastConnectedAt().getTime());
            }
            return vo;
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbindDevice(Long userId, String deviceId) {
        // 先查询设备信息，获取agentId
        DeviceEntity device = baseDao.selectById(deviceId);
        if (device == null) {
            return;
        }
        workspacePermissionService.requireWrite(device.getWorkspaceId());
        if (StringUtils.isNotBlank(device.getAgentId())) {
            // 清除智能体设备数量缓存
            redisUtils.delete(RedisKeys.getAgentDeviceCountById(device.getAgentId()));
        }

        deviceSceneService.deleteByDeviceId(deviceId);
        UpdateWrapper<DeviceEntity> wrapper = new UpdateWrapper<>();
        wrapper.eq("workspace_id", device.getWorkspaceId());
        wrapper.eq("id", deviceId);
        baseDao.delete(wrapper);
    }

    @Override
    public void deleteByUserId(Long userId) {
        UpdateWrapper<DeviceEntity> wrapper = new UpdateWrapper<>();
        wrapper.eq("user_id", userId);
        baseDao.delete(wrapper);
    }

    @Override
    public Long selectCountByUserId(Long userId) {
        UpdateWrapper<DeviceEntity> wrapper = new UpdateWrapper<>();
        wrapper.eq("user_id", userId);
        return baseDao.selectCount(wrapper);
    }

    @Override
    public void deleteByAgentId(String agentId) {
        UpdateWrapper<DeviceEntity> wrapper = new UpdateWrapper<>();
        wrapper.eq("agent_id", agentId);
        baseDao.delete(wrapper);
    }

    @Override
    public PageData<UserShowDeviceListVO> page(DevicePageUserDTO dto) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put(Constant.PAGE, dto.getPage());
        params.put(Constant.LIMIT, dto.getLimit());
        IPage<DeviceEntity> page = baseDao.selectPage(
                getPage(params, "mac_address", true),
                // 定义查询条件
                new QueryWrapper<DeviceEntity>()
                        // 必须设备关键词查找
                        .like(StringUtils.isNotBlank(dto.getKeywords()), "alias", dto.getKeywords()));
        // 循环处理page获取回来的数据，返回需要的字段
        List<UserShowDeviceListVO> list = page.getRecords().stream().map(device -> {
            UserShowDeviceListVO vo = ConvertUtils.sourceToTarget(device, UserShowDeviceListVO.class);
            // 把最后修改的时间，改为简短描述的时间
            vo.setRecentChatTime(DateUtils.getShortTime(device.getUpdateDate()));
            sysUserUtilService.assignUsername(device.getUserId(),
                    vo::setBindUserName);
            vo.setDeviceType(device.getBoard());
            vo.setBoard(device.getBoard());
            // 设置UTC时间戳供前端使用时区转换
            if (device.getLastConnectedAt() != null) {
                vo.setLastConnectedAtTimestamp(device.getLastConnectedAt().getTime());
            }
            return vo;
        }).toList();
        // 计算页数
        return new PageData<>(list, page.getTotal());
    }

    @Override
    public DeviceEntity getDeviceByMacAddress(String macAddress) {
        if (StringUtils.isBlank(macAddress)) {
            return null;
        }
        QueryWrapper<DeviceEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("mac_address", macAddress);
        return baseDao.selectOne(wrapper);
    }

    private DeviceReportRespDTO.ServerTime buildServerTime() {
        DeviceReportRespDTO.ServerTime serverTime = new DeviceReportRespDTO.ServerTime();
        TimeZone tz = TimeZone.getDefault();
        serverTime.setTimestamp(Instant.now().toEpochMilli());
        serverTime.setTimeZone(tz.getID());
        serverTime.setTimezone_offset(tz.getOffset(System.currentTimeMillis()) / (60 * 1000));
        return serverTime;
    }

    private DeviceReportRespDTO.DeviceConfig buildDeviceConfig(DeviceEntity device) {
        if (device == null || StringUtils.isBlank(device.getAgentId())) {
            return null;
        }

        AgentEntity agent = agentDao.selectById(device.getAgentId());
        if (agent == null || StringUtils.isBlank(agent.getAgentName())) {
            return null;
        }

        Date revisionDate = agent.getUpdatedAt() != null ? agent.getUpdatedAt() : agent.getCreatedAt();
        DeviceReportRespDTO.DeviceConfig deviceConfig = new DeviceReportRespDTO.DeviceConfig();
        deviceConfig.setVersion(1);
        deviceConfig.setRevision(revisionDate == null ? 0L : revisionDate.getTime());
        deviceConfig.setAssistant_name(agent.getAgentName());
        deviceConfig.setWake_words(List.of(agent.getAgentName()));
        return deviceConfig;
    }

    @Override
    public String geCodeByDeviceId(String deviceId) {
        String dataKey = getDeviceCacheKey(deviceId);

        Map<String, Object> cacheMap = (Map<String, Object>) redisUtils.get(dataKey);
        if (cacheMap != null && cacheMap.containsKey("activation_code")) {
            String cachedCode = (String) cacheMap.get("activation_code");
            return cachedCode;
        }
        return null;
    }

    @Override
    public Date getLatestLastConnectionTime(String agentId) {
        // 查询是否有缓存时间，有则返回
        Date cachedDate = (Date) redisUtils.get(RedisKeys.getAgentDeviceLastConnectedAtById(agentId));
        if (cachedDate != null) {
            return cachedDate;
        }
        Date maxDate = deviceDao.getAllLastConnectedAtByAgentId(agentId);
        if (maxDate != null) {
            redisUtils.set(RedisKeys.getAgentDeviceLastConnectedAtById(agentId), maxDate);
        }
        return maxDate;
    }

    private String getDeviceCacheKey(String deviceId) {
        String safeDeviceId = deviceId.replace(":", "_").toLowerCase();
        return RedisKeys.getOtaDeviceActivationInfo(safeDeviceId);
    }

    public DeviceReportRespDTO.Activation buildActivation(String deviceId, DeviceReportReqDTO deviceReport) {
        DeviceReportRespDTO.Activation code = new DeviceReportRespDTO.Activation();

        String cachedCode = geCodeByDeviceId(deviceId);

        if (StringUtils.isNotBlank(cachedCode)) {
            code.setCode(cachedCode);
            String frontedUrl = sysParamsService.getValue(Constant.SERVER_FRONTED_URL, true);
            code.setMessage(frontedUrl + "\n" + cachedCode);
            code.setChallenge(deviceId);
        } else {
            String newCode = RandomUtil.randomNumbers(6);
            code.setCode(newCode);
            String frontedUrl = sysParamsService.getValue(Constant.SERVER_FRONTED_URL, true);
            code.setMessage(frontedUrl + "\n" + newCode);
            code.setChallenge(deviceId);

            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("id", deviceId);
            dataMap.put("mac_address", deviceId);

            dataMap.put("board", (deviceReport.getBoard() != null && deviceReport.getBoard().getType() != null)
                    ? deviceReport.getBoard().getType()
                    : (deviceReport.getChipModelName() != null ? deviceReport.getChipModelName() : "unknown"));
            dataMap.put("app_version", (deviceReport.getApplication() != null)
                    ? deviceReport.getApplication().getVersion()
                    : null);

            dataMap.put("deviceId", deviceId);
            dataMap.put("activation_code", newCode);

            // 写入主数据 key
            String dataKey = getDeviceCacheKey(deviceId);
            redisUtils.set(dataKey, dataMap);

            // 写入反查激活码 key
            String codeKey = RedisKeys.getOtaActivationCode(newCode);
            redisUtils.set(codeKey, deviceId);
        }
        return code;
    }

    private DeviceReportRespDTO.Firmware buildFirmwareInfo(String type, String currentVersion) {
        if (StringUtils.isBlank(type)) {
            return null;
        }
        if (StringUtils.isBlank(currentVersion)) {
            currentVersion = "0.0.0";
        }

        OtaEntity ota = otaService.getLatestOta(type);
        DeviceReportRespDTO.Firmware firmware = new DeviceReportRespDTO.Firmware();
        String downloadUrl = null;

        if (ota != null) {
            // 如果设备没有版本信息，或者OTA版本比设备版本新，则返回下载地址
            if (compareVersions(ota.getVersion(), currentVersion) > 0) {
                String otaUrl = sysParamsService.getValue(Constant.SERVER_OTA, true);
                if (StringUtils.isBlank(otaUrl) || otaUrl.equals("null")) {
                    log.error("OTA地址未配置，请登录智控台，在参数管理找到【server.ota】配置");
                    // 尝试从请求中获取
                    HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder
                            .getRequestAttributes())
                            .getRequest();
                    otaUrl = request.getRequestURL().toString();
                }
                // 将URL中的/ota/替换为/otaMag/download/
                String uuid = UUID.randomUUID().toString();
                redisUtils.set(RedisKeys.getOtaIdKey(uuid), ota.getId());
                downloadUrl = otaUrl.replace("/ota/", "/otaMag/download/") + uuid;
            }
        }

        firmware.setVersion(ota == null ? currentVersion : ota.getVersion());
        firmware.setUrl(downloadUrl == null ? Constant.INVALID_FIRMWARE_URL : downloadUrl);
        return firmware;
    }

    /**
     * 比较两个版本号
     * 
     * @param version1 版本1
     * @param version2 版本2
     * @return 如果version1 > version2返回1，version1 < version2返回-1，相等返回0
     */
    private static int compareVersions(String version1, String version2) {
        if (version1 == null || version2 == null) {
            return 0;
        }

        String[] v1Parts = version1.split("\\.");
        String[] v2Parts = version2.split("\\.");

        int length = Math.max(v1Parts.length, v2Parts.length);
        for (int i = 0; i < length; i++) {
            int v1 = i < v1Parts.length ? Integer.parseInt(v1Parts[i]) : 0;
            int v2 = i < v2Parts.length ? Integer.parseInt(v2Parts[i]) : 0;

            if (v1 > v2) {
                return 1;
            } else if (v1 < v2) {
                return -1;
            }
        }
        return 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void manualAddDevice(Long userId, DeviceManualAddDTO dto) {
        AgentEntity agent = agentDao.selectById(dto.getAgentId());
        if (agent == null) {
            throw new RenException(ErrorCode.AGENT_NOT_FOUND);
        }
        workspacePermissionService.requireWrite(agent.getWorkspaceId());
        // 检查mac是否已存在
        QueryWrapper<DeviceEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("mac_address", dto.getMacAddress());
        DeviceEntity exist = baseDao.selectOne(wrapper);
        if (exist != null) {
            throw new RenException(ErrorCode.MAC_ADDRESS_ALREADY_EXISTS);
        }
        Date now = new Date();
        DeviceEntity entity = new DeviceEntity();
        entity.setId(dto.getMacAddress());
        entity.setUserId(userId);
        entity.setWorkspaceId(agent.getWorkspaceId());
        entity.setAgentId(dto.getAgentId());
        entity.setBoard(dto.getBoard());
        entity.setAppVersion(dto.getAppVersion());
        entity.setMacAddress(dto.getMacAddress());
        entity.setCreateDate(now);
        entity.setUpdateDate(now);
        entity.setLastConnectedAt(now);
        entity.setCreator(userId);
        entity.setUpdater(userId);
        entity.setAutoUpdate(1);
        baseDao.insert(entity);
        deviceSceneService.ensureCurrentBinding(entity, agent, userId);

        // 添加：清除智能体设备数量缓存
        redisUtils.delete(RedisKeys.getAgentDeviceCountById(dto.getAgentId()));
    }

    @Override
    public List<DeviceEntity> searchDevicesByMacAddress(String macAddress, Long userId) {
        String workspaceId = workspaceService.getCurrentWorkspaceEntity().getId();
        QueryWrapper<DeviceEntity> wrapper = new QueryWrapper<>();
        wrapper.like("mac_address", macAddress);
        wrapper.eq("workspace_id", workspaceId);
        return deviceDao.selectList(wrapper);
    }

    /**
     * 生成MQTT密码签名
     * 
     * @param content   签名内容 (clientId + '|' + username)
     * @param secretKey 密钥
     * @return Base64编码的HMAC-SHA256签名
     */
    private String generatePasswordSignature(String content, String secretKey) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        hmac.init(keySpec);
        byte[] signature = hmac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature);
    }

    /**
     * 生成WebSocket认证token 遵循Python端AuthManager的实现逻辑：token = signature.timestamp
     * 
     * @param clientId 客户端ID
     * @param username 用户名 (通常为deviceId/macAddress)
     * @return 认证token字符串
     */
    public String generateWebSocketToken(String clientId, String username)
            throws NoSuchAlgorithmException, InvalidKeyException {
        // 从系统参数获取密钥
        String secretKey = sysParamsService.getValue(Constant.SERVER_SECRET, false);
        if (StringUtils.isBlank(secretKey)) {
            throw new IllegalStateException("WebSocket认证密钥未配置(server.secret)");
        }

        // 获取当前时间戳(秒)
        long timestamp = System.currentTimeMillis() / 1000;

        // 构建签名内容: clientId|username|timestamp
        String content = String.format("%s|%s|%d", clientId, username, timestamp);

        // 生成HMAC-SHA256签名
        Mac hmac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        hmac.init(keySpec);
        byte[] signature = hmac.doFinal(content.getBytes(StandardCharsets.UTF_8));

        // Base64 URL-safe编码签名(去除填充符=)
        String signatureBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        // 返回格式: signature.timestamp
        return String.format("%s.%d", signatureBase64, timestamp);
    }

    /**
     * 构建MQTT配置信息
     * 
     * @param macAddress MAC地址
     * @param groupId    分组ID
     * @return MQTT配置对象
     */
    private DeviceReportRespDTO.MQTT buildMqttConfig(String macAddress, String groupId)
            throws Exception {
        // 从环境变量或系统参数获取签名密钥
        String signatureKey = sysParamsService.getValue("server.mqtt_signature_key", true);
        if (StringUtils.isBlank(signatureKey) || "null".equalsIgnoreCase(signatureKey.trim())) {
            log.warn("缺少MQTT_SIGNATURE_KEY，跳过MQTT配置生成");
            return null;
        }

        // 构建客户端ID格式：groupId@@@macAddress@@@uuid
        String groupIdSafeStr = groupId.replace(":", "_");
        String deviceIdSafeStr = macAddress.replace(":", "_");
        String mqttClientId = String.format("%s@@@%s@@@%s", groupIdSafeStr, deviceIdSafeStr, deviceIdSafeStr);

        // 构建用户数据（包含IP等信息）
        Map<String, String> userData = new HashMap<>();
        // 尝试获取客户端IP
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder
                    .getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                String clientIp = request.getRemoteAddr();
                userData.put("ip", clientIp);
            }
        } catch (Exception e) {
            userData.put("ip", "unknown");
        }

        // 将用户数据编码为Base64 JSON
        String userDataJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(userData);
        String username = Base64.getEncoder().encodeToString(userDataJson.getBytes(StandardCharsets.UTF_8));

        // 生成密码签名
        String password = generatePasswordSignature(mqttClientId + "|" + username, signatureKey);

        // 构建MQTT配置
        DeviceReportRespDTO.MQTT mqtt = new DeviceReportRespDTO.MQTT();
        mqtt.setClient_id(mqttClientId);
        mqtt.setUsername(username);
        mqtt.setPassword(password);
        mqtt.setPublish_topic("device-server");
        mqtt.setSubscribe_topic("devices/p2p/" + deviceIdSafeStr);

        return mqtt;
    }

    /**
     * 生成BearerToken
     */
    private String generateBearerToken() {
        try {
            String dateStr = DateUtil.format(new Date(), DatePattern.NORM_DATE_PATTERN);
            String signatureKey = sysParamsService.getValue(Constant.SERVER_MQTT_SECRET, false);
            if (ToolUtil.isEmpty(signatureKey)) {
                return null;
            }
            return DigestUtil.sha256Hex(dateStr + signatureKey);
        } catch (Exception e) {
            return null;
        }
    }

    private String getMqttManagerApiBase() {
        String mqttGatewayUrl = sysParamsService.getValue("server.mqtt_manager_api", true);
        if (StringUtils.isBlank(mqttGatewayUrl) || "null".equalsIgnoreCase(mqttGatewayUrl.trim())) {
            return null;
        }

        String trimmedUrl = mqttGatewayUrl.trim();
        if (StringUtils.startsWithIgnoreCase(trimmedUrl, "ws://")
                || StringUtils.startsWithIgnoreCase(trimmedUrl, "wss://")) {
            return null;
        }

        if (trimmedUrl.contains("://")
                && !StringUtils.startsWithIgnoreCase(trimmedUrl, "http://")
                && !StringUtils.startsWithIgnoreCase(trimmedUrl, "https://")) {
            return null;
        }

        String baseUrl = StringUtils.startsWithIgnoreCase(trimmedUrl, "http://")
                || StringUtils.startsWithIgnoreCase(trimmedUrl, "https://")
                        ? trimmedUrl
                        : "http://" + trimmedUrl;
        baseUrl = StringUtils.removeEnd(baseUrl, "/");

        try {
            URI uri = URI.create(baseUrl);
            if (StringUtils.isBlank(uri.getScheme()) || StringUtils.isBlank(uri.getHost())) {
                return null;
            }
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            return baseUrl;
        } catch (Exception e) {
            log.warn("解析server.mqtt_manager_api失败: {}", mqttGatewayUrl, e);
            return null;
        }
    }

    private String getMqttManagerApiConfigError() {
        String mqttGatewayUrl = sysParamsService.getValue("server.mqtt_manager_api", true);
        if (StringUtils.isBlank(mqttGatewayUrl) || "null".equalsIgnoreCase(mqttGatewayUrl.trim())) {
            return "MQTT网关管理API未配置";
        }
        if (StringUtils.startsWithIgnoreCase(mqttGatewayUrl.trim(), "ws://")
                || StringUtils.startsWithIgnoreCase(mqttGatewayUrl.trim(), "wss://")) {
            return "server.mqtt_manager_api配置错误：不能填写WebSocket地址，应填写MQTT网关管理API地址，例如 10.19.1.209:8007";
        }
        return "server.mqtt_manager_api配置无效：应填写MQTT网关管理API地址，例如 10.19.1.209:8007";
    }

    @Override
    public Map<String, Object> forwardCallRequest(String callerMac, String targetMac, String callerNickname) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "error");
        result.put("message", "网关配置缺失");

        // 从系统参数获取网关配置
        String mqttGatewayUrl = getMqttManagerApiBase();
        String mqttSignatureKey = sysParamsService.getValue("server.mqtt_signature_key", true);

        if (StringUtils.isBlank(mqttGatewayUrl)) {
            log.error("MQTT网关地址未配置");
            result.put("message", getMqttManagerApiConfigError());
            return result;
        }
        if (StringUtils.isBlank(mqttSignatureKey) || "null".equals(mqttSignatureKey)) {
            log.error("MQTT签名密钥未配置");
            result.put("message", "MQTT签名密钥未配置");
            return result;
        }

        // 生成token: SHA256(date + secret)
        String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date());
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((dateStr + mqttSignatureKey).getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            String token = hexString.toString();

            // 构建请求体
            Map<String, Object> body = new HashMap<>();
            body.put("caller_mac", callerMac);
            body.put("target_mac", targetMac);
            body.put("caller_nickname", callerNickname);

            // 发送请求并获取响应
            String url = mqttGatewayUrl + "/api/call/request";
            String response = cn.hutool.http.HttpRequest.post(url)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .body(JSONUtil.toJsonStr(body))
                    .timeout(5000)
                    .execute()
                    .body();

            // 解析网关响应
            if (StringUtils.isNotBlank(response)) {
                Map<String, Object> gwResult = JSONUtil.parseObj(response);
                result.put("status", gwResult.get("status"));
                result.put("message", gwResult.get("message"));
            }
            return result;
        } catch (Exception e) {
            log.error("转发呼叫请求失败: {}", e.getMessage());
            result.put("message", "呼叫请求转发失败: " + e.getMessage());
            return result;
        }
    }
}
