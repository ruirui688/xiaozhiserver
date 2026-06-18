package xiaozhi.modules.agent.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.agent.dao.AgentPluginMappingMapper;
import xiaozhi.modules.agent.entity.AgentPluginMapping;
import xiaozhi.modules.agent.service.AgentPluginMappingService;
import xiaozhi.modules.knowledge.entity.KnowledgeBaseEntity;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;

/**
 * @description 针对表【ai_agent_plugin_mapping(Agent与插件的唯一映射表)】的数据库操作Service实现
 * @createDate 2025-05-25 22:33:17
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentPluginMappingServiceImpl extends ServiceImpl<AgentPluginMappingMapper, AgentPluginMapping>
        implements AgentPluginMappingService {
    private final AgentPluginMappingMapper agentPluginMappingMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ModelConfigService modelConfigService;

    @Override
    public List<AgentPluginMapping> agentPluginParamsByAgentId(String agentId) {
        List<AgentPluginMapping> list = agentPluginMappingMapper.selectPluginsByAgentId(agentId);
        Map<String, List<KnowledgeBaseEntity>> knowledgeBaseMap = new HashMap<>();
        Map<String, ModelConfigEntity> modelConfigMap = new HashMap<>();
        Map<String, List<String>> knowledgeInstructionMap = new HashMap<>();
        for (int i = list.size() - 1; i >= 0; i--) {
            AgentPluginMapping mapping = list.get(i);
            if (StringUtils.isBlank(mapping.getProviderCode())) {
                // 查询知识库插件参数
                KnowledgeBaseEntity knowledgeBaseEntity = knowledgeBaseService.selectById(mapping.getPluginId());
                if (knowledgeBaseEntity == null) {
                    list.remove(i);
                    continue;
                }
                ModelConfigEntity modelConfigEntity = modelConfigService
                        .getModelByIdFromCache(knowledgeBaseEntity.getRagModelId());
                if (modelConfigEntity == null) {
                    list.remove(i);
                    continue;
                }
                Map<String, Object> mappingParamInfo = parseParamInfo(mapping.getParamInfo());
                List<KnowledgeBaseEntity> knowledgeBaseList = knowledgeBaseMap.get(modelConfigEntity.getModelCode());
                if (knowledgeBaseList == null) {
                    knowledgeBaseList = new ArrayList<>();
                }
                modelConfigMap.put(modelConfigEntity.getModelCode(), modelConfigEntity);
                knowledgeBaseList.add(knowledgeBaseEntity);
                knowledgeBaseMap.put(modelConfigEntity.getModelCode(), knowledgeBaseList);
                appendKnowledgeInstructions(knowledgeInstructionMap, knowledgeBaseEntity, mappingParamInfo);
                list.remove(i);
            }
        }
        if (knowledgeBaseMap.size() > 0) {
        for (String pluginCode : knowledgeBaseMap.keySet()) {
            List<KnowledgeBaseEntity> knowledgeBaseList = knowledgeBaseMap.get(pluginCode);
            if (knowledgeBaseList == null || knowledgeBaseList.isEmpty()) {
                continue;
            }

                AgentPluginMapping agentPluginMapping = new AgentPluginMapping();
                agentPluginMapping.setAgentId(agentId);
                agentPluginMapping.setPluginId(pluginCode);
                agentPluginMapping.setProviderCode("search_from_" + pluginCode);
                agentPluginMapping.setId(Long.valueOf(list.size() + 1));

                Map<String, Object> paramInfo = new HashMap<>(4);
                ModelConfigEntity modelConfigEntity = modelConfigMap.get(pluginCode);
                paramInfo.put("type", modelConfigEntity.getConfigJson().getStr("type"));
                paramInfo.put("base_url", modelConfigEntity.getConfigJson().getStr("base_url"));
                paramInfo.put("api_key", modelConfigEntity.getConfigJson().getStr("api_key"));
                paramInfo.put("dataset_ids",
                        knowledgeBaseList.stream().map(KnowledgeBaseEntity::getDatasetId).toList());

                String description = buildKnowledgeToolDescription(knowledgeInstructionMap, knowledgeBaseList);
                paramInfo.put("description", description);
                agentPluginMapping.setParamInfo(JsonUtils.toJsonString(paramInfo));
                list.add(agentPluginMapping);
            }
        }
        return list;
    }

    @Override
    public void deleteByAgentId(String agentId) {
        UpdateWrapper<AgentPluginMapping> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("agent_id", agentId);
        agentPluginMappingMapper.delete(updateWrapper);
    }

    @Override
    public void deleteByPluginId(String pluginId) {
        UpdateWrapper<AgentPluginMapping> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("plugin_id", pluginId);
        agentPluginMappingMapper.delete(updateWrapper);
    }

    private Map<String, Object> parseParamInfo(String paramInfo) {
        if (StringUtils.isBlank(paramInfo)) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> result = JsonUtils.parseObject(paramInfo, new TypeReference<Map<String, Object>>() {
            });
            return result == null ? new HashMap<>() : result;
        } catch (Exception e) {
            log.warn("解析智能体知识库参数失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private void appendKnowledgeInstructions(Map<String, List<String>> knowledgeInstructionMap,
            KnowledgeBaseEntity knowledgeBase, Map<String, Object> mappingParamInfo) {
        String datasetId = knowledgeBase.getDatasetId();
        if (StringUtils.isBlank(datasetId)) {
            return;
        }
        List<String> instructions = knowledgeInstructionMap.get(datasetId);
        if (instructions == null) {
            instructions = new ArrayList<>();
        }

        String datasetDescription = StringUtils.trimToEmpty(knowledgeBase.getDescription());
        if (StringUtils.isNotBlank(datasetDescription) && !"1".equals(datasetDescription)) {
            instructions.add("知识库内容范围：" + datasetDescription);
        }

        Object queryPolicy = mappingParamInfo.get("queryPolicy");
        if (queryPolicy instanceof String && StringUtils.isNotBlank((String) queryPolicy)) {
            instructions.add("查询词生成规则：" + StringUtils.trim((String) queryPolicy));
        }

        knowledgeInstructionMap.put(datasetId, instructions);
    }

    private String buildKnowledgeToolDescription(Map<String, List<String>> knowledgeInstructionMap,
            List<KnowledgeBaseEntity> knowledgeBaseList) {
        List<String> names = knowledgeBaseList.stream().map(KnowledgeBaseEntity::getName).toList();
        List<String> instructionParts = new ArrayList<>();

        for (KnowledgeBaseEntity knowledgeBase : knowledgeBaseList) {
            List<String> instructions = knowledgeInstructionMap.get(knowledgeBase.getDatasetId());
            if (instructions == null || instructions.isEmpty()) {
                continue;
            }
            instructionParts.add("【" + knowledgeBase.getName() + "】" + String.join("；", instructions));
        }

        String description = "当用户的问题明确涉及以下知识库范围时，调用本方法查询本地知识库：【"
                + String.join("、", names)
                + "】。";
        if (!instructionParts.isEmpty()) {
            description += "知识库说明：" + String.join("；", instructionParts) + "。";
        }
        description += "调用时 question 参数必须围绕用户当前问题和已明确上下文生成；不要擅自引入用户没有提到的对象、品类或场景。"
                + "如果用户只表达偏好或确认，请围绕已明确的酒类、产区、风味、价格、搭配等信息查询，不要扩写成酒杯、酒具或其他未提及物品。";
        return description;
    }

}
