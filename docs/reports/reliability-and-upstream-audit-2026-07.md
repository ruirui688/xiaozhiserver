---
status: report
owner: architecture
last_verified: 2026-07-23
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/memory
  - main/manager-api/src/main/java/xiaozhi/modules/knowledge
  - main/manager-api/src/main/java/xiaozhi/modules/agent
  - main/manager-web/src/components/role
  - main/manager-mobile/src/pages/knowledge
  - main/manager-mobile/src/pages/memory
  - main/xiaozhi-server/core/utils/prompt_budget.py
  - docs/local-customizations.md
---

# 2026-07 功能可靠性与上游差异审计

本文合并 2026-07 功能可靠性维护、仓库与官方上游能力比较，以及 2026-06 系统问题审计中仍有唯一价值的结论。它只描述 2026-07-23 所见的源码、提交和自动验证，不是当前架构事实或持续更新的 backlog。

当前事实应回到[记忆系统完整链路](../architecture/memory-system-chain.md)、[知识库完整链路](../architecture/knowledge-base-chain.md)、[本地定制记录](../local-customizations.md)和[官方上游同步策略](../standards/upstream-sync-policy.md)复核。

## 1. 审计边界

本报告必须区分三类状态：

| 状态 | 含义 |
| --- | --- |
| 已提交源码 | Git 中可追溯的实现，不等于已经发布到生产 |
| 工作区维护结果 | 当次修复及自动测试结果，仍可能需要集成、提交和目标环境验收 |
| 外部系统行为 | Redis、MySQL、RAGFlow、浏览器、微信和外部模型的真实行为；没有联调证据时不得宣称已验证 |

上游比较使用当时的 `upstream/main=b1e39da9b8ff5f53362ad21cc11918cc4819fc37`，本地已提交基线为 `518e08e1db9dc151d698dd0035ac1b8c06246e5a`，共同基点为 `b903a88cd50092ccf184650c776a6a3d27f6cc1f`。这些提交仅是历史复核坐标；再次同步上游时必须重新 fetch、重新计算 merge base，不能沿用本报告的提交数或文件数。

## 2. 可靠性故障根因

### 2.1 长期记忆维护

正式重建曾把模型调用、动作校验和数据库替换绑定在一个最长约 300 秒的 HTTP 请求上。页面刷新、代理超时或网络中断后，浏览器无法判断后台是否仍在运行，也没有可恢复 taskId、服务端进度或跨标签页互斥。

人工“记住”还曾再次依赖维护模型；维护模型返回 `NONE`、非法 JSON 或网络失败时，用户明确保存的事实可能不落库。管理端人工事实后来改为直接保存并标记 `manual`，维护模型无效输出不能撤销主模型已经作出的保存决定。

### 2.2 知识库切片与文档状态

切片失效不是单一算法问题，而是多层契约偏差：

- `parserConfig` 在数据库 JSON、DTO 和远端响应之间转换时可能丢失扩展字段；
- LocalKnowledge 已同步产出切片，service 仍可能把文档记录成 `RUNNING`；
- 规则切片和 LLM 输入窗口各自维护字符边界，长中文段落可能硬切或丢字；
- 不支持格式和零切片缺少明确失败语义，页面会长期显示处理中；
- Web 手工指定 `multipart/form-data` 时没有浏览器生成的 boundary，上传体可能无法解析。

第二轮审计还发现了授权与远端同步风险：召回请求体曾可覆盖路径中的 `datasetId`；解析或删除前没有统一验证全部 `documentId` 归属；每次列表轮询都可能触发高成本全量对账；远端上传成功而本地影子记录失败时可能遗留无主文档。

### 2.3 智能体知识绑定

智能体详情的 `functions` 同时承载普通插件和知识库映射，`paramInfo` 又可能是字符串或对象。旧表单在元数据尚未加载时恢复映射，或因深度 watcher、`clearSelection()` 和乱序请求重建表单，可能清空模型、知识库选择与 `queryPolicy`。写操作自动重放还可能在响应不确定时创建重复资源。

### 2.4 微信小程序管理链路

manager-api 已有微信、知识库和记忆接口，但移动端曾缺少完整入口；token 的字符串/对象存储形态不统一，普通请求与 `uni.uploadFile` 也没有一致透传 workspace。后端能力存在不代表微信端能够完成创建、上传、解析、切片查看、召回测试和记忆维护闭环。

### 2.5 动态 LLM 上下文

记忆、知识召回、外部 context、历史 tool result 和工具描述分别进入 LLM 上下文。只有局部数量限制而没有分域预算时，大量知识、长 `queryPolicy` 或多轮工具结果可能挤占静态角色与安全提示；裁剪工具 schema 又可能破坏参数合同。

## 3. 2026-07 源码修复边界

### 3.1 记忆任务生命周期

正式重建改为服务端异步任务：创建前校验 agent 所有权和 workspace 写权限，以 Redis 实现每智能体活动锁，保存任务状态与 latest 指针，由 worker 分阶段执行并形成 `SUCCEEDED`、`FAILED` 或 `TIMED_OUT` 终态。初始状态保存失败时不启动 worker，锁只由同一 taskId 释放，失败信息脱敏并截断，迟到结果不能覆盖失败或超时终态。

Web 通过任务 ID 轮询并逐步退避，重新进入页面时读取最近任务。活动任务期间互斥其他维护操作；预览重建和主动重审仍是同步请求。

### 3.2 切片、文档与授权契约

- manager-api 边界显式解码 `parserConfig`，优先保存用户原始完整 JSON；
- 统一中文句界、段落、LLM 窗口和硬上限切分，并测试分段不丢字；
- LocalKnowledge 同步完成时写 `DONE/100%`，零切片与不支持格式明确失败；
- Web 由 `FormData` 自动生成 multipart boundary，小程序使用 `uni.uploadFile`；
- 召回只使用已经鉴权的路径 `datasetId`，请求体不能覆盖授权资源；
- 解析和删除在调用 adapter 前验证每个文档属于路径知识库，并对重复 ID 去重；
- 知识库创建要求 workspace 写权限，智能体不能绑定其他 workspace 的知识库；
- 全量远端对账按知识库设置 60 秒 Redis/Lua 冷却；Redis 不可用时跳过高成本全量同步，但继续返回本地数据；
- 远端上传成功而影子记录持久化失败时尝试补偿删除远端与本地内容。

### 3.3 表单与小程序恢复

Web 智能体表单改用显式 hydration、dirty path 合并和请求序号；元数据完成前禁止保存，程序化恢复期间忽略空选择事件，保存失败保留草稿。普通插件与知识库映射先规范化再拆分，关闭重进恢复知识库 ID 与 `queryPolicy`。

移动端补充微信登录/绑定、统一 token、workspace 请求头、知识库 CRUD、上传/解析、切片查看、召回测试和长期记忆页面。源码存在与构建通过都不代表微信授权、合法域名或真机上传已经验收。

### 3.4 上下文预算

当次实现按来源限制 memory、外部 context、知识结果、历史 tool result 和工具描述，只裁剪动态内容及 description，不修改工具名称、类型、enum、required 等 schema。静态 system 角色与安全规则不参与裁剪。字符预算只是工程上限，不等于 tokenizer token 预算或相关性保证。

## 4. 与官方上游的能力边界

截至上述上游快照，本地仓库不是官方项目的简单增强版，而是面向私有生产、长期运营和微信管理场景的产品分支。差异应按契约判断，不能按目录覆盖：

| 领域 | 本地必须保护的边界 | 上游可重新评估的输入 |
| --- | --- | --- |
| 语义与工具 | SemanticCenter、工具优先级、IoT 服务端门控、取消和超时语义 | 协议兼容、异步化和并发修复 |
| 长期记忆 | managed memory、事实/修订/遗忘、可信声纹隔离、异步重建与审计 | PowerMem provider 修复 |
| 知识库 | LocalKnowledge、MySQL chunk、切片状态、绑定恢复、workspace 所有权 | RAGFlow 稳定性和错误提示 |
| 模型 | 标准字段/密钥拆分、真实 provider 评测、配置助手边界 | 新 provider 和 catalog 信息 |
| Mobile | 微信登录、workspace、知识库和记忆闭环 | 官方移动端质量、配网、多语言和快照能力 |
| 安全与部署 | 资源所有权、默认认证、SSRF/脱敏、ACR 版本化部署资产 | 上游安全补丁与镜像兼容更新 |

本地曾主动移除数字人测试端，并缩减部分移动端配网和多语言范围。这是时间点产品取舍，不应伪装成官方缺陷；需要恢复时应单独评审，而不是在一次上游合并中顺带带回。

本报告不授权合并任何上游提交。再次同步必须遵循上游同步策略，在独立分支重新核对认证、数据库迁移、设备协议、配置和本地定制。

## 5. 文档收敛映射

2026-07-23 文档治理把 75 份、18,694 行收敛为 36 份、约 10,500 行。删除前先迁移仍有效内容，Git 历史保留旧快照：

| 原文档组 | 当前唯一入口 |
| --- | --- |
| 架构目录索引、代码地图、云运行架构 | [项目架构](../architecture/project-architecture.md)、[运行流程](../architecture/runtime-flows.md)、[数据与契约](../architecture/data-and-contracts.md) |
| 部署架构、部署资产策略 | [生产部署](../deployment/private-docker-release.md)、[本地 Docker](../deployment/local-docker-deploy-guide.md) |
| 长期记忆维护、记忆与知识治理 | [记忆系统](../architecture/memory-system-chain.md)、[知识库链路](../architecture/knowledge-base-chain.md) |
| 模型目录、模型管理分析、性能测试教程 | [模型管理](../models/model-management.md)、[模型评测与助手](../architecture/model-benchmark-and-assistant.md) |
| 播报打断链路、状态机游戏说明 | [语义控制](../architecture/semantic-control-center.md)、[功能插件](../architecture/function-plugin-architecture.md) |
| 本地知识库专项 | [知识库链路](../architecture/knowledge-base-chain.md)；外部服务仍看 [RAGFlow](../models/ragflow-integration.md) |
| 手动启动、Ubuntu 环境 | [本地开发](../development/local-dev-workflow.md) |
| 固件设置、OTA | [固件与 OTA](../device/firmware-build.md) |
| MQTT 网关、开机 WebSocket/MCP | [设备呼叫](../device/device-call-guide.md)、[设备交互](../architecture/device-interaction-chain.md) |
| 短信、飞书、Home Assistant、天气、新闻、PaddleSpeech | [第三方集成](../integrations/third-party-integrations.md) |
| MCP Endpoint、no-code、context、device info、vision | [MCP 操作](../mcp/mcp-operation-guide.md)、[MCP 架构](../architecture/mcp-chains.md) |
| FAQ、贡献公开信、旧 backlog、两个阶段报告和报告索引 | 本报告、[开发规范](../standards/development-specification.md)和[文档入口](../README.md) |
| AI 重复指令、配置策略、规范索引 | 根目录 `AGENTS.md`、[开发规范](../standards/development-specification.md)、[文档治理](../standards/documentation-policy.md) |
| 豆包单音色与旧音量分析 | 有效结论并入[模型管理](../models/model-management.md)和[设备交互](../architecture/device-interaction-chain.md) |

## 6. 自动验证记录

2026-07-23 的最终跨模块集成记录为：Java 198 项、0 失败、0 错误、5 跳过；项目 Conda 环境下 Python 344 项通过且 `compileall` 通过；Web 状态/组件测试 17 项通过并完成构建；Mobile 切片状态机 6 项通过，并完成类型检查、H5 和微信构建。普通 pytest 曾受宿主 ROS/lark 插件与用户目录 websockets 包污染，隔离宿主插件后通过。测试数量只是这次历史运行记录，不是稳定仓库事实。

没有在本轮文档治理中重新运行 Java、Python、Web 或 Mobile 构建；文档变更只执行链接、结构、敏感信息和 Git diff 检查。

## 7. 未验证风险

- 真实 Redis 的 Spring serializer、Lua/TTL、跨进程竞争、服务重启恢复和任务过期行为未完成应用级联调；
- 真实 MySQL 数据量、事务隔离、并发重建和 chunk 状态未验证；
- RAGFlow 的上传、解析、删除、召回与补偿删除语义未做真实服务联调；补偿调用自身仍可能失败；
- 浏览器双标签、刷新、断网、知识绑定关闭重进和网络结果不确定时的人工重试未做真实路由验收；
- 微信授权 code、合法域名、文件选择、multipart 上传和 workspace 切换未做开发者工具或真机验收；
- 维护、切片、embedding 和主对话外部模型未真实调用，取消与超时只能尽力而为；
- 字符预算可能裁掉靠后的相关事实，不能代替 tokenizer 计数或相关性评测；
- 自动测试与源码提交均不代表已发布生产。

## 8. 2026-06 历史审计迁移结果

旧“系统问题与优化审计”是源码时间点快照，不再作为持续 backlog。内容按下表迁移或删除：

| 旧主题 | 处理结果 |
| --- | --- |
| 音量概念与范围 | 历史诊断已去重；当前设备音量、TTS 与运行链路从[设备交互完整链路](../architecture/device-interaction-chain.md)进入 |
| 语音管道延迟 | 真机阶段证据保留在[语音延迟报告](../models/voice-pipeline-latency-analysis.md)；当前契约从[设备交互完整链路](../architecture/device-interaction-chain.md)进入 |
| 豆包 TTS 音色 | 旧数据库快照已删除；当前 provider 与模型配置从[设备交互完整链路](../architecture/device-interaction-chain.md)和[模型管理](../models/model-management.md)复核 |
| 短期记忆不显示 | 旧双路径分析已被托管记忆架构和本报告的记忆维护结论替代 |
| 模型管理混乱 | 当前存储与运行契约以[模型管理](../models/model-management.md)为准，旧文件数和枚举快照删除 |
| 播报打断与端到端路线 | 旧方案快照已删除；当前打断契约以[语义控制中枢](../architecture/semantic-control-center.md)为准 |
| 问题总览与修复建议 | 多为上述章节重复或未经当前源码复核的方案，删除并由 Git 历史追溯 |

仍有一项唯一观察被保留：截至 2026-07-23，`AgentChatSessionDTO` 仍没有设备字段，`getSessionListByAgentId()` 仍只按 `session_id` 聚合，`ChatHistoryDialog.vue` 仍要进入具体会话后才显示首条消息的 MAC，不能在会话列表按设备查看或筛选。这只是经只读源码复核的未解决候选，不是已批准需求；实施前还需确认产品交互、资源所有权和查询索引。
