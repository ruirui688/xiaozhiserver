# ACR 镜像生产部署

这是本项目的唯一生产部署入口。

```text
开发/构建机：完整源码 -> 构建镜像 -> 推送阿里云 ACR
生产服务器：最小部署资产 -> docker pull ACR 镜像 -> Docker Compose 启动
```

生产服务器不编译源码，不安装 Maven、Node.js、Conda、Python 依赖或 FunASR 模型。所有运行代码、模型依赖和 CPU FunASR 运行时都必须已经在镜像里。

---

## 1. 先确认三件事

### 1.1 生产只认两个来源

生产升级同时读取两个来源：

```text
Git origin/main                  deploy/production/install、upgrade、docker-compose.yml
阿里云 ACR :latest 或短哈希 tag   xiaozhi-server、xiaozhi-web、mysql、redis 镜像
```

这两个来源必须来自同一次 `main` 发布。不要在 feature 分支构建并推送 `latest`，然后让生产服务器从 `main` 拉部署脚本；那会造成镜像代码和 compose/install 逻辑不匹配。

正确顺序：

```bash
git checkout main
git pull --ff-only origin main

# 确认你的改动已经合并进 main 后，再构建推送镜像
scripts/acr-login
scripts/acr-build-push

# 生产服务器只执行安装或升级
cd ~/work/xiaozhi-deploy/deploy/production
./upgrade
```

### 1.2 默认部署 latest

`scripts/acr-build-push` 每次会同时推送：

```text
xiaozhi-server:latest
xiaozhi-server:<git短哈希>
xiaozhi-web:latest
xiaozhi-web:<git短哈希>
```

生产 `./install` 和 `./upgrade` 默认使用 `VERSION=latest`，所以日常发布后不需要手写版本号：

```bash
./install
./upgrade
```

只有要固定版本或回滚时才使用：

```bash
VERSION=<git短哈希> ./upgrade
```

### 1.3 默认数据在部署用户家目录

默认持久化目录：

```text
~/xiaozhi-data
```

主要内容：

```text
~/xiaozhi-data/.env          部署变量、镜像仓库、资源限制
~/xiaozhi-data/docker-compose.yml
~/xiaozhi-data/data          xiaozhi-server 私有配置
~/xiaozhi-data/mysql/data    MySQL 数据
~/xiaozhi-data/redis/data    Redis 数据
~/xiaozhi-data/uploadfile    OTA 固件上传
~/xiaozhi-data/music         音乐文件
```

旧部署如果仍要使用 `/opt/xiaozhi-data`，必须显式指定：

```bash
DATA_DIR=/opt/xiaozhi-data ./upgrade
```

---

## 2. 镜像清单

默认 ACR 地址：

```text
crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/
```

生产运行镜像：

```text
xiaozhi-server:latest    Python 实时语音服务，可发布为轻量 profile 或 FunASR profile
xiaozhi-web:latest       Java manager-api + Vue manager-web
mysql:8.4                MySQL 运行时镜像
redis:8.0                Redis 运行时镜像
```

构建缓存镜像：

```text
xiaozhi-server-base-common:latest / base-<依赖哈希>
xiaozhi-server-base-lite:latest / base-<依赖哈希>
xiaozhi-server-base-funasr:latest / base-<依赖哈希>
xiaozhi-web:web-<内容哈希>
```

这些 base/cache 镜像只用于加速构建，不由生产 compose 直接启动。FunASR base 从 lite base 追加重依赖，lite 和 FunASR 共享 common/lite 缓存层；Web/API 内容未变化时会直接复用 `xiaozhi-web:web-<内容哈希>`，避免切换 lite/funasr 时重复构建和上传同一个管理端镜像。

---

## 3. 构建并推送镜像

在完整源码所在的开发机或构建机执行：

```bash
cd ~/work/xiaozhi-esp32-server
git checkout main
git pull --ff-only origin main
scripts/acr-login
scripts/acr-build-push
```

默认行为：

```text
BUILD_BASE=auto              自动复用或补齐 common/lite/funasr base
MIRROR_RUNTIME_IMAGES=auto   ACR 已有 mysql/redis 时跳过同步
BUILD_ENGINE=docker          单平台 linux/amd64 构建
PUSH=true                    构建后推送 ACR
SERVER_INSTALL_PROFILE=lite 不安装本地 FunASR，ASR 使用远程 provider
基础镜像默认使用 docker.m.daocloud.io 前缀，避免失效 Docker daemon mirror
BUILD_BASE=auto 只复用精确依赖 hash 的 base 缓存，不默认用 latest 冒充
Web/API 源码未变化时复用 xiaozhi-web:web-<内容哈希>，不重跑 npm/maven/docker build
```

两种服务端 profile：

| 入口 | 适用场景 | 依赖清单 | 镜像内容 |
|------|----------|----------|----------|
| `scripts/acr-build-push-lite` | 不使用本地 FunASR，ASR 走远程 provider 或 FunASR server | `requirements-docker.txt` | 不含 `torch` / `torchaudio` / `funasr`，不打包 SenseVoiceSmall |
| `scripts/acr-build-push-funasr` | 使用本地 `fun_local` / SenseVoiceSmall CPU 识别 | `requirements-docker.txt` + `requirements-docker-funasr-extra.txt` | 在 lite base 上追加 CPU PyTorch / FunASR，并默认打包 SenseVoiceSmall |

也可以直接覆盖底层变量：

```bash
SERVER_INSTALL_PROFILE=lite scripts/acr-build-push
SERVER_INSTALL_PROFILE=funasr scripts/acr-build-push
```

常用覆盖：

| 场景 | 命令 |
|------|------|
| 不使用本地 FunASR，构建轻量服务端镜像 | `scripts/acr-build-push-lite` |
| 使用本地 FunASR，构建完整服务端镜像 | `scripts/acr-build-push-funasr` |
| 只改 Java/Web，确认不需要检查基础镜像和运行时镜像 | `BUILD_BASE=false MIRROR_RUNTIME_IMAGES=false scripts/acr-build-push` |
| 改了 Python 依赖或 `Dockerfile-server-base` | `BUILD_BASE=true scripts/acr-build-push` |
| 查看完整 Docker 输出 | `VERBOSE=true scripts/acr-build-push` |
| 构建多架构镜像 | `BUILD_ENGINE=buildx PLATFORMS=linux/amd64,linux/arm64 scripts/acr-build-push` |
| 降低构建机并发负载 | `PARALLEL_RELEASE_STEPS=false PARALLEL_IMAGE_BUILDS=false scripts/acr-build-push` |

FunASR profile 的模型默认来源：

```text
main/xiaozhi-server/models/SenseVoiceSmall/model.pt
```

如果这个文件存在，FunASR 构建会直接打进 `xiaozhi-server` 镜像；不存在时才从 `FUNASR_MODEL_URL` 下载。保留本地模型文件可以避免每次构建重新下载。轻量 profile 不读取也不下载该模型。

模型在其他位置时：

```bash
FUNASR_MODEL_LOCAL_PATH=/path/to/model.pt scripts/acr-build-push-funasr
```

临时使用镜像源下载时：

```bash
FUNASR_MODEL_URL=https://your-mirror.example/SenseVoiceSmall/model.pt scripts/acr-build-push-funasr
```

FunASR profile 临时不把模型打进镜像时：

```bash
INSTALL_FUNASR_MODEL=false scripts/acr-build-push-funasr
```

这种镜像不适合当前默认生产方案，除非你另行挂载 `models/SenseVoiceSmall/model.pt`。

---

## 4. 首次安装

在生产服务器执行。只拉取 `deploy/production` 最小资产：

```bash
mkdir -p ~/work
git clone --filter=blob:none --sparse --branch main \
  ssh://git@10.144.92.32:222/gaoruirui688/xiaozhi-esp32-server.git \
  ~/work/xiaozhi-deploy
cd ~/work/xiaozhi-deploy
git sparse-checkout set deploy/production
cd deploy/production
```

登录 ACR。这里要和安装脚本实际使用的 Docker 权限一致：

```bash
# 当前用户可以直接 docker ps 时，用普通登录
docker login crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com

# 当前用户不能直接访问 Docker daemon 时，用 sudo 登录
sudo docker login crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com
```

执行安装：

```bash
./install
```

安装脚本会自动：

```text
1. 判断使用 docker 还是 sudo docker
2. 拉取 xiaozhi-server、xiaozhi-web、mysql、redis 镜像
3. 创建 ~/xiaozhi-data
4. 生成 ~/xiaozhi-data/.env 和 docker-compose.yml
5. 初始化 data/.config.yaml
6. 修正 bind mount 目录属主，让非 root 容器可写
7. 启动 MySQL、Redis、manager-api/web、xiaozhi-server
8. 从数据库读取 manager-api 的 server.secret，写回 .config.yaml
9. 重启 xiaozhi-server
```

首次安装成功后会输出类似：

```text
=== Deploy complete ===
Version: latest
manager-web: http://<服务器IP>:8002
websocket:   ws://<服务器IP>:8000/xiaozhi/v1/
```

---

## 5. 升级

标准升级：

```bash
cd ~/work/xiaozhi-deploy/deploy/production
./upgrade
```

`./upgrade` 会先对最小部署资产执行：

```text
git pull --ff-only origin <当前分支>
```

然后复用 `~/xiaozhi-data/.env`，默认拉取 `latest` 镜像并重启服务。

回滚或固定版本：

```bash
VERSION=<git短哈希> ./upgrade
```

升级不会删除：

```text
~/xiaozhi-data/mysql/data
~/xiaozhi-data/redis/data
~/xiaozhi-data/data
~/xiaozhi-data/uploadfile
~/xiaozhi-data/music
```

---

## 6. 配置上线

### 6.1 不要改错地址

三类地址必须分清：

| 用途 | 正确示例 | 说明 |
|------|----------|------|
| 浏览器访问智控台 | `http://服务器IP:8002` | 用户浏览器访问 |
| manager-api 容器访问 Python | `http://xiaozhi-esp32-server:8003` | Docker 内网地址，写在 `.env` 的 `XIAOZHI_SERVER_HTTP_BASE_URL` |
| xiaozhi-server 访问 manager-api | `http://xiaozhi-esp32-server-web:8002/xiaozhi` | 写在 `data/.config.yaml` 的 `manager-api.url` |
| ESP32 设备访问 WebSocket | `ws://服务器IP:8000/xiaozhi/v1/` | 设备网络必须可达 |
| ESP32 设备访问 OTA | `http://服务器IP:8003/xiaozhi/ota/` | 设备网络必须可达 |

不要把容器内地址改成 `127.0.0.1`。在容器里，`127.0.0.1` 只代表当前容器自己。

### 6.2 manager-api.secret 自动同步

`./install` / `./upgrade` 会在 manager-api 启动后自动读取数据库中的 `server.secret`，写入：

```text
~/xiaozhi-data/data/.config.yaml
```

首次安装不需要去网页手工复制密钥。

### 6.3 设备公网地址

在智控台“参数字典”里确认设备可访问地址：

```text
server.websocket      ws://服务器IP或域名:8000/xiaozhi/v1/
server.ota            http://服务器IP或域名:8003/xiaozhi/ota/
server.vision_explain http://服务器IP或域名:8003/mcp/vision/explain
```

生产环境建议通过 TLS 反向代理暴露公网入口，并把设备地址改成 `wss://` / `https://`。

---

## 7. 缓存与速度

### 7.1 构建机缓存

构建慢主要来自三层：

| 层 | 缓存方式 | 什么时候会重新构建或上传 |
|----|----------|--------------------------|
| 系统依赖 | Docker layer + ACR `xiaozhi-server-base-common:base-<依赖哈希>` | 改 Python 基础镜像、系统包或 common base Dockerfile |
| 普通 Python 依赖 | Docker layer + ACR `xiaozhi-server-base-lite:base-<依赖哈希>` | 改 `requirements-docker.txt` 或 lite base Dockerfile |
| FunASR 追加依赖 | Docker layer + ACR `xiaozhi-server-base-funasr:base-<依赖哈希>` | 改 `requirements-docker-funasr-extra.txt` 或 FunASR base Dockerfile |
| SenseVoiceSmall 模型 | 本地 `model.pt` + 镜像 layer | 第一次打包模型，或替换模型文件 |
| Web/API 管理端 | ACR `xiaozhi-web:web-<内容哈希>` + channel/version tag | 改 `Dockerfile-web`、`manager-api/src`、`manager-web`、nginx/start 配置或 Web 基础镜像 |
| 服务端业务代码 | Docker layer + `:<git短哈希>` / channel tag | 每次 Python 服务代码变化 |

日常只改业务逻辑时，直接运行：

```bash
scripts/acr-build-push
```

不要清理构建机 Docker cache，也不要删除：

```text
main/xiaozhi-server/models/SenseVoiceSmall/model.pt
```

### 7.2 生产服务器 pull 缓存

生产服务器不会构建，只会 `docker pull`。Docker 会自动复用本机已有镜像层：

```text
基础依赖层没变   不重复下载 CPU PyTorch/FunASR
模型层没变       不重复下载 SenseVoiceSmall layer
业务层变了       只拉取新的业务层
```

所以升级速度取决于本次镜像和上次镜像有多少 layer 变化。

---

## 8. 权限与数据目录

安装脚本主体按当前部署用户运行，默认数据放在当前部署用户的家目录。只有访问 Docker daemon 时才可能使用 `sudo docker`。

脚本会打印当前选择：

```text
docker: docker (config: /home/ubuntu/.docker)
docker: sudo docker (config: /root/.docker)
data:   /home/ubuntu/xiaozhi-data
```

如果显示 `sudo docker`，ACR 登录凭据必须在 root 的 Docker config 里：

```bash
sudo docker login crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com
```

如果显示普通 `docker`，使用当前用户登录即可：

```bash
docker login crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com
```

容器内仍然是非 root 用户运行。脚本会通过一次性 Docker 容器修正 bind mount 权限：

```text
data        999:999   xiaozhi-server 写 .auth_key 和 .config.yaml
uploadfile  100:101   manager-api 写 OTA 固件
music       100:101   manager-api 写音乐，xiaozhi-server 读取
```

---

## 9. 日常运维

默认路径：

```bash
COMPOSE=~/xiaozhi-data/docker-compose.yml
ENV=~/xiaozhi-data/.env
```

查看状态：

```bash
docker compose -f ~/xiaozhi-data/docker-compose.yml --env-file ~/xiaozhi-data/.env ps
```

重启全部：

```bash
docker compose -f ~/xiaozhi-data/docker-compose.yml --env-file ~/xiaozhi-data/.env restart
```

停止容器但保留数据：

```bash
docker compose -f ~/xiaozhi-data/docker-compose.yml --env-file ~/xiaozhi-data/.env down
```

单服务日志：

```bash
docker logs -f xiaozhi-esp32-server
docker logs -f xiaozhi-esp32-server-web
docker logs -f xiaozhi-esp32-server-db
docker logs -f xiaozhi-esp32-server-redis
```

单服务重启：

```bash
docker restart xiaozhi-esp32-server
docker restart xiaozhi-esp32-server-web
```

---

## 10. 资源调优

默认按 2 核 4G、CPU FunASR 设计：

| 容器 | CPU | 内存 |
|------|-----|------|
| xiaozhi-server | 1.00 | 2048m |
| xiaozhi-web | 0.60 | 1024m |
| MySQL | 0.30 | 768m |
| Redis | 0.10 | 192m |

`memswap_limit` 默认等于 `mem_limit`，避免小机器进入长期 swap。Python 容器低于 2G 时，CPU FunASR/SenseVoiceSmall 更容易启动失败或被 OOM kill。

调整 `~/xiaozhi-data/.env` 后执行：

```bash
cd ~/work/xiaozhi-deploy/deploy/production
./upgrade
```

常用变量：

```text
XIAOZHI_SERVER_CPUS=1.00
XIAOZHI_SERVER_MEM_LIMIT=2048m
XIAOZHI_SERVER_MEMSWAP_LIMIT=2048m
XIAOZHI_FUNASR_THREADS=2
XIAOZHI_WEB_CPUS=0.60
XIAOZHI_WEB_MEM_LIMIT=1024m
XIAOZHI_MYSQL_MEM_LIMIT=768m
XIAOZHI_REDIS_MEM_LIMIT=192m
```

旧部署如果 `.env` 里仍保留 `XIAOZHI_SERVER_MEM_LIMIT=768m`，升级脚本会尊重旧值。启用 FunASR 前必须手工调到至少 `2048m`。

---

## 11. 更换仓库或镜像

构建推送到其他 ACR namespace：

```bash
ACR_REGISTRY=你的registry \
ACR_NAMESPACE=你的namespace \
scripts/acr-build-push
```

生产安装使用其他业务镜像仓库：

```bash
SERVER_IMAGE_REPOSITORY=你的仓库/xiaozhi-server \
WEB_IMAGE_REPOSITORY=你的仓库/xiaozhi-web \
./install
```

生产安装使用其他运行时镜像：

```bash
MYSQL_IMAGE=你的仓库/mysql:8.4 \
REDIS_IMAGE=你的仓库/redis:8.0 \
./install
```

`.env` 中 `XIAOZHI_DEPLOY_MODE=pull` 不要改成源码构建模式。生产服务器没有构建工具链。

---

## 12. 常见失败

### 12.1 `Pull images FAIL`

先看脚本输出的 Docker 选择：

```text
docker: docker (config: /home/ubuntu/.docker)
docker: sudo docker (config: /root/.docker)
```

然后用同一种权限手工拉取完整镜像名：

```bash
docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-server:latest
docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-web:latest
docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/mysql:8.4
docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/redis:8.0

sudo docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-server:latest
sudo docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-web:latest
sudo docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/mysql:8.4
sudo docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/redis:8.0
```

如果手工 `docker pull` 成功但 `./install` 失败，通常是普通用户和 `sudo` 使用了不同的 Docker config。按脚本打印的 `docker:` 行重新登录。

如果提示 repository does not exist，常见原因是：

```text
镜像还没有推送到 ACR
ACR namespace 写错，不是 yihuixiaozhi
登录的是普通 docker，但脚本实际使用 sudo docker
只推送了 xiaozhi-server，没有推送 xiaozhi-web/mysql/redis
```

### 12.2 manager-api 启动超时

查看日志：

```bash
docker logs --tail 120 xiaozhi-esp32-server-web
docker logs --tail 120 xiaozhi-esp32-server-db
```

重点检查 MySQL 是否启动、Liquibase 是否报错、数据库密码是否和 `.env` 一致。

### 12.3 xiaozhi-server 反复重启

查看日志：

```bash
docker logs --tail 120 xiaozhi-esp32-server
```

常见原因：

```text
~/xiaozhi-data/data/.config.yaml 配置错误
manager-api.url 被改成公网地址或 127.0.0.1
server.secret 没同步成功
Python 容器内存过低，FunASR 初始化失败
```

### 12.4 OTA 或音乐上传失败

检查宿主机目录：

```bash
ls -ld ~/xiaozhi-data/uploadfile ~/xiaozhi-data/music
```

修复方式是重新运行安装脚本，让它通过容器修正 bind mount 权限：

```bash
cd ~/work/xiaozhi-deploy/deploy/production
./install
```

不要手工把目录改成全局可写。

### 12.5 设备连不上

确认三件事：

```text
server.websocket 是设备可访问的公网或局域网地址
防火墙放行 8000/8002/8003，或已经配置 TLS 反向代理
设备固件使用的是你的 OTA/WebSocket 地址，不是官方默认地址
```

---

## 13. 发布前检查清单

构建机：

```text
代码已合并到 main
git pull --ff-only origin main 已完成
scripts/acr-build-push 成功
ACR 中存在 xiaozhi-server/latest、xiaozhi-web/latest、mysql/8.4、redis/8.0
```

生产服务器：

```text
deploy/production 来自 origin/main
docker 或 sudo docker 已登录 ACR
./upgrade 成功
docker ps 显示四个容器运行
manager-web 可访问
设备 WebSocket/OTA 地址已配置为设备可达地址
```
