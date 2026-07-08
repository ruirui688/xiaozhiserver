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
阿里云 ACR :latest 或短哈希 tag   xiaozhi-server、xiaozhi-manager-api、xiaozhi-manager-web、xiaozhi-manager-assets、mysql、redis 镜像
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
xiaozhi-manager-api:latest
xiaozhi-manager-api:<git短哈希>
xiaozhi-manager-web:latest
xiaozhi-manager-web:<git短哈希>
xiaozhi-manager-assets:latest
xiaozhi-manager-assets:<git短哈希>
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
xiaozhi-manager-api:latest     Java manager-api
xiaozhi-manager-web:latest     Nginx + Vue manager-web dist，统一暴露 8002
xiaozhi-manager-assets:latest  manager-web 大静态资源 `/generator/static/**`
mysql:8.4                MySQL 运行时镜像
redis:8.0                Redis 运行时镜像
```

构建缓存镜像：

```text
xiaozhi-server-base-common:latest / base-<依赖哈希>
xiaozhi-server-base-lite:latest / base-<依赖哈希>
xiaozhi-server-base-funasr:latest / base-<依赖哈希>
xiaozhi-manager-api:api-<内容哈希>
xiaozhi-manager-web:web-<内容哈希>
xiaozhi-manager-assets:assets-<内容哈希>
```

这些 base/cache 镜像只用于加速构建，不由生产 compose 直接启动。FunASR base 从 lite base 追加重依赖，lite 和 FunASR 共享 common/lite 缓存层；manager-api、manager-web 和 manager-assets 分别按内容哈希复用缓存。只改 Vue 页面时不会重建 Java API 或大静态资源镜像，只改 Java 时不会重建前端镜像。

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
manager-api / manager-web / manager-assets 分别复用 api-/web-/assets-<内容哈希>，未变化组件不重跑构建
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

本地只验证镜像、不推送 ACR 时，可以显式关闭推送：

```bash
PUSH=false BUILD_BASE=false scripts/acr-build-push-lite
```

这只会更新当前 Docker daemon 里的本地标签。`deploy/production/install` 和
`deploy/production/upgrade` 是生产拉取入口，会先从 ACR `docker pull` 对应
`VERSION`，因此会覆盖同名本地标签。没有把修复后的
`xiaozhi-server`、`xiaozhi-manager-api`、`xiaozhi-manager-web`、
`xiaozhi-manager-assets` 推送到 ACR 前，不要用 `./upgrade` 验证本地镜像。
需要临时验证本地镜像时，只能在确认 `~/xiaozhi-data/.env` 指向同名本地标签后，
用 Docker Compose 直接重建目标服务，且不要先执行 pull：

```bash
docker compose -f ~/xiaozhi-data/docker-compose.yml --env-file ~/xiaozhi-data/.env \
  up -d --no-deps --force-recreate xiaozhi-esp32-manager-assets xiaozhi-esp32-server-web
```

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
2. 拉取 xiaozhi-server、xiaozhi-manager-api、xiaozhi-manager-web、xiaozhi-manager-assets、mysql、redis 镜像
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
| 浏览器访问智控台 | `https://yh.wejoy.link/` | 生产环境通过 HTTPS 反向代理访问 |
| manager-api 容器访问 Python | `http://xiaozhi-esp32-server:8003` | Docker 内网地址，写在 `.env` 的 `XIAOZHI_SERVER_HTTP_BASE_URL` |
| xiaozhi-server 访问 manager-api | `http://xiaozhi-esp32-server-web:8002/xiaozhi` | 写在 `data/.config.yaml` 的 `manager-api.url` |
| ESP32 设备访问 WebSocket | `wss://yh.wejoy.link/xiaozhi/v1/` | 生产环境公网地址，设备网络必须可达 |
| ESP32 设备访问 OTA | `https://yh.wejoy.link/xiaozhi/ota/` | 生产环境公网地址，设备网络必须可达 |
| 微信小程序访问 API | `https://yh.wejoy.link/xiaozhi` | 必须配置为微信小程序 request 合法域名 |

不要把容器内地址改成 `127.0.0.1`。在容器里，`127.0.0.1` 只代表当前容器自己。
也不要把 `.env` 里的 `XIAOZHI_SERVER_HTTP_BASE_URL` 改成公网 HTTPS 地址；它是
manager-api 容器访问 Python 容器的内网地址。

### 6.2 manager-api.secret 自动同步

`./install` / `./upgrade` 会在 manager-api 启动后自动读取数据库中的 `server.secret`，写入：

```text
~/xiaozhi-data/data/.config.yaml
```

首次安装不需要去网页手工复制密钥。

### 6.3 设备公网地址

在智控台“参数字典”里确认设备可访问地址：

```text
server.websocket      wss://yh.wejoy.link/xiaozhi/v1/
server.ota            https://yh.wejoy.link/xiaozhi/ota/
server.vision_explain https://yh.wejoy.link/mcp/vision/explain
```

生产环境必须通过 TLS 反向代理暴露公网入口，并把设备地址改成 `wss://` / `https://`。
部署包提供了 `deploy/production/nginx-https.conf.example`，映射关系如下：

```text
https://yh.wejoy.link/                   -> 127.0.0.1:8002
https://yh.wejoy.link/xiaozhi/           -> 127.0.0.1:8002/xiaozhi/
https://yh.wejoy.link/xiaozhi/ota/       -> 127.0.0.1:8003/xiaozhi/ota/
https://yh.wejoy.link/mcp/vision/explain -> 127.0.0.1:8003/mcp/vision/explain
wss://yh.wejoy.link/xiaozhi/v1/          -> 127.0.0.1:8000/xiaozhi/v1/
```

在服务器 `~/xiaozhi-data/.env` 中设置：

```bash
XIAOZHI_PUBLIC_DOMAIN=yh.wejoy.link
XIAOZHI_PUBLIC_BASE_URL=https://yh.wejoy.link
XIAOZHI_CORS_ORIGINS=https://yh.wejoy.link
```

然后执行 `./upgrade`。HTTPS 打通后，在微信公众平台把
`https://yh.wejoy.link` 加入小程序 request 合法域名；上线版小程序不能依赖
开发者工具里的“关闭合法域名校验”。

### 6.4 生产 HTTPS/WSS 完整流程

生产环境接入微信小程序时，必须先让公网域名具备可信 HTTPS 入口。SSL 证书和
ICP备案是两件独立的事：证书解决浏览器、小程序和设备是否信任
`https://yh.wejoy.link`；备案解决中国大陆服务器对外提供网站服务的合规问题。

推荐入口链路：

```text
浏览器/小程序/设备
  -> https://yh.wejoy.link / wss://yh.wejoy.link
  -> 宿主机 Nginx 80/443
  -> 127.0.0.1:8002 / 127.0.0.1:8003 / 127.0.0.1:8000
  -> Docker 容器服务
```

#### 6.4.1 前置检查

确认域名、安全组和服务器防火墙都已经准备好：

```bash
# 域名必须解析到当前服务器公网 IP
dig +short yh.wejoy.link

# 阿里云安全组入方向至少放行 TCP:80 和 TCP:443
# 服务器本机防火墙也要允许 80/443
sudo ufw status

# 业务容器本地端口应已经正常监听
sudo ss -lntp | grep -E ':(8000|8002|8003)\b'
```

阿里云安全组建议先放行：

```text
0.0.0.0/0  TCP:80
0.0.0.0/0  TCP:443
```

如果服务器启用了 IPv6，再补：

```text
::/0        TCP:80
::/0        TCP:443
```

#### 6.4.2 安装 Nginx 和证书工具

Let's Encrypt 是生产可用的免费公开 CA 证书，适合个人、小团队和常规企业服务。
如果需要企业主体增强验证，也可以改用阿里云 SSL 证书服务申请 DV/OV 证书。

在服务器安装 Nginx 和 Certbot：

```bash
sudo apt update
sudo apt install -y nginx certbot python3-certbot-nginx
sudo systemctl enable nginx
sudo systemctl start nginx
sudo nginx -t
```

#### 6.4.3 申请 `yh.wejoy.link` 证书

使用 Nginx 插件自动申请证书：

```bash
sudo certbot --nginx -d yh.wejoy.link
```

按提示填写邮箱、同意服务条款，并选择 HTTP 自动跳转 HTTPS。申请成功后证书路径通常是：

```text
/etc/letsencrypt/live/yh.wejoy.link/fullchain.pem
/etc/letsencrypt/live/yh.wejoy.link/privkey.pem
```

验证自动续期：

```bash
sudo systemctl status certbot.timer --no-pager
sudo certbot renew --dry-run
```

如果需要显式保证续期后重载 Nginx：

```bash
sudo certbot renew --deploy-hook "systemctl reload nginx"
```

#### 6.4.4 配置 Nginx 反向代理

复制部署包中的模板：

```bash
cd ~/work/xiaozhi-deploy
sudo cp deploy/production/nginx-https.conf.example /etc/nginx/sites-available/xiaozhi.conf
sudo ln -sf /etc/nginx/sites-available/xiaozhi.conf /etc/nginx/sites-enabled/xiaozhi.conf
```

编辑 `/etc/nginx/sites-available/xiaozhi.conf`，确认域名和证书路径：

```nginx
server_name yh.wejoy.link;
ssl_certificate     /etc/letsencrypt/live/yh.wejoy.link/fullchain.pem;
ssl_certificate_key /etc/letsencrypt/live/yh.wejoy.link/privkey.pem;
```

模板中的关键映射必须保持一致：

```text
https://yh.wejoy.link/                   -> http://127.0.0.1:8002/
https://yh.wejoy.link/xiaozhi/           -> http://127.0.0.1:8002/xiaozhi/
https://yh.wejoy.link/xiaozhi/ota/       -> http://127.0.0.1:8003/xiaozhi/ota/
https://yh.wejoy.link/mcp/vision/explain -> http://127.0.0.1:8003/mcp/vision/explain
wss://yh.wejoy.link/xiaozhi/v1/          -> ws://127.0.0.1:8000/xiaozhi/v1/
```

检查并重载：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

#### 6.4.5 写入部署环境变量

服务器 `~/xiaozhi-data/.env` 中保留 Docker 内网地址，同时增加公网地址：

```bash
XIAOZHI_SERVER_HTTP_BASE_URL=http://xiaozhi-esp32-server:8003
XIAOZHI_PUBLIC_DOMAIN=yh.wejoy.link
XIAOZHI_PUBLIC_BASE_URL=https://yh.wejoy.link
XIAOZHI_CORS_ORIGINS=https://yh.wejoy.link
```

不要把 `XIAOZHI_SERVER_HTTP_BASE_URL` 改成 `https://yh.wejoy.link`；它只给
manager-api 容器访问 Python 容器使用。

重新部署：

```bash
VERSION=latest ./deploy/production/upgrade
```

#### 6.4.6 验证 HTTPS/WSS 入口

先验证 HTTPS：

```bash
curl -I https://yh.wejoy.link
curl -I https://yh.wejoy.link/xiaozhi/user/pub-config
curl -I https://yh.wejoy.link/xiaozhi/ota/
```

再验证 Nginx 监听和日志：

```bash
sudo ss -lntp | grep ':443'
sudo journalctl -u nginx -n 80 --no-pager
sudo tail -n 80 /var/log/nginx/error.log
```

浏览器能访问 `https://yh.wejoy.link/` 后，在智控台“参数字典”写入：

```text
server.websocket      wss://yh.wejoy.link/xiaozhi/v1/
server.ota            https://yh.wejoy.link/xiaozhi/ota/
server.vision_explain https://yh.wejoy.link/mcp/vision/explain
```

微信公众平台配置：

```text
request 合法域名: https://yh.wejoy.link
socket 合法域名:  wss://yh.wejoy.link
```

#### 6.4.7 生产安全收口

HTTPS/WSS 验证通过后，公网安全组只保留必要入口：

```text
80   HTTP 跳转 HTTPS / 证书续期
443  HTTPS/WSS 统一入口
22   SSH，仅允许自己的固定公网 IP 更好
```

`8000`、`8002`、`8003`、MySQL 和 Redis 不应继续对全网开放。它们应只被宿主机
Nginx 或 Docker 内网访问。

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
| manager-api | ACR `xiaozhi-manager-api:api-<内容哈希>` + channel/version tag | 改 Java 源码、`pom.xml`、API Dockerfile 或 API 启动脚本 |
| manager-web | ACR `xiaozhi-manager-web:web-<内容哈希>` + channel/version tag | 改 Vue 源码、前端构建配置、Web Nginx 配置或前端基础镜像 |
| manager-assets | ACR `xiaozhi-manager-assets:assets-<内容哈希>` + channel/version tag | 改 `public/generator/static/**` 大静态资源或 assets Nginx 配置 |
| 服务端业务代码 | Docker layer + `:<git短哈希>` / channel tag | 每次 Python 服务代码变化 |

`base-<依赖哈希>` 按构建参数、逻辑文件路径和文件内容计算，不包含 Git worktree
的绝对路径。多个 worktree 构建同一份内容时，会复用同一批
`xiaozhi-server-base-*` ACR 缓存。脚本会兼容探测旧版路径相关 cache tag；
如果旧 tag 存在而新稳定 tag 不存在，会给旧镜像追加新 tag 并推送，不删除旧缓存。
需要只对比缓存 tag、不触发构建时，可以在不同 worktree 分别运行：

```bash
PRINT_BUILD_CONFIG_ONLY=true scripts/acr-build-push-lite
```

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
业务层变了       只拉取对应组件的新业务层
```

所以升级速度取决于本次镜像和上次镜像有多少 layer 变化。拆分后，常见小改动只影响一个组件镜像：只改 Vue 页面时通常只拉 `xiaozhi-manager-web` 的新层；只改 Java 时只拉 `xiaozhi-manager-api`；大静态资源不变时不会重复拉取 `xiaozhi-manager-assets`。

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
docker logs -f xiaozhi-esp32-manager-api
docker logs -f xiaozhi-esp32-manager-assets
docker logs -f xiaozhi-esp32-server-db
docker logs -f xiaozhi-esp32-server-redis
```

单服务重启：

```bash
docker restart xiaozhi-esp32-server
docker restart xiaozhi-esp32-server-web
docker restart xiaozhi-esp32-manager-api
docker restart xiaozhi-esp32-manager-assets
```

---

## 10. 资源调优

默认按 2 核 4G、CPU FunASR 设计：

| 容器 | CPU | 内存 |
|------|-----|------|
| xiaozhi-server | 1.00 | 2048m |
| xiaozhi-manager-api | 0.50 | 896m |
| xiaozhi-manager-web | 0.20 | 192m |
| xiaozhi-manager-assets | 0.10 | 128m |
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
XIAOZHI_MANAGER_API_CPUS=0.50
XIAOZHI_MANAGER_API_MEM_LIMIT=896m
XIAOZHI_MANAGER_WEB_CPUS=0.20
XIAOZHI_MANAGER_WEB_MEM_LIMIT=192m
XIAOZHI_MANAGER_ASSETS_CPUS=0.10
XIAOZHI_MANAGER_ASSETS_MEM_LIMIT=128m
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
MANAGER_API_IMAGE_REPOSITORY=你的仓库/xiaozhi-manager-api \
MANAGER_WEB_IMAGE_REPOSITORY=你的仓库/xiaozhi-manager-web \
MANAGER_ASSETS_IMAGE_REPOSITORY=你的仓库/xiaozhi-manager-assets \
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
docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-manager-api:latest
docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-manager-web:latest
docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-manager-assets:latest
docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/mysql:8.4
docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/redis:8.0

sudo docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-server:latest
sudo docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-manager-api:latest
sudo docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-manager-web:latest
sudo docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-manager-assets:latest
sudo docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/mysql:8.4
sudo docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/redis:8.0
```

如果手工 `docker pull` 成功但 `./install` 失败，通常是普通用户和 `sudo` 使用了不同的 Docker config。按脚本打印的 `docker:` 行重新登录。

如果提示 repository does not exist，常见原因是：

```text
镜像还没有推送到 ACR
ACR namespace 写错，不是 yihuixiaozhi
登录的是普通 docker，但脚本实际使用 sudo docker
只推送了 xiaozhi-server，没有推送 xiaozhi-manager-api/xiaozhi-manager-web/xiaozhi-manager-assets/mysql/redis
```

### 12.2 manager-api 启动超时

查看日志：

```bash
docker logs --tail 120 xiaozhi-esp32-manager-api
docker logs --tail 120 xiaozhi-esp32-server-db
```

重点检查 MySQL 是否启动、Liquibase 是否报错、数据库密码是否和 `.env` 一致。

如果日志里出现类似：

```text
Validation Failed:
db/changelog/db.changelog-master.yaml::<id>::<author> was: <old> but is now: <new>
```

说明生产数据库已经执行过这个 Liquibase changeSet，但当前镜像里的同一个
changeSet 被改过。处理原则是：不要清数据库、不要删 `DATABASECHANGELOG`，
先恢复或兼容旧 checksum，再新增一个新的迁移文件补偿当前需要的数据库变更。
已执行过的迁移文件、changeSet id、author 和 sqlFile 路径都不能当作普通代码
继续修改。

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
ACR 中存在 xiaozhi-server/latest、xiaozhi-manager-api/latest、xiaozhi-manager-web/latest、xiaozhi-manager-assets/latest、mysql/8.4、redis/8.0
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
