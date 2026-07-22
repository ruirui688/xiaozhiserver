---
status: report
owner: architecture
last_verified: 2026-07-23
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/memory
  - main/manager-api/src/main/java/xiaozhi/modules/knowledge
  - main/manager-api/src/main/java/xiaozhi/modules/agent/service/impl
  - main/manager-web/src/views/MemoryManagement.vue
  - main/manager-web/src/components/role/RoleEditDialog.vue
  - main/manager-mobile/src/pages/knowledge
  - main/manager-mobile/src/pages/memory
  - main/xiaozhi-server/core/utils/prompt_budget.py
---

# 2026-07 功能可靠性维护报告

本文记录长期记忆、知识库切片、智能体知识绑定和微信小程序管理链路的故障根因、修复边界与验证限制。
当前架构事实仍以[记忆系统完整链路](../architecture/memory-system-chain.md)、
[长期记忆维护架构](../architecture/long-term-memory-maintenance.md)和
[知识库完整链路](../architecture/knowledge-base-chain.md)为准。

## 1. 状态结论

本轮必须区分两个版本层次：

| 层次 | 状态 | 内容 |
| --- | --- | --- |
| 本地已提交 | `HEAD=518e08e1` | 基础记忆保存保护、本地切片契约、Web multipart 修复、微信登录/知识库/记忆页面 |
| 本轮维护变更 | 自动验证已完成，待目标环境验收 | 异步记忆重建、智能体知识绑定恢复、小程序切片恢复、领域中立工具描述、动态 LLM 上下文预算及新增测试 |

这些能力尚不能称为生产已发布。真实 Redis、MySQL、浏览器、微信、RAGFlow 和外部模型均未在本轮完成端到端验收。

## 2. 故障根因

### 2.1 记忆维护失效

管理端正式重建原先把模型调用、动作校验和数据库替换全部绑定在一个最长约 300 秒的 HTTP 请求上。
页面刷新、代理超时或网络中断后，浏览器只知道请求丢失，无法判断后台是否仍在执行，也没有可恢复的 taskId、
服务端进度或跨页面互斥。用户重试可能启动重复维护；仅靠页面 `loading` 不能约束另一标签页或服务实例。

人工“记住”原先还会再次交给维护模型。维护模型返回 `NONE`、非法 JSON 或网络失败时，用户明确保存的事实可能
没有落库。`518e08e1` 已把管理端人工记住改为直接创建共享事实并标记 `manual`，维护模型无效输出也不能撤销
主模型已经作出的保存决定。

### 2.2 知识库切片失效

切片链路存在多层契约偏差：

- `parserConfig` 在数据库 JSON 字符串、RAG DTO 和远端响应之间转换时，扩展字段可能丢失或被默认值覆盖；
- LocalKnowledge 在上传请求内已经同步解析入库，service 却仍可能把文档写成 `RUNNING`；
- 规则切片和 LLM 输入窗口分别维护字符边界，长中文段落可能被硬切或丢字；
- 不支持的二进制格式或零切片结果没有稳定失败语义，页面会长期显示处理中；
- Web 手工写入 `multipart/form-data` 时缺少浏览器生成的 boundary，服务端无法稳定解析上传体。

### 2.3 智能体知识绑定丢失

智能体详情中的 `functions` 同时保存普通插件和知识库映射，`paramInfo` 又可能以 JSON 字符串返回。
旧 `RoleEditDialog` 深度监听 `initialForm`，任一子字段编辑都会触发 `form-change`；父页面只同步 agentName 后改变 prop，
子组件立即用父页面仍然陈旧的完整快照重建表单，模型、提示词和能力选择因此消失。

同时，插件与知识库元数据异步加载完成前没有稳定调用映射恢复；知识库表格的 `clearSelection()` 会触发空
selection-change，可能把正在恢复的选择和 `queryPolicy` 清掉。API 自动重试与缺少失败回调又让页面无法区分
“仍在加载”和“已经失败”，保存按钮可能提交不完整映射。

第二轮自检进一步发现，知识库创建、上传、解析和删除在网络响应不确定时会被 Web API 自动重放，可能产生重复资源；
召回端点只鉴权路径知识库，却接受请求体覆盖 `dataset_ids`，可以借有权限路径查询另一个知识库。文档列表的每次轮询
还会触发一次全量远端对账，和活跃文档逐条同步重复，放大 RAGFlow 延迟与负载。

### 2.4 微信小程序链路不完整

manager-api 已有微信登录/绑定、知识库和记忆接口，但本地移动端此前缺少对应入口和 API 层；token 在字符串与对象
两种存储形态下读取不一致，普通请求与 `uni.uploadFile` 也没有统一透传 workspace。结果是后端能力存在，微信端却
无法完成知识库创建、上传、解析、切片查看、召回测试和长期记忆维护闭环。

### 2.5 动态上下文无统一预算

记忆、知识召回、外部 context provider、历史 tool result 和工具描述分别进入 LLM 上下文。此前各来源只做局部
数量限制，缺少总字符预算、重复内容压缩和 schema 保真规则；大量知识库、长 queryPolicy 或多轮工具结果可能挤占
静态角色与安全提示，表现为工具调用不稳定、回答遗忘约束或上下文超限。

## 3. 当前修复

### 3.1 异步记忆任务生命周期

```text
POST /agent/{agentId}/memories/rebuild
  -> 校验 agent 所有权和 workspace 写权限
  -> Redis 每智能体活动锁
  -> 保存 PENDING 任务与 latest 指针
  -> executor 执行 + scheduler 设置 10 分钟截止时间
  -> 分阶段 checkpoint
  -> SUCCEEDED | FAILED | TIMED_OUT
  -> 保存结果并按 taskId 条件释放活动锁
```

状态阶段依次为 `QUEUED`、`STARTING`、`LOADING_SESSIONS`、`EXTRACTING`、`REVIEWING`、
`VALIDATING`、`APPLYING`、`FINALIZING`、`COMPLETED`。同一智能体重复启动返回现有活动任务；状态初次写入 Redis
失败时不启动 worker。调度超时会取消 Future，checkpoint 同时检查线程中断和 deadline；终态写入后迟到成功不会覆盖。
失败信息在写入任务前脱敏并截断。

维护动作的 INFO 日志只保留 agentId、memoryId 和动作，不再输出完整事实正文；revision 仍承担受权限保护的审计职责，
不能为了排障把 canonicalText、Authorization 或模型密钥重新写回普通日志。

Web 创建任务后按 2 秒起步轮询，失败逐步退避到最多 10 秒。刷新、关闭后重新进入或启动响应中断时，通过最近任务
端点恢复状态。活动任务会锁定其他维护操作，终态明确显示结果、失败或超时。预览重建和主动重审仍是同步请求。

### 3.2 切片和绑定契约

- `KnowledgeBaseParserConfigCodec` 在 manager-api 边界显式解码 parserConfig，数据库优先保存用户原始完整 JSON；
- `LocalKnowledgeTextSplitter` 统一段落、中文句末、LLM 窗口和硬上限，测试保证分段不丢字；
- adapter 声明本地解析同步完成，上传成功写 `DONE/100%`；后续 parse 只验证切片存在，缺失时要求重新上传；
- 本地格式白名单与零切片明确失败，避免永久 `RUNNING`；
- 浏览器上传交给 FormData 自动生成 multipart boundary；小程序使用 `uni.uploadFile`；
- RoleEditDialog 使用显式 hydration、dirty path 合并和请求序号；元数据完成前禁止保存；
- 字符串/对象 `paramInfo` 统一规范化，再拆分普通插件和知识库映射；关闭重进恢复知识库 ID 和 queryPolicy；
- 选择器程序化恢复期间忽略空 selection-change，旧分页响应不能覆盖新结果；
- 保存增加重复提交锁和失败回调，失败不丢草稿。
- 知识库写操作不再自动重放；只读查询保留有限重试，网络结果不确定时由用户确认后重试；
- 召回强制使用已鉴权的路径 `datasetId`，解析和删除在调用 adapter 前验证全部 documentId 属于该知识库；
- 全量远端文档对账按知识库设置 60 秒跨实例冷却，Redis 不可用时跳过高成本全量对账并继续本地查询；
- 上传已成功但影子记录落库失败时补偿删除远端结果，避免无主文档。

### 3.3 小程序闭环

`518e08e1` 已增加微信一键登录/绑定、统一 token 读取、workspace 请求头、知识库 CRUD、文档上传/解析、切片查看、
召回测试和长期记忆管理页面。普通 HTTP 与上传请求都携带 Bearer token；存在当前 workspace 时同时携带
`X-Workspace-Id`。该提交只说明源码存在，不代表微信开发者工具和真机行为已经验收。

### 3.4 LLM 上下文预算

本轮新增的 `prompt_budget.py` 采用分域字符预算：memory 4000、外部 context 2000、知识结果 4000、历史 tool result
合计 8000 且单个结果最多 4000；知识结果最多 5 项且单项最多 1600。工具描述总预算 6000、单工具描述合计最多
1000，只删除或截断 description，不修改名称、类型、enum、required 等 schema 合同。静态 system 角色和安全规则不参与裁剪。

manager-api 的知识工具描述改为领域中立规则，限制知识库名称、详情数量和 640 字符总长度，不再包含酒类、酒杯等
固定业务假设。

## 4. 与官方上游的差异

本报告使用本地 `upstream/main=b1e39da9` 快照。只读检查确认：官方有 `mem_local_short`、PowerMem 等 provider，
但没有本地 Java `modules/memory` 事实管理域；官方知识库工厂注册 RAGFlow，没有 LocalKnowledge adapter、MySQL chunk
事实层或本地知识/记忆移动管理页面。

本地差异有明确必要性：私有数据与受限网络环境需要不依赖外部 RAGFlow 的切片检索；持续运营需要可纠正、可忘记、
可重建、可审计的记忆事实层；微信场景需要移动运维入口；异步任务与上下文预算解决的是本地新增闭环产生的生命周期
和容量问题。它们不能通过覆盖官方同名页面或 provider 自动获得。

同步官方时仍应选择性吸收 PowerMem、RAGFlow、移动端质量和协议修复，但必须保护本地数据模型、原子替换、任务锁、
workspace、绑定 hydration 和 LocalKnowledge 状态语义。具体比较见[当前仓库与官方上游能力审计](./current-vs-upstream-capability-audit.md)。

## 5. 测试层

| 层 | 新增或已有覆盖 |
| --- | --- |
| Java 切片 | parserConfig 保存、格式白名单、中文句界、LLM 窗口不丢字、本地同步状态、零切片拒绝 |
| Java 记忆任务 | 重复启动、成功释放锁、失败脱敏、超时终态、迟到结果、调度失败、跨 agent 读取 |
| Java Controller | agent 所有权、workspace 写权限、Shiro 权限声明 |
| Java 工具描述 | 领域中立、长度上限、空元数据 |
| Web 状态/组件 | 任务恢复与退避、dirty hydration、关闭重进恢复绑定、保存失败草稿、重复保存、选择器乱序 |
| Python | memory/context/knowledge/tool history/schema 各预算及静态规则保留 |
| Mobile | 切片状态机、页面离开停止轮询、替换决策和持久任务恢复；类型检查、H5 与微信小程序构建 |

截至 2026-07-23，主集成复跑 Java 全量 `mvn test` 共 198 项，0 失败、0 错误、5 跳过。以 `PYTHONNOUSERSITE=1`、
`PYTEST_DISABLE_PLUGIN_AUTOLOAD=1` 隔离宿主插件后，在项目 Conda
环境运行 Python 全量 pytest 344 通过，`compileall` 通过，有 4 个既有 websockets 弃用警告；Web 状态/组件测试
最新 17 通过，Web 构建通过（仅既有 bundle 体积和 Browserslist 警告）。首次普通 pytest 受宿主 ROS/lark 插件和 `~/.local` websockets 污染而失败，
不能作为项目运行时失败。Mobile 切片状态机测试 6 通过，`type-check`、H5 构建和微信小程序构建通过。

## 6. 未验证风险

- **Redis**：临时真实 Redis 已验证原生 Lua 锁归属、条件释放、CAS 和 TTL；尚未验证 Spring serializer、跨进程竞争、服务重启恢复和状态过期后的完整应用行为；
- **MySQL**：未在真实数据量和隔离级别验证 clear-then-rebuild 锁等待、回滚和 chunk 状态；
- **浏览器**：未用真实路由、双标签页、刷新和断网验证任务恢复及知识绑定关闭重进；
- **微信**：未验证真实授权 code、合法域名、`chooseMessageFile`、`uploadFile` multipart 与 workspace 切换；
- **外部模型**：未调用真实维护、切片、embedding 或主对话模型，不能确认 10 分钟预算与供应商取消语义；
- **RAGFlow**：本轮没有真实服务器上传、解析、删除和召回联调；
- **上下文质量**：字符上限不是 tokenizer token 上限，截断可能损失靠后的相关事实或检索结果；
- **发布状态**：自动验证和源码提交都不代表生产已经部署；本轮变更仍需目标环境验收。

正式发布前至少需要在隔离环境完成真实 Redis/MySQL、浏览器关闭重进、微信上传、LocalKnowledge 长文档、RAGFlow、
外部模型超时和知识绑定保存后重新打开的端到端验证。
