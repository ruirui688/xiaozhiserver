package xiaozhi.modules.knowledge.rag.impl.local;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.extern.slf4j.Slf4j;
import xiaozhi.modules.knowledge.dao.DocumentDao;
import xiaozhi.modules.knowledge.dao.KnowledgeChunkDao;
import xiaozhi.modules.knowledge.dto.document.RetrievalDTO;
import xiaozhi.modules.knowledge.entity.DocumentEntity;
import xiaozhi.modules.knowledge.entity.KnowledgeChunkEntity;

/**
 * 统一承载后台召回测试与设备真实对话的本地检索逻辑。
 * 首选 Lucene 磁盘索引；索引异常时保留有界 SQL/词法降级，避免影响语音主链路。
 */
@Component
@Slf4j
public class LocalKnowledgeRetrievalEngine {
    private final KnowledgeChunkDao chunkDao;
    private final DocumentDao documentDao;
    private final LocalKnowledgeLuceneIndex index;

    public LocalKnowledgeRetrievalEngine(KnowledgeChunkDao chunkDao, DocumentDao documentDao,
            LocalKnowledgeLuceneIndex index) {
        this.chunkDao = chunkDao;
        this.documentDao = documentDao;
        this.index = index;
    }

    public RetrievalDTO.ResultVO retrieve(RetrievalDTO.TestReq req, Options options) {
        String rewrittenQuestion = StringUtils.defaultString(req.getQuestion());
        String rawQuestion = StringUtils.defaultIfBlank(req.getRawQuestion(), rewrittenQuestion);
        try {
            ensureIndex(req.getDatasetIds(), req.getDocumentIds());
            List<LocalKnowledgeLuceneIndex.SearchHit> candidates = index.search(
                    LocalKnowledgeQueryExpander.expand(rawQuestion, rewrittenQuestion),
                    req.getDatasetIds(), req.getDocumentIds(), options.candidateLimit());
            return buildLuceneResult(candidates, rawQuestion, rewrittenQuestion, options);
        } catch (RuntimeException e) {
            log.warn("本地知识库磁盘索引不可用，降级到有界词法召回: errorType={}",
                    e.getClass().getSimpleName());
            return fallbackLexical(req, rawQuestion, rewrittenQuestion, options);
        }
    }

    private void ensureIndex(List<String> datasetIds, List<String> documentIds) {
        if (index.containsAll(datasetIds, documentIds)) {
            return;
        }
        List<KnowledgeChunkEntity> chunks = chunkDao.selectIndexableChunks(datasetIds, documentIds);
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        Map<String, DocumentEntity> documents = loadDocs(chunks);
        chunks.stream().collect(Collectors.groupingBy(
                chunk -> scopeId(chunk.getDatasetId(), chunk.getDocumentId()),
                LinkedHashMap::new,
                Collectors.toList()))
                .forEach((scope, documentChunks) -> {
                    KnowledgeChunkEntity first = documentChunks.get(0);
                    DocumentEntity document = documents.get(first.getDocumentId());
                    index.replaceDocument(first.getDatasetId(), first.getDocumentId(),
                            document == null ? null : document.getName(), documentChunks);
                });
    }

    private RetrievalDTO.ResultVO buildLuceneResult(List<LocalKnowledgeLuceneIndex.SearchHit> candidates,
            String rawQuestion, String rewrittenQuestion, Options options) {
        if (candidates == null || candidates.isEmpty()) {
            return emptyResult();
        }
        Map<String, DocumentEntity> documents = loadDocs(candidates.stream()
                .map(LocalKnowledgeLuceneIndex.SearchHit::chunk)
                .toList());
        LocalKnowledgeLexicalScorer lexical = new LocalKnowledgeLexicalScorer(options.matchLevel());
        Set<String> rawTerms = lexical.tokenize(rawQuestion);
        Set<String> rewrittenTerms = lexical.tokenize(rewrittenQuestion);
        Comparator<ScoredChunk> ordering = LocalKnowledgeStructuredReranker.comparator(
                rawQuestion + " " + rewrittenQuestion,
                item -> item.chunk().getContent(),
                ScoredChunk::score);

        List<ScoredChunk> scored = candidates.stream()
                .filter(hit -> isEnabledDocument(documents.get(hit.chunk().getDocumentId())))
                .map(hit -> {
                    float lexicalScore = Math.max(
                            lexical.score(rawQuestion, hit.chunk(), rawTerms),
                            lexical.score(rewrittenQuestion, hit.chunk(), rewrittenTerms));
                    float score = Math.min(0.99F, (hit.score() * 0.72F) + (lexicalScore * 0.28F));
                    return new ScoredChunk(hit.chunk(), hit.documentName(), score, hit.score());
                })
                .filter(item -> StringUtils.isBlank(rawQuestion) || item.score() >= options.threshold())
                .sorted(ordering)
                .limit(options.resultLimit())
                .toList();
        return toResult(scored, documents);
    }

    private RetrievalDTO.ResultVO fallbackLexical(RetrievalDTO.TestReq req, String rawQuestion,
            String rewrittenQuestion, Options options) {
        LocalKnowledgeLexicalScorer lexical = new LocalKnowledgeLexicalScorer(options.matchLevel());
        List<String> expandedQueries = LocalKnowledgeQueryExpander.expand(rawQuestion, rewrittenQuestion);
        Set<String> terms = expandedQueries.stream()
                .flatMap(query -> lexical.tokenize(query).stream())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        List<String> candidateTerms = lexical.selectCandidateTerms(terms);
        String fulltextQuery = lexical.buildFulltextQuery(candidateTerms);
        List<KnowledgeChunkEntity> chunks = loadFallbackCandidates(
                req, candidateTerms, fulltextQuery, options.candidateLimit());
        Map<String, DocumentEntity> documents = loadDocs(chunks);
        Comparator<ScoredChunk> ordering = LocalKnowledgeStructuredReranker.comparator(
                rawQuestion + " " + rewrittenQuestion,
                item -> item.chunk().getContent(),
                ScoredChunk::score);
        List<ScoredChunk> scored = chunks.stream()
                .filter(chunk -> isEnabledDocument(documents.get(chunk.getDocumentId())))
                .map(chunk -> {
                    float score = expandedQueries.stream()
                            .map(query -> lexical.score(query, chunk, lexical.tokenize(query)))
                            .max(Float::compare)
                            .orElse(0F);
                    DocumentEntity document = documents.get(chunk.getDocumentId());
                    return new ScoredChunk(chunk, document == null ? null : document.getName(), score, score);
                })
                .filter(item -> StringUtils.isBlank(rawQuestion) || item.score() >= options.threshold())
                .sorted(ordering)
                .limit(options.resultLimit())
                .toList();
        return toResult(scored, documents);
    }

    private List<KnowledgeChunkEntity> loadFallbackCandidates(RetrievalDTO.TestReq req,
            List<String> candidateTerms, String fulltextQuery, int candidateLimit) {
        Map<String, KnowledgeChunkEntity> candidates = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(fulltextQuery)) {
            addCandidates(candidates, chunkDao.selectRetrievalCandidates(
                    req.getDatasetIds(), req.getDocumentIds(), Collections.emptyList(),
                    fulltextQuery, candidateLimit));
        }
        if ((StringUtils.isBlank(fulltextQuery) || candidates.size() < candidateLimit)
                && candidateTerms != null && !candidateTerms.isEmpty()) {
            addCandidates(candidates, chunkDao.selectRetrievalCandidates(
                    req.getDatasetIds(), req.getDocumentIds(), candidateTerms, null,
                    candidateLimit - candidates.size()));
        }
        return new ArrayList<>(candidates.values());
    }

    private void addCandidates(Map<String, KnowledgeChunkEntity> target, List<KnowledgeChunkEntity> chunks) {
        if (chunks == null) {
            return;
        }
        for (KnowledgeChunkEntity chunk : chunks) {
            if (chunk != null) {
                target.putIfAbsent(StringUtils.defaultIfBlank(chunk.getChunkId(), chunk.getId()), chunk);
            }
        }
    }

    private RetrievalDTO.ResultVO toResult(List<ScoredChunk> scored, Map<String, DocumentEntity> documents) {
        List<RetrievalDTO.HitVO> hits = new ArrayList<>();
        int position = 0;
        for (ScoredChunk item : scored) {
            KnowledgeChunkEntity chunk = item.chunk();
            DocumentEntity document = documents.get(chunk.getDocumentId());
            hits.add(RetrievalDTO.HitVO.builder()
                    .id(chunk.getChunkId())
                    .content(chunk.getContent())
                    .documentId(chunk.getDocumentId())
                    .datasetId(chunk.getDatasetId())
                    .documentName(document == null ? item.documentName() : document.getName())
                    .similarity(item.score())
                    .termSimilarity(item.termScore())
                    .vectorSimilarity(null)
                    .index(position++)
                    .questions(StringUtils.isBlank(chunk.getQuestion()) ? null : List.of(chunk.getQuestion()))
                    .build());
        }
        Map<String, Long> counts = scored.stream().collect(Collectors.groupingBy(
                item -> item.chunk().getDocumentId(), LinkedHashMap::new, Collectors.counting()));
        List<RetrievalDTO.DocAggVO> docAggs = counts.entrySet().stream()
                .map(entry -> {
                    DocumentEntity document = documents.get(entry.getKey());
                    return RetrievalDTO.DocAggVO.builder()
                            .docId(entry.getKey())
                            .docName(document == null ? entry.getKey() : document.getName())
                            .count(entry.getValue().intValue())
                            .build();
                })
                .toList();
        return RetrievalDTO.ResultVO.builder()
                .chunks(hits)
                .docAggs(docAggs)
                .total((long) hits.size())
                .build();
    }

    private RetrievalDTO.ResultVO emptyResult() {
        return RetrievalDTO.ResultVO.builder()
                .chunks(List.of())
                .docAggs(List.of())
                .total(0L)
                .build();
    }

    private Map<String, DocumentEntity> loadDocs(List<KnowledgeChunkEntity> chunks) {
        List<String> documentIds = chunks.stream()
                .map(KnowledgeChunkEntity::getDocumentId)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        return documentDao.selectList(new QueryWrapper<DocumentEntity>().in("document_id", documentIds))
                .stream()
                .collect(Collectors.toMap(DocumentEntity::getDocumentId, Function.identity(), (left, right) -> left));
    }

    private boolean isEnabledDocument(DocumentEntity document) {
        return document != null
                && (document.getEnabled() == null || document.getEnabled() == 1)
                && "DONE".equalsIgnoreCase(document.getRun());
    }

    private String scopeId(String datasetId, String documentId) {
        return datasetId + ":" + documentId;
    }

    public record Options(int matchLevel, int resultLimit, int candidateLimit, float threshold) {
        public Options {
            matchLevel = Math.max(1, Math.min(5, matchLevel));
            resultLimit = Math.max(1, Math.min(100, resultLimit));
            candidateLimit = Math.max(resultLimit, Math.min(800, candidateLimit));
            threshold = Math.max(0F, Math.min(1F, threshold));
        }
    }

    private record ScoredChunk(KnowledgeChunkEntity chunk, String documentName, float score, float termScore) {
    }
}
