# Release 发布资产

`deploy/release/` 用于归档镜像发布、版本打标、私有仓库推送和发布校验相关资产。当前阶段只声明归档边界，不迁移现有文件。

## 资产范围

适合归入本目录的资产包括：

- 发布入口脚本。
- 镜像标签和版本号策略配置。
- 私有镜像仓库推送配置。
- 发布前校验、发布后验证和回滚检查清单。
- CI 发布流程引用的部署清单。

## 当前保留位置

以下发布相关资产暂时保留在现有位置：

- `scripts/docker-build-private.sh`
- `.github/workflows/docker-image.yml`

`scripts/docker-build-private.sh` 仍是私有镜像发布的兼容入口。后续如果迁移到 `deploy/release/`，必须保留原脚本路径作为转发入口，并明确标注替代命令。

## 与 docker 域的边界

`deploy/docker/` 关注镜像如何构建、容器如何运行；`deploy/release/` 关注哪些镜像被发布、如何打标签、推送到哪里以及如何验证发布结果。

## 与 server 域的边界

`deploy/release/` 不负责生产服务器安装和数据目录初始化。生产服务器如何拉取并运行已发布镜像，归 `deploy/server/` 管理。
