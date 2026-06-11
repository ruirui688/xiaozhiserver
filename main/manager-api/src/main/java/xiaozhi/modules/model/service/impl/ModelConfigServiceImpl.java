package xiaozhi.modules.model.service.impl;

import java.io.Serializable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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
import xiaozhi.common.utils.SensitiveDataUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.dto.LlmModelBasicInfoDTO;
import xiaozhi.modules.model.dto.ModelBasicInfoDTO;
import xiaozhi.modules.model.dto.ModelConfigBodyDTO;
import xiaozhi.modules.model.dto.ModelConfigDTO;
import xiaozhi.modules.model.dto.ModelProviderDTO;
import xiaozhi.modules.model.dto.ModelTestResultDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.ModelProviderService;
import lombok.extern.slf4j.Slf4j;

@Service
@AllArgsConstructor
@Slf4j
public class ModelConfigServiceImpl extends BaseServiceImpl<ModelConfigDao, ModelConfigEntity>
        implements ModelConfigService {

    private final ModelConfigDao modelConfigDao;
    private final ModelProviderService modelProviderService;
    private final RedisUtils redisUtils;
    private final AgentDao agentDao;
    private final RestTemplate restTemplate;

    private static final int MODEL_TEST_UNTESTED = 0;
    private static final int MODEL_TEST_PASSED = 1;
    private static final int MODEL_TEST_FAILED = 2;
    private static final Set<String> LOCAL_TESTABLE_TYPES = new HashSet<>(List.of(
            "silero", "nomem", "mem_local_short",
            "mem_report_only", "nointent", "function_call"));
    private static final String TTS_TEST_TEXT = "你好，我是小智，语音合成测试成功。";
    private static final String VLLM_TEST_QUESTION = "请用一句话描述这张图片。";

    @Override
    public List<ModelBasicInfoDTO> getModelCodeList(String modelType, String modelName) {
        List<ModelConfigEntity> entities = modelConfigDao.selectList(
                new QueryWrapper<ModelConfigEntity>()
                        .eq("model_type", modelType)
                        .eq("is_enabled", 1)
                        .eq("test_status", MODEL_TEST_PASSED)
                        .like(StringUtils.isNotBlank(modelName), "model_name", modelName)
                        .select("id", "model_name")
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
                        .select("id", "model_name", "config_json"));

        return entities.stream().map(item -> {
            LlmModelBasicInfoDTO dto = new LlmModelBasicInfoDTO();
            dto.setId(item.getId());
            dto.setModelName(item.getModelName());
            String type = item.getConfigJson().getOrDefault("type", "").toString();
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

        return getPageData(modelConfigEntityIPage, ModelConfigDTO.class);
    }

    @Override
    public ModelConfigDTO edit(String modelType, String provideCode, String id, ModelConfigBodyDTO modelConfigBodyDTO) {
        // 1. 参数验证
        validateEditParameters(modelType, provideCode, id, modelConfigBodyDTO);

        // 2. 验证模型提供者
        validateModelProvider(modelType, provideCode);

        // 3. 获取原始配置（不经过敏感数据处理）
        ModelConfigEntity originalEntity = getOriginalConfigFromDb(id);

        // 4. 验证LLM配置
        validateLlmConfiguration(modelConfigBodyDTO);

        // 5. 准备更新实体并处理敏感数据
        ModelConfigEntity modelConfigEntity = prepareUpdateEntity(modelConfigBodyDTO, originalEntity, modelType, id);

        // 6. 执行数据库更新
        modelConfigDao.updateById(modelConfigEntity);

        // 7. 清除缓存
        clearModelCache(id);

        // 8. 返回处理后的数据（包含敏感数据掩码）
        return buildResponseDTO(modelConfigEntity);
    }

    @Override
    public ModelConfigDTO add(String modelType, String provideCode, ModelConfigBodyDTO modelConfigBodyDTO) {
        validateAddParameters(modelType, provideCode, modelConfigBodyDTO);

        validateModelProvider(modelType, provideCode);

        ModelConfigEntity modelConfigEntity = prepareAddEntity(modelConfigBodyDTO, modelType);

        modelConfigDao.insert(modelConfigEntity);

        return buildResponseDTO(modelConfigEntity);
    }

    @Override
    public void delete(String id) {
        if (StringUtils.isBlank(id)) {
            throw new RenException(ErrorCode.IDENTIFIER_NOT_NULL);
        }

        ModelConfigEntity modelConfig = modelConfigDao.selectById(id);
        if (modelConfig != null && modelConfig.getIsDefault() == 1) {
            throw new RenException(ErrorCode.DEFAULT_MODEL_DELETE_ERROR);
        }

        checkAgentReference(id);
        checkIntentConfigReference(id);

        modelConfigDao.deleteById(id);

        clearModelCache(id);
    }

    @Override
    public ModelTestResultDTO testModel(String id) {
        if (StringUtils.isBlank(id)) {
            throw new RenException(ErrorCode.IDENTIFIER_NOT_NULL);
        }

        ModelConfigEntity modelConfig = modelConfigDao.selectById(id);
        if (modelConfig == null) {
            throw new RenException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        ModelTestResultDTO result = doTestModel(modelConfig);
        Date testTime = new Date();
        result.setTestTime(testTime);
        result.setTestStatus(Boolean.TRUE.equals(result.getSuccess()) ? MODEL_TEST_PASSED : MODEL_TEST_FAILED);

        ModelConfigEntity updateEntity = new ModelConfigEntity();
        updateEntity.setId(id);
        updateEntity.setConfigJson(null);
        updateEntity.setTestStatus(result.getTestStatus());
        updateEntity.setTestMessage(result.getMessage());
        updateEntity.setTestTime(testTime);
        updateEntity.setIsEnabled(Boolean.TRUE.equals(result.getSuccess()) ? 1 : 0);
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
    public ModelConfigEntity selectById(Serializable id) {
        ModelConfigEntity entity = super.selectById(id);
        if (entity != null && entity.getConfigJson() != null) {
            entity.setConfigJson(maskSensitiveFields(entity.getConfigJson()));
        }
        return entity;
    }

    @Override
    protected <D> PageData<D> getPageData(IPage<?> page, Class<D> target) {
        List<?> records = page.getRecords();
        if (records != null && !records.isEmpty()) {
            for (Object record : records) {
                if (record instanceof ModelConfigEntity) {
                    ModelConfigEntity entity = (ModelConfigEntity) record;
                    if (entity.getConfigJson() != null) {
                        entity.setConfigJson(maskSensitiveFields(entity.getConfigJson()));
                    }
                }
            }
        }
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
            entity = modelConfigDao.selectById(id);
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
     * 从数据库获取原始配置（不经过敏感数据处理）
     */
    private ModelConfigEntity getOriginalConfigFromDb(String id) {
        ModelConfigEntity originalEntity = modelConfigDao.selectById(id);
        if (originalEntity == null) {
            throw new RenException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return originalEntity;
    }

    /**
     * 验证LLM配置
     */
    private void validateLlmConfiguration(ModelConfigBodyDTO modelConfigBodyDTO) {
        if (modelConfigBodyDTO.getConfigJson() != null && modelConfigBodyDTO.getConfigJson().containsKey("llm")) {
            String llm = modelConfigBodyDTO.getConfigJson().get("llm").toString();
            ModelConfigEntity modelConfigEntity = modelConfigDao.selectOne(new LambdaQueryWrapper<ModelConfigEntity>()
                    .eq(ModelConfigEntity::getId, llm));

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
     * 准备更新实体，处理敏感数据
     */
    private ModelConfigEntity prepareUpdateEntity(ModelConfigBodyDTO modelConfigBodyDTO,
            ModelConfigEntity originalEntity,
            String modelType,
            String id) {
        // 1. 复制原始实体，保留所有原始数据（包括敏感信息）
        ModelConfigEntity modelConfigEntity = ConvertUtils.sourceToTarget(originalEntity, ModelConfigEntity.class);
        modelConfigEntity.setId(id);
        modelConfigEntity.setModelType(modelType);

        // 2. 只更新非敏感字段
        modelConfigEntity.setModelName(modelConfigBodyDTO.getModelName());
        modelConfigEntity.setSort(modelConfigBodyDTO.getSort());
        modelConfigEntity.setIsEnabled(0);
        modelConfigEntity.setRemark(modelConfigBodyDTO.getRemark());
        modelConfigEntity.setTestStatus(MODEL_TEST_UNTESTED);
        modelConfigEntity.setTestMessage(null);
        modelConfigEntity.setTestTime(null);
        // 3. 处理配置JSON，仅更新非敏感字段和明确修改的敏感字段
        if (modelConfigBodyDTO.getConfigJson() != null && originalEntity.getConfigJson() != null) {
            JSONObject originalJson = originalEntity.getConfigJson();
            JSONObject updatedJson = new JSONObject(originalJson); // 基于原始JSON进行修改

            // 遍历更新的JSON，只更新非敏感字段或确实被修改的敏感字段
            for (String key : modelConfigBodyDTO.getConfigJson().keySet()) {
                Object value = modelConfigBodyDTO.getConfigJson().get(key);

                // 如果是敏感字段，需要确认是否真的被修改（前端传入的可能是掩码后的值）
                if (SensitiveDataUtils.isSensitiveField(key)) {

                    if (value instanceof String && !SensitiveDataUtils.isMaskedValue((String) value)) {
                        updatedJson.put(key, value);
                    }
                } else if (value instanceof JSONObject) {
                    // 递归处理嵌套JSON
                    mergeJson(updatedJson, key, (JSONObject) value);
                } else {
                    // 非敏感字段直接更新
                    updatedJson.put(key, value);
                }
            }

            // 删除在新JSON中不存在的非敏感字段
            for (String oldKey : originalJson.keySet().toArray(new String[0])) {
                if (!modelConfigBodyDTO.getConfigJson().containsKey(oldKey)
                        && !SensitiveDataUtils.isSensitiveField(oldKey)) {
                    updatedJson.remove(oldKey);
                }
            }

            modelConfigEntity.setConfigJson(updatedJson);
        }

        return modelConfigEntity;
    }

    // 辅助方法：判断值是否是掩码格式
    private boolean isMaskedValue(String value) {
        if (value == null)
            return false;
        // 简单判断是否包含掩码的特征（***）
        return value.contains("***");
    }

    // 辅助方法：递归合并 JSON，保留原始敏感字段
    private void mergeJson(JSONObject original, String key, JSONObject updated) {
        // 空值检查
        if (original == null || updated == null) {
            log.warn("mergeJson: original 或 updated 为 null");
            return;
        }

        // 如果 original 中不存在 key，创建一个新的 JSON 对象
        if (!original.containsKey(key)) {
            original.put(key, new JSONObject());
        }

        // 获取 original 中的子对象
        Object originalValue = original.get(key);
        JSONObject originalChild;

        // 检查 originalValue 是否是 JSONObject 类型
        if (originalValue instanceof JSONObject) {
            originalChild = (JSONObject) originalValue;
        } else {
            // 如果不是 JSONObject 类型，记录警告并创建新的 JSON 对象
            log.warn("mergeJson: key '{}' 的值不是 JSONObject 类型 (实际类型：{})，将创建新对象",
                    key, originalValue != null ? originalValue.getClass().getSimpleName() : "null");
            originalChild = new JSONObject();
            original.put(key, originalChild);
        }

        for (String childKey : updated.keySet()) {
            Object childValue = updated.get(childKey);
            if (childValue instanceof JSONObject) {
                mergeJson(originalChild, childKey, (JSONObject) childValue);
            } else {
                if (!SensitiveDataUtils.isSensitiveField(childKey) ||
                        (childValue instanceof String && !isMaskedValue((String) childValue))) {
                    originalChild.put(childKey, childValue);
                }
            }
        }

        // 删除在新 JSON 中不存在的非敏感子字段
        for (String oldChildKey : originalChild.keySet().toArray(new String[0])) {
            if (!updated.containsKey(oldChildKey) && !SensitiveDataUtils.isSensitiveField(oldChildKey)) {
                originalChild.remove(oldChildKey);
            }
        }
    }

    /**
     * 准备新增实体
     */
    private ModelConfigEntity prepareAddEntity(ModelConfigBodyDTO modelConfigBodyDTO, String modelType) {
        ModelConfigEntity modelConfigEntity = ConvertUtils.sourceToTarget(modelConfigBodyDTO, ModelConfigEntity.class);
        modelConfigEntity.setModelType(modelType);
        modelConfigEntity.setIsDefault(0);
        modelConfigEntity.setIsEnabled(0);
        modelConfigEntity.setTestStatus(MODEL_TEST_UNTESTED);
        modelConfigEntity.setTestMessage(null);
        modelConfigEntity.setTestTime(null);
        return modelConfigEntity;
    }

    /**
     * 构建返回的DTO，处理敏感数据
     */
    private ModelConfigDTO buildResponseDTO(ModelConfigEntity entity) {
        ModelConfigDTO dto = ConvertUtils.sourceToTarget(entity, ModelConfigDTO.class);
        if (dto.getConfigJson() != null) {
            dto.setConfigJson(maskSensitiveFields(dto.getConfigJson()));
        }
        return dto;
    }

    /**
     * 处理敏感字段
     */
    private JSONObject maskSensitiveFields(JSONObject configJson) {
        return SensitiveDataUtils.maskSensitiveFields(configJson);
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
        ModelConfigEntity modelConfig = modelConfigDao.selectById(modelId);
        if (modelConfig != null
                && "LLM".equals(modelConfig.getModelType() == null ? null : modelConfig.getModelType().toUpperCase())) {
            List<ModelConfigEntity> intentConfigs = modelConfigDao.selectList(
                    new QueryWrapper<ModelConfigEntity>()
                            .eq("model_type", "Intent")
                            .like("config_json", modelId));
            if (!intentConfigs.isEmpty()) {
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

        return entities;
    }

    private ModelTestResultDTO doTestModel(ModelConfigEntity modelConfig) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        if (modelConfig.getConfigJson() == null) {
            result.setSuccess(false);
            result.setMessage("模型配置为空");
            return result;
        }

        JSONObject configJson = modelConfig.getConfigJson();
        String type = StringUtils.lowerCase(configJson.getStr("type"));
        if (StringUtils.isBlank(type)) {
            result.setSuccess(false);
            result.setMessage("模型配置缺少 type");
            return result;
        }

        String modelType = StringUtils.defaultString(modelConfig.getModelType()).toUpperCase();
        try {
            if ("LLM".equals(modelType) || "SLM".equals(modelType)) {
                return testLanguageModel(configJson, type);
            }
            if ("VLLM".equals(modelType)) {
                return testVisionModel(configJson, type);
            }
            if ("ASR".equals(modelType)) {
                return testAsrModel(configJson, type);
            }
            if ("TTS".equals(modelType)) {
                return testTtsModel(configJson, type);
            }
            if ("INTENT".equals(modelType) && "intent_llm".equals(type)) {
                return testIntentModel(configJson);
            }
            if (LOCAL_TESTABLE_TYPES.contains(type)) {
                return validateBasicModelConfig(configJson, type, modelType);
            }
            return validateBasicModelConfig(configJson, type, modelType);
        } catch (Exception e) {
            log.warn("模型测试失败，modelId: {}", modelConfig.getId(), e);
            result.setSuccess(false);
            result.setMessage("模型测试失败：" + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            return result;
        }
    }

    private ModelTestResultDTO testIntentModel(JSONObject configJson) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String llmModelId = configJson.getStr("llm");
        if (StringUtils.isBlank(llmModelId)) {
            result.setSuccess(false);
            result.setMessage("意图识别配置缺少关联 LLM");
            return result;
        }
        ModelConfigEntity llm = modelConfigDao.selectById(llmModelId);
        if (llm == null || llm.getTestStatus() == null || llm.getTestStatus() != MODEL_TEST_PASSED) {
            result.setSuccess(false);
            result.setMessage("关联 LLM 尚未测试通过");
            return result;
        }
        result.setSuccess(true);
        result.setMessage("关联 LLM 已测试通过");
        return result;
    }

    private ModelTestResultDTO testAsrModel(JSONObject configJson, String type) {
        if ("openai".equals(type)) {
            return testOpenAiAsrModel(configJson);
        }
        if ("doubao_stream".equals(type)) {
            return testDoubaoStreamAsrModel(configJson);
        }
        ModelTestResultDTO result = new ModelTestResultDTO();
        return validateBasicModelConfig(configJson, type, "ASR");
    }

    private ModelTestResultDTO testTtsModel(JSONObject configJson, String type) {
        if ("openai".equals(type)) {
            return testOpenAiTtsModel(configJson);
        }
        ModelTestResultDTO result = new ModelTestResultDTO();
        return validateBasicModelConfig(configJson, type, "TTS");
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

    private ModelTestResultDTO testOpenAiCompatibleModel(JSONObject configJson) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String baseUrl = configJson.getStr("base_url");
        String model = configJson.getStr("model_name");
        String apiKey = StringUtils.defaultIfBlank(configJson.getStr("api_key"), configJson.getStr("api_password"));
        if (StringUtils.isAnyBlank(baseUrl, model, apiKey) || isPlaceholderSecret(apiKey)) {
            result.setSuccess(false);
            result.setMessage("OpenAI 兼容模型缺少 base_url、model_name 或有效密钥");
            return result;
        }

        String apiUrl = buildChatCompletionsUrl(baseUrl);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", "请只回复 OK")));
        requestBody.put("temperature", 0);
        requestBody.put("max_tokens", 8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);
        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl, HttpMethod.POST, new HttpEntity<>(requestBody, headers), String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            result.setSuccess(false);
            result.setMessage("模型接口返回非成功状态：" + response.getStatusCode());
            return result;
        }
        JSONObject responseJson = JSONUtil.parseObj(response.getBody());
        JSONArray choices = responseJson.getJSONArray("choices");
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
            return result;
        }

        try {
            Path audioPath = findFirstExistingPath(List.of(
                    "../xiaozhi-server/config/assets/wakeup_words.wav",
                    "../xiaozhi-server/config/assets/bind_code.wav",
                    "main/xiaozhi-server/config/assets/wakeup_words.wav",
                    "main/xiaozhi-server/config/assets/bind_code.wav"));
            if (audioPath == null) {
                result.setSuccess(false);
                result.setMessage("未找到 ASR 测试音频，期望来源：main/xiaozhi-server/config/assets/*.wav");
                return result;
            }

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
            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                result.setSuccess(false);
                result.setMessage("ASR 接口返回非成功状态：" + response.getStatusCode());
                return result;
            }
            JSONObject responseJson = JSONUtil.parseObj(response.getBody());
            String text = responseJson.getStr("text");
            result.setSuccess(StringUtils.isNotBlank(text));
            result.setMessage(Boolean.TRUE.equals(result.getSuccess())
                    ? "ASR 真实音频测试通过，样本：" + audioPath.toString()
                    : "ASR 接口响应缺少 text，样本：" + audioPath.toString());
            return result;
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("ASR 真实音频测试失败：" + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
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
            return result;
        }

        boolean enableMultilingual = Boolean.parseBoolean(StringUtils.defaultString(configJson.getStr("enable_multilingual"), "false"));
        String wsUrl = enableMultilingual
                ? "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream"
                : "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async";

        try {
            CompletableFuture<byte[]> responseFuture = new CompletableFuture<>();
            WebSocket webSocket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .header("X-Api-App-Key", appid)
                    .header("X-Api-Access-Key", accessToken)
                    .header("X-Api-Resource-Id", resourceId)
                    .header("X-Api-Connect-Id", UUID.randomUUID().toString())
                    .buildAsync(URI.create(wsUrl), new BinaryResponseListener(responseFuture))
                    .get(10, TimeUnit.SECONDS);

            byte[] request = buildDoubaoStreamAsrInitRequest(configJson, appid, accessToken, enableMultilingual);
            webSocket.sendBinary(ByteBuffer.wrap(request), true).get(10, TimeUnit.SECONDS);
            byte[] response = responseFuture.get(10, TimeUnit.SECONDS);
            JSONObject parsedResponse = parseDoubaoStreamAsrResponse(response);
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test finished");

            if (parsedResponse.containsKey("code") && parsedResponse.getInt("code") != 1000) {
                result.setSuccess(false);
                result.setMessage("豆包流式 ASR 初始化失败：" + extractDoubaoErrorMessage(parsedResponse));
                return result;
            }

            JSONObject payload = parsedResponse.getJSONObject("payload_msg");
            if (payload != null && payload.containsKey("code") && payload.getInt("code") != 1000 && payload.getInt("code") != 0) {
                result.setSuccess(false);
                result.setMessage("豆包流式 ASR 初始化失败：" + extractDoubaoErrorMessage(parsedResponse));
                return result;
            }
            if (payload != null && payload.containsKey("error")) {
                result.setSuccess(false);
                result.setMessage("豆包流式 ASR 初始化失败：" + payload.getStr("error"));
                return result;
            }

            result.setSuccess(true);
            result.setMessage("豆包流式 ASR 配置连通性测试通过，resource_id：" + resourceId);
            return result;
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("豆包流式 ASR 配置连通性测试失败：" + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            return result;
        }
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

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                apiUrl, HttpMethod.POST, new HttpEntity<>(requestBody, headers), byte[].class);
        byte[] audioBytes = response.getBody();
        result.setSuccess(response.getStatusCode().is2xxSuccessful() && audioBytes != null && audioBytes.length > 1024);
        result.setMessage(Boolean.TRUE.equals(result.getSuccess())
                ? "TTS 真实合成测试通过，测试文本：" + TTS_TEST_TEXT
                : "TTS 响应音频为空或过小，测试文本：" + TTS_TEST_TEXT);
        return result;
    }

    private ModelTestResultDTO testOpenAiVisionModel(JSONObject configJson) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String baseUrl = StringUtils.defaultIfBlank(configJson.getStr("base_url"), configJson.getStr("url"));
        String model = configJson.getStr("model_name");
        String apiKey = configJson.getStr("api_key");
        if (StringUtils.isAnyBlank(baseUrl, model, apiKey) || isPlaceholderSecret(apiKey)) {
            result.setSuccess(false);
            result.setMessage("视觉模型缺少 base_url/url、model_name 或有效密钥");
            return result;
        }

        try {
            Path imagePath = findFirstExistingPath(List.of(
                    "../../docs/images/demo1.png",
                    "docs/images/demo1.png",
                    "../docs/images/demo1.png"));
            if (imagePath == null) {
                result.setSuccess(false);
                result.setMessage("未找到视觉测试图片，期望来源：docs/images/demo1.png");
                return result;
            }

            String imageBase64 = Base64.getEncoder().encodeToString(Files.readAllBytes(imagePath));
            Map<String, Object> imageUrl = Map.of("url", "data:image/png;base64," + imageBase64);
            Map<String, Object> textContent = Map.of("type", "text", "text", VLLM_TEST_QUESTION);
            Map<String, Object> imageContent = Map.of("type", "image_url", "image_url", imageUrl);
            Map<String, Object> message = Map.of("role", "user", "content", List.of(textContent, imageContent));
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(message));
            requestBody.put("stream", false);
            requestBody.put("max_tokens", 120);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);
            ResponseEntity<String> response = restTemplate.exchange(
                    buildChatCompletionsUrl(baseUrl), HttpMethod.POST, new HttpEntity<>(requestBody, headers),
                    String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                result.setSuccess(false);
                result.setMessage("视觉模型接口返回非成功状态：" + response.getStatusCode());
                return result;
            }
            JSONObject responseJson = JSONUtil.parseObj(response.getBody());
            JSONArray choices = responseJson.getJSONArray("choices");
            result.setSuccess(choices != null && !choices.isEmpty());
            result.setMessage(Boolean.TRUE.equals(result.getSuccess())
                    ? "视觉模型真实图片测试通过，样本：" + imagePath.toString()
                    : "视觉模型响应缺少 choices，样本：" + imagePath.toString());
            return result;
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("视觉模型真实图片测试失败：" + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            return result;
        }
    }

    private ModelTestResultDTO testOllamaModel(JSONObject configJson) {
        ModelTestResultDTO result = new ModelTestResultDTO();
        String baseUrl = configJson.getStr("base_url");
        String model = configJson.getStr("model_name");
        if (StringUtils.isAnyBlank(baseUrl, model)) {
            result.setSuccess(false);
            result.setMessage("Ollama 模型缺少 base_url 或 model_name");
            return result;
        }

        String apiUrl = baseUrl.endsWith("/") ? baseUrl + "api/chat" : baseUrl + "/api/chat";
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", "请只回复 OK")));
        requestBody.put("stream", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl, HttpMethod.POST, new HttpEntity<>(requestBody, headers), String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            result.setSuccess(false);
            result.setMessage("Ollama 接口返回非成功状态：" + response.getStatusCode());
            return result;
        }
        JSONObject responseJson = JSONUtil.parseObj(response.getBody());
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
        List<String> missingFields = getRequiredFields(modelType, type).stream()
                .filter(field -> !hasUsableConfigValue(configJson, field))
                .collect(Collectors.toList());

        for (List<String> group : getRequiredAnyFieldGroups(modelType, type)) {
            boolean hasAny = group.stream().anyMatch(field -> hasUsableConfigValue(configJson, field));
            if (!hasAny) {
                missingFields.add(String.join("/", group));
            }
        }

        if (CollectionUtil.isNotEmpty(missingFields)) {
            result.setSuccess(false);
            result.setMessage("模型配置缺少有效字段：" + String.join("、", missingFields));
            return result;
        }

        String localPathField = getLocalPathField(modelType, type);
        if (StringUtils.isNotBlank(localPathField)) {
            String configuredPath = configJson.getStr(localPathField);
            if (findFirstExistingPath(List.of(
                    configuredPath,
                    "../xiaozhi-server/" + configuredPath,
                    "main/xiaozhi-server/" + configuredPath)) == null) {
                result.setSuccess(false);
                result.setMessage("本地模型路径不存在：" + configuredPath);
                return result;
            }
        }

        result.setSuccess(true);
        result.setMessage("模型配置完整性测试通过");
        return result;
    }

    private List<String> getRequiredFields(String modelType, String type) {
        String normalizedModelType = StringUtils.defaultString(modelType).toUpperCase();
        String normalizedType = StringUtils.defaultString(type);
        if ("ASR".equals(normalizedModelType)) {
            return switch (normalizedType) {
                case "fun_local", "sherpa_onnx_local" -> List.of("model_dir");
                case "vosk" -> List.of("model_path");
                case "fun_server" -> List.of("host", "port");
                case "doubao" -> List.of("appid", "access_token", "cluster");
                case "tencent" -> List.of("appid", "secret_id", "secret_key");
                case "aliyun", "aliyun_stream" -> List.of("appkey", "access_key_id", "access_key_secret");
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
                case "aliyun", "aliyun_stream" -> List.of("appkey", "access_key_id", "access_key_secret", "voice");
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
                case "AliBL" -> List.of("base_url", "app_id", "api_key");
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
            return "silero".equals(normalizedType) ? List.of("model_dir") : List.of();
        }
        if ("RAG".equals(normalizedModelType)) {
            return "ragflow".equals(normalizedType) ? List.of("base_url", "api_key") : List.of();
        }
        return List.of();
    }

    private List<List<String>> getRequiredAnyFieldGroups(String modelType, String type) {
        String normalizedModelType = StringUtils.defaultString(modelType).toUpperCase();
        String normalizedType = StringUtils.defaultString(type);
        if ("TTS".equals(normalizedModelType) && "doubao".equals(normalizedType)) {
            return List.of(List.of("access_token", "authorization"));
        }
        return List.of();
    }

    private String getLocalPathField(String modelType, String type) {
        String normalizedModelType = StringUtils.defaultString(modelType).toUpperCase();
        String normalizedType = StringUtils.defaultString(type);
        if ("VAD".equals(normalizedModelType) && "silero".equals(normalizedType)) {
            return "model_dir";
        }
        if ("ASR".equals(normalizedModelType)) {
            return switch (normalizedType) {
                case "fun_local", "sherpa_onnx_local" -> "model_dir";
                case "vosk" -> "model_path";
                default -> null;
            };
        }
        return null;
    }

    private boolean hasUsableConfigValue(JSONObject configJson, String field) {
        Object value = configJson.get(field);
        if (value == null) {
            return false;
        }
        String stringValue = StringUtils.trim(String.valueOf(value));
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
