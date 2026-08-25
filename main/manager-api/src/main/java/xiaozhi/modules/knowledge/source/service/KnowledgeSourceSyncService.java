package xiaozhi.modules.knowledge.source.service;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.knowledge.dao.DocumentDao;
import xiaozhi.modules.knowledge.dao.KnowledgeChunkDao;
import xiaozhi.modules.knowledge.dto.KnowledgeBaseDTO;
import xiaozhi.modules.knowledge.dto.KnowledgeFilesDTO;
import xiaozhi.modules.knowledge.entity.DocumentEntity;
import xiaozhi.modules.knowledge.entity.KnowledgeChunkEntity;
import xiaozhi.modules.knowledge.rag.KnowledgeBaseAdapter;
import xiaozhi.modules.knowledge.rag.KnowledgeBaseAdapterFactory;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.knowledge.service.impl.LocalKnowledgeUploadTaskService;
import xiaozhi.modules.knowledge.source.connector.WebPageConnector;
import xiaozhi.modules.knowledge.source.dao.KnowledgeSourceDao;
import xiaozhi.modules.knowledge.source.dao.KnowledgeSyncRunDao;
import xiaozhi.modules.knowledge.source.dto.KnowledgeSourceView;
import xiaozhi.modules.knowledge.source.entity.KnowledgeSourceEntity;
import xiaozhi.modules.knowledge.source.entity.KnowledgeSyncRunEntity;

/** Polling, indexing and atomic publication for optional knowledge connectors. */
@Service
@Slf4j
public class KnowledgeSourceSyncService {
    private static final long STUCK_AFTER_MS = 30L * 60L * 1000L;

    private final KnowledgeSourceService sourceService;
    private final KnowledgeSourceDao sourceDao;
    private final KnowledgeSyncRunDao runDao;
    private final KnowledgeBaseService knowledgeBaseService;
    private final LocalKnowledgeUploadTaskService uploadTaskService;
    private final DocumentDao documentDao;
    private final KnowledgeChunkDao chunkDao;
    private final WebPageConnector webPageConnector;
    private final ObjectMapper objectMapper;
    private final AsyncTaskExecutor sourceExecutor;
    private final TransactionTemplate transactionTemplate;

    public KnowledgeSourceSyncService(KnowledgeSourceService sourceService,
            KnowledgeSourceDao sourceDao, KnowledgeSyncRunDao runDao,
            KnowledgeBaseService knowledgeBaseService, LocalKnowledgeUploadTaskService uploadTaskService,
            DocumentDao documentDao, KnowledgeChunkDao chunkDao, WebPageConnector webPageConnector,
            ObjectMapper objectMapper,
            @Qualifier("knowledgeSourceTaskExecutor") AsyncTaskExecutor sourceExecutor,
            TransactionTemplate transactionTemplate) {
        this.sourceService = sourceService;
        this.sourceDao = sourceDao;
        this.runDao = runDao;
        this.knowledgeBaseService = knowledgeBaseService;
        this.uploadTaskService = uploadTaskService;
        this.documentDao = documentDao;
        this.chunkDao = chunkDao;
        this.webPageConnector = webPageConnector;
        this.objectMapper = objectMapper;
        this.sourceExecutor = sourceExecutor;
        this.transactionTemplate = transactionTemplate;
    }

    public KnowledgeSourceView trigger(String datasetId, String sourceId, String triggerType) {
        KnowledgeSourceEntity source = sourceService.requireSource(datasetId, sourceId);
        int acquired = sourceDao.update(null, new UpdateWrapper<KnowledgeSourceEntity>()
                .set("sync_status", "SYNCING").set("last_sync_at", new Date()).set("last_error", null)
                .eq("id", sourceId).eq("dataset_id", datasetId).ne("sync_status", "SYNCING"));
        if (acquired == 0) {
            throw new RenException("数据源已经在同步中");
        }
        KnowledgeSyncRunEntity run = newRun(source, triggerType);
        runDao.insert(run);
        try {
            sourceExecutor.submit(() -> synchronize(sourceId, run.getId()));
        } catch (Exception e) {
            fail(sourceId, run.getId(), "同步任务队列繁忙，请稍后重试", null);
            throw new RenException("同步任务队列繁忙，请稍后重试");
        }
        return sourceService.toView(sourceService.requireSource(datasetId, sourceId));
    }

    @Scheduled(fixedDelayString = "${knowledge.sources.scheduler-delay-ms:60000}")
    public void scheduleDueSources() {
        recoverStuckSources();
        Date now = new Date();
        List<KnowledgeSourceEntity> due = sourceDao.selectList(new QueryWrapper<KnowledgeSourceEntity>()
                .eq("enabled", 1).ne("sync_status", "SYNCING")
                .and(wrapper -> wrapper.isNull("next_sync_at").or().le("next_sync_at", now))
                .orderByAsc("next_sync_at").last("LIMIT 20"));
        for (KnowledgeSourceEntity source : due) {
            try {
                trigger(source.getDatasetId(), source.getId(), "SCHEDULED");
            } catch (Exception e) {
                log.warn("跳过未能启动的知识数据源同步: sourceId={}, error={}", source.getId(), e.getMessage());
            }
        }
    }

    private void synchronize(String sourceId, String runId) {
        KnowledgeSourceEntity source = sourceDao.selectById(sourceId);
        if (source == null) return;
        String candidateDocumentId = null;
        try {
            WebPageConnector.FetchResult fetched = webPageConnector.fetch(source);
            if (fetched.notModified() || StringUtils.equals(source.getContentHash(), fetched.contentHash())) {
                unchanged(source, runId, fetched);
                return;
            }
            KnowledgeBaseDTO dataset = sourceService.requireLocalDataset(source.getDatasetId());
            Map<String, Object> ragConfig = knowledgeBaseService.getRAGConfigByDatasetId(source.getDatasetId());
            KnowledgeBaseAdapter adapter = KnowledgeBaseAdapterFactory.getAdapter("local_knowledge", ragConfig);
            int revision = value(source.getCurrentRevision()) + 1;
            candidateDocumentId = UUID.randomUUID().toString().replace("-", "");
            String immutableDocumentId = candidateDocumentId;
            String documentName = safeDocumentName(source.getName(), revision);
            MultipartFile file = new ByteArrayMultipartFile(
                    documentName, "text/markdown", fetched.content().getBytes(StandardCharsets.UTF_8));
            Map<String, Object> metadata = Map.of(
                    "source_url", fetched.finalUri().toString(),
                    "source_name", source.getName(),
                    "source_revision", revision);
            updateRunIndexing(runId, revision, fetched.bytesFetched());
            uploadTaskService.submitSource(source.getDatasetId(), file, documentName, metadata,
                    dataset.getChunkMethod(), parserConfig(dataset.getParserConfig()), adapter,
                    new LocalKnowledgeUploadTaskService.SourceUploadContext(
                            sourceId, immutableDocumentId, revision, fetched.contentHash(), "connector_web",
                            new LocalKnowledgeUploadTaskService.SourceUploadListener() {
                                @Override
                                public void onIndexed(KnowledgeFilesDTO document) {
                                    publish(sourceId, runId, document, fetched);
                                }
                                @Override
                                public void onFailed(String error) {
                                    fail(sourceId, runId, error, immutableDocumentId);
                                }
                            }));
        } catch (Exception e) {
            fail(sourceId, runId, safeError(e), candidateDocumentId);
        }
    }

    private void publish(String sourceId, String runId, KnowledgeFilesDTO document,
            WebPageConnector.FetchResult fetched) {
        transactionTemplate.executeWithoutResult(status -> {
            KnowledgeSourceEntity source = sourceDao.selectById(sourceId);
            DocumentEntity next = documentDao.selectOne(new QueryWrapper<DocumentEntity>()
                    .eq("dataset_id", document.getDatasetId()).eq("document_id", document.getDocumentId()));
            if (source == null || next == null || !"SYNCING".equals(source.getSyncStatus())) {
                throw new RenException("动态知识候选版本发布状态不一致");
            }
            DocumentEntity previous = StringUtils.isBlank(source.getCurrentDocumentId()) ? null
                    : documentDao.selectOne(new QueryWrapper<DocumentEntity>()
                            .eq("dataset_id", source.getDatasetId())
                            .eq("document_id", source.getCurrentDocumentId()));
            Date now = new Date();
            if (previous != null) {
                documentDao.update(null, new UpdateWrapper<DocumentEntity>().set("enabled", 0)
                        .eq("dataset_id", source.getDatasetId()).eq("document_id", previous.getDocumentId()));
            }
            documentDao.update(null, new UpdateWrapper<DocumentEntity>()
                    .set("enabled", 1).set("published_at", now)
                    .eq("dataset_id", source.getDatasetId()).eq("document_id", next.getDocumentId()));
            sourceDao.update(null, new UpdateWrapper<KnowledgeSourceEntity>()
                    .set("sync_status", "INDEXED").set("etag", fetched.etag())
                    .set("last_modified", fetched.lastModified()).set("content_hash", fetched.contentHash())
                    .set("current_document_id", next.getDocumentId()).set("current_revision", next.getSourceRevision())
                    .set("last_success_at", now).set("next_sync_at", nextSyncAt(source, 0))
                    .set("last_error", null).set("consecutive_failures", 0).eq("id", sourceId));
            runDao.update(null, new UpdateWrapper<KnowledgeSyncRunEntity>()
                    .set("status", "INDEXED").set("documents_updated", 1)
                    .set("finished_at", now).eq("id", runId));
            knowledgeBaseService.updateStatistics(source.getDatasetId(), previous == null ? 1 : 0,
                    (long) value(next.getChunkCount()) - value(previous == null ? null : previous.getChunkCount()),
                    value(next.getTokenCount()) - value(previous == null ? null : previous.getTokenCount()));
        });
    }

    private void unchanged(KnowledgeSourceEntity source, String runId, WebPageConnector.FetchResult fetched) {
        Date now = new Date();
        sourceDao.update(null, new UpdateWrapper<KnowledgeSourceEntity>()
                .set("sync_status", "UNCHANGED").set("last_success_at", now)
                .set("next_sync_at", nextSyncAt(source, 0)).set("last_error", null)
                .set("consecutive_failures", 0)
                .set(StringUtils.isNotBlank(fetched.etag()), "etag", fetched.etag())
                .set(StringUtils.isNotBlank(fetched.lastModified()), "last_modified", fetched.lastModified())
                .eq("id", source.getId()));
        runDao.update(null, new UpdateWrapper<KnowledgeSyncRunEntity>()
                .set("status", "UNCHANGED").set("bytes_fetched", fetched.bytesFetched())
                .set("documents_skipped", 1).set("finished_at", now).eq("id", runId));
    }

    private void fail(String sourceId, String runId, String error, String candidateDocumentId) {
        String safe = StringUtils.abbreviate(StringUtils.defaultIfBlank(error, "同步失败"), 500);
        transactionTemplate.executeWithoutResult(status -> {
            KnowledgeSourceEntity source = sourceDao.selectById(sourceId);
            if (candidateDocumentId != null && source != null) {
                chunkDao.delete(new QueryWrapper<KnowledgeChunkEntity>()
                        .eq("dataset_id", source.getDatasetId()).eq("document_id", candidateDocumentId));
                documentDao.delete(new QueryWrapper<DocumentEntity>()
                        .eq("dataset_id", source.getDatasetId()).eq("document_id", candidateDocumentId)
                        .eq("enabled", 0));
            }
            if (source != null) {
                int failures = value(source.getConsecutiveFailures()) + 1;
                sourceDao.update(null, new UpdateWrapper<KnowledgeSourceEntity>()
                        .set("sync_status", "ERROR").set("last_error", safe)
                        .set("consecutive_failures", failures).set("next_sync_at", nextSyncAt(source, failures))
                        .eq("id", sourceId));
            }
            runDao.update(null, new UpdateWrapper<KnowledgeSyncRunEntity>()
                    .set("status", "FAILED").set("error", safe)
                    .set("finished_at", new Date()).eq("id", runId));
        });
    }

    private void recoverStuckSources() {
        Date cutoff = new Date(System.currentTimeMillis() - STUCK_AFTER_MS);
        List<KnowledgeSourceEntity> stuck = sourceDao.selectList(new QueryWrapper<KnowledgeSourceEntity>()
                .eq("sync_status", "SYNCING").lt("last_sync_at", cutoff));
        for (KnowledgeSourceEntity source : stuck) {
            sourceDao.update(null, new UpdateWrapper<KnowledgeSourceEntity>()
                    .set("sync_status", "ERROR").set("last_error", "同步任务长时间没有进展，已自动释放")
                    .set("next_sync_at", new Date()).eq("id", source.getId()).eq("sync_status", "SYNCING"));
            runDao.update(null, new UpdateWrapper<KnowledgeSyncRunEntity>()
                    .set("status", "FAILED").set("error", "同步任务长时间没有进展，已自动释放")
                    .set("finished_at", new Date()).eq("source_id", source.getId())
                    .in("status", List.of("RUNNING", "INDEXING")));
        }
    }

    private void updateRunIndexing(String runId, int revision, long bytesFetched) {
        runDao.update(null, new UpdateWrapper<KnowledgeSyncRunEntity>()
                .set("status", "INDEXING").set("revision", revision)
                .set("bytes_fetched", bytesFetched).eq("id", runId));
    }

    private KnowledgeSyncRunEntity newRun(KnowledgeSourceEntity source, String triggerType) {
        KnowledgeSyncRunEntity run = new KnowledgeSyncRunEntity();
        run.setSourceId(source.getId());
        run.setDatasetId(source.getDatasetId());
        run.setTriggerType("MANUAL".equals(triggerType) ? "MANUAL" : "SCHEDULED");
        run.setStatus("RUNNING");
        run.setBytesFetched(0L);
        run.setDocumentsUpdated(0);
        run.setDocumentsSkipped(0);
        run.setStartedAt(new Date());
        return run;
    }

    private Map<String, Object> parserConfig(String raw) {
        if (StringUtils.isBlank(raw)) return null;
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RenException("知识库解析配置不是有效JSON", e);
        }
    }

    private Date nextSyncAt(KnowledgeSourceEntity source, int failures) {
        long baseMinutes = Math.max(5, value(source.getRefreshIntervalMinutes()));
        long multiplier = 1L << Math.min(Math.max(failures, 0), 6);
        return new Date(System.currentTimeMillis() + Math.min(1440L, baseMinutes * multiplier) * 60_000L);
    }

    private String safeDocumentName(String name, int revision) {
        String safe = StringUtils.defaultIfBlank(name, "web-source").replaceAll("[\\/\\r\\n]", "_");
        return StringUtils.abbreviate(safe, 140) + "-v" + revision + ".md";
    }

    private int value(Integer value) { return value == null ? 0 : value; }
    private long value(Long value) { return value == null ? 0L : value; }

    private String safeError(Exception exception) {
        Throwable cursor = exception;
        while (cursor.getCause() != null && cursor.getCause() != cursor) cursor = cursor.getCause();
        String message = StringUtils.defaultIfBlank(cursor.getMessage(), "动态知识同步失败");
        message = message.replaceAll(
                "(?i)(api[_-]?key|secret|token|password|authorization)\\s*[:=]\\s*[^\\s,;，；]+", "$1=***");
        message = message.replaceAll("(?i)Bearer\\s+[^\\s,;，；]+", "Bearer ***");
        return StringUtils.abbreviate(message, 500);
    }

    private static final class ByteArrayMultipartFile implements MultipartFile {
        private final String filename;
        private final String contentType;
        private final byte[] content;
        private ByteArrayMultipartFile(String filename, String contentType, byte[] content) {
            this.filename = filename; this.contentType = contentType; this.content = content.clone();
        }
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return filename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content.clone(); }
        @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(content); }
        @Override public void transferTo(java.io.File dest) throws java.io.IOException {
            java.nio.file.Files.write(dest.toPath(), content);
        }
    }
}
