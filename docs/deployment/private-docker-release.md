# ACR 镜像生产部署

本项目只有一种生产部署方式：

```text
源码推送到私有 Git 仓库
  -> 本地/构建机拉取完整源码并打包 Docker 镜像
  -> 推送到阿里云 ACR
  -> 生产服务器只拉取最小部署资产和已发布镜像
```

源码仍然必须进入私有 Git 仓库。区别是：构建机使用完整源码，生产服务器不使用完整源码。

生产服务器不从源码构建业务镜像，不保存完整项目源码，不安装 Maven、Node.js、Conda 或 Python 依赖。

## 镜像命名

生产服务器只从 ACR 拉取这些运行镜像：

```text
crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-server:latest
crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-web:latest
crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/mysql:8.4
crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/redis:8.0
```

构建流程内部还会维护一个基础镜像：

```text
crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-server-base:latest
```

`xiaozhi-server-base` 只用于加速构建，不在生产服务器直接运行。

每次发布还会同时推送 Git 短哈希版本，例如：

```text
xiaozhi-server:60c9ee96
xiaozhi-web:60c9ee96
xiaozhi-server-base:60c9ee96
```

`latest` 用于常规升级，短哈希版本用于定位和回滚。

## 目录约定

```text
~/work/xiaozhi-deploy  生产服务器上的最小部署资产目录，只 sparse-checkout deploy/production
/opt/xiaozhi-data      生产数据目录
```

`/opt/xiaozhi-data` 保存数据库、私有配置和上传文件。升级镜像不会删除这个目录。

## 构建并推送镜像

在本地开发机或构建机执行：

```bash
cd ~/work/xiaozhi-esp32-server
scripts/acr-login
scripts/acr-build-push
```

默认推送到：

```text
yihuixiaozhi/xiaozhi-server
yihuixiaozhi/xiaozhi-web
yihuixiaozhi/xiaozhi-server-base
yihuixiaozhi/mysql
yihuixiaozhi/redis
```

`mysql:8.4` 和 `redis:8.0` 会被同步到 ACR，生产服务器不需要访问 Docker Hub。

`scripts/acr-build-push` 默认使用单平台 Docker 构建，避免 buildx 容器读取不可用的 Docker Hub 镜像加速器。

如需多架构镜像，再显式启用 buildx：

```bash
BUILD_ENGINE=buildx PLATFORMS=linux/amd64,linux/arm64 scripts/acr-build-push
```

生产 Docker 镜像默认使用：

```text
main/xiaozhi-server/requirements-docker.txt
```

该依赖文件不安装 `torch` / `torchaudio`。当前生产路径使用云端 ASR 和 ONNX Silero VAD，不需要 Torch。如果以后确实要在容器内启用本地 FunASR，再显式改用完整依赖：

```bash
SERVER_REQUIREMENTS=main/xiaozhi-server/requirements.txt scripts/acr-build-push
```

## 首次部署

在生产服务器执行：

```bash
mkdir -p ~/work
git clone --filter=blob:none --no-checkout \
  ssh://git@10.144.92.32:222/gaoruirui688/xiaozhi-esp32-server.git \
  ~/work/xiaozhi-deploy
cd ~/work/xiaozhi-deploy
git sparse-checkout set deploy/production
git checkout feature/configurable-docker-base-images
cd deploy/production
sudo docker login crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com
./install
```

这仍然是从私有 Git 仓库取部署资产，但只取 `deploy/production` 目录，不会把 `main/`、`docs/`、完整源码和构建文件放到生产服务器。

部署脚本会：

```text
拉取 xiaozhi-server:latest
拉取 xiaozhi-web:latest
从 ACR 拉取 mysql:8.4 和 redis:8.0
创建 /opt/xiaozhi-data
生成 /opt/xiaozhi-data/.env
初始化 /opt/xiaozhi-data/data/.config.yaml
启动 Docker Compose 服务
```

首次部署后，`.env` 会记录：

```text
XIAOZHI_DEPLOY_MODE=pull
XIAOZHI_SERVER_IMAGE_REPOSITORY=.../xiaozhi-server
XIAOZHI_WEB_IMAGE_REPOSITORY=.../xiaozhi-web
```

## 升级

先在本地或构建机发布新镜像：

```bash
scripts/acr-build-push
```

再到生产服务器执行：

```bash
cd ~/work/xiaozhi-deploy/deploy/production
./upgrade
```

`./upgrade` 会更新 sparse-checkout 中的部署资产，复用 `/opt/xiaozhi-data/.env` 中的镜像仓库配置，拉取最新镜像并重启容器。

## 访问和配置

查看服务：

```bash
docker compose -f /opt/xiaozhi-data/docker-compose.yml \
  --env-file /opt/xiaozhi-data/.env ps
```

访问智控台：

```text
http://服务器IP:8002
```

首次注册管理员后，在智控台参数管理中找到 `server.secret`，写入：

```text
/opt/xiaozhi-data/data/.config.yaml
```

示例：

```yaml
manager-api:
  url: http://xiaozhi-esp32-server-web:8002/xiaozhi
  secret: 粘贴 server.secret
```

然后重启核心服务：

```bash
docker restart xiaozhi-esp32-server
```

ASR、VAD、LLM、TTS、VLLM、Memory、Intent 等模型可以在安装后到智控台配置。安装阶段不需要下载或挂载本地语音模型。

## 常用维护

查看日志：

```bash
docker logs -f xiaozhi-esp32-server
docker logs -f xiaozhi-esp32-server-web
```

停止服务：

```bash
docker compose -f /opt/xiaozhi-data/docker-compose.yml \
  --env-file /opt/xiaozhi-data/.env down
```

不要删除：

```text
/opt/xiaozhi-data/mysql/data
/opt/xiaozhi-data/data
/opt/xiaozhi-data/uploadfile
```

## 变量覆盖

默认不需要覆盖任何镜像变量。确实需要换仓库时，只覆盖业务镜像：

```bash
SERVER_IMAGE_REPOSITORY=你的仓库/xiaozhi-server \
WEB_IMAGE_REPOSITORY=你的仓库/xiaozhi-web \
./install
```

如果需要使用其他数据库或 Redis 镜像，可以覆盖：

```bash
MYSQL_IMAGE=你的仓库/mysql:8.4 \
REDIS_IMAGE=你的仓库/redis:8.0 \
./install
```
