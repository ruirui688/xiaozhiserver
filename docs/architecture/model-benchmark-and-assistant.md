---
status: supporting
owner: model
last_verified: 2026-08-03
source_paths:
  - main/xiaozhi-server/core/benchmark
  - main/manager-api/src/main/java/xiaozhi/modules/model
---

# 模型专业评测与配置智能助手架构

本文记录智控台模型专业评测和模型配置智能助手的职责边界、调用链、分层关系和治理规则。两者都服务于模型接入治理，但不能互相替代：

```text
模型专业评测       用真实 provider 和真实输入产出可审计测试证据
模型配置智能助手   用已启用 LLM 理解文档并生成配置或接入草案
```

配置助手可以提高接入效率，但它的输出不代表模型可用；专业评测可以证明某次配置在当前环境下的真实表现，但它不负责生成新 adapter 或修改源码。

模型配置页的选择目录由[模型管理与选择契约](../models/model-management.md)统一定义。用户先按能力和场景选择带协议、适配状态的目录项，再填写配置；不能从 adapter 名称猜厂商产品。

## 1. 能力边界

| 能力 | 入口 | 可以做 | 不能做 |
| --- | --- | --- | --- |
| 模型专业评测 | 智控台 `模型配置` 页面批量选择后执行 `性能测试` | 创建异步评测任务、调用 `xiaozhi-server` 真实 provider、记录成功率、延迟分位数、样本详情、Judge 结果和未实测原因 | 不修改模型配置、不启用模型、不生成 provider、不把字段校验或连通成功当作真实通过 |
| 模型配置智能助手 | 智控台模型配置页右侧助手 | 读取用户粘贴的厂商文档和当前表单上下文，匹配已知 adapter，生成可填表配置草案、缺失字段、协议接入草案和实现任务 | 不直接保存模型配置、不标记测试通过、不启用模型、不执行生产数据库变更、不把协议不匹配的同厂商 adapter 说成可用 |
| 助手产物写入 | `apply-artifacts` 开发辅助入口 | 在开发工作区写入模型接入相关白名单路径的草案文件，形成可审查 diff | 不覆盖已有文件，除非显式允许；不允许绝对路径、路径越界、生产配置或真实密钥 |

核心原则：

- 配置草案必须再经过单模型测试或批量专业评测。
- ASR 是高风险能力，测试必须发送真实音频并返回非空识别文本；握手、鉴权成功或字段完整不能标记通过。
- LLM/SLM 评测要区分候选模型直连评测和真实设备智能体运行时评测；只有选择真实设备时，才代表人设、知识库、插件、MCP、上下文源和记忆共同作用下的设备链路表现。
- LLMJudge 只用于评价候选模型输出，不是配置助手的决策来源；Judge 模型也必须复用后台已有 LLM/SLM 配置，不单独保存 API Key。

## 2. 调用链

### 2.1 专业评测

```text
manager-web ModelConfig.vue
  -> src/apis/module/model.js performanceTest()
  -> manager-api POST /models/performance-test
  -> ModelController.performanceTest()
  -> ModelBenchmarkService.createBenchmarkTask()
  -> ai_model_benchmark_task 写入 PENDING
  -> 后台线程按模型并发执行
  -> manager-api POST xiaozhi-server /benchmark/models
  -> xiaozhi-server BenchmarkHandler.handle_models()
  -> core.benchmark.runner.run_benchmark()
  -> 真实 ASR / LLM / TTS / VLLM provider
  -> manager-api 写入 ai_model_benchmark_result / ai_model_benchmark_sample
  -> manager-web 轮询 GET /models/benchmark/{taskId}
```

历史任务链路：

```text
manager-web 历史记录
  -> GET /models/benchmark?modelType=...
  -> GET /models/benchmark/{taskId}
  -> DELETE /models/benchmark/{taskId}
  -> manager-api 级联清理 task/result/sample
```

LLM/SLM 的两种运行方式：

```text
未选择真实测试设备
  -> runner 直接创建候选 LLM provider
  -> 优先使用弹窗输入的业务测试问题，留空时使用内置多轮业务样本逐轮对话
  -> 同一模型同一轮样本集批量交给 Judge 或启发式评分

选择真实测试设备
  -> manager-api 按 agentId / deviceId 构建运行时上下文
  -> runner 使用 BenchmarkRuntimeAgent 初始化 ConnectionHandler
  -> 按设备 MAC 加载真实智能体配置
  -> 使用后台传入 runtime_config 覆盖校准 prompt、记忆、插件、MCP 和上下文源
  -> 临时覆盖候选主 LLM
  -> 走 startToChat、Prompt、Memory、Intent、工具、MCP、TTS 文本捕获链路
```

Realtime LLM 的单模型/批量测试链路：

```text
runner 读取 typed_config.conversation_mode=realtime
  -> 建立真实 Realtime WebSocket
  -> session.update
  -> 临时切换 manual turn_detection，把测试问题放入本轮受限 instructions
  -> 发送非空 PCM、input_audio_buffer.commit、response.create
  -> 收集 response.text.delta / audio_transcript.delta / audio.delta
  -> response.done 或明确错误终态
  -> 输出连接耗时、首文本、首音频、总耗时和完整文本
```

该模型级 benchmark 证明 provider 与协议可用，不等于已经完成真实设备音频、图片、工具和打断的全链路验收。选择真实设备
时，运行时评测还必须确认该智能体进入 Realtime 管线；依赖普通同步 LLM 的 Judge 继续使用独立传统 LLM/SLM，不能复用
候选 Realtime 长连接会话。

### 2.2 配置智能助手

```text
manager-web ModelConfig.vue
  -> ModelConfigAssistant.vue
  -> src/apis/module/model.js getAssistantSettings/updateAssistantSettings/chatWithAssistant/applyAssistantArtifacts
  -> manager-api /models/assistant/settings
  -> manager-api /models/assistant/chat
  -> ModelAssistantServiceImpl.chat()
  -> 从 sys_params 读取助手 LLM modelId
  -> 校验该 LLM 已启用且测试通过
  -> 读取模型目录 knownAdapters
  -> 对用户文档、表单上下文和测试消息脱敏
  -> 调用后台已有 LLM 配置的 chat completions 接口
  -> 解析结构化 JSON 响应
  -> 返回 supported、matchedAdapter、draftConfigJson、warnings、protocolSpecDraft、implementationTasks、integrationArtifacts
  -> 前端把草案拆分为 adapterCode / endpointUrl / upstreamModelId / params / secrets 后保存
```

产物写入链路：

```text
manager-web 点击写入接入产物
  -> POST /models/assistant/apply-artifacts
  -> ModelAssistantServiceImpl.applyArtifacts()
  -> findProjectRoot()
  -> 校验路径白名单、路径归一化和 overwrite
  -> Files.writeString()
  -> 返回 written / skipped / failed
```

该入口仅用于开发环境形成草案 diff。写入后的文件仍要按本项目开发流程人工审核、补全实现、补数据库迁移登记、运行真实模型测试。

## 3. 职责分层

```text
展示层 manager-web
  - 提供模型选择、评测弹窗、历史记录、详情表格和助手侧栏
  - 只提交用户动作和页面上下文，不直接调用模型 provider

管理层 manager-api
  - 模型配置、评测任务、助手设置和权限控制的事实来源
  - 负责任务入库、异步执行、进度轮询、结果持久化和历史清理
  - 负责助手 LLM 选择、上下文脱敏、adapter 目录约束和草案写入安全

运行层 xiaozhi-server
  - 负责真实 provider 初始化和模型调用
  - 负责 ASR 标准音频、LLM 多轮样本、TTS 音频有效性、VLLM 调用等运行时证据
  - 选择真实设备时复用设备会话链路，而不是另写一套简化聊天链路

数据层 MySQL / Redis / sys_params
  - ai_model_benchmark_task/result/sample 保存评测历史
  - ai_model_config 保存模型通用字段
  - ai_model_config_param 保存 provider 普通参数
  - ai_model_config_secret 保存 provider 密钥参数
  - sys_params 保存助手 LLM modelId 等系统参数
```

## 4. 质量与安全规则

### 4.1 评测规则

- `REAL_INFERENCE` 才能进入延时统计；`CONNECTIVITY`、`CONFIG_ONLY`、`UNSUPPORTED` 不能显示为 0ms。
- 单模型普通“测试”和批量“专业评测”口径要一致：字段校验不是可启用依据。
- 评测任务可以失败，但失败原因必须写入任务或样本详情，便于区分服务不可达、provider 初始化失败、模型调用失败和结果持久化失败。
- LLM/SLM Judge 不保存独立密钥，只引用已有模型配置；未选择或调用失败时降级为启发式评分，并在 `judge_mode` 中标记。
- `conversation_mode=realtime` 的 LLM 必须走 Realtime benchmark transport；不能调用普通 `llm.create_instance()` 或 Chat Completions 冒充实时模型测试。
- Qwen 3.5 Realtime 不接受用 `conversation.item.create` 创建普通 user 文本项；文本评测必须走手动音频提交回合，`conversation.item.create` 只用于回注 `function_call_output`。
- 选择真实测试设备时，必须校验设备存在、MAC 地址存在、设备属于所选智能体，并能加载真实运行时配置。
- 弹窗填写的业务测试问题必须作为评测样本进入 runner，不能只用内置通用问题替代真实场景。多行问题按同一业务场景的连续多轮对话执行。
- 真实设备智能体评测必须显式应用后台 `runtime_config`，确保候选模型看到所选智能体的人设、记忆、插件、MCP 和上下文源；候选 LLM 覆盖只能发生在运行时配置校准之后。
- Realtime 测试只有在会话更新成功且收到真实文本或音频响应后才能标记 `REAL_INFERENCE`；仅 WebSocket 握手成功仍是连通性证据。
- Realtime 测试必须有连接、响应和单事件大小预算，异常时取消响应并关闭上游连接；结果与日志不得包含 Authorization、API Key、图片 base64 或原始音频。

管理后台创建任务后轮询 `/models/benchmark/{taskId}`，历史结果长期保存并可级联删除 task/result/sample；运行中的任务
不能删除。`测试轮数` 表示重复完整样本集，`并发模型数` 只控制不同候选模型并发；同一模型内部按轮次、case 和多轮
user turn 串行，避免破坏对话上下文或瞬间打满 provider。

报告至少区分 `success_rate`、`test_level`、端到端延迟及 P50/P90/P95、LLM `ttft_ms`、流式 TTS
`first_audio_ms`、质量分、Judge 模式、安全 P0/P1 和样本明细。只有 `REAL_INFERENCE` 进入延迟统计；非流式模型
不伪造首段指标。LLMJudge 按同一模型同一轮的完整多轮样本集批量评分，未选择或失败时记录启发式降级。

运行前先确认 xiaozhi-server HTTP `8003` 可达且私有配置存在。跨主机或容器部署通过
`XIAOZHI_SERVER_HTTP_BASE_URL` 指向实际服务地址；Judge 只引用标准模型配置 ID，不另存 API Key。

### 4.2 助手规则

- 助手设置只保存已配置 LLM 的 `modelId`；`base_url`、`model_name`、`api_key` 等凭据由标准模型字段、参数表和密钥表组装。
- 发送给助手的标准模型字段、`params`、`secrets`、测试消息和文档上下文必须脱敏、截断，不能泄露 API Key、Secret、Token。
- 是否支持某协议只能基于 `knownAdapters` 判断；厂商相同但协议不同必须返回未适配。
- `draftConfigJson` 是助手草案响应字段名，不是模型配置保存契约；保存前必须拆分为标准字段、`params` 和 `secrets`，且不能绕过保存后的真实测试。
- `integrationArtifacts` 是草案，不是已完成实现；提交前必须清理占位文件、补全真实实现和验证记录。
- 产物写入必须限制在模型接入白名单路径，默认不覆盖已有文件，禁止绝对路径和 `..` 路径越界。

## 5. 残留草案治理

配置助手会生成“可写入工作区”的接入草案，因此提交前必须专门检查残留占位文件。占位草案应在提交前删除或转正为完整实现。

提交前必须清理或转正的典型草案路径：

```text
main/manager-api/src/main/resources/db/changelog/YYYYMMDDHHMM.sql
main/xiaozhi-server/core/providers/asr/new_provider.py
tests/integration/test_new_provider.py
main/xiaozhi-server/tests/test_<adapter>_asr.py
```

治理规则：

- 文件名包含 `YYYYMMDDHHMM`、`new_provider`、`TODO: 调用真实模型接口`、`NotImplementedError` 的产物不能作为完成实现提交。
- 数据库草案必须改成真实时间戳迁移文件，并登记到 `db.changelog-master.yaml`；未登记或结构未按当前表确认的 SQL 不得提交。
- Provider 草案必须补齐鉴权、请求、响应解析、错误处理和真实测试，不能只保留类壳。
- 测试草案必须变成可自动结束的测试或明确标记为不提交的本地草稿。
- 如果草案只是助手示例输出，应删除出提交范围；如果要转正，必须经过代码审核和真实模型测试。

## 6. 验证矩阵

| 修改范围 | 最小验证 |
| --- | --- |
| 只改本文档或索引 | Markdown 链接/路径检查、`git diff --check` |
| 改助手提示词、脱敏或写入规则 | Java 单测或针对 `ModelAssistantServiceImpl` 的服务测试；检查路径白名单和敏感字段脱敏 |
| 改专业评测管理层 | `mvn test`，并检查任务创建、轮询、历史删除和失败路径 |
| 改 xiaozhi-server benchmark runner | `conda run -n xiaozhi-esp32-server python -m compileall .` 和相关 benchmark/LLMJudge/ASR 测试 |
| 改前端交互 | `npm run test:unit && npm run build`，并人工检查弹窗、轮询、历史记录和助手侧栏 |
| 改 Realtime 模型前端提示 | 检查目录/编辑/测试/智能体选择标签、绕过说明、手动切回和记忆边界 |
| 改数据库结构 | 新增 changelog、登记 master、检查回滚/重复执行风险 |

生产治理要求：

- 不在生产环境使用助手写入源码或数据库草案。
- 不提交真实模型 API Key、生产地址、私有 `.config.yaml` 或评测输入中的敏感业务数据。
- 专业评测结果是环境相关证据；模型、网络、设备、智能体配置或 provider 版本变化后需要重新评测。
