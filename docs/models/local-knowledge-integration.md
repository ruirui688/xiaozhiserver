# 本地知识库与大模型切片指南

本文档说明智控台本地知识库的配置、切片、召回和排查方式。本文档只适用于 `RAG_LocalKnowledge` 本地知识库适配器；RAGFlow 部署和接入请看 `docs/models/ragflow-integration.md`。

## 功能边界

本地知识库把文档切片保存在 `manager-api` 的数据库中，不依赖 RAGFlow。服务端对话调用链路如下：

```text
用户问题
  -> xiaozhi-server function call: search_from_local_knowledge
  -> manager-api /config/knowledge/retrieval
  -> ai_rag_knowledge_chunk 本地切片召回
  -> 切片内容交回大模型组织回答
```

本地知识库适合放公司介绍、产品说明、FAQ、业务规则、固定资料等私有文本。它不是通用网页搜索，也不会自动补充文档外知识。

## 知识库界面配置

在智控台进入 `知识库`，新增或编辑知识库时选择 `RAG模型` 为 `本地知识库`，然后在同一个弹窗的 `切片模型配置` 中选择是否启用大模型切片。

推荐配置：

```text
大模型切片：开启
切片模型：选择“模型管理”中已经配置并测试通过的 LLM
输入字符数：12000
输出Token数：4000
```

这些配置保存在当前知识库的 `parser_config` 字段中，只影响当前知识库。切片模型通过 `llm_model_id` 复用“模型管理”中的 LLM 配置，API Key、接口地址和上游模型名仍由模型管理统一维护，不要在知识库里重复维护密钥。旧知识库中遗留的 `llm_base_url` / `llm_api_key` / `llm_model` 仍作为兼容字段保留，但新增或编辑时应改为选择模型管理中的 LLM。

## 知识库高级设置

知识库新增/编辑弹窗中的高级设置同样保存在当前知识库的 `parser_config` 字段中，具体位于 `retrieval_config` 对象下，只影响当前知识库。

默认配置：

```json
{
  "retrieval_config": {
    "mode": "normal",
    "search_type": "semantic",
    "semantic_level": 4,
    "semantic_top_k": 10,
    "faq_threshold": 1.0,
    "semantic_threshold": 0.05,
    "rerank_enabled": false
  }
}
```

字段说明：

- `mode`：知识库使用场景，`normal` 为常规模式，`exhibition` 为展会/现场讲解模式。
- `search_type`：检索方式，`semantic` 为语义检索，`hybrid` 为语义加关键词混合检索。
- `semantic_level`：中文自然句召回级别，级别越高会启用更细粒度的中文 n-gram。
- `semantic_top_k`：最终返回给模型组织答案的最大切片数。
- `faq_threshold`：FAQ 直接匹配阈值，完全或高置信命中时优先返回。
- `semantic_threshold`：语义候选进入结果的最低分数。
- `rerank_enabled`：是否为二次排序预留更大的候选集；当前仍使用本地精排，不调用外部重排模型。

## 切片逻辑

上传文档后，`LocalKnowledgeAdapter` 会按文件类型解析并写入 `ai_rag_knowledge_chunk`。大模型切片只发生在文档解析阶段，不发生在每次对话检索阶段。

本地知识库的解析是同步流程：上传接口返回成功时切片已经写入，文档状态应为
`DONE`、进度为 100%。页面上的“解析”操作只验证上传阶段的切片是否存在，不会从
数据库切片还原原文件后再次切分。RAGFlow 知识库的解析仍是异步流程，会先显示
`RUNNING`。

当前切片流程：

1. `xlsx/xls/csv/json/jsonl` 会先按表格行或问答 JSON 解析成候选切片；如果开启大模型切片，会再调用模型对每个候选切片做清洗和补全。
2. 已按 `HY-KB-` 结构预切好的 Markdown 会直接使用原有切片，不再调用大模型重新切。
3. 普通 `txt/md` 等自由文本会优先调用大模型做语义切片，让模型返回结构化 JSON 切片。
4. 如果没有启用大模型切片、模型不可用、模型返回非 JSON 或调用失败，系统会回退到普通问答解析或段落切分。

大模型切片调用的是模型管理中所选 LLM 的 OpenAI 兼容配置，后端从 `llm_model_id` 读取该模型的 `base_url`、`model_name` 和 `api_key`。因此，切片模型需要在模型管理中先配置完整、启用并测试通过。若所选模型被删除、禁用或测试未通过，系统会跳过大模型切片并回退规则切片。

模型切片返回格式：

```json
[
  {
    "question": "这个切片适合回答的问题，可为空",
    "answer": "明确问答答案，可为空",
    "content": "完整、可检索的知识片段"
  }
]
```

切片提示词要求模型：

- 按语义完整边界切分。
- 保留一个编号条目、一个主题小节、一个问答组或一个业务事实的完整性。
- 不把标题和正文、适用问题和对应正文切开。
- 保留原文事实、数值、名称和限制，不添加外部知识。
- 每个切片通常控制在 300 到 1200 个中文字符，短内容允许更短。

如果模型接口失败或模型没有返回有效 JSON，系统会回退到普通段落切分，避免上传解析直接失败。

## 已有文档如何生效

切片逻辑只在文档解析时执行。已经上传并解析过的旧文档不会自动重切片。

本地适配器不保存可用于无损重切片的原文件。修改切片模型配置或模型管理中的 LLM
配置后，需要：

1. 重启 `manager-api`。
2. 进入对应知识库。
3. 删除旧文档并重新上传原文件；仅点击 `解析` 不会重建切片。
4. 打开 `查看切片` 确认新切片质量。
5. 使用 `召回测试` 验证问题能否命中。

## 召回逻辑

本地知识库召回读取 `ai_rag_knowledge_chunk`，会同时匹配切片的 `content`、`question` 和 `answer`。

为了避免大知识库检索时打爆 `manager-api` CPU，召回链路不是全量加载所有切片后逐条 `contains`。当前实现分两步：

1. `KnowledgeChunkDao.selectRetrievalCandidates` 先按 `dataset_id` 和可选 `document_id` 过滤，并使用 `FULLTEXT(content, question, answer)` 或中文 term 兜底查询取有限候选。
2. `LocalKnowledgeAdapter` 只对有限候选执行本地打分、阈值过滤和排序，再按 `semantic_top_k` 或请求 `page_size/top_k` 截断结果。

中文自然句检索会生成受上限控制的 2/3/4 字片段参与匹配，避免整句没有空格时必须完整命中。例如：

```text
心情不好适合喝什么威士忌推荐
```

不会再只按整句去 `contains`。系统同时会降低“什么、适合、推荐、可以”等弱词的影响，避免无意义召回。

性能边界：

- 单次请求最多只加载有限候选，默认候选上限为 400，硬上限为 2000。
- 单次查询参与候选召回和精排的 term 数量有上限，避免中文长句 n-gram 爆炸。
- `ai_rag_knowledge_chunk` 需要通过 Liquibase 迁移维护 `content/question/answer` 组合全文索引，入口为 `db.changelog-master.yaml`。

## 排查方法

如果对话中出现“根据本地知识库查询结果，没有相关信息”，按以下顺序排查：

1. 确认智能体已绑定当前知识库。
2. 确认文档状态为已完成，且 `查看切片` 有有效内容。
3. 在知识库详情页使用 `召回测试`，分别测试完整问题和关键词。
4. 如果完整问题不命中、关键词命中，优先检查切片质量和问题改写。
5. 如果 `查看切片` 仍然在句子中间截断，说明旧文档没有重新解析，或大模型切片未成功启用。
6. 如果大知识库召回变慢，确认数据库已执行 `202606291030.sql`，且 `ft_local_chunk_retrieval` 全文索引存在。
7. 查看 `manager-api` 日志中是否有 `本地知识库大模型切片失败`，常见原因是模型管理中的 LLM 未启用、未测试通过、API Key 错误、接口地址不可达、模型返回非 JSON、输出 token 不足。

## 相关代码

```text
main/manager-api/src/main/java/xiaozhi/modules/knowledge/rag/impl/LocalKnowledgeAdapter.java
main/manager-api/src/main/java/xiaozhi/modules/knowledge/rag/impl/local/LocalKnowledgeTextSplitter.java
main/manager-api/src/main/java/xiaozhi/modules/knowledge/rag/impl/local/KnowledgeBaseParserConfigCodec.java
main/manager-api/src/main/java/xiaozhi/modules/knowledge/dao/KnowledgeChunkDao.java
main/manager-api/src/main/resources/mapper/knowledge/KnowledgeChunkDao.xml
main/manager-api/src/main/java/xiaozhi/modules/knowledge/service/impl/KnowledgeBaseServiceImpl.java
main/manager-api/src/main/resources/db/changelog/202606291030.sql
main/manager-web/src/components/KnowledgeBaseDialog.vue
main/manager-web/src/views/KnowledgeBaseManagement.vue
main/xiaozhi-server/plugins_func/functions/search_from_local_knowledge.py
```
