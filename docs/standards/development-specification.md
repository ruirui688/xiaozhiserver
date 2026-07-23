---
status: canonical
owner: engineering
last_verified: 2026-07-23
source_paths:
  - AGENTS.md
  - scripts/dev
  - main/
  - deploy/
---

# 开发与交付规范

这份规范只保留人工开发时真正需要执行的规则。AI 工具以根目录 `AGENTS.md` 为唯一入口；官方同步细节见[上游同步策略](./upstream-sync-policy.md)，生产发布只看[ACR 生产部署](../deployment/private-docker-release.md)。

## 1. 仓库与分支

- `origin` 是允许推送的私有仓库，`upstream` 只用于读取官方更新；操作前以 `git remote -v` 实际输出为准。
- `main` 是稳定分支；功能、修复和官方同步分别使用 `feature/*`、`sync/upstream-YYYYMMDD`。
- 不在有无关改动的工作区里批量格式化、同步上游或发布；不自动 stash，不使用 `git reset --hard`。
- 只提交本任务文件。提交类型使用 `feat|fix|docs|refactor|test|chore|deploy|security`。

最短开发流程：

```bash
git status --short --branch
git remote -v
# 修改和验证
git diff --check
git status --short
git commit -m "type: description"
git push origin <当前分支>
```

## 2. 模块边界与运行时

| 模块 | 入口 | 最小验证 |
| --- | --- | --- |
| Python 实时服务 | `main/xiaozhi-server/app.py` | `conda run -n xiaozhi-esp32-server python -m compileall .` 和 pytest |
| Java 管理 API | `main/manager-api/` | `mvn test` |
| Web 智控台 | `main/manager-web/` | `npm run test:unit && npm run build` |
| Mobile / 微信 | `main/manager-mobile/` | `pnpm test:unit && pnpm type-check` 加目标平台构建 |
| 脚本 | `scripts/` | `bash -n <修改的脚本>` |
| Compose | `deploy/production/` | `docker compose ... config --quiet` |

`main/xiaozhi-server` 必须使用 Conda 环境 `xiaozhi-esp32-server`。禁止用系统 `python`、`python3`、`pip` 或 `pytest` 冒充项目验证。统一开发入口：

```bash
scripts/dev doctor
scripts/dev start all
scripts/dev status
scripts/dev logs server -f
scripts/dev stop
```

不要继续膨胀 `core/connection.py`、`ModelConfigServiceImpl.java`、`MemoryServiceImpl.java`、`LocalKnowledgeAdapter.java`、`ModelConfig.vue`、`roleConfig.vue` 和 `KnowledgeFileUpload.vue`；新职责先拆到领域组件。

## 3. 配置、密钥与运行数据

配置只有四层：

| 层 | 位置 | Git 规则 |
| --- | --- | --- |
| 默认模板 | `config.yaml`、`application.yml` 等 | 仅安全默认值和占位符 |
| 脱敏示例 | `config/examples/`、`.env.example/.template/.sample` | 允许提交 |
| 环境私有配置 | `runtime/config/`、兼容路径 `data/.config.yaml`、部署平台密钥 | 禁止提交 |
| 运行产物 | `runtime/logs|run|tmp|uploads|cache|config` | 仅提交 `.gitkeep` |

新增配置先决定安全默认值，再补脱敏示例；真实 API Key、Token、密码、客户数据和私网地址只能进入私有配置或部署密钥。智能体或用户第三方凭据必须使用 `XIAOZHI_CREDENTIAL_ENCRYPTION_KEY` 加密；用户共享凭据保存到密文字段，不得写入智能体映射参数或通过接口回显。

MySQL、Redis、上传、模型、日志、聊天、设备和声纹数据属于运行数据，不进入 Git。生产数据只做受控备份，不直接复制到开发机。Docker、BuildKit、pip、apt 缓存和本地模型是构建资产，未经明确同意不得清理。

## 4. 数据库

所有结构变化都通过 Liquibase：

```text
main/manager-api/src/main/resources/db/changelog/
main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
```

- 新迁移立即登记到 master；已执行 changeSet 的 id、author、内容和路径禁止修改。
- 修正历史效果时新增补偿迁移，不改旧 SQL；生产发布前先备份数据库。
- 数据库 dump、真实密码、Token 和客户数据不得提交。

## 5. 安全检查

- 新端点必须明确认证方式和资源所有权，不能信任请求体里的 `agentId`、`deviceId` 或 workspace。
- 文件上传同时检查扩展名、MIME 和路径边界；用户 URL 必须做 SSRF 验证。
- 日志不得打印 Authorization、API Key、Token、密码、记忆正文或客户数据。
- 生产使用 TLS、强制外部提供 MySQL/Redis 密码，容器保持非 root 和最小权限。
- 修改认证、授权、上传、MCP URL 或密钥路径时，增加对应失败与越权测试。

## 6. 上游同步与发布

官方更新只能在独立 `sync/upstream-*` 分支按[上游同步策略](./upstream-sync-policy.md)人工合并；不得把本地目录替换成官方目录，也不得向 `upstream` 推送。

生产只走[ACR 镜像发布流程](../deployment/private-docker-release.md)。源码提交、测试通过、镜像构建和生产部署是四个不同状态，报告时必须明确当前完成到哪一步。

## 7. 完成标准

交付前必须说明：

- 修改文件和行为变化；
- 已执行测试及结果，未执行项及原因；
- 数据库、配置、安全和生产影响；
- 工作区是否还有既有改动、草案、敏感信息或运行产物；
- 提交哈希和实际推送目标（如果用户要求提交/推送）。
