---
status: supporting
owner: memory
last_verified: 2026-07-17
source_paths:
  - main/xiaozhi-server/core/providers/memory
  - main/xiaozhi-server/plugins_func/functions/manage_memory.py
  - main/manager-api/src/main/java/xiaozhi/modules/memory
---

# 记忆系统完整链路

本文说明托管长期记忆的真实运行链路。核心原则是：普通聊天不会被后端自动抽取为记忆，记忆变更必须由主对话 LLM 主动调用 `manage_memory` 工具。

先读：[提示词注入工程](prompt-injection-engineering.md)、[长期记忆维护架构](long-term-memory-maintenance.md)。

## 1. 总体链路

```text
普通对话
  -> /config/memory/events
  -> ai_memory_source_event
  -> 只保存原始证据和审计数据，不触发记忆提取

主对话 LLM
  -> 根据完整对话语义自主决定是否调用 manage_memory
  -> operation=remember|forget
  -> visibility=private|shared
  -> /config/memory/remember 或 /config/memory/forget
  -> maintenance_llm 只做去重、更新、结构化和真实 ID 选择
  -> ai_memory_fact / ai_memory_forget_tombstone / ai_memory_revision

下一轮对话
  -> /config/memory/query
  -> 当前说话人的私有事实 + 共享事实 + 忘记意图
  -> 注入主对话 LLM
  -> 主对话 LLM 根据当前语义决定是否使用
```

系统没有姓名名单、业务关键词、条件任务类型、目标人物匹配、场景黑白名单或字符串相似度门控。

## 2. Python Memory Provider

| Provider | 查询方式 | 存储方式 |
|----------|----------|----------|
| `nomem` | 返回空 | No-op |
| `mem_report_only` | 返回空 | 只上报聊天记录 |
| `mem_local_short` | 本地短摘要 | YAML |
| `mem0ai` | mem0 SDK | 第三方服务 |
| `powermem` | PowerMem SDK | PowerMem 私有存储 |
| `managed_memory` | HTTP 查询 manager-api | 主模型工具调用后委托 manager-api |

`managed_memory` 的查询结果通过 `<memory>` 注入动态 system 上下文。查询失败或超时降级为空记忆，不阻塞语音回复。

## 3. 原始事件

`reportHandle.py` 把用户、助手和工具事件上报到 `/config/memory/events`。事件包含会话、设备、角色、正文和可信声纹信息。

`MemoryServiceImpl.recordEvent()` 只写 `ai_memory_source_event`：

- 不调用维护模型；
- 不更新会话抽取水位；
- 不创建候选或事实；
- 不按空闲时间处理对话。

旧库中的 `ai_memory_session_state` 表为已发布迁移遗留结构。为保持 Liquibase checksum 和历史库兼容，不修改旧迁移，但当前实时链路不再读取或写入该表。

## 4. 主模型记忆工具

工具定义位于 `plugins_func/functions/manage_memory.py`。

```text
manage_memory(
  operation: remember | forget,
  text: string,
  visibility: private | shared,
  category?: string,
  reason?: string
)
```

所有业务语义都由主对话 LLM 决定：

- 每轮主动判断用户实际说出或确认的信息是否形成、修正或撤销了值得跨会话使用的稳定事实；
- 是否需要修改记忆；
- 使用 `remember` 还是 `forget`；
- 记忆正文和分类；
- 使用 `private` 还是 `shared`；
- 以后在什么对话中使用已经注入的记忆。

`private` 表示事实绑定当前可信 `speaker_id`，只对该说话人可见。`shared` 表示事实不绑定人物，可在该智能体后续与任何说话人的对话中注入。跨人物需求不使用后端姓名匹配；主模型在调用工具时直接选择 `shared`，后续仍由主模型根据记忆正文和当前对话判断是否行动。

主动判断不等于后台自动抽取。主模型无需等待用户明确说“记住”，但只能依据用户实际说出或确认的内容；一次性请求、临时状态、助手推测、工具输出和设备上传的单轮背景不能写入长期记忆。

工具只在已选择 `managed_memory` 且运行时支持原生 function call 时注册。这是能力可用性检查，不分析用户语义，也不决定是否写入。

## 5. manager-api 维护

`/config/memory/remember` 收到主模型决定后，维护模型只能在以下动作中整理：

```text
ADD     新建事实
UPDATE  更新已有真实 memory_id
```

主模型的保存决定不能被维护提示词改成 `NONE`。维护模型负责正文规范化、分类、层级、重要性以及去重/更新选择。

`/config/memory/forget` 由维护模型根据完整语义选择 `DELETE`、`ARCHIVE` 或 `NONE`，并且只能引用当前可见事实列表中的真实 ID。忘记意图可保存在 tombstone 中并注入主模型，但后端不再用字符串包含关系自动删除、过滤或阻断未来写入。

后端只保留下列非业务边界：

- 智能体和工作区所有权；
- 可信 `speaker_id` 与私有事实隔离；
- `private` 请求必须存在可信说话人；
- UPDATE/DELETE/ARCHIVE/容量驱逐必须引用真实且可见的 ID；
- 单智能体 active 正文容量上限；
- 事务、幂等、状态和 revision 审计；
- 输入长度、JSON 格式和敏感信息日志保护。

后端不再执行：

- 聊天结束或空闲后的自动抽取；
- 固定重要性阈值和固定层级分类；
- working/short-term 的固定 TTL；
- 每日衰减或自动归档；
- 关键词、姓名、场景、字符串包含或相似度判断；
- 预设的条件任务和跨人物特殊可见性。

## 6. 查询与人物隔离

查询只按结构化归属过滤：

```text
已识别说话人 -> shared 事实 + speaker_id 相同的 private 事实
未识别说话人 -> shared 事实
```

事实和忘记意图按智能体共享，不按来源设备 MAC 隔离。同一智能体切换设备后仍能召回已有事实；事实中的 `scope_id` / `mac_address` 仅用于来源审计。不同智能体仍由 `agent_id` 严格隔离。

共享事实对所有说话人可见，但主模型是否采用、何时采用、对谁采取动作，仍由主模型结合当前对话语义决定。私有事实不会因为正文中出现另一个人的姓名而扩大可见范围。

忘记名单作为文本上下文提供给主模型，不再由 Java 使用字符串规则过滤事实。这样既保留模型可理解的遗忘意图，又避免后端预设语义。

## 7. 管理端操作

管理端仍保留查看、编辑、归档、删除、手工记住、手工忘记、历史重建和主动审查。这些是用户明确触发的维护操作，不属于实时对话自动抽取。

`confirmation_state=auto` 的界面显示名是“模型调用”，表示事实来自模型工具链路，不表示系统自动扫描聊天。

## 8. 关键文件

| 文件 | 职责 |
|------|------|
| `plugins_func/functions/manage_memory.py` | 主 LLM 记忆工具 |
| `core/providers/tools/server_plugins/plugin_executor.py` | 工具能力注册 |
| `config/manage_api_client.py` | 记忆 HTTP 客户端 |
| `core/handle/reportHandle.py` | 原始事件上报 |
| `core/utils/dialogue.py` | 记忆与当前说话人注入 |
| `MemoryConfigController.java` | server-to-server 记忆 API |
| `MemoryServiceImpl.java` | 事件、CRUD 和工具请求编排 |
| `MemoryMaintenanceServiceImpl.java` | 维护模型结构化治理 |
| `MemoryQueryServiceImpl.java` | 人物隔离查询与上下文构造 |
| `PowerMemPrompts.java` | 工具调用后的维护提示词 |
| `MemoryManagement.vue` | 管理端记忆页面 |

## 9. 验证重点

- 普通 `recordEvent()` 不调用 LLM、不创建事实；
- 仓库不存在会话自动处理端点和定时抽取；
- `manage_memory` 描述不含固定人物、业务场景或关键词；
- 主模型选择 `private` 时保留可信人物归属；
- 主模型选择 `shared` 时事实可被后续其他说话人查询；
- tombstone 不做字符串匹配过滤；
- 不存在固定 TTL、衰减任务和重要性阈值分类；
- 越权 ID、私有记忆串读和容量越界仍被拒绝。
