package xiaozhi.modules.agent.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import xiaozhi.modules.agent.dao.AgentTemplateDao;
import xiaozhi.modules.agent.entity.AgentTemplateEntity;
import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.AllArgsConstructor;

/**
 * @author chenerlei
 * @description 针对表【ai_agent_template(智能体配置模板表)】的数据库操作Service实现
 * @createDate 2025-03-22 11:48:18
 */
@Service
@AllArgsConstructor
public class AgentTemplateServiceImpl extends ServiceImpl<AgentTemplateDao, AgentTemplateEntity>
        implements AgentTemplateService {

    private final ModelConfigService modelConfigService;

    /**
     * 获取默认模板
     * 
     * @return 默认模板实体
     */
    public AgentTemplateEntity getDefaultTemplate() {
        LambdaQueryWrapper<AgentTemplateEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(AgentTemplateEntity::getSort)
                .last("LIMIT 1");
        return this.getOne(wrapper);
    }

    /**
     * 更新默认模板中的模型ID
     * 
     * @param modelType 模型类型
     * @param modelId   模型ID
     */
    @Override
    public void updateDefaultTemplateModelId(String modelType, String modelId) {
        modelType = modelType.toUpperCase();
        // 如果是rag模型，不需要更新
        if (modelType.equals("RAG")) {
            return;
        }

        UpdateWrapper<AgentTemplateEntity> wrapper = new UpdateWrapper<>();
        String resolvedModelId = resolveEnabledModelId(modelType, modelId);
        switch (modelType) {
            case "ASR":
                wrapper.set("asr_model_id", resolvedModelId);
                break;
            case "VAD":
                wrapper.set("vad_model_id", resolvedModelId);
                break;
            case "LLM":
                wrapper.set("llm_model_id", resolvedModelId);
                break;
            case "SLM":
                wrapper.set("slm_model_id", resolvedModelId);
                break;
            case "TTS":
                wrapper.set("tts_model_id", resolvedModelId);
                wrapper.set("tts_voice_id", null);
                break;
            case "VLLM":
                wrapper.set("vllm_model_id", resolvedModelId);
                break;
            case "MEMORY":
                wrapper.set("mem_model_id", resolvedModelId);
                break;
            case "INTENT":
                wrapper.set("intent_model_id", resolvedModelId);
                break;
        }
        wrapper.ge("sort", 0);
        update(wrapper);
    }

    @Override
    public boolean save(AgentTemplateEntity entity) {
        sanitizeTemplateModelIds(entity);
        entity.setVersion(1);
        return super.save(entity);
    }

    @Override
    public boolean updateById(AgentTemplateEntity entity) {
        sanitizeTemplateModelIds(entity);
        if (entity == null || StringUtils.isBlank(entity.getId())) {
            return false;
        }
        entity.setVersion(null);
        return baseMapper.update(entity, new LambdaUpdateWrapper<AgentTemplateEntity>()
                .eq(AgentTemplateEntity::getId, entity.getId())
                .setSql("version = version + 1")) == 1;
    }

    @Override
    public void reorderTemplatesAfterDelete(Integer deletedSort) {
        if (deletedSort == null) {
            return;
        }
        
        // 查询所有排序值大于被删除模板的记录
        UpdateWrapper<AgentTemplateEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.gt("sort", deletedSort)
                    .setSql("sort = sort - 1");
        
        // 执行批量更新，将这些记录的排序值减1
        this.update(updateWrapper);
    }

    @Override
    public Integer getNextAvailableSort() {
        // 查询所有已存在的排序值并按升序排序
        List<Integer> sortValues = baseMapper.selectList(new QueryWrapper<AgentTemplateEntity>())
                .stream()
                .map(AgentTemplateEntity::getSort)
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.toList());
        
        // 如果没有排序值，返回1
        if (sortValues.isEmpty()) {
            return 1;
        }
        
        // 寻找最小的未使用序号
        int expectedSort = 1;
        for (Integer sort : sortValues) {
            if (sort > expectedSort) {
                // 找到空缺的序号
                return expectedSort;
            }
            expectedSort = sort + 1;
        }
        
        // 如果没有空缺，返回最大序号+1
        return expectedSort;
    }

    private void sanitizeTemplateModelIds(AgentTemplateEntity entity) {
        if (entity == null) {
            return;
        }
        entity.setAsrModelId(resolveEnabledModelId("ASR", entity.getAsrModelId()));
        entity.setVadModelId(resolveEnabledModelId("VAD", entity.getVadModelId()));
        entity.setLlmModelId(resolveEnabledModelId("LLM", entity.getLlmModelId()));
        entity.setSlmModelId(resolveEnabledModelId("SLM", entity.getSlmModelId()));
        entity.setVllmModelId(resolveEnabledModelId("VLLM", entity.getVllmModelId()));
        entity.setTtsModelId(resolveEnabledModelId("TTS", entity.getTtsModelId()));
        entity.setMemModelId(resolveEnabledModelId("Memory", entity.getMemModelId()));
        entity.setIntentModelId(resolveEnabledModelId("Intent", entity.getIntentModelId()));
    }

    private String resolveEnabledModelId(String modelType, String candidateModelId) {
        if (StringUtils.isBlank(candidateModelId)) {
            return null;
        }

        ModelConfigEntity candidate = modelConfigService.getModelByIdFromCache(candidateModelId);
        if (candidate != null
                && modelType.equalsIgnoreCase(candidate.getModelType())
                && candidate.getIsEnabled() != null
                && candidate.getIsEnabled() == 1
                && candidate.getTestStatus() != null
                && candidate.getTestStatus() == 1) {
            return candidateModelId;
        }
        return null;
    }
}
