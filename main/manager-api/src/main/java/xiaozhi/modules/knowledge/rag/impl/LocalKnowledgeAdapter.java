package xiaozhi.modules.knowledge.rag.impl;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import cn.hutool.json.JSONObject;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.common.utils.SpringContextUtils;
import xiaozhi.modules.knowledge.dao.DocumentDao;
import xiaozhi.modules.knowledge.dao.KnowledgeBaseDao;
import xiaozhi.modules.knowledge.dao.KnowledgeChunkDao;
import xiaozhi.modules.knowledge.dto.KnowledgeFilesDTO;
import xiaozhi.modules.knowledge.dto.dataset.DatasetDTO;
import xiaozhi.modules.knowledge.dto.document.ChunkDTO;
import xiaozhi.modules.knowledge.dto.document.DocumentDTO;
import xiaozhi.modules.knowledge.dto.document.RetrievalDTO;
import xiaozhi.modules.knowledge.entity.DocumentEntity;
import xiaozhi.modules.knowledge.entity.KnowledgeBaseEntity;
import xiaozhi.modules.knowledge.entity.KnowledgeChunkEntity;
import xiaozhi.modules.knowledge.rag.KnowledgeBaseAdapter;
import xiaozhi.modules.llm.service.EmbeddingService;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;

@Slf4j
public class LocalKnowledgeAdapter extends KnowledgeBaseAdapter {
    private static final String ADAPTER_TYPE = "local_knowledge";
    private static final int DEFAULT_MAX_CHUNK_CHARS = 1200;
    private static final String DEFAULT_LLM_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_LLM_MODEL = "qwen-plus";
    private static final int DEFAULT_LLM_TIMEOUT_SECONDS = 90;
    private static final int DEFAULT_LLM_MAX_INPUT_CHARS = 12000;
    private static final int DEFAULT_LLM_MAX_TOKENS = 4000;
    private static final int DEFAULT_RETRIEVAL_TOP_K = 10;
    private static final int DEFAULT_RETRIEVAL_LEVEL = 4;
    private static final int DEFAULT_CANDIDATE_LIMIT = 400;
    private static final int MAX_CANDIDATE_LIMIT = 2000;
    private static final int MAX_RETRIEVAL_TERMS = 32;

    private Map<String, Object> config = new HashMap<>();
    private ObjectMapper objectMapper;
    private KnowledgeChunkDao chunkDao;
    private DocumentDao documentDao;
    private KnowledgeBaseDao knowledgeBaseDao;
    private ModelConfigService modelConfigService;
    private int maxChunkChars = DEFAULT_MAX_CHUNK_CHARS;
    private boolean llmEnabled;
    private String llmModelId;
    private String llmBaseUrl = DEFAULT_LLM_BASE_URL;
    private String llmApiKey;
    private String llmModel = DEFAULT_LLM_MODEL;
    private int llmTimeoutSeconds = DEFAULT_LLM_TIMEOUT_SECONDS;
    private int llmMaxInputChars = DEFAULT_LLM_MAX_INPUT_CHARS;
    private int llmMaxTokens = DEFAULT_LLM_MAX_TOKENS;
    private RestTemplate llmRestTemplate;
    private EmbeddingService embeddingService;
    private boolean embeddingEnabled;
    private String embeddingBaseUrl;
    private String embeddingApiKey;
    private String embeddingModel;
    private boolean llmChunkingFailed;
    private boolean hasRetrievalConfig;
    private String retrievalMode = "normal";
    private String searchType = "semantic";
    private int semanticLevel = DEFAULT_RETRIEVAL_LEVEL;
    private int semanticTopK = DEFAULT_RETRIEVAL_TOP_K;
    private float faqThreshold = 1.0F;
    private float semanticThreshold = 0.05F;
    private boolean rerankEnabled;

    @Override
    public String getAdapterType() {
        return ADAPTER_TYPE;
    }

    @Override
    public void initialize(Map<String, Object> config) {
        this.config = config == null ? new HashMap<>() : new HashMap<>(config);
        this.objectMapper = SpringContextUtils.getBean(ObjectMapper.class);
        this.chunkDao = SpringContextUtils.getBean(KnowledgeChunkDao.class);
        this.documentDao = SpringContextUtils.getBean(DocumentDao.class);
        this.knowledgeBaseDao = SpringContextUtils.getBean(KnowledgeBaseDao.class);
        this.modelConfigService = SpringContextUtils.getBean(ModelConfigService.class);
        maxChunkChars = getIntConfig("max_chunk_chars", DEFAULT_MAX_CHUNK_CHARS, 200, 20000);
        llmEnabled = getBooleanConfig("llm_enabled", false);
        llmModelId = getStringConfig("llm_model_id", "");
        llmBaseUrl = getStringConfig("llm_base_url", DEFAULT_LLM_BASE_URL);
        llmApiKey = getStringConfig("llm_api_key", "");
        llmModel = getStringConfig("llm_model", DEFAULT_LLM_MODEL);
        applyManagedLlmConfig();
        llmTimeoutSeconds = getIntConfig("llm_timeout_seconds", DEFAULT_LLM_TIMEOUT_SECONDS, 10, 600);
        llmMaxInputChars = getIntConfig("llm_max_input_chars", DEFAULT_LLM_MAX_INPUT_CHARS, 500, 20000);
        llmMaxTokens = getIntConfig("llm_max_tokens", DEFAULT_LLM_MAX_TOKENS, 200, 8000);
        // [Issue #1] Initialize EmbeddingService for vector similarity search
        try {
            this.embeddingService = SpringContextUtils.getBean(EmbeddingService.class);
        } catch (Exception e) {
            log.warn("本地知识库无法获取 EmbeddingService，向量检索将不可用: {}", e.getMessage());
            this.embeddingService = null;
        }
        this.embeddingBaseUrl = getStringConfig("embedding_base_url", getStringConfig("llm_base_url", ""));
        this.embeddingApiKey = getStringConfig("embedding_api_key", getStringConfig("llm_api_key", ""));
        this.embeddingModel = getStringConfig("embedding_model", "");
        this.embeddingEnabled = embeddingService != null
                && StringUtils.isNoneBlank(embeddingBaseUrl, embeddingApiKey, embeddingModel)
                && !isPlaceholderSecret(embeddingApiKey);
        if (this.embeddingEnabled) {
            log.info("本地知识库向量检索已启用, model={}", embeddingModel);
        }
        Map<String, Object> retrievalConfig = getMapConfig("retrieval_config");
        hasRetrievalConfig = retrievalConfig != null && !retrievalConfig.isEmpty();
        retrievalMode = getStringConfig(retrievalConfig, "mode", getStringConfig("mode", "normal"));
        searchType = getStringConfig(retrievalConfig, "search_type", getStringConfig("search_type", "semantic"));
        semanticLevel = getIntConfig(retrievalConfig, "semantic_level", DEFAULT_RETRIEVAL_LEVEL, 1, 5);
        semanticTopK = getIntConfig(retrievalConfig, "semantic_top_k", DEFAULT_RETRIEVAL_TOP_K, 1, 50);
        faqThreshold = getFloatConfig(retrievalConfig, "faq_threshold", 1.0F, 0F, 1F);
        semanticThreshold = getFloatConfig(retrievalConfig, "semantic_threshold", 0.05F, 0F, 1F);
        rerankEnabled = getBooleanConfig(retrievalConfig, "rerank_enabled", getBooleanConfig("rerank_enabled", false));
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(java.time.Duration.ofSeconds(llmTimeoutSeconds));
        this.llmRestTemplate = new RestTemplate(factory);
    }

    @Override
    public boolean validateConfig(Map<String, Object> config) {
        return true;
    }

    @Override
    public PageData<KnowledgeFilesDTO> getDocumentList(String datasetId, DocumentDTO.ListReq req) {
        int page = req.getPage() == null || req.getPage() < 1 ? 1 : req.getPage();
        int pageSize = req.getPageSize() == null || req.getPageSize() < 1 ? 10 : req.getPageSize();
        QueryWrapper<DocumentEntity> query = new QueryWrapper<>();
        query.eq("dataset_id", datasetId);
        if (StringUtils.isNotBlank(req.getId())) {
            query.eq("document_id", req.getId());
        }
        query.orderByDesc("created_at");
        IPage<DocumentEntity> docs = documentDao.selectPage(new Page<>(page, pageSize), query);
        List<KnowledgeFilesDTO> result = docs.getRecords().stream().map(this::toFileDTO).collect(Collectors.toList());
        return new PageData<>(result, docs.getTotal());
    }

    @Override
    public DocumentDTO.InfoVO getDocumentById(String datasetId, String documentId) {
        DocumentEntity entity = documentDao.selectOne(new QueryWrapper<DocumentEntity>()
                .eq("dataset_id", datasetId)
                .eq("document_id", documentId));
        if (entity == null) {
            return null;
        }
        DocumentDTO.InfoVO vo = new DocumentDTO.InfoVO();
        vo.setId(entity.getDocumentId());
        vo.setName(entity.getName());
        vo.setDatasetId(entity.getDatasetId());
        vo.setSize(entity.getSize());
        return vo;
    }

    @Override
    public KnowledgeFilesDTO uploadDocument(DocumentDTO.UploadReq req) {
        String documentId = newId();
        String filename = StringUtils.defaultIfBlank(req.getName(), req.getFile().getOriginalFilename());
        // [Issue #8] 重置 LLM 切片失败标记
        llmChunkingFailed = false;
        try {
            List<LocalChunk> chunks = parseFile(filename, req.getFile().getInputStream());
            replaceChunks(req.getDatasetId(), documentId, chunks);

            KnowledgeFilesDTO dto = new KnowledgeFilesDTO();
            dto.setId(documentId);
            dto.setDocumentId(documentId);
            dto.setDatasetId(req.getDatasetId());
            dto.setName(filename);
            dto.setFileSize(req.getFile().getSize());
            dto.setStatus("1");
            dto.setRun("DONE");
            dto.setProgress(1.0);
            dto.setSourceType(ADAPTER_TYPE);
            dto.setChunkCount(chunks.size());
            dto.setTokenCount(chunks.stream().mapToLong(item -> estimateTokens(item.getContent())).sum());
            dto.setCreatedAt(new Date());
            dto.setUpdatedAt(new Date());
            dto.setMetaFields(req.getMetaFields());
            // [Issue #8] 若 LLM 切片失败降级为规则切片，在元数据中记录质量降级提示
            if (llmChunkingFailed) {
                Map<String, Object> qualityNote = new HashMap<>();
                qualityNote.put("quality_degraded", true);
                qualityNote.put("note", "LLM切片失败降级为规则切片，切片质量可能低于预期");
                if (dto.getMetaFields() != null) {
                    dto.getMetaFields().putAll(qualityNote);
                } else {
                    dto.setMetaFields(qualityNote);
                }
                log.warn("本地知识库文档 {} 切片质量降级: LLM切片失败，使用规则切片", filename);
            }
            return dto;
        } catch (Exception e) {
            log.error("本地知识库文件解析失败: {}", filename, e);
            throw new RenException(ErrorCode.RAG_API_ERROR, "本地知识库文件解析失败: " + e.getMessage());
        }
    }

    @Override
    public PageData<KnowledgeFilesDTO> getDocumentListByStatus(String datasetId, Integer status, Integer page,
            Integer limit) {
        return getDocumentList(datasetId, DocumentDTO.ListReq.builder().page(page).pageSize(limit).build());
    }

    @Override
    public void deleteDocument(String datasetId, DocumentDTO.BatchIdReq req) {
        if (req == null || req.getIds() == null || req.getIds().isEmpty()) {
            return;
        }
        chunkDao.delete(new QueryWrapper<KnowledgeChunkEntity>()
                .eq("dataset_id", datasetId)
                .in("document_id", req.getIds()));
    }

    @Override
    public boolean parseDocuments(String datasetId, List<String> documentIds) {
        return true;
    }

    @Override
    public ChunkDTO.ListVO listChunks(String datasetId, String documentId, ChunkDTO.ListReq req) {
        int page = req.getPage() == null || req.getPage() < 1 ? 1 : req.getPage();
        int pageSize = req.getPageSize() == null || req.getPageSize() < 1 ? 10 : req.getPageSize();
        QueryWrapper<KnowledgeChunkEntity> query = new QueryWrapper<>();
        query.eq("dataset_id", datasetId).eq("document_id", documentId);
        if (StringUtils.isNotBlank(req.getId())) {
            query.eq("chunk_id", req.getId());
        }
        if (StringUtils.isNotBlank(req.getKeywords())) {
            query.like("content", req.getKeywords());
        }
        query.orderByAsc("sort_order");
        IPage<KnowledgeChunkEntity> pageResult = chunkDao.selectPage(new Page<>(page, pageSize), query);
        List<ChunkDTO.InfoVO> chunks = pageResult.getRecords().stream().map(this::toChunkVO).collect(Collectors.toList());
        return ChunkDTO.ListVO.builder()
                .chunks(chunks)
                .doc(getDocumentById(datasetId, documentId))
                .total(pageResult.getTotal())
                .build();
    }

    @Override
    public RetrievalDTO.ResultVO retrievalTest(RetrievalDTO.TestReq req) {
        String question = StringUtils.defaultString(req.getQuestion());
        int pageSize = clamp(req.getPageSize() == null ? 10 : req.getPageSize(), 1, 100);
        int resultLimit = resolveResultLimit(req, pageSize);
        Set<String> terms = tokenize(question.toLowerCase());
        List<String> candidateTerms = selectCandidateTerms(terms);
        int candidateLimit = resolveCandidateLimit(req, resultLimit);
        String fulltextQuery = buildFulltextQuery(candidateTerms);
        List<KnowledgeChunkEntity> chunks = loadRetrievalCandidates(req, candidateTerms, fulltextQuery, candidateLimit);

        // [Issue #1] 生成查询向量用于相似度检索
        float[] queryEmbedding;
        if (embeddingEnabled) {
            queryEmbedding = embeddingService.getEmbedding(
                    question, embeddingBaseUrl, embeddingApiKey, embeddingModel);
            if (queryEmbedding == null) {
                log.warn("本地知识库向量嵌入不可用，已回退关键词检索");
            }
        } else {
            queryEmbedding = null;
        }
        boolean vectorAvailable = queryEmbedding != null;

        Map<String, DocumentEntity> docs = loadDocs(chunks);
        List<ScoredChunk> scored = chunks.stream()
                .map(chunk -> {
                    float keywordScore = score(question, chunk, terms);
                    float vecScore = computeVectorSimilarity(queryEmbedding, chunk.getEmbeddingJson());
                    float combinedScore = computeCombinedScore(keywordScore, vecScore, vectorAvailable);
                    return new ScoredChunk(chunk, combinedScore, keywordScore, vecScore);
                })
                .filter(item -> shouldKeepScore(question, item.getScore()))
                .sorted(Comparator.comparing(ScoredChunk::getScore).reversed())
                .limit(resultLimit)
                .collect(Collectors.toList());

        List<RetrievalDTO.HitVO> hits = new ArrayList<>();
        int index = 0;
        for (ScoredChunk item : scored) {
            KnowledgeChunkEntity chunk = item.getChunk();
            DocumentEntity doc = docs.get(chunk.getDocumentId());
            hits.add(RetrievalDTO.HitVO.builder()
                    .id(chunk.getChunkId())
                    .content(chunk.getContent())
                    .documentId(chunk.getDocumentId())
                    .datasetId(chunk.getDatasetId())
                    .documentName(doc == null ? null : doc.getName())
                    .similarity(item.getScore())
                    .termSimilarity(item.getKeywordScore())
                    .vectorSimilarity(item.getVectorScore())
                    .index(index++)
                    .questions(StringUtils.isBlank(chunk.getQuestion()) ? null : List.of(chunk.getQuestion()))
                    .build());
        }

        Map<String, Long> docCounts = scored.stream()
                .collect(Collectors.groupingBy(item -> item.getChunk().getDocumentId(), Collectors.counting()));
        List<RetrievalDTO.DocAggVO> docAggs = docCounts.entrySet().stream()
                .map(entry -> {
                    DocumentEntity doc = docs.get(entry.getKey());
                    return RetrievalDTO.DocAggVO.builder()
                            .docId(entry.getKey())
                            .docName(doc == null ? entry.getKey() : doc.getName())
                            .count(entry.getValue().intValue())
                            .build();
                })
                .collect(Collectors.toList());

        return RetrievalDTO.ResultVO.builder()
                .chunks(hits)
                .docAggs(docAggs)
                .total((long) hits.size())
                .build();
    }

    private List<KnowledgeChunkEntity> loadRetrievalCandidates(
            RetrievalDTO.TestReq req,
            List<String> candidateTerms,
            String fulltextQuery,
            int candidateLimit) {
        Map<String, KnowledgeChunkEntity> candidates = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(fulltextQuery)) {
            addCandidates(candidates, chunkDao.selectRetrievalCandidates(
                    req.getDatasetIds(),
                    req.getDocumentIds(),
                    Collections.emptyList(),
                    fulltextQuery,
                    candidateLimit));
        }
        if ((StringUtils.isBlank(fulltextQuery) || candidates.size() < candidateLimit)
                && candidateTerms != null && !candidateTerms.isEmpty()) {
            addCandidates(candidates, chunkDao.selectRetrievalCandidates(
                    req.getDatasetIds(),
                    req.getDocumentIds(),
                    candidateTerms,
                    null,
                    candidateLimit - candidates.size()));
        }
        if (candidates.isEmpty() && (candidateTerms == null || candidateTerms.isEmpty())) {
            addCandidates(candidates, chunkDao.selectRetrievalCandidates(
                    req.getDatasetIds(),
                    req.getDocumentIds(),
                    Collections.emptyList(),
                    null,
                    candidateLimit));
        }
        return new ArrayList<>(candidates.values());
    }

    private void addCandidates(Map<String, KnowledgeChunkEntity> target, List<KnowledgeChunkEntity> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (KnowledgeChunkEntity chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            target.putIfAbsent(StringUtils.defaultIfBlank(chunk.getChunkId(), chunk.getId()), chunk);
        }
    }

    private void applyManagedLlmConfig() {
        if (StringUtils.isBlank(llmModelId)) {
            return;
        }
        ModelConfigEntity model = modelConfigService.getModelByIdFromCache(llmModelId);
        if (model == null || model.getConfigJson() == null) {
            log.warn("本地知识库切片模型配置不存在，modelId={}，将回退规则切片", llmModelId);
            llmApiKey = "";
            return;
        }
        if (!"LLM".equalsIgnoreCase(model.getModelType())) {
            log.warn("本地知识库切片模型不是 LLM 类型，modelId={}，modelType={}", llmModelId, model.getModelType());
            llmApiKey = "";
            return;
        }
        if (!Integer.valueOf(1).equals(model.getIsEnabled())
                || !Integer.valueOf(1).equals(model.getTestStatus())) {
            log.warn("本地知识库切片模型未启用或未测试通过，modelId={}，将回退规则切片", llmModelId);
            llmApiKey = "";
            return;
        }
        JSONObject modelConfig = model.getConfigJson();
        llmBaseUrl = firstConfigString(modelConfig, llmBaseUrl, "base_url", "url", "api_url", "endpoint_url");
        llmApiKey = firstConfigString(modelConfig, llmApiKey, "api_key", "api_password", "access_token", "token");
        llmModel = firstConfigString(modelConfig, llmModel, "model_name", "model", "llm_model", "resource_id");
    }

    private String firstConfigString(JSONObject config, String defaultValue, String... keys) {
        if (config == null) {
            return defaultValue;
        }
        for (String key : keys) {
            String value = config.getStr(key);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return defaultValue;
    }

    @Override
    public boolean testConnection() {
        return true;
    }

    @Override
    public Map<String, Object> getStatus() {
        return Map.of("adapterType", ADAPTER_TYPE, "connectionTest", true);
    }

    @Override
    public Map<String, Object> getSupportedConfig() {
        Map<String, String> configs = new LinkedHashMap<>();
        configs.put("max_chunk_chars", "单个切片的最大字符数");
        configs.put("llm_enabled", "是否启用大模型切片精修");
        configs.put("llm_base_url", "OpenAI兼容接口地址");
        configs.put("llm_api_key", "OpenAI兼容接口密钥");
        configs.put("llm_model", "切片精修模型");
        configs.put("llm_timeout_seconds", "单次模型请求超时时间");
        configs.put("llm_max_input_chars", "单次送入模型的最大字符数");
        configs.put("llm_max_tokens", "模型输出最大Token数");
        configs.put("embedding_model", "嵌入向量模型名称（用于向量检索）");
        configs.put("embedding_base_url", "嵌入向量API地址（默认复用llm_base_url）");
        configs.put("embedding_api_key", "嵌入向量API密钥（默认复用llm_api_key）");
        return configs.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("max_chunk_chars", DEFAULT_MAX_CHUNK_CHARS);
        defaults.put("llm_enabled", false);
        defaults.put("llm_base_url", DEFAULT_LLM_BASE_URL);
        defaults.put("llm_api_key", "");
        defaults.put("llm_model", DEFAULT_LLM_MODEL);
        defaults.put("llm_timeout_seconds", DEFAULT_LLM_TIMEOUT_SECONDS);
        defaults.put("llm_max_input_chars", DEFAULT_LLM_MAX_INPUT_CHARS);
        defaults.put("llm_max_tokens", DEFAULT_LLM_MAX_TOKENS);
        defaults.put("embedding_model", "");
        defaults.put("embedding_base_url", DEFAULT_LLM_BASE_URL);
        defaults.put("embedding_api_key", "");
        return defaults;
    }

    @Override
    public DatasetDTO.InfoVO createDataset(DatasetDTO.CreateReq req) {
        DatasetDTO.InfoVO vo = new DatasetDTO.InfoVO();
        vo.setId(newId());
        vo.setName(req.getName());
        vo.setDescription(req.getDescription());
        vo.setPermission(StringUtils.defaultIfBlank(req.getPermission(), "me"));
        vo.setChunkMethod(StringUtils.defaultIfBlank(req.getChunkMethod(), "naive"));
        vo.setChunkCount(0L);
        vo.setDocumentCount(0L);
        vo.setTokenNum(0L);
        return vo;
    }

    @Override
    public DatasetDTO.InfoVO updateDataset(String datasetId, DatasetDTO.UpdateReq req) {
        return getDatasetInfo(datasetId);
    }

    @Override
    public DatasetDTO.BatchOperationVO deleteDataset(DatasetDTO.BatchIdReq req) {
        if (req != null && req.getIds() != null && !req.getIds().isEmpty()) {
            chunkDao.delete(new QueryWrapper<KnowledgeChunkEntity>().in("dataset_id", req.getIds()));
        }
        return DatasetDTO.BatchOperationVO.builder()
                .successCount(req == null || req.getIds() == null ? 0 : req.getIds().size())
                .errors(List.of())
                .build();
    }

    @Override
    public Integer getDocumentCount(String datasetId) {
        return Math.toIntExact(documentDao.selectCount(new QueryWrapper<DocumentEntity>().eq("dataset_id", datasetId)));
    }

    @Override
    public DatasetDTO.InfoVO getDatasetInfo(String datasetId) {
        KnowledgeBaseEntity entity = knowledgeBaseDao.selectOne(new QueryWrapper<KnowledgeBaseEntity>()
                .eq("dataset_id", datasetId));
        if (entity == null) {
            return null;
        }
        DatasetDTO.InfoVO vo = new DatasetDTO.InfoVO();
        vo.setId(entity.getDatasetId());
        vo.setName(entity.getName());
        vo.setDescription(entity.getDescription());
        vo.setPermission(StringUtils.defaultIfBlank(entity.getPermission(), "me"));
        vo.setChunkMethod(StringUtils.defaultIfBlank(entity.getChunkMethod(), "naive"));
        vo.setChunkCount(entity.getChunkCount());
        vo.setDocumentCount(entity.getDocumentCount());
        vo.setTokenNum(entity.getTokenNum());
        return vo;
    }

    @Override
    public void postStream(String endpoint, Object body, Consumer<String> onData) {
        throw new RenException(ErrorCode.RAG_ADAPTER_OPERATION_FAILED, "本地知识库不支持流式接口");
    }

    @Override
    public Object postSearchBotAsk(Map<String, Object> config, Object body, Consumer<String> onData) {
        throw new RenException(ErrorCode.RAG_ADAPTER_OPERATION_FAILED, "本地知识库不支持 SearchBot");
    }

    @Override
    public void postAgentBotCompletion(Map<String, Object> config, String agentId, Object body,
            Consumer<String> onData) {
        throw new RenException(ErrorCode.RAG_ADAPTER_OPERATION_FAILED, "本地知识库不支持 AgentBot");
    }

    private KnowledgeFilesDTO toFileDTO(DocumentEntity entity) {
        KnowledgeFilesDTO dto = new KnowledgeFilesDTO();
        dto.setId(entity.getDocumentId());
        dto.setDocumentId(entity.getDocumentId());
        dto.setDatasetId(entity.getDatasetId());
        dto.setName(entity.getName());
        dto.setFileSize(entity.getSize());
        dto.setFileType(entity.getType());
        dto.setStatus(entity.getStatus());
        dto.setRun(entity.getRun());
        dto.setProgress(entity.getProgress());
        dto.setChunkCount(entity.getChunkCount());
        dto.setTokenCount(entity.getTokenCount());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private ChunkDTO.InfoVO toChunkVO(KnowledgeChunkEntity entity) {
        return ChunkDTO.InfoVO.builder()
                .id(entity.getChunkId())
                .content(entity.getContent())
                .documentId(entity.getDocumentId())
                .datasetId(entity.getDatasetId())
                .available(true)
                .questions(StringUtils.isBlank(entity.getQuestion()) ? null : List.of(entity.getQuestion()))
                .build();
    }

    private void replaceChunks(String datasetId, String documentId, List<LocalChunk> chunks) {
        // [Issue #10] 验证删除是否成功，避免旧 chunk_id 与新 chunk_id 冲突
        int deleted = chunkDao.delete(new QueryWrapper<KnowledgeChunkEntity>()
                .eq("dataset_id", datasetId)
                .eq("document_id", documentId));
        boolean useTimestampSuffix = (deleted == 0 && !chunks.isEmpty());
        long timestamp = useTimestampSuffix ? System.currentTimeMillis() : 0;
        KnowledgeBaseEntity dataset = knowledgeBaseDao.selectOne(
                new QueryWrapper<KnowledgeBaseEntity>().eq("dataset_id", datasetId).last("LIMIT 1"));
        String workspaceId = dataset == null ? null : dataset.getWorkspaceId();
        int index = 0;
        for (LocalChunk chunk : chunks) {
            KnowledgeChunkEntity entity = new KnowledgeChunkEntity();
            entity.setDatasetId(datasetId);
            entity.setWorkspaceId(workspaceId);
            entity.setDocumentId(documentId);
            entity.setChunkId(documentId + "_" + index + (useTimestampSuffix ? "_" + timestamp : ""));
            entity.setContent(chunk.getContent());
            entity.setQuestion(chunk.getQuestion());
            entity.setAnswer(chunk.getAnswer());
            entity.setSortOrder(index);
            entity.setTokenCount(estimateTokens(chunk.getContent()));
            entity.setCreatedAt(new Date());
            entity.setUpdatedAt(new Date());
            chunkDao.insert(entity);
            index++;
        }
        // [Issue #1] 为新切片生成嵌入向量
        generateAndStoreEmbeddings(datasetId, documentId);
    }

    private List<LocalChunk> parseFile(String filename, InputStream inputStream) throws Exception {
        String extension = getExtension(filename);
        List<LocalChunk> chunks;
        if ("xlsx".equals(extension) || "xls".equals(extension)) {
            chunks = parseExcel(inputStream);
            return refineChunksWithLlm(chunks);
        }
        String text = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));
        if ("csv".equals(extension)) {
            chunks = parseCsv(text);
            return refineChunksWithLlm(chunks);
        }
        if ("json".equals(extension) || "jsonl".equals(extension)) {
            chunks = parseJsonQa(text, "jsonl".equals(extension));
            return refineChunksWithLlm(chunks);
        }
        List<LocalChunk> markdownChunks = parsePreChunkedMarkdown(text);
        if (!markdownChunks.isEmpty()) {
            return markdownChunks;
        }
        List<LocalChunk> llmChunks = chunkDocumentWithLlm(filename, text);
        if (!llmChunks.isEmpty()) {
            return llmChunks;
        }
        List<LocalChunk> qaChunks = parsePlainQa(text);
        if (!qaChunks.isEmpty()) {
            chunks = qaChunks;
        } else {
            chunks = splitText(text);
        }
        return refineChunksWithLlm(chunks);
    }

    private List<LocalChunk> parseExcel(InputStream inputStream) throws Exception {
        List<LocalChunk> chunks = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            for (Sheet sheet : workbook) {
                List<List<String>> rows = new ArrayList<>();
                for (Row row : sheet) {
                    List<String> values = new ArrayList<>();
                    for (int i = 0; i < row.getLastCellNum(); i++) {
                        Cell cell = row.getCell(i);
                        values.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                    }
                    if (values.stream().anyMatch(StringUtils::isNotBlank)) {
                        rows.add(values);
                    }
                }
                chunks.addAll(rowsToChunks(rows));
            }
        }
        return chunks;
    }

    private List<LocalChunk> parseCsv(String text) {
        List<List<String>> rows = text.lines()
                .filter(StringUtils::isNotBlank)
                .map(this::parseCsvLine)
                .collect(Collectors.toList());
        return rowsToChunks(rows);
    }

    private List<LocalChunk> rowsToChunks(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> header = rows.get(0);
        int questionIndex = findHeader(header, "question", "q", "问题", "问");
        int answerIndex = findHeader(header, "answer", "a", "答案", "答", "回复");
        List<LocalChunk> chunks = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (questionIndex >= 0 && answerIndex >= 0) {
                String question = getCell(row, questionIndex);
                String answer = getCell(row, answerIndex);
                if (StringUtils.isAnyBlank(question, answer)) {
                    continue;
                }
                chunks.add(new LocalChunk(question, answer, "问题：" + question + "\n答案：" + answer));
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (int col = 0; col < row.size(); col++) {
                String key = col < header.size() && StringUtils.isNotBlank(header.get(col)) ? header.get(col) : "列" + (col + 1);
                String value = row.get(col);
                if (StringUtils.isNotBlank(value)) {
                    fields.put(key, value);
                }
            }
            if (!fields.isEmpty()) {
                chunks.add(new LocalChunk(null, null, fields.entrySet().stream()
                        .map(entry -> entry.getKey() + "：" + entry.getValue())
                        .collect(Collectors.joining("\n"))));
            }
        }
        return chunks;
    }

    private List<LocalChunk> parsePreChunkedMarkdown(String text) {
        String normalized = StringUtils.defaultString(text).trim();
        if (StringUtils.isBlank(normalized) || !normalized.contains("HY-KB-")) {
            return List.of();
        }

        List<LocalChunk> chunks = new ArrayList<>();
        for (String rawSection : normalized.split("(?m)^---\\s*$")) {
            String section = rawSection.trim();
            if (StringUtils.isBlank(section) || !section.matches("(?s).*^##\\s+HY-KB-\\d+.*")) {
                continue;
            }
            chunks.add(new LocalChunk(
                    extractMarkdownQuestions(section),
                    extractMarkdownAnswer(section),
                    section));
        }
        return chunks;
    }

    private String extractMarkdownQuestions(String section) {
        int start = section.indexOf("**适用问题**");
        if (start < 0) {
            return null;
        }
        String questionBlock = section.substring(start);
        List<String> questions = new ArrayList<>();
        for (String line : questionBlock.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ")) {
                questions.add(trimmed.substring(2).trim());
            }
        }
        return questions.isEmpty() ? null : String.join("；", questions);
    }

    private String extractMarkdownAnswer(String section) {
        int bodyStart = section.indexOf("**正文**");
        if (bodyStart < 0) {
            return null;
        }
        String body = section.substring(bodyStart)
                .replaceFirst("^\\*\\*正文\\*\\*[:：]?\\s*", "")
                .replaceFirst("(?s)\\R\\s*\\*\\*适用问题\\*\\*[:：]?.*$", "")
                .trim();
        return StringUtils.isBlank(body) ? null : body;
    }

    private List<LocalChunk> refineChunksWithLlm(List<LocalChunk> chunks) {
        if (!llmEnabled || StringUtils.isAnyBlank(llmBaseUrl, llmApiKey, llmModel)
                || isPlaceholderSecret(llmApiKey) || chunks.isEmpty()) {
            return chunks;
        }
        List<LocalChunk> refined = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            LocalChunk original = chunks.get(i);
            try {
                refined.add(refineChunkWithLlm(original));
            } catch (Exception e) {
                log.warn("本地知识库第 {} 个切片大模型精修失败，已回退规则切片: {}", i + 1, e.getMessage());
                llmChunkingFailed = true;
                refined.add(original);
            }
        }
        return refined;
    }

    /**
     * [Issue #5] 对每个分段进行 LLM 切片。若首次失败则重试一次，重试失败后该分段
     * 回退到规则切片，而非放弃整个文档。
     */
    private List<LocalChunk> chunkDocumentWithLlm(String filename, String text) {
        if (!llmAvailable() || StringUtils.isBlank(text)) {
            return List.of();
        }
        List<LocalChunk> chunks = new ArrayList<>();
        List<String> sections = splitLlmInputSections(text);
        for (int i = 0; i < sections.size(); i++) {
            String section = sections.get(i);
            boolean success = false;
            for (int attempt = 0; attempt <= 1; attempt++) {
                try {
                    chunks.addAll(chunkSectionWithLlm(filename, section, i + 1, sections.size()));
                    success = true;
                    break;
                } catch (Exception e) {
                    if (attempt == 0) {
                        log.warn("本地知识库大模型切片分段{}/{}首次失败，即将重试: {}",
                                i + 1, sections.size(), e.getMessage());
                    } else {
                        log.warn("本地知识库大模型切片分段{}/{}重试后仍失败，回退规则切片: {}",
                                i + 1, sections.size(), e.getMessage());
                    }
                }
            }
            if (!success) {
                // 记录 LLM 切片失败标记，用于 uploadDocument() 中标记质量降级
                llmChunkingFailed = true;
                // 回退到规则切片
                chunks.addAll(splitByParagraphs(section));
            }
        }
        return chunks.stream()
                .filter(chunk -> StringUtils.isNotBlank(chunk.getContent()))
                .collect(Collectors.toList());
    }

    private boolean llmAvailable() {
        return llmEnabled && StringUtils.isNoneBlank(llmBaseUrl, llmApiKey, llmModel)
                && !isPlaceholderSecret(llmApiKey);
    }

    private List<String> splitLlmInputSections(String text) {
        String normalized = StringUtils.defaultString(text).trim();
        if (StringUtils.isBlank(normalized)) {
            return List.of();
        }
        List<String> sections = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int hardEnd = Math.min(start + llmMaxInputChars, normalized.length());
            int end = findSplitEnd(normalized, start, hardEnd);
            String section = normalized.substring(start, end).trim();
            if (StringUtils.isNotBlank(section)) {
                sections.add(section);
            }
            start = end;
        }
        return sections;
    }

    private List<LocalChunk> chunkSectionWithLlm(String filename, String section, int sectionIndex, int totalSections)
            throws Exception {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", llmModel);
        requestBody.put("temperature", 0.1);
        requestBody.put("max_tokens", llmMaxTokens);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", getChunkingSystemPrompt()),
                Map.of("role", "user", "content",
                        buildChunkingUserPrompt(filename, section, sectionIndex, totalSections))));

        String content = requestLlmMessageContent(requestBody);
        List<LocalChunk> chunks = parseChunkedDocument(content);
        if (chunks.isEmpty()) {
            throw new RenException("模型未返回有效切片");
        }
        return chunks;
    }

    private String getChunkingSystemPrompt() {
        return "你是RAG知识库切片专家。你的任务是把用户提供的文档切成高质量、可检索的知识片段。"
                + "必须保留原文事实、数值、名称、范围和限制，不得编造、改写事实或添加外部知识。"
                + "切片必须按语义完整边界切分：优先保持一个编号条目、一个主题小节、一个问答组或一个业务事实完整；"
                + "不要把一句话、一个标题与正文、问题列表与对应答案切到不同片段。"
                + "每个切片建议300到1200个中文字符；内容很短时可以更短，内容过长时按小标题或自然段继续拆。"
                + "如果原文包含适用问题、关键词、正文等结构，应把同一条知识的这些字段放在同一个切片内。"
                + "只输出JSON数组，不要Markdown，不要解释。数组元素字段固定为question、answer、content。"
                + "question用于填写该片段最适合回答的用户问题，可为空字符串；answer用于明确问答答案，可为空字符串；"
                + "content必须是完整可检索知识片段。";
    }

    private String buildChunkingUserPrompt(String filename, String section, int sectionIndex, int totalSections)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("filename", filename);
        payload.put("section_index", sectionIndex);
        payload.put("total_sections", totalSections);
        payload.put("max_chunk_chars", maxChunkChars);
        payload.put("document", section);
        return "请对下面文档分段进行知识库切片，并返回JSON数组：\n"
                + objectMapper.writeValueAsString(payload);
    }

    private LocalChunk refineChunkWithLlm(LocalChunk chunk) throws Exception {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", llmModel);
        requestBody.put("temperature", 0.1);
        requestBody.put("max_tokens", llmMaxTokens);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", getLlmSystemPrompt()),
                Map.of("role", "user", "content", buildLlmUserPrompt(chunk))));

        String content = requestLlmMessageContent(requestBody);
        LocalChunk refined = parseRefinedChunk(content, chunk);
        return StringUtils.isBlank(refined.getContent()) ? chunk : refined;
    }

    private String requestLlmMessageContent(Map<String, Object> requestBody) throws Exception {
        // TODO: migrate to LlmClient
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(llmApiKey);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<String> response = llmRestTemplate.exchange(
                buildChatCompletionsUrl(llmBaseUrl), HttpMethod.POST, entity, String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RenException("模型接口返回状态码: " + response.getStatusCode());
        }
        String content = extractMessageContent(response.getBody());
        if (StringUtils.isBlank(content)) {
            throw new RenException("模型接口返回内容为空");
        }
        return content;
    }

    private String getLlmSystemPrompt() {
        return "你是知识库切片清洗助手。你的任务是把输入切片整理成适合RAG检索的高精度知识片段。"
                + "必须保留原始事实、数值、名称和约束，不得编造、扩写不存在的信息。"
                + "如果输入是问答，保留明确的问题和答案；如果输入是表格行，把字段组织成自然、完整、可检索的内容。"
                + "只输出JSON对象，不要Markdown，不要解释。JSON字段固定为question、answer、content。"
                + "没有明确问题或答案时，对应字段输出空字符串。";
    }

    private String buildLlmUserPrompt(LocalChunk chunk) throws Exception {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("question", StringUtils.defaultString(chunk.getQuestion()));
        payload.put("answer", StringUtils.defaultString(chunk.getAnswer()));
        payload.put("content", abbreviate(StringUtils.defaultString(chunk.getContent()), llmMaxInputChars));
        return "请清洗下面这个知识库候选切片，并按指定JSON格式返回：\n"
                + objectMapper.writeValueAsString(payload);
    }

    private String extractMessageContent(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            return choices.get(0).path("message").path("content").asText();
        }
        return null;
    }

    private LocalChunk parseRefinedChunk(String modelContent, LocalChunk fallback) throws Exception {
        String json = stripJson(modelContent);
        JsonNode node = objectMapper.readTree(json);
        String question = StringUtils.trimToEmpty(node.path("question").asText());
        String answer = StringUtils.trimToEmpty(node.path("answer").asText());
        String content = StringUtils.trimToEmpty(node.path("content").asText());
        if (StringUtils.isBlank(content) && StringUtils.isNoneBlank(question, answer)) {
            content = "问题：" + question + "\n答案：" + answer;
        }
        if (StringUtils.isBlank(content)) {
            content = fallback.getContent();
        }
        return new LocalChunk(
                StringUtils.isBlank(question) ? fallback.getQuestion() : question,
                StringUtils.isBlank(answer) ? fallback.getAnswer() : answer,
                content);
    }

    private List<LocalChunk> parseChunkedDocument(String modelContent) throws Exception {
        String json = stripJson(modelContent);
        JsonNode node = objectMapper.readTree(json);
        JsonNode chunksNode = node.isArray() ? node : node.path("chunks");
        if (!chunksNode.isArray()) {
            return List.of();
        }
        List<LocalChunk> chunks = new ArrayList<>();
        for (JsonNode item : chunksNode) {
            String question = StringUtils.trimToEmpty(item.path("question").asText());
            String answer = StringUtils.trimToEmpty(item.path("answer").asText());
            String content = StringUtils.trimToEmpty(item.path("content").asText());
            if (StringUtils.isBlank(content) && StringUtils.isNoneBlank(question, answer)) {
                content = "问题：" + question + "\n答案：" + answer;
            }
            if (StringUtils.isNotBlank(content)) {
                chunks.add(new LocalChunk(
                        StringUtils.isBlank(question) ? null : question,
                        StringUtils.isBlank(answer) ? null : answer,
                        content));
            }
        }
        return chunks;
    }

    private String stripJson(String text) {
        String trimmed = StringUtils.trimToEmpty(text);
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        int arrayStart = trimmed.indexOf('[');
        int arrayEnd = trimmed.lastIndexOf(']');
        int objectStart = trimmed.indexOf('{');
        int objectEnd = trimmed.lastIndexOf('}');
        if (arrayStart >= 0 && arrayEnd > arrayStart
                && (objectStart < 0 || arrayStart < objectStart)) {
            return trimmed.substring(arrayStart, arrayEnd + 1);
        }
        int start = objectStart;
        int end = objectEnd;
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String buildChatCompletionsUrl(String baseUrl) {
        String url = StringUtils.trimToEmpty(baseUrl);
        if (url.endsWith("/chat/completions")) {
            return url;
        }
        return StringUtils.removeEnd(url, "/") + "/chat/completions";
    }

    private String abbreviate(String text, int maxChars) {
        String value = StringUtils.defaultString(text);
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n...[内容已截断，保持原文事实清洗]";
    }

    private List<LocalChunk> parseJsonQa(String text, boolean jsonLines) throws Exception {
        List<LocalChunk> chunks = new ArrayList<>();
        if (jsonLines) {
            for (String line : text.split("\\R")) {
                if (StringUtils.isNotBlank(line)) {
                    addJsonNodeChunk(chunks, objectMapper.readTree(line));
                }
            }
        } else {
            JsonNode node = objectMapper.readTree(text);
            if (node.isArray()) {
                for (JsonNode item : node) {
                    addJsonNodeChunk(chunks, item);
                }
            } else {
                addJsonNodeChunk(chunks, node);
            }
        }
        return chunks;
    }

    private void addJsonNodeChunk(List<LocalChunk> chunks, JsonNode node) throws Exception {
        String question = firstText(node, "question", "q", "问题", "问");
        String answer = firstText(node, "answer", "a", "答案", "答", "response", "回复");
        if (StringUtils.isNoneBlank(question, answer)) {
            chunks.add(new LocalChunk(question, answer, "问题：" + question + "\n答案：" + answer));
        } else {
            Map<String, Object> map = objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {
            });
            chunks.add(new LocalChunk(null, null, objectMapper.writeValueAsString(map)));
        }
    }

    private List<LocalChunk> parsePlainQa(String text) {
        List<LocalChunk> chunks = new ArrayList<>();
        String[] blocks = text.split("(\\R\\s*\\R)+");
        for (String block : blocks) {
            String question = null;
            String answer = null;
            for (String line : block.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.matches("^(Q|q|问|问题)[:：].*")) {
                    question = trimmed.replaceFirst("^(Q|q|问|问题)[:：]\\s*", "");
                } else if (trimmed.matches("^(A|a|答|答案)[:：].*")) {
                    answer = trimmed.replaceFirst("^(A|a|答|答案)[:：]\\s*", "");
                }
            }
            if (StringUtils.isNoneBlank(question, answer)) {
                chunks.add(new LocalChunk(question, answer, "问题：" + question + "\n答案：" + answer));
            }
        }
        return chunks;
    }

    private List<LocalChunk> splitText(String text) {
        return splitByParagraphs(StringUtils.defaultString(text).trim());
    }

    private List<LocalChunk> splitByParagraphs(String text) {
        List<LocalChunk> chunks = new ArrayList<>();
        String normalized = StringUtils.defaultString(text).trim();
        if (StringUtils.isBlank(normalized)) {
            return chunks;
        }
        StringBuilder current = new StringBuilder();
        for (String paragraph : normalized.split("(\\R\\s*\\R)+")) {
            String part = paragraph.trim();
            if (StringUtils.isBlank(part)) {
                continue;
            }
            if (part.length() > maxChunkChars) {
                flushChunk(chunks, current);
                splitLongParagraph(chunks, part);
                continue;
            }
            int nextLength = current.length() == 0 ? part.length() : current.length() + 2 + part.length();
            if (nextLength > maxChunkChars) {
                flushChunk(chunks, current);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(part);
        }
        flushChunk(chunks, current);
        return chunks;
    }

    private void splitLongParagraph(List<LocalChunk> chunks, String text) {
        int start = 0;
        while (start < text.length()) {
            int end = findSplitEnd(text, start, Math.min(start + maxChunkChars, text.length()));
            String content = text.substring(start, end).trim();
            if (StringUtils.isNotBlank(content)) {
                chunks.add(new LocalChunk(null, null, content));
            }
            start = end;
        }
    }

    private int findSplitEnd(String text, int start, int hardEnd) {
        if (hardEnd >= text.length()) {
            return text.length();
        }
        int best = -1;
        for (String separator : List.of("\n", "。", "！", "？", "；", ";", ".", "!", "?")) {
            int index = text.lastIndexOf(separator, hardEnd - 1);
            if (index >= start + Math.min(80, maxChunkChars / 4)) {
                best = Math.max(best, index + separator.length());
            }
        }
        return best > start ? best : hardEnd;
    }

    private void flushChunk(List<LocalChunk> chunks, StringBuilder current) {
        if (StringUtils.isNotBlank(current.toString())) {
            chunks.add(new LocalChunk(null, null, current.toString().trim()));
            current.setLength(0);
        }
    }

    private int resolveResultLimit(RetrievalDTO.TestReq req, int pageSize) {
        int limit = pageSize;
        if (req.getTopK() != null && req.getTopK() > 0) {
            limit = Math.min(limit, clamp(req.getTopK(), 1, 100));
        } else if (hasRetrievalConfig) {
            limit = Math.min(limit, semanticTopK);
        }
        return Math.max(1, limit);
    }

    private int resolveCandidateLimit(RetrievalDTO.TestReq req, int resultLimit) {
        int requestedTopK = req.getTopK() == null || req.getTopK() < 1 ? semanticTopK : req.getTopK();
        int baseLimit = Math.max(DEFAULT_CANDIDATE_LIMIT, Math.max(resultLimit, requestedTopK) * 20);
        if (StringUtils.equalsIgnoreCase(searchType, "hybrid")) {
            baseLimit = Math.max(baseLimit, resultLimit * 30);
        }
        if (StringUtils.equalsIgnoreCase(retrievalMode, "exhibition")) {
            baseLimit = Math.max(baseLimit, resultLimit * 25);
        }
        if (rerankEnabled) {
            baseLimit = Math.max(baseLimit, resultLimit * 30);
        }
        return clamp(baseLimit, resultLimit, MAX_CANDIDATE_LIMIT);
    }

    private boolean shouldKeepScore(String question, float score) {
        if (StringUtils.isBlank(question)) {
            return true;
        }
        if (score >= faqThreshold) {
            return true;
        }
        return score >= semanticThreshold;
    }

    private List<String> selectCandidateTerms(Set<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return Collections.emptyList();
        }
        return terms.stream()
                .filter(StringUtils::isNotBlank)
                .filter(term -> term.length() >= 2 || containsNonCjk(term))
                .sorted(Comparator
                        .comparingInt(String::length).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .limit(MAX_RETRIEVAL_TERMS)
                .collect(Collectors.toList());
    }

    private String buildFulltextQuery(List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return null;
        }
        return terms.stream()
                .filter(term -> term.length() >= 2)
                .limit(12)
                .map(term -> "+" + term.replaceAll("[+\\-<>()~*\"@]+", ""))
                .filter(term -> term.length() > 1)
                .collect(Collectors.joining(" "));
    }

    private Float score(String question, KnowledgeChunkEntity chunk, Set<String> terms) {
        String content = String.join("\n",
                StringUtils.defaultString(chunk.getContent()),
                StringUtils.defaultString(chunk.getQuestion()),
                StringUtils.defaultString(chunk.getAnswer())).toLowerCase();
        String q = StringUtils.defaultString(question).toLowerCase();
        if (StringUtils.isBlank(q)) {
            return 1F;
        }
        if (content.contains(q)) {
            return 1F;
        }
        if (terms.isEmpty()) {
            return 0F;
        }
        float totalWeight = 0F;
        float hitWeight = 0F;
        int hitCount = 0;
        boolean hasStrongHit = false;
        for (String term : terms) {
            float weight = termWeight(term);
            totalWeight += weight;
            if (content.contains(term)) {
                hitWeight += weight;
                hitCount++;
                if (term.length() >= 3 || containsNonCjk(term)) {
                    hasStrongHit = true;
                }
            }
        }
        if (hitCount == 0 || totalWeight <= 0F) {
            return 0F;
        }
        float score = hitWeight / totalWeight;
        if (hasStrongHit) {
            score = Math.max(score, 0.35F);
        } else if (hitCount < 2 || score < 0.18F) {
            return 0F;
        }
        return Math.min(score, 0.99F);
    }

    private Set<String> tokenize(String text) {
        Set<String> terms = new LinkedHashSet<>();
        for (String rawTerm : text.split("[\\s,，。！？；;:：、]+")) {
            String term = StringUtils.trimToEmpty(rawTerm);
            if (StringUtils.isBlank(term)) {
                continue;
            }
            addTermWithLimit(terms, term);
            if (containsCjk(term) && term.length() > 2) {
                addCjkNgrams(terms, term, 4);
                if (semanticLevel >= 2) {
                    addCjkNgrams(terms, term, 3);
                }
                if (semanticLevel >= 4) {
                    addCjkNgrams(terms, term, 2);
                }
            }
            if (terms.size() >= MAX_RETRIEVAL_TERMS) {
                break;
            }
        }
        return terms;
    }

    private void addCjkNgrams(Set<String> terms, String text, int size) {
        if (text.length() < size) {
            return;
        }
        for (int i = 0; i <= text.length() - size; i++) {
            String gram = text.substring(i, i + size);
            if (containsCjk(gram) && !isWeakCjkTerm(gram)) {
                addTermWithLimit(terms, gram);
                if (terms.size() >= MAX_RETRIEVAL_TERMS) {
                    return;
                }
            }
        }
    }

    private void addTermWithLimit(Set<String> terms, String term) {
        if (terms.size() < MAX_RETRIEVAL_TERMS) {
            terms.add(term);
        }
    }

    private float termWeight(String term) {
        int length = term.length();
        if (length >= 4) {
            return 4F;
        }
        if (length == 3) {
            return 2F;
        }
        return 1F;
    }

    private boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private boolean containsNonCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) != Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private boolean isWeakCjkTerm(String term) {
        return Set.of("什么", "适合", "推荐", "可以", "一个", "一下", "这个", "那个", "现在").contains(term);
    }

    private Map<String, DocumentEntity> loadDocs(List<KnowledgeChunkEntity> chunks) {
        List<String> docIds = chunks.stream()
                .map(KnowledgeChunkEntity::getDocumentId)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
        if (docIds.isEmpty()) {
            return Map.of();
        }
        return documentDao.selectList(new QueryWrapper<DocumentEntity>().in("document_id", docIds))
                .stream()
                .collect(Collectors.toMap(DocumentEntity::getDocumentId, item -> item, (a, b) -> a));
    }

    private List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString().trim());
        return cells;
    }

    private int findHeader(List<String> headers, String... names) {
        for (int i = 0; i < headers.size(); i++) {
            String header = StringUtils.defaultString(headers.get(i)).trim().toLowerCase();
            for (String name : names) {
                if (header.equals(name.toLowerCase())) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isValueNode() && StringUtils.isNotBlank(value.asText())) {
                return value.asText();
            }
        }
        return null;
    }

    private String getCell(List<String> row, int index) {
        return index >= 0 && index < row.size() ? row.get(index) : "";
    }

    private String getStringConfig(String key, String defaultValue) {
        Object value = config.get(key);
        return StringUtils.defaultIfBlank(value == null ? null : value.toString(), defaultValue);
    }

    private String getStringConfig(Map<String, Object> source, String key, String defaultValue) {
        if (source == null) {
            return defaultValue;
        }
        Object value = source.get(key);
        return StringUtils.defaultIfBlank(value == null ? null : value.toString(), defaultValue);
    }

    private boolean getBooleanConfig(String key, boolean defaultValue) {
        Object value = config.get(key);
        return parseBooleanConfig(value, defaultValue);
    }

    private boolean getBooleanConfig(Map<String, Object> source, String key, boolean defaultValue) {
        Object value = source == null ? null : source.get(key);
        return parseBooleanConfig(value, defaultValue);
    }

    private boolean parseBooleanConfig(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = value.toString().trim();
        if (StringUtils.equalsAnyIgnoreCase(text, "true", "1", "yes", "y", "on")) {
            return true;
        }
        if (StringUtils.equalsAnyIgnoreCase(text, "false", "0", "no", "n", "off")) {
            return false;
        }
        return defaultValue;
    }

    private int getIntConfig(String key, int defaultValue, int min, int max) {
        Object value = config.get(key);
        return parseIntConfig(key, value, defaultValue, min, max);
    }

    private int getIntConfig(Map<String, Object> source, String key, int defaultValue, int min, int max) {
        Object value = source == null ? null : source.get(key);
        return parseIntConfig(key, value, defaultValue, min, max);
    }

    private int parseIntConfig(String key, Object value, int defaultValue, int min, int max) {
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.toString());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException e) {
            log.warn("本地知识库 {} 配置无效，使用默认值 {}", key, defaultValue);
            return defaultValue;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float getFloatConfig(Map<String, Object> source, String key, float defaultValue, float min, float max) {
        Object value = source == null ? null : source.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            float parsed = Float.parseFloat(value.toString());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException e) {
            log.warn("本地知识库 {} 配置无效，使用默认值 {}", key, defaultValue);
            return defaultValue;
        }
    }

    private Map<String, Object> getMapConfig(String key) {
        Object value = config.get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new HashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        if (value instanceof String text && StringUtils.isNotBlank(text)) {
            try {
                return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                log.warn("解析本地知识库 {} 配置失败，忽略检索扩展配置: {}", key, e.getMessage());
            }
        }
        return Collections.emptyMap();
    }

    private boolean isPlaceholderSecret(String value) {
        String text = StringUtils.trimToEmpty(value);
        return StringUtils.equalsAnyIgnoreCase(text, "your_api_key", "your_key", "api_key", "none")
                || text.contains("你的");
    }

    private <T> List<T> paginate(List<T> items, int page, int pageSize) {
        int from = Math.min((page - 1) * pageSize, items.size());
        int to = Math.min(from + pageSize, items.size());
        return items.subList(from, to);
    }

    private String getExtension(String filename) {
        int dotIndex = StringUtils.defaultString(filename).lastIndexOf('.');
        return dotIndex >= 0 ? filename.substring(dotIndex + 1).toLowerCase() : "";
    }

    /**
     * 估算文本的 token 数量。
     * [Issue #4] 对 CJK 字符（中文、日文、韩文）使用 1.5 tokens/字符的比率，
     * 对英文及其他 ASCII 字符使用 0.75 tokens/字符的比率，比简单的 length/2 更精确。
     */
    private Long estimateTokens(String content) {
        String text = StringUtils.defaultString(content);
        if (text.isEmpty()) {
            return 1L;
        }
        long cjkChars = 0;
        long otherChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN
                    || Character.UnicodeScript.of(c) == Character.UnicodeScript.HIRAGANA
                    || Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA
                    || Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL) {
                cjkChars++;
            } else {
                otherChars++;
            }
        }
        long estimated = (long) Math.ceil(cjkChars * 1.5 + otherChars * 0.75);
        return Math.max(1L, estimated);
    }

    /**
     * [Issue #1] 为新插入的切片生成向量嵌入。
     * 查询新插入的切片列表，逐条生成 embedding 并写回 database。
     * 若 embedding 服务不可用，静默跳过。
     */
    private void generateAndStoreEmbeddings(String datasetId, String documentId) {
        if (!embeddingEnabled) {
            return;
        }
        List<KnowledgeChunkEntity> storedChunks = chunkDao.selectList(
                new QueryWrapper<KnowledgeChunkEntity>()
                        .eq("dataset_id", datasetId)
                        .eq("document_id", documentId)
                        .orderByAsc("sort_order"));
        for (KnowledgeChunkEntity chunk : storedChunks) {
            try {
                String text = String.join("\n",
                        StringUtils.defaultString(chunk.getContent()),
                        StringUtils.defaultString(chunk.getQuestion()),
                        StringUtils.defaultString(chunk.getAnswer()));
                float[] embedding = embeddingService.getEmbedding(
                        text, embeddingBaseUrl, embeddingApiKey, embeddingModel);
                if (embedding != null && embedding.length > 0) {
                    String json = objectMapper.writeValueAsString(embedding);
                    KnowledgeChunkEntity update = new KnowledgeChunkEntity();
                    update.setId(chunk.getId());
                    update.setEmbeddingJson(json);
                    chunkDao.updateById(update);
                }
            } catch (Exception e) {
                log.warn("生成chunk embedding失败, chunkId={}: {}", chunk.getChunkId(), e.getMessage());
            }
        }
    }

    /**
     * [Issue #1] 计算两个 float 数组的余弦相似度。
     */
    private float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0F;
        }
        double dotProduct = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA <= 0 || normB <= 0) {
            return 0F;
        }
        return (float) (dotProduct / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    /**
     * [Issue #1] 将 JSON 格式的嵌入向量反序列化为 float[]。
     */
    private float[] parseEmbeddingJson(String embeddingJson) {
        if (StringUtils.isBlank(embeddingJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(embeddingJson, float[].class);
        } catch (Exception e) {
            log.debug("解析embedding_json失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * [Issue #1] 计算 query 与 chunk 的向量相似度。
     */
    private float computeVectorSimilarity(float[] queryEmbedding, String chunkEmbeddingJson) {
        if (queryEmbedding == null || StringUtils.isBlank(chunkEmbeddingJson)) {
            return 0F;
        }
        float[] chunkEmbedding = parseEmbeddingJson(chunkEmbeddingJson);
        if (chunkEmbedding == null) {
            return 0F;
        }
        return cosineSimilarity(queryEmbedding, chunkEmbedding);
    }

    /**
     * [Issue #1] 融合关键词得分和向量相似度得到最终得分。
     */
    private float computeCombinedScore(float keywordScore, float vectorScore, boolean embeddingAvailable) {
        if (!embeddingAvailable || vectorScore <= 0F) {
            return keywordScore;
        }
        // 关键词匹配和向量检索各占 50% 权重
        return 0.5F * keywordScore + 0.5F * vectorScore;
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class LocalChunk {
        private String question;
        private String answer;
        private String content;
    }

    @Data
    @AllArgsConstructor
    private static class ScoredChunk {
        private KnowledgeChunkEntity chunk;
        private Float score; // Combined score (keyword + vector weighted)
        private Float keywordScore; // Keyword overlap score
        private Float vectorScore; // Embedding cosine similarity
    }
}
