package xiaozhi.modules.knowledge.service;

import java.util.List;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xiaozhi.modules.knowledge.dao.*;
import xiaozhi.modules.knowledge.entity.*;

/** Caller deletes documents in the same transaction while document locks remain held. */
@Service
@RequiredArgsConstructor
public class KnowledgeEntryCleanupService {
    private final DocumentDao documents;
    private final KnowledgeChunkDao chunks;
    private final KnowledgeEntryRevisionDao revisions;
    private final KnowledgeBaseDao bases;

    @Transactional
    public List<DocumentEntity> delete(String dataset, List<String> documentIds) {
        bases.selectOne(new QueryWrapper<KnowledgeBaseEntity>().eq("dataset_id", dataset).last("FOR UPDATE"));
        QueryWrapper<DocumentEntity> locks = new QueryWrapper<DocumentEntity>().eq("dataset_id", dataset);
        QueryWrapper<KnowledgeChunkEntity> chunkScope = new QueryWrapper<KnowledgeChunkEntity>().eq("dataset_id", dataset);
        QueryWrapper<KnowledgeEntryRevisionEntity> revisionScope = new QueryWrapper<KnowledgeEntryRevisionEntity>().eq("dataset_id", dataset);
        if (documentIds != null) {
            if (documentIds.isEmpty()) return List.of();
            locks.in("document_id", documentIds);
            chunkScope.in("document_id", documentIds);
            revisionScope.in("document_id", documentIds);
        }
        List<DocumentEntity> locked = documents.selectList(locks.orderByAsc("id").last("FOR UPDATE"));
        revisions.delete(revisionScope);
        chunks.delete(chunkScope);
        return locked;
    }
}
