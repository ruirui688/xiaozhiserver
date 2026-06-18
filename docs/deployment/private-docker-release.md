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

发布脚本默认隐藏 Docker 逐层构建和推送明细，只输出步骤摘要。完整日志会写入 `/tmp/xiaozhi-*` 临时目录。需要排查构建细节时使用：

```bash
VERBOSE=true scripts/acr-build-push
```

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
git clone --filter=blob:none --sparse --branch main \
  ssh://git@10.144.92.32:222/gaoruirui688/xiaozhi-esp32-server.git \
  ~/work/xiaozhi-deploy
cd ~/work/xiaozhi-deploy
git sparse-checkout set deploy/production
cd deploy/production
sudo docker login crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com
./install
```

生产部署固定使用私有仓库的 `main` 分支。功能分支只用于开发和合并前验证，不写入生产安装命令。

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

## 日常操作

生产服务器统一使用 `/opt/xiaozhi-data/docker-compose.yml` 和 `/opt/xiaozhi-data/.env` 管理服务。

查看服务：

```bash
docker compose -f /opt/xiaozhi-data/docker-compose.yml \
  --env-file /opt/xiaozhi-data/.env ps
```

启动项目：

```bash
docker compose -f /opt/xiaozhi-data/docker-compose.yml \
  --env-file /opt/xiaozhi-data/.env up -d
```

重启全部服务：

```bash
docker compose -f /opt/xiaozhi-data/docker-compose.yml \
  --env-file /opt/xiaozhi-data/.env restart
```

只重启核心服务：

```bash
docker restart xiaozhi-esp32-server
```

只重启智控台和 API：

```bash
docker restart xiaozhi-esp32-server-web
```

关闭项目但保留容器：

```bash
docker compose -f /opt/xiaozhi-data/docker-compose.yml \
  --env-file /opt/xiaozhi-data/.env stop
```

停止并移除容器和网络，但保留数据库、配置和上传文件：

```bash
docker compose -f /opt/xiaozhi-data/docker-compose.yml \
  --env-file /opt/xiaozhi-data/.env down
```

查看日志：

```bash
docker logs -f xiaozhi-esp32-server
docker logs -f xiaozhi-esp32-server-web
docker logs -f xiaozhi-esp32-server-db
docker logs -f xiaozhi-esp32-server-redis
```

## 访问和配置

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
server:
  ip: 0.0.0.0
  port: 8000
  http_port: 8003
  vision_explain: http://服务器IP或域名:8003/mcp/vision/explain
manager-api:
  url: http://xiaozhi-esp32-server-web:8002/xiaozhi
  secret: 粘贴 server.secret
```

这里要区分三类地址：

```text
浏览器访问智控台： http://服务器IP或域名:8002
容器内访问 API：  http://xiaozhi-esp32-server-web:8002/xiaozhi
设备访问服务端：  必须使用服务器公网IP、域名或反向代理地址
```

`manager-api.url` 是 `xiaozhi-server` 容器访问 `manager-api` 的 Docker 内网地址，生产环境默认保持：

```text
http://xiaozhi-esp32-server-web:8002/xiaozhi
```

不要把 `manager-api.url` 改成公网 IP。公网 IP 是给浏览器和 ESP32 设备访问的，不是容器内部服务互访地址。

首次上线还必须在智控台“参数字典”中确认这些设备可访问地址：

```text
server.websocket      ws://服务器IP或域名:8000/xiaozhi/v1/
server.ota            http://服务器IP或域名:8003/xiaozhi/ota/
server.vision_explain http://服务器IP或域名:8003/mcp/vision/explain
```

这些参数会下发给 ESP32 设备，必须从设备网络可达。不能写 Docker 内网服务名，例如不能写 `xiaozhi-esp32-server-web`。

然后重启核心服务：

```bash
docker restart xiaozhi-esp32-server
```

ASR、VAD、LLM、TTS、VLLM、Memory、Intent 等模型可以在安装后到智控台配置。安装阶段不需要下载或挂载本地语音模型。

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
