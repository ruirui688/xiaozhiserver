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

在智控台进入 `知识库`，新增或编辑知识库时选择 `RAG模型` 为 `本地知识库`，然后在同一个弹窗的 `切片模型配置` 中配置大模型切片。

推荐配置：

```text
大模型切片：开启
接口地址：https://dashscope.aliyuncs.com/compatible-mode/v1
API密钥：填写阿里云百炼 API Key
模型：qwen-plus
输入字符数：12000
输出Token数：4000
```

这些配置保存在当前知识库的 `parser_config` 字段中，只影响当前知识库。不要再为了某个知识库去后台模型配置里手动修改全局 `RAG_LocalKnowledge`。

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

上传 `txt/md` 等自由文本时，系统优先使用当前知识库的切片模型配置调用 OpenAI 兼容接口，让模型返回结构化 JSON 切片。

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

如果没有开启大模型切片、没有填写 API Key、模型接口失败或模型没有返回有效 JSON，系统会回退到普通段落切分，避免上传解析直接失败。

## 已有文档如何生效

切片逻辑只在文档解析时执行。已经上传并解析过的旧文档不会自动重切片。

修改切片模型配置后，需要：

1. 重启 `manager-api`。
2. 进入对应知识库。
3. 对已有文档重新点击 `解析`。
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
7. 查看 `manager-api` 日志中是否有 `本地知识库大模型切片失败`，常见原因是 API Key 错误、接口地址不可达、模型返回非 JSON、输出 token 不足。

## 相关代码

```text
main/manager-api/src/main/java/xiaozhi/modules/knowledge/rag/impl/LocalKnowledgeAdapter.java
main/manager-api/src/main/java/xiaozhi/modules/knowledge/dao/KnowledgeChunkDao.java
main/manager-api/src/main/resources/mapper/knowledge/KnowledgeChunkDao.xml
main/manager-api/src/main/java/xiaozhi/modules/knowledge/service/impl/KnowledgeBaseServiceImpl.java
main/manager-api/src/main/resources/db/changelog/202606291030.sql
main/manager-web/src/components/KnowledgeBaseDialog.vue
main/manager-web/src/views/KnowledgeBaseManagement.vue
main/xiaozhi-server/plugins_func/functions/search_from_local_knowledge.py
```
