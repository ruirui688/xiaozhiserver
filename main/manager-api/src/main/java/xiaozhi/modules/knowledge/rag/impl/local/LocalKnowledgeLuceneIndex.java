package xiaozhi.modules.knowledge.rag.impl.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import xiaozhi.modules.knowledge.entity.KnowledgeChunkEntity;
import xiaozhi.modules.knowledge.entity.DocumentEntity;

/**
 * 本地知识库的内嵌磁盘索引。索引只保存可从原始文档重建的派生数据，
 * 不引入 Elasticsearch、向量数据库或常驻检索服务。
 */
@Component
public class LocalKnowledgeLuceneIndex {
    private static final String FIELD_SCOPE_ID = "scope_id";
    private static final String FIELD_DATASET_ID = "dataset_id";
    private static final String FIELD_DOCUMENT_ID = "document_id";
    private static final String FIELD_CHUNK_ID = "chunk_id";
    private static final String FIELD_SCHEMA_VERSION = "schema_version";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_PRIMARY_NAME = "primary_name";
    private static final String FIELD_QUESTION = "question";
    private static final String FIELD_ANSWER = "answer";
    private static final String FIELD_DOCUMENT_NAME = "document_name";
    private static final String FIELD_CONTENT_VERSION = "content_version";
    private static final String FIELD_KIND = "record_kind";
    private static final String SCHEMA_VERSION = "3";

    private final Path root;
    private final Analyzer analyzer;

    @Autowired
    public LocalKnowledgeLuceneIndex(
            @Value("${knowledge.local-index-root:uploadfile/knowledge-index}") String root) {
        this(Path.of(root));
    }

    LocalKnowledgeLuceneIndex(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.analyzer = new CJKAnalyzer();
    }

    public synchronized void replaceDocument(String datasetId, String documentId, String documentName,
            List<KnowledgeChunkEntity> chunks) {
        replaceDocument(datasetId, documentId, documentName, chunks, 0L);
    }

    public synchronized void replaceDocument(String datasetId, String documentId, String documentName,
            List<KnowledgeChunkEntity> chunks, long contentVersion) {
        requireId(datasetId, "datasetId");
        requireId(documentId, "documentId");
        try {
            Files.createDirectories(root);
            try (Directory directory = FSDirectory.open(root);
                    IndexWriter writer = new IndexWriter(directory, writerConfig())) {
                writer.deleteDocuments(new Term(FIELD_SCOPE_ID, scopeId(datasetId, documentId)));
                if (chunks != null) {
                    for (KnowledgeChunkEntity chunk : chunks) {
                        if (chunk != null && !Integer.valueOf(0).equals(chunk.getEnabled())
                                && StringUtils.isNotBlank(chunk.getChunkId())) {
                            writer.addDocument(toDocument(datasetId, documentId, documentName, chunk));
                        }
                    }
                }
                // A marker also represents an empty/fully disabled document.
                Document marker = new Document();
                marker.add(new StringField(FIELD_SCOPE_ID, scopeId(datasetId, documentId), Field.Store.NO));
                marker.add(new StringField(FIELD_DATASET_ID, datasetId, Field.Store.YES));
                marker.add(new StringField(FIELD_DOCUMENT_ID, documentId, Field.Store.YES));
                marker.add(new StringField(FIELD_SCHEMA_VERSION, SCHEMA_VERSION, Field.Store.NO));
                marker.add(new StringField(FIELD_KIND, "version", Field.Store.NO));
                marker.add(new StringField(FIELD_CONTENT_VERSION, Long.toString(contentVersion), Field.Store.YES));
                writer.addDocument(marker);
                writer.commit();
            }
        } catch (IOException e) {
            throw new IllegalStateException("更新本地知识库索引失败", e);
        }
    }

    /** Compare each process's local index to authoritative DB versions. */
    public List<DocumentEntity> staleDocuments(List<DocumentEntity> documents) {
        if (documents.isEmpty() || !indexExists()) {
            return documents;
        }
        try (Directory directory = FSDirectory.open(root);
                DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            List<DocumentEntity> stale = new ArrayList<>();
            for (DocumentEntity document : documents) {
                BooleanQuery query = new BooleanQuery.Builder()
                        .add(filteredQuery(new TermQuery(new Term(FIELD_KIND, "version")),
                                List.of(document.getDatasetId()), List.of(document.getDocumentId())), BooleanClause.Occur.MUST)
                        .build();
                TopDocs found = searcher.search(query, 1);
                long expected = document.getContentVersion() == null ? 0L : document.getContentVersion();
                if (found.scoreDocs.length == 0 || !Long.toString(expected).equals(
                        searcher.storedFields().document(found.scoreDocs[0].doc).get(FIELD_CONTENT_VERSION))) {
                    stale.add(document);
                }
            }
            return stale;
        } catch (IOException e) {
            throw new IllegalStateException("读取知识条目索引版本失败", e);
        }
    }

    public synchronized void deleteDocuments(String datasetId, Collection<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty() || !Files.exists(root)) {
            return;
        }
        requireId(datasetId, "datasetId");
        try (Directory directory = FSDirectory.open(root);
                IndexWriter writer = new IndexWriter(directory, writerConfig())) {
            for (String documentId : documentIds) {
                requireId(documentId, "documentId");
                writer.deleteDocuments(new Term(FIELD_SCOPE_ID, scopeId(datasetId, documentId)));
            }
            writer.commit();
        } catch (IOException e) {
            throw new IllegalStateException("删除本地知识库索引失败", e);
        }
    }

    public synchronized void deleteDatasets(Collection<String> datasetIds) {
        if (datasetIds == null || datasetIds.isEmpty() || !Files.exists(root)) {
            return;
        }
        try (Directory directory = FSDirectory.open(root);
                IndexWriter writer = new IndexWriter(directory, writerConfig())) {
            for (String datasetId : datasetIds) {
                requireId(datasetId, "datasetId");
                writer.deleteDocuments(new Term(FIELD_DATASET_ID, datasetId));
            }
            writer.commit();
        } catch (IOException e) {
            throw new IllegalStateException("删除本地知识库索引失败", e);
        }
    }

    public boolean containsAll(Collection<String> datasetIds, Collection<String> documentIds) {
        if (datasetIds == null || datasetIds.isEmpty() || !indexExists()) {
            return false;
        }
        try (Directory directory = FSDirectory.open(root);
                DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            if (documentIds != null && !documentIds.isEmpty()) {
                for (String documentId : documentIds) {
                    if (searcher.count(filteredQuery(
                            new MatchAllDocsQuery(), datasetIds, List.of(documentId))) == 0) {
                        return false;
                    }
                }
                return true;
            }
            for (String datasetId : datasetIds) {
                if (searcher.count(filteredQuery(new MatchAllDocsQuery(), List.of(datasetId), null)) == 0) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("读取本地知识库索引失败", e);
        }
    }

    public List<SearchHit> search(List<String> queries, Collection<String> datasetIds,
            Collection<String> documentIds, int limit) {
        if (queries == null || queries.isEmpty() || datasetIds == null || datasetIds.isEmpty()
                || limit < 1 || !indexExists()) {
            return List.of();
        }
        try (Directory directory = FSDirectory.open(root);
                DirectoryReader reader = DirectoryReader.open(directory)) {
            Set<String> terms = analyze(queries);
            if (terms.isEmpty()) {
                return List.of();
            }
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity());
            boolean preferPrimaryName = !LocalKnowledgeStructuredIntent.requestsAuxiliaryRelationship(
                    String.join(" ", queries));
            Query query = filteredQuery(textQuery(terms, preferPrimaryName), datasetIds, documentIds);
            TopDocs topDocs = searcher.search(query, Math.max(1, limit));
            List<SearchHit> hits = new ArrayList<>(topDocs.scoreDocs.length);
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document document = searcher.storedFields().document(scoreDoc.doc);
                KnowledgeChunkEntity chunk = new KnowledgeChunkEntity();
                chunk.setDatasetId(document.get(FIELD_DATASET_ID));
                chunk.setDocumentId(document.get(FIELD_DOCUMENT_ID));
                chunk.setChunkId(document.get(FIELD_CHUNK_ID));
                chunk.setContent(document.get(FIELD_CONTENT));
                chunk.setQuestion(document.get(FIELD_QUESTION));
                chunk.setAnswer(document.get(FIELD_ANSWER));
                hits.add(new SearchHit(chunk, document.get(FIELD_DOCUMENT_NAME), normalizeScore(scoreDoc.score)));
            }
            return hits;
        } catch (IOException e) {
            throw new IllegalStateException("检索本地知识库索引失败", e);
        }
    }

    private Document toDocument(String datasetId, String documentId, String documentName,
            KnowledgeChunkEntity chunk) {
        Document document = new Document();
        document.add(new StringField(FIELD_SCOPE_ID, scopeId(datasetId, documentId), Field.Store.NO));
        document.add(new StringField(FIELD_DATASET_ID, datasetId, Field.Store.YES));
        document.add(new StringField(FIELD_DOCUMENT_ID, documentId, Field.Store.YES));
        document.add(new StringField(FIELD_CHUNK_ID, chunk.getChunkId(), Field.Store.YES));
        document.add(new StringField(FIELD_SCHEMA_VERSION, SCHEMA_VERSION, Field.Store.NO));
        document.add(new TextField(FIELD_CONTENT, StringUtils.defaultString(chunk.getContent()), Field.Store.YES));
        document.add(new TextField(FIELD_QUESTION, StringUtils.defaultString(chunk.getQuestion()), Field.Store.YES));
        document.add(new TextField(FIELD_ANSWER, StringUtils.defaultString(chunk.getAnswer()), Field.Store.YES));
        document.add(new TextField(FIELD_DOCUMENT_NAME, StringUtils.defaultString(documentName), Field.Store.YES));
        document.add(new TextField(FIELD_PRIMARY_NAME, LocalKnowledgeStructuredIntent.primaryName(String.join("\n",
                StringUtils.defaultString(chunk.getContent()),
                StringUtils.defaultString(chunk.getQuestion()),
                StringUtils.defaultString(chunk.getAnswer()))), Field.Store.NO));
        return document;
    }

    private Query textQuery(Set<String> terms, boolean preferPrimaryName) {
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        for (String term : terms) {
            BooleanQuery.Builder fields = new BooleanQuery.Builder();
            fields.add(new BoostQuery(new TermQuery(new Term(FIELD_CONTENT, term)), 1.0F), BooleanClause.Occur.SHOULD);
            fields.add(new BoostQuery(new TermQuery(new Term(FIELD_QUESTION, term)), 2.2F), BooleanClause.Occur.SHOULD);
            fields.add(new BoostQuery(new TermQuery(new Term(FIELD_ANSWER, term)), 1.6F), BooleanClause.Occur.SHOULD);
            fields.add(new BoostQuery(new TermQuery(new Term(FIELD_DOCUMENT_NAME, term)), 0.8F),
                    BooleanClause.Occur.SHOULD);
            fields.add(new BoostQuery(new TermQuery(new Term(FIELD_PRIMARY_NAME, term)),
                    preferPrimaryName ? 5.0F : 0.8F),
                    BooleanClause.Occur.SHOULD);
            fields.setMinimumNumberShouldMatch(1);
            query.add(fields.build(), BooleanClause.Occur.SHOULD);
        }
        query.setMinimumNumberShouldMatch(1);
        return query.build();
    }

    private Query filteredQuery(Query textQuery, Collection<String> datasetIds, Collection<String> documentIds) {
        BooleanQuery.Builder result = new BooleanQuery.Builder();
        result.add(textQuery, BooleanClause.Occur.MUST);
        result.add(new TermQuery(new Term(FIELD_SCHEMA_VERSION, SCHEMA_VERSION)), BooleanClause.Occur.FILTER);
        result.add(idFilter(FIELD_DATASET_ID, datasetIds), BooleanClause.Occur.FILTER);
        if (documentIds != null && !documentIds.isEmpty()) {
            result.add(idFilter(FIELD_DOCUMENT_ID, documentIds), BooleanClause.Occur.FILTER);
        }
        return result.build();
    }

    private Query idFilter(String field, Collection<String> ids) {
        BooleanQuery.Builder filter = new BooleanQuery.Builder();
        for (String id : ids) {
            requireId(id, field);
            filter.add(new TermQuery(new Term(field, id)), BooleanClause.Occur.SHOULD);
        }
        filter.setMinimumNumberShouldMatch(1);
        return filter.build();
    }

    private Set<String> analyze(List<String> queries) throws IOException {
        Set<String> terms = new LinkedHashSet<>();
        for (String query : queries) {
            try (TokenStream stream = analyzer.tokenStream(FIELD_CONTENT, StringUtils.defaultString(query))) {
                CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
                stream.reset();
                while (stream.incrementToken() && terms.size() < 96) {
                    if (StringUtils.isNotBlank(term.toString())) {
                        terms.add(term.toString());
                    }
                }
                stream.end();
            }
            if (terms.size() >= 96) {
                break;
            }
        }
        return terms;
    }

    private IndexWriterConfig writerConfig() {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        config.setSimilarity(new BM25Similarity());
        return config;
    }

    private boolean indexExists() {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Directory directory = FSDirectory.open(root)) {
            return DirectoryReader.indexExists(directory);
        } catch (IOException e) {
            return false;
        }
    }

    private String scopeId(String datasetId, String documentId) {
        return datasetId + ":" + documentId;
    }

    private float normalizeScore(float score) {
        return Math.min(0.99F, Math.max(0F, 1F - (float) Math.exp(-score / 4F)));
    }

    private void requireId(String id, String label) {
        if (StringUtils.isBlank(id) || id.length() > 128 || !id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException(label + "不合法");
        }
    }

    public record SearchHit(KnowledgeChunkEntity chunk, String documentName, float score) {
    }
}
