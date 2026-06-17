# scripts 入口说明

本目录保留开发、镜像发布和兼容入口。生产端只有一条路线：

```text
本地/构建机 build + push 到阿里云 ACR
生产服务器只使用 deploy/production 最小部署资产，docker pull 已发布镜像并启动
```

生产服务器不从源码构建业务镜像。

## 常用入口

本地开发：

```bash
scripts/dev doctor
scripts/dev start all
scripts/dev status
scripts/dev stop
```

发布镜像到阿里云 ACR：

```bash
scripts/acr-login
scripts/acr-build-push
```

WSL 或代理环境中，如果 buildx 容器无法访问 Docker Hub，使用单平台 Docker 构建：

```bash
BUILD_ENGINE=docker scripts/acr-build-push
```

生产服务器拉取镜像部署：

```bash
cd deploy/production
sudo docker login crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com
./install
```

生产服务器升级：

```bash
cd deploy/production
./upgrade
```

首次部署后，`/opt/xiaozhi-data/.env` 会记录 `XIAOZHI_DEPLOY_MODE=pull`、`XIAOZHI_SERVER_IMAGE_REPOSITORY` 和 `XIAOZHI_WEB_IMAGE_REPOSITORY`。后续 `deploy/production/upgrade` 会复用该配置。

## 脚本分组

推荐入口：

```text
dev                  本地源码开发统一入口
acr-login            登录阿里云 ACR
acr-build-push       构建并推送 xiaozhi-server/xiaozhi-web 镜像
acr-deploy-pull      兼容入口，转发到 deploy/production/install
deploy               兼容入口，转发到 deploy/production/install
upgrade              兼容入口，转发到 deploy/production/upgrade
```

内部实现入口：

```text
docker-build-private.sh     镜像构建底层实现
install-private-docker.sh   旧兼容入口，转发到 deploy/production/install
upgrade-git-docker.sh       旧源码目录升级入口，不作为生产主入口
docker-config-mirror        Docker daemon 镜像加速器配置工具
start-dev.sh                旧开发入口，仅转发到 scripts/dev
```

## 生产约束

- 不在生产服务器编译业务镜像，生产安装脚本只支持 pull 模式。
- 不把 ACR 密码、数据库密码、Redis 密码或生产私有配置写入 Git。
- 生产镜像默认使用 `main/xiaozhi-server/requirements-docker.txt`，不安装 `torch` / `torchaudio`。
- 如果确实需要容器内本地 FunASR，构建时显式设置 `SERVER_REQUIREMENTS=main/xiaozhi-server/requirements.txt`。
