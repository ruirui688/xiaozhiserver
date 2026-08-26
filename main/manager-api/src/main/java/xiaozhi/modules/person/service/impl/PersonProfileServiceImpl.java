package xiaozhi.modules.person.service.impl;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.person.dao.PersonFaceAssetDao;
import xiaozhi.modules.person.dao.PersonProfileDao;
import xiaozhi.modules.person.dto.PersonProfileSaveDTO;
import xiaozhi.modules.person.entity.PersonAgentBindingEntity;
import xiaozhi.modules.person.entity.PersonFaceAssetEntity;
import xiaozhi.modules.person.entity.PersonProfileEntity;
import xiaozhi.modules.person.service.PersonFacePublicationService;
import xiaozhi.modules.person.service.PersonFaceStorage;
import xiaozhi.modules.person.service.PersonProfileService;
import xiaozhi.modules.person.vo.PersonAgentOptionVO;
import xiaozhi.modules.person.vo.PersonProfileVO;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@Service
@RequiredArgsConstructor
public class PersonProfileServiceImpl implements PersonProfileService {
    private final PersonProfileDao profileDao;
    private final PersonFaceAssetDao assetDao;
    private final PersonFaceStorage faceStorage;
    private final PersonFacePublicationService publicationService;
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
            query.and(wrapper -> wrapper.like(PersonProfileEntity::getName, normalizedKeyword)
                    .or().like(PersonProfileEntity::getGroupName, normalizedKeyword)
                    .or().like(PersonProfileEntity::getDescription, normalizedKeyword));
        }
        return toVOs(profileDao.selectList(query), workspaceId);
    }

    @Override
    public List<PersonAgentOptionVO> listWorkspaceAgents() {
        String workspaceId = workspacePermissionService.currentWorkspaceId();
        workspacePermissionService.requireRead(workspaceId);
        return publicationService.listWorkspaceAgents(workspaceId);
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
        publicationService.replaceDesiredBindings(entity, dto.getAgentIds(), dto.getDeviceIds());
        return toVOs(List.of(entity), workspaceId).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PersonProfileVO update(String id, PersonProfileSaveDTO dto) {
        PersonProfileEntity entity = requireProfile(id, true);
        applyEditableFields(entity, dto);
        profileDao.updateById(entity);
        publicationService.replaceDesiredBindings(entity, dto.getAgentIds(), dto.getDeviceIds());
        return toVOs(List.of(entity), entity.getWorkspaceId()).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        PersonProfileEntity entity = requireProfile(id, true);
        List<String> paths = assetDao.selectList(new LambdaQueryWrapper<PersonFaceAssetEntity>()
                .eq(PersonFaceAssetEntity::getPersonId, id)
                .eq(PersonFaceAssetEntity::getWorkspaceId, entity.getWorkspaceId())).stream()
                .map(PersonFaceAssetEntity::getFilePath).filter(StringUtils::isNotBlank).toList();
        publicationService.prepareDelete(entity);
        profileDao.deleteById(id);
        deleteFilesAfterCommit(paths);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PersonProfileVO uploadFace(String id, MultipartFile file) {
        PersonProfileEntity entity = requireProfile(id, true);
        PersonFaceStorage.StoredFace stored = faceStorage.store(entity.getWorkspaceId(), entity.getId(), file);
        deleteFileOnRollback(stored.relativePath());
        long version = Math.max(0L, Objects.requireNonNullElse(entity.getFaceVersion(), 0L)) + 1L;
        PersonFaceAssetEntity asset = new PersonFaceAssetEntity();
        asset.setWorkspaceId(entity.getWorkspaceId());
        asset.setPersonId(entity.getId());
        asset.setAssetVersion(version);
        asset.setFilePath(stored.relativePath());
        asset.setContentType(stored.contentType());
        asset.setSha256(stored.sha256());
        asset.setCreator(SecurityUser.getUserId());
        try {
            assetDao.insert(asset);
            entity.setCurrentFaceAssetId(asset.getId());
            // 兼容旧管理字段；运行时只读取不可变资源和已发布快照。
            entity.setFaceFilePath(asset.getFilePath());
            entity.setFaceContentType(asset.getContentType());
            entity.setFaceSha256(asset.getSha256());
            entity.setFaceVersion(version);
            entity.setUpdater(SecurityUser.getUserId());
            entity.setUpdateDate(new Date());
            if (profileDao.updateById(entity) != 1) {
                throw new RenException("保存人脸照片失败");
            }
        } catch (RuntimeException error) {
            faceStorage.deleteQuietly(stored.relativePath());
            throw error;
        }
        return toVOs(List.of(entity), entity.getWorkspaceId()).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFace(String id) {
        PersonProfileEntity entity = requireProfile(id, true);
        entity.setCurrentFaceAssetId(null);
        entity.setFaceFilePath(null);
        entity.setFaceContentType(null);
        entity.setFaceSha256(null);
        entity.setFaceVersion(Math.max(0L, Objects.requireNonNullElse(entity.getFaceVersion(), 0L)) + 1L);
        profileDao.updateById(entity);
        // 已发布智能体继续引用不可变旧版本，直到显式发布删除。
    }

    @Override
    public FaceImage readFaceForManagement(String id) {
        PersonProfileEntity entity = requireProfile(id, false);
        if (StringUtils.isBlank(entity.getCurrentFaceAssetId())) {
            throw new RenException("人物尚未上传人脸照片");
        }
        PersonFaceAssetEntity asset = assetDao.selectById(entity.getCurrentFaceAssetId());
        if (asset == null || !Objects.equals(asset.getPersonId(), entity.getId())
                || !Objects.equals(asset.getWorkspaceId(), entity.getWorkspaceId())) {
            throw new RenException("人物人脸资源不存在");
        }
        byte[] bytes = faceStorage.read(asset.getFilePath());
        return new FaceImage(new ByteArrayResource(bytes),
                StringUtils.defaultIfBlank(asset.getContentType(), "image/jpeg"), asset.getSha256(),
                Objects.requireNonNullElse(asset.getAssetVersion(), 0L));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PersonProfileVO publish(String id) {
        PersonProfileEntity entity = requireProfile(id, true);
        publicationService.publish(entity);
        return toVOs(List.of(entity), entity.getWorkspaceId()).get(0);
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

    private void applyEditableFields(PersonProfileEntity entity, PersonProfileSaveDTO dto) {
        String action = normalizeAction(dto.getRecognitionAction());
        String greeting = StringUtils.trimToNull(dto.getGreetingText());
        if ("SPEAK_TEXT".equals(action) && StringUtils.isBlank(greeting)) {
            throw new RenException("选择设备播报后必须填写播报内容");
        }
        entity.setName(dto.getName().trim());
        entity.setGender(StringUtils.defaultIfBlank(dto.getGender(), "UNKNOWN"));
        entity.setBirthday(dto.getBirthday());
        entity.setGroupName(StringUtils.trimToNull(dto.getGroupName()));
        entity.setDescription(StringUtils.trimToNull(dto.getDescription()));
        entity.setRecognitionAction(action);
        entity.setGreetingText("SPEAK_TEXT".equals(action) ? greeting : null);
        entity.setStatus(Objects.equals(dto.getStatus(), 0) ? 0 : 1);
    }

    private List<PersonProfileVO> toVOs(List<PersonProfileEntity> profiles, String workspaceId) {
        if (profiles.isEmpty()) {
            return List.of();
        }
        List<String> personIds = profiles.stream().map(PersonProfileEntity::getId).toList();
        Map<String, List<PersonAgentBindingEntity>> bindingsByPerson =
                publicationService.bindingsByPerson(personIds, workspaceId);
        Map<String, String> agentNames = publicationService.agentNames(workspaceId);
        return profiles.stream().map(profile -> {
            List<PersonAgentBindingEntity> bindings = bindingsByPerson.getOrDefault(profile.getId(), List.of());
            List<String> desiredIds = bindings.stream().filter(item -> Objects.equals(item.getDesiredActive(), 1))
                    .map(PersonAgentBindingEntity::getAgentId).toList();
            List<String> publishedIds = bindings.stream().filter(item -> Objects.equals(item.getPublishedActive(), 1))
                    .map(PersonAgentBindingEntity::getAgentId).toList();
            return PersonProfileVO.builder()
                    .id(profile.getId()).name(profile.getName()).gender(profile.getGender())
                    .birthday(profile.getBirthday()).groupName(profile.getGroupName()).description(profile.getDescription())
                    .faceReady(StringUtils.isNotBlank(profile.getCurrentFaceAssetId()))
                    .faceVersion(Objects.requireNonNullElse(profile.getFaceVersion(), 0L))
                    .faceSha256(profile.getFaceSha256())
                    .recognitionAction(normalizeAction(profile.getRecognitionAction()))
                    .greetingText(profile.getGreetingText()).status(profile.getStatus())
                    .deviceIds(List.of()).deviceNames(List.of())
                    .agentIds(desiredIds)
                    .agentNames(desiredIds.stream().map(id -> agentNames.getOrDefault(id, id)).toList())
                    .publishedAgentIds(publishedIds)
                    .unpublishedChanges(publicationService.hasUnpublishedChanges(profile, bindings))
                    .createDate(profile.getCreateDate()).updateDate(profile.getUpdateDate()).build();
        }).toList();
    }

    private String normalizeAction(String action) {
        return "SPEAK_TEXT".equals(action) ? "SPEAK_TEXT" : "NONE";
    }

    private void deleteFileOnRollback(String path) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    faceStorage.deleteQuietly(path);
                }
            }
        });
    }

    private void deleteFilesAfterCommit(List<String> paths) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            paths.forEach(faceStorage::deleteQuietly);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                paths.forEach(faceStorage::deleteQuietly);
            }
        });
    }
}
