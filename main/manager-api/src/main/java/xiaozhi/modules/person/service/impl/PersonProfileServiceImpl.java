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
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.person.dao.PersonDeviceBindingDao;
import xiaozhi.modules.person.dao.PersonProfileDao;
import xiaozhi.modules.person.dao.PersonRecognitionEventDao;
import xiaozhi.modules.person.dto.DeviceFaceRecognitionEventDTO;
import xiaozhi.modules.person.dto.PersonProfileSaveDTO;
import xiaozhi.modules.person.entity.PersonDeviceBindingEntity;
import xiaozhi.modules.person.entity.PersonProfileEntity;
import xiaozhi.modules.person.entity.PersonRecognitionEventEntity;
import xiaozhi.modules.person.service.PersonFaceStorage;
import xiaozhi.modules.person.service.PersonProfileService;
import xiaozhi.modules.person.vo.DeviceFaceProfileManifestVO;
import xiaozhi.modules.person.vo.DeviceFaceRecognitionActionVO;
import xiaozhi.modules.person.vo.PersonDeviceOptionVO;
import xiaozhi.modules.person.vo.PersonProfileVO;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@Service
@RequiredArgsConstructor
public class PersonProfileServiceImpl implements PersonProfileService {
    private static final String ACTION_NONE = "NONE";
    private static final String ACTION_SPEAK_TEXT = "SPEAK_TEXT";

    private final PersonProfileDao profileDao;
    private final PersonDeviceBindingDao bindingDao;
    private final PersonRecognitionEventDao eventDao;
    private final DeviceDao deviceDao;
    private final PersonFaceStorage faceStorage;
    private final WorkspacePermissionService workspacePermissionService;

    @Override
    public List<PersonProfileVO> list(String keyword) {
        String workspaceId = workspacePermissionService.currentWorkspaceId();
        workspacePermissionService.requireRead(workspaceId);
        LambdaQueryWrapper<PersonProfileEntity> query = new LambdaQueryWrapper<PersonProfileEntity>()
                .eq(PersonProfileEntity::getWorkspaceId, workspaceId)
                .orderByAsc(PersonProfileEntity::getGroupName)
                .orderByDesc(PersonProfileEntity::getUpdateDate);
        String normalizedKeyword = StringUtils.trimToEmpty(keyword);
        if (StringUtils.isNotBlank(normalizedKeyword)) {
            query.and(wrapper -> wrapper
                    .like(PersonProfileEntity::getName, normalizedKeyword)
                    .or()
                    .like(PersonProfileEntity::getGroupName, normalizedKeyword)
                    .or()
                    .like(PersonProfileEntity::getDescription, normalizedKeyword));
        }
        List<PersonProfileEntity> profiles = profileDao.selectList(query);
        return toVOs(profiles, workspaceId);
    }

    @Override
    public List<PersonDeviceOptionVO> listWorkspaceDevices() {
        String workspaceId = workspacePermissionService.currentWorkspaceId();
        workspacePermissionService.requireRead(workspaceId);
        return workspaceDevices(workspaceId).stream()
                .map(device -> PersonDeviceOptionVO.builder()
                        .id(device.getId())
                        .name(deviceName(device))
                        .macAddress(device.getMacAddress())
                        .board(device.getBoard())
                        .build())
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PersonProfileVO create(PersonProfileSaveDTO dto) {
        String workspaceId = workspacePermissionService.currentWorkspaceId();
        workspacePermissionService.requireWrite(workspaceId);
        PersonProfileEntity entity = new PersonProfileEntity();
        entity.setWorkspaceId(workspaceId);
        applyEditableFields(entity, dto);
        entity.setFaceVersion(0L);
        profileDao.insert(entity);
        replaceDeviceBindings(entity, dto.getDeviceIds());
        return toVOs(List.of(entity), workspaceId).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PersonProfileVO update(String id, PersonProfileSaveDTO dto) {
        PersonProfileEntity entity = requireProfile(id, true);
        applyEditableFields(entity, dto);
        profileDao.updateById(entity);
        replaceDeviceBindings(entity, dto.getDeviceIds());
        return toVOs(List.of(entity), entity.getWorkspaceId()).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        PersonProfileEntity entity = requireProfile(id, true);
        profileDao.deleteById(id);
        faceStorage.deleteQuietly(entity.getFaceFilePath());
    }

    @Override
    public PersonProfileVO uploadFace(String id, MultipartFile file) {
        PersonProfileEntity entity = requireProfile(id, true);
        PersonFaceStorage.StoredFace stored = faceStorage.store(entity.getWorkspaceId(), entity.getId(), file);
        String previousPath = entity.getFaceFilePath();
        try {
            entity.setFaceFilePath(stored.relativePath());
            entity.setFaceContentType(stored.contentType());
            entity.setFaceSha256(stored.sha256());
            entity.setFaceVersion(Math.max(0L, Objects.requireNonNullElse(entity.getFaceVersion(), 0L)) + 1L);
            entity.setUpdater(SecurityUser.getUserId());
            entity.setUpdateDate(new Date());
            if (profileDao.updateById(entity) != 1) {
                throw new RenException("保存人脸照片失败");
            }
        } catch (RuntimeException e) {
            faceStorage.deleteQuietly(stored.relativePath());
            throw e;
        }
        faceStorage.deleteQuietly(previousPath);
        return toVOs(List.of(entity), entity.getWorkspaceId()).get(0);
    }

    @Override
    public void deleteFace(String id) {
        PersonProfileEntity entity = requireProfile(id, true);
        String previousPath = entity.getFaceFilePath();
        entity.setFaceFilePath(null);
        entity.setFaceContentType(null);
        entity.setFaceSha256(null);
        entity.setFaceVersion(Math.max(0L, Objects.requireNonNullElse(entity.getFaceVersion(), 0L)) + 1L);
        profileDao.updateById(entity);
        faceStorage.deleteQuietly(previousPath);
    }

    @Override
    public FaceImage readFaceForManagement(String id) {
        PersonProfileEntity entity = requireProfile(id, false);
        return readFace(entity);
    }

    @Override
    public DeviceFaceProfileManifestVO getDeviceManifest(String macAddress) {
        DeviceEntity device = requireBoundDevice(macAddress);
        List<PersonDeviceBindingEntity> bindings = bindingDao.selectList(
                new LambdaQueryWrapper<PersonDeviceBindingEntity>()
                        .eq(PersonDeviceBindingEntity::getDeviceId, device.getId())
                        .eq(PersonDeviceBindingEntity::getWorkspaceId, device.getWorkspaceId()));
        if (bindings.isEmpty()) {
            return DeviceFaceProfileManifestVO.builder()
                    .deviceId(device.getId())
                    .macAddress(device.getMacAddress())
                    .syncVersion(0L)
                    .profiles(List.of())
                    .build();
        }
        Set<String> personIds = bindings.stream().map(PersonDeviceBindingEntity::getPersonId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<PersonProfileEntity> profiles = profileDao.selectList(
                new LambdaQueryWrapper<PersonProfileEntity>()
                        .in(PersonProfileEntity::getId, personIds)
                        .eq(PersonProfileEntity::getWorkspaceId, device.getWorkspaceId())
                        .eq(PersonProfileEntity::getStatus, 1)
                        .isNotNull(PersonProfileEntity::getFaceFilePath)
                        .orderByAsc(PersonProfileEntity::getName));
        List<DeviceFaceProfileManifestVO.Profile> items = profiles.stream()
                .map(profile -> DeviceFaceProfileManifestVO.Profile.builder()
                        .personId(profile.getId())
                        .name(profile.getName())
                        .groupName(profile.getGroupName())
                        .faceVersion(Objects.requireNonNullElse(profile.getFaceVersion(), 0L))
                        .faceSha256(profile.getFaceSha256())
                        .contentType(profile.getFaceContentType())
                        .recognitionAction(normalizeAction(profile.getRecognitionAction()))
                        .build())
                .toList();
        long syncVersion = 17L;
        for (DeviceFaceProfileManifestVO.Profile item : items) {
            syncVersion = 31L * syncVersion + item.getPersonId().hashCode();
            syncVersion = 31L * syncVersion + item.getFaceVersion();
            syncVersion = 31L * syncVersion + Objects.hashCode(item.getFaceSha256());
        }
        return DeviceFaceProfileManifestVO.builder()
                .deviceId(device.getId())
                .macAddress(device.getMacAddress())
                .syncVersion(syncVersion)
                .profiles(items)
                .build();
    }

    @Override
    public FaceImage readFaceForDevice(String macAddress, String personId) {
        DeviceEntity device = requireBoundDevice(macAddress);
        PersonDeviceBindingEntity binding = bindingDao.selectOne(
                new LambdaQueryWrapper<PersonDeviceBindingEntity>()
                        .eq(PersonDeviceBindingEntity::getDeviceId, device.getId())
                        .eq(PersonDeviceBindingEntity::getPersonId, personId)
                        .eq(PersonDeviceBindingEntity::getWorkspaceId, device.getWorkspaceId())
                        .last("LIMIT 1"));
        if (binding == null) {
            throw new RenException("该人脸未授权下发到此设备");
        }
        PersonProfileEntity profile = profileDao.selectById(personId);
        if (profile == null || !Objects.equals(profile.getWorkspaceId(), device.getWorkspaceId())
                || !Objects.equals(profile.getStatus(), 1)) {
            throw new RenException("人脸资源不可用");
        }
        return readFace(profile);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, noRollbackFor = DuplicateKeyException.class)
    public DeviceFaceRecognitionActionVO recordRecognition(DeviceFaceRecognitionEventDTO dto) {
        DeviceEntity device = requireBoundDevice(dto.getMacAddress());
        PersonProfileEntity profile = profileDao.selectById(dto.getPersonId());
        if (profile == null || !Objects.equals(profile.getWorkspaceId(), device.getWorkspaceId())
                || !Objects.equals(profile.getStatus(), 1)) {
            throw new RenException("人物档案不可用");
        }
        long bindingCount = bindingDao.selectCount(new LambdaQueryWrapper<PersonDeviceBindingEntity>()
                .eq(PersonDeviceBindingEntity::getDeviceId, device.getId())
                .eq(PersonDeviceBindingEntity::getPersonId, profile.getId())
                .eq(PersonDeviceBindingEntity::getWorkspaceId, device.getWorkspaceId()));
        if (bindingCount != 1L) {
            throw new RenException("该设备未绑定此人物档案");
        }

        PersonRecognitionEventEntity existing = eventDao.selectOne(
                new LambdaQueryWrapper<PersonRecognitionEventEntity>()
                        .eq(PersonRecognitionEventEntity::getDeviceId, device.getId())
                        .eq(PersonRecognitionEventEntity::getDeviceEventId, dto.getEventId())
                        .last("LIMIT 1"));
        if (existing != null) {
            return buildAction(dto.getEventId(), profile, true, existing.getActionType());
        }

        String action = normalizeAction(profile.getRecognitionAction());
        PersonRecognitionEventEntity event = new PersonRecognitionEventEntity();
        event.setWorkspaceId(device.getWorkspaceId());
        event.setDeviceId(device.getId());
        event.setPersonId(profile.getId());
        event.setDeviceEventId(dto.getEventId());
        event.setConfidence(normalizeConfidence(dto.getConfidence()));
        event.setOccurredAt(dto.getOccurredAt());
        event.setActionType(action);
        try {
            eventDao.insert(event);
            return buildAction(dto.getEventId(), profile, false, action);
        } catch (DuplicateKeyException e) {
            return buildAction(dto.getEventId(), profile, true, action);
        }
    }

    private PersonProfileEntity requireProfile(String id, boolean write) {
        PersonProfileEntity entity = profileDao.selectById(id);
        if (entity == null) {
            throw new RenException("人物档案不存在");
        }
        if (write) {
            workspacePermissionService.requireWrite(entity.getWorkspaceId());
        } else {
            workspacePermissionService.requireRead(entity.getWorkspaceId());
        }
        return entity;
    }

    private DeviceEntity requireBoundDevice(String macAddress) {
        if (StringUtils.isBlank(macAddress)) {
            throw new RenException("设备标识不能为空");
        }
        DeviceEntity device = deviceDao.selectOne(new LambdaQueryWrapper<DeviceEntity>()
                .eq(DeviceEntity::getMacAddress, macAddress.trim())
                .last("LIMIT 1"));
        if (device == null || StringUtils.isBlank(device.getWorkspaceId()) || StringUtils.isBlank(device.getAgentId())) {
            throw new RenException("设备不存在或尚未绑定智能体");
        }
        return device;
    }

    private void applyEditableFields(PersonProfileEntity entity, PersonProfileSaveDTO dto) {
        String action = normalizeAction(dto.getRecognitionAction());
        String greeting = StringUtils.trimToNull(dto.getGreetingText());
        if (ACTION_SPEAK_TEXT.equals(action) && StringUtils.isBlank(greeting)) {
            throw new RenException("选择设备播报后必须填写播报内容");
        }
        entity.setName(dto.getName().trim());
        entity.setGender(StringUtils.defaultIfBlank(dto.getGender(), "UNKNOWN"));
        entity.setBirthday(dto.getBirthday());
        entity.setGroupName(StringUtils.trimToNull(dto.getGroupName()));
        entity.setDescription(StringUtils.trimToNull(dto.getDescription()));
        entity.setRecognitionAction(action);
        entity.setGreetingText(ACTION_SPEAK_TEXT.equals(action) ? greeting : null);
        entity.setStatus(Objects.equals(dto.getStatus(), 0) ? 0 : 1);
    }

    private void replaceDeviceBindings(PersonProfileEntity profile, List<String> requestedDeviceIds) {
        Set<String> deviceIds = requestedDeviceIds == null
                ? Set.of()
                : new LinkedHashSet<>(requestedDeviceIds.stream().filter(StringUtils::isNotBlank).toList());
        if (deviceIds.size() > 50) {
            throw new RenException("单个人物最多下发到50台设备");
        }
        Map<String, DeviceEntity> allowed = new LinkedHashMap<>();
        for (DeviceEntity device : workspaceDevices(profile.getWorkspaceId())) {
            allowed.put(device.getId(), device);
        }
        for (String deviceId : deviceIds) {
            if (!allowed.containsKey(deviceId)) {
                throw new RenException("包含不属于当前工作空间的设备");
            }
        }
        bindingDao.delete(new LambdaQueryWrapper<PersonDeviceBindingEntity>()
                .eq(PersonDeviceBindingEntity::getPersonId, profile.getId()));
        for (String deviceId : deviceIds) {
            PersonDeviceBindingEntity binding = new PersonDeviceBindingEntity();
            binding.setWorkspaceId(profile.getWorkspaceId());
            binding.setPersonId(profile.getId());
            binding.setDeviceId(deviceId);
            binding.setCreator(SecurityUser.getUserId());
            bindingDao.insert(binding);
        }
    }

    private List<PersonProfileVO> toVOs(List<PersonProfileEntity> profiles, String workspaceId) {
        if (profiles.isEmpty()) {
            return List.of();
        }
        Set<String> personIds = profiles.stream().map(PersonProfileEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<PersonDeviceBindingEntity> bindings = bindingDao.selectList(
                new LambdaQueryWrapper<PersonDeviceBindingEntity>()
                        .eq(PersonDeviceBindingEntity::getWorkspaceId, workspaceId)
                        .in(PersonDeviceBindingEntity::getPersonId, personIds));
        Map<String, DeviceEntity> devices = new LinkedHashMap<>();
        for (DeviceEntity device : workspaceDevices(workspaceId)) {
            devices.put(device.getId(), device);
        }
        Map<String, List<String>> idsByPerson = new LinkedHashMap<>();
        Map<String, List<String>> namesByPerson = new LinkedHashMap<>();
        for (PersonDeviceBindingEntity binding : bindings) {
            DeviceEntity device = devices.get(binding.getDeviceId());
            if (device == null) {
                continue;
            }
            idsByPerson.computeIfAbsent(binding.getPersonId(), ignored -> new ArrayList<>()).add(device.getId());
            namesByPerson.computeIfAbsent(binding.getPersonId(), ignored -> new ArrayList<>()).add(deviceName(device));
        }
        return profiles.stream().map(profile -> PersonProfileVO.builder()
                .id(profile.getId())
                .name(profile.getName())
                .gender(profile.getGender())
                .birthday(profile.getBirthday())
                .groupName(profile.getGroupName())
                .description(profile.getDescription())
                .faceReady(StringUtils.isNotBlank(profile.getFaceFilePath()))
                .faceVersion(Objects.requireNonNullElse(profile.getFaceVersion(), 0L))
                .faceSha256(profile.getFaceSha256())
                .recognitionAction(normalizeAction(profile.getRecognitionAction()))
                .greetingText(profile.getGreetingText())
                .status(profile.getStatus())
                .deviceIds(idsByPerson.getOrDefault(profile.getId(), List.of()))
                .deviceNames(namesByPerson.getOrDefault(profile.getId(), List.of()))
                .createDate(profile.getCreateDate())
                .updateDate(profile.getUpdateDate())
                .build()).toList();
    }

    private List<DeviceEntity> workspaceDevices(String workspaceId) {
        return deviceDao.selectList(new LambdaQueryWrapper<DeviceEntity>()
                .eq(DeviceEntity::getWorkspaceId, workspaceId)
                .orderByAsc(DeviceEntity::getSort)
                .orderByDesc(DeviceEntity::getUpdateDate));
    }

    private String deviceName(DeviceEntity device) {
        return StringUtils.defaultIfBlank(device.getAlias(), device.getMacAddress());
    }

    private FaceImage readFace(PersonProfileEntity profile) {
        byte[] bytes = faceStorage.read(profile.getFaceFilePath());
        return new FaceImage(new ByteArrayResource(bytes),
                StringUtils.defaultIfBlank(profile.getFaceContentType(), "image/jpeg"),
                profile.getFaceSha256(), Objects.requireNonNullElse(profile.getFaceVersion(), 0L));
    }

    private String normalizeAction(String action) {
        return ACTION_SPEAK_TEXT.equals(action) ? ACTION_SPEAK_TEXT : ACTION_NONE;
    }

    private BigDecimal normalizeConfidence(BigDecimal confidence) {
        if (confidence == null) {
            return null;
        }
        return confidence.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private DeviceFaceRecognitionActionVO buildAction(
            String eventId, PersonProfileEntity profile, boolean duplicate, String actionType) {
        String action = normalizeAction(actionType);
        return DeviceFaceRecognitionActionVO.builder()
                .eventId(eventId)
                .personId(profile.getId())
                .personName(profile.getName())
                .actionType(action)
                .text(ACTION_SPEAK_TEXT.equals(action) ? profile.getGreetingText() : null)
                .duplicate(duplicate)
                .build();
    }
}
