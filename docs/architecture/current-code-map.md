---
status: supporting
owner: architecture
last_verified: 2026-07-12
source_paths:
  - docs/generated/repository-facts.md
  - main/
  - scripts/dev
  - deploy/production/
---

# 当前代码地图

本页提供稳定的源码导航和验证入口。技术版本、文件数量、迁移数量和热点文件行数由[仓库事实](../generated/repository-facts.md)自动生成；不要在本页复制这些易漂移数字。

完整系统边界、数据面与控制面以[项目架构总览](./project-architecture.md)为准。关键运行时序见[运行时链路](./runtime-flows.md)，跨模块数据所有权和配置契约见[数据与契约](./data-and-contracts.md)。

## 模块导航

| 模块 | 职责 | 代码入口 | 开发说明 |
| --- | --- | --- | --- |
| xiaozhi-server | 设备 WebSocket、语音链路、模型与工具运行时 | `main/xiaozhi-server/app.py` | [本地开发工作流](../development/local-dev-workflow.md) |
| manager-api | 配置管理、权限、数据持久化和异步编排 | `main/manager-api/src/main/java/xiaozhi/AdminApplication.java` | [模块 README](../../main/manager-api/README.md) |
| manager-web | Vue 2 Web 智控台 | `main/manager-web/src/main.js` | [模块 README](../../main/manager-web/README.md) |
| manager-mobile | Vue 3 / uni-app 移动智控台 | `main/manager-mobile/src/main.ts` | [模块 README](../../main/manager-mobile/README.md) |

## 关键源码边界

Python 实时服务：

- `core/websocket_server.py`：设备连接、认证和会话创建。
- `core/connection.py`：单设备会话协调，不应继续承接无关职责。
- `core/session/`：关闭期限、资源清理和初始化/关闭竞态协调。
- `core/observability/`：会话级计数、LLM 时延和脱敏关闭摘要。
- `core/handle/`：设备协议与音频消息处理。
- `core/providers/`：VAD、ASR、LLM、TTS、Memory、Intent 和工具 provider。
- `core/semantic/`：确定性语义控制。
- `plugins_func/`：服务端功能插件。

Java 管理 API：

- `modules/config/`：面向 xiaozhi-server 的运行时配置下发。
- `modules/model/`：模型目录、配置、测试和评测编排。
- `modules/agent/`、`modules/device/`：智能体和设备管理。
- `modules/knowledge/`、`modules/memory/`：知识库与长期记忆。
- `modules/security/`：Shiro、JWT 和服务端共享密钥边界。
- `resources/db/changelog/`：Liquibase 唯一数据库迁移入口。

前端：

- Web 页面位于 `main/manager-web/src/views/`，复用领域组件位于 `src/components/`，API 封装位于 `src/apis/`。
- Mobile 页面位于 `main/manager-mobile/src/pages/`，请求层位于 `src/api/` 与 `src/http/`，共享状态位于 `src/store/`。

## 运行与部署入口

本地开发统一使用：

```bash
scripts/dev doctor
scripts/dev start all
scripts/dev status
scripts/dev logs server -f
scripts/dev stop
```

生产部署只从[ACR 镜像生产部署](../deployment/private-docker-release.md)进入。`deploy/production/` 保存最小生产部署资产，历史教程不得作为生产依据。

## 验证入口

按实际修改范围执行最小验证：

```bash
# 文档与自动事实
scripts/docs-facts --check
scripts/docs-check

# Python，必须使用项目 Conda 环境
cd main/xiaozhi-server
conda run -n xiaozhi-esp32-server python -m compileall .
conda run -n xiaozhi-esp32-server pytest

# Java
cd main/manager-api && mvn test

# Web
cd main/manager-web && npm run build

# Mobile
cd main/manager-mobile && pnpm type-check && pnpm build:h5 && pnpm build:mp-weixin
```

脚本与 Compose 的专项验证见[开发规范](../standards/development-specification.md)。外部模型真实调用、长期服务和生产部署不属于默认验证。
