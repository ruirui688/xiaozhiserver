package xiaozhi.modules.model.service.impl;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONObject;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.common.redis.RedisKeys;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.service.impl.BaseServiceImpl;
import xiaozhi.common.utils.ConvertUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.runtime.AgentRuntimeConfigRefreshEvent;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.dto.LlmModelBasicInfoDTO;
import xiaozhi.modules.model.dto.ModelBasicInfoDTO;
import xiaozhi.modules.model.dto.ModelCatalogDTO;
import xiaozhi.modules.model.dto.ModelConfigBodyDTO;
import xiaozhi.modules.model.dto.ModelConfigDTO;
import xiaozhi.modules.model.dto.ModelProviderDTO;
import xiaozhi.modules.model.dto.ModelTestResultDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelCatalogService;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.ModelProviderService;
import xiaozhi.modules.model.service.ModelTestService;

@Service
@AllArgsConstructor
@Slf4j
public class ModelConfigServiceImpl extends BaseServiceImpl<ModelConfigDao, ModelConfigEntity>
        implements ModelConfigService {

    private final ModelConfigDao modelConfigDao;
    private final ModelRuntimeAssembler modelRuntimeAssembler;
    private final ModelProviderService modelProviderService;
    private final ModelCatalogService modelCatalogService;
    private final RedisUtils redisUtils;
    private final AgentDao agentDao;
    private final ModelTestService modelTestService;
    private final ApplicationEventPublisher eventPublisher;

    private static final int MODEL_TEST_UNTESTED = 0;
    private static final int MODEL_TEST_PASSED = 1;
    private static final int MODEL_TEST_FAILED = 2;
    private static final List<String> UPSTREAM_MODEL_FIELDS = List.of(
            "model_name", "model", "resource_id", "model_dir", "llm_model", "llm");
    private static final List<String> ENDPOINT_FIELDS = List.of(
            "base_url", "api_url", "url", "server_url", "ws_url", "host");
    @Override
    public List<ModelBasicInfoDTO> getModelCodeList(String modelType, String modelName) {
        List<ModelConfigEntity> entities = modelConfigDao.selectList(
                new QueryWrapper<ModelConfigEntity>()
                        .eq("model_type", modelType)
                        .eq("is_enabled", 1)
                        .eq("test_status", MODEL_TEST_PASSED)
                        .like(StringUtils.isNotBlank(modelName), "model_name", modelName)
                        .select("id", "model_name", "model_code", "adapter_code", "vendor_name",
                                "product_name", "protocol", "upstream_model_id")
                        .orderByAsc("sort"));
        return ConvertUtils.sourceToTarget(entities, ModelBasicInfoDTO.class);
    }

    @Override
    public List<LlmModelBasicInfoDTO> getLlmModelCodeList(String modelName) {
        List<ModelConfigEntity> entities = modelConfigDao.selectList(
                new QueryWrapper<ModelConfigEntity>()
                        .eq("model_type", "llm")
                        .eq("is_enabled", 1)
                        .eq("test_status", MODEL_TEST_PASSED)
                        .like(StringUtils.isNotBlank(modelName), "model_name", modelName)
                        .select("id", "model_name", "model_code", "adapter_code", "vendor_name",
                                "product_name", "protocol", "upstream_model_id", "endpoint_url"));

        return attachRuntimeConfigs(entities).stream().map(item -> {
            LlmModelBasicInfoDTO dto = new LlmModelBasicInfoDTO();
            dto.setId(item.getId());
            dto.setModelName(item.getModelName());
            dto.setModelCode(item.getModelCode());
            dto.setAdapterCode(item.getAdapterCode());
            dto.setVendorName(item.getVendorName());
            dto.setProductName(item.getProductName());
            dto.setProtocol(item.getProtocol());
            dto.setUpstreamModelId(item.getUpstreamModelId());
            String type = item.getConfigJson() == null ? "" : item.getConfigJson().getOrDefault("type", "").toString();
            dto.setType(type);
            String conversationMode = item.getConfigJson() == null
                    ? ""
                    : StringUtils.defaultString(item.getConfigJson().getStr("conversation_mode"));
            dto.setConversationMode(conversationMode);
            dto.setRealtime("realtime".equalsIgnoreCase(conversationMode)
                    || "qwen_omni_realtime".equalsIgnoreCase(type)
                    || "doubao_realtime".equalsIgnoreCase(type));
            return dto;
        }).toList();
    }

    @Override
    public PageData<ModelConfigDTO> getPageList(String modelType, String modelName, String page, String limit) {
        Map<String, Object> params = new HashMap<>();
        params.put(Constant.PAGE, page);
        params.put(Constant.LIMIT, limit);

        long curPage = Long.parseLong(page);
        long pageSize = Long.parseLong(limit);
        Page<ModelConfigEntity> pageInfo = new Page<>(curPage, pageSize);

        // 添加排序规则：先按is_enabled降序，再按sort升序
        pageInfo.addOrder(OrderItem.desc("is_enabled"), OrderItem.asc("sort"));

        IPage<ModelConfigEntity> modelConfigEntityIPage = modelConfigDao.selectPage(
                pageInfo,
                new QueryWrapper<ModelConfigEntity>()
                        .eq("model_type", modelType)
                        .like(StringUtils.isNotBlank(modelName), "model_name", modelName));

        Map<String, ModelProviderDTO> providerCache = new HashMap<>();
        List<ModelConfigEntity> records = attachRuntimeConfigs(modelConfigEntityIPage.getRecords());
        List<ModelConfigDTO> list = records.stream()
                .map(entity -> buildResponseDTO(entity, providerCache))
                .toList();
        return new PageData<>(list, modelConfigEntityIPage.getTotal());
    }

    @Override
    public ModelConfigDTO edit(String modelType, String provideCode, String id, ModelConfigBodyDTO modelConfigBodyDTO) {
        // 1. 参数验证
        validateEditParameters(modelType, provideCode, id, modelConfigBodyDTO);

        // 2. 验证模型提供者
        validateModelProvider(modelType, provideCode);

        // 3. 获取原始配置
        ModelConfigEntity originalEntity = getOriginalConfigFromDb(id);

        JSONObject runtimeConfig = buildStandardRuntimeConfig(modelConfigBodyDTO, provideCode);

        // 4. 验证LLM配置
        validateLlmConfiguration(runtimeConfig);

        // 5. 准备更新实体
        ModelConfigEntity modelConfigEntity = prepareUpdateEntity(modelConfigBodyDTO, originalEntity, modelType, id);

        // 6. 执行数据库更新
        modelConfigDao.updateById(modelConfigEntity);
        modelRuntimeAssembler.saveRuntimeConfig(modelConfigEntity, runtimeConfig);

        // 7. 清除缓存
        clearModelCache(id);
        publishRuntimeRefreshForModel(id, "model_config_updated");

        // 8. 返回更新后的数据
        return buildResponseDTO(modelConfigEntity);
    }

    @Override
    public ModelConfigDTO add(String modelType, String provideCode, ModelConfigBodyDTO modelConfigBodyDTO) {
        validateAddParameters(modelType, provideCode, modelConfigBodyDTO);

        validateModelProvider(modelType, provideCode);

        ModelConfigEntity modelConfigEntity = prepareAddEntity(modelConfigBodyDTO, modelType);
        JSONObject runtimeConfig = buildStandardRuntimeConfig(modelConfigBodyDTO, provideCode);

        modelConfigDao.insert(modelConfigEntity);
        modelRuntimeAssembler.saveRuntimeConfig(modelConfigEntity, runtimeConfig);

        return buildResponseDTO(modelConfigEntity);
    }

    @Override
    public ModelConfigDTO getDTOById(String id) {
        if (StringUtils.isBlank(id)) {
            throw new RenException(ErrorCode.IDENTIFIER_NOT_NULL);
        }
        ModelConfigEntity entity = attachRuntimeConfig(modelConfigDao.selectById(id));
        if (entity == null) {
            throw new RenException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return buildResponseDTO(entity, new HashMap<>(), true);
    }

    @Override
    public void delete(String id) {
        if (StringUtils.isBlank(id)) {
            throw new RenException(ErrorCode.IDENTIFIER_NOT_NULL);
        }

        ModelConfigEntity modelConfig = attachRuntimeConfig(modelConfigDao.selectById(id));
        if (modelConfig != null && modelConfig.getIsDefault() == 1) {
            throw new RenException(ErrorCode.DEFAULT_MODEL_DELETE_ERROR);
        }

        checkAgentReference(id);
        checkIntentConfigReference(id);

        modelConfigDao.deleteById(id);
        modelRuntimeAssembler.deleteRuntimeConfig(id);

        clearModelCache(id);
    }

    @Override
    public ModelTestResultDTO testModel(String id) {
        if (StringUtils.isBlank(id)) {
            throw new RenException(ErrorCode.IDENTIFIER_NOT_NULL);
        }

        ModelConfigEntity modelConfig = attachRuntimeConfig(modelConfigDao.selectById(id));
        if (modelConfig == null) {
            throw new RenException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        long startTime = System.currentTimeMillis();
        ModelTestResultDTO result = modelTestService.testModel(modelConfig);
        if (result.getResponseTimeMs() == null && modelTestService.shouldShowModelTestResponseTime(result)) {
            result.setResponseTimeMs(System.currentTimeMillis() - startTime);
        }
        Date testTime = new Date();
        result.setTestTime(testTime);
        boolean verified = modelTestService.isVerifiedModelTest(result);
        result.setVerified(verified);
        result.setTestStatus(verified ? MODEL_TEST_PASSED : MODEL_TEST_FAILED);

        ModelConfigEntity updateEntity = new ModelConfigEntity();
        updateEntity.setId(id);
        updateEntity.setConfigJson(null);
        updateEntity.setTestStatus(result.getTestStatus());
        updateEntity.setTestMessage(result.getMessage());
        updateEntity.setTestTime(testTime);
        updateEntity.setIsEnabled(verified ? 1 : 0);
        modelConfigDao.updateById(updateEntity);
        clearModelCache(id);
        publishRuntimeRefreshForModel(id, "model_status_updated");
        return result;
    }

    @Override
    public String getModelNameById(String id) {
        if (StringUtils.isBlank(id)) {
            return null;
        }

        String cacheKey = RedisKeys.getModelNameById(id);
        String cachedName = (String) redisUtils.get(cacheKey);
        if (StringUtils.isNotBlank(cachedName)) {
            return cachedName;
        }

        ModelConfigEntity entity = modelConfigDao.selectById(id);
        if (entity != null) {
            String modelName = entity.getModelName();
            if (StringUtils.isNotBlank(modelName)) {
                redisUtils.set(cacheKey, modelName);
            }
            return modelName;
        }

        return null;
    }

    @Override
    protected <D> PageData<D> getPageData(IPage<?> page, Class<D> target) {
        return super.getPageData(page, target);
    }

    @Override
    public ModelConfigEntity getModelByIdFromCache(String id) {
        if (StringUtils.isBlank(id)) {
            return null;
        }
        String cacheKey = RedisKeys.getModelConfigById(id);
        ModelConfigEntity entity = (ModelConfigEntity) redisUtils.get(cacheKey);
        if (entity == null) {
            entity = attachRuntimeConfig(modelConfigDao.selectById(id));
            if (entity != null) {
                redisUtils.set(cacheKey, entity);
            }
        }
        return entity;
    }

    /**
     * 验证编辑参数
     */
    private void validateEditParameters(String modelType, String provideCode, String id,
            ModelConfigBodyDTO modelConfigBodyDTO) {
        if (StringUtils.isBlank(modelType) || StringUtils.isBlank(provideCode)) {
            throw new RenException(ErrorCode.MODEL_TYPE_PROVIDE_CODE_NOT_NULL);
        }
        if (StringUtils.isBlank(id)) {
            throw new RenException(ErrorCode.IDENTIFIER_NOT_NULL);
        }
        if (modelConfigBodyDTO == null) {
            throw new RenException(ErrorCode.PARAMS_GET_ERROR);
        }
    }

    /**
     * 验证添加参数
     */
    private void validateAddParameters(String modelType, String provideCode, ModelConfigBodyDTO modelConfigBodyDTO) {
        if (StringUtils.isBlank(modelType) || StringUtils.isBlank(provideCode)) {
            throw new RenException(ErrorCode.MODEL_TYPE_PROVIDE_CODE_NOT_NULL);
        }
        if (modelConfigBodyDTO == null) {
            throw new RenException(ErrorCode.PARAMS_GET_ERROR);
        }
        if (StringUtils.isBlank(modelConfigBodyDTO.getId())) {
            // 参照 MP @TableId AutoUUID 策略使用
            // com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator(UUID.replace("-",""))
            // 进行分配默认模型ID
            modelConfigBodyDTO.setId(DefaultIdentifierGenerator.getInstance().nextUUID(ModelConfigEntity.class));
        }
    }

    /**
     * 设置默认模型
     */
    @Override
    public void setDefaultModel(String modelType, int isDefault) {
        // 参数验证
        if (StringUtils.isBlank(modelType)) {
            throw new RenException(ErrorCode.MODEL_TYPE_PROVIDE_CODE_NOT_NULL);
        }

        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setIsDefault(isDefault);
        modelConfigDao.update(entity, new QueryWrapper<ModelConfigEntity>()
                .eq("model_type", modelType));

        // 清除相关缓存
        clearModelCacheByType(modelType);
    }

    /**
     * 验证模型提供者
     */
    private void validateModelProvider(String modelType, String provideCode) {
        List<ModelProviderDTO> providerList = modelProviderService.getList(modelType, provideCode);
        if (CollectionUtil.isEmpty(providerList)) {
            throw new RenException(ErrorCode.MODEL_PROVIDER_NOT_EXIST);
        }
    }

    /**
     * 从数据库获取原始配置
     */
    private ModelConfigEntity getOriginalConfigFromDb(String id) {
        ModelConfigEntity originalEntity = attachRuntimeConfig(modelConfigDao.selectById(id));
        if (originalEntity == null) {
            throw new RenException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return originalEntity;
    }

    /**
     * 验证LLM配置
     */
    private void validateLlmConfiguration(JSONObject runtimeConfig) {
        if (runtimeConfig != null && runtimeConfig.containsKey("llm")) {
            String llm = runtimeConfig.get("llm").toString();
            ModelConfigEntity modelConfigEntity = attachRuntimeConfig(
                    modelConfigDao.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                            .eq(ModelConfigEntity::getId, llm)));

            if (modelConfigEntity == null) {
                throw new RenException(ErrorCode.LLM_NOT_EXIST);
            }

            String modelType = modelConfigEntity.getModelType();
            if (modelType == null || !"LLM".equals(modelType.toUpperCase())) {
                throw new RenException(ErrorCode.LLM_NOT_EXIST);
            }

            // 验证LLM类型
            JSONObject configJson = modelConfigEntity.getConfigJson();
            if (configJson != null && configJson.containsKey("type")) {
                String type = configJson.get("type").toString();
                if (!"openai".equals(type) && !"ollama".equals(type)) {
                    throw new RenException(ErrorCode.INVALID_LLM_TYPE);
                }
            }
        }
    }

    /**
     * 准备更新实体
     */
    private ModelConfigEntity prepareUpdateEntity(ModelConfigBodyDTO modelConfigBodyDTO,
            ModelConfigEntity originalEntity,
            String modelType,
            String id) {
        // 1. 复制原始实体，保留未在编辑表单中暴露的字段
        ModelConfigEntity modelConfigEntity = ConvertUtils.sourceToTarget(originalEntity, ModelConfigEntity.class);
        modelConfigEntity.setId(id);
        modelConfigEntity.setModelType(modelType);

        // 2. 更新基础字段
        modelConfigEntity.setModelName(normalizeUserModelName(modelConfigBodyDTO, originalEntity.getModelName()));
        modelConfigEntity.setModelCode(resolveInternalModelCode(modelConfigBodyDTO, modelConfigEntity.getModelName(),
                originalEntity.getModelCode()));
        applyStandardModelFields(modelConfigEntity, modelConfigBodyDTO);
        modelConfigEntity.setSort(modelConfigBodyDTO.getSort());
        modelConfigEntity.setIsEnabled(0);
        modelConfigEntity.setDocLink(modelConfigBodyDTO.getDocLink());
        modelConfigEntity.setRemark(modelConfigBodyDTO.getRemark());
        modelConfigEntity.setTestStatus(MODEL_TEST_UNTESTED);
        modelConfigEntity.setTestMessage(null);
        modelConfigEntity.setTestTime(null);
        // 标准运行时配置写入模型标准字段、参数表和密钥表，不再依赖 ai_model_config.config_json。
        modelConfigEntity.setConfigJson(null);

        return modelConfigEntity;
    }

    /**
     * 准备新增实体
     */
    private ModelConfigEntity prepareAddEntity(ModelConfigBodyDTO modelConfigBodyDTO, String modelType) {
        ModelConfigEntity modelConfigEntity = ConvertUtils.sourceToTarget(modelConfigBodyDTO, ModelConfigEntity.class);
        modelConfigEntity.setModelType(modelType);
        modelConfigEntity.setModelName(normalizeUserModelName(modelConfigBodyDTO, modelConfigEntity.getModelName()));
        modelConfigEntity.setModelCode(resolveInternalModelCode(modelConfigBodyDTO, modelConfigEntity.getModelName(),
                null));
        applyStandardModelFields(modelConfigEntity, modelConfigBodyDTO);
        modelConfigEntity.setIsDefault(0);
        modelConfigEntity.setIsEnabled(0);
        modelConfigEntity.setTestStatus(MODEL_TEST_UNTESTED);
        modelConfigEntity.setTestMessage(null);
        modelConfigEntity.setTestTime(null);
        modelConfigEntity.setConfigJson(null);
        return modelConfigEntity;
    }

    /**
     * 构建返回的DTO
     */
    private ModelConfigDTO buildResponseDTO(ModelConfigEntity entity) {
        return buildResponseDTO(entity, new HashMap<>(), false);
    }

    private ModelConfigDTO buildResponseDTO(ModelConfigEntity entity, Map<String, ModelProviderDTO> providerCache) {
        return buildResponseDTO(entity, providerCache, false);
    }

    private ModelConfigDTO buildResponseDTO(ModelConfigEntity entity, Map<String, ModelProviderDTO> providerCache,
            boolean includePlainSecrets) {
        ModelConfigDTO dto = ConvertUtils.sourceToTarget(entity, ModelConfigDTO.class);
        if (dto == null) {
            return null;
        }
        dto.setParams(modelRuntimeAssembler.buildPublicParams(entity));
        dto.setSecrets(includePlainSecrets
                ? modelRuntimeAssembler.buildPlainSecrets(entity)
                : modelRuntimeAssembler.buildMaskedSecrets(entity));
        ModelDisplayInfo displayInfo = buildModelDisplayInfo(entity, providerCache);
        dto.setProviderCode(displayInfo.providerCode);
        dto.setProviderName(displayInfo.providerName);
        dto.setAdapterLabel(displayInfo.adapterLabel);
        dto.setUpstreamModelId(displayInfo.upstreamModelId);
        dto.setPlatformName(displayInfo.platformName);
        dto.setEndpointSummary(displayInfo.endpointSummary);
        dto.setModelTags(displayInfo.modelTags);
        enrichCatalogInfo(dto, entity, displayInfo);
        return dto;
    }

    private void applyStandardModelFields(ModelConfigEntity entity, ModelConfigBodyDTO body) {
        String userModelName = normalizeUserModelName(body, entity.getModelName());
        String upstreamModel = firstNonBlank(body.getUpstreamModelId(), userModelName, entity.getUpstreamModelId());
        entity.setAdapterCode(firstNonBlank(
                body.getAdapterCode(),
                body.getProviderCode(),
                entity.getAdapterCode()));
        entity.setTemplateCode(StringUtils.defaultString(body.getTemplateCode(), entity.getTemplateCode()));
        entity.setVendorName(StringUtils.defaultString(body.getVendorName(), entity.getVendorName()));
        entity.setProductName(StringUtils.defaultString(body.getProductName(), entity.getProductName()));
        entity.setProtocol(StringUtils.defaultString(body.getProtocol(), entity.getProtocol()));
        entity.setUpstreamModelId(StringUtils.defaultString(upstreamModel, entity.getUpstreamModelId()));
        entity.setEndpointUrl(StringUtils.defaultString(body.getEndpointUrl(), entity.getEndpointUrl()));
    }

    private String normalizeUserModelName(ModelConfigBodyDTO body, String fallback) {
        if (body == null) {
            return StringUtils.defaultString(fallback);
        }
        return firstNonBlank(body.getModelName(), body.getUpstreamModelId(), fallback);
    }

    private String resolveInternalModelCode(ModelConfigBodyDTO body, String modelName, String fallback) {
        if (body == null) {
            return StringUtils.defaultString(fallback);
        }
        return firstNonBlank(
                fallback,
                body.getAdapterCode(),
                body.getProviderCode(),
                body.getTemplateCode(),
                modelName);
    }

    private JSONObject buildStandardRuntimeConfig(ModelConfigBodyDTO body, String provideCode) {
        JSONObject config = new JSONObject();
        if (body == null) {
            return config;
        }
        String userModelName = normalizeUserModelName(body, "");
        String upstreamModel = firstNonBlank(body.getUpstreamModelId(), userModelName);
        setIfNotBlank(config, "type", firstNonBlank(body.getAdapterCode(), body.getProviderCode(), provideCode));
        setIfNotBlank(config, "protocol", body.getProtocol());
        setIfNotBlank(config, "base_url", body.getEndpointUrl());
        setIfNotBlank(config, "api_url", body.getEndpointUrl());
        setIfNotBlank(config, "url", body.getEndpointUrl());
        setIfNotBlank(config, "ws_url", body.getEndpointUrl());
        setIfNotBlank(config, "host", body.getEndpointUrl());
        setIfNotBlank(config, "endpoint_url", body.getEndpointUrl());
        setIfNotBlank(config, "model_name", upstreamModel);
        setIfNotBlank(config, "model", upstreamModel);
        setIfNotBlank(config, "resource_id", upstreamModel);
        mergeConfigMap(config, body.getParams());
        mergeConfigMap(config, body.getSecrets());
        HuoshanDoubleStreamTtsHelper.normalize(config, body.getTemplateCode(), provideCode);
        return config;
    }
    private void mergeConfigMap(JSONObject config, Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        values.forEach((key, value) -> {
            if (StringUtils.isNotBlank(key) && value != null) {
                config.set(key, value);
            }
        });
    }

    private void setIfNotBlank(JSONObject config, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            config.set(key, value);
        }
    }

    private List<ModelConfigEntity> attachRuntimeConfigs(List<ModelConfigEntity> models) {
        if (models == null || models.isEmpty()) {
            return models;
        }
        return models.stream().map(this::attachRuntimeConfig).toList();
    }

    private ModelConfigEntity attachRuntimeConfig(ModelConfigEntity model) {
        if (model == null) {
            return null;
        }
        model.setConfigJson(modelRuntimeAssembler.buildTypedConfig(model));
        return model;
    }

    private void enrichCatalogInfo(ModelConfigDTO dto, ModelConfigEntity entity, ModelDisplayInfo displayInfo) {
        ModelCatalogDTO catalog = modelCatalogService.resolve(entity);
        if (catalog == null) {
            dto.setMigrationStatus("UNSUPPORTED");
            return;
        }
        applyResolvedCatalogToDto(dto, catalog);
        dto.setMigrationStatus(hasMissingCatalogRequiredFields(entity, dto, catalog) ? "NEEDS_REVIEW" : "AUTO");
        if (StringUtils.isBlank(dto.getPlatformName())) {
            dto.setPlatformName(catalog.getVendorName());
        }
        if (StringUtils.isBlank(dto.getAdapterLabel())) {
            dto.setAdapterLabel(StringUtils.defaultIfBlank(catalog.getAdapterName(), displayInfo.providerCode));
        }
    }

    private void applyResolvedCatalogToDto(ModelConfigDTO dto, ModelCatalogDTO catalog) {
        dto.setTemplateCode(catalog.getTemplateCode());
        dto.setVendorName(catalog.getVendorName());
        dto.setProductName(catalog.getProductName());
        dto.setAdapterCode(catalog.getProviderCode());
        dto.setProviderCode(catalog.getProviderCode());
        dto.setCapabilityLabel(catalog.getCapabilityLabel());
        dto.setAccessModeLabel(catalog.getAccessModeLabel());
        dto.setDeploymentLabel(catalog.getDeploymentLabel());
        dto.setProtocolLabel(catalog.getProtocolLabel());
        dto.setProtocol(catalog.getProtocolLabel());
        dto.setUpstreamResourceLabel(catalog.getUpstreamResourceLabel());
        dto.setCredentialSummary(catalog.getCredentialFields());
        dto.setRequiredFields(catalog.getRequiredFields());
        dto.setSetupHints(catalog.getSetupHints());
        dto.setKnownLimitations(catalog.getKnownLimitations());
        dto.setSupportedStatus(catalog.getSupportedStatus());
        dto.setSelectable(catalog.getSelectable());
        dto.setRecommendedFor(catalog.getRecommendedFor());
        dto.setAvoidWhen(catalog.getAvoidWhen());
        dto.setTestContract(catalog.getTestContract());
    }

    private boolean hasMissingCatalogRequiredFields(ModelConfigEntity entity, ModelConfigDTO dto, ModelCatalogDTO catalog) {
        JSONObject configJson = entity == null ? null : entity.getConfigJson();
        for (String field : catalog.getRequiredFields()) {
            if (!hasUsableStandardField(entity, dto, field) && !hasUsableConfigValue(configJson, field)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasUsableStandardField(ModelConfigEntity entity, ModelConfigDTO dto, String field) {
        if (entity == null || StringUtils.isBlank(field)) {
            return false;
        }
        String value = switch (field) {
            case "base_url", "api_url", "url", "ws_url", "host", "endpoint_url" -> entity.getEndpointUrl();
            case "model_name", "model", "resource_id" -> entity.getUpstreamModelId();
            default -> null;
        };
        if (StringUtils.isNotBlank(value) && hasUsableTextValue(value)) {
            return true;
        }
        if (dto == null) {
            return false;
        }
        Object paramValue = dto.getParams() == null ? null : dto.getParams().get(field);
        Object secretValue = dto.getSecrets() == null ? null : dto.getSecrets().get(field);
        return hasUsableObjectValue(paramValue) || hasUsableObjectValue(secretValue);
    }

    private ModelDisplayInfo buildModelDisplayInfo(ModelConfigEntity entity,
            Map<String, ModelProviderDTO> providerCache) {
        ModelDisplayInfo displayInfo = new ModelDisplayInfo();
        if (entity == null) {
            return displayInfo;
        }

        JSONObject configJson = entity.getConfigJson();
        displayInfo.providerCode = getConfigString(configJson, "type");
        ModelProviderDTO provider = findProvider(entity.getModelType(), displayInfo.providerCode, providerCache);
        if (provider != null) {
            displayInfo.providerName = StringUtils.defaultString(provider.getName());
        }
        displayInfo.adapterLabel = StringUtils.defaultIfBlank(displayInfo.providerName, displayInfo.providerCode);
        displayInfo.upstreamModelId = firstConfigString(configJson, UPSTREAM_MODEL_FIELDS);
        displayInfo.endpointSummary = buildEndpointSummary(configJson);
        displayInfo.platformName = inferPlatformName(configJson, provider, displayInfo.providerCode,
                displayInfo.endpointSummary);
        displayInfo.modelTags = buildModelTags(entity, configJson, displayInfo);
        return displayInfo;
    }

    private ModelProviderDTO findProvider(String modelType, String providerCode,
            Map<String, ModelProviderDTO> providerCache) {
        if (StringUtils.isBlank(modelType) || StringUtils.isBlank(providerCode)) {
            return null;
        }
        String cacheKey = modelType + ":" + providerCode;
        if (providerCache.containsKey(cacheKey)) {
            return providerCache.get(cacheKey);
        }
        List<ModelProviderDTO> providerList = modelProviderService.getList(modelType, providerCode);
        ModelProviderDTO provider = CollectionUtil.isEmpty(providerList) ? null : providerList.get(0);
        providerCache.put(cacheKey, provider);
        return provider;
    }

    private String firstConfigString(JSONObject configJson, List<String> keys) {
        if (configJson == null) {
            return "";
        }
        for (String key : keys) {
            String value = getConfigString(configJson, key);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private String getConfigString(JSONObject configJson, String key) {
        if (configJson == null || StringUtils.isBlank(key) || !configJson.containsKey(key)) {
            return "";
        }
        Object value = configJson.get(key);
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return "null".equalsIgnoreCase(text) ? "" : text;
    }

    private String buildEndpointSummary(JSONObject configJson) {
        if (configJson == null) {
            return "";
        }
        String endpoint = firstConfigString(configJson, ENDPOINT_FIELDS);
        String port = getConfigString(configJson, "port");
        if (StringUtils.isNotBlank(port)
                && StringUtils.isNotBlank(endpoint)
                && !StringUtils.contains(endpoint, "://")
                && !StringUtils.contains(endpoint, ":")) {
            endpoint = endpoint + ":" + port;
        }
        return summarizeEndpoint(endpoint);
    }

    private String summarizeEndpoint(String endpoint) {
        if (StringUtils.isBlank(endpoint)) {
            return "";
        }
        String sanitized = StringUtils.substringBefore(StringUtils.substringBefore(endpoint.trim(), "?"), "#");
        try {
            URI uri = new URI(sanitized);
            if (StringUtils.isBlank(uri.getScheme()) || StringUtils.isBlank(uri.getHost())) {
                return sanitized;
            }
            StringBuilder summary = new StringBuilder();
            summary.append(uri.getScheme()).append("://").append(uri.getHost());
            if (uri.getPort() >= 0) {
                summary.append(":").append(uri.getPort());
            }
            if (StringUtils.isNotBlank(uri.getPath())) {
                summary.append(uri.getPath());
            }
            return summary.toString();
        } catch (Exception e) {
            return sanitized;
        }
    }

    private String inferPlatformName(JSONObject configJson, ModelProviderDTO provider, String providerCode,
            String endpointSummary) {
        String explicitPlatform = firstConfigString(configJson, List.of("platform_name", "platform", "vendor"));
        if (StringUtils.isNotBlank(explicitPlatform)) {
            return explicitPlatform;
        }
        String endpoint = StringUtils.lowerCase(endpointSummary);
        if (StringUtils.contains(endpoint, "dashscope.aliyuncs.com")) {
            return "阿里云百炼";
        }
        if (StringUtils.contains(endpoint, "aliyuncs.com")) {
            return "阿里云";
        }
        if (StringUtils.contains(endpoint, "ark.cn-")
                || StringUtils.contains(endpoint, "volces.com")
                || StringUtils.contains(endpoint, "volcengine")
                || StringUtils.contains(endpoint, "bytedance.com")) {
            return "火山引擎";
        }
        if (StringUtils.contains(endpoint, "deepseek.com")) {
            return "DeepSeek";
        }
        if (StringUtils.contains(endpoint, "open.bigmodel.cn")) {
            return "智谱AI";
        }
        if (StringUtils.contains(endpoint, "api.openai.com")) {
            return "OpenAI";
        }
        if (StringUtils.contains(endpoint, "api.groq.com")) {
            return "Groq";
        }
        if (isLocalEndpoint(endpoint)) {
            return "本地服务";
        }

        String normalizedProvider = StringUtils.lowerCase(providerCode);
        if (StringUtils.contains(normalizedProvider, "aliyun") || StringUtils.contains(normalizedProvider, "alibl")) {
            return "阿里云";
        }
        if (StringUtils.contains(normalizedProvider, "doubao")) {
            return "火山引擎";
        }
        if (StringUtils.contains(normalizedProvider, "gemini")) {
            return "Google Gemini";
        }
        if (StringUtils.contains(normalizedProvider, "ollama")) {
            return "Ollama";
        }
        if (StringUtils.contains(normalizedProvider, "xinference")) {
            return "Xinference";
        }
        if (StringUtils.contains(normalizedProvider, "openai")) {
            return "OpenAI兼容";
        }
        return provider == null ? "" : StringUtils.defaultString(provider.getName());
    }

    private List<String> buildModelTags(ModelConfigEntity entity, JSONObject configJson, ModelDisplayInfo displayInfo) {
        Set<String> tags = new LinkedHashSet<>();
        addTag(tags, entity.getModelType());
        if (Integer.valueOf(1).equals(entity.getIsDefault())) {
            addTag(tags, "默认");
        }
        if (Integer.valueOf(1).equals(entity.getIsEnabled())) {
            addTag(tags, "启用");
        } else {
            addTag(tags, "停用");
        }
        if (Integer.valueOf(MODEL_TEST_PASSED).equals(entity.getTestStatus())) {
            addTag(tags, "测试通过");
        } else if (Integer.valueOf(MODEL_TEST_FAILED).equals(entity.getTestStatus())) {
            addTag(tags, "测试失败");
        } else {
            addTag(tags, "未测试");
        }
        String providerCode = StringUtils.lowerCase(displayInfo.providerCode);
        if (StringUtils.contains(providerCode, "openai")) {
            addTag(tags, "OpenAI兼容");
        }
        if (StringUtils.contains(providerCode, "stream")
                || Boolean.TRUE.equals(configJson == null ? null : configJson.getBool("streaming"))) {
            addTag(tags, "流式");
        }
        if (StringUtils.isNotBlank(getConfigString(configJson, "model_dir"))
                || isLocalEndpoint(StringUtils.lowerCase(displayInfo.endpointSummary))) {
            addTag(tags, "本地");
        } else if (StringUtils.isNotBlank(displayInfo.endpointSummary)) {
            addTag(tags, "云端");
        }
        return new ArrayList<>(tags);
    }

    private void addTag(Set<String> tags, String tag) {
        if (StringUtils.isNotBlank(tag)) {
            tags.add(tag);
        }
    }

    private boolean isLocalEndpoint(String endpoint) {
        String value = StringUtils.lowerCase(endpoint);
        return StringUtils.contains(value, "127.0.0.1")
                || StringUtils.contains(value, "localhost")
                || StringUtils.contains(value, "0.0.0.0");
    }

    private static class ModelDisplayInfo {
        private String providerCode = "";
        private String providerName = "";
        private String adapterLabel = "";
        private String upstreamModelId = "";
        private String platformName = "";
        private String endpointSummary = "";
        private List<String> modelTags = List.of();
    }

    /**
     * 清除模型缓存
     */
    private void clearModelCache(String id) {
        redisUtils.delete(RedisKeys.getModelConfigById(id));
        redisUtils.delete(RedisKeys.getModelNameById(id));
    }

    /**
     * 按模型类型清除缓存
     */
    private void clearModelCacheByType(String modelType) {
        List<ModelConfigEntity> entities = modelConfigDao.selectList(
                new QueryWrapper<ModelConfigEntity>().eq("model_type", modelType));
        for (ModelConfigEntity entity : entities) {
            clearModelCache(entity.getId());
        }
    }

    /**
     * 检查智能体配置是否有引用
     */
    private void checkAgentReference(String modelId) {
        List<AgentEntity> agents = agentDao.selectList(
                new QueryWrapper<AgentEntity>()
                        .eq("vad_model_id", modelId)
                        .or()
                        .eq("asr_model_id", modelId)
                        .or()
                        .eq("llm_model_id", modelId)
                        .or()
                        .eq("tts_model_id", modelId)
                        .or()
                        .eq("mem_model_id", modelId)
                        .or()
                        .eq("vllm_model_id", modelId)
                        .or()
                        .eq("intent_model_id", modelId));
        if (!agents.isEmpty()) {
            String agentNames = agents.stream()
                    .map(AgentEntity::getAgentName)
                    .collect(Collectors.joining("、"));
            throw new RenException(ErrorCode.MODEL_REFERENCED_BY_AGENT, agentNames);
        }
    }

    private void publishRuntimeRefreshForModel(String modelId, String reason) {
        List<AgentEntity> agents = agentDao.selectList(
                new QueryWrapper<AgentEntity>()
                        .select("id")
                        .and(query -> query.eq("vad_model_id", modelId)
                                .or().eq("asr_model_id", modelId)
                                .or().eq("llm_model_id", modelId)
                                .or().eq("slm_model_id", modelId)
                                .or().eq("tts_model_id", modelId)
                                .or().eq("mem_model_id", modelId)
                                .or().eq("vllm_model_id", modelId)
                                .or().eq("intent_model_id", modelId)));
        List<String> agentIds = agents.stream()
                .map(AgentEntity::getId)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        if (!agentIds.isEmpty()) {
            eventPublisher.publishEvent(new AgentRuntimeConfigRefreshEvent(agentIds, reason));
        }
    }

    /**
     * 检查意图识别配置是否有引用
     */
    private void checkIntentConfigReference(String modelId) {
        ModelConfigEntity modelConfig = attachRuntimeConfig(modelConfigDao.selectById(modelId));
        if (modelConfig != null
                && "LLM".equals(modelConfig.getModelType() == null ? null : modelConfig.getModelType().toUpperCase())) {
            if (modelRuntimeAssembler.hasRuntimeReference("Intent", modelId)) {
                throw new RenException(ErrorCode.LLM_REFERENCED_BY_INTENT);
            }
        }
    }

    /**
     * 获取符合条件的TTS平台列表
     */
    @Override
    public List<Map<String, Object>> getTtsPlatformList() {
        return modelConfigDao.getTtsPlatformList();
    }

    // -- Shared utility methods (also present in ModelTestServiceImpl) --

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private static boolean hasUsableConfigValue(JSONObject configJson, String field) {
        if (configJson == null) {
            return false;
        }
        Object value = configJson.get(field);
        return hasUsableObjectValue(value);
    }

    private static boolean hasUsableObjectValue(Object value) {
        if (value == null) {
            return false;
        }
        String stringValue = StringUtils.trim(String.valueOf(value));
        return hasUsableTextValue(stringValue);
    }

    private static boolean hasUsableTextValue(String stringValue) {
        return StringUtils.isNotBlank(stringValue) && !"null".equalsIgnoreCase(stringValue) && !isPlaceholderSecret(stringValue);
    }

    private static boolean isPlaceholderSecret(String value) {
        String lowerValue = StringUtils.defaultString(value).toLowerCase();
        return lowerValue.contains("你的")
                || lowerValue.contains("请输入")
                || lowerValue.contains("placeholder")
                || lowerValue.contains("your ")
                || lowerValue.contains("your_")
                || lowerValue.contains("sk-xxx")
                || lowerValue.contains("xxxx")
                || lowerValue.contains("***");
    }

    /**
     * 根据模型类型获取所有启用的模型配置
     */
    @Override
    public List<ModelConfigEntity> getEnabledModelsByType(String modelType) {
        if (StringUtils.isBlank(modelType)) {
            return null;
        }

        List<ModelConfigEntity> entities = modelConfigDao.selectList(
                new QueryWrapper<ModelConfigEntity>()
                        .eq("model_type", modelType)
                        .eq("is_enabled", 1)
                        .eq("test_status", MODEL_TEST_PASSED)
                        .orderByAsc("sort"));

        return attachRuntimeConfigs(entities);
    }

}
