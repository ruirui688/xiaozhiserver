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

服务端镜像有两种安装/构建 profile：

| 入口 | 用途 | Python 依赖 | FunASR 模型 |
|------|------|-------------|-------------|
| `scripts/acr-build-push-lite` | 不使用本地 FunASR，ASR 走豆包、阿里云、腾讯、讯飞、OpenAI 兼容、FunASR server 等远程服务 | `requirements-docker.txt`，不安装 `torch` / `torchaudio` / `funasr` | 不打包 |
| `scripts/acr-build-push-funasr` | 使用本地 `fun_local` / SenseVoiceSmall CPU 识别 | `requirements-docker-funasr.txt`，安装 CPU PyTorch、torchaudio、FunASR | 默认打包 |

日常不需要本地 FunASR 时优先用轻量入口：

```bash
scripts/acr-build-push-lite
```

需要切回本地 FunASR 时使用：

```bash
scripts/acr-build-push-funasr
```

底层也可以直接切换：

```bash
SERVER_INSTALL_PROFILE=standard scripts/acr-build-push
SERVER_INSTALL_PROFILE=funasr scripts/acr-build-push
```

> ⚠️ **发布前必须先合并到 `main` 分支。**
>
> `acr-build-push` 将当前分支的代码构建为 Docker 镜像并推送到 ACR 的
> `:latest` 标签。生产服务器 `./upgrade` 始终从 `main` 拉取部署脚本
> （docker-compose.yml, install），同时从 ACR 拉取 `:latest` 镜像。
>
> 如果从 feature 分支推送镜像而部署脚本还在 `main`，两边的
> docker-compose.yml 不一致（容器依赖、卷挂载等可能缺失），
> 镜像代码也与部署配置不匹配。
>
> **正确流程：先 `git checkout main && git merge <feature>`，再从 `main`
> 执行 `acr-build-push`。** 详见 `deploy/production/README.md`。

`scripts/acr-build-push` 是日常一键发布入口。它默认自动复用已有基础镜像和运行时镜像，只在缓存不存在时补齐：

```text
xiaozhi-server:latest
xiaozhi-web:latest
xiaozhi-server-base:latest / xiaozhi-server-base:base-<依赖哈希>
mysql:8.4 / redis:8.0
```

默认发布命令使用单平台 Docker 构建并推送 `linux/amd64` 镜像，避免 buildx 容器走不可用的 Docker Hub 镜像加速器。需要多架构镜像时再显式启用 buildx：

```bash
BUILD_ENGINE=buildx PLATFORMS=linux/amd64,linux/arm64 scripts/acr-build-push
```

发布脚本默认使用安静输出，只显示短摘要、运行时镜像状态、每个构建/推送步骤的进度和结果；Docker 原始日志会写入 `/tmp/xiaozhi-*` 临时目录。排查问题时可以打开完整输出：

```bash
VERBOSE=true scripts/acr-build-push
```

安静输出下，交互式终端默认在同一行显示 `⠴ Build manager web/api image ━━━━━╸━━━━━━━━━ 00:35` 这类不确定进度条，只表示任务仍在运行，不代表真实百分比；为避免多个后台任务抢占同一行，交互式安静进度模式默认串行执行步骤，显式设置并行变量时仍可并行。非 TTY 日志会按 `XIAOZHI_PROGRESS_INTERVAL` 保留多行心跳，Docker 原始进度仍写入临时日志。可以按需调整或关闭：

```bash
XIAOZHI_PROGRESS_INTERVAL=10 scripts/acr-build-push
XIAOZHI_PROGRESS=false scripts/acr-build-push
PARALLEL_RELEASE_STEPS=true PARALLEL_IMAGE_BUILDS=true scripts/acr-build-push
```

非交互式环境或显式开启并行时，发布脚本可以并行执行互不依赖的步骤：运行时镜像同步与业务镜像构建可并行，`xiaozhi-server` 与 `xiaozhi-web` 也可并行。`xiaozhi-server` 只有在确实需要构建 `xiaozhi-server-base` 时才会等待基础镜像先完成。需要降低本机负载时可关闭并行：

```bash
PARALLEL_RELEASE_STEPS=false PARALLEL_IMAGE_BUILDS=false scripts/acr-build-push
```

默认值已经适合日常发布：

```bash
BUILD_BASE=auto MIRROR_RUNTIME_IMAGES=auto scripts/acr-build-push
```

只改 Java/Web 管理端且明确不需要检查基础镜像或运行时镜像时，可显式跳过：

```bash
BUILD_BASE=false \
SERVER_BASE_IMAGE=crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-server-base:latest \
MIRROR_RUNTIME_IMAGES=false \
scripts/acr-build-push
```

修改了 `Dockerfile-server-base`、`main/xiaozhi-server/requirements-docker*.txt` 或 Python 系统依赖时，强制重建基础镜像：

```bash
BUILD_BASE=true scripts/acr-build-push
```

### 构建缓存与提速

`acr-build-push` 的缓存边界如下：

```text
xiaozhi-server-base:base-<依赖哈希>  Python 依赖缓存；FunASR profile 会额外缓存 CPU PyTorch/FunASR
xiaozhi-server:<commit>             服务代码；FunASR profile 会额外包含 SenseVoiceSmall 模型
xiaozhi-web:<commit>                manager-api + manager-web
mysql:8.4 / redis:8.0               运行时镜像，ACR 已存在时跳过同步
```

日常只改业务代码时使用对应 profile 的发布入口即可，脚本会优先复用现有 base。只有改了 `requirements-docker*.txt`、`Dockerfile-server-base`、Python 基础镜像或系统包时，才需要 `BUILD_BASE=true`。只改 Java/Web、文档或部署说明且确认运行时镜像已在 ACR 时，可以用下面命令减少检查和推送：

```bash
BUILD_BASE=false MIRROR_RUNTIME_IMAGES=false scripts/acr-build-push
```

FunASR profile 会优先复用构建机本地模型文件：

```text
main/xiaozhi-server/models/SenseVoiceSmall/model.pt
```

这样后续 FunASR 构建会直接复制本地文件，不会重新下载。该文件被 `.gitignore` 和 `.dockerignore` 排除，不进入 Git，也不通过普通源码上下文重复传输；服务镜像只通过 `funasr_model` build context 显式打包它。轻量 profile 不读取也不下载这个模型。

生产服务器拉取镜像部署：

```bash
cd deploy/production
sudo docker login crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com
./install
```

安装脚本主体按当前部署用户运行，默认数据目录是 `~/xiaozhi-data`。
如果当前用户能直接访问 Docker daemon，脚本使用普通 `docker`；如果不能，
脚本只对 Docker 命令使用 `sudo docker`，因此这种主机上需要先执行
`sudo docker login`。

生产服务器升级：

```bash
cd deploy/production
./upgrade
```

需要固定部署或回滚时，才使用 `VERSION=<git短哈希> ./upgrade`。

首次部署后，`~/xiaozhi-data/.env` 会记录 `XIAOZHI_DEPLOY_MODE=pull`、`XIAOZHI_SERVER_IMAGE_REPOSITORY` 和 `XIAOZHI_WEB_IMAGE_REPOSITORY`。后续 `deploy/production/upgrade` 会复用该配置。旧部署如需继续使用 `/opt/xiaozhi-data`，执行时显式传入 `DATA_DIR=/opt/xiaozhi-data`。

## 脚本分组

推荐入口：

```text
dev                  本地源码开发统一入口
acr-login            登录阿里云 ACR
acr-build-push       构建并推送业务镜像，并同步 MySQL/Redis 到 ACR
acr-build-push-lite  构建并推送不含本地 FunASR 的轻量业务镜像
acr-build-push-funasr 构建并推送包含本地 FunASR/SenseVoice 的业务镜像
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
- 轻量生产镜像使用 `main/xiaozhi-server/requirements-docker.txt`，不安装 CPU PyTorch / FunASR。
- FunASR 生产镜像使用 `main/xiaozhi-server/requirements-docker-funasr.txt`，安装 CPU 版 `torch` / `torchaudio` / `funasr`。
- FunASR 服务镜像构建阶段优先复用 `main/xiaozhi-server/models/SenseVoiceSmall/model.pt`；如果模型在其他目录，使用 `FUNASR_MODEL_LOCAL_PATH=/path/to/model.pt scripts/acr-build-push-funasr`；本地没有模型时才用 `FUNASR_MODEL_URL=...` 下载。
