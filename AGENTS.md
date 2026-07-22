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
                                          ├── MySQL + Redis + Liquibase
                                          ├── 智能体 / 模型 / 设备 / 知识库 / OTA / 声纹 / 记忆管理
                                          └── 模型评测编排 (调用 xiaozhi-server benchmark API)
```

### 模块清单

| 模块 | 语言/框架 | 职责 |
|------|----------|------|
| `main/xiaozhi-server/` | Python 3.10, aiohttp | 实时语音服务：WebSocket、VAD/ASR/LLM/TTS、插件、MCP |
| `main/manager-api/` | Java 21, Spring Boot 3.4.4 | 管理 API：配置下发、模型管理、评测编排、权限、记忆、知识库 |
| `main/manager-web/` | Vue 2, Element UI | Web 智控台 |
| `main/manager-mobile/` | Vue 3, uni-app, TypeScript | 移动端智控台 (H5/iOS/Android/微信) |
| `docs/` | Markdown | 架构、部署、开发、集成、MCP、模型、安全、规范 |
| `deploy/` / `scripts/` | Shell / Compose | 本地开发入口 + ACR 生产部署 |

模块数量、测试数量、迁移数量和热点文件行数统一查看 `docs/generated/repository-facts.md`，禁止在本文件重复维护动态数字。

### 安全态势

项目已完成 2026-07 全量安全审计与修复：
- WebSocket 认证**默认开启**，OTA 端点有速率限制
- CORS origin-aware 模式，Knife4j 生产需认证
- 生产部署**必须**配置 TLS 反向代理 + MySQL/Redis 强密码
- API key 日志已脱敏，SSRF MCP 端点有私有 IP 拦截
- 详见 §13 安全开发规则

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
是否涉及认证、授权、加密、日志脱敏或安全敏感路径？
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
  → core/connection.py (中央状态机，修改前必须读)
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
  → docs/architecture/device-interaction-chain.md
  → docs/architecture/mcp-chains.md

长期记忆
  → docs/architecture/long-term-memory-maintenance.md
  → docs/architecture/memory-system-chain.md
  → main/manager-api/.../modules/memory/ (25个文件)

知识库 / RAG
  → docs/architecture/knowledge-base-chain.md
  → docs/models/local-knowledge-integration.md
  → main/manager-api/.../modules/knowledge/

认证 / 授权 / 安全
  → main/manager-api/.../modules/security/config/ShiroConfig.java
  → main/manager-api/.../modules/security/secret/ServerSecretFilter.java
  → main/xiaozhi-server/core/utils/auth.py
  → main/xiaozhi-server/core/websocket_server.py

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

| 文件 | 替代方向 |
|------|---------|
| `core/connection.py` | 音频初始化已拆至 `core/handle/audioInitializer.py`，报告线程已拆至 `reportThreadManager.py`，关闭生命周期已拆至 `core/session/` |
| `ModelConfigServiceImpl.java` | 模型测试已拆至 `ModelTestServiceImpl.java`，继续通过 tester 执行器分域 |
| `ModelBenchmarkDialog.vue` | 已拆至 `BenchmarkConfigPanel.vue` + `BenchmarkResultPanel.vue` |
| `MemoryServiceImpl.java` | 已拆至维护、查询服务，维护输出解析继续下沉到独立组件 |
| `game_session.py` | 保持 thin re-export，具体实现位于 `core/game/` |
| `ModelAssistantServiceImpl.java` | 766行 | 草案代码已清理 |
| `ModelConfig.vue` | 1889行 | 测评 UI 已拆至 `ModelBenchmarkDialog.vue` |
| `roleConfig.vue` | 610行 | 编辑弹窗已拆至 `role/RoleEditDialog.vue` + `RoleTestDialog.vue` |
| `LocalKnowledgeAdapter.java` | 1658行 | 本地 KB 引擎（含向量嵌入 + LLM 分块） |
| `KnowledgeFileUpload.vue` | 1260行 | 已拆至 `knowledge/FileUploadPanel.vue` + `FilePreviewDialog.vue` |

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

LLM 可用的 function list 由五类工具源汇聚而成，注册优先级（高→低）：

| 优先级 | 工具源 | 注册方式 | 门控 |
|--------|--------|---------|------|
| 1 (最高) | Server Plugin | `@register_function` 装饰器 | `Intent.<selected>.functions` 白名单 |
| 2 | Server MCP | 本地 MCP 服务器 | MCP 端点配置且连接成功 |
| 3 | Device IoT | 设备 WebSocket 上报 descriptors | `SERVER_GATED_IOT_DEVICES` 门控映射 |
| 4 | Device MCP | 设备 MCP `tools/list` | 设备 `features.mcp=true` |
| 5 (最低) | MCP Endpoint | 外部 MCP 接入点 | `mcp_endpoint` URL 配置且通过 SSRF 验证 |

**冲突解决**：同名工具先注册者保留（Server Plugin 最高优先级），后注册者跳过并记录 warning。

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

### 认证与授权边界

```
WebSocket 设备连接 → auth.enabled (默认开启) → JWT token 验证 → 设备白名单旁路
HTTP API (Python)  → origin-aware CORS → Bearer token (原始共享密钥，内网可信)
Java API           → Shiro JWT Filter → @RequiresPermissions 注解
Server-to-Server   → ServerSecretFilter (共享密钥, /config/**)
匿名端点           → /ota/**, /user/login, /user/register, /user/captcha
```

**修改认证相关代码时必须检查**：
- 是否新增了匿名端点？→ 必须在 ShiroConfig 中显式声明为 `anon`
- 是否修改了 ServerSecretFilter 覆盖范围？→ 回顾所有 `/config/**` 路径
- 是否在日志中打印了 Authorization header 或 API key？→ 必须脱敏

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

### 构建缓存保护

Docker / BuildKit / pip / apt 缓存是本项目镜像构建速度的关键资产。没有用户明确授权，任何 AI 工具和自动化脚本都**禁止**清理或删除以下缓存与镜像：

```text
Docker build cache / BuildKit cache
docker builder prune / docker buildx prune / docker system prune / docker image prune
pip cache / apt cache
xiaozhi-server-base-common:*
xiaozhi-server-base-lite:*
xiaozhi-server-base-funasr:*
旧兼容 xiaozhi-server-base:*
main/xiaozhi-server/models/SenseVoiceSmall/model.pt
```

允许查看缓存、复用缓存、按 profile 新增缓存标签。需要释放磁盘空间时，必须先说明会删除哪些缓存、会导致哪些构建重新下载依赖，并获得用户明确同意。禁止为了"重新构建干净"擅自删除缓存。

---

## 6. 配置与敏感信息边界

```text
可以提交       main/xiaozhi-server/config.yaml（默认配置，含占位符值）
              config/examples/*（脱敏示例）
              .env.example / .env.template / .env.sample
              runtime/.gitkeep
              application-prod.yml (环境变量占位符，不含真实密钥)

禁止提交       main/xiaozhi-server/data/.config.yaml（私有配置）
              .env / .env.local / .env.dev / .env.prod
              .config.yaml / .secrets.yaml / .private_config.yaml
              API Key / Token / Secret / 数据库密码 / Redis 密码
              runtime/*（除 .gitkeep 外）
              模型大文件 (model.pt, sherpa-onnx*)
              生产数据库 dump
              包含真实密钥的 application-dev.yml
```

### 日志脱敏规则

修改任何涉及 HTTP 请求、WebSocket 连接、API 调用的代码时，**禁止**在日志中打印以下原始值：

```text
Authorization header
api_key / Api-Key / X-Api-Key
access_token / secret_key / private_key
Bearer token
```

必须使用脱敏工具函数（参见 `core/utils/util.py` 或项目已有的脱敏模式）替换为 `***`。

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
- 已执行过的 changeSet 内容、author、id、sqlFile 路径 → 禁止回头修改；Liquibase checksum 不一致会导致 manager-api 拒绝启动
- 需要修正旧迁移效果 → 新增后续迁移补偿；只有兼容历史库启动时才允许补 `validCheckSum`，且必须另建新迁移执行当前期望变更
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
| Web 功能测试 | `cd main/manager-web && npm run test:unit` | 必须（修改 Web 时，构建前执行） |
| Web 构建 | `cd main/manager-web && npm run build` | 必须（修改 Web 时） |
| Mobile 功能测试 | `cd main/manager-mobile && pnpm test:unit` | 必须（修改 Mobile 时，构建前执行） |
| Mobile 构建 | `cd main/manager-mobile && pnpm build:h5` | 必须（修改 Mobile 时） |
| Shell 语法 | `bash -n scripts/dev scripts/start-dev.sh docker-setup.sh` | 修改脚本时 |
| Docker 语法 | `docker compose -f deploy/production/docker-compose.yml config --quiet` | 修改 compose 时 |
| 环境体检 | `scripts/dev doctor` | 推荐 |

**验证规则**：
- 修改 Python → 必须 Conda 验证，未验证就说"未在项目 Conda 运行时验证"
- 修改 Java → 至少 `mvn test`，如果 POM 跳过测试要说清楚
- 修改 Web → 必须先运行 `npm run test:unit`，再运行 `npm run build`
- 修改 Mobile → 必须先运行 `pnpm test:unit`，再运行 `pnpm type-check` 和目标平台构建
- 修改数据库 → 检查 changelog 和 master.yaml
- 修改 Docker/部署 → 检查 compose 文件语法 + bash -n 所有脚本
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

### 安全审查规则（新增）

修改任何涉及以下路径的代码时，集成者必须额外检查：
- 认证逻辑（ShiroConfig, auth.py, websocket_server.py）
- 授权检查（@RequiresPermissions, checkAgentPermission）
- API 密钥处理（日志脱敏, 传输加密, Redis 缓存）
- 文件上传/下载路径（路径穿越检查）
- MCP 端点 URL（SSRF 验证）

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
security  安全加固（认证、授权、加密、脱敏、依赖升级）
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
认证密钥持久化:     main/xiaozhi-server/data/.auth_key
提示词模板:         main/xiaozhi-server/agent-base-prompt.txt
中央状态机:         main/xiaozhi-server/core/connection.py
语义控制中枢:       main/xiaozhi-server/core/semantic/ (8个文件, ~800行)
语音处理:           main/xiaozhi-server/core/handle/ (22个文件, ~2000行)
功能插件:           main/xiaozhi-server/plugins_func/functions/ (15个)
游戏会话:           main/xiaozhi-server/core/game/ (6个文件, 1513行)
MCP 基类:          main/xiaozhi-server/core/providers/tools/base/mcp_client_base.py (122行)
Python 认证:        main/xiaozhi-server/core/utils/auth.py
Python CORS:        main/xiaozhi-server/core/api/base_handler.py (~40行)
模型评测运行器:     main/xiaozhi-server/core/benchmark/runner.py
SSRF 验证:          main/xiaozhi-server/core/utils/util.py (validate_mcp_endpoint)
Provider 验证:      main/xiaozhi-server/core/utils/provider_validator.py
Java 安全配置:      .../modules/security/config/ShiroConfig.java
Java Token 生成:    .../modules/security/oauth2/TokenGenerator.java (SHA-256)
Java 服务端密钥:    .../modules/security/secret/ServerSecretFilter.java
Java 评测编排:      .../model/service/impl/ModelBenchmarkServiceImpl.java (262行)
Java 模型测试:      .../model/service/impl/ModelTestServiceImpl.java
长期记忆服务:       .../memory/service/impl/MemoryServiceImpl.java (491行)
长期记忆维护:       .../memory/service/impl/MemoryMaintenanceServiceImpl.java
长期记忆查询:       .../memory/service/impl/MemoryQueryServiceImpl.java (266行)
本地知识库引擎:     .../knowledge/rag/impl/LocalKnowledgeAdapter.java (1658行)
数据库迁移:         main/manager-api/src/main/resources/db/changelog/
本地开发入口:       scripts/dev (748行)
生产部署入口:       docs/deployment/private-docker-release.md
生产部署包:         deploy/production/
生产 Compose:       deploy/production/docker-compose.yml
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
cd main/manager-web
npm run test:unit
npm run build

# Mobile
cd main/manager-mobile
pnpm test:unit
pnpm type-check
pnpm build:h5
pnpm build:mp-weixin

# Shell
bash -n scripts/dev scripts/start-dev.sh docker-setup.sh

# Docker Compose
docker compose -f deploy/production/docker-compose.yml config --quiet
```

---

## 13. 安全开发规则

以下规则源自 2026-07 全量安全审计（66 个漏洞修复），后续开发**必须**遵守。

### 认证

1. WebSocket 认证**默认开启**。新增 WebSocket 入口时，认证必须是 opt-out（默认需要），不是 opt-in。
2. 所有新增 HTTP 端点必须在 ShiroConfig 中显式声明权限（`anon`/`authc`/`server`）。不得依赖"默认拒绝"。
3. 匿名端点（`/ota/**`, `/user/login` 等）必须有速率限制或防暴力破解措施。
4. Token 生成使用 SHA-256，禁止 MD5/SHA-1。

### 授权

5. 所有 CRUD 操作**必须校验资源所有权**。`agentId`/`deviceId` 不能从请求体直接信任 — 必须验证当前用户是否有权限。
6. ServerSecretFilter 覆盖的 `/config/**` 路径内的端点，也必须有 per-agent 鉴权（不能只依赖共享密钥）。
7. 新增 controller 方法时，检查是否需要 `@RequiresPermissions` 注解。

### 加密与传输

8. 生产部署**必须**配置 TLS 反向代理。`config.yaml` 中的 `websocket` URL 在生产应使用 `wss://`。
9. MySQL/Redis 密码**禁止**设置默认值。docker-compose 中必须使用 `${VAR:?required}` 强制用户设置。
10. 禁止在 Docker Compose 中使用 `seccomp:unconfined`。

### API 密钥处理

11. **禁止**在 INFO 级别日志中打印原始 `Authorization` header、`api_key`、`access_token`。必须脱敏为 `***`。
12. **禁止**将 API key 放在 URL query parameter 中传输（会泄露到代理日志）。
13. **禁止**在代码中硬编码 API key、密码、Token、AppID 等敏感值。使用环境变量或配置文件占位符。
14. `config.yaml` 中的敏感值必须是占位符（如 `你的xxx APIKey`），真实值通过 `data/.config.yaml` 注入。

### 输入验证

15. 所有文件路径参数必须做路径穿越检查（`toRealPath()` + `startsWith(root)`）。
16. 所有用户提供的 URL（MCP endpoint、RAGFlow base_url、天气/新闻 API URL）必须做 SSRF 验证 — 阻止私有 IP、回环地址、链路本地地址。
17. 文件上传必须同时检查扩展名和 MIME 类型（不单独依赖扩展名）。
18. 所有 `@RequestBody` 参数应添加 `@Valid` 注解。

### 依赖管理

19. Python 依赖使用 `==` 精确版本锁定（禁止 `>=` 仅最小版本）。
20. Docker 基础镜像使用具体版本标签（如 `python:3.10-slim`），生产推荐使用 digest 锁定。
21. MCP Server 配置中使用 `npx -y` 的包必须有安全审查记录。生产环境推荐预安装 MCP 服务器包。

### 容器安全

22. 所有 Docker 容器必须有 `cap_drop: [ALL]`，仅 `cap_add` 所需最小权限。
23. 所有 Dockerfile 必须有 `HEALTHCHECK` 指令。
24. `.env*` 文件必须在 `.dockerignore` 中排除。
25. 容器以非 root 用户运行（已实现，新增容器必须延续）。

### 安全验证

26. 修改认证/授权/加密代码时，额外运行：`rg -n "except:" main/xiaozhi-server/core` 检查是否有裸 except 吞掉安全异常。
27. 新增数据库迁移时，检查是否涉及敏感字段（密码、token、secret），确认存储方式安全。
28. 修改 Docker Compose 时，验证：`docker compose config --quiet` 且人工检查是否有弱密码默认值。
