---
status: supporting
owner: memory
last_verified: 2026-07-23
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/memory
  - main/xiaozhi-server/plugins_func/functions/manage_memory.py
  - main/xiaozhi-server/core/providers/memory
  - main/manager-web/src/views/MemoryManagement.vue
---

# 长期记忆维护架构

本文记录 `managed_memory` 的工程边界。实时对话中的记忆变更只有一个入口：主对话 LLM 主动调用 `manage_memory`。manager-api 不扫描聊天、不猜测业务语义、不按固定规则自动写入或归档。

## 核心链路

```text
原始聊天事件
  -> /config/memory/events
  -> ai_memory_source_event
  -> 仅证据和审计

主对话 LLM 自主判断
  -> manage_memory(operation, text, visibility, category?, reason?)
  -> /config/memory/remember 或 /config/memory/forget
  -> maintenance_llm 结构化、去重、更新和真实 ID 选择
  -> fact / tombstone / revision

运行时查询
  -> private/shared 结构化隔离
  -> 事实和忘记意图注入主对话 LLM
  -> 主对话 LLM 自主决定是否使用
```

## 数据层

首期继续使用 manager-api 的 MySQL + Liquibase：

| 数据 | 用途 |
|------|------|
| `ai_memory_source_event` | 原始对话证据，不触发抽取 |
| `ai_memory_candidate` | 管理端预览和历史重建候选 |
| `ai_memory_fact` | 生效、归档或删除的文本事实 |
| `ai_memory_revision` | 所有维护动作的审计轨迹 |
| `ai_memory_profile` | 管理端画像数据 |
| `ai_memory_tool_behavior` | 工具行为统计 |
| `ai_memory_forget_tombstone` | 提供给主模型理解的忘记意图 |
| `ai_memory_session_state` | 历史迁移兼容表，当前链路不使用 |

已发布迁移不能回改。废弃的 session state 表暂不删除，后续如清理必须新增补偿迁移。

## 主模型职责

主对话 LLM 负责全部记忆业务语义：

- 每轮主动判断用户实际说出或确认的信息是否形成、修正或撤销了值得跨会话使用的稳定事实；
- 是否调用记忆工具；
- 记住还是忘记；
- 正文、分类和原因；
- 私有还是共享；
- 已注入记忆在当前轮是否相关；
- 是否根据记忆执行后续对话或工具动作。

工具定义不提供业务示例、人物名单、关键词列表、场景规则或固定任务类型。
它只提供通用决策边界：满足跨会话稳定事实时无需等待用户明确说“记住”即可主动调用；一次性请求、临时状态、助手推测、工具输出和设备背景不得写入。

### 可见性

`visibility=private`：绑定当前可信声纹 `speaker_id`。后端拒绝没有可信人物身份的 private 写入。

`visibility=shared`：不绑定人物，可注入该智能体与任意说话人的后续对话。需要跨人物延续的内容由主模型选择 shared；后端不解析正文中的姓名，也不建立“条件任务”特殊类型。

共享只扩大事实的读取范围，不代表后端自动执行。事实何时相关、对谁表达、是否需要调用其他工具，仍由主模型决定。

事实和忘记意图的运行时边界是智能体：同一智能体更换或新增设备后仍可读取已有事实。`scope_id` / `mac_address` 只保留来源设备审计，不作为事实召回和维护隔离条件；private 事实仍必须匹配同一可信 `speaker_id`，shared 事实才可供该智能体下其他说话人使用。

## 维护模型职责

`maintenance_llm` 是模型管理中的 LLM/SLM modelId 引用，不保存 API Key、Base URL 或模型名。

主模型调用 remember 后，维护模型必须保留“需要保存”这一决定，只负责：

- 在 `ADD` 和 `UPDATE` 之间选择；
- 选择已有真实 `memory_id`；
- 整理可读正文、分类、层级、重要性和敏感度；
- 避免重复事实。

主模型调用 forget 后，维护模型根据完整语义选择可见事实中的真实 ID，并输出 `DELETE`、`ARCHIVE` 或没有匹配目标时的 `NONE`。忘记意图可写 tombstone 供主模型后续理解，但服务端不使用 tombstone 做字符串匹配、自动过滤或自动阻断。

## 后端边界

允许保留的确定性校验只有：

- 工作区、智能体和设备所有权；
- server-to-server 认证；
- 当前可信声纹与私有事实隔离；
- 操作引用的 memory ID 必须真实、active、属于当前智能体且当前可见；
- active 正文容量上限及由模型给出的驱逐 ID 校验；
- 事务、幂等、状态、JSON、长度和 revision 审计；
- 查询超时和日志脱敏。

禁止加入：

- 固定人物、姓名匹配或目标人物字段；
- 条件任务、业务类别或场景黑白名单；
- 关键词触发、正则语义判断、字符串包含和相似度门控；
- 置信度/重要性阈值决定是否写入；
- 由后端按分数推导记忆层级；
- 固定 TTL、每日衰减和自动归档；
- 会话结束、空闲时间或定时任务触发的聊天抽取。

## 原始事件与历史重建

普通事件只保存证据。`/config/memory/process-session` 已移除，`recordEvent()` 不更新会话处理水位，manager-api 不再存在空闲会话整理调度。

管理端历史重建是用户明确点击触发的离线维护操作，和实时对话写入分开。它可以调用维护模型生成预览、合并或清空后重建，并继续受所有权、人物归属、真实 ID、容量和审计约束。

`clear_then_rebuild` 使用两阶段原子替换：先在数据库事务外读取历史、调用维护模型、审校并验证动作；该模式只接受 `ADD` / `NONE`。只有存在可应用的 `ADD` 时，才在一个短事务内锁定智能体、清理旧事实并写入全部新事实。任一动作写入失败会回滚整个替换事务，旧记忆不会处于“已清空但新记忆未写入”的状态。

### 异步重建任务

执行重建不再让浏览器持有最长 300 秒的同步 HTTP 请求。`POST /agent/{agentId}/memories/rebuild`
只在完成智能体所有权和 workspace 写权限检查后创建任务，页面通过最近任务或指定任务端点恢复和轮询状态：

```text
PENDING / QUEUED
  -> RUNNING / STARTING
  -> LOADING_SESSIONS
  -> EXTRACTING
  -> REVIEWING
  -> VALIDATING
  -> APPLYING
  -> FINALIZING
  -> SUCCEEDED | FAILED | TIMED_OUT
```

任务状态、最近任务指针和每智能体活动锁保存在 Redis。活动锁保证同一智能体重复点击返回同一个未结束任务；
状态对象保存 owner、workspace、进度、截止时间和最终结果。默认执行上限为 10 分钟，调度器到期后取消 worker
并把状态固定为 `TIMED_OUT`；维护流程在读取会话、模型修复、复核和应用前设置检查点，已经进入终态的任务不能被迟到成功结果覆盖。
锁释放使用“值仍等于当前 taskId 才删除”的 Redis 脚本，避免旧任务释放新任务的锁。

任务读取仍先检查当前用户对 agent 的所有权，不能使用另一个 agentId 读取已知 taskId。任务状态依赖 Redis；
Redis 无法持久化初始状态时不启动重建。线程中断对已经进入的第三方模型或数据库阻塞调用只能尽力取消，
因此超时后的底层调用仍需通过目标环境联调观察，但终态和原子替换边界可以防止迟到结果改写任务状态。

## 查询

事实查询按结构化人物归属执行：

```text
current speaker 已识别 -> 当前 speaker_id 的 private + shared
current speaker 未识别 -> shared
```

正文里出现谁的名字不会改变可见性。主模型选择 shared 才能让内容进入其他说话人的上下文。

查询同时返回忘记意图。Java 不再把事实正文和 tombstone 做字符串包含比较；是否忽略事实由主模型理解完整上下文后决定。

## 管理端

管理端支持查看、编辑、归档、删除、手工记住、手工忘记、候选确认、历史重建和主动审查。所有操作写 revision。

重建预览和重审仍是同步维护模型请求；正式历史重建是 Redis 持久化的异步任务。manager-web 创建任务后按
2 秒起步轮询，失败时逐步退避且最长 10 秒；刷新或重新进入页面会查询最近任务并恢复进度，启动响应中断时也会先查询
服务端任务再决定是否报错。服务端任务处于 `PENDING` / `RUNNING` 时，页面互斥锁定重建、重审、清空、手工记住和忘记，
并分别展示完成、失败和超时。同步维护请求仍必须区分“无会话”“无动作”“无事实变更”和网络结果不确定，不能把所有返回都显示成成功。

界面中的 `confirmation_state=auto` 显示为“模型调用”，不能描述为“自动抽取”。空列表也不能承诺开始聊天后系统会自动生成记忆。

## 配置要求

- `managed_memory` 必须引用可用的 `maintenance_llm`；
- 运行时意图模式必须支持原生 function call，主模型才能调用真实工具；
- `nomem` 不查询、不注入，也不注册托管记忆工具；
- 记忆查询有超时保护，失败降级为空上下文；
- `/config/memory/**` 使用 server secret，并继续校验 agent/device 归属。

这些是能力和安全边界，不分析对话内容，不构成业务语义门控。

## 验证

```bash
cd main/manager-api && mvn test

cd main/xiaozhi-server
PYTHONNOUSERSITE=1 PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 \
  conda run -n xiaozhi-esp32-server pytest \
  tests/test_model_managed_memory_tool.py \
  tests/test_unified_tool_handler_gating.py

cd main/manager-web
npm run test:unit
npm run build
```

新增可靠性测试分层：

- `MemoryRebuildTaskServiceImplTest`：重复启动复用、成功释放锁、失败可观测与脱敏、超时不可被迟到完成覆盖、调度失败和跨智能体读取拒绝；
- `MemoryManagementControllerTest`：所有权、workspace 写权限和 `sys:role:normal` 权限声明；
- `memoryRebuildTaskState.test.mjs`：页面重建后的任务恢复、终态展示、超时展示和轮询退避。

截至 2026-07-23，主集成复跑 Java 全量 `mvn test` 188 通过、5 跳过，Web 功能测试 17 通过且
`npm run build` 通过。临时真实 Redis 已验证 Lua 锁归属、条件释放、CAS 和 TTL；这些仍不是 Spring 序列化、
多进程重启、MySQL 或浏览器端到端验收，仍需在真实服务组合和外部维护
模型上验证任务恢复、锁过期、事务回滚与超时行为。

检查残留：

```bash
rg -n "自动抽取|空闲整理|process-session|conditional_task|target_person_name|MemoryAutoWriteGate" \
  docs main/manager-api main/manager-web main/xiaozhi-server
```
