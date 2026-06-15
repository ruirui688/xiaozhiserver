# deploy 目录治理边界

`deploy/` 是项目部署资产的归档入口，用来收纳可以直接参与部署、安装、升级或发布的文件。当前阶段只建立边界和规则，不迁移现有 Dockerfile、Docker Compose 文件或脚本。

## 目录分域

- `deploy/docker/`：Docker 镜像构建、Compose 编排、容器运行所需资产。
- `deploy/server/`：本地服务器安装、升级、回滚、运行时目录初始化所需资产。
- `deploy/release/`：镜像发布、版本打标、私有仓库推送、发布产物校验所需资产。

## 与 docs/deployment 的关系

`docs/deployment/` 负责部署流程说明、教程、操作背景和排障说明。它可以引用 `deploy/` 中的资产，但不应成为可执行部署资产的长期存放位置。

`deploy/` 负责可执行部署资产，包括 Compose 模板、环境变量模板、安装入口、发布入口和部署清单。迁入 `deploy/` 的资产应能被脚本、CI 或部署人员直接使用。

## 与 scripts 的关系

`scripts/` 负责本地开发、检查、辅助命令和当前兼容入口。已有脚本如果被部署流程依赖，迁移到 `deploy/` 前必须先保留兼容入口，避免破坏旧文档、CI 或用户命令。

## 现有资产保留规则

以下资产目前仍保留在原位置：

- `scripts/docker-build-private.sh`
- `scripts/install-private-docker.sh`
- `main/xiaozhi-server/docker-compose_all.yml`

后续如果迁移这些资产，必须先提供原路径兼容入口，并在对应文档中声明迁移窗口、替代路径和回滚方式。

## 修改约束

- 不在没有兼容入口的情况下移动、重命名或删除现有部署资产。
- 不把本地开发检查命令混入 `deploy/`。
- 不把教程型内容扩展为 `deploy/` 的主要内容；教程应留在 `docs/deployment/`。
- 新增部署资产时，优先归入 `docker`、`server`、`release` 三个域之一。
