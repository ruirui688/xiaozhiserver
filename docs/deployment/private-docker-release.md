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

构建流程内部还会维护基础镜像：

```text
crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-server-base:latest
crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-server-base:base-<依赖哈希>
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

`mysql:8.4` 和 `redis:8.0` 会被同步到 ACR，生产服务器不需要访问 Docker Hub。发布脚本默认 `MIRROR_RUNTIME_IMAGES=auto`，如果 ACR 中已存在这两个运行时镜像，会自动跳过同步。

`scripts/acr-build-push` 默认使用单平台 Docker 构建，避免 buildx 容器读取不可用的 Docker Hub 镜像加速器。

发布脚本默认并行执行互不依赖的步骤：

```text
运行时镜像同步  与  业务镜像构建      可并行
xiaozhi-server  与  xiaozhi-web/api  可并行
```

发布脚本默认 `BUILD_BASE=auto`。它会优先复用 `xiaozhi-server-base:base-<依赖哈希>`，其次复用 `xiaozhi-server-base:latest`，只有缓存不存在时才重建 Python 基础镜像。`xiaozhi-server` 只有在确实需要构建 `xiaozhi-server-base` 时才会等待基础镜像先完成。

需要降低本机负载或排查并发构建问题时，可以关闭并行：

```bash
PARALLEL_RELEASE_STEPS=false PARALLEL_IMAGE_BUILDS=false scripts/acr-build-push
```

默认日常发布命令等价于：

```bash
BUILD_BASE=auto MIRROR_RUNTIME_IMAGES=auto scripts/acr-build-push
```

只改 Java 管理 API 或 Web 管理后台，且明确不需要检查基础镜像或运行时镜像时，可以显式跳过：

```bash
BUILD_BASE=false \
SERVER_BASE_IMAGE=crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-server-base:latest \
MIRROR_RUNTIME_IMAGES=false \
scripts/acr-build-push
```

修改了 `Dockerfile-server-base`、`main/xiaozhi-server/requirements-docker.txt` 或 Python 系统依赖时，强制重建基础镜像：

```bash
BUILD_BASE=true scripts/acr-build-push
```

发布脚本默认隐藏 Docker 逐层构建和推送明细，只输出短摘要、运行时镜像状态、每个构建/推送步骤的进度和结果。完整日志会写入 `/tmp/xiaozhi-*` 临时目录。需要排查构建细节时使用：

```bash
VERBOSE=true scripts/acr-build-push
```

安静输出下，交互式终端默认在同一行显示 `⠴ Build manager web/api image ━━━━━╸━━━━━━━━━ 00:35` 这类不确定进度条，只表示任务仍在运行，不代表真实百分比；为避免多个后台任务抢占同一行，交互式安静进度模式默认串行执行步骤，显式设置并行变量时仍可并行。非 TTY 日志会按 `XIAOZHI_PROGRESS_INTERVAL` 保留多行心跳，Docker 原始进度仍写入临时日志。需要调整或关闭时使用：

```bash
XIAOZHI_PROGRESS_INTERVAL=10 scripts/acr-build-push
XIAOZHI_PROGRESS=false scripts/acr-build-push
PARALLEL_RELEASE_STEPS=true PARALLEL_IMAGE_BUILDS=true scripts/acr-build-push
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
XIAOZHI_SERVER_HTTP_BASE_URL=http://xiaozhi-esp32-server:8003
```

`XIAOZHI_SERVER_HTTP_BASE_URL` 是 manager-api 容器访问 Python
`xiaozhi-server` HTTP/OTA/评测接口的 Docker 内网地址。生产 Docker 部署中
不要配置成 `http://127.0.0.1:8003`，否则模型专业评测会从 manager-api
容器访问自身的 8003 端口，无法调用到 Python 的 `/benchmark/models`。

## 容器资源限制

生产 Compose 模板默认按 2 核 4G 服务器做保守限制。限制写在
`deploy/production/docker-compose.yml` 中，`./install` 会同步生成到
`/opt/xiaozhi-data/.env`，后续 `./upgrade` 会继续复用。

默认资源预算：

```text
Python xiaozhi-server  CPU 0.75核  内存 768m
Java/Web manager-api   CPU 0.80核  内存 1280m
MySQL                  CPU 0.35核  内存 1024m
Redis                  CPU 0.10核  内存 256m
合计                   CPU 2.00核  内存约 3328m
```

这样会给宿主机、Docker daemon、内核页缓存和临时峰值保留约 700MB
以上空间。`memswap_limit` 默认等于 `mem_limit`，避免容器内存不足时把
宿主机拖入 swap 导致整机卡死。容器触发内存上限时会由 Docker 重启，
优先保护宿主机可操作性。

对应调优变量可直接写入 `/opt/xiaozhi-data/.env` 后执行 `./upgrade`：

```text
XIAOZHI_SERVER_CPUS=0.75
XIAOZHI_SERVER_MEM_LIMIT=768m
XIAOZHI_WEB_CPUS=0.80
XIAOZHI_WEB_MEM_LIMIT=1280m
XIAOZHI_MYSQL_CPUS=0.35
XIAOZHI_MYSQL_MEM_LIMIT=1024m
XIAOZHI_REDIS_CPUS=0.10
XIAOZHI_REDIS_MEM_LIMIT=256m
```

Java 容器通过 `XIAOZHI_WEB_JAVA_TOOL_OPTIONS` 控制堆内存占容器内存的比例；
MySQL 默认限制 `innodb_buffer_pool_size=256M`、`max_connections=80`；
Redis 默认 `maxmemory=192mb` 且使用 `allkeys-lru`，避免缓存无限增长。
如服务器规格高于 2 核 4G，可以按比例上调这些变量。

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
/opt/xiaozhi-data/music
```

网页“音乐管理”上传的音频会保存到 `/opt/xiaozhi-data/music`。该目录会同时挂载给
`xiaozhi-esp32-server-web` 的 `/music` 和 `xiaozhi-esp32-server` 的
`/opt/xiaozhi-esp32-server/music`，确保后台上传、列表展示和设备播放使用同一份持久数据。

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
