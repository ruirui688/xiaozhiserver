package xiaozhi.modules.person.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.person.dao.AgentFaceReleaseDao;
import xiaozhi.modules.person.dao.PersonAgentBindingDao;
import xiaozhi.modules.person.dao.PersonFaceAssetDao;
import xiaozhi.modules.person.dao.PersonRecognitionEventDao;
import xiaozhi.modules.person.dto.AgentFaceSyncRequestDTO;
import xiaozhi.modules.person.dto.DeviceFaceRecognitionEventDTO;
import xiaozhi.modules.person.entity.AgentFaceReleaseEntity;
import xiaozhi.modules.person.entity.PersonAgentBindingEntity;
import xiaozhi.modules.person.entity.PersonFaceAssetEntity;
import xiaozhi.modules.person.entity.PersonRecognitionEventEntity;
import xiaozhi.modules.person.service.PersonFaceRuntimeService;
import xiaozhi.modules.person.service.PersonFaceStorage;
import xiaozhi.modules.person.service.PersonProfileService;
import xiaozhi.modules.person.vo.AgentFaceSyncVO;
import xiaozhi.modules.person.vo.DeviceFaceProfileManifestVO;
import xiaozhi.modules.person.vo.DeviceFaceRecognitionActionVO;

@Service
@RequiredArgsConstructor
public class PersonFaceRuntimeServiceImpl implements PersonFaceRuntimeService {
    private final DeviceDao deviceDao;
    private final PersonAgentBindingDao bindingDao;
    private final PersonFaceAssetDao assetDao;
    private final AgentFaceReleaseDao releaseDao;
    private final PersonRecognitionEventDao eventDao;
    private final PersonFaceStorage faceStorage;

    @Override
    public AgentFaceSyncVO sync(AgentFaceSyncRequestDTO request) {
        DeviceEntity device = requireBoundDevice(request.getMacAddress());
        String agentId = device.getAgentId();
        long releaseVersion = releaseVersion(agentId);
        long cachedVersion = Objects.requireNonNullElse(request.getReleaseVersion(), 0L);
        boolean sameAgent = Objects.equals(agentId, StringUtils.trimToNull(request.getCachedAgentId()));

        List<AgentFaceSyncVO.Profile> current = publishedProfiles(agentId, device.getWorkspaceId());
        Map<String, AgentFaceSyncRequestDTO.CachedProfile> cached = new LinkedHashMap<>();
        if (sameAgent && request.getProfiles() != null) {
            for (AgentFaceSyncRequestDTO.CachedProfile item : request.getProfiles()) {
                cached.putIfAbsent(item.getPersonId(), item);
            }
        }
        Set<String> currentIds = new LinkedHashSet<>();
        List<AgentFaceSyncVO.Profile> upserts = new ArrayList<>();
        for (AgentFaceSyncVO.Profile profile : current) {
            currentIds.add(profile.getPersonId());
            AgentFaceSyncRequestDTO.CachedProfile local = cached.get(profile.getPersonId());
            if (local == null
                    || !Objects.equals(local.getFaceVersion(), profile.getFaceVersion())
                    || !Objects.equals(local.getFaceSha256(), profile.getFaceSha256())
                    || !Objects.equals(local.getName(), profile.getName())
                    || !Objects.equals(local.getGroupName(), profile.getGroupName())
                    || !Objects.equals(local.getDescription(), profile.getDescription())
                    || !Objects.equals(normalizeAction(local.getRecognitionAction()),
                            profile.getRecognitionAction())) {
                upserts.add(profile);
            }
        }
        List<String> deletes = new ArrayList<>();
        List<AgentFaceSyncRequestDTO.CachedProfile> localProfiles = request.getProfiles() == null
                ? List.of() : request.getProfiles();
        for (AgentFaceSyncRequestDTO.CachedProfile local : localProfiles) {
            if (!currentIds.contains(local.getPersonId())) {
                deletes.add(local.getPersonId());
            }
        }
        if (sameAgent && cachedVersion == releaseVersion && upserts.isEmpty() && deletes.isEmpty()) {
            return AgentFaceSyncVO.builder()
                    .protocolVersion(2).agentId(agentId).releaseVersion(releaseVersion)
                    .unchanged(true).upserts(List.of()).deletes(List.of()).build();
        }
        return AgentFaceSyncVO.builder()
                .protocolVersion(2).agentId(agentId).releaseVersion(releaseVersion)
                .unchanged(false).upserts(upserts).deletes(deletes).build();
    }

    @Override
    public PersonProfileService.FaceImage readPublishedFace(
            String macAddress, String personId, String assetId) {
        DeviceEntity device = requireBoundDevice(macAddress);
        PersonAgentBindingEntity binding = publishedBinding(device, personId);
        if (!Objects.equals(binding.getPublishedFaceAssetId(), assetId)) {
            throw new RenException("人脸资源版本未授权给当前智能体");
        }
        return readAsset(requireAsset(assetId, device.getWorkspaceId(), personId));
    }

    @Override
    public DeviceFaceProfileManifestVO getLegacyManifest(String macAddress) {
        DeviceEntity device = requireBoundDevice(macAddress);
        List<AgentFaceSyncVO.Profile> profiles = publishedProfiles(device.getAgentId(), device.getWorkspaceId());
        return DeviceFaceProfileManifestVO.builder()
                .deviceId(device.getId())
                .macAddress(device.getMacAddress())
                .syncVersion(releaseVersion(device.getAgentId()))
                .profiles(profiles.stream().map(profile -> DeviceFaceProfileManifestVO.Profile.builder()
                        .personId(profile.getPersonId()).name(profile.getName()).groupName(profile.getGroupName())
                        .description(profile.getDescription())
                        .faceVersion(profile.getFaceVersion()).faceSha256(profile.getFaceSha256())
                        .contentType(profile.getContentType()).recognitionAction(profile.getRecognitionAction())
                        .build()).toList())
                .build();
    }

    @Override
    public PersonProfileService.FaceImage readLegacyFace(String macAddress, String personId) {
        DeviceEntity device = requireBoundDevice(macAddress);
        PersonAgentBindingEntity binding = publishedBinding(device, personId);
        return readAsset(requireAsset(binding.getPublishedFaceAssetId(), device.getWorkspaceId(), personId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = DuplicateKeyException.class)
    public DeviceFaceRecognitionActionVO recordRecognition(DeviceFaceRecognitionEventDTO dto) {
        DeviceEntity device = requireBoundDevice(dto.getMacAddress());
        PersonAgentBindingEntity binding = publishedBinding(device, dto.getPersonId());
        PersonRecognitionEventEntity existing = eventDao.selectOne(
                new LambdaQueryWrapper<PersonRecognitionEventEntity>()
                        .eq(PersonRecognitionEventEntity::getDeviceId, device.getId())
                        .eq(PersonRecognitionEventEntity::getDeviceEventId, dto.getEventId())
                        .last("LIMIT 1"));
        if (existing != null) {
            return buildAction(dto.getEventId(), binding, true, existing.getActionType());
        }
        String action = normalizeAction(binding.getPublishedRecognitionAction());
        PersonRecognitionEventEntity event = new PersonRecognitionEventEntity();
        event.setWorkspaceId(device.getWorkspaceId());
        event.setDeviceId(device.getId());
        event.setPersonId(dto.getPersonId());
        event.setDeviceEventId(dto.getEventId());
        event.setConfidence(normalizeConfidence(dto.getConfidence()));
        event.setOccurredAt(dto.getOccurredAt());
        event.setActionType(action);
        try {
            eventDao.insert(event);
            return buildAction(dto.getEventId(), binding, false, action);
        } catch (DuplicateKeyException error) {
            return buildAction(dto.getEventId(), binding, true, action);
        }
    }

    private List<AgentFaceSyncVO.Profile> publishedProfiles(String agentId, String workspaceId) {
        List<PersonAgentBindingEntity> bindings = bindingDao.selectList(
                new LambdaQueryWrapper<PersonAgentBindingEntity>()
                        .eq(PersonAgentBindingEntity::getAgentId, agentId)
                        .eq(PersonAgentBindingEntity::getWorkspaceId, workspaceId)
                        .eq(PersonAgentBindingEntity::getPublishedActive, 1)
                        .isNotNull(PersonAgentBindingEntity::getPublishedFaceAssetId)
                        .orderByAsc(PersonAgentBindingEntity::getPublishedPersonName));
        if (bindings.isEmpty()) {
            return List.of();
        }
        Set<String> assetIds = bindings.stream().map(PersonAgentBindingEntity::getPublishedFaceAssetId)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, PersonFaceAssetEntity> assets = new LinkedHashMap<>();
        for (PersonFaceAssetEntity asset : assetDao.selectBatchIds(assetIds)) {
            if (Objects.equals(asset.getWorkspaceId(), workspaceId)) {
                assets.put(asset.getId(), asset);
            }
        }
        List<AgentFaceSyncVO.Profile> result = new ArrayList<>();
        for (PersonAgentBindingEntity binding : bindings) {
            PersonFaceAssetEntity asset = assets.get(binding.getPublishedFaceAssetId());
            if (asset == null || !Objects.equals(asset.getPersonId(), binding.getPersonId())) {
                continue;
            }
            result.add(AgentFaceSyncVO.Profile.builder()
                    .personId(binding.getPersonId()).assetId(asset.getId())
                    .name(binding.getPublishedPersonName()).groupName(binding.getPublishedGroupName())
                    .description(binding.getPublishedDescription())
                    .faceVersion(Objects.requireNonNullElse(asset.getAssetVersion(), 0L))
                    .faceSha256(asset.getSha256()).contentType(asset.getContentType())
                    .recognitionAction(normalizeAction(binding.getPublishedRecognitionAction()))
                    .build());
        }
        return result;
    }

    private PersonAgentBindingEntity publishedBinding(DeviceEntity device, String personId) {
        PersonAgentBindingEntity binding = bindingDao.selectOne(
                new LambdaQueryWrapper<PersonAgentBindingEntity>()
                        .eq(PersonAgentBindingEntity::getWorkspaceId, device.getWorkspaceId())
                        .eq(PersonAgentBindingEntity::getAgentId, device.getAgentId())
                        .eq(PersonAgentBindingEntity::getPersonId, personId)
                        .eq(PersonAgentBindingEntity::getPublishedActive, 1)
                        .last("LIMIT 1"));
        if (binding == null) {
            throw new RenException("当前智能体未发布此人物人脸");
        }
        return binding;
    }

    private PersonFaceAssetEntity requireAsset(String assetId, String workspaceId, String personId) {
        PersonFaceAssetEntity asset = StringUtils.isBlank(assetId) ? null : assetDao.selectById(assetId);
        if (asset == null || !Objects.equals(asset.getWorkspaceId(), workspaceId)
                || !Objects.equals(asset.getPersonId(), personId)) {
            throw new RenException("人脸资源不可用");
        }
        return asset;
    }

    private PersonProfileService.FaceImage readAsset(PersonFaceAssetEntity asset) {
        byte[] bytes = faceStorage.read(asset.getFilePath());
        return new PersonProfileService.FaceImage(new ByteArrayResource(bytes),
                StringUtils.defaultIfBlank(asset.getContentType(), "image/jpeg"), asset.getSha256(),
                Objects.requireNonNullElse(asset.getAssetVersion(), 0L));
    }

    private long releaseVersion(String agentId) {
        AgentFaceReleaseEntity release = releaseDao.selectOne(new LambdaQueryWrapper<AgentFaceReleaseEntity>()
                .eq(AgentFaceReleaseEntity::getAgentId, agentId).last("LIMIT 1"));
        return release == null ? 0L : Objects.requireNonNullElse(release.getReleaseVersion(), 0L);
    }

    private DeviceEntity requireBoundDevice(String macAddress) {
        if (StringUtils.isBlank(macAddress)) {
            throw new RenException("设备标识不能为空");
        }
        DeviceEntity device = deviceDao.selectOne(new LambdaQueryWrapper<DeviceEntity>()
                .eq(DeviceEntity::getMacAddress, macAddress.trim()).last("LIMIT 1"));
        if (device == null || StringUtils.isBlank(device.getWorkspaceId()) || StringUtils.isBlank(device.getAgentId())) {
            throw new RenException("设备不存在或尚未绑定智能体");
        }
        return device;
    }

    private DeviceFaceRecognitionActionVO buildAction(
            String eventId, PersonAgentBindingEntity binding, boolean duplicate, String actionType) {
        String action = normalizeAction(actionType);
        return DeviceFaceRecognitionActionVO.builder()
                .eventId(eventId).personId(binding.getPersonId()).personName(binding.getPublishedPersonName())
                .description(binding.getPublishedDescription())
                .actionType(action)
                .text("SPEAK_TEXT".equals(action) ? binding.getPublishedGreetingText() : null)
                .duplicate(duplicate).build();
    }

    private String normalizeAction(String action) {
        return "SPEAK_TEXT".equals(action) ? "SPEAK_TEXT" : "NONE";
    }

    private BigDecimal normalizeConfidence(BigDecimal confidence) {
        return confidence == null ? null : confidence.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }
}
