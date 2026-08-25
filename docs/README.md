---
status: canonical
owner: docs
last_verified: 2026-07-23
source_paths:
  - docs/
---

# 项目文档入口

不需要通读 `docs/`。日常工作先选下面一个入口，只有修改对应链路时才继续读专项文档。

## 高频入口

| 任务 | 只先读这一份 | 需要深入时再读 |
| --- | --- | --- |
| 了解系统 | [项目架构](./architecture/project-architecture.md) | [运行流程](./architecture/runtime-flows.md)、[数据与契约](./architecture/data-and-contracts.md) |
| 本地启动与排障 | [本地开发](./development/local-dev-workflow.md) | 根目录 `scripts/dev doctor` |
| 生产发布 | [ACR 生产部署](./deployment/private-docker-release.md) | [本地 Docker](./deployment/local-docker-deploy-guide.md) |
| 实时语音、设备、OTA | [设备交互链路](./architecture/device-interaction-chain.md) | [设备多场景](./architecture/device-scene-management.md)、[固件与 OTA](./device/firmware-build.md) |
| 人物资料、识别与机器人迎宾 | [人物资源与设备人脸同步](./architecture/person-resource-and-face-sync.md) | [机器人门口迎宾产品化](./architecture/robot-reception-productization.md)说明端云职责、接待数据与协议；再读[安全架构](./architecture/security-architecture.md) |
| LLM、ASR、TTS 模型 | [模型管理](./models/model-management.md) | [模型评测与配置助手](./architecture/model-benchmark-and-assistant.md) |
| VAD 与端点延迟 | [VAD 低延迟配置](./models/vad-endpointing-low-latency.md) | [设备交互链路](./architecture/device-interaction-chain.md) |
| 记忆 | [记忆系统](./architecture/memory-system-chain.md) | [PowerMem](./models/powermem-integration.md) |
| 知识库与切片 | [知识库链路](./architecture/knowledge-base-chain.md) | [RAGFlow](./models/ragflow-integration.md) |
| 插件、工具、MCP | [功能插件](./architecture/function-plugin-architecture.md) | [MCP 架构](./architecture/mcp-chains.md)、[MCP 操作](./mcp/mcp-operation-guide.md) |
| workspace、认证、安全 | [安全架构](./architecture/security-architecture.md) | [workspace 与组织](./architecture/workspace-organization-management.md) |
| 第三方服务 | [第三方集成](./integrations/third-party-integrations.md) | 飞书、天气、Home Assistant、短信和新闻都在同一页 |
| 查看本轮修复与官方差异 | [2026-07 可靠性与上游审计](./reports/reliability-and-upstream-audit-2026-07.md) | 报告不是当前架构事实 |

## 修改核心链路时

- 提示词、上下文预算和工具 schema：[提示词注入](./architecture/prompt-injection-engineering.md)
- 语义路由、打断和会议门控：[语义控制](./architecture/semantic-control-center.md)
- 设备呼叫能力：[设备呼叫](./device/device-call-guide.md)
- 声纹与人物记忆隔离：[声纹集成](./models/voiceprint-integration.md)
- 当前官方同步保护点：[本地定制记录](./local-customizations.md)和[上游同步策略](./standards/upstream-sync-policy.md)

## 开发规则

- AI 工具唯一入口：[AGENTS.md](../AGENTS.md)
- 人工开发、验证、配置和数据边界：[开发与交付规范](./standards/development-specification.md)
- 文档合并、删除和状态规则：[文档治理](./standards/documentation-policy.md)
- 自动仓库数字：[仓库事实](./generated/repository-facts.md)

源码提交、自动测试、镜像构建和生产部署是四种不同状态。生产只使用 ACR 入口；新增专题前先确认现有入口不能承载，禁止恢复“每个小问题一份 Markdown”的模式。
