package xiaozhi.modules.knowledge.source.service;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.knowledge.dao.DocumentDao;
import xiaozhi.modules.knowledge.dao.KnowledgeChunkDao;
import xiaozhi.modules.knowledge.dto.KnowledgeBaseDTO;
import xiaozhi.modules.knowledge.entity.DocumentEntity;
import xiaozhi.modules.knowledge.entity.KnowledgeChunkEntity;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.knowledge.source.connector.PublicUrlGuard;
import xiaozhi.modules.knowledge.source.dao.KnowledgeSourceDao;
import xiaozhi.modules.knowledge.source.dao.KnowledgeSyncRunDao;
import xiaozhi.modules.knowledge.source.dto.KnowledgeSourceRequest;
import xiaozhi.modules.knowledge.source.dto.KnowledgeSourceView;
import xiaozhi.modules.knowledge.source.entity.KnowledgeSourceEntity;
import xiaozhi.modules.knowledge.source.entity.KnowledgeSyncRunEntity;
import xiaozhi.modules.security.user.SecurityUser;

/** Data-source registry. Fetching and publication belong to KnowledgeSourceSyncService. */
@Service
public class KnowledgeSourceService {
    private final KnowledgeSourceDao sourceDao;
    private final KnowledgeSyncRunDao runDao;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentDao documentDao;
    private final KnowledgeChunkDao chunkDao;
    private final TransactionTemplate transactionTemplate;

    public KnowledgeSourceService(KnowledgeSourceDao sourceDao, KnowledgeSyncRunDao runDao,
            KnowledgeBaseService knowledgeBaseService, DocumentDao documentDao,
            KnowledgeChunkDao chunkDao, TransactionTemplate transactionTemplate) {
        this.sourceDao = sourceDao;
        this.runDao = runDao;
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentDao = documentDao;
        this.chunkDao = chunkDao;
        this.transactionTemplate = transactionTemplate;
    }

    public List<KnowledgeSourceView> list(String datasetId) {
        requireLocalDataset(datasetId);
        return sourceDao.selectList(new QueryWrapper<KnowledgeSourceEntity>()
                        .eq("dataset_id", datasetId).orderByDesc("created_at"))
                .stream().map(this::toView).toList();
    }

    public List<KnowledgeSyncRunEntity> listRuns(String datasetId, String sourceId) {
        requireSource(datasetId, sourceId);
        return runDao.selectList(new QueryWrapper<KnowledgeSyncRunEntity>()
                .eq("dataset_id", datasetId).eq("source_id", sourceId)
                .orderByDesc("started_at").last("LIMIT 50"));
    }

    public KnowledgeSourceView create(String datasetId, KnowledgeSourceRequest request) {
        return createManaged(datasetId, request, SecurityUser.getUserId());
    }

    /** Creates a source from a trusted background workflow with an explicit audit actor. */
    public KnowledgeSourceView createManaged(String datasetId, KnowledgeSourceRequest request, Long creator) {
        if (creator == null) {
            throw new RenException("动态数据源创建者不能为空");
        }
        KnowledgeBaseDTO dataset = requireLocalDataset(datasetId);
        PublicUrlGuard.requirePublicHttpUrl(request.getSourceUri());
        Date now = new Date();
        KnowledgeSourceEntity entity = new KnowledgeSourceEntity();
        entity.setDatasetId(datasetId);
        entity.setWorkspaceId(dataset.getWorkspaceId());
        entity.setName(StringUtils.trim(request.getName()));
        entity.setSourceType("WEB_PAGE");
        entity.setSourceUri(StringUtils.trim(request.getSourceUri()));
        entity.setRefreshIntervalMinutes(request.getRefreshIntervalMinutes());
        entity.setEnabled(Boolean.TRUE.equals(request.getEnabled()) ? 1 : 0);
        entity.setSyncStatus(entity.getEnabled() == 1 ? "PENDING" : "PAUSED");
        entity.setCurrentRevision(0);
        entity.setConsecutiveFailures(0);
        entity.setNextSyncAt(entity.getEnabled() == 1 ? now : null);
        entity.setCreator(creator);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        sourceDao.insert(entity);
        return toView(entity);
    }

    public KnowledgeSourceView update(String datasetId, String sourceId, KnowledgeSourceRequest request) {
        KnowledgeSourceEntity existing = requireSource(datasetId, sourceId);
        if ("SYNCING".equals(existing.getSyncStatus())) {
            throw new RenException("数据源正在同步，暂时不能修改");
        }
        PublicUrlGuard.requirePublicHttpUrl(request.getSourceUri());
        boolean uriChanged = !StringUtils.equals(existing.getSourceUri(), StringUtils.trim(request.getSourceUri()));
        existing.setName(StringUtils.trim(request.getName()));
        existing.setSourceUri(StringUtils.trim(request.getSourceUri()));
        existing.setRefreshIntervalMinutes(request.getRefreshIntervalMinutes());
        existing.setEnabled(Boolean.TRUE.equals(request.getEnabled()) ? 1 : 0);
        existing.setSyncStatus(existing.getEnabled() == 1 ? "PENDING" : "PAUSED");
        existing.setNextSyncAt(existing.getEnabled() == 1 ? new Date() : null);
        existing.setLastError(null);
        existing.setUpdatedAt(new Date());
        if (uriChanged) {
            existing.setEtag(null);
            existing.setLastModified(null);
            existing.setContentHash(null);
        }
        sourceDao.updateById(existing);
        return toView(existing);
    }

    public void delete(String datasetId, String sourceId) {
        KnowledgeSourceEntity source = requireSource(datasetId, sourceId);
        if ("SYNCING".equals(source.getSyncStatus())) {
            throw new RenException("数据源正在同步，暂时不能删除");
        }
        transactionTemplate.executeWithoutResult(status -> {
            List<DocumentEntity> documents = documentDao.selectList(new QueryWrapper<DocumentEntity>()
                    .eq("dataset_id", datasetId).eq("knowledge_source_id", sourceId));
            long activeChunks = documents.stream().filter(this::isEnabled)
                    .mapToLong(item -> value(item.getChunkCount())).sum();
            long activeTokens = documents.stream().filter(this::isEnabled)
                    .mapToLong(item -> value(item.getTokenCount())).sum();
            int activeDocuments = Math.toIntExact(documents.stream().filter(this::isEnabled).count());
            if (!documents.isEmpty()) {
                chunkDao.delete(new QueryWrapper<KnowledgeChunkEntity>()
                        .eq("dataset_id", datasetId)
                        .in("document_id", documents.stream().map(DocumentEntity::getDocumentId).toList()));
            }
            documentDao.delete(new QueryWrapper<DocumentEntity>()
                    .eq("dataset_id", datasetId).eq("knowledge_source_id", sourceId));
            sourceDao.deleteById(sourceId);
            if (activeDocuments > 0 || activeChunks > 0 || activeTokens > 0) {
                knowledgeBaseService.updateStatistics(datasetId, -activeDocuments, -activeChunks, -activeTokens);
            }
        });
    }

    KnowledgeSourceEntity requireSource(String datasetId, String sourceId) {
        KnowledgeSourceEntity source = sourceDao.selectOne(new QueryWrapper<KnowledgeSourceEntity>()
                .eq("id", sourceId).eq("dataset_id", datasetId));
        if (source == null) {
            throw new RenException("知识库动态数据源不存在");
        }
        return source;
    }

    KnowledgeBaseDTO requireLocalDataset(String datasetId) {
        KnowledgeBaseDTO dataset = knowledgeBaseService.getByDatasetId(datasetId);
        Map<String, Object> config = knowledgeBaseService.getRAGConfigByDatasetId(datasetId);
        if (config == null || !"local_knowledge".equals(config.get("type"))) {
            throw new RenException("动态数据源第一阶段仅支持本地知识库");
        }
        return dataset;
    }

    KnowledgeSourceView toView(KnowledgeSourceEntity entity) {
        KnowledgeSourceView view = new KnowledgeSourceView();
        BeanUtils.copyProperties(entity, view);
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled() == 1);
        return view;
    }

    private boolean isEnabled(DocumentEntity document) {
        return document.getEnabled() == null || document.getEnabled() == 1;
    }

    private long value(Integer value) { return value == null ? 0L : value.longValue(); }
    private long value(Long value) { return value == null ? 0L : value; }
}
