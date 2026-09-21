package xiaozhi.modules.knowledge.service;

import java.util.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.knowledge.dao.*;
import xiaozhi.modules.knowledge.dto.*;
import xiaozhi.modules.knowledge.entity.*;

/** Human edits are canonical database content; the original upload is never rewritten. */
@Service
@RequiredArgsConstructor
public class KnowledgeEntryService {
    private final KnowledgeBaseService bases;
    private final KnowledgeResourcePermissionService permissions;
    private final DocumentDao documents;
    private final KnowledgeChunkDao chunks;
    private final KnowledgeBaseDao baseDao;
    private final KnowledgeFilesService files;

    public Map<String, Object> list(String dataset, String document, int page, int size, String keywords) {
        KnowledgeBaseDTO base = authorize(dataset, false);
        DocumentEntity doc = document(dataset, document, false);
        if (keywords != null && keywords.length() > 200) throw new RenException("搜索内容不能超过200字");
        String reason = readOnlyReason(base, doc);
        if (!"local_knowledge".equals(doc.getSourceType())) {
            var remote = files.listChunks(dataset, document,
                    xiaozhi.modules.knowledge.dto.document.ChunkDTO.ListReq.builder()
                            .page(Math.max(1, page)).pageSize(Math.max(1, Math.min(100, size))).keywords(keywords).build());
            List<Map<String, Object>> rows = new ArrayList<>();
            if (remote.getChunks() != null) for (var item : remote.getChunks()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.getId()); row.put("chunkId", item.getId()); row.put("content", item.getContent());
                row.put("enabled", Boolean.FALSE.equals(item.getAvailable()) ? 0 : 1); row.put("editVersion", 0);
                rows.add(row);
            }
            return Map.of("list", rows, "total", Optional.ofNullable(remote.getTotal()).orElse(0L),
                    "editable", false, "reason", reason, "indexStatus", "ready");
        }
        QueryWrapper<KnowledgeChunkEntity> query = scope(dataset, document);
        if (StringUtils.isNotBlank(keywords)) {
            query.and(q -> q.like("content", keywords).or().like("question", keywords).or().like("answer", keywords));
        }
        var result = chunks.selectPage(new Page<>(Math.max(1, page), Math.max(1, Math.min(100, size))),
                query.orderByAsc("sort_order", "id"));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("list", result.getRecords().stream().map(this::view).toList());
        response.put("total", result.getTotal());
        response.put("editable", reason.isEmpty());
        response.put("reason", reason);
        response.put("indexStatus", Integer.valueOf(1).equals(doc.getIndexDirty()) ? "pending" : "ready");
        return response;
    }

    @Transactional
    public Map<String, Object> create(String dataset, String document, KnowledgeEntryRequest request) {
        DocumentEntity doc = writableDocument(dataset, document);
        KnowledgeChunkEntity entry = new KnowledgeChunkEntity();
        entry.setId(UUID.randomUUID().toString().replace("-", ""));
        entry.setChunkId("manual_" + entry.getId());
        entry.setDatasetId(dataset);
        entry.setDocumentId(document);
        entry.setWorkspaceId(doc.getWorkspaceId());
        entry.setEditVersion(0L);
        var last = chunks.selectList(scope(dataset, document).orderByDesc("sort_order").last("LIMIT 1"));
        entry.setSortOrder(last.isEmpty() ? 0 : Optional.ofNullable(last.get(0).getSortOrder()).orElse(0) + 1);
        apply(entry, request);
        entry.setCreatedAt(new Date());
        chunks.insert(entry);
        updateDocument(doc, 1, entry.getTokenCount());
        return saved(entry);
    }

    @Transactional
    public Map<String, Object> update(String dataset, String document, String id, KnowledgeEntryRequest request) {
        DocumentEntity doc = writableDocument(dataset, document);
        KnowledgeChunkEntity entry = entry(dataset, document, id);
        checkVersion(entry, request.getExpectedVersion());
        return updateLocked(doc, entry, request);
    }

    private Map<String, Object> updateLocked(DocumentEntity doc, KnowledgeChunkEntity entry, KnowledgeEntryRequest request) {
        long previousVersion = version(entry);
        long oldTokens = Optional.ofNullable(entry.getTokenCount()).orElse(0L);
        apply(entry, request);
        entry.setEditVersion(previousVersion + 1);
        int changed = chunks.update(null, new UpdateWrapper<KnowledgeChunkEntity>()
                .eq("id", entry.getId()).eq("dataset_id", entry.getDatasetId()).eq("document_id", entry.getDocumentId())
                .eq("edit_version", previousVersion)
                .set("content", entry.getContent()).set("question", entry.getQuestion()).set("answer", entry.getAnswer())
                .set("enabled", entry.getEnabled()).set("edit_version", entry.getEditVersion())
                .set("token_count", entry.getTokenCount()).set("embedding_json", null).set("updated_at", entry.getUpdatedAt()));
        if (changed != 1) throw new RenException("内容已被他人修改，请刷新后重试");
        updateDocument(doc, 0, entry.getTokenCount() - oldTokens);
        return saved(entry);
    }

    private void updateDocument(DocumentEntity doc, int countDelta, long tokenDelta) {
        documents.update(null, new UpdateWrapper<DocumentEntity>().eq("id", doc.getId())
                .set("content_version", Optional.ofNullable(doc.getContentVersion()).orElse(0L) + 1)
                .set("index_dirty", 1).set("updated_at", new Date())
                .set("chunk_count", Optional.ofNullable(doc.getChunkCount()).orElse(0) + countDelta)
                .set("token_count", Math.max(0, Optional.ofNullable(doc.getTokenCount()).orElse(0L) + tokenDelta)));
        baseDao.updateStatsAfterChange(doc.getDatasetId(), 0, (long) countDelta, tokenDelta);
    }

    private KnowledgeBaseDTO authorize(String dataset, boolean write) {
        KnowledgeBaseDTO base = bases.getByDatasetId(dataset);
        if (write) permissions.requireWrite(base); else permissions.requireRead(base);
        return base;
    }

    private DocumentEntity writableDocument(String dataset, String document) {
        KnowledgeBaseDTO base = authorize(dataset, true);
        if (baseDao.selectOne(new QueryWrapper<KnowledgeBaseEntity>().eq("dataset_id", dataset).last("FOR UPDATE")) == null)
            throw new RenException("知识库不存在或已被删除");
        DocumentEntity doc = document(dataset, document, true);
        String reason = readOnlyReason(base, doc);
        if (!reason.isEmpty()) throw new RenException(reason);
        return doc;
    }

    private String readOnlyReason(KnowledgeBaseDTO base, DocumentEntity doc) {
        if (!permissions.canWrite(base)) return "没有此知识库的编辑权限";
        if (!"local_knowledge".equals(doc.getSourceType())) return "外部知识库暂不支持在此编辑，请使用原平台管理内容";
        if (StringUtils.isNotBlank(doc.getKnowledgeSourceId())) return "动态来源内容只读，请修改原始来源后同步，避免人工修改被覆盖";
        if (!"DONE".equals(doc.getRun())) return "文档尚未处理完成，暂不可编辑";
        return "";
    }

    private DocumentEntity document(String dataset, String document, boolean lock) {
        QueryWrapper<DocumentEntity> query = new QueryWrapper<DocumentEntity>().eq("dataset_id", dataset).eq("document_id", document);
        if (lock) query.last("FOR UPDATE");
        DocumentEntity doc = documents.selectOne(query);
        if (doc == null) throw new RenException("文档不存在或不属于当前知识库");
        return doc;
    }

    private KnowledgeChunkEntity entry(String dataset, String document, String id) {
        KnowledgeChunkEntity entry = chunks.selectOne(scope(dataset, document).eq("id", id));
        if (entry == null) throw new RenException("知识条目不存在或不属于当前文档");
        return entry;
    }

    private QueryWrapper<KnowledgeChunkEntity> scope(String dataset, String document) {
        return new QueryWrapper<KnowledgeChunkEntity>().eq("dataset_id", dataset).eq("document_id", document);
    }

    private long version(KnowledgeChunkEntity entry) { return Optional.ofNullable(entry.getEditVersion()).orElse(0L); }

    private void checkVersion(KnowledgeChunkEntity entry, Long expected) {
        if (expected == null || expected != version(entry)) throw new RenException("内容已被他人修改，请刷新后重试");
    }

    private void apply(KnowledgeChunkEntity entry, KnowledgeEntryRequest request) {
        String question = StringUtils.trimToEmpty(request.getQuestion());
        String answer = StringUtils.trimToEmpty(request.getAnswer());
        String content = StringUtils.trimToEmpty(request.getContent());
        if (!question.isEmpty() || !answer.isEmpty()) {
            if (question.isEmpty() || answer.isEmpty()) throw new RenException("问题和答案必须同时填写");
            content = "问题：" + question + "\n答案：" + answer;
        }
        if (content.isEmpty() || content.length() > 20000 || question.length() > 4000 || answer.length() > 16000)
            throw new RenException("知识内容不能为空且不能超过长度限制");
        if (request.getEnabled() == null || request.getEnabled() < 0 || request.getEnabled() > 1)
            throw new RenException("启用状态不合法");
        entry.setContent(content);
        entry.setQuestion(question);
        entry.setAnswer(answer);
        entry.setEnabled(request.getEnabled());
        entry.setEmbeddingJson(null);
        entry.setTokenCount((long) Math.ceil(content.codePoints().mapToDouble(c -> c > 127 ? 1.5 : 0.75).sum()));
        entry.setUpdatedAt(new Date());
    }

    private Map<String, Object> saved(KnowledgeChunkEntity entry) {
        Map<String, Object> result = view(entry);
        result.put("indexStatus", "pending");
        return result;
    }

    private Map<String, Object> view(KnowledgeChunkEntity entry) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entry.getId()); result.put("chunkId", entry.getChunkId());
        result.put("content", entry.getContent()); result.put("question", entry.getQuestion()); result.put("answer", entry.getAnswer());
        result.put("enabled", Optional.ofNullable(entry.getEnabled()).orElse(1));
        result.put("editVersion", version(entry)); result.put("updatedAt", entry.getUpdatedAt());
        return result;
    }
}
