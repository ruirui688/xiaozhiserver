# 规范文档入口

本目录保存本项目的强制规则。这里的文档不是建议，也不是经验总结；开发者和 AI 编程工具在修改项目代码、配置、部署流程或文档结构前，必须按任务读取并遵守。

## 文档列表

- [development-specification.md](./development-specification.md) - 项目开发总规范。规定仓库职责、标准远程命名、分支模型、提交流程、数据库版本管理、环境配置隔离、阿里云部署、生产发布、官方上游同步和禁止事项。该文档面向人和 AI，是项目二次开发的最高规则文档。
- [ai-coding-agent-instructions.md](./ai-coding-agent-instructions.md) - AI 编程工具工作指令。规定 Codex、Cursor、Copilot、Claude Code 等工具在本项目中开工前必须读取哪些文件、如何识别真实私有远端、如何推送当前分支、可以自动执行哪些操作、哪些操作必须等待用户授权，以及不同任务类型对应的文档读取范围。
- [config-runtime-policy.md](./config-runtime-policy.md) - 配置与运行产物治理策略。规定默认模板、示例配置、环境私有配置、运行时生成配置的边界，以及 `config/examples/`、`runtime/` 和私有配置的提交规则。
- [upstream-sync-policy.md](./upstream-sync-policy.md) - 官方上游同步策略。规定官方源码与本地定制的边界、同步前检查、冲突处理、高风险文件和同步后验证要求。

## 使用规则

所有任务必须先读取：

```text
README.md
docs/standards/development-specification.md
docs/standards/ai-coding-agent-instructions.md
```

如果任务涉及专项内容，再按 `docs/README.md` 的分类读取对应文档。禁止为了一个小修改一次性读取全部文档，也禁止未读取强制规范就修改代码。

本地 Ubuntu 源码开发环境准备任务还必须读取：

```text
docs/development/ubuntu-source-development-environment.md
```

涉及配置、运行产物、部署资产、官方同步的任务还必须按需读取：

```text
docs/standards/config-runtime-policy.md
docs/standards/upstream-sync-policy.md
docs/deployment/deployment-asset-policy.md
```
