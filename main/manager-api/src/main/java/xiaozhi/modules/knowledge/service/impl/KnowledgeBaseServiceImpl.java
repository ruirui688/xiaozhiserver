package xiaozhi.modules.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.BeanUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.common.redis.RedisKeys;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.service.impl.BaseServiceImpl;
import xiaozhi.common.utils.ConvertUtils;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.knowledge.dao.KnowledgeBaseDao;
import xiaozhi.modules.knowledge.dao.KnowledgeChunkDao;
import xiaozhi.modules.knowledge.dao.DocumentDao;
import xiaozhi.modules.knowledge.entity.DocumentEntity;
import xiaozhi.modules.knowledge.entity.KnowledgeChunkEntity;
import xiaozhi.modules.knowledge.dto.KnowledgeBaseDTO;
import xiaozhi.modules.knowledge.dto.dataset.DatasetDTO;
import xiaozhi.modules.knowledge.entity.KnowledgeBaseEntity;
import xiaozhi.modules.knowledge.rag.KnowledgeBaseAdapter;
import xiaozhi.modules.knowledge.rag.KnowledgeBaseAdapterFactory;
import xiaozhi.modules.knowledge.rag.impl.local.KnowledgeBaseParserConfigCodec;
import xiaozhi.modules.knowledge.rag.impl.local.LocalKnowledgeProcessingStrategy;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.knowledge.service.KnowledgeResourcePermissionService;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;
import xiaozhi.modules.workspace.service.WorkspaceService;
import xiaozhi.modules.workspace.entity.WorkspaceEntity;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库服务实现类 (Refactored)
 * 集成 RAGFlow Adapter 与 Shadow DB 模式
 */
@Service
@lombok.RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseServiceImpl extends BaseServiceImpl<KnowledgeBaseDao, KnowledgeBaseEntity>
        implements KnowledgeBaseService {

    private final KnowledgeBaseDao knowledgeBaseDao;
    @org.springframework.beans.factory.annotation.Autowired
    private xiaozhi.modules.knowledge.service.KnowledgeEntryCleanupService entryCleanup;
    private final KnowledgeChunkDao knowledgeChunkDao;
    private final DocumentDao documentDao;
    private final ModelConfigService modelConfigService;
    private final ModelConfigDao modelConfigDao;
    private final RedisUtils redisUtils;
    private final WorkspaceService workspaceService;
    private final WorkspacePermissionService workspacePermissionService;
    private final KnowledgeResourcePermissionService knowledgePermissionService;
    private final ObjectMapper objectMapper;

    @Override
    public PageData<KnowledgeBaseDTO> getPageList(
            KnowledgeBaseDTO knowledgeBaseDTO, String scope, Integer page, Integer limit) {
        Page<KnowledgeBaseEntity> pageInfo = new Page<>(page, limit);
        QueryWrapper<KnowledgeBaseEntity> queryWrapper = new QueryWrapper<>();
        boolean officialListing = KnowledgeResourcePermissionService.SCOPE_OFFICIAL.equalsIgnoreCase(scope);

        if (knowledgeBaseDTO != null) {
            queryWrapper.like(StringUtils.isNotBlank(knowledgeBaseDTO.getName()), "name", knowledgeBaseDTO.getName());
            queryWrapper.eq(knowledgeBaseDTO.getStatus() != null, "status", knowledgeBaseDTO.getStatus());
            if (officialListing) {
                queryWrapper.eq("scope", KnowledgeResourcePermissionService.SCOPE_OFFICIAL);
            } else {
                queryWrapper.eq("workspace_id", workspaceService.getCurrentWorkspaceEntity().getId())
                        .eq("scope", KnowledgeResourcePermissionService.SCOPE_WORKSPACE);
            }
        }
        queryWrapper.orderByDesc("created_at");

        IPage<KnowledgeBaseEntity> iPage = knowledgeBaseDao.selectPage(pageInfo, queryWrapper);
        PageData<KnowledgeBaseDTO> pageData = getPageData(iPage, KnowledgeBaseDTO.class);

        // Enrich with Document Count from RAG (Optional / Lazy)
        if (pageData != null && pageData.getList() != null) {
            pageData.getList().removeIf(dto -> {
                // 全局只读列表不能触发远端对账、重命名或级联清理等写操作。
                if (!officialListing) {
                    enrichDocumentCount(dto);
                }
                dto.setCanEdit(knowledgePermissionService.canWrite(dto));
                dto.setCanManageMaintenance(knowledgePermissionService.canManageAutomatedMaintenance(dto));
                dto.setCanPublishOfficial(knowledgePermissionService.isOfficialMaintainer());
                // syncDatasetFromRAG 检测到 RAGFlow 端已删除时，会将本地记录清理
                // 此时 datasetId 被置空作为标记，需要在列表中移除该条目
                return dto.getDatasetId() == null;
            });
        }
        return pageData;
    }

    private void enrichDocumentCount(KnowledgeBaseDTO dto) {
        syncDatasetFromRAG(dto);
    }

    /**
     * 从 RAGFlow 同步数据集信息：检测删除、同步名称/简介、获取文档数量
     * 每次列表刷新时实时查询 RAGFlow，确保立即感知远端变更
     */
    private void syncDatasetFromRAG(KnowledgeBaseDTO dto) {
        try {
            if (StringUtils.isBlank(dto.getDatasetId()) || StringUtils.isBlank(dto.getRagModelId())) {
                return;
            }

            KnowledgeBaseAdapter adapter = getAdapterByModelId(dto.getRagModelId());
            if (adapter == null) {
                return;
            }

            DatasetDTO.InfoVO datasetInfo = adapter.getDatasetInfo(dto.getDatasetId());

            if (datasetInfo == null) {
                // RAGFlow 端已删除 → 本地级联清理
                log.info("数据集 {} 在 RAGFlow 端不存在，执行本地清理", dto.getDatasetId());
                cleanupLocalDataset(dto.getDatasetId(), dto.getId());
                // 标记为已删除，让上层从列表中移除
                dto.setDatasetId(null);
                return;
            }

            // 同步名称（去掉 username_ 前缀）
            // [Issue #7] 使用当前登录用户名作为前缀匹配，而非简单查找第一个下划线。
            // 例如 username=john_doe, datasetName=my_dataset, RAGFlow存储为 john_doe_my_dataset。
            // 之前用 ragflowName.indexOf('_') 会错误截取为 doe_my_dataset。
            String ragflowName = datasetInfo.getName();
            if (StringUtils.isNotBlank(ragflowName)) {
                String localName = ragflowName;
                // 尝试用当前用户名前缀剥离
                String currentUsername = null;
                try {
                    currentUsername = SecurityUser.getUser().getUsername();
                } catch (Exception e) {
                    log.warn("无法获取当前用户名，使用备用剥离策略: {}", e.getMessage());
                }
                if (StringUtils.isNotBlank(currentUsername)) {
                    String prefix = currentUsername + "_";
                    if (ragflowName.startsWith(prefix)) {
                        localName = ragflowName.substring(prefix.length());
                    }
                }
                // 备用方案：取最后一个下划线后的部分
                if (localName.equals(ragflowName) && ragflowName.contains("_")) {
                    localName = ragflowName.substring(ragflowName.lastIndexOf('_') + 1);
                }
                if (!localName.equals(dto.getName())) {
                    log.info("同步知识库名称: {} -> {}", dto.getName(), localName);
                    KnowledgeBaseEntity entity = knowledgeBaseDao.selectById(dto.getId());
                    if (entity != null) {
                        entity.setName(localName);
                        knowledgeBaseDao.updateById(entity);
                        dto.setName(localName);
                    }
                }
            }

            // 同步简介
            String ragflowDesc = datasetInfo.getDescription();
            String localDesc = dto.getDescription();
            boolean descChanged = (ragflowDesc == null && localDesc != null) || (ragflowDesc != null && !ragflowDesc.equals(localDesc));
            if (descChanged) {
                log.info("同步知识库简介: datasetId={}", dto.getDatasetId());
                KnowledgeBaseEntity entity = knowledgeBaseDao.selectById(dto.getId());
                if (entity != null) {
                    entity.setDescription(ragflowDesc);
                    knowledgeBaseDao.updateById(entity);
                    dto.setDescription(ragflowDesc);
                }
            }

            // 设置文档数量（保留原有功能）
            if (datasetInfo.getDocumentCount() != null) {
                dto.setDocumentCount(datasetInfo.getDocumentCount().intValue());
            }

        } catch (Exception e) {
            log.warn("同步数据集信息失败 {}: {}", dto.getName(), e.getMessage());
            dto.setDocumentCount(0);
        }
    }

    /**
     * 本地级联清理：RAGFlow 端已删除时，清理本地所有关联数据
     * 不调用 RAGFlow 删除 API
     */
    @Transactional(rollbackFor = Exception.class)
    public void cleanupLocalDataset(String datasetId, String entityId) {
        try {
            // 1. 删除文档影子记录
            documentDao.delete(new QueryWrapper<DocumentEntity>().eq("dataset_id", datasetId));
            // 2. 删除插件映射
            knowledgeBaseDao.deletePluginMappingByKnowledgeBaseId(entityId);
            // 3. 删除知识库记录
            knowledgeBaseDao.deleteById(entityId);
            // 4. 清理缓存
            redisUtils.delete(RedisKeys.getKnowledgeBaseCacheKey(entityId));
            log.info("本地级联清理完成: datasetId={}, entityId={}", datasetId, entityId);
        } catch (Exception e) {
            log.error("本地级联清理失败: datasetId={}, entityId={}", datasetId, entityId, e);
        }
    }

    @Override
    public KnowledgeBaseDTO getById(String id) {
        KnowledgeBaseEntity entity = knowledgeBaseDao.selectById(id);
        if (entity == null) {
            throw new RenException(ErrorCode.Knowledge_Base_RECORD_NOT_EXISTS);
        }
        return ConvertUtils.sourceToTarget(entity, KnowledgeBaseDTO.class);
    }

    @Override
    public KnowledgeBaseDTO getByDatasetId(String datasetId) {
        if (StringUtils.isBlank(datasetId)) {
            throw new RenException(ErrorCode.PARAMS_GET_ERROR);
        }
        // [Production Fix] 兼容性查找：优先通过 dataset_id 找，找不到通过主键 id 找，确保前端传哪种 UUID 都能命中
        KnowledgeBaseEntity entity = knowledgeBaseDao
                .selectOne(new QueryWrapper<KnowledgeBaseEntity>()
                        .eq("dataset_id", datasetId)
                        .or()
                        .eq("id", datasetId));
        if (entity == null) {
            throw new RenException(ErrorCode.Knowledge_Base_RECORD_NOT_EXISTS);
        }
        return ConvertUtils.sourceToTarget(entity, KnowledgeBaseDTO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseDTO save(KnowledgeBaseDTO dto) {
        // 1. Validation
        WorkspaceEntity workspace = workspaceService.getCurrentWorkspaceEntity();
        workspacePermissionService.requireWrite(workspace.getId());
        checkDuplicateName(dto.getName(), null);
        KnowledgeBaseAdapter adapter = null;

        // 2. RAG Creation
        String datasetId = null;
        try {
            // 若未指定 RAG 模型，自动使用系统默认
            if (StringUtils.isBlank(dto.getRagModelId())) {
                List<ModelConfigEntity> models = getRAGModels();
                if (models != null && !models.isEmpty()) {
                    dto.setRagModelId(models.get(0).getId());
                } else {
                    throw new RenException(ErrorCode.RAG_CONFIG_NOT_FOUND, "未指定且无可用默认 RAG 模型");
                }
            }

            Map<String, Object> ragConfig = getValidatedRAGConfig(dto.getRagModelId());
            enforceLocalProcessingConfig(dto, ragConfig);
            adapter = KnowledgeBaseAdapterFactory.getAdapter((String) ragConfig.get("type"),
                    ragConfig);

            DatasetDTO.CreateReq createReq = ConvertUtils.sourceToTarget(dto, DatasetDTO.CreateReq.class);
            createReq.setName(SecurityUser.getUser().getUsername() + "_" + dto.getName());
            createReq.setParserConfig(
                    KnowledgeBaseParserConfigCodec.decodeDatasetConfig(dto.getParserConfig(), objectMapper));

            DatasetDTO.InfoVO ragResponse = adapter.createDataset(createReq);
            if (ragResponse == null || StringUtils.isBlank(ragResponse.getId())) {
                throw new RenException(ErrorCode.RAG_API_ERROR, "RAG创建返回无效: 缺失ID");
            }
            datasetId = ragResponse.getId();

            // 3. Local Save (Shadow)
            KnowledgeBaseEntity entity = ConvertUtils.sourceToTarget(dto, KnowledgeBaseEntity.class);
            entity.setWorkspaceId(workspace.getId());
            entity.setScope(KnowledgeResourcePermissionService.SCOPE_WORKSPACE);

            // [Production Fix] 统一本地 ID 与 RAGFlow ID，防止前端调用 /delete 或 /update 时因 ID 混淆（本地
            // UUID vs RAG UUID）导致 10163 错误
            entity.setId(datasetId);
            entity.setDatasetId(datasetId);
            entity.setStatus(1); // Default Enabled

            // ✅ FULL PERSISTENCE: 严格全量回写 (User Requirement)
            // 使用强类型 DTO 属性获取，不再从 Map 中手动解析 Key
            entity.setTenantId(ragResponse.getTenantId());
            entity.setChunkMethod(ragResponse.getChunkMethod());
            entity.setEmbeddingModel(ragResponse.getEmbeddingModel());
            entity.setPermission(ragResponse.getPermission());

            if (StringUtils.isBlank(entity.getAvatar())) {
                entity.setAvatar(ragResponse.getAvatar());
            }

            // Parse Config (JSON)
            if (StringUtils.isBlank(entity.getParserConfig()) && ragResponse.getParserConfig() != null) {
                entity.setParserConfig(JsonUtils.toJsonString(ragResponse.getParserConfig()));
            }

            // Numeric fields
            entity.setChunkCount(ragResponse.getChunkCount() != null ? ragResponse.getChunkCount() : 0L);
            entity.setDocumentCount(ragResponse.getDocumentCount() != null ? ragResponse.getDocumentCount() : 0L);
            entity.setTokenNum(ragResponse.getTokenNum() != null ? ragResponse.getTokenNum() : 0L);

            // 清空 creator/updater，让 FieldMetaObjectHandler 从 SecurityUser 自动填充
            // ConvertUtils 会把 DTO 中的 creator=0 拷贝过来，导致 strictInsertFill 跳过填充
            entity.setCreator(null);
            entity.setUpdater(null);

            knowledgeBaseDao.insert(entity);
            return ConvertUtils.sourceToTarget(entity, KnowledgeBaseDTO.class);
        } catch (Exception e) {
            log.error("RAG创建或本地保存失败", e);
            // 如果datasetId已生成但在保存本地时失败，尝试回滚RAG (Best Effort)
            if (StringUtils.isNotBlank(datasetId)) {
                try {
                    if (adapter != null)
                        adapter.deleteDataset(
                                DatasetDTO.BatchIdReq.builder().ids(Collections.singletonList(datasetId)).build());
                } catch (Exception rollbackEx) {
                    log.error("RAG回滚失败: {}", datasetId, rollbackEx);
                }
            }
            if (e instanceof RenException) {
                throw (RenException) e;
            }
            throw new RenException(ErrorCode.RAG_API_ERROR, "创建知识库失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @SuppressWarnings("deprecation")
    public KnowledgeBaseDTO update(KnowledgeBaseDTO dto) {
        log.info("Update Service Called: ID={}, DatasetID={}", dto.getId(), dto.getDatasetId());
        KnowledgeBaseEntity entity = knowledgeBaseDao.selectById(dto.getId());
        if (entity == null) {
            log.error("Update failed: Entity not found for ID={}", dto.getId());
            throw new RenException(ErrorCode.Knowledge_Base_RECORD_NOT_EXISTS);
        }
        knowledgePermissionService.requireWrite(ConvertUtils.sourceToTarget(entity, KnowledgeBaseDTO.class));

        checkDuplicateName(dto.getName(), dto.getId());

        // 验证数据集ID是否与其他记录冲突
        if (StringUtils.isNotBlank(dto.getDatasetId())) {
            KnowledgeBaseEntity conflictEntity = knowledgeBaseDao.selectOne(
                    new QueryWrapper<KnowledgeBaseEntity>()
                            .eq("dataset_id", dto.getDatasetId())
                            .ne("id", dto.getId()));
            if (conflictEntity != null) {
                throw new RenException(ErrorCode.DB_RECORD_EXISTS);
            }
        }

        // RAG Update if needed
        if (StringUtils.isNotBlank(entity.getDatasetId())) {
            try {
                if (StringUtils.isBlank(dto.getRagModelId())) {
                    dto.setRagModelId(entity.getRagModelId());
                }

                // [FIX] 智能补全：如果 DTO 里的关键字段为空，则使用 Entity 里的旧值
                // 确保发给 RAGFlow 的请求包含所有必填项 (Partial Update Support)
                if (StringUtils.isBlank(dto.getPermission())) {
                    dto.setPermission(entity.getPermission());
                }
                if (StringUtils.isBlank(dto.getChunkMethod())) {
                    dto.setChunkMethod(entity.getChunkMethod());
                }
                if (StringUtils.isBlank(dto.getParserConfig())) {
                    dto.setParserConfig(entity.getParserConfig());
                }

                Map<String, Object> ragConfig = getValidatedRAGConfig(dto.getRagModelId());
                enforceLocalProcessingConfig(dto, ragConfig);
                KnowledgeBaseAdapter adapter = KnowledgeBaseAdapterFactory.getAdapter(
                        (String) ragConfig.get("type"), ragConfig);
                if (adapter != null) {
                    DatasetDTO.UpdateReq updateReq = ConvertUtils.sourceToTarget(dto, DatasetDTO.UpdateReq.class);

                    // 1. 必填/核心字段前缀处理
                    if (StringUtils.isNotBlank(dto.getName())) {
                        updateReq.setName(SecurityUser.getUser().getUsername() + "_" + dto.getName());
                    }

                    // 2. 解析器配置支持 (如果 DTO 里有字符串形式的配置，尝试转换，但优先建议 DTO 化)
                    if (StringUtils.isNotBlank(dto.getParserConfig())) {
                        updateReq.setParserConfig(
                                KnowledgeBaseParserConfigCodec.decodeDatasetConfig(dto.getParserConfig(), objectMapper));
                    }

                    adapter.updateDataset(entity.getDatasetId(), updateReq);
                    log.info("RAG更新成功: {}", entity.getDatasetId());
                }
            } catch (Exception e) {
                log.error("RAG更新失败", e);
                // 恢复事务一致性：RAG失败则整体回滚
                if (e instanceof RenException) {
                    throw (RenException) e;
                }
                throw new RenException(ErrorCode.RAG_API_ERROR, "RAG更新失败: " + e.getMessage());
            }
        }

        String preservedScope = entity.getScope();
        String preservedWorkspaceId = entity.getWorkspaceId();
        BeanUtils.copyProperties(dto, entity, "scope", "workspaceId", "canEdit", "canManageMaintenance",
                "canPublishOfficial");
        entity.setScope(preservedScope);
        entity.setWorkspaceId(preservedWorkspaceId);
        knowledgeBaseDao.updateById(entity);

        // Clean cache
        redisUtils.delete(RedisKeys.getKnowledgeBaseCacheKey(entity.getId()));

        return ConvertUtils.sourceToTarget(entity, KnowledgeBaseDTO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDatasetId(String datasetId) {
        if (StringUtils.isBlank(datasetId)) {
            throw new RenException(ErrorCode.PARAMS_GET_ERROR);
        }

        KnowledgeBaseEntity entity = knowledgeBaseDao
                .selectOne(new QueryWrapper<KnowledgeBaseEntity>().eq("dataset_id", datasetId).last("FOR UPDATE"));

        // 1. 恢复 404 校验：找不到记录抛异常
        if (entity == null) {
            log.warn("记录不存在，datasetId: {}", datasetId);
            throw new RenException(ErrorCode.Knowledge_Base_RECORD_NOT_EXISTS);
        }
        knowledgePermissionService.requireWrite(ConvertUtils.sourceToTarget(entity, KnowledgeBaseDTO.class));
        log.info("找到记录: ID={}, datasetId={}, ragModelId={}",
                entity.getId(), entity.getDatasetId(), entity.getRagModelId());

        // 2. RAG Delete (Strict Mode)
        // 恢复严格一致性：RAG 删除失败则抛出异常，触发事务回滚，不允许已删除本地但保留远程的脏数据
        boolean apiDeleteSuccess = false;
        if (StringUtils.isNotBlank(entity.getRagModelId()) && StringUtils.isNotBlank(entity.getDatasetId())) {
            try {
                KnowledgeBaseAdapter adapter = getAdapterByModelId(entity.getRagModelId());
                if (adapter != null) {
                    adapter.deleteDataset(
                            DatasetDTO.BatchIdReq.builder().ids(Collections.singletonList(datasetId)).build());
                }
                apiDeleteSuccess = true;
            } catch (Exception e) {
                log.error("RAG删除失败，触发回滚", e);
                if (e instanceof RenException) {
                    throw (RenException) e;
                }
                throw new RenException(ErrorCode.RAG_API_ERROR, "RAG删除失败: " + e.getMessage());
            }
        } else {
            log.warn("datasetId或ragModelId为空，跳过RAG删除");
            apiDeleteSuccess = true; // 没有RAG数据集，视为成功
        }

        // 3. Local Delete (Safe Order)
        // 恢复正确顺序：先删子表 (Chunks → Plugin Mapping → Documents)，再删主表 (Entity)
        if (apiDeleteSuccess) {
            entryCleanup.delete(datasetId, null);
            // [Issue #13] 清理可能残留的本地知识库切片，防止适配器切换后产生孤立数据
            try {
                int chunkDeleted = knowledgeChunkDao.delete(
                        new QueryWrapper<KnowledgeChunkEntity>().eq("dataset_id", datasetId));
                if (chunkDeleted > 0) {
                    log.info("已清理 {} 个本地知识库切片记录, datasetId={}", chunkDeleted, datasetId);
                }
            } catch (Exception e) {
                log.warn("清理本地切片记录失败(不影响主流程): {}", e.getMessage());
            }
            log.info("开始删除ai_agent_plugin_mapping表中与知识库ID '{}' 相关的映射记录", entity.getId());
            log.info("开始删除关联数据, entityId: {}", entity.getId());
            knowledgeBaseDao.deletePluginMappingByKnowledgeBaseId(entity.getId());
            log.info("插件映射记录删除完成");
            int deleteCount = knowledgeBaseDao.deleteById(entity.getId());
            log.info("本地数据库删除结果: {}", deleteCount > 0 ? "成功" : "失败");
            redisUtils.delete(RedisKeys.getKnowledgeBaseCacheKey(entity.getId()));
        }
    }

    @Override
    public List<KnowledgeBaseDTO> getByDatasetIdList(List<String> datasetIdList) {
        if (datasetIdList == null || datasetIdList.isEmpty()) {
            return Collections.emptyList();
        }
        // [Production Fix] 批量兼容性查找
        QueryWrapper<KnowledgeBaseEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("dataset_id", datasetIdList).or().in("id", datasetIdList);
        List<KnowledgeBaseEntity> list = knowledgeBaseDao.selectList(queryWrapper);
        return ConvertUtils.sourceToTarget(list, KnowledgeBaseDTO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseDTO updatePublicationScope(String datasetId, String scope) {
        KnowledgeBaseDTO existing = getByDatasetId(datasetId);
        knowledgePermissionService.requireWrite(existing);
        String normalized = StringUtils.upperCase(StringUtils.trim(scope));
        if (!KnowledgeResourcePermissionService.SCOPE_WORKSPACE.equals(normalized)
                && !KnowledgeResourcePermissionService.SCOPE_OFFICIAL.equals(normalized)) {
            throw new RenException(ErrorCode.PARAMS_GET_ERROR);
        }
        if (KnowledgeResourcePermissionService.SCOPE_OFFICIAL.equals(normalized)) {
            existing.setScope(KnowledgeResourcePermissionService.SCOPE_OFFICIAL);
            knowledgePermissionService.requireAutomatedMaintenance(existing);
        }
        KnowledgeBaseEntity entity = knowledgeBaseDao.selectById(existing.getId());
        entity.setScope(normalized);
        knowledgeBaseDao.updateById(entity);
        KnowledgeBaseDTO result = ConvertUtils.sourceToTarget(entity, KnowledgeBaseDTO.class);
        result.setCanEdit(knowledgePermissionService.canWrite(result));
        result.setCanManageMaintenance(knowledgePermissionService.canManageAutomatedMaintenance(result));
        result.setCanPublishOfficial(knowledgePermissionService.isOfficialMaintainer());
        return result;
    }

    @Override
    public Map<String, Object> getRAGConfig(String ragModelId) {
        return getValidatedRAGConfig(ragModelId);
    }

    @Override
    public Map<String, Object> getRAGConfigByDatasetId(String datasetId) {
        KnowledgeBaseEntity entity = knowledgeBaseDao
                .selectOne(new QueryWrapper<KnowledgeBaseEntity>().eq("dataset_id", datasetId));
        if (entity == null || StringUtils.isBlank(entity.getRagModelId())) {
            throw new RenException(ErrorCode.RAG_CONFIG_NOT_FOUND);
        }
        Map<String, Object> config = getRAGConfig(entity.getRagModelId());
        mergeDatasetParserConfig(config, entity.getParserConfig());
        return config;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatistics(String datasetId, Integer docDelta, Long chunkDelta, Long tokenDelta) {
        log.info("递增更新知识库统计: datasetId={}, docs={}, chunks={}, tokens={}", datasetId, docDelta, chunkDelta, tokenDelta);
        knowledgeBaseDao.updateStatsAfterChange(datasetId, docDelta, chunkDelta, tokenDelta);
    }

    @Override
    public List<ModelConfigEntity> getRAGModels() {
        return modelConfigDao.selectList(new QueryWrapper<ModelConfigEntity>()
                .select("id", "model_name", "config_json") // Explicitly select needed fields
                .eq("model_type", Constant.RAG_CONFIG_TYPE)
                .eq("is_enabled", 1)
                .orderByDesc("is_default")
                .orderByDesc("create_date"));
    }

    // --- Helpers ---

    private void checkDuplicateName(String name, String excludeId) {
        if (StringUtils.isBlank(name))
            return;
        QueryWrapper<KnowledgeBaseEntity> qw = new QueryWrapper<>();
        qw.eq("name", name).eq("workspace_id", workspaceService.getCurrentWorkspaceEntity().getId());
        if (excludeId != null)
            qw.ne("id", excludeId);
        if (knowledgeBaseDao.selectCount(qw) > 0) {
            throw new RenException(ErrorCode.KNOWLEDGE_BASE_NAME_EXISTS);
        }
    }

    private KnowledgeBaseAdapter getAdapterByModelId(String modelId) {
        Map<String, Object> config = getValidatedRAGConfig(modelId);
        return KnowledgeBaseAdapterFactory.getAdapter((String) config.get("type"), config);
    }

    private Map<String, Object> getValidatedRAGConfig(String modelId) {
        ModelConfigEntity configEntity = modelConfigService.getModelByIdFromCache(modelId);
        if (configEntity == null || configEntity.getConfigJson() == null) {
            throw new RenException(ErrorCode.RAG_CONFIG_NOT_FOUND);
        }
        Map<String, Object> config = new HashMap<>(configEntity.getConfigJson());
        if (!config.containsKey("type")) {
            config.put("type", "ragflow");
        }
        return config;
    }

    private void mergeDatasetParserConfig(Map<String, Object> config, String parserConfig) {
        if (StringUtils.isBlank(parserConfig)) {
            return;
        }
        try {
            Map<String, Object> datasetConfig = JsonUtils.parseObject(parserConfig, Map.class);
            if (datasetConfig == null || datasetConfig.isEmpty()) {
                return;
            }
            for (String key : List.of(
                    "processing_strategy",
                    "max_chunk_chars",
                    "llm_enabled",
                    "llm_model_id",
                    "llm_timeout_seconds",
                    "retrieval_config",
                    "mode",
                    "search_type",
                    "semantic_level",
                    "semantic_top_k",
                    "faq_threshold",
                    "semantic_threshold",
                    "rerank_enabled",
                    "keyword_level",
                    "keyword_top_k",
                    "keyword_threshold")) {
                Object value = datasetConfig.get(key);
                if (value != null && StringUtils.isNotBlank(value.toString())) {
                    config.put(key, value);
                }
            }
        } catch (Exception e) {
            log.warn("解析知识库 parser_config 失败，使用RAG模型默认配置: {}", e.getMessage());
        }
    }

    private void enforceLocalProcessingConfig(KnowledgeBaseDTO dto, Map<String, Object> ragConfig) {
        if (!"local_knowledge".equals(ragConfig.get("type"))) {
            return;
        }
        Map<String, Object> parserConfig;
        try {
            parserConfig = StringUtils.isBlank(dto.getParserConfig())
                    ? new HashMap<>()
                    : new HashMap<>(JsonUtils.parseObject(dto.getParserConfig(), Map.class));
        } catch (Exception e) {
            throw new RenException("parserConfig不是有效的JSON对象", e);
        }
        String strategyValue = parserConfig.get("processing_strategy") == null
                ? LocalKnowledgeProcessingStrategy.LLM_ENHANCED.configValue()
                : parserConfig.get("processing_strategy").toString().trim();
        if (!LocalKnowledgeProcessingStrategy.isSupported(strategyValue)) {
            throw new RenException("不支持的本地知识库处理策略: " + strategyValue);
        }
        LocalKnowledgeProcessingStrategy strategy = LocalKnowledgeProcessingStrategy.fromConfig(strategyValue);
        String llmModelId = parserConfig.get("llm_model_id") == null
                ? ""
                : parserConfig.get("llm_model_id").toString().trim();
        if (strategy == LocalKnowledgeProcessingStrategy.LLM_ENHANCED
                && StringUtils.isBlank(llmModelId)) {
            throw new RenException("大模型语义增强策略必须选择切片模型");
        }
        if (strategy != LocalKnowledgeProcessingStrategy.DIRECT && StringUtils.isNotBlank(llmModelId)) {
            validateChunkingModel(llmModelId);
        }

        parserConfig.put("processing_strategy", strategy.configValue());
        parserConfig.put("llm_enabled", strategy != LocalKnowledgeProcessingStrategy.DIRECT
                && StringUtils.isNotBlank(llmModelId));
        parserConfig.put("llm_model_id", llmModelId);
        parserConfig.remove("llm_base_url");
        parserConfig.remove("llm_api_key");
        parserConfig.remove("llm_model");
        dto.setParserConfig(JsonUtils.toJsonString(parserConfig));
    }

    private void validateChunkingModel(String llmModelId) {
        ModelConfigEntity model = modelConfigService.getModelByIdFromCache(llmModelId);
        if (model == null || !"LLM".equalsIgnoreCase(model.getModelType())) {
            throw new RenException("切片模型不存在或不是LLM类型");
        }
        if (!Integer.valueOf(1).equals(model.getIsEnabled())
                || !Integer.valueOf(1).equals(model.getTestStatus())) {
            throw new RenException("切片模型必须已启用并通过真实模型测试");
        }
        String adapterCode = model.getAdapterCode();
        if (StringUtils.isBlank(adapterCode) && model.getConfigJson() != null) {
            adapterCode = model.getConfigJson().getStr("type");
        }
        boolean openAiCompatible = "openai".equalsIgnoreCase(adapterCode)
                || StringUtils.containsIgnoreCase(model.getProtocol(), "openai");
        if (!openAiCompatible) {
            throw new RenException("切片模型必须兼容OpenAI Chat Completions协议");
        }
    }
}
