# ACR 镜像生产部署

本文档是生产服务器安装和升级入口。

生产部署支持两种方式：

```text
pull 模式   本地或构建机先构建业务镜像并推送到镜像仓库，生产服务器只拉镜像启动
build 模式  生产服务器从 Git 拉源码并在本机构建业务镜像，仅作为历史兼容路径保留
```

本项目生产端标准路线是 pull 模式。生产服务器只需要访问私有 Git 仓库和你的镜像仓库，不再拉取 Python、Node、Maven 等构建基础镜像，也不在生产服务器编译业务镜像。

本项目当前默认使用的阿里云 ACR 个人版仓库：

```text
登录地址  crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com
镜像仓库  crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi
```

目录约定：

```text
~/work/xiaozhi-esp32-server  项目源码目录
/opt/xiaozhi-data            生产数据目录
```

`/opt/xiaozhi-data` 保存数据库、私有配置和上传文件。升级会重建业务镜像和重启容器，但不会删除这个目录。

安装阶段不需要下载或放置任何语音模型文件。ASR、VAD、LLM、TTS、VLLM、Memory、Intent 都可以先为空，服务启动后再到智控台配置。

## 快速命令

首次部署，镜像仓库 pull 模式：

```bash
mkdir -p ~/work
git clone ssh://git@10.144.92.32:222/gaoruirui688/xiaozhi-esp32-server.git ~/work/xiaozhi-esp32-server
cd ~/work/xiaozhi-esp32-server
sudo scripts/acr-login
scripts/acr-deploy-pull
```

升级当前分支：

```bash
cd ~/work/xiaozhi-esp32-server
scripts/upgrade
```

升级到指定 tag：

```bash
cd ~/work/xiaozhi-esp32-server
REF=v版本号 scripts/upgrade
```

## 服务器要求

需要安装：

```text
Docker
Docker Compose 插件
Git
```

服务器还需要：

```text
私有 Git 仓库访问权限
Docker 镜像拉取能力
```

build 模式需要生产服务器能拉取公共 Docker 基础镜像。

pull 模式需要生产服务器能拉取你的业务镜像仓库，以及 MySQL、Redis 运行镜像。

不需要单独安装：

```text
Maven
Node.js
Conda
Python 依赖
```

这些构建依赖会在 Docker 构建过程中处理。

如果服务器无法访问 Docker Hub，构建会在拉取基础镜像阶段失败，例如：

```text
failed to resolve source metadata for docker.io/library/python:3.10-slim
Head "https://registry-1.docker.io/v2/library/python/manifests/3.10-slim": i/o timeout
```

这表示服务器没有拉到 Docker 基础镜像，还没有进入项目业务代码构建。处理方式二选一：

1. 给 Docker 配置可用的 registry mirror，然后重新执行 `sudo scripts/deploy`。
2. 使用当前网络可访问的兼容基础镜像覆盖默认值。

配置 Docker registry mirror：

```bash
sudo scripts/docker-config-mirror https://你的镜像加速器地址
```

阿里云个人账号通常可以在这里找到专属地址：

```text
阿里云控制台 -> 容器镜像服务 -> 镜像工具 -> 镜像加速器
```

脚本会备份现有 `/etc/docker/daemon.json`，更新 `registry-mirrors`，然后重启 Docker。配置后先验证基础镜像能否拉取：

```bash
docker pull python:3.10-slim
```

可覆盖的基础镜像变量：

```text
PYTHON_BASE_IMAGE       默认 python:3.10-slim
NODE_BASE_IMAGE         默认 node:18
MAVEN_BASE_IMAGE        默认 maven:3.9.4-eclipse-temurin-21
WEB_RUNTIME_BASE_IMAGE  默认 bellsoft/liberica-runtime-container:jre-21-glibc
```

示例：

```bash
sudo \
  PYTHON_BASE_IMAGE=你的镜像仓库/library/python:3.10-slim \
  NODE_BASE_IMAGE=你的镜像仓库/library/node:18 \
  MAVEN_BASE_IMAGE=你的镜像仓库/library/maven:3.9.4-eclipse-temurin-21 \
  WEB_RUNTIME_BASE_IMAGE=你的镜像仓库/bellsoft/liberica-runtime-container:jre-21-glibc \
  scripts/deploy
```

替换镜像必须与默认镜像兼容。`PYTHON_BASE_IMAGE` 需要支持 `apt-get`，`WEB_RUNTIME_BASE_IMAGE` 需要支持当前 Dockerfile 中的 `apk` 安装步骤。

注意：`docker login crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com` 只表示你可以向阿里云 ACR 推送或拉取自己的镜像。它不会让 Docker 自动从阿里云获取 `python:3.10-slim`、`node:18` 等 Docker Hub 基础镜像。只要执行构建，构建机仍然需要能访问这些基础镜像，或者通过 `PYTHON_BASE_IMAGE` 等变量指定可访问的兼容基础镜像。

如果 Docker daemon 里配置的镜像加速器不可用，buildx 可能在拉取基础镜像元数据时报 `403 Forbidden`。可以临时禁用 buildx 镜像加速器后重试：

```bash
BUILDKIT_REGISTRY_MIRROR=none scripts/acr-build-push
```

如果在 WSL 或代理环境中，buildx 容器无法访问 Docker Hub，但普通 `docker pull python:3.10-slim` 可以成功，可以改用单平台 Docker 构建发布：

```bash
BUILD_ENGINE=docker scripts/acr-build-push
```

server-base 构建默认使用阿里云 Debian 镜像源，避免生产镜像构建阶段访问 Debian 官方源过慢或被中间缓存污染。如需切换，可以覆盖：

```bash
DEBIAN_MIRROR=https://deb.debian.org/debian \
DEBIAN_SECURITY_MIRROR=https://deb.debian.org/debian-security \
scripts/acr-build-push
```

生产镜像默认使用 `main/xiaozhi-server/requirements-docker.txt`，不安装 `torch` / `torchaudio`，适合使用云端 ASR 和 ONNX Silero VAD 的部署方式。如果确实要在容器内启用本地 FunASR，可以显式改回完整依赖：

```bash
SERVER_REQUIREMENTS=main/xiaozhi-server/requirements.txt scripts/acr-build-push
```

## 推送业务镜像到阿里云 ACR

在可以访问 Docker Hub 的本地开发机或构建机上执行。

先登录阿里云 ACR：

```bash
scripts/acr-login
```

如果需要非交互登录，不要把密码写进命令行或提交到 Git。可以使用只存在本机的密码文件：

```bash
ACR_USERNAME=你的阿里云ACR用户名 \
ACR_PASSWORD_FILE=/path/to/acr-password.txt \
scripts/acr-login
```

也可以从标准输入传入密码：

```bash
read -rsp 'ACR password: ' ACR_PASSWORD
printf '\n'
printf '%s' "$ACR_PASSWORD" | \
  ACR_USERNAME=你的阿里云ACR用户名 \
  ACR_PASSWORD_STDIN=true \
  scripts/acr-login
unset ACR_PASSWORD
```

构建并推送业务镜像：

```bash
scripts/acr-build-push
```

这会推送：

```text
crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi:server-base
crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi:server_latest
crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi:web_latest
crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi:server_版本号
crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi:web_版本号
```

如果需要推送到其他仓库，可以覆盖：

```bash
ACR_REGISTRY=其他登录地址 \
ACR_NAMESPACE=其他命名空间 \
ACR_REPOSITORY_NAME=其他仓库名 \
scripts/acr-build-push
```

## pull 模式部署

生产服务器上先登录镜像仓库：

```bash
sudo scripts/acr-login
```

然后部署：

```bash
scripts/acr-deploy-pull
```

pull 模式会拉取以下镜像并启动服务：

```text
crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi:server_latest
crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi:web_latest
mysql:latest
redis:8.0
```

如果服务器也无法访问 Docker Hub 的 MySQL 或 Redis 镜像，需要把它们也同步到阿里云 ACR，然后指定：

```bash
sudo env DEPLOY_MODE=pull \
  IMAGE_REPOSITORY=crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi \
  MYSQL_IMAGE=crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/mysql:8.4 \
  REDIS_IMAGE=crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/redis:8.0 \
  scripts/deploy
```

## 首次部署

准备目录并拉取代码：

```bash
mkdir -p ~/work
git clone ssh://git@10.144.92.32:222/gaoruirui688/xiaozhi-esp32-server.git ~/work/xiaozhi-esp32-server
cd ~/work/xiaozhi-esp32-server
```

部署 `main`：

```bash
git checkout main
git pull --ff-only origin main
sudo scripts/deploy
```

部署指定 tag：

```bash
git fetch --tags origin
git checkout v版本号
sudo scripts/deploy
```

`scripts/deploy` 会完成：

```text
构建或拉取业务镜像
创建 /opt/xiaozhi-data
生成 /opt/xiaozhi-data/.env
初始化 /opt/xiaozhi-data/data/.config.yaml
启动 Docker Compose 服务
```

运行目录：

```text
/opt/xiaozhi-data/docker-compose_all.yml
/opt/xiaozhi-data/.env
/opt/xiaozhi-data/data/.config.yaml
/opt/xiaozhi-data/mysql/data
/opt/xiaozhi-data/uploadfile
```

## 访问后台

查看服务：

```bash
docker compose -f /opt/xiaozhi-data/docker-compose_all.yml \
  --env-file /opt/xiaozhi-data/.env ps
```

浏览器访问：

```text
http://服务器IP:8002
```

首次进入智控台后，注册管理员账号。

## 配置 server.secret

进入智控台参数管理，找到：

```text
server.secret
```

复制它的值，写入：

```text
/opt/xiaozhi-data/data/.config.yaml
```

示例：

```yaml
manager-api:
  url: http://xiaozhi-esp32-server-web:8002/xiaozhi
  secret: 粘贴 server.secret
```

保存后重启核心服务：

```bash
docker restart xiaozhi-esp32-server
```

## 配置模型

安装阶段可以不配置模型。

需要语音对话时，再到智控台按实际需要配置：

```text
ASR   语音识别
VAD   语音活动检测
LLM   大语言模型
TTS   语音合成
VLLM  视觉语言模型
Memory / Intent 等可选能力
```

配置完成后，在智能体配置中选择对应模型，然后重启核心服务：

```bash
docker restart xiaozhi-esp32-server
```

## 一键升级

升级当前分支：

```bash
cd ~/work/xiaozhi-esp32-server
scripts/upgrade
```

升级到指定 tag：

```bash
cd ~/work/xiaozhi-esp32-server
REF=v版本号 scripts/upgrade
```

升级脚本会先拉取代码，再按 `/opt/xiaozhi-data/.env` 中记录的 pull 模式拉取远程镜像，并执行 `docker compose up -d`。

pull 模式升级时保留同样的环境变量：

```bash
cd ~/work/xiaozhi-esp32-server
scripts/upgrade
```

首次使用 `scripts/acr-deploy-pull` 后，部署模式和镜像仓库会写入 `/opt/xiaozhi-data/.env`。后续 `scripts/upgrade` 会复用该配置，先拉取代码，再拉取远程镜像，并执行 `docker compose up -d`。

如果需要临时切换镜像仓库，可以显式覆盖：

```bash
cd ~/work/xiaozhi-esp32-server
DEPLOY_MODE=pull \
IMAGE_REPOSITORY=crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi \
scripts/upgrade
```

这些数据会继续保留：

```text
管理员账号
模型配置
智能体配置
设备记录
server.secret
上传文件
MySQL 数据
```

## 常用维护

查看服务：

```bash
docker compose -f /opt/xiaozhi-data/docker-compose_all.yml --env-file /opt/xiaozhi-data/.env ps
```

查看日志：

```bash
docker logs -f xiaozhi-esp32-server
docker logs -f xiaozhi-esp32-server-web
```

停止服务：

```bash
docker compose -f /opt/xiaozhi-data/docker-compose_all.yml --env-file /opt/xiaozhi-data/.env down
```

不要删除：

```text
/opt/xiaozhi-data/mysql/data
/opt/xiaozhi-data/data
/opt/xiaozhi-data/uploadfile
```
