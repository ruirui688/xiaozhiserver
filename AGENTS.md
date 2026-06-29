# AI Agent Rules

本文件是本仓库所有 AI 编程工具的**唯一入口规则**。Codex、Cursor、Copilot、Claude Code 或任何其他 AI 工具在项目根目录新开会话时，必须首先读取并遵守本文件。

本项目是大幅本地定制过的 `xiaozhi-esp32-server`。**不要按官方原仓库或通用 Python/Java/Vue 项目习惯直接操作。**

---

## 0. 项目速览

```text
xiaozhi-esp32-server = 端云协同语音智能体平台

ESP32 设备 ──WebSocket──→ xiaozhi-server (Python, port 8000/8003)
                                ├── VAD → ASR → SemanticCenter → LLM → TTS → 音频回传
                                ├── 12种 ASR / 17种 TTS / 9种 LLM provider
                                ├── 15个功能插件 / 3类 MCP 链路 / 语义控制中枢 / 长期记忆
                                └── 通过 HTTP 从 manager-api 拉取配置

manager-web / manager-mobile ──HTTP──→ manager-api (Java, port 8002)
                                          ├── MySQL + Redis + Liquibase (143 migrations)
                                          ├── 智能体 / 模型 / 设备 / 知识库 / OTA / 声纹 / 记忆管理
                                          └── 模型评测编排 (调用 xiaozhi-server benchmark API)
```

### 模块清单

| 模块 | 语言/框架 | 文件数 | 职责 |
|------|----------|--------|------|
| `main/xiaozhi-server/` | Python 3.10, aiohttp | 218 .py | 实时语音服务：WebSocket、VAD/ASR/LLM/TTS、插件、MCP |
| `main/manager-api/` | Java 21, Spring Boot 3.4 | 406 .java | 管理 API：配置下发、模型管理、评测编排、权限、记忆 |
| `main/manager-web/` | Vue 2, Element UI | 183 .vue (26 views, 34 components) | Web 智控台 |
| `main/manager-mobile/` | Vue 3, uni-app, TypeScript | 152 .vue | 移动端智控台 (H5/iOS/Android/微信) |
| `docs/` | 64 .md | — | 架构、部署、开发、集成、MCP、模型、规范 |
| `deploy/` / `scripts/` | 12 Shell scripts | — | 本地开发入口 + ACR 生产部署 |

---

## 1. 开工协议 — 必须执行

在修改任何文件前，**必须**完成：

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

**必须能回答以下问题后再动手**：

```text
本次任务属于哪个模块？
允许写哪些路径？
需要读哪些专项文档？
需要跑哪些最小验证？
是否涉及数据库、配置、密钥、生产、长期服务或外部模型调用？
```

---

## 2. 任务路由 — 按类型读对应文档

```
所有任务
  → AGENTS.md + README.md + docs/README.md
  → docs/standards/development-specification.md
  → docs/standards/ai-coding-agent-instructions.md
  → docs/local-customizations.md

Python 核心服务
  → main/xiaozhi-server/requirements.txt
  → core/connection.py (2060行中央状态机，修改前必须读)
  → 相关 core/handle/、core/providers/、core/semantic/、plugins_func/

Java 管理 API
  → main/manager-api/README.md
  → main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml

Web 管理后台
  → main/manager-web/README.md

移动端
  → main/manager-mobile/README.md

模型评测 / 配置助手
  → docs/architecture/model-benchmark-and-assistant.md
  → docs/models/performance_tester.md

提示词注入 / 工具调用 / MCP / 语义控制
  → docs/architecture/project-architecture.md
  → docs/architecture/cloud-runtime-architecture.md
  → docs/architecture/semantic-control-center.md
  → docs/architecture/prompt-injection-engineering.md
  → docs/architecture/function-plugin-architecture.md

长期记忆
  → docs/architecture/long-term-memory-maintenance.md
  → main/manager-api/.../modules/memory/ (25个文件)

配置 / 运行产物
  → docs/standards/config-runtime-policy.md

数据库
  → main/manager-api/src/main/resources/db/changelog/
  → main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml

部署 / Docker
  → docs/deployment/private-docker-release.md (唯一生产入口)
  → deploy/README.md
  → scripts/README.md

官方同步
  → docs/standards/upstream-sync-policy.md
  → docs/local-customizations.md

文档治理
  → docs/standards/documentation-policy.md
```

---

## 3. Python 运行时硬规则

`main/xiaozhi-server` **必须**使用 Conda 环境 `xiaozhi-esp32-server`：

```bash
conda run -n xiaozhi-esp32-server python ...
conda run -n xiaozhi-esp32-server pytest
```

**禁止**直接使用：`python`、`python3`、`pip`、`pytest`

本地开发统一入口（已内置 Conda 约束）：

```bash
scripts/dev doctor
scripts/dev start server
scripts/dev status
scripts/dev logs server -f
scripts/dev stop
```

如果 Conda 环境不可用，**必须如实说明"未在项目 Conda 运行时验证"**，不得用系统 Python 冒充。

---

## 4. 架构分层硬约束

后续开发必须优先降低混乱度，**不得继续膨胀以下巨型文件**：

| 文件 | 当前规模 | 替代方向 |
|------|---------|---------|
| `core/connection.py` | 1963行 | 新逻辑放入 `core/handle/` 子模块 |
| `ModelConfigServiceImpl.java` | 2542行 | 拆入 provider 子目录和 helper 类 |
| `ModelConfig.vue` | 1889行 | 拆入独立组件和弹窗（已有 `ModelEditDialog.vue`、`ModelBenchmarkDialog.vue`） |
| `ModelBenchmarkDialog.vue` | 2817行 | 拆入 benchmark 子组件 |
| `ModelAssistantServiceImpl.java` | 793行 | 拆入独立 agent 类 |

如果必须修改这些文件，需要说明：**为什么不能拆？是否增加职责混杂？是否同步更新架构文档？**

### 模型评测边界

```
manager-web      → 只负责页面交互、弹窗、轮询、展示
manager-api      → 只负责异步编排、权限、调用 xiaozhi-server、持久化
xiaozhi-server   → 只负责真实 provider 调用、样本执行、指标输出
```

禁止：Java 伪造结果、ASR 降级成握手检查、把评测 UI 塞回 ModelConfig.vue。

### 模型配置助手边界

允许：匹配 adapter、生成配置草案、写入白名单路径的草案文件。
禁止：直接启用模型、生成/保存真实 API Key、协议不兼容硬说兼容、生产环境写源码。

### 工具注册门控规则

LLM 可用的 function list 由两类工具源汇聚而成，它们的注册机制门控方式不同：

| 工具源 | 注册方式 | 门控 |
|--------|---------|------|
| Server Plugin (`plugins_func/functions/`) | `@register_function` 装饰器 | 仅 `Intent.<selected>.functions` 中列出的函数会被 `ServerPluginExecutor.get_tools()` 选取 |
| Server MCP | 外部 MCP 服务器 | 仅当配置了 MCP 端点且连接成功 |
| **Device IoT** | ESP32 设备 WebSocket 上报 `{"type":"iot","descriptors":[...]}` | `iot_handler.py` 中 `SERVER_GATED_IOT_DEVICES` 映射控制。设备名在映射中时须对应服务端能力已启用才会注册；不在映射中的设备名默认放行（向后兼容） |
| Device MCP | ESP32 设备 MCP 协议 `tools/list` | 无条件注册（设备固件自行控制） |
| MCP Endpoint | 外部 MCP 接入点 | 仅当配置了 `mcp_endpoint` URL |

**关键原则**：设备端上报的 IoT 描述符不能绕过服务端配置直接成为 LLM 工具。
新增需要服务端显式启用的 IoT 设备类型时，在 `SERVER_GATED_IOT_DEVICES` 中添加条目：

```python
# core/providers/tools/device_iot/iot_handler.py
SERVER_GATED_IOT_DEVICES = {
    "hass": {
        "config_check": lambda conn: bool(
            conn.config.get("plugins", {}).get("home_assistant")
            or conn.config.get("plugins", {}).get("hass_get_state")
        ),
        "label": "Home Assistant",
    },
    # 新增设备类型在此添加
}
```

---

## 5. Git 与工作区安全

### 远端约定

```text
origin    = 私有仓库 (允许 push)
upstream  = 官方仓库 (只允许 fetch/diff/sync，禁止 push)
```

执行 push 前**必须以 `git remote -v` 实际结果确认目标**。

### 不可破坏规则

- 不回滚、覆盖、格式化与当前任务无关的文件
- 不批量格式化整个模块或全仓库
- 不自动 `stash`、不自动 `commit`
- 不执行 `git reset --hard` 等破坏性命令
- 不删除未跟踪文件（除非确认是草案/缓存/运行产物）
- 不把"工作区既有改动"当成自己的改动

---

## 6. 配置与敏感信息边界

```text
可以提交       main/xiaozhi-server/config.yaml（默认配置）
              config/examples/*（脱敏示例）
              .env.example / .env.template / .env.sample
              runtime/.gitkeep

禁止提交       main/xiaozhi-server/data/.config.yaml（私有配置）
              .env / .env.local / .env.dev / .env.prod
              .config.yaml / .secrets.yaml / .private_config.yaml
              API Key / Token / Secret / 数据库密码 / Redis 密码
              runtime/*（除 .gitkeep 外）
              模型大文件 (model.pt, sherpa-onnx*)
              生产数据库 dump
```

### 禁止提交的草案特征

```text
YYYYMMDDHHMM.sql   (非标准迁移命名)
new_provider.py    (占位 provider)
NewProvider         (未实现的 provider 类名)
TODO: 调用真实模型接口  (占位注释)
raise NotImplementedError  (未实现方法)
pass  # TODO         (占位代码)
未登记到 db.changelog-master.yaml 的真实数据库迁移
```

---

## 7. 数据库规则

所有表结构变化必须通过 Liquibase：

```text
路径:  main/manager-api/src/main/resources/db/changelog/
入口:  main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
```

- 新增迁移 SQL → 立即登记到 master.yaml
- 已发布迁移 → 禁止修改
- 已部署 changeSet id → 禁止修改
- 生产发布前 → 必须备份数据库
- 禁止把生产数据库 dump 提交到 Git

---

## 8. 验证矩阵

> 优先级：静态检查 > 单元测试 > 编译/构建 > 长期服务（仅用户要求时启动）

| 模块 | 命令 | 条件 |
|------|------|------|
| Python 语法 | `cd main/xiaozhi-server && conda run -n xiaozhi-esp32-server python -m compileall .` | 必须 |
| Python 测试 | `conda run -n xiaozhi-esp32-server pytest` | 如果测试存在 |
| Java 测试 | `cd main/manager-api && mvn test` | 必须（修改 Java 时） |
| Web 构建 | `cd main/manager-web && npm run build` | 必须（修改 Web 时） |
| Mobile 构建 | `cd main/manager-mobile && pnpm build:h5` | 必须（修改 Mobile 时） |
| Shell 语法 | `bash -n scripts/dev scripts/start-dev.sh` | 修改脚本时 |
| 环境体检 | `scripts/dev doctor` | 推荐 |

**验证规则**：
- 修改 Python → 必须 Conda 验证，未验证就说"未在项目 Conda 运行时验证"
- 修改 Java → 至少 `mvn test`，如果 POM 跳过测试要说清楚
- 修改 Web → 至少 `npm run build`
- 修改数据库 → 检查 changelog 和 master.yaml
- 禁止把外部模型真实调用、长期服务启动、生产部署当作默认验证

---

## 9. 多智能体协作规则

### 文件归属

```
docs/                        文档和索引
scripts/                     本地开发脚本和工具
deploy/                      部署资产
main/xiaozhi-server/         Python 实时服务
main/manager-api/            Java API 和数据库迁移
main/manager-web/            Web 管理后台
main/manager-mobile/         移动端
```

### 子智能体规则

- 必须在任务说明里写明"允许写入路径"和"禁止写入路径"
- 必须先执行开工协议，再读对应专项文档
- 只能修改自己负责的路径；发现需要改其他路径时停止并说明
- 不得回滚、覆盖、格式化其他智能体或用户的改动
- 不得创建占位迁移、空 provider、伪测试或不可运行草案
- 最终回复必须列出：实际检查/修改路径、验证命令、失败原因、残留风险

### 集成者规则

- 不盲信子智能体结论
- 必须最终查看 `git status`、`git diff --stat` 和关键 diff
- 必须检查是否有草案残留、未登记迁移、敏感信息和跨边界改动
- 必须统一运行可自动结束的验证命令

---

## 10. 提交规范

```text
feat      新功能
fix       修复问题
docs      文档变更
refactor  重构
test      测试
chore     构建、依赖、脚本等杂项
deploy    部署相关
```

提交信息格式：`type: description`（如 `feat: add voice benchmark flow`）

- 结尾追加 `Co-Authored-By: Claude <noreply@anthropic.com>`

---

## 11. 输出要求

完成任务后必须说明：

```text
- 改了哪些文件
- 执行了哪些验证
- 哪些验证未执行及原因
- 是否发现现有未提交改动或残留风险
- 是否清理了草案残留、未跟踪运行产物或敏感信息
- 如果使用多智能体，说明每个子智能体负责的路径和最终集成结果
```

回答要简洁、直接，不要整段复制无关文档或命令输出。

---

## 12. 快速参考

### 端口

```
8000  xiaozhi-server WebSocket
8003  xiaozhi-server HTTP / OTA / 视觉
8002  manager-api + manager-web
3306  MySQL
6379  Redis
```

### 关键路径

```text
Python 入口:        main/xiaozhi-server/app.py
配置文件:           main/xiaozhi-server/config.yaml
私有配置:           main/xiaozhi-server/data/.config.yaml
提示词模板:         main/xiaozhi-server/agent-base-prompt.txt
中央状态机:         main/xiaozhi-server/core/connection.py (1963行)
语义控制中枢:       main/xiaozhi-server/core/semantic/ (9个文件, 741行)
语音处理:           main/xiaozhi-server/core/handle/ (18个文件, 1708行)
功能插件:           main/xiaozhi-server/plugins_func/functions/ (15个)
模型评测运行器:     main/xiaozhi-server/core/benchmark/runner.py
Java 评测编排:      .../model/service/impl/ModelBenchmarkServiceImpl.java (262行)
长期记忆服务:       .../memory/service/impl/MemoryServiceImpl.java (844行)
数据库迁移:         main/manager-api/src/main/resources/db/changelog/ (143个迁移)
本地开发入口:       scripts/dev (748行)
生产部署入口:       docs/deployment/private-docker-release.md
生产部署包:         deploy/production/
```

### 本地开发常用命令

```bash
scripts/dev doctor           # 环境体检
scripts/dev start all        # 启动全部服务
scripts/dev status           # 查看状态
scripts/dev logs server -f   # 跟踪 Python 日志
scripts/dev stop             # 停止全部服务
```

### 验证常用命令

```bash
# Python
cd main/xiaozhi-server
conda run -n xiaozhi-esp32-server python -m compileall .
conda run -n xiaozhi-esp32-server pytest

# Java
cd main/manager-api && mvn test

# Web
cd main/manager-web && npm run build

# Mobile
cd main/manager-mobile && pnpm build:h5

# Shell
bash -n scripts/dev scripts/start-dev.sh
```
