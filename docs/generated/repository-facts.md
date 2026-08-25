---
status: supporting
owner: architecture
last_verified: 2026-08-25
source_paths:
  - Dockerfile-server-base
  - main/xiaozhi-server/
  - main/manager-api/pom.xml
  - main/manager-api/src/main/resources/db/changelog/
  - main/manager-web/package.json
  - main/manager-mobile/package.json
---

# 仓库事实

> 本页由 `scripts/docs-facts` 生成，请勿手工编辑。运行 `scripts/docs-facts` 更新，运行 `scripts/docs-facts --check` 检查漂移。

生成日期：`2026-08-25`（UTC）

## 技术栈版本

| 模块 | 运行时或框架 | 版本来源 |
| --- | --- | --- |
| xiaozhi-server | Python 3.10（基础镜像 `python:3.10-slim`） | `Dockerfile-server-base` |
| manager-api | Java 21 / Spring Boot 3.4.4 | `main/manager-api/pom.xml` |
| manager-web | Vue 2.6.14 / Element UI 2.15.14 | `main/manager-web/package.json` |
| manager-mobile | Vue 3.4.21 / uni-app 3.0.0-4060620250520001 / TypeScript 5.7.2 | `main/manager-mobile/package.json` |

依赖版本保留清单中的语义；表格为便于阅读去掉了 npm 的 `^` 或 `~` 前缀，锁定结果仍以对应 lockfile 为准。

## 模块规模

| 模块 | 源码文件 | 测试文件 | 统计口径 |
| --- | ---: | ---: | --- |
| xiaozhi-server | 278 | 79 | 源码为非 `tests/` 的 `*.py`；测试为 `test_*.py` |
| manager-api | 633 | 90 | `src/main/**/*.java`；`src/test/**/*Test.java` |
| manager-web | 139 | 23 | `src/` 下 `*.vue/*.js/*.ts/*.mjs`；`*.spec.*/*.test.*` |
| manager-mobile | 85 | 5 | `src/` 下 `*.vue/*.js/*.ts/*.mjs`；`*.spec.*/*.test.*` |

统计排除依赖和构建产物，不表示代码行数或测试覆盖率。

## 关键入口

| 边界 | 入口 |
| --- | --- |
| Python 实时服务 | `main/xiaozhi-server/app.py` |
| Python WebSocket | `main/xiaozhi-server/core/websocket_server.py` |
| Python HTTP | `main/xiaozhi-server/core/http_server.py` |
| Java 管理 API | `main/manager-api/src/main/java/xiaozhi/AdminApplication.java` |
| Java 数据库迁移 | `main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml` |
| Web 管理后台 | `main/manager-web/src/main.js` |
| Mobile 管理后台 | `main/manager-mobile/src/main.ts` |
| 本地开发 | `scripts/dev` |
| 生产部署 | `deploy/production/docker-compose.yml` |

## 数据库迁移

| 事实 | 数量 |
| --- | ---: |
| Liquibase SQL 文件 | 200 |
| master.yaml changeSet | 200 |
| master.yaml 唯一 SQL 登记 | 200 |

数量相等不代表迁移内容正确；`scripts/docs-check` 还会检查遗漏登记、重复登记和失效路径。

## 关键大文件

| 文件 | 行数 |
| --- | ---: |
| `main/xiaozhi-server/core/connection.py` | 2837 |
| `main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelCatalogServiceImpl.java` | 1826 |
| `main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelTestServiceImpl.java` | 1351 |
| `main/manager-api/src/main/java/xiaozhi/modules/knowledge/rag/impl/LocalKnowledgeAdapter.java` | 1313 |
| `main/manager-api/src/main/java/xiaozhi/modules/memory/service/impl/MemoryMaintenanceServiceImpl.java` | 1237 |
| `main/manager-web/src/views/ModelConfig.vue` | 1917 |
| `main/manager-web/src/components/BenchmarkResultPanel.vue` | 1587 |
| `main/manager-web/src/views/KnowledgeFileUpload.vue` | 1356 |
| `main/manager-mobile/src/pages/agent/edit.vue` | 1023 |

这些行数用于识别架构热点，不是强制拆分阈值。重构判断仍应结合职责、依赖和测试风险。
