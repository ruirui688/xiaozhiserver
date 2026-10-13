package xiaozhi.modules.person.service.impl;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentVoicePrintDao;
import xiaozhi.modules.agent.entity.AgentVoicePrintEntity;
import xiaozhi.modules.agent.vo.AgentVoicePrintVO;
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
    private final AgentVoicePrintDao voicePrintDao;
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
    public List<AgentVoicePrintVO> listVoicePrintOptions(String id) {
        PersonProfileEntity profile = requireProfile(id, true);
        return voicePrintDao.selectPersonOptions(profile.getWorkspaceId(), profile.getId()).stream()
                .map(this::toVoicePrintOption)
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
    public PersonProfileVO bindVoicePrint(String id, String voicePrintId) {
        PersonProfileEntity profile = requireProfile(id, true);
        AgentVoicePrintEntity voicePrint = requireVoicePrint(voicePrintId, profile.getWorkspaceId());
        AgentVoicePrintEntity existing = findVoicePrintForPerson(profile.getId(), profile.getWorkspaceId());
        if (existing != null && !Objects.equals(existing.getId(), voicePrint.getId())) {
            throw new RenException("人物已关联其他声纹，请先解除现有关联");
        }
        if (Objects.equals(voicePrint.getPersonId(), profile.getId())) {
            return toVOs(List.of(profile), profile.getWorkspaceId()).get(0);
        }
        if (StringUtils.isNotBlank(voicePrint.getPersonId())) {
            throw new RenException("声纹已关联其他人物，请先解除现有关联");
        }

        AgentVoicePrintEntity responseVoicePrint = voicePrint;
        try {
            int updated = voicePrintDao.bindPerson(
                    voicePrint.getId(), profile.getWorkspaceId(), profile.getId(), SecurityUser.getUserId());
            if (updated != 1) {
                // MySQL REPEATABLE READ 下普通 SELECT 可能仍看到事务开始时的旧快照；
                // locking read 必须读取条件 UPDATE 之后的当前状态，才能可靠判断并发幂等。
                AgentVoicePrintEntity latest = voicePrintDao.selectByIdForUpdate(
                        voicePrint.getId(), profile.getWorkspaceId());
                if (latest == null || !Objects.equals(latest.getPersonId(), profile.getId())) {
                    throw new RenException("声纹关联状态已变化，请刷新后重试");
                }
                responseVoicePrint = latest;
            }
        } catch (DuplicateKeyException error) {
            throw new RenException("人物已关联其他声纹，请先解除现有关联", error);
        }
        responseVoicePrint.setPersonId(profile.getId());
        PersonProfileVO result = toVOs(List.of(profile), profile.getWorkspaceId()).get(0);
        // 条件写入后的权威关联已知，覆盖 RR 快照组装出的旧声纹摘要。
        applyVoicePrintSummary(result, responseVoicePrint);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PersonProfileVO unbindVoicePrint(String id) {
        PersonProfileEntity profile = requireProfile(id, true);
        AgentVoicePrintEntity voicePrint = findVoicePrintForPerson(profile.getId(), profile.getWorkspaceId());
        if (voicePrint == null) {
            return toVOs(List.of(profile), profile.getWorkspaceId()).get(0);
        }
        int updated = voicePrintDao.unbindPerson(
                voicePrint.getId(), profile.getWorkspaceId(), profile.getId(), SecurityUser.getUserId());
        if (updated != 1) {
            // 按人物做 current read，避免旧声纹刚解绑、另一声纹又并发绑定时误判为成功。
            AgentVoicePrintEntity latest = voicePrintDao.selectByPersonForUpdate(
                    profile.getWorkspaceId(), profile.getId());
            if (latest != null) {
                throw new RenException("声纹关联状态已变化，请刷新后重试");
            }
        }
        voicePrint.setPersonId(null);
        PersonProfileVO result = toVOs(List.of(profile), profile.getWorkspaceId()).get(0);
        // 即使普通摘要查询仍命中旧快照，解绑响应也必须反映当前写入结果。
        applyVoicePrintSummary(result, null);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        PersonProfileEntity entity = requireProfile(id, true);
        if (findVoicePrintForPerson(entity.getId(), entity.getWorkspaceId()) != null) {
            throw new RenException("人物已关联声纹，请先解除或删除声纹后再删除人物");
        }
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
        Map<String, AgentVoicePrintEntity> voicePrintsByPerson = voicePrintsByPerson(personIds, workspaceId);
        return profiles.stream().map(profile -> {
            List<PersonAgentBindingEntity> bindings = bindingsByPerson.getOrDefault(profile.getId(), List.of());
            AgentVoicePrintEntity voicePrint = voicePrintsByPerson.get(profile.getId());
            List<String> desiredIds = bindings.stream().filter(item -> Objects.equals(item.getDesiredActive(), 1))
                    .map(PersonAgentBindingEntity::getAgentId).toList();
            List<String> publishedIds = bindings.stream().filter(item -> Objects.equals(item.getPublishedActive(), 1))
                    .map(PersonAgentBindingEntity::getAgentId).toList();
            return PersonProfileVO.builder()
                    .id(profile.getId()).name(profile.getName()).gender(profile.getGender())
                    .birthday(profile.getBirthday()).groupName(profile.getGroupName()).description(profile.getDescription())
                    .voicePrintReady(voicePrint != null)
                    .voicePrintId(voicePrint == null ? null : voicePrint.getId())
                    .voicePrintAgentId(voicePrint == null ? null : voicePrint.getAgentId())
                    .voicePrintSourceName(voicePrint == null ? null : voicePrint.getSourceName())
                    .voicePrintDescription(voicePrint == null ? null : voicePrint.getIntroduce())
                    .voicePrintCreateDate(voicePrint == null ? null : voicePrint.getCreateDate())
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

    private Map<String, AgentVoicePrintEntity> voicePrintsByPerson(List<String> personIds, String workspaceId) {
        Map<String, AgentVoicePrintEntity> result = new LinkedHashMap<>();
        if (personIds.isEmpty()) {
            return result;
        }
        for (AgentVoicePrintEntity voicePrint : voicePrintDao.selectList(
                new LambdaQueryWrapper<AgentVoicePrintEntity>()
                        .eq(AgentVoicePrintEntity::getWorkspaceId, workspaceId)
                        .in(AgentVoicePrintEntity::getPersonId, personIds))) {
            if (StringUtils.isNotBlank(voicePrint.getPersonId())) {
                result.put(voicePrint.getPersonId(), voicePrint);
            }
        }
        return result;
    }

    private AgentVoicePrintEntity findVoicePrintForPerson(String personId, String workspaceId) {
        return voicePrintDao.selectOne(new LambdaQueryWrapper<AgentVoicePrintEntity>()
                .eq(AgentVoicePrintEntity::getWorkspaceId, workspaceId)
                .eq(AgentVoicePrintEntity::getPersonId, personId)
                .last("LIMIT 1"));
    }

    private AgentVoicePrintEntity requireVoicePrint(String voicePrintId, String workspaceId) {
        AgentVoicePrintEntity voicePrint = StringUtils.isBlank(voicePrintId)
                ? null : voicePrintDao.selectById(voicePrintId);
        if (voicePrint == null || !Objects.equals(voicePrint.getWorkspaceId(), workspaceId)) {
            throw new RenException("声纹不存在或不属于当前工作空间");
        }
        return voicePrint;
    }

    private AgentVoicePrintVO toVoicePrintOption(AgentVoicePrintEntity entity) {
        AgentVoicePrintVO option = new AgentVoicePrintVO();
        option.setId(entity.getId());
        option.setPersonId(entity.getPersonId());
        option.setAgentId(entity.getAgentId());
        option.setAudioId(entity.getAudioId());
        option.setSourceName(entity.getSourceName());
        option.setIntroduce(entity.getIntroduce());
        option.setCreateDate(entity.getCreateDate());
        return option;
    }

    private void applyVoicePrintSummary(PersonProfileVO profile, AgentVoicePrintEntity voicePrint) {
        profile.setVoicePrintReady(voicePrint != null);
        profile.setVoicePrintId(voicePrint == null ? null : voicePrint.getId());
        profile.setVoicePrintAgentId(voicePrint == null ? null : voicePrint.getAgentId());
        profile.setVoicePrintSourceName(voicePrint == null ? null : voicePrint.getSourceName());
        profile.setVoicePrintDescription(voicePrint == null ? null : voicePrint.getIntroduce());
        profile.setVoicePrintCreateDate(voicePrint == null ? null : voicePrint.getCreateDate());
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
