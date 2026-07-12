---
status: canonical
owner: architecture
last_verified: 2026-07-12
source_paths:
  - docs/architecture
---

# 架构中心

这里是理解项目当前架构的唯一入口。先读 [项目完整架构](./project-architecture.md)，再按任务进入运行链路、数据契约、安全、部署、模块或领域专题。

## 快速阅读路径

| 目标 | 阅读顺序 |
| --- | --- |
| 五分钟理解系统 | [项目完整架构](./project-architecture.md) |
| 理解一次设备对话 | [项目完整架构](./project-architecture.md) -> [运行时链路](./runtime-flows.md) -> [设备交互链路](./device-interaction-chain.md) |
| 理解后台配置如何生效 | [数据与契约](./data-and-contracts.md) -> [运行时链路](./runtime-flows.md) |
| 开发实时语音或工具 | [设备交互链路](./device-interaction-chain.md) -> [语义控制中枢](./semantic-control-center.md) -> [MCP 链路](./mcp-chains.md) |
| 开发管理 API 或数据能力 | [数据与契约](./data-and-contracts.md) -> [工作空间隔离](./workspace-organization-management.md) |
| 做安全审查 | [安全架构](./security-architecture.md) -> [部署架构](./deployment-architecture.md) |
| 生产部署 | [部署架构](./deployment-architecture.md) -> [唯一生产部署入口](../deployment/private-docker-release.md) |

## 稳定架构文档

- [项目完整架构](./project-architecture.md)：系统上下文、模块边界、主链路和能力地图。
- [运行时链路](./runtime-flows.md)：设备启动、配置装配、语音、工具、记忆和知识库时序。
- [数据与契约](./data-and-contracts.md)：事实来源、跨模块接口和兼容规则。
- [安全架构](./security-architecture.md)：身份、权限、密钥和输入信任边界。
- [部署架构](./deployment-architecture.md)：本地与生产拓扑、网络、存储和恢复。

## 领域专题

- 设备与语音：[设备交互链路](./device-interaction-chain.md)、[语义控制中枢](./semantic-control-center.md)、[语义打断分析](./semantic-interruption-and-e2e-voice-model-analysis.md)
- 提示词与工具：[提示词注入](./prompt-injection-engineering.md)、[功能插件](./function-plugin-architecture.md)、[MCP 链路](./mcp-chains.md)
- 模型：[模型评测与助手](./model-benchmark-and-assistant.md)、[模型选择与厂商目录](./model-selection-and-vendor-catalog.md)
- 记忆与知识：[记忆系统](./memory-system-chain.md)、[长期记忆维护](./long-term-memory-maintenance.md)、[知识库链路](./knowledge-base-chain.md)
- 记忆治理参考：[记忆系统与知识库治理](../models/memory-and-knowledge-governance.md)
- 组织与权限：[工作空间隔离](./workspace-organization-management.md)
- 互动能力：[游戏会话边界](./stateful-game-session.md)
- 设备常驻连接：[WebSocket 与 MCP 启动连接变更](../device/websocket-mcp-boot-connection-change.md)

## 文档状态

文档头部元数据含义：

- `canonical`：当前领域的权威事实源。
- `supporting`：对权威文档的实现级补充。
- `report`：阶段性分析或调研，不作为当前实现事实。

`source_paths` 标明校验文档时应对照的主要代码路径。代码行为与文档冲突时，以代码和强制规范为准，并同步修正文档。
