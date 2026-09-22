package xiaozhi.modules.knowledge.rag.impl.local;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

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
            LocalKnowledgeLuceneIndex.SearchOutcome outcome = index.searchOutcome(
                    LocalKnowledgeQueryExpander.expand(rawQuestion, rewrittenQuestion),
                    req.getDatasetIds(), req.getDocumentIds(), options.candidateLimit());
            return buildLuceneResult(outcome, rawQuestion, rewrittenQuestion, options);
        } catch (RuntimeException e) {
            log.warn("本地知识库磁盘索引不可用，降级到有界词法召回: errorType={}",
                    e.getClass().getSimpleName());
            return fallbackLexical(req, rawQuestion, rewrittenQuestion, options);
        }
    }

    private void ensureIndex(List<String> datasetIds, List<String> documentIds) {
        if (datasetIds == null || datasetIds.isEmpty()) {
            return;
        }
        QueryWrapper<DocumentEntity> query = new QueryWrapper<DocumentEntity>()
                .in("dataset_id", datasetIds).eq("run", "DONE")
                .and(w -> w.isNull("enabled").or().eq("enabled", 1));
        if (documentIds != null && !documentIds.isEmpty()) {
            query.in("document_id", documentIds);
        }
        List<DocumentEntity> documents = documentDao.selectList(query);
        for (DocumentEntity document : index.staleDocuments(documents)) {
            List<KnowledgeChunkEntity> chunks = chunkDao.selectIndexableChunks(
                    List.of(document.getDatasetId()), List.of(document.getDocumentId()));
            long version = document.getContentVersion() == null ? 0L : document.getContentVersion();
            index.replaceDocument(document.getDatasetId(), document.getDocumentId(), document.getName(), chunks, version);
            // A concurrent edit must retain its dirty marker and newer version.
            documentDao.update(null, new UpdateWrapper<DocumentEntity>()
                    .eq("dataset_id", document.getDatasetId()).eq("document_id", document.getDocumentId())
                    .eq("content_version", version).set("index_dirty", 0));
        }
    }

    private RetrievalDTO.ResultVO buildLuceneResult(LocalKnowledgeLuceneIndex.SearchOutcome outcome,
            String rawQuestion, String rewrittenQuestion, Options options) {
        List<LocalKnowledgeLuceneIndex.SearchHit> candidates = outcome.hits();
        if (candidates == null || candidates.isEmpty()) {
            return emptyResult();
        }
        candidates = authoritativeHits(candidates);
        Map<String, DocumentEntity> documents = loadDocs(candidates.stream()
                .map(LocalKnowledgeLuceneIndex.SearchHit::chunk)
                .toList());
        LocalKnowledgeLexicalScorer lexical = new LocalKnowledgeLexicalScorer(options.matchLevel());
        // 词法打分必须复用检索侧同一套有效词项：否则“慧苑/什么/推荐”这类被剔除的泛词
        // 仍会在这里给无关切片加分，把真正命中主题词的切片压下去。
        Set<String> rawTerms = outcome.terms().isEmpty() ? lexical.tokenize(rawQuestion) : outcome.terms();
        Set<String> rewrittenTerms = outcome.terms().isEmpty() ? lexical.tokenize(rewrittenQuestion)
                : outcome.terms();
        String entityQuery = rawQuestion + " " + rewrittenQuestion;
        List<LocalKnowledgeLuceneIndex.SearchHit> enabledCandidates = candidates.stream()
                .filter(hit -> isEnabledDocument(documents.get(scopeId(hit.chunk().getDatasetId(), hit.chunk().getDocumentId()))))
                .toList();
        Comparator<ScoredChunk> ordering = LocalKnowledgeStructuredReranker.comparator(
                entityQuery,
                item -> structuredText(item.chunk()),
                entityMatchFunction(entityQuery, enabledCandidates.stream()
                        .map(LocalKnowledgeLuceneIndex.SearchHit::chunk).toList(), options.resultLimit()),
                ScoredChunk::score);

        List<ScoredChunk> scored = enabledCandidates.stream()
                .map(hit -> {
                    float rawLexical = lexical.score(rawQuestion, hit.chunk(), rawTerms);
                    float rewrittenLexical = lexical.score(rewrittenQuestion, hit.chunk(), rewrittenTerms);
                    float lexicalScore = Math.max(rawLexical, rewrittenLexical);
                    float score = Math.min(0.99F, (hit.score() * 0.72F) + (lexicalScore * 0.28F));
                    boolean exactQuestion = isExactQuestionMatch(rawQuestion, rawLexical)
                            || isExactQuestionMatch(rewrittenQuestion, rewrittenLexical);
                    return new ScoredChunk(hit.chunk(), hit.documentName(), score, hit.score(), exactQuestion);
                })
                .filter(item -> StringUtils.isBlank(rawQuestion) || item.score() >= options.threshold())
                .sorted(exactQuestionFirst().thenComparing(ordering))
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
        List<KnowledgeChunkEntity> enabledChunks = chunks.stream()
                .filter(chunk -> !Integer.valueOf(0).equals(chunk.getEnabled()))
                .filter(chunk -> isEnabledDocument(documents.get(scopeId(chunk.getDatasetId(), chunk.getDocumentId()))))
                .toList();
        String entityQuery = rawQuestion + " " + rewrittenQuestion;
        Comparator<ScoredChunk> ordering = LocalKnowledgeStructuredReranker.comparator(
                entityQuery,
                item -> structuredText(item.chunk()),
                entityMatchFunction(entityQuery, enabledChunks, options.resultLimit()),
                ScoredChunk::score);
        List<ScoredChunk> scored = enabledChunks.stream()
                .map(chunk -> {
                    List<Float> queryScores = expandedQueries.stream()
                            .map(query -> lexical.score(query, chunk, lexical.tokenize(query)))
                            .toList();
                    float score = queryScores.stream().max(Float::compare).orElse(0F);
                    boolean exactQuestion = false;
                    for (int i = 0; i < queryScores.size(); i++) {
                        if (isExactQuestionMatch(expandedQueries.get(i), queryScores.get(i))) {
                            exactQuestion = true;
                            break;
                        }
                    }
                    DocumentEntity document = documents.get(scopeId(chunk.getDatasetId(), chunk.getDocumentId()));
                    return new ScoredChunk(chunk, document == null ? null : document.getName(), score, score,
                            exactQuestion);
                })
                .filter(item -> StringUtils.isBlank(rawQuestion) || item.score() >= options.threshold())
                .sorted(exactQuestionFirst().thenComparing(ordering))
                .limit(options.resultLimit())
                .toList();
        return toResult(scored, documents);
    }

    /**
     * 切片正文、问题或答案完整包含用户问题时，词法评分取 1F，属于最高置信度证据。
     * 这种命中必须先于主实体/价格等结构化排序返回，否则会被同库大量表格行挤出结果窗口。
     */
    private static boolean isExactQuestionMatch(String question, float lexicalScore) {
        return StringUtils.isNotBlank(question) && lexicalScore >= 1F;
    }

    private static Comparator<ScoredChunk> exactQuestionFirst() {
        return Comparator.comparingInt((ScoredChunk item) -> item.exactQuestion() ? 1 : 0).reversed();
    }

    /**
     * 主实体优先只在实体确实能缩小候选集时生效。如果带主实体字段的候选已经多到能占满整个
     * 结果窗口，实体优先只会把同一实体前缀的表格行排在前面，把真正回答问题的切片挤出召回结果
     * （例如“慧苑演出几点开始”被十几行“套餐名称：慧苑…”占满）。此时实体命中恒为 0，
     * 排序退化为按相关度进行。
     */
    static boolean entityMatchDiscriminates(String query, List<KnowledgeChunkEntity> candidates, int resultLimit) {
        long matched = candidates.stream()
                .filter(chunk -> LocalKnowledgeStructuredIntent.primaryEntityMatch(query, structuredText(chunk)) > 0)
                .count();
        return matched < resultLimit;
    }

    private ToIntFunction<ScoredChunk> entityMatchFunction(String query, List<KnowledgeChunkEntity> candidates,
            int resultLimit) {
        if (!entityMatchDiscriminates(query, candidates, resultLimit)) {
            return item -> 0;
        }
        return item -> LocalKnowledgeStructuredIntent.primaryEntityMatch(query, structuredText(item.chunk()));
    }

    private List<LocalKnowledgeLuceneIndex.SearchHit> authoritativeHits(
            List<LocalKnowledgeLuceneIndex.SearchHit> hits) {
        // Never return stale/disabled content even if another process edits
        // between version checking and searching this local index.
        List<KnowledgeChunkEntity> rows = chunkDao.selectList(new QueryWrapper<KnowledgeChunkEntity>()
                .in("dataset_id", hits.stream().map(h -> h.chunk().getDatasetId()).distinct().toList())
                .in("document_id", hits.stream().map(h -> h.chunk().getDocumentId()).distinct().toList())
                .in("chunk_id", hits.stream().map(h -> h.chunk().getChunkId()).distinct().toList()));
        Map<String, KnowledgeChunkEntity> current = rows.stream().collect(Collectors.toMap(
                this::chunkScope, Function.identity(), (left, right) -> left));
        List<LocalKnowledgeLuceneIndex.SearchHit> result = new ArrayList<>();
        for (LocalKnowledgeLuceneIndex.SearchHit hit : hits) {
            KnowledgeChunkEntity row = current.get(chunkScope(hit.chunk()));
            if (row == null || Integer.valueOf(0).equals(row.getEnabled())) {
                continue;
            }
            if (!java.util.Objects.equals(row.getContent(), hit.chunk().getContent())
                    || !StringUtils.defaultString(row.getQuestion()).equals(StringUtils.defaultString(hit.chunk().getQuestion()))
                    || !StringUtils.defaultString(row.getAnswer()).equals(StringUtils.defaultString(hit.chunk().getAnswer()))) {
                throw new IllegalStateException("知识条目在索引查询期间已更新");
            }
            result.add(new LocalKnowledgeLuceneIndex.SearchHit(row, hit.documentName(), hit.score()));
        }
        return result;
    }

    private String chunkScope(KnowledgeChunkEntity chunk) {
        return scopeId(chunk.getDatasetId(), chunk.getDocumentId()) + ":" + chunk.getChunkId();
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
                target.putIfAbsent(scopeId(chunk.getDatasetId(), chunk.getDocumentId()) + ":"
                        + StringUtils.defaultIfBlank(chunk.getChunkId(), chunk.getId()), chunk);
            }
        }
    }

    private RetrievalDTO.ResultVO toResult(List<ScoredChunk> scored, Map<String, DocumentEntity> documents) {
        List<RetrievalDTO.HitVO> hits = new ArrayList<>();
        int position = 0;
        for (ScoredChunk item : scored) {
            KnowledgeChunkEntity chunk = item.chunk();
            DocumentEntity document = documents.get(scopeId(chunk.getDatasetId(), chunk.getDocumentId()));
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
                item -> scopeId(item.chunk().getDatasetId(), item.chunk().getDocumentId()), LinkedHashMap::new, Collectors.counting()));
        List<RetrievalDTO.DocAggVO> docAggs = counts.entrySet().stream()
                .map(entry -> {
                    DocumentEntity document = documents.get(entry.getKey());
                    return RetrievalDTO.DocAggVO.builder()
                            .docId(document.getDocumentId())
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
        return documentDao.selectList(new QueryWrapper<DocumentEntity>().in("document_id", documentIds)
                        .in("dataset_id", chunks.stream().map(KnowledgeChunkEntity::getDatasetId).distinct().toList()))
                .stream()
                .collect(Collectors.toMap(doc -> scopeId(doc.getDatasetId(), doc.getDocumentId()),
                        Function.identity(), (left, right) -> left));
    }

    private boolean isEnabledDocument(DocumentEntity document) {
        return document != null
                && (document.getEnabled() == null || document.getEnabled() == 1)
                && "DONE".equalsIgnoreCase(document.getRun());
    }

    private static String structuredText(KnowledgeChunkEntity chunk) {
        return String.join("\n",
                StringUtils.defaultString(chunk.getContent()),
                StringUtils.defaultString(chunk.getQuestion()),
                StringUtils.defaultString(chunk.getAnswer()));
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

    private record ScoredChunk(KnowledgeChunkEntity chunk, String documentName, float score, float termScore,
            boolean exactQuestion) {
    }
}
