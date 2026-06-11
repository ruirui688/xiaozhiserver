package xiaozhi.modules.knowledge.rag.impl;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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

@Slf4j
public class LocalKnowledgeAdapter extends KnowledgeBaseAdapter {
    private static final String ADAPTER_TYPE = "local_knowledge";
    private static final int DEFAULT_MAX_CHUNK_CHARS = 1200;

    private Map<String, Object> config = new HashMap<>();
    private ObjectMapper objectMapper;
    private KnowledgeChunkDao chunkDao;
    private DocumentDao documentDao;
    private KnowledgeBaseDao knowledgeBaseDao;
    private int maxChunkChars = DEFAULT_MAX_CHUNK_CHARS;

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
        Object configuredMax = this.config.get("max_chunk_chars");
        if (configuredMax != null) {
            try {
                maxChunkChars = Math.max(200, Integer.parseInt(configuredMax.toString()));
            } catch (NumberFormatException e) {
                log.warn("本地知识库 max_chunk_chars 配置无效，使用默认值 {}", DEFAULT_MAX_CHUNK_CHARS);
            }
        }
    }

    @Override
    public boolean validateConfig(Map<String, Object> config) {
        return true;
    }

    @Override
    public PageData<KnowledgeFilesDTO> getDocumentList(String datasetId, DocumentDTO.ListReq req) {
        QueryWrapper<DocumentEntity> query = new QueryWrapper<>();
        query.eq("dataset_id", datasetId);
        if (StringUtils.isNotBlank(req.getId())) {
            query.eq("document_id", req.getId());
        }
        query.orderByDesc("created_at");
        List<DocumentEntity> docs = documentDao.selectList(query);
        List<KnowledgeFilesDTO> result = docs.stream().map(this::toFileDTO).collect(Collectors.toList());
        return new PageData<>(result, result.size());
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
        List<KnowledgeChunkEntity> all = chunkDao.selectList(query);
        List<KnowledgeChunkEntity> pageItems = paginate(all, page, pageSize);
        List<ChunkDTO.InfoVO> chunks = pageItems.stream().map(this::toChunkVO).collect(Collectors.toList());
        return ChunkDTO.ListVO.builder()
                .chunks(chunks)
                .doc(getDocumentById(datasetId, documentId))
                .total((long) all.size())
                .build();
    }

    @Override
    public RetrievalDTO.ResultVO retrievalTest(RetrievalDTO.TestReq req) {
        QueryWrapper<KnowledgeChunkEntity> query = new QueryWrapper<>();
        query.in("dataset_id", req.getDatasetIds());
        if (req.getDocumentIds() != null && !req.getDocumentIds().isEmpty()) {
            query.in("document_id", req.getDocumentIds());
        }
        List<KnowledgeChunkEntity> chunks = chunkDao.selectList(query);
        String question = StringUtils.defaultString(req.getQuestion());
        int pageSize = req.getPageSize() == null || req.getPageSize() < 1 ? 10 : req.getPageSize();

        Map<String, DocumentEntity> docs = loadDocs(chunks);
        List<ScoredChunk> scored = chunks.stream()
                .map(chunk -> new ScoredChunk(chunk, score(question, chunk)))
                .filter(item -> item.getScore() > 0 || StringUtils.isBlank(question))
                .sorted(Comparator.comparing(ScoredChunk::getScore).reversed())
                .limit(pageSize)
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
                    .termSimilarity(item.getScore())
                    .vectorSimilarity(0F)
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
        return Map.of("max_chunk_chars", "单个切片的最大字符数");
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        return Map.of("max_chunk_chars", DEFAULT_MAX_CHUNK_CHARS);
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
        chunkDao.delete(new QueryWrapper<KnowledgeChunkEntity>()
                .eq("dataset_id", datasetId)
                .eq("document_id", documentId));
        int index = 0;
        for (LocalChunk chunk : chunks) {
            KnowledgeChunkEntity entity = new KnowledgeChunkEntity();
            entity.setDatasetId(datasetId);
            entity.setDocumentId(documentId);
            entity.setChunkId(documentId + "_" + index);
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
    }

    private List<LocalChunk> parseFile(String filename, InputStream inputStream) throws Exception {
        String extension = getExtension(filename);
        if ("xlsx".equals(extension) || "xls".equals(extension)) {
            return parseExcel(inputStream);
        }
        String text = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));
        if ("csv".equals(extension)) {
            return parseCsv(text);
        }
        if ("json".equals(extension) || "jsonl".equals(extension)) {
            return parseJsonQa(text, "jsonl".equals(extension));
        }
        List<LocalChunk> qaChunks = parsePlainQa(text);
        return qaChunks.isEmpty() ? splitText(text) : qaChunks;
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
        int start = questionIndex >= 0 && answerIndex >= 0 ? 1 : 0;
        for (int i = start; i < rows.size(); i++) {
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
        List<LocalChunk> chunks = new ArrayList<>();
        String normalized = StringUtils.defaultString(text).trim();
        for (int start = 0; start < normalized.length(); start += maxChunkChars) {
            int end = Math.min(start + maxChunkChars, normalized.length());
            String content = normalized.substring(start, end).trim();
            if (StringUtils.isNotBlank(content)) {
                chunks.add(new LocalChunk(null, null, content));
            }
        }
        return chunks;
    }

    private Float score(String question, KnowledgeChunkEntity chunk) {
        String content = StringUtils.defaultString(chunk.getContent()).toLowerCase();
        String q = StringUtils.defaultString(question).toLowerCase();
        if (StringUtils.isBlank(q)) {
            return 1F;
        }
        if (content.contains(q)) {
            return 1F;
        }
        Set<String> terms = tokenize(q);
        if (terms.isEmpty()) {
            return 0F;
        }
        long hit = terms.stream().filter(content::contains).count();
        return (float) hit / terms.size();
    }

    private Set<String> tokenize(String text) {
        Set<String> terms = new HashSet<>();
        for (String term : text.split("[\\s,，。！？；;:：、]+")) {
            if (StringUtils.isNotBlank(term)) {
                terms.add(term);
            }
        }
        return terms;
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

    private <T> List<T> paginate(List<T> items, int page, int pageSize) {
        int from = Math.min((page - 1) * pageSize, items.size());
        int to = Math.min(from + pageSize, items.size());
        return items.subList(from, to);
    }

    private String getExtension(String filename) {
        int dotIndex = StringUtils.defaultString(filename).lastIndexOf('.');
        return dotIndex >= 0 ? filename.substring(dotIndex + 1).toLowerCase() : "";
    }

    private Long estimateTokens(String content) {
        return Math.max(1L, StringUtils.defaultString(content).length() / 2L);
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
        private Float score;
    }
}
