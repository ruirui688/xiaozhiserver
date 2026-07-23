---
status: supporting
owner: knowledge
last_verified: 2026-07-23
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/knowledge
  - main/manager-api/src/main/java/xiaozhi/modules/agent/service/impl/AgentPluginMappingServiceImpl.java
  - main/xiaozhi-server/plugins_func/functions/search_from_local_knowledge.py
  - main/xiaozhi-server/core/utils/prompt_budget.py
  - main/manager-web/src/components/role/RoleEditDialog.vue
  - main/manager-mobile/src/pages/knowledge
---

# 知识库完整链路

本文档说明 xiaozhi-esp32-server 知识库（Knowledge Base / RAG）的完整链路：从文件上传、分块、向量化，到检索注入 LLM 上下文的端到端流程。

模型配置与密钥边界见[模型管理](../models/model-management.md)。记忆保存用户长期事实，知识库保存业务资料和可检索文档；两者不得共用事实表、模型密钥或权限判断。

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
LocalKnowledgeAdapter.parseFile()

源文件规范化:
  Excel (.xlsx/.xls) → Apache POI 转成带字段名的模型输入文本
  txt/md/mdx/csv/json/jsonl/qa → UTF-8 原文

唯一切片路径:
  chunkDocumentWithLlm()
    → 按 llm_max_input_chars 划分模型输入窗口（窗口不是最终切片）
    → OpenAI Chat Completions 兼容 LLM 输出 question/answer/content JSON 数组
    → 单个输入窗口失败时重试 1 次
    → 任一窗口仍失败、超时、空输出或非 JSON：整个上传失败，不写入规则切片
```

本地知识库必须选择模型管理中已启用、测试通过且兼容 OpenAI Chat Completions 的 LLM 配置 ID，保存到
`parser_config.llm_model_id`；不得再次保存 API Key、Base URL 或上游模型名。`llm_enabled=true` 只作为旧版本
滚动升级兼容字段，新实现没有关闭入口。大模型切片只发生在文档解析阶段，不在每次检索时调用 LLM。

LocalKnowledge 上传请求只完成文件校验、安全暂存、`RUNNING` 文档记录创建和后台任务入队，随后立即返回。
大模型切片由 `LocalKnowledgeUploadTaskService` 在专用有界执行器中串行执行，并在每个模型输入窗口完成后更新进度。
页面可以关闭或离开，重新进入后继续从文档列表读取排队、处理中、`DONE` 或 `FAIL` 状态；成功时进度为 100%，
失败时保留脱敏后的错误原因。任务完成、失败或被拒绝后都必须清理暂存文件，服务异常重启遗留的无进展任务由超时巡检标记失败，
不能永久停在处理中。页面“解析”及 `POST /chunks` 只验证上传阶段生成的切片是否仍然存在，不能把数据库切片拼接成有损原文后重新切片。
修改切片模型或参数、或发现切片缺失时，必须删除旧文档并重新上传原文件，再通过“查看切片”和“召回测试”验证。
RAGFlow 仍通过 `POST /chunks` 异步启动远端解析，状态可先进入 `RUNNING`。

知识库新增和更新时，`parserConfig` 字符串在 manager-api 边界显式解码为
RAG 请求 DTO；数据库继续保存用户提交的完整 JSON。远端响应仅在用户未提交配置时
用于补全，不能覆盖本地切片、模型和召回扩展字段。

本轮切片契约固定为严格语义模式：`KnowledgeBaseServiceImpl` 在新增和更新时强制校验托管模型 ID、启用/测试状态及
OpenAI 兼容协议；`LocalKnowledgeTextSplitter` 只负责划分不会丢字的模型输入窗口，不产生可持久化规则切片；
`LocalKnowledgeAdapter` 每次上传重新解析托管模型配置，避免适配器缓存继续使用已修改或停用的旧配置。
本地适配器不支持的格式、MIME 与扩展名不匹配、零切片、模型不可用或调用失败都必须明确失败，不能创建 DONE 记录；后续 `/chunks`
只验证上传时已经生成的切片，不伪造重解析结果。

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

### 5.3 上下文与工具描述预算

当前工作区对本地知识库和 RAGFlow 检索结果使用相同预算：最多保留 5 个去重结果，单项最多 1600 字符，
合计最多 4000 字符。历史 tool message 合计最多 8000 字符，单个结果最多 4000 字符，并优先保留最近结果。裁剪只作用于动态检索内容，
不会修改工具参数类型、枚举或 required schema。

manager-api 生成知识搜索工具描述时不再注入酒类、酒具等特定业务规则。描述最多列出 10 个知识库名称、
4 组范围说明并限制为 640 字符；查询规则只要求依据用户当前问题和已明确上下文。Python 侧还会对全部工具的
描述性文本设置共享预算，避免知识库数量、queryPolicy 或其他工具说明挤占主提示词。
全部工具描述共享 6000 字符预算，单个工具的描述性文本合计最多 1000 字符。

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

### 6.3 智能体知识库绑定

智能体详情的 `functions` 同时承载普通插件映射和知识库映射，后端数据库中的 `paramInfo` 在详情响应中可能是
JSON 字符串，而编辑组件内部使用对象。此前 `RoleEditDialog` 深度监听 `initialForm`：任一字段编辑触发
`form-change` 后，父组件只同步智能体名称并改动 prop，子组件随即用父组件的旧快照重建整个表单，模型选择、
提示词和绑定映射因此被清空。知识库列表异步返回后调用表格 `clearSelection()`，还可能通过 selection-change
把正在恢复的选择覆盖为空。

当前工作区改为显式 `hydrateAgentConfig()`：先合并服务端详情与本地 dirty 字段，再并行加载插件和知识库元数据，
统一解析字符串/对象 `paramInfo`，最后确定性拆分两类映射并恢复 `queryPolicy`。元数据未完成或失败时禁止保存，
用户已修改能力后迟到响应不能覆盖当前选择。知识库选择器使用请求序号丢弃旧响应，并在程序化恢复选择期间屏蔽
空 change 事件。父页面还增加配置加载状态、重复保存锁和显式失败回调；保存失败保留草稿，关闭后重新进入会从
详情响应恢复模型、知识库 ID 与查询策略。

Web 上传不再手工指定 `multipart/form-data`，由浏览器为 `FormData` 生成包含 boundary 的请求头。微信小程序
使用 `uni.uploadFile` 并显式透传 Bearer token 与 `X-Workspace-Id`；知识库页面覆盖 CRUD、上传、解析、切片查看
和召回测试。小程序源码已进入当前 `HEAD`，但本轮仍需完成最终 Mobile 构建和真实微信开发者工具/真机验证。

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
| `components/role/RoleEditDialog.vue` | — | 智能体配置 hydration 与插件/知识库映射恢复 |
| `utils/roleConfigDraft.js` | — | dirty 字段合并和 capability 映射规范化 |
| `core/utils/prompt_budget.py` | — | 记忆、知识、工具历史和 schema 字符预算 |

## 8. 测试层与验证边界

| 层 | 当前覆盖 |
|---|---|
| Java 切片契约 | 必选托管 LLM、OpenAI 兼容校验、无规则回退、LLM 窗口不丢字、后台任务与持久化进度、零切片拒绝 |
| Java 工具描述 | 领域中立、知识库数量与说明长度上限、空元数据确定性输出 |
| Web 组件状态 | 上传提交后不阻塞等待、列表持续显示进度与失败原因、终态通知、晚到详情不覆盖 dirty 字段、保存失败保留草稿、选择器乱序与恢复保护 |
| Python 上下文 | 本地/RAGFlow 结果去重与预算、tool history 总预算、schema 保真、动态 context 和 memory 预算 |
| Mobile | API 与页面源码覆盖 CRUD、上传、解析、切片和召回；最终构建与微信端联调仍待完成 |

自动验证应覆盖 Java 全量测试、项目 Conda 环境中的 Python 测试与 `compileall`、Web 功能测试和构建。
真实 MySQL 数据、Redis、浏览器关闭重开、RAGFlow、外部切片/embedding
模型和微信 `uploadFile` 尚未端到端验证；字符预算也不等于模型 token 预算，正式发布前必须用目标模型与业务长文档
检查召回相关性。

## 9. 已知修复（2026-07）

| # | 问题 | 修复 |
|---|------|------|
| 1 | 本地 KB 无向量嵌入 | embedding_json 列 + cosine similarity 混合评分 |
| 2 | RAGFlow 删除失败后本地影子丢失 | 远程成功后才删本地 |
| 3 | RAGFlowClient 连接池无效 | 优先使用 Spring RestTemplate |
| 4 | Token 估算粗糙 (content.length()/2) | CJK-aware (1.5/0.75 per char) |
| 5 | LLM 分块失败无重试 | per-section 重试；重试失败则整个上传失败 |
| 6 | 后端无上传大小限制 | knowledge.max-upload-size 配置 |
| 7 | RAGFlow 名解析 indexOf('_') 脆 | 已知用户名前缀剥离 |
| 8 | LLM 分块失败仍标记 DONE | 严格失败，不持久化规则切片或 DONE 文档 |
| 9 | chunk_id 重上传唯一冲突 | timestamp 后缀 + 删除计数检查 |
| 10 | RAGFlowClient 日期格式硬编码 | 移除固定格式解析 |
| 11 | embeddingModel 字段 UI 误导 | 添加 Javadoc 说明 |
| 12 | dataset 删除孤儿 chunk | 添加 chunk 级联清理 |
| 13 | Knowledge-Memory 无编排 | 添加 TODO 注释 |
| 14 | 新增/更新时 parserConfig 被转换丢失或被远端响应覆盖 | 边界显式解码，原始完整 JSON 优先持久化 |
| 15 | 本地文档已同步切片却被标记为 RUNNING | 适配器声明同步解析，服务写入 DONE/100% |
| 16 | 智能体编辑时旧 prop 回灌导致模型和知识库绑定丢失 | 显式 hydration、dirty 字段合并、映射规范化和保存门控 |
| 17 | 选择器恢复与乱序请求把已选知识库清空 | 请求序号和 selection 恢复保护 |
| 18 | 知识工具描述包含固定酒类规则且可无限增长 | 领域中立的有界描述构建器 |
| 19 | 记忆、知识检索和工具历史挤占 LLM 上下文 | 动态上下文分域预算，保留静态规则与 schema |

## 10. 排查方法

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
