# 记忆系统与知识库治理保证

本文档记录本项目记忆系统、PowerMem 借鉴能力、知识库 LLM 辅助能力和后续开发保证。它用于防止记忆配置、知识库配置和模型配置继续混在一起，也用于约束后续 AI 或开发者修改相关功能时必须保留的工程边界。

## 目标

记忆系统和知识库不是同一个能力：

- 记忆系统面向用户偏好、长期事实、候选记忆、忘记规则、画像和工具经验。
- 知识库面向业务资料、产品说明、FAQ、文档切片和检索问答。
- LLM/SLM 是辅助能力，只能通过模型管理中的已配置模型引用进入记忆或知识库，不应在记忆或知识库页面重复保存 API Key、Base URL 或上游模型名。

后续优化必须保证：

- 用户明确选择记忆模式后，才展示该模式对应配置。
- `nomem` 不应自动弹出记忆配置项，也不应注入长期记忆。
- `managed_memory` 是本项目推荐的治理型长期记忆入口。
- `maintenance_llm` 必须是模型管理中的 LLM/SLM 模型 ID 引用。
- 知识库的大模型切片同样必须通过已配置模型列表选择 `llm_model_id`。
- 显式记住、显式忘记、候选确认、删除审计和运行时降级必须长期保留。

## 现有记忆模式边界

| 模式 | 定位 | LLM 辅助 | 存储与治理 |
|------|------|----------|------------|
| `nomem` | 关闭记忆 | 无 | 不保存、不查询、不注入 |
| `mem_report_only` | 只上报聊天记录 | 无 | 只用于聊天记录链路，不总结长期记忆 |
| `mem_local_short` | 本地短摘要 | 可通过原有总结链路 | 兼容 `summaryMemory`，不是可审计长期事实层 |
| `powermem` | PowerMem provider 接入 | PowerMem 自身使用 LLM 和向量检索 | 运行时 provider 私有存储，适合独立 PowerMem 部署 |
| `mem0ai` | 第三方记忆服务 | 由第三方服务决定 | 外部服务存储和检索 |
| `managed_memory` | 智控台治理记忆 | 主对话 LLM 主动调用工具，`maintenance_llm` 结构化维护 | manager-api 事实层、候选确认、审计、忘记名单和运行时查询 |

`managed_memory` 不替代 PowerMem provider。它吸收 PowerMem 的长期检索、用户画像、经验沉淀和异步维护方向，但把状态和权限收束到本项目已有的 `manager-api`、MySQL、Web 管理端和设备/智能体作用域里。

## PowerMem 借鉴点

PowerMem 的原生记忆系统优势包括：

- 使用 LLM 做记忆总结、抽取和用户画像。
- 使用向量检索提升语义召回能力。
- 支持用户维度隔离。
- 支持异步记忆维护，避免阻塞主对话。
- 支持遗忘或衰减思路，减少噪声长期堆积。

本项目吸收这些方向时必须符合已有工程边界：

- 不在 Python provider 里继续堆私有长期状态。
- 不要求用户在记忆配置页重复填写模型密钥。
- 实时对话只有主模型调用 `manage_memory` 后才允许维护 active fact；历史重建预览仍使用 `pending` 候选。
- 不绕过管理端确认、删除墓碑和 revision 审计。
- 不因为记忆查询、维护模型失败或超时阻塞语音主链路。
- 后续引入向量、全文或图谱时，应优先扩展 manager-api 检索层和事实层投影；不得恢复固定时间衰减或自动归档。

## LLM 辅助记忆保证

`maintenance_llm` 是长期记忆维护模型字段。它只保存模型管理里的模型配置 ID。

前端要求：

- 字段类型应使用 `model-select` 或等效模型引用控件。
- 下拉列表只展示已配置、已启用、测试通过的 `LLM` 或 `SLM`。
- 控件文案应说明“这里选择模型，不配置模型密钥”。
- 用户未选择治理型记忆模式时，不应主动展示治理记忆专属字段。

后端要求：

- `maintenance_llm` 不得保存 `api_key`、`base_url`、`model_name`。
- 模型调用前必须从模型管理配置读取真实连接参数。
- `managed_memory` 必须配置维护模型；实时写入由主对话 LLM 调用 `manage_memory` 发起，维护模型只做 ADD/UPDATE、真实 ID 选择和结构化治理。
- 是否记忆、记忆正文、分类、私有/共享可见性以及后续是否使用，全部由主对话 LLM 根据完整语义决定。
- 提示词和后端不得内置人物、关键词、条件任务、场景黑白名单、置信度阈值、固定 TTL、时间衰减或字符串相似度规则。
- 原始聊天事件只写证据层，不创建候选或事实；只有管理端历史重建预览继续使用 `pending` 候选。

运行时要求：

- `/config/memory/events` 和 `/config/memory/query` 继续使用 `server.secret`。
- 记忆查询必须有超时保护，失败时降级为空记忆。
- `nomem` 模式必须阻断长期记忆注入。
- 忘记意图可以写 tombstone 并注入主模型；后端不得通过字符串匹配自动过滤事实或阻断未来模型调用。
- `private` 事实只对当前可信 `speaker_id` 可见，`shared` 事实可供后续任意说话人的对话使用；该选择由主模型调用工具时给出。
- `managed_memory` 的“保存并测试”应按本地运行时配置处理：必须选择维护模型，并确认被引用的 LLM/SLM 已启用且测试通过。

## 知识库 LLM 辅助保证

知识库的 LLM 辅助重点是文档切片和可检索内容整理，不是让知识库页面再次变成模型配置页面。

前端要求：

- 知识库配置弹窗中应使用列表框选择模型管理中已配置好的 LLM。
- 列表框保存 `parser_config.llm_model_id`。
- 不应在知识库弹窗里新增 API Key、Base URL、模型名等密钥字段。
- 文案应明确“选择切片模型”，而不是“配置模型”。

后端要求：

- 大模型切片只发生在文档解析阶段，不应在每次对话召回阶段调用 LLM。
- 所选模型必须来自模型管理，且需要启用、可用、测试通过。
- 模型不可用、返回非 JSON、超时或输出为空时，必须回退到规则切片。
- 旧文档修改切片模型后必须重新解析才生效。
- 召回必须保持有限候选 SQL 查询和 Java 内存精排，禁止退回全量 `selectList` 后逐条字符串匹配。

运行时要求：

- 对话时由 `search_from_local_knowledge` 调用 manager-api 本地知识库召回。
- 召回结果交给主 LLM 组织回答。
- 本地知识库不得越权读取未绑定到当前智能体的知识库。

## UI 逻辑保证

记忆和知识库页面必须降低混乱度：

- 模型选择、记忆模式选择、知识库切片模型选择是三类不同动作。
- 记忆模式未选择或选择 `nomem` 时，不显示记忆维护模型。
- 选择 `managed_memory` 时，才显示 `maintenance_llm` 和治理记忆说明。
- 选择 `powermem` 时，展示 PowerMem provider 自身需要的 LLM、Embedding、vector_store 配置。
- 知识库页面只选择“切片模型”，不负责创建 LLM 配置。
- 如果没有可用 LLM/SLM，列表框应为空并提示先去模型管理配置和测试模型。

新增前端字段时，优先复用已有的模板字段、`input_type`、`model-select` 控件和模型管理 API，不要在业务弹窗中复制新的模型配置表单。

## 数据库与迁移保证

涉及记忆、知识库或模型模板字段的数据库变更必须通过 Liquibase：

```text
main/manager-api/src/main/resources/db/changelog/
main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
```

必须保证：

- 新增迁移立即登记到 `db.changelog-master.yaml`。
- 不修改已发布 changeSet。
- 不提交未登记迁移。
- 不删除仍被 Java、Vue、Python 使用的字段。
- 不提交草案迁移，例如仅按时间命名但没有主文件登记、或会破坏现有字段兼容性的 SQL。

当前 `summary_memory` 仍被短期记忆和角色配置兼容链路使用。删除该字段前，必须先完成 Java、Vue、Python、文档和迁移的完整替代方案，不能只提交 `DROP COLUMN`。

## 后续开发检查清单

修改记忆或知识库相关功能前，必须检查：

- [长期记忆维护架构](../architecture/long-term-memory-maintenance.md)
- [PowerMem 记忆组件集成指南](./powermem-integration.md)
- [本地知识库与大模型切片指南](./local-knowledge-integration.md)
- [模型配置目录与治理说明](./model-config-catalog.md)
- [本地定制记录](../local-customizations.md)

最小验证要求：

- 修改 `manager-api`：执行 `cd main/manager-api && mvn test`。
- 修改 `manager-web`：执行 `cd main/manager-web && npm run build`。
- 修改 `xiaozhi-server`：执行 `cd main/xiaozhi-server && conda run -n xiaozhi-esp32-server python -m compileall .`。
- 修改数据库：检查迁移文件和 `db.changelog-master.yaml` 是否一致。
- 修改文档：检查 `docs/README.md` 索引和相关相对链接。

不允许把外部模型真实调用、长期服务启动或生产数据库迁移作为默认验证。需要真实调用外部模型时，必须由用户明确确认测试环境和费用风险。

## 相关代码路径

```text
main/manager-web/src/components/ModelEditDialog.vue
main/manager-web/src/components/KnowledgeBaseDialog.vue
main/manager-web/src/views/KnowledgeBaseManagement.vue
main/manager-api/src/main/java/xiaozhi/modules/memory/
main/manager-api/src/main/java/xiaozhi/modules/knowledge/
main/manager-api/src/main/resources/db/changelog/
main/xiaozhi-server/core/providers/memory/
main/xiaozhi-server/plugins_func/functions/search_from_local_knowledge.py
```
