---
status: canonical
owner: docs
last_verified: 2026-07-23
source_paths:
  - docs/
---

# 项目文档门户

这里只负责把读者送到正确的事实源，不复制架构、规范或报告内容。先按角色选择入口，再按当前任务补读一条专项链路；不要从文件名猜测实现，也不要把整个 `docs/` 当作开工必读。

## 按角色开始

| 角色 | 首读 | 下一步 |
| --- | --- | --- |
| 新成员、架构评审 | [项目完整架构](./architecture/project-architecture.md) | [架构中心](./architecture/README.md)、[当前代码地图](./architecture/current-code-map.md) |
| Python 实时服务开发 | [设备交互链路](./architecture/device-interaction-chain.md) | [本地开发工作流](./development/local-dev-workflow.md) |
| Java / Web / Mobile 开发 | [数据与契约](./architecture/data-and-contracts.md) | [manager-api](../main/manager-api/README.md)、[manager-web](../main/manager-web/README.md)、[manager-mobile](../main/manager-mobile/README.md) |
| 部署与运维 | [部署架构](./architecture/deployment-architecture.md) | [ACR 生产部署](./deployment/private-docker-release.md) |
| 安全或权限审查 | [安全架构](./architecture/security-architecture.md) | [开发规范](./standards/development-specification.md) |
| AI 编程工具、文档维护者 | [AI 工作指令](./standards/ai-coding-agent-instructions.md) | [规范入口](./standards/README.md)、[文档治理](./standards/documentation-policy.md)、[贡献说明](./reference/contributor_open_letter.md) |

## 按任务进入

| 我要做什么 | 首选入口 | 继续阅读 |
| --- | --- | --- |
| 准备或启动本地环境 | [本地开发工作流](./development/local-dev-workflow.md) | [开发机环境](./development/ubuntu-source-development-environment.md)、[手动启动](./development/manual-startup.md) |
| 修改实时对话或语音链路 | [设备交互完整链路](./architecture/device-interaction-chain.md) | [语义控制中枢](./architecture/semantic-control-center.md)、[提示词注入](./architecture/prompt-injection-engineering.md) |
| 修改工具、插件或 MCP | [功能插件架构](./architecture/function-plugin-architecture.md) | [MCP 三类链路](./architecture/mcp-chains.md)、[设备交互链路](./architecture/device-interaction-chain.md) |
| 修改模型管理或评测 | [模型评测与配置助手](./architecture/model-benchmark-and-assistant.md) | [模型选择与厂商目录](./architecture/model-selection-and-vendor-catalog.md)、[模型管理](./models/model-management.md) |
| 修改记忆系统 | [记忆系统完整链路](./architecture/memory-system-chain.md) | [长期记忆维护](./architecture/long-term-memory-maintenance.md)、[PowerMem 集成](./models/powermem-integration.md) |
| 修改知识库或 RAG | [知识库完整链路](./architecture/knowledge-base-chain.md) | [本地知识库接入](./models/local-knowledge-integration.md)、[RAGFlow 接入](./models/ragflow-integration.md) |
| 修改设备、OTA 或固件 | [设备交互完整链路](./architecture/device-interaction-chain.md) | [固件编译](./device/firmware-build.md)、[OTA 升级](./device/ota-upgrade-guide.md) |
| 修改 workspace、认证或权限 | [安全架构](./architecture/security-architecture.md) | [工作空间与数据隔离](./architecture/workspace-organization-management.md) |
| 接入第三方服务 | [架构中心](./architecture/README.md) | [飞书](./integrations/feishu-integration.md)、[Home Assistant](./integrations/homeassistant-integration.md) |
| 本地 Docker 或生产部署 | [部署架构](./architecture/deployment-architecture.md) | [部署资产边界](./deployment/deployment-asset-policy.md)、[本地 Docker](./deployment/local-docker-deploy-guide.md)、[ACR 生产部署](./deployment/private-docker-release.md) |
| 排障或查看阶段结论 | [FAQ](./reference/FAQ.md) | [分析与评测报告](./reports/README.md) |
| 同步官方更新 | [上游同步策略](./standards/upstream-sync-policy.md) | [本地定制记录](./local-customizations.md) |
| 整理文档 | [文档治理策略](./standards/documentation-policy.md) | [仓库事实](./generated/repository-facts.md) |

## 事实边界

| 文档类型 | 用途 | 不应用于 |
| --- | --- | --- |
| `canonical` | 当前架构、契约或强制规则的唯一事实源 | 保存一次性排障过程 |
| `supporting` | 某个能力的实现、接入或操作细节 | 建立与 canonical 冲突的规则 |
| `report` | 带日期、版本和验证边界的审计或评测 | 判断当前代码一定仍如此 |
| [仓库事实](./generated/repository-facts.md) | 自动生成的版本、数量和热点数据 | 手工维护动态数字 |
| 源码与模块 README | 最终实现和模块构建约定 | 代替跨模块架构说明 |

[架构中心](./architecture/README.md)只维护架构阅读路径，[规范入口](./standards/README.md)只维护强制规则目录，[报告入口](./reports/README.md)只维护阶段性结论目录。生产部署只使用 [ACR 镜像生产部署](./deployment/private-docker-release.md)；新增、移动或删除文档后必须运行文档检查。
