# ACR 镜像生产部署

本项目唯一生产部署方式：

```text
源码推送私有 Git → 构建机打包镜像 → 推送 ACR → 生产服务器拉取最小资产 + 已发布镜像
```

生产服务器不保存完整源码，不安装 Maven、Node.js、Conda 或 Python 依赖。

---

## 目录

1. [快速参考](#1-快速参考)
2. [镜像与目录](#2-镜像与目录)
3. [构建与发布](#3-构建与发布)
4. [首次部署](#4-首次部署)
5. [配置上线](#5-配置上线)
6. [升级](#6-升级)
7. [日常运维](#7-日常运维)
8. [资源调优](#8-资源调优)
9. [变量覆盖](#9-变量覆盖)

---

## 1. 快速参考

### 角色速查

| 你是什么角色 | 看哪些章节 |
|-------------|-----------|
| 构建/发布镜像 | [§3 构建与发布](#3-构建与发布) |
| 首次部署到新服务器 | [§4 首次部署](#4-首次部署) → [§5 配置上线](#5-配置上线) |
| 日常升级 | [§6 升级](#6-升级) |
| 运维管理 | [§7 日常运维](#7-日常运维) |
| 调优资源 | [§8 资源调优](#8-资源调优) |

### 关键路径

```text
构建机:   ~/work/xiaozhi-esp32-server                完整源码
生产机:   ~/work/xiaozhi-deploy/deploy/production    最小部署资产（sparse-checkout）
持久化:   /opt/xiaozhi-data                          数据、配置、上传文件
```

### 常用命令

```bash
# 构建机：发布镜像
scripts/acr-login && scripts/acr-build-push

# 生产机：升级到指定版本
cd ~/work/xiaozhi-deploy/deploy/production
VERSION=<git短哈希> ./upgrade

# 生产机：查看日志
docker logs -f xiaozhi-esp32-server
```

---

## 2. 镜像与目录

### 2.1 镜像清单

生产服务器只从 ACR 拉取：

```text
crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/
  ├── xiaozhi-server:latest          Python 实时语音服务
  ├── xiaozhi-web:latest             Java API + Vue Web 智控台
  ├── mysql:8.4                      MySQL 数据库
  └── redis:8.0                      Redis 缓存
```

构建过程内部使用（不部署到生产）：

```text
  └── xiaozhi-server-base:latest      Python 基础镜像（加速构建）
  └── xiaozhi-server-base:base-<依赖哈希>   带版本的基础镜像
```

每次发布同时推送 Git 短哈希版本（如 `xiaozhi-server:60c9ee96`）。`latest` 用于常规升级，短哈希用于定位和回滚。

### 2.2 目录约定

```text
~/work/xiaozhi-deploy   生产服务器最小部署资产目录（sparse-checkout deploy/production）
/opt/xiaozhi-data       生产持久化目录
  ├── data/             私有配置（.config.yaml）
  ├── mysql/data/       数据库文件
  ├── uploadfile/       上传文件
  └── music/            音乐文件
```

升级镜像不会删除 `/opt/xiaozhi-data`。

---

## 3. 构建与发布

在本地开发机或构建机执行。

### 3.1 日常发布

```bash
cd ~/work/xiaozhi-esp32-server
scripts/acr-login
scripts/acr-build-push
```

默认行为：
- `BUILD_BASE=auto`：优先复用基础镜像缓存，仅在依赖变更时重建
- `MIRROR_RUNTIME_IMAGES=auto`：ACR 已有 mysql/redis 时自动跳过同步
- 并行构建 `xiaozhi-server` 和 `xiaozhi-web`

输出只显示短摘要。完整构建日志写入 `/tmp/xiaozhi-*` 临时目录。

### 3.2 场景化命令

| 场景 | 命令 |
|------|------|
| **只改了 Java/Web**（跳过基础镜像检查） | `BUILD_BASE=false MIRROR_RUNTIME_IMAGES=false scripts/acr-build-push` |
| **改了 Python 依赖**（强制重建基础镜像） | `BUILD_BASE=true scripts/acr-build-push` |
| **排查构建失败**（显示详细日志） | `VERBOSE=true scripts/acr-build-push` |
| **多架构镜像** | `BUILD_ENGINE=buildx PLATFORMS=linux/amd64,linux/arm64 scripts/acr-build-push` |
| **关闭并行** | `PARALLEL_RELEASE_STEPS=false PARALLEL_IMAGE_BUILDS=false scripts/acr-build-push` |

### 3.3 Python 依赖

生产镜像默认使用 `requirements-docker.txt`（不含 torch/torchaudio，使用云端 ASR + ONNX VAD）。如需本地 FunASR：

```bash
SERVER_REQUIREMENTS=main/xiaozhi-server/requirements.txt scripts/acr-build-push
```

---

## 4. 首次部署

在生产服务器执行。部署固定使用私有仓库的 `main` 分支。

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

`./install` 会：

```text
1. 从 ACR 拉取 xiaozhi-server:latest / xiaozhi-web:latest / mysql:8.4 / redis:8.0
2. 创建 /opt/xiaozhi-data
3. 生成 /opt/xiaozhi-data/.env
4. 初始化 /opt/xiaozhi-data/data/.config.yaml
5. 启动 Docker Compose 服务
```

首次部署后 `.env` 记录：

```text
XIAOZHI_DEPLOY_MODE=pull
XIAOZHI_SERVER_IMAGE_REPOSITORY=.../xiaozhi-server
XIAOZHI_WEB_IMAGE_REPOSITORY=.../xiaozhi-web
XIAOZHI_SERVER_HTTP_BASE_URL=http://xiaozhi-esp32-server:8003
```

> ⚠️ `XIAOZHI_SERVER_HTTP_BASE_URL` 是 manager-api 容器访问 Python 服务的 Docker 内网地址。**不要**改成 `http://127.0.0.1:8003`，否则模型评测会从 manager-api 容器访问自身端口，无法调用 Python `/benchmark/models`。

---

## 5. 配置上线

### 5.1 私有配置

首次注册管理员后，在智控台参数管理中找到 `server.secret`，写入 `/opt/xiaozhi-data/data/.config.yaml`：

```yaml
server:
  ip: 0.0.0.0
  port: 8000
  http_port: 8003
  vision_explain: http://服务器IP或域名:8003/mcp/vision/explain
manager-api:
  url: http://xiaozhi-esp32-server-web:8002/xiaozhi
  secret: <粘贴 server.secret>
```

### 5.2 三类地址（容易配错）

| 用途 | 正确写法 | 错误写法 |
|------|---------|---------|
| 浏览器访问智控台 | `http://服务器公网IP:8002` | — |
| **容器内**访问 API | `http://xiaozhi-esp32-server-web:8002/xiaozhi` | ❌ `http://公网IP:8002` |
| **设备**访问服务端 | `ws://服务器公网IP:8000/xiaozhi/v1/` | ❌ `ws://xiaozhi-esp32-server-web:8000/` |

- `manager-api.url` 是容器内网地址，保持默认不要改。
- 设备地址（`server.websocket`、`server.ota`、`server.vision_explain`）必须从 ESP32 设备网络可达。

### 5.3 参数字典确认

在智控台"参数字典"中确认：

```text
server.websocket      ws://服务器IP或域名:8000/xiaozhi/v1/
server.ota            http://服务器IP或域名:8003/xiaozhi/ota/
server.vision_explain http://服务器IP或域名:8003/mcp/vision/explain
```

配置完成后：

```bash
docker restart xiaozhi-esp32-server
```

### 5.4 持久化保护

以下目录**绝对不能删除**：

```text
/opt/xiaozhi-data/mysql/data     数据库
/opt/xiaozhi-data/data           私有配置
/opt/xiaozhi-data/uploadfile     上传文件
/opt/xiaozhi-data/music          音乐文件
```

音乐管理上传的音频保存到 `/opt/xiaozhi-data/music`，同时挂载给 web 容器（展示列表）和 server 容器（设备播放）。

---

## 6. 升级

### 6.1 标准升级

```bash
# 1. 构建机：发布新版本
scripts/acr-build-push
# 输出示例：Pushed xiaozhi-server:fb945e48

# 2. 生产机：升级
cd ~/work/xiaozhi-deploy/deploy/production
VERSION=fb945e48 ./upgrade
```

`./upgrade` 输出摘要：

```text
Production deploy
  version   本次请求部署的版本
  current   /opt/xiaozhi-data/.env 中记录的上次版本
  assets    部署脚本所在 Git 分支及 pull 前后 commit
  images    本次拉取并启动的业务镜像

=== Deploy complete ===
Version    最终部署版本
Images     运行中容器绑定的业务镜像
Runtime    运行中容器绑定的 MySQL/Redis 镜像
```

`./upgrade` 会更新 sparse-checkout 资产，复用 `.env` 配置，拉取指定版本并重启容器。

### 6.2 回滚

```bash
VERSION=<上一个正常版本> ./upgrade
```

---

## 7. 日常运维

生产服务器统一通过 `/opt/xiaozhi-data/docker-compose.yml` 和 `/opt/xiaozhi-data/.env` 管理。

### 服务管理

```bash
# 查看状态
docker compose -f /opt/xiaozhi-data/docker-compose.yml \
  --env-file /opt/xiaozhi-data/.env ps

# 启动
docker compose -f /opt/xiaozhi-data/docker-compose.yml \
  --env-file /opt/xiaozhi-data/.env up -d

# 停止（保留容器）
docker compose -f /opt/xiaozhi-data/docker-compose.yml \
  --env-file /opt/xiaozhi-data/.env stop

# 停止并移除容器和网络（保留数据）
docker compose -f /opt/xiaozhi-data/docker-compose.yml \
  --env-file /opt/xiaozhi-data/.env down

# 重启全部
docker compose -f /opt/xiaozhi-data/docker-compose.yml \
  --env-file /opt/xiaozhi-data/.env restart
```

### 单服务重启

```bash
docker restart xiaozhi-esp32-server        # Python 语音服务
docker restart xiaozhi-esp32-server-web    # Java API + Web 智控台
```

### 日志

```bash
docker logs -f xiaozhi-esp32-server        # Python 日志
docker logs -f xiaozhi-esp32-server-web    # Java/Web 日志
docker logs -f xiaozhi-esp32-server-db     # MySQL 日志
docker logs -f xiaozhi-esp32-server-redis  # Redis 日志
```

---

## 8. 资源调优

### 8.1 默认限制（2 核 4G 服务器）

| 容器 | CPU | 内存 |
|------|-----|------|
| xiaozhi-server (Python) | 0.75 核 | 768 MB |
| xiaozhi-esp32-server-web (Java) | 0.80 核 | 1280 MB |
| MySQL | 0.35 核 | 1024 MB |
| Redis | 0.10 核 | 256 MB |
| **合计** | **2.00 核** | **~3328 MB** |

给宿主机保留约 700MB。`memswap_limit` 等于 `mem_limit`，防止 swap 拖死宿主机。

### 8.2 调整资源

编辑 `/opt/xiaozhi-data/.env`，修改对应变量后执行 `./upgrade`：

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

### 8.3 内部调优

| 服务 | 参数 | 默认值 |
|------|------|--------|
| Java | `XIAOZHI_WEB_JAVA_TOOL_OPTIONS` | 堆内存占容器内存比例 |
| MySQL | `innodb_buffer_pool_size` | 256M |
| MySQL | `max_connections` | 80 |
| Redis | `maxmemory` | 192mb / `allkeys-lru` |

服务器规格更高时按比例上调。

---

## 9. 变量覆盖

仅在需要更换镜像仓库或镜像来源时使用。

### 业务镜像

```bash
SERVER_IMAGE_REPOSITORY=你的仓库/xiaozhi-server \
WEB_IMAGE_REPOSITORY=你的仓库/xiaozhi-web \
./install
```

### 运行时镜像

```bash
MYSQL_IMAGE=你的仓库/mysql:8.4 \
REDIS_IMAGE=你的仓库/redis:8.0 \
./install
```

### 部署模式

`.env` 中 `XIAOZHI_DEPLOY_MODE=pull` 表示从 ACR 拉取。不要改成源码构建模式 — 生产服务器没有构建工具链。
