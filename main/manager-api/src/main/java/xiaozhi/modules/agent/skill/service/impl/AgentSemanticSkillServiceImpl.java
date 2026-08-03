package xiaozhi.modules.agent.skill.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.skill.dao.AgentSemanticSkillDao;
import xiaozhi.modules.agent.runtime.AgentRuntimeConfigRefreshEvent;
import xiaozhi.modules.agent.skill.dto.AgentSemanticSkillOrderDTO;
import xiaozhi.modules.agent.skill.dto.AgentSemanticSkillSaveDTO;
import xiaozhi.modules.agent.skill.entity.AgentSemanticSkillEntity;
import xiaozhi.modules.agent.skill.service.AgentSemanticSkillService;
import xiaozhi.modules.agent.skill.service.SemanticSkillRuleNormalizer;
import xiaozhi.modules.agent.skill.vo.AgentSemanticSkillVO;

@Service
@RequiredArgsConstructor
public class AgentSemanticSkillServiceImpl implements AgentSemanticSkillService {
    private static final int MAX_SKILLS_PER_AGENT = 50;

    private final AgentSemanticSkillDao skillDao;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public List<AgentSemanticSkillVO> list(String agentId) {
        return select(agentId, false).stream().map(this::toVO).toList();
    }

    @Override
    public List<AgentSemanticSkillVO> listEnabled(String agentId) {
        return select(agentId, true).stream().map(this::toVO).toList();
    }

    private List<AgentSemanticSkillEntity> select(String agentId, boolean enabledOnly) {
        LambdaQueryWrapper<AgentSemanticSkillEntity> query = new LambdaQueryWrapper<AgentSemanticSkillEntity>()
                .eq(AgentSemanticSkillEntity::getAgentId, agentId);
        if (enabledOnly) {
            query.eq(AgentSemanticSkillEntity::getEnabled, true)
                    .orderByDesc(AgentSemanticSkillEntity::getPriority);
        }
        query.orderByAsc(AgentSemanticSkillEntity::getSort)
                .orderByAsc(AgentSemanticSkillEntity::getId);
        return skillDao.selectList(query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentSemanticSkillVO create(String agentId, AgentSemanticSkillSaveDTO dto, Long operatorId) {
        Long count = skillDao.selectCount(new LambdaQueryWrapper<AgentSemanticSkillEntity>()
                .eq(AgentSemanticSkillEntity::getAgentId, agentId));
        if (count != null && count >= MAX_SKILLS_PER_AGENT) {
            throw new RenException(ErrorCode.PARAMS_GET_ERROR);
        }

        Date now = new Date();
        AgentSemanticSkillEntity entity = new AgentSemanticSkillEntity();
        entity.setId(UUID.randomUUID().toString().replace("-", ""));
        entity.setAgentId(agentId);
        apply(dto, entity);
        entity.setCreator(operatorId);
        entity.setCreatedAt(now);
        entity.setUpdater(operatorId);
        entity.setUpdatedAt(now);
        skillDao.insert(entity);
        publishRefresh(agentId, "semantic_skill_created");
        return toVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentSemanticSkillVO update(
            String agentId,
            String skillId,
            AgentSemanticSkillSaveDTO dto,
            Long operatorId) {
        AgentSemanticSkillEntity entity = requireOwned(agentId, skillId);
        apply(dto, entity);
        entity.setUpdater(operatorId);
        entity.setUpdatedAt(new Date());
        skillDao.updateById(entity);
        publishRefresh(agentId, "semantic_skill_updated");
        return toVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String agentId, String skillId) {
        int deleted = skillDao.delete(new LambdaQueryWrapper<AgentSemanticSkillEntity>()
                .eq(AgentSemanticSkillEntity::getAgentId, agentId)
                .eq(AgentSemanticSkillEntity::getId, skillId));
        if (deleted == 0) {
            throw new RenException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        publishRefresh(agentId, "semantic_skill_deleted");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateOrder(String agentId, AgentSemanticSkillOrderDTO dto, Long operatorId) {
        if (dto == null || dto.getItems() == null || dto.getItems().isEmpty()
                || dto.getItems().size() > MAX_SKILLS_PER_AGENT) {
            throw new RenException("技能排序参数无效");
        }
        Set<String> ids = new HashSet<>();
        for (AgentSemanticSkillOrderDTO.OrderItem item : dto.getItems()) {
            if (item == null || StringUtils.isBlank(item.getId()) || item.getSort() == null
                    || !ids.add(item.getId())) {
                throw new RenException(ErrorCode.PARAMS_GET_ERROR);
            }
        }

        List<AgentSemanticSkillEntity> entities = skillDao.selectList(
                new LambdaQueryWrapper<AgentSemanticSkillEntity>()
                        .eq(AgentSemanticSkillEntity::getAgentId, agentId)
                        .in(AgentSemanticSkillEntity::getId, ids));
        if (entities.size() != ids.size()) {
            throw new RenException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        var sortById = dto.getItems().stream().collect(
                java.util.stream.Collectors.toMap(AgentSemanticSkillOrderDTO.OrderItem::getId,
                        AgentSemanticSkillOrderDTO.OrderItem::getSort));
        Date now = new Date();
        for (AgentSemanticSkillEntity entity : entities) {
            entity.setSort(sortById.get(entity.getId()));
            entity.setUpdater(operatorId);
            entity.setUpdatedAt(now);
            skillDao.updateById(entity);
        }
        publishRefresh(agentId, "semantic_skill_reordered");
    }

    @Override
    public void deleteByAgentId(String agentId) {
        skillDao.delete(new LambdaQueryWrapper<AgentSemanticSkillEntity>()
                .eq(AgentSemanticSkillEntity::getAgentId, agentId));
    }

    @Override
    public void deleteByAgentIds(List<String> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return;
        }
        skillDao.delete(new LambdaQueryWrapper<AgentSemanticSkillEntity>()
                .in(AgentSemanticSkillEntity::getAgentId, agentIds));
    }

    private AgentSemanticSkillEntity requireOwned(String agentId, String skillId) {
        AgentSemanticSkillEntity entity = skillDao.selectOne(new LambdaQueryWrapper<AgentSemanticSkillEntity>()
                .eq(AgentSemanticSkillEntity::getAgentId, agentId)
                .eq(AgentSemanticSkillEntity::getId, skillId));
        if (entity == null) {
            throw new RenException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return entity;
    }

    private void apply(AgentSemanticSkillSaveDTO dto, AgentSemanticSkillEntity entity) {
        String name = StringUtils.trim(dto.getSkillName());
        String response = StringUtils.trim(dto.getResponseText());
        if (StringUtils.isBlank(name) || name.length() > 64
                || StringUtils.isBlank(response) || response.length() > 3000) {
            throw new RenException(ErrorCode.PARAMS_GET_ERROR);
        }
        entity.setSkillName(name);
        entity.setEnabled(!Boolean.FALSE.equals(dto.getEnabled()));
        entity.setTriggerRules(SemanticSkillRuleNormalizer.normalize(dto.getTriggerRules()));
        entity.setResponseText(response);
        entity.setPriority(bounded(dto.getPriority()));
        entity.setSort(bounded(dto.getSort()));
    }

    private int bounded(Integer value) {
        int resolved = value == null ? 0 : value;
        if (resolved < 0 || resolved > 1_000_000) {
            throw new RenException("技能优先级或排序参数无效");
        }
        return resolved;
    }

    private AgentSemanticSkillVO toVO(AgentSemanticSkillEntity entity) {
        AgentSemanticSkillVO vo = new AgentSemanticSkillVO();
        vo.setId(entity.getId());
        vo.setSkillName(entity.getSkillName());
        vo.setEnabled(entity.getEnabled());
        vo.setTriggerRules(entity.getTriggerRules() == null ? new ArrayList<>() : entity.getTriggerRules());
        vo.setResponseText(entity.getResponseText());
        vo.setPriority(entity.getPriority());
        vo.setSort(entity.getSort());
        return vo;
    }

    private void publishRefresh(String agentId, String reason) {
        eventPublisher.publishEvent(AgentRuntimeConfigRefreshEvent.forAgent(agentId, reason));
    }
}
