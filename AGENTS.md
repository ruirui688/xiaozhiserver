# Project Agent Rules

本文件是本仓库的 AI 编程工具入口规则。Codex 在项目根目录新开会话时应先读取并遵守本文件，再按任务读取更详细的项目文档。

本项目是大幅本地定制过的 `xiaozhi-esp32-server`。不要按官方原仓库或通用 Python/Java/Vue 项目习惯直接操作。

## 0. 总原则：先守边界，再写代码

后续所有主智能体、子智能体、审查智能体都必须把本文件当作最高优先级的仓库入口规则。任何“为了快”“只是小改”“先临时生成一下”的理由，都不能绕过本文件。

强制原则：

- 先理解任务边界，再读代码；先确认写入范围，再修改文件。
- 不允许把模型测试、智能助手、插件、MCP、语义控制、配置下发等跨模块能力继续随意塞进既有大文件。
- 不允许把草案文件、占位迁移、未实现 provider、真实密钥或运行产物留在可提交状态。
- 不允许为了通过构建而删除测试、降级验证、静默吞错或改动无关业务逻辑。
- 不允许子智能体之间并行编辑同一文件；集成者必须最终检查 diff 和验证结果。

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

开工后必须明确回答给自己：

```text
本次任务属于哪个模块？
允许写哪些路径？
哪些现有未提交改动不能碰？
需要读哪些专项文档？
需要跑哪些最小验证？
是否涉及数据库、配置、密钥、生产、长期服务或外部模型调用？
```

如果不能回答这些问题，先继续读代码和文档，不要先改文件。

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

模型专业评测、模型配置智能助手、模型接入草案相关任务必须读取：

```text
docs/models/performance_tester.md
docs/architecture/model-benchmark-and-assistant.md
```

修改提示词注入、工具调用、MCP、真实设备智能体运行时上下文时，还必须读取：

```text
docs/architecture/project-architecture.md
docs/architecture/cloud-runtime-architecture.md
docs/architecture/semantic-control-center.md
docs/integrations/prompt-injection-engineering.md
```

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
- 不批量格式化整个模块、整个目录或全仓库。
- 不自动 `stash`、不自动提交。
- 不执行 `git reset --hard`、`git checkout -- <file>` 等破坏性命令。
- 不修改远端配置，除非用户明确要求。
- 不执行 `commit`、`push`、`tag`、部署、生产数据库操作，除非用户明确要求。
- 不删除未跟踪文件，除非能确认它是本次任务产生的草案/缓存/运行产物，或用户明确要求。
- 不把“工作区已有改动”当成自己的改动汇报；最终必须区分本次改动和既有未提交改动。

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

禁止提交或保留在提交范围内的草案特征：

```text
YYYYMMDDHHMM.sql
new_provider.py
NewProvider
TODO: 调用真实模型接口
raise NotImplementedError
pass  # TODO
未登记到 db.changelog-master.yaml 的真实数据库迁移
```

如果这些内容只是智能助手生成的接入草案，必须删除出提交范围；如果要转正，必须补齐真实实现、真实迁移编号、测试和文档。

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

更细的拆分规则：

```text
main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/benchmark/
  Java 模型评测编排 helper，只允许 Java API 子智能体修改

main/xiaozhi-server/core/benchmark/
  Python 模型评测运行器，只允许 Python 子智能体修改

main/manager-web/src/components/ModelBenchmarkDialog.vue
  Web 模型评测弹窗，只允许 Web 子智能体修改

docs/architecture/model-benchmark-and-assistant.md
  模型评测与助手架构文档，只允许文档子智能体修改
```

子智能体硬规则：

- 必须在自己的任务说明里写明“允许写入路径”和“禁止写入路径”。
- 必须先执行开工协议，再读本文件和对应专项文档。
- 只能修改自己负责的路径；发现需要改其它路径时，停止并向集成者说明。
- 不得回滚、覆盖、格式化其它智能体或用户的改动。
- 不得创建占位迁移、空 provider、伪测试或不可运行草案文件。
- 最终回复必须列出实际检查/修改的路径、验证命令、失败原因和残留风险。

集成者硬规则：

- 不盲信子智能体结论，必须最终查看 `git status`、`git diff --stat` 和关键 diff。
- 必须检查是否有草案残留、未登记迁移、敏感信息和跨边界改动。
- 必须统一运行可自动结束的验证命令。
- 必须关闭或结束不再需要的子智能体，不让后台任务悬挂。

## 7. 架构分层硬约束

本项目已经有大量本地定制。后续开发必须优先降低混乱度，不得继续扩大巨型文件。

### 7.1 模型专业评测

模型专业评测必须保持以下边界：

```text
manager-web
  只负责页面交互、弹窗、轮询、结果展示

manager-api
  只负责任务创建、权限、异步编排、调用 xiaozhi-server、结果持久化

xiaozhi-server
  只负责真实 provider 调用、真实智能体运行时桥接、样本执行和指标输出
```

禁止：

- 在 Java 里伪造 provider 测试结果。
- 用字段校验、连通成功冒充真实推理成功。
- 把 ASR 测试降级成只握手或只检查配置。
- 把大量评测 UI 继续塞回 `ModelConfig.vue`。
- 把 ASR/TTS/VLLM/Memory/Intent 新逻辑继续堆进 `runner.py`。
- 把任务上下文、结果映射、HTTP client、持久化逻辑继续堆进 `ModelBenchmarkServiceImpl`。

### 7.2 模型配置智能助手

模型配置智能助手只能辅助接入，不能替代开发流程。

允许：

- 匹配已知 adapter。
- 生成配置草案、缺失字段、协议说明、实现任务。
- 在开发环境写入白名单路径内的草案文件，且必须可审查。

禁止：

- 直接启用模型或标记测试通过。
- 生成或保存真实 API Key、Token、Secret。
- 把协议不兼容的同厂商 adapter 说成可用。
- 在生产环境执行 `apply-artifacts` 写源码或数据库草案。
- 覆盖已有文件，除非用户明确要求且 diff 可审查。

### 7.3 文件复杂度约束

后续新增功能时，优先新增小的职责类/组件/模块，不要继续膨胀以下文件：

```text
main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelBenchmarkServiceImpl.java
main/xiaozhi-server/core/benchmark/runner.py
main/manager-web/src/views/ModelConfig.vue
main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelAssistantServiceImpl.java
```

如果确实必须修改这些文件，必须同时说明：

```text
为什么不能放到 helper/module/component？
是否会增加职责混杂？
有没有同步更新架构文档？
```

## 8. 验证优先级

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

验证规则：

- 修改 Python 核心服务，必须使用 Conda 验证；未验证就明确说“未在项目 Conda 运行时验证”。
- 修改 Java API，至少运行 `mvn test`；如果项目默认跳过测试，需要说明“编译通过但测试被 POM 跳过”。
- 修改 Web，至少运行 `npm run build`。
- 修改数据库迁移，必须检查 changelog 文件和 `db.changelog-master.yaml`。
- 修改文档索引，必须检查相对链接或至少说明未自动检查的原因。
- 不允许把外部模型真实调用、长期服务启动、生产部署当作默认验证；需要用户要求或任务确实需要。

## 9. 输出要求

最终回复必须说明：

- 改了哪些文件。
- 执行了哪些验证。
- 哪些验证未执行及原因。
- 是否发现现有未提交改动或残留风险。
- 是否清理了草案残留、未跟踪运行产物或敏感信息。
- 如果使用多智能体，说明每个子智能体负责的路径和最终集成结果。

回答要简洁、直接，不要把无关文档或命令输出整段复制给用户。
