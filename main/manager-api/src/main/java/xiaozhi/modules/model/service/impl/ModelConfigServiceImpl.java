package xiaozhi.modules.model.service.impl;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
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
import lombok.extern.slf4j.Slf4j;

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
    private final RestTemplate restTemplate;
    private final Environment environment;

    private static final int MODEL_TEST_UNTESTED = 0;
    private static final int MODEL_TEST_PASSED = 1;
    private static final int MODEL_TEST_FAILED = 2;
    private static final Set<String> LOCAL_TESTABLE_TYPES = new HashSet<>(List.of(
            "silero", "nomem", "mem_local_short",
            "mem_report_only", "nointent", "function_call"));
    private static final String LLM_TEST_PROMPT = "请只回复 OK";
    private static final String TTS_TEST_TEXT = "你好，我是小智，语音合成测试成功。";
    private static final String VLLM_TEST_QUESTION = "请用一句话描述这张图片。";
    private static final String STANDARD_ASR_TEST_AUDIO = "wakeup_words_short.wav";
    private static final int DOUBAO_ASR_MAX_RESULT_MESSAGES = 20;
    private static final int SINGLE_ASR_BENCHMARK_TIMEOUT_SECONDS = 120;
    private static final String TEST_LEVEL_REAL_INFERENCE = "REAL_INFERENCE";
    private static final String TEST_LEVEL_CONNECTIVITY = "CONNECTIVITY";
    private static final String TEST_LEVEL_RUNTIME_CONFIG = "RUNTIME_CONFIG";
    private static final String TEST_LEVEL_CONFIG_ONLY = "CONFIG_ONLY";
    private static final String TEST_LEVEL_UNSUPPORTED = "UNSUPPORTED";
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
        ModelTestResultDTO result = doTestModel(modelConfig);
        if (result.getResponseTimeMs() == null && shouldShowModelTestResponseTime(result)) {
            result.setResponseTimeMs(System.currentTimeMillis() - startTime);
        }
        Date testTime = new Date();
        result.setTestTime(testTime);
        boolean verified = isVerifiedModelTest(result);
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

    private ModelTestResultDTO doTestModel(ModelConfigEntity modelConfig) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        if (modelConfig.getConfigJson() == null) {
            result.setSuccess(false);
            result.setMessage("模型配置为空");
            markModelTestLevel(result, TEST_LEVEL_UNSUPPORTED, false);
            return result;
        }

        JSONObject configJson = modelConfig.getConfigJson();
        String type = StringUtils.lowerCase(configJson.getStr("type"));
        if (StringUtils.isBlank(type)) {
            result.setSuccess(false);
            result.setMessage("模型配置缺少 type");
            markModelTestLevel(result, TEST_LEVEL_UNSUPPORTED, false);
            return result;
        }

        String modelType = StringUtils.defaultString(modelConfig.getModelType()).toUpperCase();
        try {
            String testLevel = getModelTestLevel(modelConfig);
            if ("LLM".equals(modelType) || "SLM".equals(modelType)) {
                result = testLanguageModel(configJson, type);
                return applyModelTestSemantics(result, testLevel);
            }
            if ("VLLM".equals(modelType)) {
                result = testVisionModel(configJson, type);
                return applyModelTestSemantics(result, testLevel);
            }
            if ("ASR".equals(modelType)) {
                result = testAsrModel(configJson, type);
                return applyModelTestSemantics(result, testLevel);
            }
            if ("TTS".equals(modelType)) {
                result = testTtsModel(configJson, type);
                return applyModelTestSemantics(result, testLevel);
            }
            if ("INTENT".equals(modelType) && "intent_llm".equals(type)) {
                result = testIntentModel(configJson);
                return applyModelTestSemantics(result, testLevel);
            }
            if (LOCAL_TESTABLE_TYPES.contains(type)) {
                result = validateBasicModelConfig(configJson, type, modelType);
                return applyModelTestSemantics(result, testLevel);
            }
            result = validateBasicModelConfig(configJson, type, modelType);
            return applyModelTestSemantics(result, testLevel);
        } catch (Exception e) {
            log.warn("模型测试失败，modelId: {}", modelConfig.getId(), e);
            result.setSuccess(false);
            result.setMessage("模型测试失败：" + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            result.setTestInput("模型类型：" + modelType + "\n供应器类型：" + type);
            result.setTestOutput(result.getMessage());
            markModelTestLevel(result, TEST_LEVEL_UNSUPPORTED, false);
            return result;
        }
    }

    private ModelTestResultDTO applyModelTestSemantics(ModelTestResultDTO result, String testLevel) {
        if (result == null) {
            result = new ModelTestResultDTO();
            result.setSuccess(false);
            result.setMessage("模型测试没有返回结果");
            result.setTestOutput(result.getMessage());
        }
        boolean rawSuccess = Boolean.TRUE.equals(result.getSuccess());
        boolean verified = rawSuccess
                && (TEST_LEVEL_REAL_INFERENCE.equals(testLevel) || TEST_LEVEL_CONNECTIVITY.equals(testLevel)
                        || TEST_LEVEL_RUNTIME_CONFIG.equals(testLevel));
        markModelTestLevel(result, testLevel, verified);
        if (rawSuccess && !verified) {
            result.setSuccess(false);
            result.setMessage("仅完成配置校验，未执行真实模型调用，不能标记为测试通过；"
                    + StringUtils.defaultString(result.getMessage()));
        }
        return result;
    }

    private boolean isVerifiedModelTest(ModelTestResultDTO result) {
        return result != null && Boolean.TRUE.equals(result.getVerified());
    }

    private boolean shouldShowModelTestResponseTime(ModelTestResultDTO result) {
        if (result == null) {
            return false;
        }
        return TEST_LEVEL_REAL_INFERENCE.equals(result.getTestLevel())
                || TEST_LEVEL_CONNECTIVITY.equals(result.getTestLevel());
    }

    private void markModelTestLevel(ModelTestResultDTO result, String testLevel, boolean verified) {
        result.setTestLevel(testLevel);
        result.setTestLevelText(getModelTestLevelText(testLevel));
        result.setVerified(verified);
        if (!TEST_LEVEL_REAL_INFERENCE.equals(testLevel) && !TEST_LEVEL_CONNECTIVITY.equals(testLevel)) {
            result.setResponseTimeMs(null);
        }
    }

    private String getModelTestLevel(ModelConfigEntity modelConfig) {
        if (modelConfig == null || modelConfig.getConfigJson() == null) {
            return TEST_LEVEL_UNSUPPORTED;
        }
        String modelType = StringUtils.defaultString(modelConfig.getModelType()).toUpperCase();
        String type = StringUtils.lowerCase(modelConfig.getConfigJson().getStr("type"));
        if (StringUtils.isBlank(type)) {
            return TEST_LEVEL_UNSUPPORTED;
        }
        if (("LLM".equals(modelType) || "SLM".equals(modelType))
                && ("openai".equals(type) || "ollama".equals(type))) {
            return TEST_LEVEL_REAL_INFERENCE;
        }
        if ("VLLM".equals(modelType) && "openai".equals(type)) {
            return TEST_LEVEL_REAL_INFERENCE;
        }
        if ("ASR".equals(modelType) && "openai".equals(type)) {
            return TEST_LEVEL_REAL_INFERENCE;
        }
        if ("TTS".equals(modelType) && "openai".equals(type)) {
            return TEST_LEVEL_REAL_INFERENCE;
        }
        if ("TTS".equals(modelType)
                && (CollectionUtil.isNotEmpty(getRequiredFields(modelType, type))
                        || CollectionUtil.isNotEmpty(getRequiredAnyFieldGroups(modelType, type))
                        || CollectionUtil.isNotEmpty(getMissingAlternativeRequirements(modelConfig.getConfigJson(), modelType, type)))) {
            return TEST_LEVEL_REAL_INFERENCE;
        }
        if ("ASR".equals(modelType) && "doubao_stream".equals(type)) {
            return TEST_LEVEL_REAL_INFERENCE;
        }
        if ("ASR".equals(modelType)
                && (CollectionUtil.isNotEmpty(getRequiredFields(modelType, type))
                        || CollectionUtil.isNotEmpty(getRequiredAnyFieldGroups(modelType, type))
                        || CollectionUtil.isNotEmpty(getMissingAlternativeRequirements(modelConfig.getConfigJson(), modelType, type)))) {
            return TEST_LEVEL_REAL_INFERENCE;
        }
        if ("INTENT".equals(modelType) && "intent_llm".equals(type)) {
            return TEST_LEVEL_CONFIG_ONLY;
        }
        if ("VAD".equals(modelType) && "silero".equals(type)) {
            return TEST_LEVEL_RUNTIME_CONFIG;
        }
        if (LOCAL_TESTABLE_TYPES.contains(type)) {
            return TEST_LEVEL_CONFIG_ONLY;
        }
        if (CollectionUtil.isNotEmpty(getRequiredFields(modelType, type))
                || CollectionUtil.isNotEmpty(getRequiredAnyFieldGroups(modelType, type))
                || CollectionUtil.isNotEmpty(getMissingAlternativeRequirements(modelConfig.getConfigJson(), modelType, type))) {
            return TEST_LEVEL_CONFIG_ONLY;
        }
        return TEST_LEVEL_UNSUPPORTED;
    }

    private String getModelTestLevelText(String testLevel) {
        if (TEST_LEVEL_REAL_INFERENCE.equals(testLevel)) {
            return "真实推理";
        }
        if (TEST_LEVEL_CONNECTIVITY.equals(testLevel)) {
            return "连通性";
        }
        if (TEST_LEVEL_RUNTIME_CONFIG.equals(testLevel)) {
            return "运行时配置";
        }
        if (TEST_LEVEL_CONFIG_ONLY.equals(testLevel)) {
            return "配置校验";
        }
        return "未实测";
    }

    private ModelTestResultDTO testIntentModel(JSONObject configJson) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String llmModelId = configJson.getStr("llm");
        if (StringUtils.isBlank(llmModelId)) {
            result.setSuccess(false);
            result.setMessage("意图识别配置缺少关联 LLM");
            result.setTestInput("关联 LLM：未配置");
            result.setTestOutput(result.getMessage());
            return result;
        }
        ModelConfigEntity llm = attachRuntimeConfig(modelConfigDao.selectById(llmModelId));
        if (llm == null || llm.getTestStatus() == null || llm.getTestStatus() != MODEL_TEST_PASSED) {
            result.setSuccess(false);
            result.setMessage("关联 LLM 尚未测试通过");
            result.setTestInput("关联 LLM：" + llmModelId);
            result.setTestOutput(result.getMessage());
            return result;
        }
        result.setSuccess(true);
        result.setMessage("关联 LLM 已测试通过");
        result.setTestInput("关联 LLM：" + llmModelId);
        result.setTestOutput("关联 LLM 已通过测试，可用于意图识别");
        return result;
    }

    private ModelTestResultDTO testAsrModel(JSONObject configJson, String type) {
        ModelTestResultDTO configCheck = validateBasicModelConfig(configJson, type, "ASR");
        if (!Boolean.TRUE.equals(configCheck.getSuccess())) {
            return configCheck;
        }
        // ASR 单模型测试必须走 xiaozhi-server 运行时。manager-api Docker 镜像不携带
        // xiaozhi-server/config/assets，不能在 Java 容器内直接读取测试音频。
        return testAsrModelViaRuntimeBenchmark(configJson, type);
    }

    private ModelTestResultDTO testTtsModel(JSONObject configJson, String type) {
        if ("openai".equals(type)) {
            return testOpenAiTtsModel(configJson);
        }
        ModelTestResultDTO configCheck = validateBasicModelConfig(configJson, type, "TTS");
        if (!Boolean.TRUE.equals(configCheck.getSuccess())) {
            return configCheck;
        }
        return testTtsModelViaRuntimeBenchmark(configJson, type);
    }

    private ModelTestResultDTO testLanguageModel(JSONObject configJson, String type) {
        if ("openai".equals(type)) {
            return testOpenAiCompatibleModel(configJson);
        }
        if ("ollama".equals(type)) {
            return testOllamaModel(configJson);
        }
        ModelTestResultDTO result = new ModelTestResultDTO();
        return validateBasicModelConfig(configJson, type, "LLM");
    }

    private ModelTestResultDTO testVisionModel(JSONObject configJson, String type) {
        if ("openai".equals(type)) {
            return testOpenAiVisionModel(configJson);
        }
        ModelTestResultDTO result = new ModelTestResultDTO();
        return validateBasicModelConfig(configJson, type, "VLLM");
    }

    private boolean isDeepSeek(String baseUrl) {
        return StringUtils.containsIgnoreCase(baseUrl, "api.deepseek.com");
    }

    private String normalizeThinkingType(Object value) {
        if (value == null) {
            return null;
        }
        String thinkingType;
        if (value instanceof JSONObject thinking) {
            thinkingType = thinking.getStr("type");
        } else if (value instanceof Map<?, ?> thinking) {
            Object type = thinking.get("type");
            thinkingType = type == null ? null : String.valueOf(type);
        } else if (value instanceof Boolean enabled) {
            thinkingType = enabled ? "enabled" : "disabled";
        } else {
            thinkingType = String.valueOf(value);
        }

        thinkingType = StringUtils.lowerCase(StringUtils.trimToEmpty(thinkingType));
        thinkingType = switch (thinkingType) {
            case "enable", "true", "on", "1" -> "enabled";
            case "disable", "false", "off", "0" -> "disabled";
            default -> thinkingType;
        };
        return "enabled".equals(thinkingType) || "disabled".equals(thinkingType) ? thinkingType : null;
    }

    private String normalizeReasoningEffort(String value) {
        String effort = StringUtils.lowerCase(StringUtils.trimToEmpty(value));
        effort = switch (effort) {
            case "low", "medium" -> "high";
            case "xhigh" -> "max";
            default -> effort;
        };
        return "high".equals(effort) || "max".equals(effort) ? effort : null;
    }

    private void applyDeepSeekOptions(String baseUrl, JSONObject configJson, Map<String, Object> requestBody) {
        if (!isDeepSeek(baseUrl)) {
            return;
        }

        String thinkingType = normalizeThinkingType(configJson.get("thinking"));
        if (thinkingType != null) {
            requestBody.put("thinking", Map.of("type", thinkingType));
        }

        String reasoningEffort = normalizeReasoningEffort(configJson.getStr("reasoning_effort"));
        if ("enabled".equals(thinkingType) && reasoningEffort != null) {
            requestBody.put("reasoning_effort", reasoningEffort);
        }
    }

    private ModelTestResultDTO testOpenAiCompatibleModel(JSONObject configJson) {
        // TODO: migrate to LlmClient
        ModelTestResultDTO result = new ModelTestResultDTO();
        String baseUrl = configJson.getStr("base_url");
        String model = configJson.getStr("model_name");
        String apiKey = StringUtils.defaultIfBlank(configJson.getStr("api_key"), configJson.getStr("api_password"));
        if (StringUtils.isAnyBlank(baseUrl, model, apiKey) || isPlaceholderSecret(apiKey)) {
            result.setSuccess(false);
            result.setMessage("OpenAI 兼容模型缺少 base_url、model_name 或有效密钥");
            result.setTestInput("模型：" + StringUtils.defaultString(model, "-")
                    + "\n提示词：" + LLM_TEST_PROMPT);
            result.setTestOutput(result.getMessage());
            return result;
        }

        String apiUrl = buildChatCompletionsUrl(baseUrl);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", LLM_TEST_PROMPT)));
        if (!isDeepSeek(baseUrl)) {
            requestBody.put("temperature", 0);
        }
        requestBody.put("max_tokens", 8);
        applyDeepSeekOptions(baseUrl, configJson, requestBody);
        result.setTestInput("接口：" + apiUrl + "\n模型：" + model + "\n提示词：" + LLM_TEST_PROMPT);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);
        long startTime = System.currentTimeMillis();
        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl, HttpMethod.POST, new HttpEntity<>(requestBody, headers), String.class);
        result.setResponseTimeMs(System.currentTimeMillis() - startTime);
        result.setTestOutput(StringUtils.defaultString(response.getBody()));
        if (!response.getStatusCode().is2xxSuccessful()) {
            result.setSuccess(false);
            result.setMessage("模型接口返回非成功状态：" + response.getStatusCode());
            return result;
        }
        JSONObject responseJson = JSONUtil.parseObj(response.getBody());
        JSONArray choices = responseJson.getJSONArray("choices");
        String answer = extractOpenAiChoiceText(choices);
        if (StringUtils.isNotBlank(answer)) {
            result.setTestOutput(answer);
        }
        result.setSuccess(choices != null && !choices.isEmpty());
        result.setMessage(Boolean.TRUE.equals(result.getSuccess()) ? "模型连通性测试通过" : "模型接口响应缺少 choices");
        return result;
    }

    private ModelTestResultDTO testOpenAiAsrModel(JSONObject configJson) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String apiUrl = StringUtils.defaultIfBlank(configJson.getStr("base_url"), configJson.getStr("api_url"));
        String model = configJson.getStr("model_name");
        String apiKey = configJson.getStr("api_key");
        if (StringUtils.isAnyBlank(apiUrl, model, apiKey) || isPlaceholderSecret(apiKey)) {
            result.setSuccess(false);
            result.setMessage("OpenAI ASR 缺少 base_url/api_url、model_name 或有效密钥");
            result.setTestInput("接口：" + StringUtils.defaultString(apiUrl, "-")
                    + "\n模型：" + StringUtils.defaultString(model, "-")
                    + "\n测试音频：main/xiaozhi-server/config/assets/*.wav");
            result.setTestOutput(result.getMessage());
            return result;
        }

        try {
            Path audioPath = findAsrTestAudioPath();
            if (audioPath == null) {
                result.setSuccess(false);
                result.setMessage("未找到 ASR 测试音频，期望来源：main/xiaozhi-server/config/assets/*.wav");
                result.setTestInput("测试音频：main/xiaozhi-server/config/assets/*.wav");
                result.setTestOutput(result.getMessage());
                return result;
            }
            result.setTestInput("接口：" + apiUrl + "\n模型：" + model + "\n音频：" + audioPath);
            result.setTestAudioDataUrl(buildAudioDataUrl(audioPath));

            byte[] audioBytes = Files.readAllBytes(audioPath);
            ByteArrayResource audioResource = new ByteArrayResource(audioBytes) {
                @Override
                public String getFilename() {
                    return audioPath.getFileName().toString();
                }
            };
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("model", model);
            body.add("file", audioResource);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.set("Authorization", "Bearer " + apiKey);
            long startTime = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            result.setResponseTimeMs(System.currentTimeMillis() - startTime);
            result.setTestOutput(StringUtils.defaultString(response.getBody()));
            if (!response.getStatusCode().is2xxSuccessful()) {
                result.setSuccess(false);
                result.setMessage("ASR 接口返回非成功状态：" + response.getStatusCode());
                return result;
            }
            JSONObject responseJson = JSONUtil.parseObj(response.getBody());
            String text = responseJson.getStr("text");
            if (StringUtils.isNotBlank(text)) {
                result.setTestOutput(text);
            }
            result.setSuccess(StringUtils.isNotBlank(text));
            result.setMessage(Boolean.TRUE.equals(result.getSuccess())
                    ? "ASR 真实音频测试通过，样本：" + audioPath.toString()
                    : "ASR 接口响应缺少 text，样本：" + audioPath.toString());
            return result;
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("ASR 真实音频测试失败：" + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            if (StringUtils.isBlank(result.getTestOutput())) {
                result.setTestOutput(result.getMessage());
            }
            return result;
        }
    }

    private ModelTestResultDTO testDoubaoStreamAsrModel(JSONObject configJson) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String appid = configJson.getStr("appid");
        String accessToken = configJson.getStr("access_token");
        String resourceId = configJson.getStr("resource_id");
        if (StringUtils.isAnyBlank(appid, accessToken, resourceId)
                || isPlaceholderSecret(appid)
                || isPlaceholderSecret(accessToken)) {
            result.setSuccess(false);
            result.setMessage("豆包流式 ASR 缺少有效 appid、access_token 或 resource_id");
            result.setTestInput("resource_id：" + StringUtils.defaultString(resourceId, "-")
                    + "\n测试内容：真实音频流式识别");
            result.setTestOutput(result.getMessage());
            return result;
        }

        boolean enableMultilingual = Boolean.parseBoolean(StringUtils.defaultString(configJson.getStr("enable_multilingual"), "false"));
        String wsUrl = enableMultilingual
                ? "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream"
                : "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async";

        Path audioPath = findAsrTestAudioPath();
        if (audioPath == null) {
            result.setSuccess(false);
            result.setMessage("未找到 ASR 测试音频，期望来源：main/xiaozhi-server/config/assets/*.wav");
            result.setTestInput("WebSocket：" + wsUrl + "\nresource_id：" + resourceId
                    + "\n测试内容：真实音频流式识别");
            result.setTestOutput(result.getMessage());
            return result;
        }

        result.setTestInput("WebSocket：" + wsUrl + "\nresource_id：" + resourceId
                + "\n音频：" + audioPath
                + "\n测试内容：真实音频流式识别");
        result.setTestAudioDataUrl(buildAudioDataUrl(audioPath));

        try {
            BinaryResponseQueueListener listener = new BinaryResponseQueueListener();
            long startTime = System.currentTimeMillis();
            WebSocket webSocket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .header("X-Api-App-Key", appid)
                    .header("X-Api-Access-Key", accessToken)
                    .header("X-Api-Resource-Id", resourceId)
                    .header("X-Api-Connect-Id", UUID.randomUUID().toString())
                    .buildAsync(URI.create(wsUrl), listener)
                    .get(10, TimeUnit.SECONDS);

            byte[] request = buildDoubaoStreamAsrInitRequest(configJson, appid, accessToken, enableMultilingual);
            webSocket.sendBinary(ByteBuffer.wrap(request), true).get(10, TimeUnit.SECONDS);
            JSONObject initResponse = parseDoubaoStreamAsrResponse(listener.poll(10, TimeUnit.SECONDS));
            result.setTestOutput(initResponse.toStringPretty());

            if (initResponse.containsKey("code") && initResponse.getInt("code") != 1000) {
                result.setSuccess(false);
                result.setMessage("豆包流式 ASR 初始化失败：" + extractDoubaoErrorMessage(initResponse));
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test finished");
                return result;
            }

            JSONObject payload = initResponse.getJSONObject("payload_msg");
            if (payload != null && payload.containsKey("code") && payload.getInt("code") != 1000 && payload.getInt("code") != 0) {
                result.setSuccess(false);
                result.setMessage("豆包流式 ASR 初始化失败：" + extractDoubaoErrorMessage(initResponse));
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test finished");
                return result;
            }
            if (payload != null && payload.containsKey("error")) {
                result.setSuccess(false);
                result.setMessage("豆包流式 ASR 初始化失败：" + payload.getStr("error"));
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test finished");
                return result;
            }

            byte[] pcmBytes = readAsrTestPcmBytes(audioPath);
            byte[] audioRequest = buildDoubaoStreamAsrAudioRequest(pcmBytes, true);
            webSocket.sendBinary(ByteBuffer.wrap(audioRequest), true).get(10, TimeUnit.SECONDS);

            List<String> responseSummaries = new ArrayList<>();
            String recognizedText = "";
            for (int i = 0; i < DOUBAO_ASR_MAX_RESULT_MESSAGES; i++) {
                JSONObject asrResponse = parseDoubaoStreamAsrResponse(listener.poll(10, TimeUnit.SECONDS));
                responseSummaries.add(asrResponse.toStringPretty());
                recognizedText = extractDoubaoRecognizedText(asrResponse);
                if (StringUtils.isNotBlank(recognizedText)) {
                    break;
                }
            }
            result.setResponseTimeMs(System.currentTimeMillis() - startTime);
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test finished");

            if (StringUtils.isNotBlank(recognizedText)) {
                result.setSuccess(true);
                result.setMessage("豆包流式 ASR 真实音频测试通过，样本：" + audioPath);
                result.setTestOutput(recognizedText);
                return result;
            }

            result.setSuccess(false);
            result.setMessage("豆包流式 ASR 未返回识别文本，样本：" + audioPath);
            result.setTestOutput(String.join("\n\n", responseSummaries));
            return result;
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("豆包流式 ASR 真实音频测试失败：" + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            if (StringUtils.isBlank(result.getTestOutput())) {
                result.setTestOutput(result.getMessage());
            }
            return result;
        }
    }

    private ModelTestResultDTO testAsrModelViaRuntimeBenchmark(JSONObject configJson, String type) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String providerType = StringUtils.defaultString(type);
        String modelName = firstNonBlank(configJson, "model_name", "model", "resource_id", "host");
        result.setTestInput("供应器类型：" + providerType
                + "\n模型：" + StringUtils.defaultIfBlank(modelName, "-")
                + "\n测试方式：调用 xiaozhi-server 运行时 provider，读取内置音频并执行 1 轮真实 ASR 识别");

        try {
            JSONObject payload = new JSONObject();
            payload.set("task_id", "single-asr-test-" + UUID.randomUUID());
            payload.set("model_type", "ASR");
            payload.set("iterations", 1);
            payload.set("concurrency", 1);

            JSONObject model = new JSONObject();
            model.set("id", "single-asr-test");
            model.set("model_name", StringUtils.defaultIfBlank(modelName, providerType));
            model.set("model_type", "ASR");
            model.set("provider_type", providerType);
            model.set("config", configJson);
            model.set("test_audio_name", STANDARD_ASR_TEST_AUDIO);
            model.set("include_audio_preview", true);
            JSONArray models = new JSONArray();
            models.add(model);
            payload.set("models", models);

            long startTime = System.currentTimeMillis();
            JSONObject response = callRuntimeBenchmark(payload);
            result.setResponseTimeMs(System.currentTimeMillis() - startTime);

            JSONObject benchmarkResult = firstJsonObject(response.getJSONArray("results"));
            if (benchmarkResult == null) {
                result.setSuccess(false);
                result.setMessage("ASR 真实音频测试没有返回结果");
                result.setTestOutput(response.toStringPretty());
                return result;
            }

            JSONObject sample = firstJsonObject(benchmarkResult.getJSONArray("samples"));
            if (sample == null) {
                result.setSuccess(false);
                result.setMessage(StringUtils.defaultIfBlank(
                        benchmarkResult.getStr("error_summary"),
                        "ASR 真实音频测试没有返回样本"));
                result.setTestOutput(benchmarkResult.toStringPretty());
                return result;
            }

            String recognizedText = extractRuntimeBenchmarkRecognizedText(sample);
            Long latencyMs = sample.getLong("latency_ms");
            if (latencyMs != null) {
                result.setResponseTimeMs(latencyMs);
            }
            result.setFirstTextMs(sample.getLong("ttft_ms"));
            result.setTestInput(buildRuntimeBenchmarkAsrInput(sample, providerType));
            result.setTestAudioDataUrl(buildRuntimeBenchmarkAsrAudioDataUrl(sample));
            result.setTestOutput(StringUtils.defaultIfBlank(recognizedText, sample.toStringPretty()));
            boolean success = Boolean.TRUE.equals(sample.getBool("success")) && StringUtils.isNotBlank(recognizedText);
            result.setSuccess(success);
            result.setMessage(success
                    ? "ASR 真实音频测试通过"
                    : StringUtils.defaultIfBlank(sample.getStr("message"), "ASR 真实音频测试未返回识别文本"));
            return result;
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("ASR 真实音频测试失败：" + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            result.setTestOutput(result.getMessage());
            return result;
        }
    }

    private JSONObject callRuntimeBenchmark(JSONObject payload) {
        String baseUrl = resolveXiaozhiServerHttpBaseUrl();
        String url = baseUrl + "/benchmark/models";
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(SINGLE_ASR_BENCHMARK_TIMEOUT_SECONDS));
        RestTemplate benchmarkRestTemplate = new RestTemplate(factory);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<String> response = benchmarkRestTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(payload.toString(), headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RenException("xiaozhi-server 运行时真实测试接口返回异常：" + response.getStatusCode());
            }
            return JSONUtil.parseObj(response.getBody());
        } catch (ResourceAccessException e) {
            throw new RenException("无法连接 xiaozhi-server 运行时真实测试接口：" + url
                    + "。请先确认 xiaozhi-server 已启动且 HTTP 端口可访问；源码开发可执行 `scripts/dev status server`。原始错误："
                    + e.getMessage(), e);
        }
    }

    private String resolveXiaozhiServerHttpBaseUrl() {
        String configured = environment.getProperty("xiaozhi.server.http-base-url");
        if (StringUtils.isNotBlank(configured)) {
            return StringUtils.removeEnd(configured.trim(), "/");
        }
        return "http://127.0.0.1:" + resolveXiaozhiServerHttpPort();
    }

    private int resolveXiaozhiServerHttpPort() {
        String value = environment.getProperty("xiaozhi.server.http-port");
        if (StringUtils.isNumeric(value)) {
            return Integer.parseInt(value);
        }
        return 8003;
    }

    private JSONObject firstJsonObject(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return null;
        }
        return JSONUtil.parseObj(array.get(0));
    }

    private String buildRuntimeBenchmarkAsrInput(JSONObject sample, String providerType) {
        JSONObject inputDetail = sample.getJSONObject("input_detail");
        String audioName = inputDetail == null ? sample.getStr("input_summary") : inputDetail.getStr("audio_name");
        String reference = inputDetail == null ? null : inputDetail.getStr("reference_text");
        String audioBytes = inputDetail == null ? null : StringUtils.defaultString(inputDetail.getStr("audio_bytes"));
        return "供应器类型：" + providerType
                + "\n音频：" + StringUtils.defaultIfBlank(audioName, "-")
                + "\n音频格式：pcm"
                + (StringUtils.isNotBlank(audioBytes) ? "\n音频大小：" + audioBytes + " bytes" : "")
                + (StringUtils.isNotBlank(reference) ? "\n参考文本：" + reference : "")
                + "\n测试方式：xiaozhi-server 运行时 provider 真实识别";
    }

    private String extractRuntimeBenchmarkRecognizedText(JSONObject sample) {
        JSONObject outputDetail = sample.getJSONObject("output_detail");
        if (outputDetail != null && StringUtils.isNotBlank(outputDetail.getStr("recognized_text"))) {
            return outputDetail.getStr("recognized_text");
        }
        return StringUtils.defaultString(sample.getStr("output_summary"));
    }

    private String buildRuntimeBenchmarkAsrAudioDataUrl(JSONObject sample) {
        JSONObject inputDetail = sample.getJSONObject("input_detail");
        String runtimeAudioDataUrl = inputDetail == null ? "" : inputDetail.getStr("audio_data_url");
        if (StringUtils.isNotBlank(runtimeAudioDataUrl)) {
            return runtimeAudioDataUrl;
        }
        String audioName = inputDetail == null ? sample.getStr("input_summary") : inputDetail.getStr("audio_name");
        if (StringUtils.isBlank(audioName)) {
            return "";
        }
        Path audioPath = findFirstExistingPath(List.of(
                "../xiaozhi-server/config/assets/" + audioName,
                "main/xiaozhi-server/config/assets/" + audioName));
        return buildAudioDataUrl(audioPath);
    }

    private ModelTestResultDTO testTtsModelViaRuntimeBenchmark(JSONObject configJson, String type) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String providerType = StringUtils.defaultString(type);
        String modelName = firstNonBlank(configJson, "model", "model_name", "resource_id", "voice", "speaker",
                "voice_id", "api_url", "url");
        result.setTestInput("供应器类型：" + providerType
                + "\n模型/音色：" + StringUtils.defaultIfBlank(modelName, "-")
                + "\n测试文本：" + TTS_TEST_TEXT
                + "\n测试方式：调用 xiaozhi-server 运行时 provider 执行 1 轮真实 TTS 合成");

        try {
            JSONObject payload = new JSONObject();
            payload.set("task_id", "single-tts-test-" + UUID.randomUUID());
            payload.set("model_type", "TTS");
            payload.set("iterations", 1);
            payload.set("concurrency", 1);

            JSONObject model = new JSONObject();
            model.set("id", "single-tts-test");
            model.set("model_name", StringUtils.defaultIfBlank(modelName, providerType));
            model.set("model_type", "TTS");
            model.set("provider_type", providerType);
            model.set("config", configJson);
            JSONArray models = new JSONArray();
            models.add(model);
            payload.set("models", models);

            long startTime = System.currentTimeMillis();
            JSONObject response = callRuntimeBenchmark(payload);
            result.setResponseTimeMs(System.currentTimeMillis() - startTime);

            JSONObject benchmarkResult = firstJsonObject(response.getJSONArray("results"));
            if (benchmarkResult == null) {
                result.setSuccess(false);
                result.setMessage("TTS 真实合成测试没有返回结果");
                result.setTestOutput(response.toStringPretty());
                return result;
            }

            JSONObject sample = firstJsonObject(benchmarkResult.getJSONArray("samples"));
            if (sample == null) {
                result.setSuccess(false);
                result.setMessage(StringUtils.defaultIfBlank(
                        benchmarkResult.getStr("error_summary"),
                        "TTS 真实合成测试没有返回样本"));
                result.setTestOutput(benchmarkResult.toStringPretty());
                return result;
            }

            Long latencyMs = sample.getLong("latency_ms");
            if (latencyMs != null) {
                result.setResponseTimeMs(latencyMs);
            }
            result.setFirstAudioMs(sample.getLong("first_audio_ms"));
            result.setTestInput(buildRuntimeBenchmarkTtsInput(sample, providerType));
            result.setTestAudioDataUrl(buildRuntimeBenchmarkTtsAudioDataUrl(sample));
            result.setTestOutput(buildRuntimeBenchmarkTtsOutput(sample));
            boolean success = Boolean.TRUE.equals(sample.getBool("success"))
                    && getRuntimeBenchmarkTtsAudioBytes(sample) > 0;
            result.setSuccess(success);
            result.setMessage(success
                    ? "TTS 真实合成测试通过"
                    : StringUtils.defaultIfBlank(sample.getStr("message"), "TTS 真实合成未生成有效音频"));
            return result;
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("TTS 真实合成测试失败：" + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            result.setTestOutput(result.getMessage());
            return result;
        }
    }

    private String buildRuntimeBenchmarkTtsInput(JSONObject sample, String providerType) {
        JSONObject inputDetail = sample.getJSONObject("input_detail");
        String text = inputDetail == null ? sample.getStr("input_summary") : inputDetail.getStr("text");
        String sampleRate = inputDetail == null ? null : StringUtils.defaultString(inputDetail.getStr("sample_rate"));
        String audioFormat = inputDetail == null ? null : inputDetail.getStr("audio_format");
        return "供应器类型：" + providerType
                + "\n文本：" + StringUtils.defaultIfBlank(text, TTS_TEST_TEXT)
                + (StringUtils.isNotBlank(sampleRate) ? "\n采样率：" + sampleRate : "")
                + (StringUtils.isNotBlank(audioFormat) ? "\n音频格式：" + audioFormat : "")
                + "\n测试方式：xiaozhi-server 运行时 provider 真实合成";
    }

    private String buildRuntimeBenchmarkTtsOutput(JSONObject sample) {
        JSONObject outputDetail = sample.getJSONObject("output_detail");
        if (outputDetail == null) {
            return StringUtils.defaultString(sample.getStr("output_summary"));
        }
        String audioBytes = StringUtils.defaultString(outputDetail.getStr("audio_bytes"));
        String outputFileType = outputDetail.getStr("output_file_type");
        String previewFormat = outputDetail.getStr("audio_preview_format");
        return "音频字节数：" + StringUtils.defaultIfBlank(audioBytes, "0")
                + (StringUtils.isNotBlank(outputFileType) ? "\n输出类型：" + outputFileType : "")
                + (StringUtils.isNotBlank(previewFormat) ? "\n预览格式：" + previewFormat : "");
    }

    private String buildRuntimeBenchmarkTtsAudioDataUrl(JSONObject sample) {
        JSONObject outputDetail = sample.getJSONObject("output_detail");
        return outputDetail == null ? "" : StringUtils.defaultString(outputDetail.getStr("audio_data_url"));
    }

    private long getRuntimeBenchmarkTtsAudioBytes(JSONObject sample) {
        JSONObject outputDetail = sample.getJSONObject("output_detail");
        if (outputDetail == null) {
            return 0;
        }
        Long audioBytes = outputDetail.getLong("audio_bytes");
        return audioBytes == null ? 0 : audioBytes;
    }

    private String firstNonBlank(JSONObject configJson, String... fields) {
        for (String field : fields) {
            String value = configJson.getStr(field);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private ModelTestResultDTO testOpenAiTtsModel(JSONObject configJson) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String apiUrl = StringUtils.defaultIfBlank(configJson.getStr("api_url"), configJson.getStr("base_url"));
        String model = configJson.getStr("model");
        String voice = configJson.getStr("voice");
        String apiKey = configJson.getStr("api_key");
        if (StringUtils.isAnyBlank(apiUrl, model, voice, apiKey) || isPlaceholderSecret(apiKey)) {
            result.setSuccess(false);
            result.setMessage("OpenAI TTS 缺少 api_url、model、voice 或有效密钥");
            result.setTestInput("接口：" + StringUtils.defaultString(apiUrl, "-")
                    + "\n模型：" + StringUtils.defaultString(model, "-")
                    + "\n音色：" + StringUtils.defaultString(voice, "-")
                    + "\n文本：" + TTS_TEST_TEXT);
            result.setTestOutput(result.getMessage());
            return result;
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", TTS_TEST_TEXT);
        requestBody.put("voice", voice);
        requestBody.put("response_format", StringUtils.defaultIfBlank(configJson.getStr("format"), "wav"));
        if (configJson.containsKey("speed")) {
            requestBody.put("speed", configJson.get("speed"));
        }
        result.setTestInput("接口：" + apiUrl + "\n模型：" + model + "\n音色：" + voice + "\n文本：" + TTS_TEST_TEXT);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);
        long startTime = System.currentTimeMillis();
        ResponseEntity<byte[]> response = restTemplate.exchange(
                apiUrl, HttpMethod.POST, new HttpEntity<>(requestBody, headers), byte[].class);
        result.setResponseTimeMs(System.currentTimeMillis() - startTime);
        byte[] audioBytes = response.getBody();
        result.setTestOutput("HTTP 状态：" + response.getStatusCode()
                + "\n音频字节数：" + (audioBytes == null ? 0 : audioBytes.length));
        result.setSuccess(response.getStatusCode().is2xxSuccessful() && audioBytes != null && audioBytes.length > 1024);
        result.setMessage(Boolean.TRUE.equals(result.getSuccess())
                ? "TTS 真实合成测试通过，测试文本：" + TTS_TEST_TEXT
                : "TTS 响应音频为空或过小，测试文本：" + TTS_TEST_TEXT);
        return result;
    }

    private ModelTestResultDTO testOpenAiVisionModel(JSONObject configJson) {
        // TODO: migrate to LlmClient
        ModelTestResultDTO result = new ModelTestResultDTO();
        String baseUrl = StringUtils.defaultIfBlank(configJson.getStr("base_url"), configJson.getStr("url"));
        String model = configJson.getStr("model_name");
        String apiKey = configJson.getStr("api_key");
        if (StringUtils.isAnyBlank(baseUrl, model, apiKey) || isPlaceholderSecret(apiKey)) {
            result.setSuccess(false);
            result.setMessage("视觉模型缺少 base_url/url、model_name 或有效密钥");
            result.setTestInput("模型：" + StringUtils.defaultString(model, "-")
                    + "\n问题：" + VLLM_TEST_QUESTION);
            result.setTestOutput(result.getMessage());
            return result;
        }

        try {
            Path imagePath = findFirstExistingPath(List.of(
                    "../../docs/images/demo1.png",
                    "docs/images/demo1.png",
                    "../docs/images/demo1.png"));
            String imageSource = imagePath == null ? "内置视觉测试图片" : imagePath.toString();
            byte[] imageBytes = imagePath == null ? createEmbeddedVisionTestImage() : Files.readAllBytes(imagePath);
            String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);
            result.setTestImageDataUrl("data:image/png;base64," + imageBase64);
            Map<String, Object> imageUrl = Map.of("url", "data:image/png;base64," + imageBase64);
            Map<String, Object> textContent = Map.of("type", "text", "text", VLLM_TEST_QUESTION);
            Map<String, Object> imageContent = Map.of("type", "image_url", "image_url", imageUrl);
            Map<String, Object> message = Map.of("role", "user", "content", List.of(textContent, imageContent));
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(message));
            requestBody.put("stream", false);
            requestBody.put("max_tokens", 120);
            result.setTestInput("图片：" + imageSource + "\n问题：" + VLLM_TEST_QUESTION);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);
            long startTime = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.exchange(
                    buildChatCompletionsUrl(baseUrl), HttpMethod.POST, new HttpEntity<>(requestBody, headers),
                    String.class);
            result.setResponseTimeMs(System.currentTimeMillis() - startTime);
            result.setTestOutput(StringUtils.defaultString(response.getBody()));
            if (!response.getStatusCode().is2xxSuccessful()) {
                result.setSuccess(false);
                result.setMessage("视觉模型接口返回非成功状态：" + response.getStatusCode());
                return result;
            }
            JSONObject responseJson = JSONUtil.parseObj(response.getBody());
            JSONArray choices = responseJson.getJSONArray("choices");
            String answer = extractOpenAiChoiceText(choices);
            if (StringUtils.isNotBlank(answer)) {
                result.setTestOutput(answer);
            }
            result.setSuccess(choices != null && !choices.isEmpty());
            result.setMessage(Boolean.TRUE.equals(result.getSuccess())
                    ? "视觉模型真实图片测试通过，样本：" + imageSource
                    : "视觉模型响应缺少 choices，样本：" + imageSource);
            return result;
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("视觉模型真实图片测试失败：" + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            if (StringUtils.isBlank(result.getTestOutput())) {
                result.setTestOutput(result.getMessage());
            }
            return result;
        }
    }

    private byte[] createEmbeddedVisionTestImage() throws IOException {
        BufferedImage image = new BufferedImage(480, 320, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(246, 248, 252));
            graphics.fillRect(0, 0, 480, 320);
            graphics.setColor(new Color(43, 92, 171));
            graphics.fillRoundRect(54, 64, 160, 116, 18, 18);
            graphics.setColor(new Color(239, 91, 91));
            graphics.fillOval(276, 56, 128, 128);
            graphics.setColor(new Color(49, 168, 126));
            int[] xPoints = { 152, 244, 336 };
            int[] yPoints = { 264, 128, 264 };
            graphics.fillPolygon(xPoints, yPoints, 3);
            graphics.setColor(new Color(28, 36, 52));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            graphics.drawString("XIAOZHI VISION TEST", 82, 292);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private String extractOpenAiChoiceText(JSONArray choices) {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        JSONObject firstChoice = choices.getJSONObject(0);
        if (firstChoice == null) {
            return null;
        }
        JSONObject message = firstChoice.getJSONObject("message");
        if (message == null) {
            return null;
        }
        Object content = message.get("content");
        if (content == null) {
            return null;
        }
        return content.toString();
    }

    private ModelTestResultDTO testOllamaModel(JSONObject configJson) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String baseUrl = configJson.getStr("base_url");
        String model = configJson.getStr("model_name");
        if (StringUtils.isAnyBlank(baseUrl, model)) {
            result.setSuccess(false);
            result.setMessage("Ollama 模型缺少 base_url 或 model_name");
            result.setTestInput("接口：" + StringUtils.defaultString(baseUrl, "-")
                    + "\n模型：" + StringUtils.defaultString(model, "-")
                    + "\n提示词：" + LLM_TEST_PROMPT);
            result.setTestOutput(result.getMessage());
            return result;
        }

        String apiUrl = baseUrl.endsWith("/") ? baseUrl + "api/chat" : baseUrl + "/api/chat";
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", LLM_TEST_PROMPT)));
        requestBody.put("stream", false);
        result.setTestInput("接口：" + apiUrl + "\n模型：" + model + "\n提示词：" + LLM_TEST_PROMPT);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        long startTime = System.currentTimeMillis();
        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl, HttpMethod.POST, new HttpEntity<>(requestBody, headers), String.class);
        result.setResponseTimeMs(System.currentTimeMillis() - startTime);
        result.setTestOutput(StringUtils.defaultString(response.getBody()));
        if (!response.getStatusCode().is2xxSuccessful()) {
            result.setSuccess(false);
            result.setMessage("Ollama 接口返回非成功状态：" + response.getStatusCode());
            return result;
        }
        JSONObject responseJson = JSONUtil.parseObj(response.getBody());
        JSONObject message = responseJson.getJSONObject("message");
        if (message != null && StringUtils.isNotBlank(message.getStr("content"))) {
            result.setTestOutput(message.getStr("content"));
        } else if (StringUtils.isNotBlank(responseJson.getStr("response"))) {
            result.setTestOutput(responseJson.getStr("response"));
        }
        result.setSuccess(responseJson.containsKey("message") || responseJson.containsKey("response"));
        result.setMessage(Boolean.TRUE.equals(result.getSuccess()) ? "Ollama 连通性测试通过" : "Ollama 响应格式不符合预期");
        return result;
    }

    private String buildChatCompletionsUrl(String baseUrl) {
        if (baseUrl.endsWith("/chat/completions")) {
            return baseUrl;
        }
        return baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
    }

    private ModelTestResultDTO validateBasicModelConfig(JSONObject configJson, String type, String modelType) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        result.setTestInput("模型类型：" + modelType + "\n供应器类型：" + type + "\n测试内容：配置完整性校验");
        List<String> missingFields = getRequiredFields(modelType, type).stream()
                .filter(field -> !hasUsableConfigValue(configJson, field))
                .collect(Collectors.toList());

        for (List<String> group : getRequiredAnyFieldGroups(modelType, type)) {
            boolean hasAny = group.stream().anyMatch(field -> hasUsableConfigValue(configJson, field));
            if (!hasAny) {
                missingFields.add(String.join("/", group));
            }
        }
        missingFields.addAll(getMissingAlternativeRequirements(configJson, modelType, type));

        if (CollectionUtil.isNotEmpty(missingFields)) {
            result.setSuccess(false);
            result.setMessage("模型配置缺少有效字段：" + String.join("、", missingFields));
            result.setTestOutput(result.getMessage());
            return result;
        }

        result.setSuccess(true);
        result.setMessage("模型配置完整性测试通过");
        result.setTestOutput(buildBasicConfigSuccessOutput(modelType, type));
        return result;
    }

    private List<String> getRequiredFields(String modelType, String type) {
        String normalizedModelType = StringUtils.defaultString(modelType).toUpperCase();
        String normalizedType = StringUtils.lowerCase(StringUtils.defaultString(type));
        if ("ASR".equals(normalizedModelType)) {
            return switch (normalizedType) {
                case "fun_local", "sherpa_onnx_local" -> List.of("model_dir");
                case "vosk" -> List.of("model_path");
                case "fun_server" -> List.of("host", "port");
                case "doubao" -> List.of("appid", "access_token", "cluster");
                case "tencent" -> List.of("appid", "secret_id", "secret_key");
                case "aliyun", "aliyun_stream" -> List.of("appkey");
                case "baidu" -> List.of("app_id", "api_key", "secret_key");
                case "qwen3_asr_flash" -> List.of("api_key", "base_url", "model_name");
                case "aliyunbl_stream" -> List.of("api_key", "model");
                case "xunfei_stream" -> List.of("app_id", "api_key", "api_secret");
                default -> List.of();
            };
        }
        if ("TTS".equals(normalizedModelType)) {
            return switch (normalizedType) {
                case "edge" -> List.of("voice");
                case "doubao" -> List.of("api_url", "voice");
                case "siliconflow" -> List.of("access_token", "model", "voice");
                case "cozecn" -> List.of("access_token", "voice");
                case "fishspeech" -> List.of("api_url");
                case "tencent" -> List.of("appid", "secret_id", "secret_key", "voice");
                case "gpt_sovits_v2", "gpt_sovits_v3", "custom", "paddle_speech" -> List.of("url");
                case "aliyun", "aliyun_stream" -> List.of("appkey", "voice");
                case "huoshan_double_stream" -> List.of("appid", "access_token", "resource_id", "speaker");
                case "index_stream" -> List.of("api_url", "voice");
                case "minimax_httpstream" -> List.of("api_key", "group_id", "voice_id", "model");
                case "alibl_stream" -> List.of("appkey", "voice", "model");
                case "xunfei_stream" -> List.of("app_id", "api_key", "api_secret", "voice");
                default -> List.of();
            };
        }
        if ("LLM".equals(normalizedModelType)) {
            return switch (normalizedType) {
                case "alibl" -> List.of("base_url", "app_id", "api_key");
                case "dify", "fastgpt" -> List.of("base_url", "api_key");
                case "gemini" -> List.of("api_key", "model_name");
                case "coze" -> List.of("bot_id", "personal_access_token");
                case "xinference" -> List.of("base_url", "model_name");
                default -> List.of();
            };
        }
        if ("VLLM".equals(normalizedModelType)) {
            return List.of("base_url", "model_name", "api_key");
        }
        if ("MEMORY".equals(normalizedModelType)) {
            return switch (normalizedType) {
                case "mem0ai" -> List.of("api_key");
                case "mem_local_short" -> List.of("llm");
                case "powermem" -> List.of("llm_model", "llm_api_key", "embedding_model", "embedding_api_key");
                default -> List.of();
            };
        }
        if ("VAD".equals(normalizedModelType)) {
            return "silero".equals(normalizedType) ? List.of("model_dir", "threshold", "min_silence_duration_ms")
                    : List.of();
        }
        if ("RAG".equals(normalizedModelType)) {
            return "ragflow".equals(normalizedType) ? List.of("base_url", "api_key") : List.of();
        }
        return List.of();
    }

    private List<List<String>> getRequiredAnyFieldGroups(String modelType, String type) {
        String normalizedModelType = StringUtils.defaultString(modelType).toUpperCase();
        String normalizedType = StringUtils.lowerCase(StringUtils.defaultString(type));
        if ("TTS".equals(normalizedModelType) && "doubao".equals(normalizedType)) {
            return List.of(List.of("access_token", "authorization"));
        }
        return List.of();
    }

    private List<String> getMissingAlternativeRequirements(JSONObject configJson, String modelType, String type) {
        if (!isAliyunNlsModel(modelType, type)) {
            return List.of();
        }
        boolean hasToken = hasUsableConfigValue(configJson, "token");
        boolean hasAccessKeyPair = hasUsableConfigValue(configJson, "access_key_id")
                && hasUsableConfigValue(configJson, "access_key_secret");
        if (hasToken || hasAccessKeyPair) {
            return List.of();
        }
        return List.of("token 或 access_key_id+access_key_secret");
    }

    private boolean isAliyunNlsModel(String modelType, String type) {
        String normalizedModelType = StringUtils.defaultString(modelType).toUpperCase();
        String normalizedType = StringUtils.lowerCase(StringUtils.defaultString(type));
        return ("ASR".equals(normalizedModelType) || "TTS".equals(normalizedModelType))
                && ("aliyun".equals(normalizedType) || "aliyun_stream".equals(normalizedType));
    }

    private String buildBasicConfigSuccessOutput(String modelType, String type) {
        String normalizedModelType = StringUtils.defaultString(modelType).toUpperCase();
        String normalizedType = StringUtils.lowerCase(StringUtils.defaultString(type));
        if ("VAD".equals(normalizedModelType) && "silero".equals(normalizedType)) {
            return "必填字段已配置，后台允许启用。SileroVAD 模型文件由 xiaozhi-server 按当前运行方式在运行时校验；"
                    + "Docker 部署需确保容器内路径可访问，源码运行需确保本机路径可访问。";
        }
        return "必填字段已配置";
    }

    private boolean hasUsableConfigValue(JSONObject configJson, String field) {
        if (configJson == null) {
            return false;
        }
        Object value = configJson.get(field);
        return hasUsableObjectValue(value);
    }

    private boolean hasUsableObjectValue(Object value) {
        if (value == null) {
            return false;
        }
        String stringValue = StringUtils.trim(String.valueOf(value));
        return hasUsableTextValue(stringValue);
    }

    private boolean hasUsableTextValue(String stringValue) {
        return StringUtils.isNotBlank(stringValue) && !"null".equalsIgnoreCase(stringValue) && !isPlaceholderSecret(stringValue);
    }

    private byte[] buildDoubaoStreamAsrInitRequest(JSONObject configJson, String appid, String accessToken,
            boolean enableMultilingual) throws IOException {
        JSONObject request = new JSONObject();
        request.set("app", new JSONObject()
                .set("appid", appid)
                .set("token", accessToken));
        request.set("user", new JSONObject()
                .set("uid", StringUtils.defaultIfBlank(configJson.getStr("uid"), "streaming_asr_service")));

        JSONObject corpus = new JSONObject()
                .set("boosting_table_name", StringUtils.defaultString(configJson.getStr("boosting_table_name")))
                .set("correct_table_name", StringUtils.defaultString(configJson.getStr("correct_table_name")));
        JSONObject requestBody = new JSONObject()
                .set("reqid", UUID.randomUUID().toString())
                .set("workflow", StringUtils.defaultIfBlank(
                        configJson.getStr("workflow"),
                        "audio_in,resample,partition,vad,fe,decode,itn,nlu_punctuate"))
                .set("show_utterances", true)
                .set("result_type", StringUtils.defaultIfBlank(configJson.getStr("result_type"), "single"))
                .set("sequence", 1)
                .set("end_window_size", configJson.getInt("end_window_size", 200))
                .set("corpus", corpus);
        request.set("request", requestBody);

        JSONObject audio = new JSONObject()
                .set("format", StringUtils.defaultIfBlank(configJson.getStr("format"), "pcm"))
                .set("codec", StringUtils.defaultIfBlank(configJson.getStr("codec"), "pcm"))
                .set("rate", configJson.getInt("sample_rate", 16000))
                .set("bits", configJson.getInt("bits", 16))
                .set("channel", configJson.getInt("channel", 1))
                .set("sample_rate", configJson.getInt("sample_rate", 16000));
        if (enableMultilingual && StringUtils.isNotBlank(configJson.getStr("language"))) {
            audio.set("language", configJson.getStr("language"));
        }
        request.set("audio", audio);

        byte[] payload = gzipCompress(request.toString().getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(generateDoubaoHeader(0x01, 0x01, 0x00, 0x01, 0x01));
        output.write(ByteBuffer.allocate(4).putInt(payload.length).array());
        output.write(payload);
        return output.toByteArray();
    }

    private byte[] buildDoubaoStreamAsrAudioRequest(byte[] pcmBytes, boolean last) throws IOException {
        byte[] payload = gzipCompress(pcmBytes == null ? new byte[0] : pcmBytes);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(generateDoubaoHeader(0x01, 0x02, last ? 0x02 : 0x00, 0x01, 0x01));
        output.write(ByteBuffer.allocate(4).putInt(payload.length).array());
        output.write(payload);
        return output.toByteArray();
    }

    private byte[] generateDoubaoHeader(int version, int messageType, int messageTypeSpecificFlags,
            int serialMethod, int compressionType) {
        return new byte[] {
                (byte) ((version << 4) | 0x01),
                (byte) ((messageType << 4) | messageTypeSpecificFlags),
                (byte) ((serialMethod << 4) | compressionType),
                0x00
        };
    }

    private byte[] gzipCompress(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(output)) {
            gzipOutputStream.write(bytes);
        }
        return output.toByteArray();
    }

    private JSONObject parseDoubaoStreamAsrResponse(byte[] response) {
        if (response.length < 4) {
            throw new IllegalArgumentException("响应数据长度不足：" + response.length);
        }

        int messageType = (response[1] & 0xFF) >> 4;
        if (messageType == 0x0F) {
            int code = ByteBuffer.wrap(response, 4, 4).getInt();
            String errorText = response.length > 12
                    ? new String(response, 12, response.length - 12, StandardCharsets.UTF_8)
                    : "";
            JSONObject parsed = new JSONObject();
            parsed.set("code", code);
            parsed.set("payload_msg", JSONUtil.isTypeJSON(errorText) ? JSONUtil.parseObj(errorText)
                    : new JSONObject().set("error", errorText));
            return parsed;
        }

        String payloadText;
        if (response.length >= 12) {
            int length = ByteBuffer.wrap(response, 8, 4).getInt();
            if (length > 0 && length <= response.length - 12) {
                payloadText = new String(response, 12, length, StandardCharsets.UTF_8);
            } else {
                payloadText = new String(response, 8, response.length - 8, StandardCharsets.UTF_8);
            }
        } else {
            payloadText = new String(response, 4, response.length - 4, StandardCharsets.UTF_8);
        }

        JSONObject parsed = new JSONObject();
        parsed.set("payload_msg", JSONUtil.parseObj(payloadText));
        return parsed;
    }

    private String extractDoubaoErrorMessage(JSONObject parsedResponse) {
        JSONObject payload = parsedResponse.getJSONObject("payload_msg");
        if (payload == null) {
            return parsedResponse.toString();
        }
        return StringUtils.defaultIfBlank(payload.getStr("error"), payload.toString());
    }

    private String extractDoubaoRecognizedText(JSONObject parsedResponse) {
        JSONObject payload = parsedResponse.getJSONObject("payload_msg");
        if (payload == null) {
            return "";
        }
        JSONObject result = payload.getJSONObject("result");
        if (result == null) {
            return "";
        }
        JSONArray utterances = result.getJSONArray("utterances");
        if (utterances != null && !utterances.isEmpty()) {
            String text = utterances.stream()
                    .map(JSONUtil::parseObj)
                    .filter(item -> Boolean.TRUE.equals(item.getBool("definite")))
                    .map(item -> StringUtils.defaultString(item.getStr("text")))
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.joining());
            if (StringUtils.isNotBlank(text)) {
                return text;
            }
        }
        return StringUtils.defaultString(result.getStr("text"));
    }

    private boolean isPlaceholderSecret(String value) {
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

    private Path findFirstExistingPath(List<String> candidates) {
        for (String candidate : candidates) {
            Path path = Paths.get(candidate).toAbsolutePath().normalize();
            if (Files.exists(path) && Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    private Path findAsrTestAudioPath() {
        Path codeRootPath = Paths.get("").toAbsolutePath().normalize();
        Path projectRootPath = locateProjectRoot(codeRootPath);
        return findFirstExistingPath(List.of(
                projectRootPath.resolve("main/xiaozhi-server/config/assets/" + STANDARD_ASR_TEST_AUDIO).toString(),
                projectRootPath.resolve("main/xiaozhi-server/config/assets/wakeup_words.wav").toString(),
                projectRootPath.resolve("main/xiaozhi-server/config/assets/bind_code.wav").toString(),
                codeRootPath.resolve("../xiaozhi-server/config/assets/" + STANDARD_ASR_TEST_AUDIO).toString(),
                "../xiaozhi-server/config/assets/" + STANDARD_ASR_TEST_AUDIO,
                "../xiaozhi-server/config/assets/wakeup_words.wav",
                "../xiaozhi-server/config/assets/bind_code.wav",
                "main/xiaozhi-server/config/assets/" + STANDARD_ASR_TEST_AUDIO,
                "main/xiaozhi-server/config/assets/wakeup_words.wav",
                "main/xiaozhi-server/config/assets/bind_code.wav"));
    }

    private Path locateProjectRoot(Path startPath) {
        Path current = startPath;
        while (current != null) {
            if (Files.exists(current.resolve("main/xiaozhi-server/config/assets/" + STANDARD_ASR_TEST_AUDIO))) {
                return current;
            }
            current = current.getParent();
        }
        return startPath;
    }

    private String buildAudioDataUrl(Path audioPath) {
        if (audioPath == null || !Files.exists(audioPath) || !Files.isRegularFile(audioPath)) {
            return "";
        }
        try {
            String filename = audioPath.getFileName().toString().toLowerCase();
            String mediaType = filename.endsWith(".mp3") ? "audio/mpeg" : "audio/wav";
            return "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(audioPath));
        } catch (IOException e) {
            log.warn("生成测试音频预览失败，path: {}", audioPath, e);
            return "";
        }
    }

    private byte[] readAsrTestPcmBytes(Path audioPath) throws IOException {
        byte[] bytes = Files.readAllBytes(audioPath);
        if (bytes.length > 44 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            byte[] pcm = new byte[bytes.length - 44];
            System.arraycopy(bytes, 44, pcm, 0, pcm.length);
            return pcm;
        }
        return bytes;
    }

    private static class BinaryResponseQueueListener implements WebSocket.Listener {
        private final BlockingQueue<byte[]> responses = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private volatile Throwable error;

        private byte[] poll(long timeout, TimeUnit unit) throws Exception {
            if (error != null) {
                throw new IOException(error);
            }
            byte[] response = responses.poll(timeout, unit);
            if (response == null) {
                if (error != null) {
                    throw new IOException(error);
                }
                throw new IOException("等待 ASR 响应超时");
            }
            return response;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            synchronized (buffer) {
                buffer.write(bytes, 0, bytes.length);
                if (last) {
                    responses.offer(buffer.toByteArray());
                    buffer.reset();
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            this.error = error;
        }
    }

    private static class BinaryResponseListener implements WebSocket.Listener {
        private final CompletableFuture<byte[]> responseFuture;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private BinaryResponseListener(CompletableFuture<byte[]> responseFuture) {
            this.responseFuture = responseFuture;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            synchronized (buffer) {
                buffer.write(bytes, 0, bytes.length);
                if (last && !responseFuture.isDone()) {
                    responseFuture.complete(buffer.toByteArray());
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            responseFuture.completeExceptionally(error);
        }
    }
}
