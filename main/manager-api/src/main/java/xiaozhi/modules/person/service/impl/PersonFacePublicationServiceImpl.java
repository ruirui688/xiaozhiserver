package xiaozhi.modules.person.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.person.dao.AgentFaceReleaseDao;
import xiaozhi.modules.person.dao.PersonAgentBindingDao;
import xiaozhi.modules.person.entity.AgentFaceReleaseEntity;
import xiaozhi.modules.person.entity.PersonAgentBindingEntity;
import xiaozhi.modules.person.entity.PersonProfileEntity;
import xiaozhi.modules.person.runtime.AgentFaceReleaseEvent;
import xiaozhi.modules.person.service.PersonFacePublicationService;
import xiaozhi.modules.person.vo.PersonAgentOptionVO;
import xiaozhi.modules.security.user.SecurityUser;

@Service
@RequiredArgsConstructor
public class PersonFacePublicationServiceImpl implements PersonFacePublicationService {
    private final PersonAgentBindingDao bindingDao;
    private final AgentFaceReleaseDao releaseDao;
    private final AgentDao agentDao;
    private final DeviceDao deviceDao;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public List<PersonAgentOptionVO> listWorkspaceAgents(String workspaceId) {
        List<AgentEntity> agents = agentDao.selectList(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getWorkspaceId, workspaceId)
                .orderByAsc(AgentEntity::getSort)
                .orderByDesc(AgentEntity::getUpdatedAt));
        Map<String, Long> deviceCounts = new LinkedHashMap<>();
        for (DeviceEntity device : deviceDao.selectList(new LambdaQueryWrapper<DeviceEntity>()
                .eq(DeviceEntity::getWorkspaceId, workspaceId))) {
            if (StringUtils.isNotBlank(device.getAgentId())) {
                deviceCounts.merge(device.getAgentId(), 1L, Long::sum);
            }
        }
        return agents.stream().map(agent -> PersonAgentOptionVO.builder()
                .id(agent.getId())
                .name(agent.getAgentName())
                .deviceCount(deviceCounts.getOrDefault(agent.getId(), 0L))
                .build()).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceDesiredBindings(
            PersonProfileEntity profile, List<String> requestedAgentIds, List<String> legacyDeviceIds) {
        Set<String> agentIds = normalizeAgentIds(profile.getWorkspaceId(), requestedAgentIds, legacyDeviceIds);
        List<PersonAgentBindingEntity> existing = bindingDao.selectList(
                new LambdaQueryWrapper<PersonAgentBindingEntity>()
                        .eq(PersonAgentBindingEntity::getPersonId, profile.getId())
                        .eq(PersonAgentBindingEntity::getWorkspaceId, profile.getWorkspaceId()));
        Map<String, PersonAgentBindingEntity> byAgent = new LinkedHashMap<>();
        existing.forEach(binding -> byAgent.put(binding.getAgentId(), binding));

        for (PersonAgentBindingEntity binding : existing) {
            int desired = agentIds.contains(binding.getAgentId()) ? 1 : 0;
            if (!Objects.equals(binding.getDesiredActive(), desired)) {
                binding.setDesiredActive(desired);
                bindingDao.updateById(binding);
            }
        }
        for (String agentId : agentIds) {
            if (byAgent.containsKey(agentId)) {
                continue;
            }
            PersonAgentBindingEntity binding = new PersonAgentBindingEntity();
            binding.setWorkspaceId(profile.getWorkspaceId());
            binding.setPersonId(profile.getId());
            binding.setAgentId(agentId);
            binding.setDesiredActive(1);
            binding.setPublishedActive(0);
            binding.setPublishedRecognitionAction("NONE");
            bindingDao.insert(binding);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<String> publish(PersonProfileEntity profile) {
        List<PersonAgentBindingEntity> bindings = bindingDao.selectList(
                new LambdaQueryWrapper<PersonAgentBindingEntity>()
                        .eq(PersonAgentBindingEntity::getPersonId, profile.getId())
                        .eq(PersonAgentBindingEntity::getWorkspaceId, profile.getWorkspaceId()));
        Set<String> affected = new LinkedHashSet<>();
        for (PersonAgentBindingEntity binding : bindings) {
            if (!hasUnpublishedChanges(profile, List.of(binding))) {
                continue;
            }
            affected.add(binding.getAgentId());
            boolean publishActive = Objects.equals(binding.getDesiredActive(), 1)
                    && Objects.equals(profile.getStatus(), 1)
                    && StringUtils.isNotBlank(profile.getCurrentFaceAssetId());
            binding.setPublishedActive(publishActive ? 1 : 0);
            binding.setPublishedPersonName(publishActive ? profile.getName() : null);
            binding.setPublishedGroupName(publishActive ? profile.getGroupName() : null);
            binding.setPublishedFaceAssetId(publishActive ? profile.getCurrentFaceAssetId() : null);
            binding.setPublishedRecognitionAction(publishActive
                    ? normalizeAction(profile.getRecognitionAction()) : "NONE");
            binding.setPublishedGreetingText(publishActive
                    && "SPEAK_TEXT".equals(normalizeAction(profile.getRecognitionAction()))
                            ? publishedGreeting(profile) : null);
            bindingDao.updateById(binding);
        }
        bumpReleases(profile.getWorkspaceId(), affected);
        if (!affected.isEmpty()) {
            eventPublisher.publishEvent(new AgentFaceReleaseEvent(new ArrayList<>(affected)));
        }
        return new ArrayList<>(affected);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<String> prepareDelete(PersonProfileEntity profile) {
        Set<String> affected = bindingDao.selectList(new LambdaQueryWrapper<PersonAgentBindingEntity>()
                .eq(PersonAgentBindingEntity::getPersonId, profile.getId())
                .eq(PersonAgentBindingEntity::getPublishedActive, 1)).stream()
                .map(PersonAgentBindingEntity::getAgentId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        bumpReleases(profile.getWorkspaceId(), affected);
        if (!affected.isEmpty()) {
            eventPublisher.publishEvent(new AgentFaceReleaseEvent(new ArrayList<>(affected)));
        }
        return new ArrayList<>(affected);
    }

    @Override
    public Map<String, List<PersonAgentBindingEntity>> bindingsByPerson(
            List<String> personIds, String workspaceId) {
        Map<String, List<PersonAgentBindingEntity>> result = new LinkedHashMap<>();
        if (personIds.isEmpty()) {
            return result;
        }
        for (PersonAgentBindingEntity binding : bindingDao.selectList(
                new LambdaQueryWrapper<PersonAgentBindingEntity>()
                        .eq(PersonAgentBindingEntity::getWorkspaceId, workspaceId)
                        .in(PersonAgentBindingEntity::getPersonId, personIds))) {
            result.computeIfAbsent(binding.getPersonId(), ignored -> new ArrayList<>()).add(binding);
        }
        return result;
    }

    @Override
    public Map<String, String> agentNames(String workspaceId) {
        Map<String, String> result = new LinkedHashMap<>();
        for (AgentEntity agent : agentDao.selectList(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getWorkspaceId, workspaceId))) {
            result.put(agent.getId(), agent.getAgentName());
        }
        return result;
    }

    @Override
    public boolean hasUnpublishedChanges(
            PersonProfileEntity profile, List<PersonAgentBindingEntity> bindings) {
        for (PersonAgentBindingEntity binding : bindings) {
            boolean desired = Objects.equals(binding.getDesiredActive(), 1)
                    && Objects.equals(profile.getStatus(), 1)
                    && StringUtils.isNotBlank(profile.getCurrentFaceAssetId());
            boolean published = Objects.equals(binding.getPublishedActive(), 1);
            if (desired != published) {
                return true;
            }
            if (desired && (!Objects.equals(binding.getPublishedFaceAssetId(), profile.getCurrentFaceAssetId())
                    || !Objects.equals(binding.getPublishedPersonName(), profile.getName())
                    || !Objects.equals(binding.getPublishedGroupName(), profile.getGroupName())
                    || !Objects.equals(binding.getPublishedRecognitionAction(), normalizeAction(profile.getRecognitionAction()))
                    || !Objects.equals(binding.getPublishedGreetingText(), publishedGreeting(profile)))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> normalizeAgentIds(
            String workspaceId, List<String> requestedAgentIds, List<String> legacyDeviceIds) {
        Set<String> requested = new LinkedHashSet<>();
        if (requestedAgentIds != null) {
            requestedAgentIds.stream().filter(StringUtils::isNotBlank).forEach(requested::add);
        } else if (legacyDeviceIds != null) {
            Set<String> deviceIds = legacyDeviceIds.stream().filter(StringUtils::isNotBlank)
                    .collect(java.util.stream.Collectors.toSet());
            if (!deviceIds.isEmpty()) {
                deviceDao.selectList(new LambdaQueryWrapper<DeviceEntity>()
                        .eq(DeviceEntity::getWorkspaceId, workspaceId)
                        .in(DeviceEntity::getId, deviceIds)).stream()
                        .map(DeviceEntity::getAgentId).filter(StringUtils::isNotBlank).forEach(requested::add);
            }
        }
        if (requested.size() > 50) {
            throw new RenException("单个人物最多绑定50个智能体");
        }
        if (requested.isEmpty()) {
            return requested;
        }
        Set<String> allowed = agentDao.selectList(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getWorkspaceId, workspaceId)
                .in(AgentEntity::getId, requested)).stream()
                .map(AgentEntity::getId).collect(java.util.stream.Collectors.toSet());
        if (!allowed.containsAll(requested)) {
            throw new RenException("包含不属于当前工作空间的智能体");
        }
        return requested;
    }

    private void bumpReleases(String workspaceId, Set<String> agentIds) {
        for (String agentId : agentIds) {
            AgentFaceReleaseEntity release = releaseDao.selectOne(
                    new LambdaQueryWrapper<AgentFaceReleaseEntity>()
                            .eq(AgentFaceReleaseEntity::getAgentId, agentId)
                            .last("LIMIT 1 FOR UPDATE"));
            if (release == null) {
                release = new AgentFaceReleaseEntity();
                release.setWorkspaceId(workspaceId);
                release.setAgentId(agentId);
                release.setReleaseVersion(1L);
                release.setPublishDate(new Date());
                releaseDao.insert(release);
            } else {
                release.setReleaseVersion(Math.max(0L, Objects.requireNonNullElse(release.getReleaseVersion(), 0L)) + 1L);
                release.setPublishDate(new Date());
                release.setUpdater(SecurityUser.getUserId());
                releaseDao.updateById(release);
            }
        }
    }

    private String normalizeAction(String action) {
        return "SPEAK_TEXT".equals(action) ? "SPEAK_TEXT" : "NONE";
    }

    private String publishedGreeting(PersonProfileEntity profile) {
        return "SPEAK_TEXT".equals(normalizeAction(profile.getRecognitionAction()))
                ? StringUtils.trimToNull(profile.getGreetingText()) : null;
    }
}
