---
status: supporting
owner: knowledge
last_verified: 2026-07-22
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/knowledge
  - main/xiaozhi-server/plugins_func/functions/search_from_local_knowledge.py
---

# 知识库完整链路

本文档说明 xiaozhi-esp32-server 知识库（Knowledge Base / RAG）的完整链路：从文件上传、分块、向量化，到检索注入 LLM 上下文的端到端流程。

先读：[模型选择与厂商目录](model-selection-and-vendor-catalog.md)、[记忆系统与知识库治理](../models/memory-and-knowledge-governance.md)。

## 1. 总览

```text
┌─────────────┐     ┌──────────────────┐     ┌─────────────────┐
│ manager-web │     │   manager-api    │     │  xiaozhi-server │
│ 知识库管理   │────→│  知识库 CRUD      │     │                 │
│ 文件上传     │     │  文件分块/向量化   │     │  search_from_   │
│ 分块预览     │     │  检索 API         │────→│  local_knowledge│
│ 检索测试     │     │                  │     │  search_from_   │
└─────────────┘     │  适配器:          │     │  ragflow        │
                    │  - RAGFlowAdapter │     │                 │
                    │  - LocalKnowledge │     │  → LLM tool     │
                    │    Adapter        │     │  → REQLLM 注入  │
                    └──────────────────┘     └─────────────────┘
```

## 2. 知识库管理 (Java)

### 2.1 API 端点

```text
KnowledgeBaseController (/datasets)
  GET    /datasets                        分页列表
  GET    /datasets/{id}                   单个详情
  POST   /datasets                        创建
  PUT    /datasets/{id}                   更新
  DELETE /datasets/{id}                   删除（级联）
  DELETE /datasets/batch?ids=...          批量删除

KnowledgeFilesController (/datasets/{id}/documents)
  GET    /documents                       分页文档列表
  GET    /documents/status/{status}       按解析状态过滤
  POST   /documents                       上传文档 (MultipartFile)
  DELETE /documents                       批量删除
  DELETE /documents/{id}                  单个删除
  POST   /chunks                          触发解析/分块
  GET    /documents/{id}/chunks           查看分块列表
  POST   /retrieval-test                  检索测试
```

### 2.2 两种适配器

| 适配器 | 类型 | 存储 | 检索 |
|--------|------|------|------|
| `RAGFlowAdapter` | `"ragflow"` | RAGFlow 服务器 | RAGFlow API |
| `LocalKnowledgeAdapter` | `"local_knowledge"` | MySQL `ai_rag_knowledge_chunk` | MySQL fulltext + 向量混合 |

适配器选择：`ai_model_config.config_json.type` → `KnowledgeBaseAdapterFactory`

### 2.3 支持的文档类型

| 适配器 | 支持格式 |
|--------|---------|
| RAGFlow | pdf, doc, docx, txt, md, mdx, csv, xls, xlsx, ppt, pptx |
| LocalKnowledge | txt, md, mdx, csv, xlsx, xls, json, jsonl, qa, HY-KB 混合 markdown |

## 3. LocalKnowledge 适配器

### 3.1 文件分块管道

```text
LocalKnowledgeAdapter.parseFile()  (1658 行)

按文件类型分发:
  Excel (.xlsx/.xls) → Apache POI 行解析 → question/answer 检测
  CSV               → 类似行解析
  JSON/JSONL        → 结构化 Q&A 解析
  HY-KB Markdown    → h2 标签检测 → "---" 分段
  Plain QA          → "Q:"/"A:" 前缀检测

可选 LLM 分块 (llm_enabled=true):
  chunkDocumentWithLlm()
    → 分段 (max llmMaxInputChars)
    → LLM 提取结构化 chunks
    → 失败 → per-section 重试 (1 次) → 回退规则分块

可选 LLM 精炼:
  refineChunksWithLlm()
    → 每 chunk 清理/提取 question+answer
    → 失败 → 保留原始 chunk（graceful degradation）
```

本地适配器在上传请求内同步完成解析、切片和入库，上传成功时文档状态直接为
`DONE`。`POST /chunks` 对本地文档只校验上传阶段生成的切片是否仍然存在，
不会把已有切片反向拼接成有损原文后重新切片；缺少切片时必须重新上传原文件。
RAGFlow 适配器仍通过 `POST /chunks` 异步启动解析，状态先进入 `RUNNING`。

知识库新增和更新时，`parserConfig` 字符串在 manager-api 边界显式解码为
RAG 请求 DTO；数据库继续保存用户提交的完整 JSON。远端响应仅在用户未提交配置时
用于补全，不能覆盖本地切片、模型和召回扩展字段。

### 3.2 向量嵌入（新增）

```text
LocalKnowledgeAdapter.generateAndStoreEmbeddings()
  每个 chunk → EmbeddingService.generateEmbedding(content)
  → 存储到 ai_rag_knowledge_chunk.embedding_json (float[] as JSON)

配置（在 KnowledgeBase 的 parserConfig 中）:
  embeddingEnabled: true/false
  embeddingBaseUrl: OpenAI-compatible endpoint
  embeddingApiKey: API key
  embeddingModel: model name
```

### 3.3 混合检索

```text
LocalKnowledgeAdapter.retrievalTest()

1. 查询向量化：
   if embedding_enabled:
     query_embedding = embeddingService.generateEmbedding(question)

2. 关键词匹配：
   - MySQL INSTR(content, term)     子串精确匹配
   - MATCH(content) AGAINST(... IN BOOLEAN MODE)  全文索引

3. CJK n-gram tokenization → 词频评分

4. 向量评分（如有 embedding）：
   cosine_similarity(query_embedding, chunk_embedding)
   → combined_score = 0.5 * keyword_score + 0.5 * vector_score

5. Fallback: embedding 不可用 → 纯关键词搜索 + 警告日志
```

### 3.4 数据表

```text
ai_rag_dataset          知识库元数据
  - id, datasetId, name, embeddingModel, chunkMethod, parserConfig

ai_rag_knowledge_document  文档记录
  - id, datasetId, documentId, name, type, run (processing status)
  - chunkCount, tokenCount, error, progress

ai_rag_knowledge_chunk    分块内容
  - id, datasetId, documentId, chunkId, content
  - question, answer, sortOrder
  - embedding_json (新增 - mediumtext)  FLOAT 数组的 JSON
```

## 4. RAGFlow 适配器

### 4.1 代理模式

```text
RAGFlowAdapter  → RAGFlowClient  → HTTP → RAGFlow Server
  - uploadDocument()    → POST /api/v1/datasets/{id}/documents
  - deleteDocument()    → DELETE
  - retrievalTest()     → POST /api/v1/retrieval
  - 本地仅维护影子记录（ai_rag_knowledge_document）
```

### 4.2 HTTP 连接

```text
RAGFlowClient:
  - 优先使用 Spring 注入的 RestTemplate（连接池复用）
  - Fallback: SimpleClientHttpRequestFactory
```

### 4.3 删除一致性

```text
远程删除成功 → 删除本地影子记录
远程删除失败 → 抛出 RenException → 本地影子记录保留
```

## 5. 知识检索注入 (Python)

### 5.1 两个搜索工具

```text
search_from_local_knowledge  (plugins_func/functions/)
  → POST {api_url}/config/knowledge/retrieval
  → dataset_ids + question
  → 超时 8s
  → Action.REQLLM → context_text 注入

search_from_ragflow  (plugins_func/functions/)
  → POST {ragflow_url}/api/v1/retrieval
  → API key 认证
  → 超时 5s
  → Action.REQLLM → context_text 注入
```

### 5.2 LLM 上下文注入

```text
LLM 调用 search_from_local_knowledge / search_from_ragflow
  → 工具执行 → ActionResponse(Action.REQLLM, context_text)
  → dialogue.put(Message(role="tool", content=context_text))
  → LLM 再调用 → 用知识上下文生成自然回复
```

**注意**：知识检索结果作为 tool message 注入（不是 system prompt），与记忆注入（`<memory>` 标签在 system prompt 中）是不同的机制。LLM 需要自行交叉参考两个来源。

## 6. Vue 前端

### 6.1 页面与组件

| 页面/组件 | 文件 | 职责 |
|-----------|------|------|
| KnowledgeBaseManagement | `views/KnowledgeBaseManagement.vue` | KB CRUD 列表 |
| KnowledgeBaseDialog | `components/KnowledgeBaseDialog.vue` | 创建/编辑表单 |
| KnowledgeFileUpload | `views/KnowledgeFileUpload.vue` | 文档列表 + 上传 |
| FileUploadPanel | `components/knowledge/FileUploadPanel.vue` | 拖拽上传面板 |
| FilePreviewDialog | `components/knowledge/FilePreviewDialog.vue` | 分块预览 |
| KnowledgeBaseSelectorDialog | `components/KnowledgeBaseSelectorDialog.vue` | 智能体选择 KB |
| manager-mobile knowledge pages | `manager-mobile/src/pages/knowledge/` | 小程序知识库、文档、切片与召回测试 |

### 6.2 上传限制

- 前端: 10MB 硬编码限制 → 后端同步 enforce `knowledge.max-upload-size` 配置 (默认 10MB)
- 支持格式: .doc, .docx, .pdf, .txt, .md, .mdx, .csv, .xls, .xlsx, .ppt, .pptx, .json, .jsonl, .qa
- 批量上传: 逐个文件 Promise.all

## 7. 关键文件

| 文件 | 行数 | 职责 |
|------|------|------|
| **Java 侧** | | |
| `modules/knowledge/controller/KnowledgeBaseController.java` | ~120 | KB CRUD API |
| `modules/knowledge/controller/KnowledgeFilesController.java` | ~200 | 文件 CRUD API |
| `modules/knowledge/rag/KnowledgeBaseAdapterFactory.java` | ~50 | 适配器工厂 |
| `modules/knowledge/rag/impl/LocalKnowledgeAdapter.java` | 1658 | 本地 KB 引擎 |
| `modules/knowledge/rag/impl/RAGFlowAdapter.java` | ~600 | RAGFlow 代理 |
| `modules/knowledge/rag/RAGFlowClient.java` | ~180 | RAGFlow HTTP 客户端 |
| `modules/knowledge/service/impl/KnowledgeBaseServiceImpl.java` | ~580 | KB 服务 |
| `modules/knowledge/service/impl/KnowledgeFilesServiceImpl.java` | ~600 | 文件服务 |
| `modules/knowledge/entity/KnowledgeBaseEntity.java` | ~60 | KB 实体 |
| `modules/knowledge/entity/DocumentEntity.java` | ~80 | 文档实体 |
| `modules/knowledge/entity/KnowledgeChunkEntity.java` | ~50 | 分块实体 |
| `modules/config/controller/ConfigController.java` | ~100 | 配置 API（含检索端点） |
| `db/changelog/202606111530.sql` | — | 分块表 + local_knowledge 配置 |
| `db/changelog/202607011000.sql` | — | embedding_json 列 |
| **Python 侧** | | |
| `plugins_func/functions/search_from_local_knowledge.py` | ~80 | 本地 KB 搜索工具 |
| `plugins_func/functions/search_from_ragflow.py` | ~60 | RAGFlow 搜索工具 |
| **Vue 前端** | | |
| `views/KnowledgeBaseManagement.vue` | ~300 | KB 管理页面 |
| `views/KnowledgeFileUpload.vue` | 1260 | 文档管理页面 |
| `components/knowledge/FileUploadPanel.vue` | 299 | 上传面板 |
| `components/knowledge/FilePreviewDialog.vue` | 561 | 分块预览 |

## 8. 已知修复（2026-07）

| # | 问题 | 修复 |
|---|------|------|
| 1 | 本地 KB 无向量嵌入 | embedding_json 列 + cosine similarity 混合评分 |
| 2 | RAGFlow 删除失败后本地影子丢失 | 远程成功后才删本地 |
| 3 | RAGFlowClient 连接池无效 | 优先使用 Spring RestTemplate |
| 4 | Token 估算粗糙 (content.length()/2) | CJK-aware (1.5/0.75 per char) |
| 5 | LLM 分块失败无重试 | per-section 重试 + 回退 |
| 6 | 后端无上传大小限制 | knowledge.max-upload-size 配置 |
| 7 | RAGFlow 名解析 indexOf('_') 脆 | 已知用户名前缀剥离 |
| 8 | LLM 分块失败标记 DONE | 添加 quality_degraded 标记 |
| 9 | chunk_id 重上传唯一冲突 | timestamp 后缀 + 删除计数检查 |
| 10 | RAGFlowClient 日期格式硬编码 | 移除固定格式解析 |
| 11 | embeddingModel 字段 UI 误导 | 添加 Javadoc 说明 |
| 12 | dataset 删除孤儿 chunk | 添加 chunk 级联清理 |
| 13 | Knowledge-Memory 无编排 | 添加 TODO 注释 |
| 14 | 新增/更新时 parserConfig 被转换丢失或被远端响应覆盖 | 边界显式解码，原始完整 JSON 优先持久化 |
| 15 | 本地文档已同步切片却被标记为 RUNNING | 适配器声明同步解析，服务写入 DONE/100% |

## 9. 排查方法

```bash
# 查看知识库检索请求
scripts/dev logs server -f | grep -E "search_from_local_knowledge|search_from_ragflow|retrieval"

# 检查知识库数据
mysql> SELECT kb.name, count(d.id) as docs, count(c.id) as chunks
       FROM ai_rag_dataset kb
       LEFT JOIN ai_rag_knowledge_document d ON d.dataset_id = kb.id
       LEFT JOIN ai_rag_knowledge_chunk c ON c.dataset_id = kb.id
       GROUP BY kb.id;

# 查看 embedding 状态
mysql> SELECT count(*), embedding_json IS NOT NULL as has_embedding
       FROM ai_rag_knowledge_chunk GROUP BY has_embedding;
```
