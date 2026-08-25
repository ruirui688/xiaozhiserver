package xiaozhi.modules.knowledge.service.impl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.knowledge.dao.DocumentDao;
import xiaozhi.modules.knowledge.dto.KnowledgeFilesDTO;
import xiaozhi.modules.knowledge.dto.document.DocumentDTO;
import xiaozhi.modules.knowledge.entity.DocumentEntity;
import xiaozhi.modules.knowledge.rag.KnowledgeBaseAdapter;
import xiaozhi.modules.knowledge.rag.impl.local.LocalKnowledgeDocumentFormats;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.knowledge.service.KnowledgeFilesService;

@Service
@Slf4j
public class LocalKnowledgeUploadTaskService {
    private static final double QUEUED_PROGRESS = 0.02D;
    private static final Duration ACTIVE_STALE_AFTER = Duration.ofMinutes(10);
    private static final Duration QUEUED_STALE_AFTER = Duration.ofHours(1);

    private final AsyncTaskExecutor taskExecutor;
    private final KnowledgeFilesService knowledgeFilesService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentDao documentDao;
    private final ObjectMapper objectMapper;

    public LocalKnowledgeUploadTaskService(
            @Qualifier("knowledgeUploadTaskExecutor") AsyncTaskExecutor taskExecutor,
            @Lazy KnowledgeFilesService knowledgeFilesService,
            KnowledgeBaseService knowledgeBaseService,
            DocumentDao documentDao,
            ObjectMapper objectMapper) {
        this.taskExecutor = taskExecutor;
        this.knowledgeFilesService = knowledgeFilesService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentDao = documentDao;
        this.objectMapper = objectMapper;
    }

    public KnowledgeFilesDTO submit(String datasetId, MultipartFile sourceFile, String requestedName,
            Map<String, Object> metaFields, String chunkMethod, Map<String, Object> parserConfig,
            KnowledgeBaseAdapter adapter) {
        return submitInternal(datasetId, sourceFile, requestedName, metaFields, chunkMethod, parserConfig,
                adapter, null);
    }

    /**
     * 提交动态数据源生成的候选版本。候选文档在切片完成后仍保持 disabled，
     * 由回调在单个数据库事务中切换正式版本，避免新旧版本同时参与召回。
     */
    public KnowledgeFilesDTO submitSource(String datasetId, MultipartFile sourceFile, String requestedName,
            Map<String, Object> metaFields, String chunkMethod, Map<String, Object> parserConfig,
            KnowledgeBaseAdapter adapter, SourceUploadContext sourceContext) {
        if (sourceContext == null || StringUtils.isAnyBlank(
                sourceContext.sourceId(), sourceContext.documentId(), sourceContext.contentHash())) {
            throw new IllegalArgumentException("动态知识数据源上下文不完整");
        }
        return submitInternal(datasetId, sourceFile, requestedName, metaFields, chunkMethod, parserConfig,
                adapter, sourceContext);
    }

    private KnowledgeFilesDTO submitInternal(String datasetId, MultipartFile sourceFile, String requestedName,
            Map<String, Object> metaFields, String chunkMethod, Map<String, Object> parserConfig,
            KnowledgeBaseAdapter adapter, SourceUploadContext sourceContext) {
        String filename = safeFilename(StringUtils.defaultIfBlank(requestedName, sourceFile.getOriginalFilename()));
        validateFile(filename, sourceFile.getContentType());

        Path stagedFile = stageFile(sourceFile);
        String documentId = sourceContext == null ? newDocumentId() : sourceContext.documentId();
        KnowledgeFilesDTO queued = queuedDocument(
                datasetId, documentId, filename, sourceFile, metaFields, sourceContext);
        try {
            saveShadow(datasetId, queued, filename, chunkMethod, parserConfig, sourceContext);
        } catch (Exception e) {
            deleteStagedFile(stagedFile);
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RenException(ErrorCode.RAG_API_ERROR, "创建后台切片任务失败");
        }
        try {
            taskExecutor.submit(() -> process(
                    datasetId, documentId, filename, sourceFile.getContentType(), stagedFile,
                    metaFields, chunkMethod, parserConfig, adapter, sourceContext));
            return queued;
        } catch (Exception e) {
            deleteStagedFile(stagedFile);
            markFailed(datasetId, documentId, "后台切片任务繁忙，请稍后重新上传");
            throw new RenException(ErrorCode.RAG_API_ERROR, "后台切片任务繁忙，请稍后重新上传");
        }
    }

    private void process(String datasetId, String documentId, String filename, String contentType,
            Path stagedFile, Map<String, Object> metaFields, String chunkMethod,
            Map<String, Object> parserConfig, KnowledgeBaseAdapter adapter,
            SourceUploadContext sourceContext) {
        long startedAt = System.nanoTime();
        try {
            if (!isRunning(datasetId, documentId)) {
                return;
            }
            updateProgress(datasetId, documentId, 0.08D);
            AtomicReference<Double> lastProgress = new AtomicReference<>(0.08D);
            DocumentDTO.UploadReq uploadReq = DocumentDTO.UploadReq.builder()
                    .datasetId(datasetId)
                    .documentId(documentId)
                    .file(new StagedMultipartFile(filename, contentType, stagedFile))
                    .name(filename)
                    .metaFields(metaFields)
                    .progressListener(progress -> updateProgressIfNeeded(
                            datasetId, documentId, progress, lastProgress))
                    .build();
            if (StringUtils.isNotBlank(chunkMethod)) {
                try {
                    uploadReq.setChunkMethod(DocumentDTO.InfoVO.ChunkMethod.valueOf(chunkMethod.toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                    log.warn("忽略无效的知识库分块方法: {}", chunkMethod);
                }
            }
            if (parserConfig != null && !parserConfig.isEmpty()) {
                uploadReq.setParserConfig(objectMapper.convertValue(
                        parserConfig, DocumentDTO.InfoVO.ParserConfig.class));
            }

            KnowledgeFilesDTO result = adapter.uploadDocument(uploadReq);
            if (result == null || StringUtils.isBlank(result.getDocumentId())) {
                throw new RenException("大模型切片完成但未返回有效文档 ID");
            }
            if (!isRunning(datasetId, documentId)) {
                cleanupChunks(adapter, datasetId, documentId);
                return;
            }
            result.setProcessDuration((System.nanoTime() - startedAt) / 1_000_000_000D);
            applySourceContext(result, sourceContext);
            saveShadow(datasetId, result, filename, chunkMethod, parserConfig, sourceContext);
            long chunkDelta = result.getChunkCount() == null ? 0L : result.getChunkCount().longValue();
            long tokenDelta = result.getTokenCount() == null ? 0L : result.getTokenCount();
            if (sourceContext != null) {
                sourceContext.listener().onIndexed(result);
            } else if (chunkDelta > 0 || tokenDelta > 0) {
                knowledgeBaseService.updateStatistics(datasetId, 0, chunkDelta, tokenDelta);
            }
            log.info("本地知识库后台切片完成: datasetId={}, documentId={}, chunks={}",
                    datasetId, documentId, chunkDelta);
        } catch (Exception e) {
            cleanupChunks(adapter, datasetId, documentId);
            String failureMessage = safeFailureMessage(e);
            markFailed(datasetId, documentId, failureMessage);
            if (sourceContext != null) {
                sourceContext.listener().onFailed(failureMessage);
            }
            log.error("本地知识库后台切片失败: datasetId={}, documentId={}, error={}",
                    datasetId, documentId, failureMessage);
        } finally {
            deleteStagedFile(stagedFile);
        }
    }

    private void updateProgressIfNeeded(String datasetId, String documentId, double progress,
            AtomicReference<Double> lastProgress) {
        double normalized = Math.max(0.08D, Math.min(0.90D, progress));
        double previous = lastProgress.get();
        if (normalized - previous < 0.02D && normalized < 0.90D) {
            return;
        }
        if (lastProgress.compareAndSet(previous, normalized)) {
            updateProgress(datasetId, documentId, normalized);
        }
    }

    private void updateProgress(String datasetId, String documentId, double progress) {
        documentDao.update(null, new UpdateWrapper<DocumentEntity>()
                .set("progress", progress)
                .set("updated_at", new Date())
                .eq("dataset_id", datasetId)
                .eq("document_id", documentId)
                .eq("run", "RUNNING"));
    }

    private boolean isRunning(String datasetId, String documentId) {
        Long count = documentDao.selectCount(new QueryWrapper<DocumentEntity>()
                .eq("dataset_id", datasetId)
                .eq("document_id", documentId)
                .eq("run", "RUNNING"));
        return count != null && count > 0;
    }

    private void markFailed(String datasetId, String documentId, String message) {
        documentDao.update(null, new UpdateWrapper<DocumentEntity>()
                .set("run", "FAIL")
                .set("progress", 0D)
                .set("error", message)
                .set("updated_at", new Date())
                .eq("dataset_id", datasetId)
                .eq("document_id", documentId)
                .eq("run", "RUNNING"));
    }

    @Scheduled(fixedDelay = 60000)
    public void expireStaleTasks() {
        Date activeCutoff = new Date(System.currentTimeMillis() - ACTIVE_STALE_AFTER.toMillis());
        Date queuedCutoff = new Date(System.currentTimeMillis() - QUEUED_STALE_AFTER.toMillis());
        List<DocumentEntity> stale = documentDao.selectList(new QueryWrapper<DocumentEntity>()
                .eq("source_type", "local_knowledge")
                .eq("run", "RUNNING")
                .and(wrapper -> wrapper
                        .nested(active -> active.gt("progress", 0.05D).lt("updated_at", activeCutoff))
                        .or(queued -> queued.le("progress", 0.05D).lt("updated_at", queuedCutoff))));
        for (DocumentEntity document : stale) {
            markFailed(document.getDatasetId(), document.getDocumentId(),
                    "大模型切片任务长时间没有进展，请删除该记录后重新上传");
        }
    }

    private KnowledgeFilesDTO queuedDocument(String datasetId, String documentId, String filename,
            MultipartFile sourceFile, Map<String, Object> metaFields, SourceUploadContext sourceContext) {
        Date now = new Date();
        KnowledgeFilesDTO queued = new KnowledgeFilesDTO();
        queued.setId(documentId);
        queued.setDocumentId(documentId);
        queued.setDatasetId(datasetId);
        queued.setName(filename);
        queued.setFileSize(sourceFile.getSize());
        queued.setStatus("1");
        queued.setRun("RUNNING");
        queued.setProgress(QUEUED_PROGRESS);
        queued.setSourceType(sourceContext == null ? "local_knowledge" : sourceContext.sourceType());
        applySourceContext(queued, sourceContext);
        queued.setChunkCount(0);
        queued.setTokenCount(0L);
        queued.setMetaFields(metaFields);
        queued.setCreatedAt(now);
        queued.setUpdatedAt(now);
        return queued;
    }

    private void applySourceContext(KnowledgeFilesDTO document, SourceUploadContext sourceContext) {
        if (sourceContext == null) {
            return;
        }
        document.setDocumentId(sourceContext.documentId());
        document.setId(sourceContext.documentId());
        document.setSourceType(sourceContext.sourceType());
        document.setKnowledgeSourceId(sourceContext.sourceId());
        document.setSourceRevision(sourceContext.revision());
        document.setContentHash(sourceContext.contentHash());
        document.setPublishedAt(null);
    }

    private void saveShadow(String datasetId, KnowledgeFilesDTO document, String filename,
            String chunkMethod, Map<String, Object> parserConfig, SourceUploadContext sourceContext) {
        if (sourceContext == null) {
            knowledgeFilesService.saveDocumentShadow(datasetId, document, filename, chunkMethod, parserConfig);
        } else {
            knowledgeFilesService.saveDocumentShadow(
                    datasetId, document, filename, chunkMethod, parserConfig, false, false);
        }
    }

    public record SourceUploadContext(
            String sourceId,
            String documentId,
            int revision,
            String contentHash,
            String sourceType,
            SourceUploadListener listener) {
        public SourceUploadContext {
            if (listener == null) {
                throw new IllegalArgumentException("动态知识数据源回调不能为空");
            }
        }
    }

    public interface SourceUploadListener {
        void onIndexed(KnowledgeFilesDTO document);

        void onFailed(String error);
    }

    private void validateFile(String filename, String contentType) {
        if (!LocalKnowledgeDocumentFormats.supports(filename)) {
            throw new RenException(ErrorCode.UPLOAD_FILE_ERROR,
                    "本地知识库不支持该文件格式，请上传 txt/md/mdx/csv/xls/xlsx/json/jsonl/qa 文件");
        }
        if (!LocalKnowledgeDocumentFormats.supportsContentType(filename, contentType)) {
            throw new RenException(ErrorCode.UPLOAD_FILE_ERROR, "文件扩展名与 MIME 类型不匹配");
        }
    }

    private Path stageFile(MultipartFile sourceFile) {
        Path stagedFile = null;
        try {
            stagedFile = Files.createTempFile("xiaozhi-knowledge-upload-", ".tmp");
            try (InputStream input = sourceFile.getInputStream()) {
                Files.copy(input, stagedFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return stagedFile;
        } catch (IOException e) {
            if (stagedFile != null) {
                deleteStagedFile(stagedFile);
            }
            throw new RenException(ErrorCode.UPLOAD_FILE_ERROR, "暂存上传文件失败");
        }
    }

    private String safeFilename(String filename) {
        String normalized = StringUtils.trimToEmpty(filename).replace('\\', '/');
        if (normalized.contains("/")) {
            normalized = StringUtils.substringAfterLast(normalized, "/");
        }
        normalized = normalized.replace("\r", "").replace("\n", "");
        if (StringUtils.isBlank(normalized)) {
            throw new RenException(ErrorCode.RAG_FILE_NAME_NOT_NULL);
        }
        return normalized;
    }

    private String safeFailureMessage(Exception exception) {
        Throwable cursor = exception;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = StringUtils.defaultIfBlank(cursor.getMessage(), "大模型语义切片失败");
        message = message.replaceAll(
                "(?i)(api[_-]?key|api[_-]?secret|secret[_-]?key|token|password|authorization)\\s*[:=]\\s*[^\\s,;，；]+",
                "$1=***");
        message = message.replaceAll("(?i)Bearer\\s+[^\\s,;，；]+", "Bearer ***");
        return StringUtils.abbreviate(message, 500);
    }

    private void cleanupChunks(KnowledgeBaseAdapter adapter, String datasetId, String documentId) {
        try {
            adapter.deleteDocument(datasetId, DocumentDTO.BatchIdReq.builder()
                    .ids(List.of(documentId))
                    .build());
        } catch (Exception cleanupError) {
            log.warn("清理失败的知识库切片未完成: datasetId={}, documentId={}", datasetId, documentId);
        }
    }

    private void deleteStagedFile(Path stagedFile) {
        try {
            Files.deleteIfExists(stagedFile);
        } catch (IOException e) {
            log.warn("删除知识库上传暂存文件失败: {}", stagedFile.getFileName());
        }
    }

    private String newDocumentId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static final class StagedMultipartFile implements MultipartFile {
        private final String filename;
        private final String contentType;
        private final Path path;

        private StagedMultipartFile(String filename, String contentType, Path path) {
            this.filename = filename;
            this.contentType = contentType;
            this.path = path;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return filename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return getSize() == 0L;
        }

        @Override
        public long getSize() {
            try {
                return Files.size(path);
            } catch (IOException e) {
                return 0L;
            }
        }

        @Override
        public byte[] getBytes() throws IOException {
            return Files.readAllBytes(path);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return Files.newInputStream(path);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            try (InputStream input = getInputStream(); OutputStream output = Files.newOutputStream(dest.toPath())) {
                input.transferTo(output);
            }
        }
    }
}
