# 私有 Docker 镜像发布与生产部署

本文档用于把本项目的二次开发代码发布为私有 Docker 镜像，并在生产服务器通过 Docker Compose 安装运行。源码编译调试仍按 `docs/development/ubuntu-source-development-environment.md` 和 `scripts/dev` 执行。

当前 `scripts/docker-build-private.sh` 和 `scripts/install-private-docker.sh` 是私有镜像构建与生产安装的兼容入口；后续如果迁入 `deploy/`，必须保留旧脚本转发入口。

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

生产服务器只负责运行已经构建好的 Docker 镜像。服务器上需要准备：

```text
必须安装：
  Docker
  Docker Compose 插件

不需要安装：
  Maven
  Node.js
  Conda
  Java / Web / Python 源码编译环境
```

### 3.1 推荐方式：让脚本自动准备运行目录

如果服务器已经拉取了本仓库代码，并且服务器可以访问模型下载地址，直接执行：

```bash
sudo IMAGE_REPOSITORY=10.144.92.32:3001/gaoruirui688/xiaozhi-esp32-server \
  scripts/install-private-docker.sh
```

脚本会完成这些事：

```text
1. 创建生产运行目录：
   /opt/xiaozhi-server/

2. 复制 Docker Compose 文件：
   /opt/xiaozhi-server/docker-compose_all.yml

3. 生成镜像仓库和数据库密码环境文件：
   /opt/xiaozhi-server/.env

4. 初始化 xiaozhi-server 私有运行配置：
   /opt/xiaozhi-server/data/.config.yaml

5. 准备数据库和上传文件目录：
   /opt/xiaozhi-server/mysql/data
   /opt/xiaozhi-server/uploadfile

6. 如果模型文件不存在，自动下载语音识别模型：
   /opt/xiaozhi-server/models/SenseVoiceSmall/model.pt

7. 执行 docker compose up -d 启动服务。
```

这里的模型文件是 `SenseVoiceSmall/model.pt`，用于本地语音识别，也就是把设备上传的语音转成文字。它不是大语言模型，不是 Docker 镜像，也不是后台管理系统的模型配置。

### 3.2 不能自动下载模型时怎么做

如果生产服务器不能访问 ModelScope，或者内网服务器不允许安装脚本访问公网，需要先在其他机器下载模型文件，再上传到生产服务器。

模型下载地址：

```text
https://modelscope.cn/models/iic/SenseVoiceSmall/resolve/master/model.pt
```

上传后必须放到这个固定位置：

```text
/opt/xiaozhi-server/models/SenseVoiceSmall/model.pt
```

例如先把 `model.pt` 上传到服务器的 `/tmp/model.pt`，再执行：

```bash
sudo mkdir -p /opt/xiaozhi-server/models/SenseVoiceSmall
sudo cp /tmp/model.pt /opt/xiaozhi-server/models/SenseVoiceSmall/model.pt
```

然后执行安装脚本，并明确跳过自动下载：

```bash
sudo DOWNLOAD_MODEL=false \
  IMAGE_REPOSITORY=10.144.92.32:3001/gaoruirui688/xiaozhi-esp32-server \
  scripts/install-private-docker.sh
```

如果这个文件路径不对，核心服务启动后会找不到本地语音识别模型。

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
