package xiaozhi.modules.memory.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import cn.hutool.json.JSONObject;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.llm.service.LLMService;
import xiaozhi.modules.memory.dao.MemoryFactDao;
import xiaozhi.modules.memory.dao.MemoryForgetTombstoneDao;
import xiaozhi.modules.memory.dao.MemoryProfileDao;
import xiaozhi.modules.memory.dao.MemoryToolBehaviorDao;
import xiaozhi.modules.memory.dto.MemoryQueryDTO;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.entity.MemoryForgetTombstoneEntity;
import xiaozhi.modules.memory.entity.MemoryProfileEntity;
import xiaozhi.modules.memory.entity.MemoryToolBehaviorEntity;
import xiaozhi.modules.memory.service.MemoryQueryService;
import xiaozhi.modules.memory.utils.MemoryHelper;
import xiaozhi.modules.memory.utils.MemorySimilarityUtils;
import xiaozhi.modules.memory.vo.MemoryQueryVO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.impl.ModelRuntimeAssembler;

@Service
@AllArgsConstructor
@Slf4j
public class MemoryQueryServiceImpl implements MemoryQueryService {

    private final MemoryFactDao factDao;
    private final MemoryProfileDao profileDao;
    private final MemoryToolBehaviorDao toolBehaviorDao;
    private final MemoryForgetTombstoneDao tombstoneDao;
    private final DeviceDao deviceDao;
    private final AgentDao agentDao;
    private final LLMService llmService;
    private final ModelConfigService modelConfigService;
    private final ModelRuntimeAssembler modelRuntimeAssembler;

    @Override
    public MemoryQueryVO query(MemoryQueryDTO dto) {
        AgentScope scope = resolveScope(dto.getAgentId(), dto.getMacAddress());
        MemoryQueryVO vo = new MemoryQueryVO();
        if (scope.agent == null) {
            vo.setContext("");
            return vo;
        }
        int limit = dto.getLimit() == null ? 8 : Math.max(1, Math.min(dto.getLimit(), 20));
        Date now = new Date();

        // 用户画像
        QueryWrapper<MemoryProfileEntity> profileWrapper = new QueryWrapper<>();
        profileWrapper.eq("agent_id", scope.agent.getId()).eq("scope_type", MemoryHelper.SCOPE_DEVICE)
                .and(w -> w.eq("scope_id", scope.scopeId).or().eq("scope_id", scope.agent.getId()))
                .orderByDesc("updated_at")
                .last("LIMIT 1");
        MemoryProfileEntity profile = profileDao.selectOne(profileWrapper);
        if (profile != null) {
            vo.setProfile(StringUtils.defaultIfBlank(profile.getProfileSummary(), profile.getProfileJson()));
        }

        String keyword = MemoryHelper.compactQuery(dto.getQuery());
        List<MemoryFactEntity> facts;

        // 尝试向量语义搜索（如果有查询且有嵌入配置）
        float[] queryEmbedding = null;
        if (StringUtils.isNotBlank(keyword)) {
            String memModelId = getMaintenanceLlmModelId(scope.agent);
            if (StringUtils.isNotBlank(memModelId)) {
                queryEmbedding = generateEmbedding(dto.getQuery(), memModelId);
            }
        }

        if (queryEmbedding != null) {
            // 向量语义搜索：检索所有活跃事实，按相似度重新排序
            List<MemoryFactEntity> allActive = factDao.selectList(new QueryWrapper<MemoryFactEntity>()
                    .eq("agent_id", scope.agent.getId())
                    .eq("scope_type", MemoryHelper.SCOPE_DEVICE)
                    .and(w -> w.eq("scope_id", scope.scopeId).or().eq("scope_id", scope.agent.getId()))
                    .eq("status", MemoryHelper.STATUS_ACTIVE)
                    .and(w -> w.isNull("expires_at").or().gt("expires_at", now))
                    .orderByDesc("importance", "last_seen_at")
                    .last("LIMIT 200"));
            List<Map.Entry<MemoryFactEntity, Double>> scored = new ArrayList<>();
            for (MemoryFactEntity fact : allActive) {
                float[] factEmb = MemorySimilarityUtils.parseEmbedding(fact.getEmbeddingJson());
                if (factEmb != null) {
                    double sim = MemorySimilarityUtils.cosineSimilarity(queryEmbedding, factEmb);
                    double combined = 0.6 * sim + 0.4 * (fact.getImportance() != null
                            ? fact.getImportance().doubleValue() : 0.5);
                    scored.add(Map.entry(fact, combined));
                } else {
                    // 无嵌入的事实使用关键词回退
                    if (StringUtils.containsIgnoreCase(fact.getCanonicalText(), keyword)
                            || StringUtils.containsIgnoreCase(fact.getCategory(), keyword)) {
                        scored.add(Map.entry(fact, 0.3 + (fact.getImportance() != null
                                ? fact.getImportance().doubleValue() : 0.5) * 0.4));
                    }
                }
            }
            scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            facts = new ArrayList<>();
            for (int i = 0; i < Math.min(limit, scored.size()); i++) {
                facts.add(scored.get(i).getKey());
            }
        } else {
            // 关键词 LIKE 搜索（原有逻辑）
            QueryWrapper<MemoryFactEntity> factWrapper = new QueryWrapper<>();
            factWrapper.eq("agent_id", scope.agent.getId())
                    .eq("scope_type", MemoryHelper.SCOPE_DEVICE)
                    .and(w -> w.eq("scope_id", scope.scopeId).or().eq("scope_id", scope.agent.getId()))
                    .eq("status", MemoryHelper.STATUS_ACTIVE)
                    .and(w -> w.isNull("expires_at").or().gt("expires_at", now));
            if (StringUtils.isNotBlank(keyword)) {
                factWrapper.and(w -> w.like("canonical_text", keyword).or().like("category", keyword));
            }
            factWrapper.orderByDesc("importance", "last_seen_at").last("LIMIT " + limit);
            facts = factDao.selectList(factWrapper);
            if (facts.isEmpty() && StringUtils.isNotBlank(keyword)) {
                QueryWrapper<MemoryFactEntity> fallback = new QueryWrapper<>();
                fallback.eq("agent_id", scope.agent.getId())
                        .eq("scope_type", MemoryHelper.SCOPE_DEVICE)
                        .and(w -> w.eq("scope_id", scope.scopeId).or().eq("scope_id", scope.agent.getId()))
                        .eq("status", MemoryHelper.STATUS_ACTIVE)
                        .and(w -> w.isNull("expires_at").or().gt("expires_at", now))
                        .orderByDesc("importance", "last_seen_at")
                        .last("LIMIT " + limit);
                facts = factDao.selectList(fallback);
            }
        }
        vo.setFacts(facts);
        refreshFactAccess(facts, now);

        // 工具行为
        QueryWrapper<MemoryToolBehaviorEntity> toolWrapper = new QueryWrapper<>();
        toolWrapper.eq("agent_id", scope.agent.getId())
                .eq("scope_type", MemoryHelper.SCOPE_DEVICE)
                .and(w -> w.eq("scope_id", scope.scopeId).or().eq("scope_id", scope.agent.getId()))
                .orderByDesc("updated_at")
                .last("LIMIT 5");
        vo.setToolBehaviors(toolBehaviorDao.selectList(toolWrapper));

        // 忘记名单
        QueryWrapper<MemoryForgetTombstoneEntity> tombstoneWrapper = new QueryWrapper<>();
        tombstoneWrapper.eq("agent_id", scope.agent.getId())
                .eq("scope_type", MemoryHelper.SCOPE_DEVICE)
                .and(w -> w.eq("scope_id", scope.scopeId).or().eq("scope_id", scope.agent.getId()))
                .eq("status", MemoryHelper.STATUS_ACTIVE)
                .orderByDesc("created_at")
                .last("LIMIT 10");
        vo.setBlockedPatterns(tombstoneDao.selectList(tombstoneWrapper).stream()
                .map(MemoryForgetTombstoneEntity::getPatternText)
                .toList());
        vo.setContext(buildContext(vo));
        return vo;
    }

    // ===================== 私有辅助方法 =====================

    /**
     * 构建查询上下文字符串。
     */
    private String buildContext(MemoryQueryVO vo) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.isNotBlank(vo.getProfile())) {
            builder.append("【用户画像】\n").append(vo.getProfile()).append("\n\n");
        }
        if (!vo.getFacts().isEmpty()) {
            builder.append("【长期记忆】\n");
            for (MemoryFactEntity fact : vo.getFacts()) {
                builder.append("- [").append(fact.getCategory()).append("] ")
                        .append(fact.getCanonicalText()).append("\n");
            }
            builder.append("\n");
        }
        if (!vo.getToolBehaviors().isEmpty()) {
            builder.append("【工具使用经验】\n");
            for (MemoryToolBehaviorEntity tool : vo.getToolBehaviors()) {
                builder.append("- ").append(tool.getToolName()).append(": 成功 ")
                        .append(tool.getSuccessCount()).append(" 次，失败 ")
                        .append(tool.getFailureCount()).append(" 次\n");
            }
            builder.append("\n");
        }
        if (!vo.getBlockedPatterns().isEmpty()) {
            builder.append("【忘记名单】\n");
            for (String pattern : vo.getBlockedPatterns()) {
                builder.append("- 不要再主动记住或使用：").append(pattern).append("\n");
            }
        }
        return builder.toString().trim();
    }

    private void refreshFactAccess(List<MemoryFactEntity> facts, Date now) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        for (MemoryFactEntity fact : facts) {
            fact.setLastSeenAt(now);
            fact.setUpdatedAt(now);
            factDao.updateById(fact);
        }
    }

    /**
     * 解析作用域：通过 macAddress / agentId 确定智能体和 scopeId。
     */
    private AgentScope resolveScope(String agentId, String macAddress) {
        AgentEntity agent = null;
        String scopeId = StringUtils.defaultIfBlank(macAddress, agentId);
        if (StringUtils.isNotBlank(macAddress)) {
            DeviceEntity device = deviceDao.selectOne(
                    new QueryWrapper<DeviceEntity>().eq("mac_address", macAddress).last("LIMIT 1"));
            if (device != null && StringUtils.isNotBlank(device.getAgentId())) {
                agent = agentDao.selectById(device.getAgentId());
                scopeId = macAddress;
            }
        }
        if (agent == null && StringUtils.isNotBlank(agentId)) {
            agent = agentDao.selectById(agentId);
        }
        return new AgentScope(agent, scopeId);
    }

    /**
     * 为文本生成嵌入向量。
     */
    private float[] generateEmbedding(String text, String modelId) {
        try {
            List<Double> vector = llmService.generateEmbedding(text, modelId);
            if (vector == null || vector.isEmpty()) return null;
            float[] result = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                result[i] = vector.get(i).floatValue();
            }
            return result;
        } catch (Exception e) {
            log.debug("生成嵌入向量失败（将回退到维护模型上下文决策）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取维护模型 ID。
     */
    private String getMaintenanceLlmModelId(AgentEntity agent) {
        if (agent == null || StringUtils.isBlank(agent.getMemModelId())) {
            return null;
        }
        try {
            ModelConfigEntity memoryConfig = modelConfigService.getModelByIdFromCache(agent.getMemModelId());
            if (memoryConfig == null) {
                return null;
            }
            JSONObject config = modelRuntimeAssembler.buildTypedConfig(memoryConfig);
            return StringUtils.defaultIfBlank(config.getStr(MemoryHelper.MAINTENANCE_LLM_FIELD),
                    config.getStr("memory_maintenance_llm"));
        } catch (Exception e) {
            log.warn("读取记忆维护模型配置失败，agentId: {}, memModelId: {}, error: {}",
                    agent.getId(), agent.getMemModelId(), e.getMessage());
            return null;
        }
    }

    /**
     * 作用域内部记录。
     */
    private record AgentScope(AgentEntity agent, String scopeId) {
    }
}
