package xiaozhi.modules.model.service.impl;

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.dao.AgentPluginMappingMapper;
import xiaozhi.modules.agent.entity.AgentPluginMapping;
import xiaozhi.modules.agent.runtime.AgentRuntimeConfigRefreshEvent;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.common.service.impl.BaseServiceImpl;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.ConvertUtils;
import xiaozhi.modules.model.dao.ModelProviderDao;
import xiaozhi.modules.model.dto.ModelProviderDTO;
import xiaozhi.modules.model.entity.ModelProviderEntity;
import xiaozhi.modules.model.service.ModelProviderService;
import xiaozhi.modules.model.support.PluginCatalogContract;
import xiaozhi.modules.security.user.SecurityUser;

@Service
@AllArgsConstructor
public class ModelProviderServiceImpl extends BaseServiceImpl<ModelProviderDao, ModelProviderEntity>
        implements ModelProviderService {

    private final ModelProviderDao modelProviderDao;
    private final AgentDao agentDao;
    private final ApplicationEventPublisher eventPublisher;
    private final AgentPluginMappingMapper pluginMappingMapper;

    @Override
    public List<ModelProviderDTO> getPluginList() {
        // 1. 获取插件列表
        LambdaQueryWrapper<ModelProviderEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ModelProviderEntity::getModelType, "Plugin");
        List<ModelProviderEntity> providerEntities = modelProviderDao.selectList(queryWrapper);
        return PluginCatalogContract.decorate(ConvertUtils.sourceToTarget(providerEntities, ModelProviderDTO.class));
    }

    @Override
    public ModelProviderDTO getById(String id) {
        ModelProviderEntity entity = modelProviderDao.selectById(id);
        return PluginCatalogContract.decorate(ConvertUtils.sourceToTarget(entity, ModelProviderDTO.class));
    }

    @Override
    public List<ModelProviderDTO> getPluginListByIds(Collection<String> ids) {
        LambdaQueryWrapper<ModelProviderEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(ModelProviderEntity::getId, ids);
        queryWrapper.eq(ModelProviderEntity::getModelType, "Plugin");
        List<ModelProviderEntity> providerEntities = modelProviderDao.selectList(queryWrapper);
        return PluginCatalogContract.decorate(ConvertUtils.sourceToTarget(providerEntities, ModelProviderDTO.class));
    }

    @Override
    public List<ModelProviderDTO> getListByModelType(String modelType) {

        QueryWrapper<ModelProviderEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("model_type", StringUtils.isBlank(modelType) ? "" : modelType);
        queryWrapper.orderByAsc("sort");
        List<ModelProviderEntity> providerEntities = modelProviderDao.selectList(queryWrapper);
        return PluginCatalogContract.decorate(ConvertUtils.sourceToTarget(providerEntities, ModelProviderDTO.class));
    }

    @Override
    public PageData<ModelProviderDTO> getListPage(ModelProviderDTO modelProviderDTO, String page, String limit) {

        Map<String, Object> params = new HashMap<String, Object>();
        params.put(Constant.PAGE, page);
        params.put(Constant.LIMIT, limit);
        params.put(Constant.ORDER_FIELD, List.of("model_type", "sort"));
        params.put(Constant.ORDER, "asc");

        IPage<ModelProviderEntity> pageParam = getPage(params, null, true);

        QueryWrapper<ModelProviderEntity> wrapper = new QueryWrapper<ModelProviderEntity>();

        if (StringUtils.isNotBlank(modelProviderDTO.getModelType())) {
            wrapper.eq("model_type", modelProviderDTO.getModelType());
        }

        if (StringUtils.isNotBlank(modelProviderDTO.getName())) {
            wrapper.and(w -> w.like("name", modelProviderDTO.getName())
                    .or()
                    .like("provider_code", modelProviderDTO.getName()));
        }
        PageData<ModelProviderDTO> pageData = getPageData(modelProviderDao.selectPage(pageParam, wrapper), ModelProviderDTO.class);
        PluginCatalogContract.decorate(pageData.getList());
        return pageData;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelProviderDTO add(ModelProviderDTO modelProviderDTO) {
        validatePluginPrompt(modelProviderDTO);
        UserDetail user = SecurityUser.getUser();
        modelProviderDTO.setCreator(user.getId());
        modelProviderDTO.setUpdater(user.getId());
        modelProviderDTO.setCreateDate(new Date());
        modelProviderDTO.setUpdateDate(new Date());
        // 去除Fields左右的双引号

        modelProviderDTO.setFields(modelProviderDTO.getFields());
        ModelProviderEntity entity = ConvertUtils.sourceToTarget(modelProviderDTO, ModelProviderEntity.class);
        if (modelProviderDao.insert(entity) == 0) {
            throw new RenException(ErrorCode.ADD_DATA_FAILED);
        }

        publishToolPromptRefresh(modelProviderDTO, "tool_catalog_added");

        return PluginCatalogContract.decorate(ConvertUtils.sourceToTarget(modelProviderDTO, ModelProviderDTO.class));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelProviderDTO edit(ModelProviderDTO modelProviderDTO) {
        validatePluginPrompt(modelProviderDTO);
        ModelProviderEntity existing = modelProviderDao.selectById(modelProviderDTO.getId());
        if (existing != null && "Plugin".equals(existing.getModelType())
                && PluginCatalogContract.isRetired(existing.getProviderCode())) {
            throw new RenException("该插件已退役，不能修改或通过更改编码重新启用");
        }
        UserDetail user = SecurityUser.getUser();
        modelProviderDTO.setUpdater(user.getId());
        modelProviderDTO.setUpdateDate(new Date());
        if (modelProviderDao
                .updateById(ConvertUtils.sourceToTarget(modelProviderDTO, ModelProviderEntity.class)) == 0) {
            throw new RenException(ErrorCode.UPDATE_DATA_FAILED);
        }
        // Rules, activation metadata and schemas affect runtime as well as description.
        publishToolPromptRefresh(modelProviderDTO, "tool_catalog_updated");
        if (existing != null && "Plugin".equals(existing.getModelType())
                && !"Plugin".equals(modelProviderDTO.getModelType())) {
            publishToolPromptRefresh(ConvertUtils.sourceToTarget(existing, ModelProviderDTO.class), "tool_catalog_updated");
        }
        return PluginCatalogContract.decorate(ConvertUtils.sourceToTarget(modelProviderDTO, ModelProviderDTO.class));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        ModelProviderDTO deleted = getById(id);
        if (deleted != null && "Plugin".equals(deleted.getModelType())) {
            pluginMappingMapper.delete(new QueryWrapper<AgentPluginMapping>().eq("plugin_id", id));
        }
        if (modelProviderDao.deleteById(id) == 0) {
            throw new RenException(ErrorCode.DELETE_DATA_FAILED);
        }
        publishToolPromptRefresh(deleted, "tool_catalog_deleted");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(List<String> ids) {
        List<ModelProviderDTO> deleted = getPluginListByIds(ids);
        if (!deleted.isEmpty()) {
            pluginMappingMapper.delete(new QueryWrapper<AgentPluginMapping>()
                    .in("plugin_id", deleted.stream().map(ModelProviderDTO::getId).toList()));
        }
        if (modelProviderDao.deleteBatchIds(ids) == 0) {
            throw new RenException(ErrorCode.DELETE_DATA_FAILED);
        }
        if (!deleted.isEmpty()) publishToolPromptRefresh(deleted.get(0), "tool_catalog_deleted");
    }

    @Override
    public List<ModelProviderDTO> getList(String modelType, String providerCode) {
        QueryWrapper<ModelProviderEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("model_type", StringUtils.isBlank(modelType) ? "" : modelType);
        queryWrapper.eq("provider_code", StringUtils.isBlank(providerCode) ? "" : providerCode);
        List<ModelProviderEntity> providerEntities = modelProviderDao.selectList(queryWrapper);
        return PluginCatalogContract.decorate(ConvertUtils.sourceToTarget(providerEntities, ModelProviderDTO.class));
    }

    private void validatePluginPrompt(ModelProviderDTO provider) {
        if (provider == null || !"Plugin".equals(provider.getModelType())) {
            return;
        }
        if (PluginCatalogContract.isRetired(provider.getProviderCode())) {
            throw new RenException("该插件已退役，不能新增或修改");
        }
        try {
            JSONArray fields = JSONUtil.parseArray(provider.getFields());
            for (Object value : fields) {
                if (!(value instanceof JSONObject field)
                        || !"description".equals(field.getStr("key"))) {
                    continue;
                }
                String description = StringUtils.trimToEmpty(field.getStr("default"));
                if (StringUtils.isBlank(description)) {
                    throw new RenException("大模型工具提示词不能为空");
                }
                if (description.length() > 1000) {
                    throw new RenException("大模型工具提示词不能超过1000个字符");
                }
            }
        } catch (RenException error) {
            throw error;
        } catch (Exception error) {
            throw new RenException("插件字段必须是合法的JSON数组");
        }
    }

    private void publishToolPromptRefresh(ModelProviderDTO provider, String reason) {
        if (provider == null || !"Plugin".equals(provider.getModelType())) {
            return;
        }
        List<String> agentIds = agentDao.selectAllAgentIds();
        if (agentIds != null && !agentIds.isEmpty()) {
            eventPublisher.publishEvent(new AgentRuntimeConfigRefreshEvent(agentIds, reason));
        }
    }
}
