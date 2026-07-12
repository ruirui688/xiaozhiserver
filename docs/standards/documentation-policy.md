---
status: canonical
owner: docs
last_verified: 2026-07-12
source_paths:
  - docs/
---

# 文档治理策略

本文档规定项目文档的分类、命名、索引和移动规则。它用于后续整理文档时保持入口清晰、链接稳定，避免把部署资产、运行产物和说明文档混在一起。

## 1. 文档入口

文档入口职责如下：

```text
README.md                         项目总入口，只保留项目概览、常用入口和关键规范链接
AGENTS.md                         AI 编程工具的最短开工规则
docs/README.md                    文档任务门户，按开发、部署和排障任务提供入口
docs/architecture/project-architecture.md  当前系统架构的总入口
docs/standards/README.md          规范目录入口，只解释 standards 下各规范职责
docs/standards/*.md               强制规范，是具体规则来源
main/README.md                    main 模块目录入口，只说明模块职责和文档去向
main/<module>/README.md           模块开发入口，只说明该模块构建、启动、验证和本地约定
```

避免在多个入口重复大段规则。规则变更应优先修改对应 `docs/standards/*.md`，入口文档只链接到规则来源。

## 2. 分类规则

新文档按主题放入以下目录：

```text
docs/architecture/     架构、运行链路、模块边界
docs/development/      本地开发、调试、启动、工具链
docs/deployment/       部署、运维、发布、服务器流程
docs/device/           ESP32 固件、OTA、设备协议、MQTT
docs/integrations/     第三方服务、插件、外部 API 集成
docs/mcp/              MCP 接入点、MCP 工具、设备上下文
docs/models/           ASR、TTS、LLM、声纹、记忆、RAG、性能测试
docs/reference/        FAQ、贡献说明、通用参考
docs/reports/          阶段性评测、审计和问题分析，不作为当前架构事实源
docs/decisions/        架构决策记录（ADR），说明长期决策的原因与后果
docs/standards/        强制规范、治理策略
docs/images/           文档图片资源
```

禁止在 `main/` 下新增通用 `doc/` 或 `docs/` 目录。`main/` 只保留业务模块和模块级 README；通用专题、排障记录、架构说明、部署教程、设备/MCP/模型/集成说明必须归入根目录 `docs/` 的对应分类。

依赖目录、构建产物和缓存目录中的 README、报告或 txt 文件不纳入项目文档索引，例如：

```text
node_modules/
target/
.pytest_cache/
dist/
```

部署脚本、Nginx 配置、Compose 文件、安装脚本等可执行或可直接投产的资产，优先放在 `deploy/` 或 `scripts/`，不要新增到 `docs/`。

## 3. 命名规则

新增 Markdown 文档使用小写 kebab-case：

```text
local-dev-workflow.md
documentation-policy.md
```

历史文档如果已经被外部或内部链接引用，可以暂缓重命名。重命名前必须统一检查并修复链接，必要时保留兼容说明。

## 4. 索引维护

新增、删除或移动文档时必须同步检查：

```text
docs/README.md
docs/standards/README.md
README.md
相关专题文档中的相对链接
```

新增强制规范时，还必须同步检查：

```text
AGENTS.md
docs/standards/ai-coding-agent-instructions.md
docs/local-customizations.md
```

## 5. 文档状态与元数据

除纯操作教程和模块 README 外，新增或实质修改的文档应在文件顶部声明：

```yaml
---
status: canonical | supporting | report
owner: module-or-domain
last_verified: YYYY-MM-DD
source_paths:
  - path/to/source
---
```

状态含义：

- `canonical`：某一架构、契约或规则的唯一当前事实源。同一主题只能有一个 canonical 文档。
- `supporting`：实现细节、接入说明或操作指南，必须链接到对应 canonical 文档，不得另立冲突规则。
- `report`：某个日期的评测、审计或问题分析。结论可能随代码变化，必须保留验证日期和事实来源。

`owner` 使用模块或领域名，例如 `xiaozhi-server`、`manager-api`、`security`、`docs`。`source_paths` 只列支撑文档结论的主要源码、配置或脚本路径，不罗列整个仓库。

动态数字（文件数、代码行数、测试数、迁移数、provider 数量）应由脚本生成或在报告中标注验证日期，不得在多个入口手工复制。端口、协议、认证默认值等运行事实修改后，必须同步更新对应 canonical 文档。

## 6. 移动与重命名

移动文档前先搜索引用：

```bash
rg "old-file-name|old/path" README.md docs main deploy scripts
```

移动后必须检查：

```text
Markdown 相对链接
图片相对路径
README 或 docs/README.md 索引
AI 必读规则是否仍指向正确位置
```

不要把大规模文档搬迁和业务代码改动混在同一次任务中。涉及官方旧文档路径的移动，应优先在独立文档整理任务中完成。

## 7. 删除与归档

删除文档前必须完成：

1. 使用 `rg` 搜索文件名、相对路径、标题和关键锚点在 `README.md`、`AGENTS.md`、`docs/`、`main/`、`deploy/`、`scripts/`、`.github/` 中的引用。
2. 核对文档引用的图片、下载地址、脚本和配置，确认是否仍有其他文档依赖。
3. 将仍有效且没有其他事实源的内容迁移到对应 canonical 或 supporting 文档。
4. 修复全部引用后再删除，并重新执行链接检查。

已过期、与当前实现冲突或被完整替代的文档直接删除，由 Git 历史承担追溯职责；不要建立永久的 `archive/` 或保留只写“已迁移”的空壳页面。只有仍具当前排障或审计价值的阶段性材料才进入 `docs/reports/`，并标为 `report`。

以下情况不得删除：仍是外部稳定入口、仍被自动化脚本读取、包含未迁移的唯一安全/恢复步骤，或无法确认其替代入口。遇到这些情况应先补齐替代文档和兼容策略。

## 8. 质量检查

文档变更至少检查：

```bash
rg -n 'old-file-name|old/path' README.md AGENTS.md docs main deploy scripts .github
```

同时验证：

- Markdown 相对链接指向存在的文件，目录链接具有 README。
- 页面只有一个 H1，标题层级不跳级。
- 非报告文档能够从 `README.md` 或 `docs/README.md` 进入，不形成孤立页面。
- canonical 文档之间不存在互相冲突的端口、配置优先级、认证或部署口径。
- 示例不包含真实 API Key、Token、密码、内网私有仓库地址或运行产物。
