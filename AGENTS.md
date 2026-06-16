# Project Agent Rules

本文件是本仓库的 AI 编程工具入口规则。Codex 在项目根目录新开会话时应先读取并遵守本文件，再按任务读取更详细的项目文档。

本项目是大幅本地定制过的 `xiaozhi-esp32-server`。不要按官方原仓库或通用 Python/Java/Vue 项目习惯直接操作。

## 1. 开工协议

在修改任何文件前，必须先做这几件事：

```bash
git status --short --branch
git remote -v
```

然后读取基础文档：

```text
README.md
docs/README.md
docs/standards/development-specification.md
docs/standards/ai-coding-agent-instructions.md
docs/local-customizations.md
```

只按任务增读专项文档，不要为了一个小修改批量读取全部 `docs/`。

## 2. 任务文档路由

按任务类型继续读取：

```text
文档整理:
  docs/standards/documentation-policy.md

本地开发/启动/环境:
  docs/development/local-dev-workflow.md
  docs/development/ubuntu-source-development-environment.md

Python 核心服务:
  main/xiaozhi-server/requirements.txt
  相关 core/、plugins_func/、tests/ 文件

Java 管理 API:
  main/manager-api/README.md
  main/manager-api/src/main/resources/db/changelog/
  main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml

Web 管理后台:
  main/manager-web/README.md

移动端:
  main/manager-mobile/README.md

数字人:
  main/digital-human/README.md

配置/运行产物:
  docs/standards/config-runtime-policy.md

官方上游同步:
  docs/standards/upstream-sync-policy.md

全项目代码梳理:
  docs/architecture/current-code-map.md
```

涉及 MCP、插件、提示词、语义控制、模型、设备、部署时，按 `docs/README.md` 对应分类读取专项文档。

## 3. Python 运行时硬规则

`main/xiaozhi-server` 必须使用项目 Conda 环境：

```bash
conda run -n xiaozhi-esp32-server python ...
```

禁止直接使用系统命令验证或启动 `main/xiaozhi-server`：

```text
python
python3
pip
pytest
```

正确示例：

```bash
cd main/xiaozhi-server
conda run -n xiaozhi-esp32-server python -m compileall .
conda run -n xiaozhi-esp32-server pytest
```

本地服务统一入口：

```bash
scripts/dev doctor
scripts/dev start server
scripts/dev status
scripts/dev logs server -f
scripts/dev stop server
```

`scripts/dev` 已经拒绝退回系统 Python。如果 Conda 路径特殊，必须显式指定：

```bash
XIAOZHI_SERVER_PYTHON=/path/to/env/bin/python scripts/dev start server
```

如果目标 Conda 环境不可用，必须如实说明“未在项目 Conda 运行时验证”，不得用系统 Python 冒充验证结果。

## 4. Git 与工作区安全

当前工作区可能已有用户未提交改动。必须遵守：

- 不回滚、覆盖、格式化与当前任务无关的文件。
- 不自动 `stash`、不自动提交。
- 不执行 `git reset --hard`、`git checkout -- <file>` 等破坏性命令。
- 不修改远端配置，除非用户明确要求。
- 不执行 `commit`、`push`、`tag`、部署、生产数据库操作，除非用户明确要求。

远端约定：

```text
origin    私有仓库，允许按授权 push
upstream  官方仓库，只用于 fetch / diff / sync
```

执行任何 push 前，必须以 `git remote -v` 的实际结果确认目标。

## 5. 配置与敏感信息

禁止提交真实环境私有配置、密钥、Token、数据库密码、Redis 密码、生产地址和运行数据。

重点边界：

```text
main/xiaozhi-server/config.yaml        默认配置，可以提交
main/xiaozhi-server/data/.config.yaml  私有配置，禁止提交
config/examples/                       脱敏示例，可以提交
runtime/                               运行产物，只提交 .gitkeep
```

数据库变更必须通过 `main/manager-api/src/main/resources/db/changelog/` 管理，并同步检查 `db.changelog-master.yaml`。

## 6. 多智能体协作规则

使用多智能体时必须按文件归属拆分，避免并行编辑同一文件：

```text
docs/                  文档和索引
scripts/               本地开发脚本和工具
main/xiaozhi-server/   Python 实时服务
main/manager-api/      Java API 和数据库迁移
main/manager-web/      Web 管理后台
main/manager-mobile/   移动端
main/digital-human/    数字人模块
```

子智能体必须说明自己检查或修改了哪些路径。集成者负责最终 diff、验证和冲突处理。

## 7. 验证优先级

优先运行可自动结束的验证命令。

常用验证：

```bash
bash -n scripts/dev scripts/start-dev.sh
scripts/dev doctor
```

Python：

```bash
cd main/xiaozhi-server
conda run -n xiaozhi-esp32-server python -m compileall .
conda run -n xiaozhi-esp32-server pytest
```

Java：

```bash
cd main/manager-api
mvn test
```

Web：

```bash
cd main/manager-web
npm run build
```

Mobile：

```bash
cd main/manager-mobile
pnpm build:h5
```

只有用户要求或任务确实需要时，才启动长期运行服务。

## 8. 输出要求

最终回复必须说明：

- 改了哪些文件。
- 执行了哪些验证。
- 哪些验证未执行及原因。
- 是否发现现有未提交改动或残留风险。

回答要简洁、直接，不要把无关文档或命令输出整段复制给用户。
