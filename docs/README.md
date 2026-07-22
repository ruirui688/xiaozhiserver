---
status: canonical
owner: docs
last_verified: 2026-07-12
source_paths:
  - docs/
---

# 项目文档门户

这里是按任务进入文档的唯一入口。不要从目录文件名猜测当前实现，也不要把 `docs/` 全部作为开工必读；先进入架构总览，再按当前任务读取专项文档。

## 快速理解项目

1. [项目架构总览](./architecture/project-architecture.md) - 系统上下文、模块职责、核心链路、数据与安全边界。
2. [云端运行与配置链路](./architecture/cloud-runtime-architecture.md) - 后台配置如何进入 Python 实时服务并影响设备行为。
3. [当前代码地图](./architecture/current-code-map.md) - 源码入口、关键目录和验证矩阵。
4. [仓库事实](./generated/repository-facts.md) - 自动生成的技术版本、模块规模、迁移登记和热点文件数据。
5. [当前仓库与官方上游能力审计](./reports/current-vs-upstream-capability-audit.md) - 按提交基线说明能力差异、必要性和同步策略。

模块开发入口：

- [Python 实时服务](./architecture/project-architecture.md)（启动和验证见[本地开发工作流](./development/local-dev-workflow.md)）
- [Java 管理 API](../main/manager-api/README.md)
- [Web 管理后台](../main/manager-web/README.md)
- [Mobile 管理后台](../main/manager-mobile/README.md)

## 按任务进入

| 我要做什么 | 首选入口 | 继续阅读 |
| --- | --- | --- |
| 开始本地开发 | [本地开发工作流](./development/local-dev-workflow.md) | [开发机环境](./development/ubuntu-source-development-environment.md)、[手动启动](./development/manual-startup.md) |
| 修改实时对话或语音链路 | [设备交互完整链路](./architecture/device-interaction-chain.md) | [语义控制中枢](./architecture/semantic-control-center.md)、[提示词注入](./architecture/prompt-injection-engineering.md) |
| 修改工具、插件或 MCP | [功能插件架构](./architecture/function-plugin-architecture.md) | [MCP 三类链路](./architecture/mcp-chains.md)、[设备交互链路](./architecture/device-interaction-chain.md) |
| 修改模型管理或评测 | [模型评测与配置助手](./architecture/model-benchmark-and-assistant.md) | [模型选择与厂商目录](./architecture/model-selection-and-vendor-catalog.md)、[模型管理](./models/model-management.md) |
| 修改记忆系统 | [记忆系统完整链路](./architecture/memory-system-chain.md) | [长期记忆维护](./architecture/long-term-memory-maintenance.md)、[PowerMem 集成](./models/powermem-integration.md) |
| 修改知识库或 RAG | [知识库完整链路](./architecture/knowledge-base-chain.md) | [本地知识库接入](./models/local-knowledge-integration.md)、[RAGFlow 接入](./models/ragflow-integration.md) |
| 修改设备、OTA 或固件 | [设备交互完整链路](./architecture/device-interaction-chain.md) | [固件编译](./device/firmware-build.md)、[OTA 升级](./device/ota-upgrade-guide.md) |
| 修改 workspace 或权限 | [工作空间与数据隔离](./architecture/workspace-organization-management.md) | [开发规范](./standards/development-specification.md) |
| 排查现有问题 | [FAQ](./reference/FAQ.md) | [问题与优化 backlog](./reference/system-issues-and-optimization-backlog.md) |
| 参与项目开发 | [贡献说明](./reference/contributor_open_letter.md) | [规范目录](./standards/README.md)、[开发规范](./standards/development-specification.md) |

## 部署与运行

部署入口按环境严格区分：

- **生产环境**：[ACR 镜像生产部署](./deployment/private-docker-release.md)，这是唯一生产入口。
- **本地或测试 Docker**：[本地 Docker 部署](./deployment/local-docker-deploy-guide.md)。
- **本地源码开发**：[本地开发工作流](./development/local-dev-workflow.md)。
- **部署资产边界**：[部署资产治理策略](./deployment/deployment-asset-policy.md)。

部署脚本、Compose 和生产资产分别从 [scripts/README.md](../scripts/README.md) 与 [deploy/README.md](../deploy/README.md) 进入。旧官方镜像教程和服务器源码自动升级流程已移除，不得作为当前部署依据。

## 专项能力

- **设备与固件**：从[固件编译](./device/firmware-build.md)或[设备交互链路](./architecture/device-interaction-chain.md)进入固件、OTA、MQTT 和设备互呼专题。
- **第三方集成**：从[飞书智能体集成](./integrations/feishu-integration.md)、[Home Assistant](./integrations/homeassistant-integration.md)进入协作平台、短信、新闻、天气等外部能力接入。
- **MCP**：从[MCP 三类链路](./architecture/mcp-chains.md)或[MCP Endpoint 启用](./mcp/mcp-endpoint-enable.md)进入设备上下文、视觉和无代码工具专题。
- **模型**：从[模型管理](./models/model-management.md)进入 ASR、TTS、LLM、声纹、记忆、RAG 和性能专题。
- **分析报告**：[reports/README.md](./reports/README.md) 汇总阶段性评测与问题分析；当前可直接查看[仓库与官方上游能力审计](./reports/current-vs-upstream-capability-audit.md)和 [FunASR / SenseVoice 资源基准](./models/funasr-sensevoice-resource-benchmark.md)。

专项文档只解释所属能力。跨模块的当前事实应回写架构文档，强制约束应回写规范文档，避免在多个教程中各自维护一套架构描述。

## 强制规范

所有修改必须先读取：

- [开发规范](./standards/development-specification.md)
- [AI 编程工具工作指令](./standards/ai-coding-agent-instructions.md)
- [本地定制记录](./local-customizations.md)
- [规范目录](./standards/README.md)

按任务补充读取：

- [配置与运行产物策略](./standards/config-runtime-policy.md)
- [官方上游同步策略](./standards/upstream-sync-policy.md)
- [文档治理策略](./standards/documentation-policy.md)

新增、移动、删除文档时必须同步更新本门户，并执行相对链接与孤立文档检查。文档的 `status` 表示其权威程度：`canonical` 是当前事实的唯一来源，`supporting` 是使用或实现细节，`report` 是带验证日期的阶段性结论。
