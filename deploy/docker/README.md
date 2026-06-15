# Docker 部署资产

`deploy/docker/` 用于归档 Docker 构建和容器运行相关资产。当前阶段只声明归档边界，不迁移现有文件。

## 资产范围

适合归入本目录的资产包括：

- Dockerfile 或 Dockerfile 模板。
- Docker Compose 模板和运行时编排文件。
- 容器环境变量模板。
- 容器健康检查、启动包装、镜像构建上下文相关文件。
- 与容器运行直接绑定的 Nginx、入口脚本等配置。

## 当前保留位置

以下 Docker 相关资产暂时保留在现有位置：

- `Dockerfile-server`
- `Dockerfile-server-base`
- `Dockerfile-web`
- `main/xiaozhi-server/docker-compose.yml`
- `main/xiaozhi-server/docker-compose_all.yml`
- `docs/docker/nginx.conf`
- `docs/docker/start.sh`

这些文件被现有文档、构建脚本或用户部署命令引用。迁移前必须提供兼容入口，并同步更新 `docs/deployment/` 中的流程说明。

## 与 release 域的边界

本目录关注“如何构建和运行容器”。镜像标签策略、私有仓库推送、发布校验和发布记录归 `deploy/release/` 管理。

## 与 server 域的边界

本目录不负责服务器目录初始化、生产数据目录约定、升级回滚步骤。那些资产归 `deploy/server/` 管理，Docker Compose 可以作为被服务器安装流程引用的运行资产。
