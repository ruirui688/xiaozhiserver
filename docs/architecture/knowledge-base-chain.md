---
status: supporting
owner: knowledge
last_verified: 2026-09-21
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

KnowledgeEntryController (/datasets/{datasetId}/documents/{documentId}/entries)
  GET    /entries                         搜索和分页查看当前条目及编辑能力
  POST   /entries                         手动新增文本/问答条目
  PUT    /entries/{entryId}               修改正文/问答/启停（expectedVersion）
```

### 2.2 资源范围和维护权限

`ai_rag_dataset.workspace_id` 表示知识库所有者，`scope` 表示可见范围：

- `WORKSPACE`：只允许所属工作空间成员读取，OWNER/ADMIN 写入。
- `OFFICIAL`：所有登录用户可读取和绑定到智能体；普通用户不能上传、修改、删除或配置数据源。
- 发布、撤回和写入官方知识库，只允许超级管理员本人或超级管理员所拥有工作空间的有效成员。
- Harness 自主维护策略可以绑定到官方知识库，也可以绑定到超级管理员团队拥有写权限的工作空间知识库；普通团队即使能够编辑自己的知识库，也不能开启该能力。策略表仍是自动维护是否启用的唯一状态源，不在知识库表重复保存开关。

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
修改处理策略不会自动重新切片；手动修正已有内容可以直接使用“管理内容”，不需要重新上传。
需要按新处理策略重新解析时应另行上传原文件，不支持替换原文件并自动合并人工修改；删除旧文档前应确认其中的人工修改不再需要。
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

### 3.2 文档原件、派生索引与低资源召回

```text
LocalKnowledgeAdapter.retrievalTest()
1. 上传成功时把原始文件保存到 knowledge.document-storage-root/{datasetId}/{documentId}/source.{ext}
2. 切片写入可兼容旧链路的 MySQL 表，同时写入本机 Lucene CJK/BM25 派生索引
3. Python 同时传入用户原问 raw_question 与模型改写 question
4. LocalKnowledgeQueryExpander 对数量/人数、普通价格问法、单品表达和“有什么酒/喝的/吃的”这类
   品类问法做确定性等价扩展（表格里记的是“酒水名称：… 品类：洋酒/葡萄酒/软饮”和“餐品名称/分类：主食”，
   原话里的“酒/吃的”不足以召回）；Lucene 对 content/question/answer/documentName 检索，
   并从结构化切片中提取商品名称、品名、菜品名称等主实体字段单独加权。
   LocalKnowledgeDocumentVocabulary 还会从每个文档自身的列头与品类取值自动推导“文档级词表”
   （酒水名称 → 酒水，品类：洋酒 → 洋酒；通用列头与文档名不参与），写进 document_vocabulary 字段，
   命中即代表整篇文档相关，用于把“问品类”路由到对应的表；词表由内容推导，不要求人工写文档介绍。
   同时丢弃两类不承担主题信息的查询词项，且只在其余有效词仍存在时才丢弃：
   ①疑问词/口语填充词（什么/有什/哪些/推荐）：中文按二字切词，这类词会命中问答库
   “标准问题：……是什么？”样板文本，靠多个常见词叠加压过真正命中“沙拉/海鲜”等罕见主题词的切片
   （库里 5 条沙拉却一条都排不上来）；词法打分本来就会跳过它们，Lucene 侧必须一致；
   ②“全库级词”：出现在多数文档名里的词（客户品牌名，按本次检索的数据集范围统计）没有区分度
   （酒水表正文里没有品牌名，否则永远排不上来）
5. 结构化重排先区分主实体命中与“推荐搭配”等辅助关系命中：查询“可乐价格”时商品名称命中优先，
   查询“搭配可乐的菜”时仍保留关系结果；随后再对“最低价/最高价”等明确极值执行确定性价格排序。
   主实体命中要求命中片段覆盖实体名称的一半以上（“慧苑”不算“慧苑VIP4人套餐”的实体命中），
   并且带主实体字段的候选必须少于 keyword_top_k；否则实体优先只会用同前缀表格行占满召回窗口，
   把真正回答问题的切片挤出结果
6. 完整包含用户问题的切片（词法满分）优先于主实体/价格结构化排序，精确答案不会被表格行挤掉；
   最后按 LocalKnowledgeLexicalScorer 精排、keyword_threshold 过滤和 keyword_top_k 返回；索引 schema
   变化会触发从 MySQL DONE 切片惰性重建，不要求重新上传原文件
```

原始文档保留上传时的原件，不随人工编辑重写。首次导入后，MySQL 当前条目是人工修订的权威数据，不另存编辑历史快照，
Lucene 只是可从当前数据库内容重建的派生索引；不能重新解析原件覆盖人工编辑。删除文档或知识库同步清理其原件、
索引。历史文档即使没有保留原 Excel，也可直接编辑已有条目，不要求重新上传；缺失原件不能从切片反向恢复。

条目保存提交后增加文档 contentVersion 并标记 indexDirty。检索时比较本进程 Lucene 版本和数据库权威版本，
仅对版本不一致的文档惰性重建；清除 dirty 使用版本条件，不能吞掉并发编辑。停用条目不入索引，召回结果再次核对
数据库正文及启用状态。索引异常或查询期间内容变化时回退数据库有界词法召回，不返回旧索引正文冒充保存后结果。
保存响应为索引待更新，不声称索引已经发布成功；可通过召回测试触发重建并验证。

本地知识库不生成切片 embedding，检索时也不调用 EmbeddingService、向量数据库或外部重排模型。旧
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

uploadfile/knowledge-documents  原始文档事实目录（持久化卷）
uploadfile/knowledge-index      Lucene 派生索引目录（可重建）
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
  → dataset_ids + raw_question + question
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

独立 `intent_llm` 模式下，本地知识库和 RAGFlow 检索完成后同样进入主模型的流式回答链路，
不再由意图模型收齐完整答案后调用 TTS。原用户问题只记录一次，检索结果以 tool message 注入；
本次回答关闭工具调用并保留空检索的临时可信提示，生成的文本分片持续进入现有 TTS 队列。
检索期间发生打断或回复 generation 失效时，旧结果不得启动回答或写入知识上下文。
该入口复用 `ConnectionHandler.chat` 的协议过滤、计时和 FIRST/LAST 生命周期，不新增独立播报管线。
后续意图切回 `continue_chat` 时必须保留配对的工具调用和结果，不得只删除 tool 消息；
历史长度和知识内容预算继续由 Dialogue 统一处理，避免连续普通对话因孤立 tool_calls 被模型拒绝。

本地知识库请求成功但没有返回切片时，工具结果固定为“本地知识库未检索到相关内容”，并通过
`ActionResponse` 只对下一次递归回答关闭 tools、增加临时可信 system 指令。主 LLM 应自行按完整语义区分：
公开历史、文化、科普和故事可使用通用知识直接回答；企业资料、人员身份和其他私有事实必须说明暂无记录，
不能猜测。该指令不持久化到 Dialogue，下一轮新的用户问题会恢复正常工具发现；服务端不增加关键词路由或
独立分类器。接口失败、配置缺失和鉴权错误仍走原有明确错误回复，不能伪装成空检索。

即使空检索后的递归回答临时关闭 tools，LLM 流式输出仍经过工具协议守卫。原始或 HTML 转义的 DSML
`tool_calls/invoke` 标记会在送入字幕和 TTS 前被拦截并转成稳定错误回复，不能作为普通文本显示给用户。

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
| KnowledgeContentWorkspace | `components/knowledge/content/KnowledgeContentWorkspace.vue` | 独立内容管理页的列表与编辑状态编排 |
| KnowledgeBaseSelectorDialog | `components/KnowledgeBaseSelectorDialog.vue` | 智能体选择 KB |
| manager-mobile knowledge pages | `manager-mobile/src/pages/knowledge/` | 小程序知识库、文档、切片与召回测试 |

### 6.2 内容编辑边界

“管理内容”进入独立路由页，不再用大弹窗承载全部内容。桌面采用左侧搜索和条目列表、右侧编辑详情的分栏布局，
列表和编辑器拆成独立组件，窄屏保持可访问的纵向布局。离开路由、关闭页面或切换条目前保护未保存草稿；
保存过程中阻止离开，取消丢弃确认则保留原草稿，不能无提示覆盖编辑。

页面支持普通文本以及问题/答案成对编辑、手动新增、停用和恢复召回。前后端均不提供修改历史、版本对比或历史恢复功能，保存只更新当前条目，不存储旧正文副本。所有写入校验知识库写权限、
文档和条目归属，使用文档锁与 expectedVersion 防止并发覆盖；editVersion 仅为并发计数，contentVersion 仅用于索引一致性，不代表保留历史内容。
保存失败或冲突不能静默丢失编辑草稿。

后续迁移 `knowledge-content-remove-history.sql` 仅删除知识条目历史表 `ai_rag_knowledge_entry_revision`，不修改当前条目、原件、聊天记录或长期记忆审计。
该迁移会永久清除该表内的历史内容，部署前必须备份并停止旧版写入；不能混跑仍依赖历史表的旧 API。
不修改已发布的建表迁移。清理后不能直接回滚到依赖该表的版本，应使用保留本次移除逻辑的回滚制品；如确需恢复旧功能，须另行评审兼容迁移及历史备份恢复，不能整库覆盖新数据。

仅完成处理的本地人工文档可编辑。无写权限的官方知识库、动态来源文档和 RAGFlow 在此页面保持只读，并展示原因；
动态内容应修改原始来源后同步，当前不提供人工修改合并或冲突解决。原文件替换合并编辑也不在本期支持范围。
旧 Excel 切片若没有可靠的结构化字段映射，继续以完整正文编辑；不能按冒号自动拆分成价格、库存等表单字段，
避免破坏原文和多行内容。问答字段仅依据后端明确提供的 question/answer 使用成对编辑。
知识编辑由独立 KnowledgeEntryService 和内容工作区组件负责，文档上传主页面仅负责导航，不增加上传适配器的编辑职责。
Harness 按钮、原生 Console、策略/审批/审计面板及关联前端请求已移除，普通动态数据源、召回测试不受影响。

### 6.3 上传限制

- 前端: 10MB 硬编码限制 → 后端同步 enforce `knowledge.max-upload-size` 配置 (默认 10MB)
- 支持格式: .doc, .docx, .pdf, .txt, .md, .mdx, .csv, .xls, .xlsx, .ppt, .pptx, .json, .jsonl, .qa
- 批量上传: 逐个文件 Promise.all

### 6.4 智能体知识库绑定

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
| `components/knowledge/content/KnowledgeContentWorkspace.vue` | — | 独立内容管理工作区及拆分面板 |
| `components/knowledge/KnowledgeSourcePanel.vue` | — | 指定知识库的动态来源管理与同步监控 |
| `components/role/RoleEditDialog.vue` | — | 智能体配置 hydration 与插件/知识库映射恢复 |
| `utils/roleConfigDraft.js` | — | dirty 字段合并和 capability 映射规范化 |
| `core/utils/prompt_budget.py` | — | 记忆、知识、工具历史和 schema 字符预算 |

## 8. 测试层与验证边界

| 层 | 当前覆盖 |
|---|---|
| Java 切片与召回契约 | 必选托管 LLM、OpenAI 兼容校验、无规则回退、LLM 窗口不丢字、后台任务与持久化进度、零切片拒绝；结构化主实体/价格/搭配关系排序回归；品牌前缀召回回归（含真实语料规模） |
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

## 11. 保留的 Harness 后台维护边界

manager-web 已移除全部 Harness 入口、原生 Console iframe、策略设置、提案审批、运行审计组件及其请求模块。
这次变更只移除产品界面，不删除 maintenance-agent、后台接口、已有策略、提案、审计和原生会话数据，
也不改变部署拓扑。**已启用的策略不会因界面移除自动停止**；若要停用后台服务或策略，应另行授权并明确目标。
待审批提案不会因界面消失自动批准，已启用自动模式的后台流程仍按既有策略运行。

后台知识维护仍按 dataset opt-in，未配置策略的知识库为 OFF；DISCOVERY_REVIEW 与 DISCOVERY_AUTO 保留原语义。
Manager API 继续作为权限、月度运行上限、审批和最终写入的权威；maintenance-agent 不获得数据库、Shell 或宿主文件权限。
凭据仅来自启用且测试通过的托管 DeepSeek 模型，并限于单次运行内存，不复制保存 API Key。

ADD_SOURCE 提案必须经过公网 URL/SSRF、可信域名和审批规则。批准后保持 APPLYING，事务提交后调用
KnowledgeSourceService；只有文档抓取、切片和原子发布成功才回写 APPLIED，失败则 FAILED 并保留旧版本。
REMOVE_SOURCE 必须精确引用 sourceId 并人工批准，即使可信域名自动模式也不能自动删除来源。
custom_prompt 只是质量指导，不得取消安全、审批或配额规则。后台审计仅保存脱敏里程碑，不存放隐藏推理、
完整提示词、网页正文、凭据或容器 stdout。现有内部 Console 接口及网关未在本轮停用，但管理端不再提供访问入口。
