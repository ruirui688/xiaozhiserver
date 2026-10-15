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
| `scripts/acr-build-push-funasr` | 使用本地 `fun_local` / SenseVoiceSmall CPU 识别 | 先复用 `requirements-docker.txt`，再追加 `requirements-docker-funasr-extra.txt` | 默认打包 |

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
SERVER_INSTALL_PROFILE=lite scripts/acr-build-push
SERVER_INSTALL_PROFILE=funasr scripts/acr-build-push
```

发布脚本默认把 Python、maintenance-agent Python、Node、Maven、Java runtime 和 Nginx runtime 基础镜像指向
`docker.m.daocloud.io`，避免构建机 Docker daemon 配了失效公共 mirror 时
拉取基础镜像失败。仍可按需覆盖：

```bash
PYTHON_BASE_IMAGE=python:3.10-slim-bookworm \
MAINTENANCE_PYTHON_BASE_IMAGE=python:3.12.11-slim-bookworm \
MAINTENANCE_NODE_BASE_IMAGE=node:22.22.0-bookworm-slim \
NODE_BASE_IMAGE=node:20-bookworm-slim \
MAVEN_BASE_IMAGE=maven:3.9.4-eclipse-temurin-21 \
API_RUNTIME_BASE_IMAGE=bellsoft/liberica-runtime-container:jre-21-glibc \
MANAGER_WEB_RUNTIME_BASE_IMAGE=alpine:3.20 \
ASSETS_RUNTIME_BASE_IMAGE=alpine:3.20 \
RELEASE_ASSETS_BASE_IMAGE=alpine:3.20 \
scripts/acr-build-push-lite
```

maintenance-agent 默认使用清华 PyPI 镜像、600 秒读取超时，并通过 BuildKit cache mount 复用 pip/npm 下载缓存；可用
`MAINTENANCE_PIP_TIMEOUT` 和 `MAINTENANCE_PIP_INDEX_URL` 覆盖（包括切回官方 PyPI）。缓存只存在于构建器中，
不进入运行镜像。

服务端 base 缓存分三层，lite 和 FunASR 共享 common/lite 层，FunASR 只额外追加重依赖：

```text
xiaozhi-server-base-common  系统依赖、locale、pip 基础配置
xiaozhi-server-base-lite    common + 普通 Python 依赖
xiaozhi-server-base-funasr  lite + torch / torchaudio / funasr
```

`BUILD_BASE=auto` 只复用对应层的精确 `base-<hash>` 缓存，不再把旧的单仓库
base 当成通用缓存，避免 lite 镜像混入 FunASR。
`base-<hash>` 只按构建参数、逻辑文件路径和文件内容计算，不依赖 Git worktree
所在的绝对路径；从不同 worktree 执行同一份内容的构建，会命中同一批 ACR
base 缓存。脚本仍会探测旧版路径相关 cache tag，命中时只给旧镜像追加新的稳定
tag 并推送，不删除本地或 ACR 中已有缓存。

> `acr-build-push` 会把三个业务镜像和 `xiaozhi-deploy-assets` 作为同一个
> 版本发布。部署资产镜像包含 Compose、install/upgrade、release manifest
> 和环境变量契约，因此 `VERSION=<sha> ~/xiaozhi-upgrade` 不再混用服务器 Git 分支
> 中的旧 Compose。生产仍应优先发布经过评审的提交；`PUSH=true` 时默认拒绝
> 脏工作区，本地未提交内容只能使用 `PUSH=false` 验证。

`scripts/acr-build-push` 是日常一键发布入口。它默认自动复用已有基础镜像和运行时镜像，只在缓存不存在时补齐：

```text
xiaozhi-server:latest
xiaozhi-manager-api:latest
xiaozhi-manager-web:latest
xiaozhi-deploy-assets:latest
xiaozhi-server-base-common/lite/funasr:latest
xiaozhi-server-base-common/lite/funasr:base-<依赖哈希>
xiaozhi-manager-api:api-<内容哈希>
xiaozhi-manager-web:web-<内容哈希>
mysql:8.4 / redis:8.0
```

默认发布命令使用 Docker engine 构建并推送单平台 `linux/amd64` 镜像，优先复用 Docker
daemon 本地基础镜像和构建层。需要跨构建机 registry cache 或多架构镜像时显式使用
`BUILD_ENGINE=buildx`：

```bash
PLATFORMS=linux/amd64,linux/arm64 scripts/acr-build-push
```

发布脚本默认使用安静输出，只显示短摘要、运行时镜像状态、每个构建/推送步骤的进度和结果；Docker 原始日志会写入 `/tmp/xiaozhi-*` 临时目录。排查问题时可以打开完整输出：

```bash
VERBOSE=true scripts/acr-build-push
```

安静输出下，交互式终端默认在同一行显示 `⠴ Build manager Web image ━━━━━╸━━━━━━━━━ 00:35` 这类不确定进度条，只表示任务仍在运行，不代表真实百分比；为避免多个后台任务抢占同一行，交互式安静进度模式默认串行执行步骤，显式设置并行变量时仍可并行。非 TTY 日志会按 `XIAOZHI_PROGRESS_INTERVAL` 保留多行心跳，Docker 原始进度仍写入临时日志。可以按需调整或关闭：

```bash
XIAOZHI_PROGRESS_INTERVAL=10 scripts/acr-build-push
XIAOZHI_PROGRESS=false scripts/acr-build-push
PARALLEL_RELEASE_STEPS=true PARALLEL_IMAGE_BUILDS=true scripts/acr-build-push
```

非交互式环境或显式开启并行时，发布脚本可以并行执行互不依赖的步骤：运行时镜像同步与业务镜像构建可并行，`xiaozhi-server`、`xiaozhi-manager-api` 和 `xiaozhi-manager-web` 也可并行。`xiaozhi-server` 只有在确实需要构建 `xiaozhi-server-base` 时才会等待基础镜像先完成。需要降低本机负载时可关闭并行：

```bash
PARALLEL_RELEASE_STEPS=false PARALLEL_IMAGE_BUILDS=false scripts/acr-build-push
```

默认值已经适合日常发布：

```bash
BUILD_BASE=auto MIRROR_RUNTIME_IMAGES=auto scripts/acr-build-push
```

需要在多次发布或不同构建机之间复用 manager-api 的 Maven 层和 manager-web 的
npm 层时，使用 buildx registry cache。缓存写入两个稳定的 ACR `buildcache-v1`
标签，不会为每个提交新增一份本地运行镜像：

```bash
BUILD_ENGINE=buildx BUILDKIT_REGISTRY_CACHE=true scripts/acr-build-push
```

默认 `BUILD_ENGINE=docker`。需要跨构建机复用 registry cache 时显式设置
`BUILD_ENGINE=buildx`；`BUILDKIT_REGISTRY_CACHE=auto` 只复用已经存在的 registry
cache，不会在正常业务发布中首次上传 Maven/npm 深缓存；需要在组件下一次重建时创建或更新
`buildcache-v1`，显式使用上面的 `BUILDKIT_REGISTRY_CACHE=true`。Docker engine 不读写 ACR
registry cache。
同一构建机上的 manager-api 依赖预取和 JAR 打包还会共用具名 Maven BuildKit cache；
仓库连接和响应等待具有有限超时及重试，单个 CDN 节点无响应时不会无限停在
`Scanning for projects...`。这些保护不会清理或覆盖已有 Maven/BuildKit 缓存。
底层 `docker-build-private.sh` 单独运行时仍默认使用 Docker engine，避免本地 `PUSH=false`
构建误把 daemon-only base 当成 docker-container builder 可见镜像。显式使用
`BUILD_ENGINE=buildx PUSH=false` 时必须设置 `BUILD_BASE=false` 使用 registry-backed base，
或显式提供 `SERVER_BASE_IMAGE`。

manager-web 的默认 builder 固定为带 digest 的 `node:20-bookworm-slim`，避免浮动的完整
`node:20` 在本地镜像清理后重新下载数百 MB。远端缓存和运行时镜像使用
`docker buildx imagetools inspect` 做 OCI 兼容的元数据检查；检查本身不会把完整镜像拉到
本地 Docker daemon。

正式发布使用 `xiaozhi-builder-v2`。脚本不再把 Docker daemon 的代理自动复制进 builder
容器；daemon 常见的 `127.0.0.1` 代理在容器里会指向容器自身，导致基础镜像元数据请求
失败。构建器代理必须通过 `BUILDKIT_HTTP_PROXY`、`BUILDKIT_HTTPS_PROXY` 和
`BUILDKIT_NO_PROXY` 显式传入容器可访问的地址。

只改 Java/Web 管理端且明确不需要检查基础镜像或运行时镜像时，可显式跳过：

```bash
BUILD_BASE=false \
SERVER_BASE_IMAGE=crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-server-base-lite:latest \
MIRROR_RUNTIME_IMAGES=false \
scripts/acr-build-push-lite
```

修改了 `Dockerfile-server-base`、`main/xiaozhi-server/requirements-docker*.txt` 或 Python 系统依赖时，强制重建基础镜像：

```bash
BUILD_BASE=true scripts/acr-build-push
```

### 构建缓存与提速

`acr-build-push` 的缓存边界如下：

```text
xiaozhi-server-base-common:base-<hash>  系统依赖缓存
xiaozhi-server-base-lite:base-<hash>    普通 Python 依赖缓存
xiaozhi-server-base-funasr:base-<hash>  CPU PyTorch/FunASR 追加层缓存
xiaozhi-server:<commit>             服务代码；FunASR profile 会额外包含 SenseVoiceSmall 模型
xiaozhi-manager-api:api-<hash>      Java API 内容缓存，源码未变时直接复用
xiaozhi-manager-web:web-<hash>      Vue 管理端内容缓存，源码未变时直接复用
xiaozhi-manager-api/web:<commit>    拆分后的管理端运行镜像
mysql:8.4 / redis:8.0               运行时镜像，ACR 已存在时跳过同步
```

日常只改业务代码时使用对应 profile 的发布入口即可，脚本会优先复用现有 base 和拆分后的 manager-api / manager-web 内容缓存。只有改了 `requirements-docker*.txt`、`Dockerfile-server-base`、Python 基础镜像或系统包时，才需要 `BUILD_BASE=true`。只改 Java/Web、文档或部署说明且确认运行时镜像已在 ACR 时，可以用下面命令减少检查和推送：

```bash
BUILD_BASE=false MIRROR_RUNTIME_IMAGES=false scripts/acr-build-push
```

多个 Git worktree 可以直接从各自目录运行发布脚本；只要构建输入内容一致，
服务端 base、manager-api 和 manager-web 的 cache tag 都会一致。
不要为了切换 worktree 清理 Docker/BuildKit/ACR 缓存。
需要只对比缓存 tag、不触发构建时，可以在两个 worktree 分别运行：

```bash
PRINT_BUILD_CONFIG_ONLY=true scripts/acr-build-push-lite
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

首次引导成功后会自动安装并更新固定生产入口 `~/xiaozhi-upgrade`。

安装脚本主体按当前部署用户运行，默认数据目录是 `~/xiaozhi-data`。
如果当前用户能直接访问 Docker daemon，脚本使用普通 `docker`；如果不能，
脚本只对 Docker 命令使用 `sudo docker`，因此这种主机上需要先执行
`sudo docker login`。

生产服务器升级：

```bash
VERSION=<git短哈希> ~/xiaozhi-upgrade
```

升级器先拉取
同版本 `xiaozhi-deploy-assets`，再按 manifest 拉取匹配的业务镜像。

首次部署后，`~/xiaozhi-data/.env` 会记录 `XIAOZHI_DEPLOY_MODE=pull`、
`XIAOZHI_DEPLOY_ASSETS_IMAGE_REPOSITORY`、release source/contract、三个业务镜像
仓库和运行参数。后续 `~/xiaozhi-upgrade` 会复用资产仓库配置。旧部署如需继续
使用 `/opt/xiaozhi-data`，首次引导时显式传入 `DATA_DIR=/opt/xiaozhi-data`，固定
入口会记住该目录。

## 脚本分组

注册赠额部署契约的无副作用回归：`node scripts/test-trial-deployment.cjs`。
只执行隔离的配置解析，验证新安装默认 100、旧环境显式禁用保留及升级覆盖，不访问生产或发放额度。

推荐入口：

```text
dev                  本地源码开发统一入口
ui-audit-wsl-chrome  WSL 复用 Windows Chrome 做三档 Web UI 截图检查
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
docker-image-maintain       默认 dry-run 的本项目历史运行镜像保留工具
start-dev.sh                旧开发入口，仅转发到 scripts/dev
```

构建机磁盘占用过高时先预览旧项目镜像候选：

```bash
scripts/docker-image-maintain --keep 8
```

它不会调用任何 prune，也不管理 BuildKit/Docker cache、server base、MySQL、Redis、
卷或 ACR 远端镜像。只有人工确认列表后显式增加 `--apply` 才会删除候选的本地项目
镜像引用。

`ui-audit-wsl-chrome` 不安装浏览器或 npm 包，默认复用 Windows Chrome，并输出 `2048x960`、`1366x768`、`1024x768` 三档截图。完整的临时组件预览、CDP 控制台检查和清理流程见 `docs/development/local-dev-workflow.md`。

## 生产约束

- 不在生产服务器编译业务镜像，生产安装脚本只支持 pull 模式。
- 不把 ACR 密码、数据库密码、Redis 密码或生产私有配置写入 Git。
- 轻量生产镜像使用 `main/xiaozhi-server/requirements-docker.txt`，不安装 CPU PyTorch / FunASR。
- FunASR 生产镜像在 lite 依赖上追加 `main/xiaozhi-server/requirements-docker-funasr-extra.txt`，安装 CPU 版 `torch` / `torchaudio` / `funasr`。
- FunASR 服务镜像构建阶段优先复用 `main/xiaozhi-server/models/SenseVoiceSmall/model.pt`；如果模型在其他目录，使用 `FUNASR_MODEL_LOCAL_PATH=/path/to/model.pt scripts/acr-build-push-funasr`；本地没有模型时才用 `FUNASR_MODEL_URL=...` 下载。
