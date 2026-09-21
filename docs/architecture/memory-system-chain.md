---
status: canonical
owner: memory
last_verified: 2026-09-21
source_paths:
  - main/xiaozhi-server/core/providers/memory
  - main/xiaozhi-server/config/manage_api_client.py
  - main/manager-api/src/main/java/xiaozhi/modules/memory
  - main/manager-web/src/views/MemoryManagement.vue
---

# 记忆系统完整链路

本文说明托管长期记忆的真实运行链路。`managed_memory` 参考官方 Memory provider 的保存方式：设备会话结束后，把完整对话交给已配置的记忆 LLM/SLM，由该模型主动提取、合并、修正或撤销长期记忆。主对话模型不需要调用 `manage_memory` 工具，用户也不需要明确说“记住”。

## 1. 总体链路

```text
智能体配置
  -> 选择“大模型主动记忆”
  -> 选择已配置并测试通过的主动记忆 LLM/SLM
  -> 选择聊天记录只保存文字，或保存文字+音频

实时对话
  -> 用户/助手/工具事件继续写入 ai_memory_source_event，作为原始证据和审计记录
  -> 主对话正常使用 LLM、TTS 和其他工具，不暴露 manage_memory

设备会话结束
  -> managed_memory.save_memory()
  -> POST /config/memory/process-session
  -> 记忆模型读取完整会话 + 当前生效记忆
  -> 输出 ADD | UPDATE | ARCHIVE | DELETE | NONE
  -> manager-api 校验人物归属、真实 memory_id、容量和事务边界
  -> 写入 ai_memory_fact / ai_memory_revision / ai_memory_forget_tombstone

连接初始化 / 既有权威配置刷新
  -> manager-api 从事实层生成有界 managed_memory_snapshot
  -> Python 校验设备、角色、版本和有效期，保存连接级摘要

下一次对话（普通问题和唤醒使用同一路径）
  -> 本地读取 shared + 当前可信 speaker_id 的 private 摘要
  -> 不再逐轮 POST /config/memory/query
  -> 通过 <memory> 注入主对话 LLM
```

## 2. 与官方实现的对应关系

官方 `mem_local_short`、Mem0 和 PowerMem provider 都在 `save_memory()` 中接收完整会话，再由记忆模型或记忆服务自动保存。本项目的 `managed_memory` 保留相同触发时机，但把事实存储、人物隔离、审计和管理页面放在 manager-api/MySQL 中。

```text
官方 provider: ConnectionHandler._save_and_close -> provider.save_memory(dialogue)
本地 managed:  ConnectionHandler._save_and_close -> managed_memory.save_memory(dialogue)
                 -> manager-api process-session -> maintenance_llm -> fact/revision
```

这条链路不依赖 Intent 是否为 `function_call`，也不会向主聊天模型注册额外记忆工具。

读取对齐的是上游 `mem_local_short` 的“初始化加载、查询直接返回本地摘要”方式，不是把现有 MySQL 存储改成本地 YAML。对照上游提交 `788f5301fdd60cc3a8ef74025bfeece9b82b94ce`；第三方 Mem0 等远程 provider 不具备同样的本地读取性质，上游仓库也不代表 xiaozhi.me 云端内部实现。

## 3. 聊天记录与音频配置

`AgentEntity.chatHistoryConf` 的含义保持不变：

| 值 | 行为 |
| --- | --- |
| `0` | 不上报聊天记录；选择无记忆时使用 |
| `1` | 保存文字，不保存聊天音频 |
| `2` | 保存文字和聊天音频 |

智能体配置页在启用记忆后显示“上报文字 / 上报文字+语音”。用户的选择会持久化，切换不同记忆模型时不得强制改回 `2`；临时切换到无记忆再切回时恢复上次启用状态的选择。

主动记忆处理使用会话文本，不依赖音频文件。因此选择 `1` 仍可正常生成长期记忆，只是不保存聊天音频。

## 4. Python Memory Provider

| Provider | 查询方式 | 保存方式 |
| --- | --- | --- |
| `nomem` | 返回空 | No-op |
| `mem_report_only` | 返回空 | 只上报聊天记录 |
| `mem_local_short` | 本地短摘要 | 会话结束后 LLM 摘要 |
| `mem0ai` | mem0 SDK | 会话结束后 mem0 add |
| `powermem` | PowerMem SDK | 会话结束后 PowerMem add |
| `managed_memory` | 读取随权威配置下发的连接级有界摘要 | 会话结束后 POST 完整对话给 manager-api 主动整理 |

`managed_memory.save_memory()` 只提交非临时的 user/assistant/tool 消息，单条正文和消息数量均有上限。system prompt、临时 few-shot 和密钥不会进入请求。

## 5. manager-api 主动整理

`/config/memory/process-session` 位于 `/config/**`，由 `ServerSecretFilter` 校验服务端共享密钥。请求同时携带 `agentId` 和设备 MAC；服务端优先从可信设备绑定关系解析智能体，不能靠请求体把记忆写到其他智能体。

处理步骤：

1. 确认所选 Memory 配置的 `type=managed_memory`。
2. 读取 `maintenance_llm` 模型 ID，不复制 API Key、Base URL 或上游模型名。
3. 将完整会话、可信 `speaker_id` 和当前生效事实交给记忆模型。
4. 模型自主决定是否值得长期保存，以及 ADD/UPDATE/ARCHIVE/DELETE/NONE、正文、分类和层级。
5. 第二次审校只检查证据、人物归属、结构和真实 ID，不使用关键词或业务场景规则替代模型判断。
6. manager-api 在事务内校验容量、事实归属、状态和 ID 后写入，并记录 revision。
7. 用 `session_memory_processed + sessionId` 记录已完成会话，避免同一关闭流程重复整理。

合法的自动 ADD 必须有用户侧证据。助手回复和工具结果可以帮助理解上下文，但不能单独生成用户长期事实。

## 6. 人物隔离

```text
会话只有一个可信 speaker_id -> 自动事实绑定该 speaker_id，作为 private 事实
会话没有可信 speaker_id     -> 自动事实作为 shared 事实
多人或已识别/未识别混合会话  -> 模型必须在动作中给出可验证 speaker_id，否则跳过该动作
```

查询只按结构化归属过滤：

```text
已识别说话人 -> shared + speaker_id 相同的 private
未识别说话人 -> shared
```

正文中出现另一个人的姓名不会扩大 private 事实的可见范围。

## 7. 原始事件与管理端

`reportHandle.py` 仍把聊天和工具事件写入 `/config/memory/events`。这些事件用于证据、时间线和历史重建，不在单条事件到达时调用 LLM，避免每句话产生一次额外模型请求。

管理端保留：

- 查看、编辑、归档和删除事实；
- 手工记住与手工忘记；
- 候选预览；
- 从历史聊天异步重建；
- 主动审查和 revision 时间线。

`confirmation_state=auto` 在页面显示为“主动记忆”，表示事实来自会话结束后的记忆模型处理；`manual` 显示为“手动创建”。

## 8. 查询注入与降级

`managed_memory` 不再依赖当前问题做 HTTP 召回。manager-api 在设备权威配置响应中附加 `managed_memory_snapshot`：从当前智能体的有效 active 事实一次有界读取最多 200 条，按重要度、最近时间和稳定 ID 排序，按结构化人物归属分组；不调用向量、embedding 或额外 LLM。最多 16 位已绑定说话人，shared 最多 4000 字符、每位 private 最多 2000 字符，总计最多 16000 字符，保留完整事实而不截断单条语义。

契约包含 `schema_version=1`、`status`、`agent_id`、`device_id`、内容哈希 `revision`、`issued_at_ms`、`expires_at_ms`、`shared_context`、`speaker_contexts`。Python 只按当前可信 `current_speaker_id` 选取 private；未知人物只读 shared，不通过姓名猜归属。摘要不是系统指令，继续通过 `prompt_budget.py` 裁剪后注入 `<memory>`。连接主文件只保留薄调用，校验、租约和失效逻辑独立在 `core/session/managed_memory_snapshot.py`。

兼容性取舍：运行时不再按问题做词法排名；容量外低优先级事实可能不被注入。缺少可靠人物归属的旧画像和工具经验保留在数据库和管理链路，但不进入新摘要。忘记名单只用于服务端保守过滤，不向设备下发遗忘正文；复杂语义遗忘仍由原有写入维护负责。原 `/config/memory/query` 接口及词法策略保留给既有调用者，不再是 Python 主对话路径。

快照租约最多 30 秒，并受其中最早事实到期时间约束；客户端同时检查单调时钟租约和绝对有效期。缺失、错误、过期、设备/角色不匹配、刷新失败或连接关闭时返回空记忆，不回退 HTTP 查询或旧摘要。摘要字段不进入 provider 配置指纹，单独续租或内容变化不重建 ASR/LLM/TTS。

无 `listen.start` 的连续会话也能恢复：读取发现租约临近到期或已失效时，只安排后台配置续取，不等待结果；每连接单任务并保留失败退避。配置指纹相同才安装新摘要，若角色或模型变化则只标记待刷新，仍由原安全边界切换完整管线。续取期间保守清空旧摘要，该轮可能不含记忆；关闭时取消任务。后台续取仍有配置 API 成本，不是永久离线缓存。

记忆更新、删除、遗忘、自动整理及声纹绑定变化复用既有事务提交后的运行配置刷新通知。收到通知先使旧摘要失效，再在既有安全边界获取权威配置；并发旧响应不能覆盖新失效状态。通知不是可靠消息队列，丢失时依赖既有配置检查和有限租约，不能承诺撤销后零毫秒全连接生效。已提交给 LLM 的内容不能追溯撤回。

`nomem` 不加载、不注入；其他 provider 仅走各自实现，不再先查询托管事实层。首次配置加载和既有权限/配置检查仍有 HTTP 与数据库成本：本改动消除的是逐轮记忆专用请求，不代表整条对话链路无网络等待。

## 9. 关键文件

| 文件 | 职责 |
| --- | --- |
| `core/providers/memory/managed_memory/managed_memory.py` | 会话结束后提交完整对话、运行时本地读取 |
| `core/session/managed_memory_snapshot.py` | 连接级摘要校验、人物选择、租约和失效 |
| `ManagedMemorySnapshotService.java` | 权威配置中的有界事实摘要 |
| `MemoryRuntimeRefresh.java` | 记忆变更复用事务提交后的失效通知 |
| `config/manage_api_client.py` | `/config/memory/process-session` 和 query 客户端 |
| `core/handle/reportHandle.py` | 原始聊天/工具事件上报 |
| `MemoryConfigController.java` | server-to-server 记忆 API |
| `MemoryServiceImpl.java` | 可信智能体/设备作用域解析 |
| `MemoryMaintenanceServiceImpl.java` | 主动抽取、审校、动作应用和审计 |
| `MemorySpeakerConversationBuilder.java` | 对话正文和可信声纹人物构造 |
| `PowerMemPrompts.java` | 会话主动记忆与历史重建提示词 |
| `MemoryQueryServiceImpl.java` | private/shared 隔离、候选查询与上下文构建 |
| `MemoryLexicalRecallPolicy.java` | 无向量的有界词法召回与核心记忆兜底 |
| `RoleEditDialog.vue` | 记忆模式与文字/音频保存选择 |
| `MemoryManagement.vue` | 长期记忆管理页面 |

## 10. 验证重点

- `tools/list` 和主 LLM tools 中不存在 `manage_memory`；
- 启用 `managed_memory` 时，不要求 Intent 为 function_call；
- 会话结束后只提交非临时对话消息，并调用 `/config/memory/process-session`；
- 未配置主动记忆模型时明确失败或跳过，不伪造成功；
- 同一 session 不重复整理；
- 自动 ADD 有用户证据，可信 speaker_id 不串写；
- 文字模式和文字+音频模式都能生成记忆；
- 用户选择的 `chatHistoryConf=1|2` 不被模型切换覆盖；
- 摘要缺失、过期或刷新失败时本地降级为空，不回退 HTTP；
- 私有事实只给当前可信人物，跨设备/角色/连接不串线；
- 摘要刷新不重建 provider，旧刷新响应不能恢复已失效摘要；
- 记忆和声纹变更在事务提交后触发失效；
- SQL 候选最多 200，运行时不调用向量或额外召回模型；
- 新迁移已登记到 `db.changelog-master.yaml`，不修改已发布 changeSet。

## 11. 保留的 Harness 后台维护

Harness 记忆维护按智能体单独启用，模式只有 `OFF` 和 `REVIEW`。它读取最多 200 条、累计最多 4 万字符的
当前 active 事实，只能提出 `UPDATE`、`ARCHIVE`、`DELETE`，不能新增事实、不能联网，也不能直接写数据库。
每条提案必须引用快照中真实存在的 `memoryId`，所有变更仍需显式人工批准，不能因界面移除自动采纳。

批准后仍调用现有 `MemoryService`，继续执行智能体所有权检查、容量与状态检查、事务更新和 revision 审计。
manager-web 已删除 Harness 按钮、Console、策略设置、提案审批、审计面板及关联请求；普通记忆编辑、重建、重审、
记住和忘记保留。后台策略、服务、审计和原生会话数据不变，已启用策略不会自动停止；需要停用须另行明确授权。
后台维护会话不是产品长期记忆，也不参与实时对话召回。移除界面不等于关闭既有后台接口或改变原生会话存储。
策略的月度运行上限、单次提案数和串行队列属于可持久监控的控制面，避免用散落的“提示字符数”代替真实运行治理。

## 12. 配套升级与回滚边界

本次读取契约要求 manager-api 与 xiaozhi-server 配套升级；只升级 Python 而继续使用不下发摘要的旧 Java，会安全降级为空记忆，不自动恢复旧逐轮 HTTP 查询。无需修改数据库结构或迁移已有记忆，也不需要修改管理员所选 Memory 模式。`nomem` 保持关闭，启用 `managed_memory` 时仍使用原主动记忆模型配置。

未来发布须另行授权，并在同条件下测量首次配置与后续回复，不能把单元测试耗时作为线上提速数据。回滚时恢复配套的上一版 Java/Python 制品；数据库、事实和审计记录无需回滚。回滚前后均须验证唤醒去重、人物隔离、遗忘和会话结束后的保存链路。
