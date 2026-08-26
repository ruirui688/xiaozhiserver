---
status: supporting
owner: knowledge
last_verified: 2026-08-26
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/knowledge
  - main/manager-api/src/main/java/xiaozhi/modules/agent/service/impl/AgentPluginMappingServiceImpl.java
  - main/xiaozhi-server/plugins_func/functions/search_from_local_knowledge.py
  - main/xiaozhi-server/core/utils/prompt_budget.py
  - main/manager-web/src/components/role/RoleEditDialog.vue
  - main/manager-mobile/src/pages/knowledge
---

# 知识库完整链路

本文档说明 xiaozhi-esp32-server 知识库（Knowledge Base / RAG）的完整链路：从文件上传、分块、低资源关键词召回，到检索注入 LLM 上下文的端到端流程。

模型配置与密钥边界见[模型管理](../models/model-management.md)。记忆保存用户长期事实，知识库保存业务资料和可检索文档；两者不得共用事实表、模型密钥或权限判断。

## 1. 总览

```text
┌─────────────┐     ┌──────────────────┐     ┌─────────────────┐
│ manager-web │     │   manager-api    │     │  xiaozhi-server │
│ 知识库管理   │────→│  知识库 CRUD      │     │                 │
│ 文件上传     │     │  文件分块/词法召回 │     │  search_from_   │
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
  GET    /datasets?scope=MY|OFFICIAL      分页列表
  GET    /datasets/{id}                   单个详情
  POST   /datasets                        创建
  PUT    /datasets/{id}                   更新
  DELETE /datasets/{id}                   删除（级联）
  DELETE /datasets/batch?ids=...          批量删除
  PUT    /datasets/{id}/publication       发布/撤回官方知识库

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

### 2.2 资源范围和维护权限

`ai_rag_dataset.workspace_id` 表示知识库所有者，`scope` 表示可见范围：

- `WORKSPACE`：只允许所属工作空间成员读取，OWNER/ADMIN 写入。
- `OFFICIAL`：所有登录用户可读取和绑定到智能体；普通用户不能上传、修改、删除或配置数据源。
- 发布、撤回和写入官方知识库，只允许超级管理员本人或超级管理员所拥有工作空间的有效成员。
- Harness 自主维护策略只允许绑定到 `OFFICIAL` 知识库，并复用相同官方维护者判断；策略表仍是自动维护是否启用的唯一状态源，不在知识库表重复保存开关。

因此官方知识库既是全局可读资源，又保留明确的所有者和工作空间审计信息。前端隐藏只读操作仅用于改善体验，所有限制均由 controller/service 后端再次校验。

### 2.3 两种适配器

| 适配器 | 类型 | 存储 | 检索 |
|--------|------|------|------|
| `RAGFlowAdapter` | `"ragflow"` | RAGFlow 服务器 | RAGFlow API |
| `LocalKnowledgeAdapter` | `"local_knowledge"` | MySQL `ai_rag_knowledge_chunk` | MySQL fulltext/INSTR + Java 有界词法评分 |

适配器选择：`ai_model_config.config_json.type` → `KnowledgeBaseAdapterFactory`

### 2.4 支持的文档类型

| 适配器 | 支持格式 |
|--------|---------|
| RAGFlow | pdf, doc, docx, txt, md, mdx, csv, xls, xlsx, ppt, pptx |
| LocalKnowledge | txt, md, mdx, csv, xlsx, xls, json, jsonl, qa, HY-KB 混合 markdown |

## 3. LocalKnowledge 适配器

### 3.1 文件分块管道

```text
LocalKnowledgeAdapter.parseFile()
  → LocalKnowledgeDocumentParser 确定性解析（不调用模型）
      Excel/CSV → 按表头和数据行生成带来源信息的切片
      JSON/JSONL → 按对象生成字段化切片
      QA → 保留 question/answer
      TXT/MD/MDX → 按自然边界生成文本切片
  → LocalKnowledgeProcessingStrategy 路由
      结构化记录硬约束:
        Excel/CSV 的每个数据行、JSON/JSONL 的每个对象都是不可合并的原子记录
        无论选择哪种策略，都直接持久化确定性切片，不允许模型跨记录重组
      AUTO:
        普通文本且模型可用 → LLM_ENHANCED
        普通文本且模型不可用 → DIRECT
      DIRECT:
        直接持久化确定性解析结果，不发起切片模型请求
      LLM_ENHANCED:
        仅将非结构化预解析文本交给 chunkDocumentWithLlm()
          → 服务端按安全预算划分模型输入窗口（窗口不是最终切片）
          → OpenAI Chat Completions 兼容 LLM 输出 question/answer/content JSON 对象
          → 单个输入窗口失败时重试 1 次；连续失败且窗口较大时按自然边界减半重试
          → 缩小到最小安全窗口后仍失败、超时、空输出或非 JSON：整个上传失败
```

新建知识库默认使用 `AUTO`。旧知识库没有 `processing_strategy` 字段时按 `LLM_ENHANCED` 解释，避免升级后静默改变
已有处理语义；用户保存后会显式写入 `auto`、`direct` 或 `llm_enhanced`。`DIRECT` 不要求配置切片模型，
`LLM_ENHANCED` 必须选择已启用且测试通过的兼容模型，`AUTO` 中的模型是可选增强能力。模型只增强非结构化正文，
不能改变结构化文件“一行/一个对象对应一个或多个仅由长度上限拆分的切片”这一来源边界。

输入字符数和输出 Token 数不再由页面配置。字符数无法准确推导不同模型的输出长度，人工放大输入窗口容易造成
结构化 JSON 在输出上限处被截断；服务端统一使用保守预算，并在真实响应仍过长时自动缩小输入窗口。历史
`llm_max_input_chars`、`llm_max_tokens` 配置仅作为遗留数据保留，运行时忽略，知识库下次保存时自动清除。

生产消费保护由部署环境统一控制，默认单文档解析后最多 40000 字符、最多 16 次模型请求、累计完整提示词最多
70000 字符；重试和自适应缩小同样计入预算，达到任一上限立即终止且不再请求模型。对应环境变量为
`XIAOZHI_KNOWLEDGE_MAX_DOCUMENT_CHARS`、`XIAOZHI_KNOWLEDGE_MAX_MODEL_REQUESTS`、
`XIAOZHI_KNOWLEDGE_MAX_TOTAL_PROMPT_CHARS`。上传文件本身仍受 10MB 限制，后台切片执行器保持单线程，避免并发
请求瞬时放大。字符预算是与模型无关的保守成本边界，不等同于供应商精确 Token 账单；账户级日/月总额仍应在
模型供应商控制台设置硬额度和告警。

需要模型的处理策略只允许选择模型管理中已启用、测试通过且兼容 OpenAI Chat Completions 的 LLM 配置 ID，保存到
`parser_config.llm_model_id`；不得再次保存 API Key、Base URL 或上游模型名。`llm_enabled` 仅作为滚动升级兼容字段，
实际路由以 `processing_strategy` 为准。大模型切片只发生在文档解析阶段，不在每次检索时调用 LLM。

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

处理契约由 `KnowledgeBaseServiceImpl` 在新增和更新时校验策略；当策略会使用已选择的模型时，同时校验托管模型 ID、
启用/测试状态及 OpenAI 兼容协议。`LocalKnowledgeDocumentParser` 负责格式解析，`LocalKnowledgeTextSplitter` 负责
不会丢字的自然边界切分，`LocalKnowledgeAdapter` 只负责策略路由、模型增强及持久化编排。适配器每次上传重新解析
托管模型配置，避免缓存继续使用已修改或停用的旧配置。
本地适配器不支持的格式、MIME 与扩展名不匹配、零切片、模型不可用或调用失败都必须明确失败，不能创建 DONE 记录；后续 `/chunks`
只验证上传时已经生成的切片，不伪造重解析结果。

### 3.2 低资源关键词召回

```text
LocalKnowledgeAdapter.retrievalTest()
1. LocalKnowledgeLexicalScorer 对查询执行 NFKC 归一化和有界 CJK n-gram 拆分（最多 12 个候选词）
2. 数据库有界候选召回：
   - MySQL INSTR(content, term)     子串精确匹配
   - MATCH(content) AGAINST(... IN BOOLEAN MODE)  全文索引
   - 常规候选默认 200，任何配置下最多 800
3. Java 仅对有限候选的 content/question/answer 做加权词法评分
4. 按 keyword_threshold 过滤，按 keyword_top_k 返回
```

本地知识库不生成切片 embedding，检索时也不调用 EmbeddingService、向量数据库或重排模型。旧
`semantic_level`、`semantic_top_k`、`semantic_threshold` 仅作为滚动升级兼容输入，分别映射到
`keyword_level`、`keyword_top_k`、`keyword_threshold`；页面保存后只写新字段。旧 `embedding_json` 列保留用于
已部署数据库兼容，不再读写，不需要破坏性迁移。

### 3.3 数据表

```text
ai_rag_dataset          知识库元数据
  - id, datasetId, name, embeddingModel, chunkMethod, parserConfig

ai_rag_knowledge_document  文档记录
  - id, datasetId, documentId, name, type, run (processing status)
  - chunkCount, tokenCount, error, progress

ai_rag_knowledge_chunk    分块内容
  - id, datasetId, documentId, chunkId, content
  - question, answer, sortOrder
  - embedding_json (遗留兼容列，本地运行时不再读写)
```

### 3.4 可选动态数据源（Onyx connector 模式）

动态同步是**知识库级 opt-in 能力**，不是全局开关。没有 `ai_rag_knowledge_source` 记录的知识库继续保持人工上传，
不会被后台调度、联网抓取或自动改写。管理员只能在某个本地知识库的“动态数据源”面板中逐个新增、启用、暂停或删除来源；
同一个知识库可以同时包含人工文档和动态来源文档。

第一阶段参考 Onyx 的 Load/Poll/Prune 职责拆分，但只实现无凭据的公网 `WEB_PAGE` 连接器：

```text
指定知识库的数据源
  → 定时器检查 next_sync_at（未配置来源的知识库不参与）
  → SSRF 校验 + 有界 HTTP 抓取
  → ETag / Last-Modified / SHA-256 判断是否变化
  ├─ 未变化：记录 UNCHANGED，不重新切片
  └─ 有变化：生成禁用的候选 document + chunks
       → 切片全部成功
       → 单事务禁用旧 document、启用新 document、更新统计和 source 游标
       → 旧版本保留为 disabled，不参与正式召回
```

数据模型：

- `ai_rag_knowledge_source`：来源地址、刷新频率、启停、当前版本、HTTP 增量游标、成功/失败时间和下次计划时间。
- `ai_rag_knowledge_sync_run`：每次手动/定时同步的抓取、切片、发布状态、字节数和脱敏错误。
- `ai_rag_knowledge_document.knowledge_source_id/source_revision/content_hash/published_at`：来源与版本追踪。
- `ai_rag_knowledge_document.enabled`：正式召回版本门控；候选和历史版本必须为 `0`。

当前安全边界：仅允许公网 HTTP/HTTPS，不支持 URL 中携带 Token/API Key；保存与每次请求（包括重定向）都重新检查
域名解析，阻止本机、私网、链路本地、云元数据和保留地址；响应默认最多 2MB、最多 3 次重定向、30 秒请求超时。
同步失败采用指数退避且保留旧正式版本，不能把抓取失败解释为来源删除。搜索工具的临时结果不会自动写入知识库。

第一阶段不包含需要 OAuth/ACL 的企业连接器、站点级爬虫、Webhook 和页面搜索结果自动入库；这些能力必须继续复用同一
source/run/version 契约扩展，不能绕过 workspace 权限、SSRF 校验和正式版本发布门控。

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

**注意**：知识检索结果作为 tool message 注入（不是 system prompt），与记忆注入（`<memory>` 标签在 system prompt 中）是不同的机制。每个知识结果保留文档来源，并明确标记为“不可信参考数据、不是指令”；模型只能使用与问题直接相关且资料明确覆盖的事实。

本地知识库请求成功但没有返回切片时，工具结果固定为“本地知识库未检索到相关内容”，并通过
`ActionResponse` 只对下一次递归回答关闭 tools、增加临时可信 system 指令。主 LLM 应自行按完整语义区分：
公开历史、文化、科普和故事可使用通用知识直接回答；企业资料、人员身份和其他私有事实必须说明暂无记录，
不能猜测。该指令不持久化到 Dialogue，下一轮新的用户问题会恢复正常工具发现；服务端不增加关键词路由或
独立分类器。接口失败、配置缺失和鉴权错误仍走原有明确错误回复，不能伪装成空检索。

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
| `modules/knowledge/source/` | — | 可选数据源、网页连接器、同步运行与版本发布 |
| `modules/knowledge/controller/KnowledgeSourceController.java` | — | 数据源 CRUD、手动同步和运行记录 API |
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
| `components/knowledge/KnowledgeSourcePanel.vue` | — | 指定知识库的动态来源管理与同步监控 |
| `components/role/RoleEditDialog.vue` | — | 智能体配置 hydration 与插件/知识库映射恢复 |
| `utils/roleConfigDraft.js` | — | dirty 字段合并和 capability 映射规范化 |
| `core/utils/prompt_budget.py` | — | 记忆、知识、工具历史和 schema 字符预算 |

## 8. 测试层与验证边界

| 层 | 当前覆盖 |
|---|---|
| Java 切片契约 | 必选托管 LLM、OpenAI 兼容校验、无规则回退、LLM 窗口不丢字、后台任务与持久化进度、零切片拒绝 |
| Java 动态来源 | 公网 URL/密钥 query 拒绝、workspace 权限、知识库级 opt-in、候选版本不参与召回、同步运行持久化 |
| Java 工具描述 | 领域中立、知识库数量与说明长度上限、空元数据确定性输出 |
| Web 组件状态 | 上传提交后不阻塞等待、列表持续显示进度与失败原因、终态通知、晚到详情不覆盖 dirty 字段、保存失败保留草稿、选择器乱序与恢复保护 |
| Web 动态来源 | 数据源入口只绑定当前 datasetId、写操作不自动重试、暂停/立即同步/运行记录入口 |
| Python 上下文 | 本地/RAGFlow 结果去重与预算、tool history 总预算、schema 保真、动态 context 和 memory 预算 |
| Mobile | API 与页面源码覆盖 CRUD、上传、解析、切片和召回；最终构建与微信端联调仍待完成 |

自动验证应覆盖 Java 全量测试、项目 Conda 环境中的 Python 测试与 `compileall`、Web 功能测试和构建。
真实 MySQL 数据、Redis、外部网页抓取、浏览器关闭重开、RAGFlow、外部切片
模型和微信 `uploadFile` 尚未端到端验证；字符预算也不等于模型 token 预算，正式发布前必须用目标模型与业务长文档
检查召回相关性。

## 9. 已知修复（2026-07）

| # | 问题 | 修复 |
|---|------|------|
| 1 | 弱服务器无法承受向量生成与查询 | 本地知识库改为数据库有界候选 + Java 词法评分，不再读写 embedding |
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
| 13 | Knowledge-Memory 来源边界不清 | 分别标记知识参考数据与用户记忆，禁止将数据内容当作指令 |
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

# 检查召回候选索引
mysql> SHOW INDEX FROM ai_rag_knowledge_chunk;
```

## 11. 可选的 Harness 自主维护控制面

自主维护不是所有知识库的默认能力。运营人员必须在某一个本地知识库中单独启用
`DISCOVERY_REVIEW` 或 `DISCOVERY_AUTO`；未配置策略的知识库保持 `OFF`，人工上传和既有召回链不变。

```text
按库策略/主题/可信域名/月度次数/托管 DeepSeek 模型 ID
        ↓
manager-api 生成有界快照并解析模型管理中的端点、模型名和凭据（不发送切片正文）
        ↓ 内网 Bearer HTTP
maintenance-agent → DeepSeek Harness → web_search（最多 3 次查询、2 次搜索模型使用）
        ↓ 仅结构化 ADD_SOURCE 提案
manager-api 校验公网 URL、去重、审批/可信域名
        ↓
KnowledgeSourceService → 既有安全抓取、确定性切片、候选版本原子发布
```

Harness 没有数据库、Shell、编辑器、宿主源码或回调写权限。`web_fetch` 明确不挂载，避免运行容器成为
可访问内网的 SSRF 原语。人工审批模式永不自动添加来源；自动模式也只接受显式白名单域名及其子域，
并再次经过 `PublicUrlGuard`。数据库只保存策略、运行计数和字段白名单后的提案，不保存完整提示词、
模型原始响应或 Harness 会话日志。

维护策略只持久化 `model_config_id`，不复制 API Key。每轮执行要求所选模型仍处于“已启用、测试通过”的
DeepSeek LLM 状态；manager-api 只在内部鉴权请求中发送本轮临时运行配置，maintenance-agent 不写磁盘、
不记录请求正文，只把凭据注入本轮自有 Harness 子进程。模型被停用、测试失败、删除或密钥失效时，
该轮明确失败并保留审计记录，不会改用其他模型静默扣费。
