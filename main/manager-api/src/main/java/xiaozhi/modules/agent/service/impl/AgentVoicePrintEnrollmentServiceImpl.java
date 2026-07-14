package xiaozhi.modules.agent.service.impl;

import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import cn.hutool.json.JSONObject;
import lombok.AllArgsConstructor;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.ConvertUtils;
import xiaozhi.modules.agent.Enums.AgentChatHistoryType;
import xiaozhi.modules.agent.dao.AgentVoicePrintEnrollmentDao;
import xiaozhi.modules.agent.dao.AgentVoicePrintEnrollmentSampleDao;
import xiaozhi.modules.agent.dto.AgentVoicePrintEnrollmentCreateDTO;
import xiaozhi.modules.agent.dto.AgentVoicePrintEnrollmentSampleDTO;
import xiaozhi.modules.agent.dto.AgentVoicePrintRegistrationResult;
import xiaozhi.modules.agent.dto.AgentVoicePrintSaveDTO;
import xiaozhi.modules.agent.entity.AgentChatHistoryEntity;
import xiaozhi.modules.agent.entity.AgentVoicePrintEnrollmentEntity;
import xiaozhi.modules.agent.entity.AgentVoicePrintEnrollmentSampleEntity;
import xiaozhi.modules.agent.service.AgentChatAudioService;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.agent.service.AgentVoicePrintEnrollmentService;
import xiaozhi.modules.agent.service.AgentVoicePrintService;
import xiaozhi.modules.agent.vo.AgentVoicePrintEnrollmentSampleVO;
import xiaozhi.modules.agent.vo.AgentVoicePrintEnrollmentVO;
import xiaozhi.modules.sys.service.SysParamsService;

@Service
@AllArgsConstructor
public class AgentVoicePrintEnrollmentServiceImpl
        extends ServiceImpl<AgentVoicePrintEnrollmentDao, AgentVoicePrintEnrollmentEntity>
        implements AgentVoicePrintEnrollmentService {
    public static final String STATUS_COLLECTING = "COLLECTING";
    public static final String STATUS_COMPLETING = "COMPLETING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    private static final String DEFAULT_PROMPT_TEXT =
            "今天天气不错，我正在用正常音量录入声纹。读完这句话后，我会保持安静，等待系统完成检测。";

    private final AgentVoicePrintEnrollmentSampleDao sampleDao;
    private final AgentVoicePrintService agentVoicePrintService;
    private final AgentChatHistoryService agentChatHistoryService;
    private final AgentChatAudioService agentChatAudioService;
    private final SysParamsService sysParamsService;
    private final Environment environment;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentVoicePrintEnrollmentVO create(AgentVoicePrintEnrollmentCreateDTO dto) {
        AgentVoicePrintEnrollmentEntity entity = ConvertUtils.sourceToTarget(
                dto, AgentVoicePrintEnrollmentEntity.class);
        entity.setRequiredSamples(1);
        entity.setStatus(STATUS_COLLECTING);
        baseMapper.insert(entity);
        startDeviceEnrollment(entity);
        return toVO(entity, List.of());
    }

    @Override
    public AgentVoicePrintEnrollmentVO get(String id) {
        AgentVoicePrintEnrollmentEntity entity = getExisting(id);
        return toVO(entity, listSamples(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentVoicePrintEnrollmentVO addDeviceSample(String id, AgentVoicePrintEnrollmentSampleDTO dto) {
        AgentVoicePrintEnrollmentEntity entity = getExisting(id);
        requireCollecting(entity);
        requireSampleCapacity(id);
        if (StringUtils.isBlank(dto.getAudioBase64())) {
            throw new RenException(ErrorCode.VOICEPRINT_AUDIO_EMPTY);
        }
        String macAddress = StringUtils.trimToEmpty(dto.getMacAddress());
        if (StringUtils.isNotBlank(entity.getDeviceId())
                && StringUtils.isNotBlank(macAddress)
                && !StringUtils.equalsIgnoreCase(entity.getDeviceId(), macAddress)) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        byte[] audio;
        try {
            audio = Base64.getDecoder().decode(dto.getAudioBase64());
        } catch (IllegalArgumentException e) {
            throw new RenException(ErrorCode.VOICEPRINT_AUDIO_FORMAT_INVALID);
        }
        if (audio.length == 0) {
            throw new RenException(ErrorCode.VOICEPRINT_AUDIO_EMPTY);
        }
        String audioId = agentChatAudioService.saveAudio(audio);
        saveEnrollmentHistory(entity, dto, audioId);
        AgentVoicePrintEnrollmentSampleDTO sampleDTO = new AgentVoicePrintEnrollmentSampleDTO();
        sampleDTO.setAudioId(audioId);
        return addSample(id, sampleDTO);
    }

    @Override
    public AgentVoicePrintEnrollmentVO addSample(String id, AgentVoicePrintEnrollmentSampleDTO dto) {
        AgentVoicePrintEnrollmentEntity entity = getExisting(id);
        requireCollecting(entity);
        String audioId = StringUtils.trimToNull(dto.getAudioId());
        if (audioId == null || !agentChatHistoryService.isAudioOwnedByAgent(audioId, entity.getAgentId())) {
            throw new RenException(ErrorCode.VOICEPRINT_AUDIO_NOT_BELONG_AGENT);
        }

        Long existingCount = sampleDao.selectCount(new QueryWrapper<AgentVoicePrintEnrollmentSampleEntity>()
                .eq("enrollment_id", id)
                .eq("audio_id", audioId));
        if (existingCount != null && existingCount > 0) {
            return toVO(entity, listSamples(id));
        }

        Long sampleCount = sampleDao.selectCount(new QueryWrapper<AgentVoicePrintEnrollmentSampleEntity>()
                .eq("enrollment_id", id));
        requireSampleCapacity(sampleCount);

        AgentVoicePrintEnrollmentSampleEntity sample = new AgentVoicePrintEnrollmentSampleEntity();
        sample.setEnrollmentId(id);
        sample.setAgentId(entity.getAgentId());
        sample.setAudioId(audioId);
        sampleDao.insert(sample);
        return toVO(entity, listSamples(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentVoicePrintEnrollmentVO complete(String id) {
        AgentVoicePrintEnrollmentEntity entity = getExisting(id);
        requireCollecting(entity);
        List<AgentVoicePrintEnrollmentSampleEntity> samples = listSamples(id);
        if (samples.size() < normalizeRequiredSamples(entity.getRequiredSamples())
                || samples.size() > AgentVoicePrintServiceImpl.MAX_REGISTER_SAMPLES) {
            throw new RenException(ErrorCode.VOICEPRINT_SAMPLE_COUNT_INVALID);
        }

        int locked = baseMapper.update(null, new UpdateWrapper<AgentVoicePrintEnrollmentEntity>()
                .eq("id", id)
                .eq("status", STATUS_COLLECTING)
                .set("status", STATUS_COMPLETING));
        if (locked != 1) {
            throw new RenException(ErrorCode.INVALID_SERVER_ACTION, "声纹采集任务状态已变化");
        }

        AgentVoicePrintSaveDTO saveDTO = new AgentVoicePrintSaveDTO();
        saveDTO.setAgentId(entity.getAgentId());
        saveDTO.setSourceName(entity.getSourceName());
        saveDTO.setIntroduce(entity.getIntroduce());
        saveDTO.setAudioIds(samples.stream()
                .map(AgentVoicePrintEnrollmentSampleEntity::getAudioId)
                .toList());
        AgentVoicePrintRegistrationResult registrationResult =
                agentVoicePrintService.insertAndReturnRegistration(saveDTO);
        String voicePrintId = registrationResult.getVoicePrintId();

        baseMapper.update(null, new UpdateWrapper<AgentVoicePrintEnrollmentEntity>()
                .eq("id", id)
                .set("status", STATUS_COMPLETED)
                .set("voice_print_id", voicePrintId));
        AgentVoicePrintEnrollmentVO vo = get(id);
        vo.setRegistrationResult(registrationResult.getRegistrationResult());
        return vo;
    }

    @Override
    public AgentVoicePrintEnrollmentVO cancel(String id) {
        AgentVoicePrintEnrollmentEntity entity = getExisting(id);
        requireCollecting(entity);
        baseMapper.update(null, new UpdateWrapper<AgentVoicePrintEnrollmentEntity>()
                .eq("id", id)
                .eq("status", STATUS_COLLECTING)
                .set("status", STATUS_CANCELLED));
        entity.setStatus(STATUS_CANCELLED);
        return toVO(entity, listSamples(id));
    }

    @Override
    public String getAgentIdByEnrollmentId(String id) {
        AgentVoicePrintEnrollmentEntity entity = baseMapper.selectById(id);
        return entity == null ? null : entity.getAgentId();
    }

    private AgentVoicePrintEnrollmentEntity getExisting(String id) {
        AgentVoicePrintEnrollmentEntity entity = baseMapper.selectById(id);
        if (entity == null) {
            throw new RenException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return entity;
    }

    private void requireCollecting(AgentVoicePrintEnrollmentEntity entity) {
        if (!STATUS_COLLECTING.equals(entity.getStatus())) {
            throw new RenException(ErrorCode.INVALID_SERVER_ACTION, "声纹采集任务状态不允许执行该操作");
        }
    }

    private List<AgentVoicePrintEnrollmentSampleEntity> listSamples(String enrollmentId) {
        return sampleDao.selectList(new QueryWrapper<AgentVoicePrintEnrollmentSampleEntity>()
                .eq("enrollment_id", enrollmentId)
                .orderByAsc("create_date")
                .orderByAsc("id"));
    }

    private void requireSampleCapacity(String enrollmentId) {
        Long sampleCount = sampleDao.selectCount(new QueryWrapper<AgentVoicePrintEnrollmentSampleEntity>()
                .eq("enrollment_id", enrollmentId));
        requireSampleCapacity(sampleCount);
    }

    private void requireSampleCapacity(Long sampleCount) {
        if (sampleCount != null && sampleCount >= AgentVoicePrintServiceImpl.MAX_REGISTER_SAMPLES) {
            throw new RenException(ErrorCode.VOICEPRINT_SAMPLE_COUNT_INVALID);
        }
    }

    private AgentVoicePrintEnrollmentVO toVO(
            AgentVoicePrintEnrollmentEntity entity,
            List<AgentVoicePrintEnrollmentSampleEntity> samples) {
        AgentVoicePrintEnrollmentVO vo = ConvertUtils.sourceToTarget(entity, AgentVoicePrintEnrollmentVO.class);
        vo.setSamples(ConvertUtils.sourceToTarget(samples, AgentVoicePrintEnrollmentSampleVO.class));
        int sampleCount = samples == null ? 0 : samples.size();
        int requiredSamples = normalizeRequiredSamples(entity.getRequiredSamples());
        vo.setSampleCount(sampleCount);
        vo.setCollectedCount(sampleCount);
        vo.setRequiredSamples(requiredSamples);
        vo.setProgress(Math.min(100, Math.round(sampleCount * 100.0f / requiredSamples)));
        return vo;
    }

    private void saveEnrollmentHistory(
            AgentVoicePrintEnrollmentEntity entity,
            AgentVoicePrintEnrollmentSampleDTO dto,
            String audioId) {
        AgentChatHistoryEntity history = AgentChatHistoryEntity.builder()
                .macAddress(StringUtils.defaultIfBlank(dto.getMacAddress(), entity.getDeviceId()))
                .agentId(entity.getAgentId())
                .sessionId(StringUtils.defaultIfBlank(dto.getSessionId(), "voiceprint-enrollment-" + entity.getId()))
                .chatType(AgentChatHistoryType.USER.getValue())
                .content("声纹注册样本：" + entity.getSourceName())
                .audioId(audioId)
                .createdAt(new Date())
                .build();
        agentChatHistoryService.save(history);
    }

    private void startDeviceEnrollment(AgentVoicePrintEnrollmentEntity entity) {
        String baseUrl = resolveXiaozhiServerHttpBaseUrl();
        String serverSecret = sysParamsService.getValue(Constant.SERVER_SECRET, true);
        if (StringUtils.isBlank(serverSecret) || StringUtils.equalsIgnoreCase(serverSecret, "null")) {
            throw new RenException("server.secret 未配置，无法让设备进入声纹采集模式");
        }

        JSONObject payload = new JSONObject();
        payload.set("deviceId", entity.getDeviceId());
        payload.set("requestId", entity.getId());
        payload.set("agentId", entity.getAgentId());
        payload.set("sourceName", entity.getSourceName());
        payload.set("introduce", entity.getIntroduce());
        payload.set("targetSamples", 1);
        payload.set("promptText", DEFAULT_PROMPT_TEXT);
        payload.set("minDurationSeconds", 0.5);
        payload.set("maxDurationSeconds", 30.0);
        payload.set("uploadMode", "server");
        payload.set("reportEndpoint", "/agent/voice-print/enrollments/" + entity.getId() + "/device-samples");

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(10));
        RestTemplate client = new RestTemplate(factory);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(serverSecret);
        try {
            ResponseEntity<String> response = client.exchange(
                    baseUrl + "/device/voiceprint/enrollment/start",
                    HttpMethod.POST,
                    new HttpEntity<>(payload.toString(), headers),
                    String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RenException("设备声纹采集模式启动失败：" + response.getStatusCode());
            }
        } catch (RestClientException e) {
            throw new RenException("无法让设备进入声纹采集模式，请确认设备在线且 xiaozhi-server HTTP 端口可访问", e);
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

    private int normalizeRequiredSamples(Integer value) {
        if (value == null) {
            return 1;
        }
        return Math.min(
                AgentVoicePrintServiceImpl.MAX_REGISTER_SAMPLES,
                Math.max(AgentVoicePrintServiceImpl.MIN_REGISTER_SAMPLES, value));
    }
}
