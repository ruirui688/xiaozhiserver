# 私有 Docker 镜像发布与生产部署

本文档用于把本项目的二次开发代码发布为私有 Docker 镜像，并在生产服务器通过 Docker Compose 安装运行。源码编译调试仍按 `docs/development/ubuntu-source-development-environment.md` 和 `scripts/start-dev.sh` 执行。

## 1. 镜像命名

默认私有镜像仓库：

```text
10.144.92.32:3001/gaoruirui688/xiaozhi-esp32-server
```

会发布以下标签：

```text
server-base
server_latest
server_<version>
web_latest
web_<version>
```

其中 `server` 是 Python 核心服务，`web` 是管理后台前端和 `manager-api` 后端组合镜像。

## 2. 构建并推送镜像

先登录私有镜像仓库：

```bash
docker login 10.144.92.32:3001
```

在项目根目录执行：

```bash
IMAGE_REPOSITORY=10.144.92.32:3001/gaoruirui688/xiaozhi-esp32-server \
VERSION=0.1.0-local \
PLATFORMS=linux/amd64 \
scripts/docker-build-private.sh
```

如果要在本机只构建不推送：

```bash
PUSH=false \
PLATFORMS=linux/amd64 \
scripts/docker-build-private.sh
```

多架构发布示例：

```bash
PLATFORMS=linux/amd64,linux/arm64 \
scripts/docker-build-private.sh
```

多架构构建必须推送到镜像仓库，不能直接 `--load` 到本机 Docker。

## 3. 生产服务器安装

生产服务器只需要 Docker、Docker Compose 插件、模型文件和运行时配置，不需要 Maven、Node.js、Conda 或源码编译环境。

如果服务器已经拉取了本仓库代码，可以执行：

```bash
sudo IMAGE_REPOSITORY=10.144.92.32:3001/gaoruirui688/xiaozhi-esp32-server \
  scripts/install-private-docker.sh
```

脚本会创建：

```text
/opt/xiaozhi-server/docker-compose_all.yml
/opt/xiaozhi-server/.env
/opt/xiaozhi-server/data/.config.yaml
/opt/xiaozhi-server/models/SenseVoiceSmall/model.pt
/opt/xiaozhi-server/mysql/data
/opt/xiaozhi-server/uploadfile
```

如果生产服务器不允许自动下载模型，可以先手动放置模型，然后执行：

```bash
sudo DOWNLOAD_MODEL=false scripts/install-private-docker.sh
```

## 4. 首次配置 server.secret

首次启动后访问智控台：

```text
http://服务器IP:8002
```

注册第一个管理员账号后，在参数管理中找到 `server.secret`，写入：

```text
/opt/xiaozhi-server/data/.config.yaml
```

配置示例：

```yaml
manager-api:
  url: http://xiaozhi-esp32-server-web:8002/xiaozhi
  secret: 复制出来的 server.secret
```

然后重启核心服务：

```bash
docker restart xiaozhi-esp32-server
```

## 5. 常用维护命令

查看服务：

```bash
docker compose -f /opt/xiaozhi-server/docker-compose_all.yml --env-file /opt/xiaozhi-server/.env ps
```

查看日志：

```bash
docker logs -f xiaozhi-esp32-server
docker logs -f xiaozhi-esp32-server-web
```

升级到最新镜像：

```bash
docker compose -f /opt/xiaozhi-server/docker-compose_all.yml --env-file /opt/xiaozhi-server/.env pull
docker compose -f /opt/xiaozhi-server/docker-compose_all.yml --env-file /opt/xiaozhi-server/.env up -d
```

停止服务：

```bash
docker compose -f /opt/xiaozhi-server/docker-compose_all.yml --env-file /opt/xiaozhi-server/.env down
```

不要删除 `/opt/xiaozhi-server/mysql/data`、`/opt/xiaozhi-server/data`、`/opt/xiaozhi-server/uploadfile`，这些目录是生产运行数据。
