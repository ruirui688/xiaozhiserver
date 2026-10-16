# ACR 镜像生产部署

本文是生产服务器安装、升级、回滚和恢复的唯一入口。部署文档与可执行资产的边界如下：

仅做本机镜像构建和集成验证时使用[本地 Docker 部署指南](local-docker-deploy-guide.md)，不要把其中的本地拓扑当作生产方案。

- `docs/deployment/` 只保存流程、决策、安全要求和排障说明。
- `deploy/production/` 是生产服务器可直接消费的最小部署包；生产机无需完整源码。
- `deploy/docker/`、`deploy/server/`、`deploy/release/` 分别承载镜像构建、服务器运维和发布资产。
- `scripts/docker-build-private.sh`、`scripts/install-private-docker.sh`、`scripts/deploy`、`scripts/upgrade` 是兼容入口；迁移实现时必须先保留转发入口、更新引用并说明替代命令与回滚方法。
- `main/xiaozhi-server/docker-compose_all.yml` 仅供源码内参考，不是生产入口。

新增可执行资产应先明确所属部署域。数据目录、配置路径、镜像标签或恢复步骤的变化必须独立说明风险；不得在一次变更中同时搬迁资产并改变运行行为。

这是本项目的唯一生产部署入口。

### 旧 DeepSeek Harness 维护服务状态

当前生产 Compose 不启动 `xiaozhi-maintenance-agent`，发布脚本默认
`BUILD_MAINTENANCE_AGENT=false`，Java Harness 网关也默认停用。旧维护入口尚未接入平台额度准入，
不能通过恢复旧 `.env` 或复用模型目录中的全局密钥重新开通，也不能将它列为本次上线可用功能。

已有维护策略的定时调度仍可能留下失败记录，升级验收应核对其状态和日志。
工作空间的普通记忆维护通过 Java 模型执行链使用平台通道并扣负责人的额度，与旧 Harness 服务分开。

### 模型计费与额度查询

计费和资源包查询为可选能力，默认不读取供应商账号。变量见 `deploy/production/env.example`：火山启用
`XIAOZHI_BILLING_VOLCENGINE_ENABLED` 并配置独立只读 AK/SK、非敏感账号标识；语音资源包另需
`XIAOZHI_BILLING_VOLCENGINE_SPEECH_PROJECT` 和逗号分隔的 `XIAOZHI_BILLING_VOLCENGINE_SPEECH_RESOURCE_IDS`，
以及目标项目上的 `speech_saas_prod:ResourcePacksStatus` 查询权限。通用资源包需费用中心只读权限，
完整说明见模型管理文档。DeepSeek 通过
`XIAOZHI_BILLING_DEEPSEEK_API_KEY` 配置专用财务查询凭据，配合明确的 `ACCOUNT_REF` 查询余额；不再从模型配置或租户凭据库存取钥匙。
真实值仅放部署数据目录的私有 `.env`，Compose 已透传这些变量；不要写进镜像或示例文件。
升级器会保留现有 `.env` 中的 9 项 `XIAOZHI_BILLING_*` 配置，显式传入的非空环境变量优先；
写回 `.env` 时设置权限为 `0600`，仅文件所有者可读写。

本次发布对供应商仅执行只读查询。账单导入和归属映射只更新本项目台账，不购买、续费或切换资源包，
也不切换或启用模型。

升级前按现有流程备份数据库，启动时执行新增计费 Liquibase 迁移。Python 的 `data/billing/` 与管理端的
`uploadfile/billing-outbox/`、`uploadfile/platform-settlements/` 是待入账、待结算记录的持久队列，
不能作为缓存清理。生产 Compose 将 `XIAOZHI_PLATFORM_OUTBOX_ROOT` 固定为
`/uploadfile/platform-settlements`，使用现有 `./uploadfile:/uploadfile` 挂载；安装和升级复制同版本
Compose 时自动保留此设置，无需在私有 `.env` 增加路径参数。本地源码运行仍使用相对目录。
代码部署后可在管理员页面首次同步火山账单，按供应商账号和官方账期查看总账；账号总账不要求空间绑定。
DeepSeek 的独立财务凭据仅用于固定官方余额端点，实际费用仍需官方金额凭证导入，不能通过余额推算。
需要查看空间归属费用时，再确认供应商计费范围绑定，不能默认把共享账户费用归入当前空间。
配置、数据覆盖和资源包规则见[模型管理](../models/model-management.md#11-模型用量与实际消费管理)。

### 平台额度与模型开通

升级保留用户、空间、额度和历史凭据，不自动发放额度或变更费率。新增通道迁移必须先备份数据库并保留原
`XIAOZHI_CREDENTIAL_ENCRYPTION_KEY`。新调用需要有效通道、足够额度、已发布费率与受支持模型。

开通顺序：有限 OPS 岗位、额度发放池、模型平台通道、平台费率与调用量上限，最后按确认金额发放额度。
通过通道配置接口可在服务端复制选定旧凭据并重新加密；禁止返回明文或批量自动启用所有历史密钥。
模型测试、评测、配置助手和官方知识任务使用显式服务预算与模型/用途范围，调用同样走平台通道。
测试会产生真实供应商调用，不能作为默认部署验证；模型发布审批继续保留。

费率按基础单位配置：LLM 输入/输出 token、ASR 毫秒、TTS 字符，不把每千 token、每分钟报价直接写入。
注册初始额度缺省启用、每用户一次性 100；已有环境的显式配置仍优先，不因镜像升级自动覆盖。
旧用户供应商 Key 不再是服务就绪条件。

注册赠额发布检查：

- 核对容器内生效的 `XIAOZHI_PLATFORM_TRIAL_ENABLED=true`、`XIAOZHI_PLATFORM_TRIAL_AMOUNT=100`，
  不能只检查仓库示例。旧 `false/0` 可通过固定升级入口的同名显式环境变量覆盖并持久保留。
- 通过授权运营入口按批准预算补充发放池；发布程序和 Liquibase 不自动增加池总额，也不直接改账户余额。
- 新注册事务记录赠额意图；核对发放意图状态、`trial_policy` 和幂等 GRANT 流水三者一致。
  缺池/限额导致待发不是已到账；有限重试耗尽后由运维处理。
- 历史漏领只对核实的用户范围使用受保护的补发入口，不能在迁移中全库发额。
- 运行 `node scripts/test-trial-deployment.cjs` 验证默认 100、旧配置保留和显式覆盖。
  真实到账和付费语音测试仍需分别核验，不能用配置测试冒充。

无付费验收读取账户、空间用量、通道配置、费率、服务预算、历史结算，并实际测试各岗位入口、筛选和表单校验。
服务 READY 仅说明存在已发布且已定价的能力通道，不代表已经完成供应商付费调用测试。
核对共享供应商账号的费用绑定，不把共享账号新费用归给某一个空间；保留旧账期证据和原始关联。
新迁移不删除旧表，已开始的旧许可继续按冻结版本结算。回退镜像不能撤销数据库、通道或账目事实，应优先前向修复。

网页入口 `/ops.html`、`/index.html` 和 `/service-worker.js` 使用 `Cache-Control: no-cache, must-revalidate`，
确保刷新时校验新版本，带内容哈希的静态资源继续缓存。已打开的旧页面仍需刷新一次，不清除用户存储或退出其他会话。

### 小团队运营身份维护

运营入口为 `/ops/login`，旧 `ops.html#/login` 书签仍会跳转到新地址。当前选择岗位、输入该账号已有登录密钥即可登录，无需手填账号；登录密钥不是供应商 API Key，
`OPS_LOGIN_MFA_REQUIRED=false` 与 `OPS_LOGIN_CAPTCHA_REQUIRED=false` 分别关闭登录动态验证码和图形验证码；
需要额外验证时在部署私有 `.env` 中显式启用，升级器保留现有值，显式环境变量优先。
启用动态验证码前须确认员工仍持有已绑定的验证器；不要删除或重新生成现有验证器密文。
这些配置只影响运营登录，不更改用户登录、身份申请/恢复、岗位权限、业务审批或租户隔离。
验收时核对身份策略接口的 `mfaRequired`、`captchaRequired` 与部署配置一致，并验证运营页面刷新和旧链接跳转。

已完成首次初始化的环境可按[三账号模式](../architecture/platform-service-and-operations.md#小团队三账号模式)
配置 `ops-keys`（模型与额度）、`ops-resources`（内容与设备）、`ops-finance`（审核与审计，保留历史账号名）。
固定岗位和范围以该架构文档及 `OpsPermissionCatalog` 为准；不分配 IAM 岗位或身份委派权限，
供应商财务、额度总池和额度发放归模型与额度岗位管理，内容与设备岗位不再承担池预算；保留发放/池管理与复核分离，以及发布与审核分离、禁止自批规则。运营账号不得读取用户聊天、音频、私人记忆或知识库正文。

上线时核对匿名只读 `GET /ops/auth/identity-policy` 返回网页身份申请、恢复不可用；
前端和服务端均须拒绝 `enroll`、`recover`，且拒绝发生在消费恢复码之前。
只有启用网页 IAM 自助模式才需持续配备至少两名独立治理人员；当前首次初始化清单仍要求 IAM 申请与审批岗位，
不能把三账号配置直接用于首次 bootstrap，也不能通过删除员工或重置初始化状态重开 bootstrap。

此模式的员工新增、登录因素恢复和岗位调整纳入受控部署维护：先备份数据库与相关加密主密钥，
核实操作对象、身份依据和授权范围，保留已有员工 ID、业务归属和审计历史，记录维护操作并撤销受影响会话。
密码、TOTP 种子及恢复码只向本人安全交付，不进入仓库、镜像、命令输出或日志。
当前没有通用恢复工具；不得将维护要求实现为新增公网重置接口，或以维护为由跳过核验和业务审批。

```text
开发/构建机：完整源码 -> 构建镜像 -> 推送阿里云 ACR
生产服务器：引导脚本 -> 拉取版本化 deploy-assets -> 拉取匹配镜像 -> Docker Compose 启动
```

生产服务器不编译源码，不安装 Maven、Node.js、Conda、Python 依赖或 FunASR 模型。所有运行代码、模型依赖和 CPU FunASR 运行时都必须已经在镜像里。

---

## 1. 先确认三件事

### 1.1 生产使用完整版本包

每次发布同时写入 ACR：

```text
xiaozhi-server:<version>
xiaozhi-manager-api:<version>
xiaozhi-manager-web:<version>
xiaozhi-deploy-assets:<version>
  ├── docker-compose.yml
  ├── install / upgrade
  ├── config_from_api.yaml
  ├── release-manifest.env
  └── release-contract.env
```

生产服务器 Git 中的 `deploy/production/install` 只作为首次引导或故障恢复入口。
首次部署成功后，发布资产会自动安装并持续更新唯一的日常生产命令
`~/xiaozhi-upgrade`。`VERSION=<version> ~/xiaozhi-upgrade` 会从 ACR 拉取同版本
deploy-assets，校验 manifest 与环境变量契约，再执行其中的 Compose 和安装逻辑。
因此业务镜像不再与服务器当前 Git 分支中的旧部署资产混用。

正确顺序：

```bash
git checkout main
git pull --ff-only origin main

# 生产仍建议从已评审提交发布；技术上版本包可来自任意干净 commit
scripts/acr-login
scripts/acr-build-push

# 生产服务器日常只使用固定入口
VERSION=<git短哈希> ~/xiaozhi-upgrade
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
xiaozhi-deploy-assets:latest
xiaozhi-deploy-assets:<git短哈希>
```

生产固定入口默认使用 `VERSION=latest`。升级器先读取
`xiaozhi-deploy-assets:latest` 中记录的不可变版本，再部署对应短哈希业务镜像：

```bash
~/xiaozhi-upgrade
```

只有要固定版本或回滚时才使用：

```bash
VERSION=<git短哈希> ~/xiaozhi-upgrade
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
~/xiaozhi-data/uploadfile    OTA 固件与人物人脸照片
~/xiaozhi-data/music         音乐文件
```

旧部署如果仍要使用 `/opt/xiaozhi-data`，必须显式指定：

```bash
DATA_DIR=/opt/xiaozhi-data ./install
```

首次安装会把该数据目录写入固定升级命令；后续直接运行 `~/xiaozhi-upgrade` 即可，
不需要重复指定 `DATA_DIR`。临时覆盖时仍可显式传入。

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
xiaozhi-deploy-assets:latest   版本化 Compose、安装脚本、manifest 与环境变量契约
mysql:8.4                MySQL 运行时镜像
redis:8.0                Redis 运行时镜像
```

首次启用完整版本发布前，确认 ACR 命名空间允许创建或已经创建
`xiaozhi-deploy-assets` 仓库，并使用与其他业务镜像相同的访问权限。

构建缓存镜像：

```text
xiaozhi-server-base-common:latest / base-<依赖哈希>
xiaozhi-server-base-lite:latest / base-<依赖哈希>
xiaozhi-server-base-funasr:latest / base-<依赖哈希>
xiaozhi-manager-api:api-<内容哈希>
xiaozhi-manager-web:web-<内容哈希>
xiaozhi-manager-api:buildcache-v1
xiaozhi-manager-web:buildcache-v1
```

这些 base/cache 镜像只用于加速构建，不由生产 compose 直接启动。FunASR base 从 lite base 追加重依赖，lite 和 FunASR 共享 common/lite 缓存层；manager-api 和 manager-web 分别按内容哈希复用缓存。只改 Vue 页面时不会重建 Java API，只改 Java 时不会重建前端镜像。
`buildcache-v1` 是 BuildKit 的跨构建机中间层缓存，只在 buildx 推送构建时读写，不能作为
生产运行镜像启动。

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
BUILD_ENGINE=docker          默认单平台 linux/amd64 构建，复用 Docker daemon 本地缓存
PUSH=true                    构建后推送 ACR
BUILDKIT_REGISTRY_CACHE=auto buildx 推送时只复用已存在的 manager-api / manager-web ACR BuildKit 缓存
SERVER_INSTALL_PROFILE=lite 不安装本地 FunASR，ASR 使用远程 provider
基础镜像默认使用 docker.m.daocloud.io 前缀，避免失效 Docker daemon mirror
BUILD_BASE=auto 只复用精确依赖 hash 的 base 缓存，不默认用 latest 冒充
manager-api / manager-web 分别复用 api-/web-<内容哈希>，未变化组件不重跑构建
同时发布 xiaozhi-deploy-assets:latest 和 :<短哈希>，与三个业务镜像组成完整版本
PUSH=true 时拒绝脏工作区，避免 commit 标签与实际发布内容不一致
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
| 构建多架构镜像 | `PLATFORMS=linux/amd64,linux/arm64 scripts/acr-build-push` |
| 临时回退 Docker engine（不使用 registry cache） | `BUILD_ENGINE=docker scripts/acr-build-push` |
| buildx 推送时禁用 ACR BuildKit 缓存 | `BUILDKIT_REGISTRY_CACHE=false scripts/acr-build-push` |
| 降低构建机并发负载 | `PARALLEL_RELEASE_STEPS=false PARALLEL_IMAGE_BUILDS=false scripts/acr-build-push` |

本地只验证镜像、不推送 ACR 时，可以显式关闭推送：

```bash
PUSH=false BUILD_BASE=false scripts/acr-build-push-lite
```

这只会更新当前 Docker daemon 里的本地标签。`deploy/production/install` 和
`~/xiaozhi-upgrade` 是生产日常拉取入口，会先从 ACR `docker pull` 对应
`VERSION`，因此会覆盖同名本地标签。没有把修复后的
`xiaozhi-server`、`xiaozhi-manager-api`、`xiaozhi-manager-web`、
对应的 manager-api / manager-web 镜像推送到 ACR 前，不要用生产升级命令验证本地镜像。
需要临时验证本地镜像时，只能在确认 `~/xiaozhi-data/.env` 指向同名本地标签后，
用 Docker Compose 直接重建目标服务，且不要先执行 pull：

```bash
docker compose -f ~/xiaozhi-data/docker-compose.yml --env-file ~/xiaozhi-data/.env \
  up -d --no-deps --force-recreate xiaozhi-esp32-server-web
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
2. 拉取 xiaozhi-server、xiaozhi-manager-api、xiaozhi-manager-web、mysql、redis 镜像
3. 创建 ~/xiaozhi-data
4. 生成 ~/xiaozhi-data/.env 和 docker-compose.yml，并持久化第三方凭据加密密钥
5. 初始化 data/.config.yaml
6. 修正 bind mount 目录属主，让非 root 容器可写
7. 启动 MySQL、Redis、manager-api/web、xiaozhi-server
8. 从数据库读取 manager-api 的 server.secret，写回 .config.yaml
9. 重启 xiaozhi-server
10. 安装或更新唯一的日常生产命令 ~/xiaozhi-upgrade
```

首次安装成功后会输出类似：

```text
=== Deploy complete ===
Version: latest
manager-web: http://<服务器IP>:8002
websocket:   ws://<服务器IP>:8000/xiaozhi/v1/
upgrade:     VERSION=<version> ~/xiaozhi-upgrade
```

---

## 5. 升级

标准升级：

```bash
VERSION=<git短哈希> ~/xiaozhi-upgrade
```

`~/xiaozhi-upgrade` 会先拉取 `xiaozhi-deploy-assets:<VERSION>`，校验其中的
`release-manifest.env` 和 `release-contract.env`，再使用 manifest 记录的镜像仓库
和解析出的不可变版本执行安装。`~/xiaozhi-data/.env` 会记录资产仓库、实际版本、
源码 commit 和契约版本，后续升级继续复用。每次部署还会原子刷新固定入口及其
数据目录内的解析器，因此日常升级不依赖生产服务器 Git 分支。

`XIAOZHI_CREDENTIAL_ENCRYPTION_KEY` 用于解密智能体级飞书等第三方凭据。升级必须保留原值；
直接更换会导致历史密文无法解密。

回滚或固定版本：

```bash
VERSION=<git短哈希> ~/xiaozhi-upgrade
```

升级不会删除：

```text
~/xiaozhi-data/mysql/data
~/xiaozhi-data/redis/data
~/xiaozhi-data/data
~/xiaozhi-data/uploadfile
~/xiaozhi-data/music
```

### 5.1 当前升级的中断边界

> 状态说明（2026-08-05）：下面记录的是当前实现事实和后续优化目标。现有
> `~/xiaozhi-upgrade` 仍是单实例原地替换，不具备滚动发布或蓝绿切流能力；在蓝绿部署资产
> 和排空逻辑真正实现、验证并发布前，不得把标准升级描述为“零停机”。

当前脚本会先完成发布资产解析和镜像拉取，再执行生产 Compose 的 `up -d`。拉取镜像阶段
不会影响正在运行的服务，但三个应用容器使用固定容器名和固定宿主机端口：

```text
xiaozhi-server       8000 / 8003
manager-web          8002
manager-api          Docker 内部 8003
```

镜像或 Compose 配置变化时，新旧应用容器无法同时绑定同一端口，Compose 必须替换旧容器。
因此当前升级可能出现以下中断：

| 链路 | 当前中断来源 | 影响 |
|------|--------------|------|
| manager API / Web | manager-api 冷启动、Liquibase 迁移、manager-web 容器替换 | 页面请求可能失败或短暂不可访问 |
| 设备 WebSocket | xiaozhi-server 容器替换 | 已建立的设备连接和正在进行的语音轮次会断开 |
| Python HTTP | xiaozhi-server 8003 端口替换 | 视觉、MCP、评测等 HTTP 请求可能失败 |
| 回滚 | 目标 Compose 和 `.env` 已写入后新服务启动失败 | 需要人工固定旧版本重新执行升级；数据库变化不随镜像自动回滚 |

manager-api 当前以容器健康状态等待 8003 端口就绪，等待预算默认 900 秒；这能避免脚本过早
报告成功，但不能消除等待期间的单实例中断。xiaozhi-server 的升级后检查目前只确认容器处于
`running` 且重启计数稳定，没有验证完整应用 readiness。`sync_manager_api_secret` 之后的
“重启”步骤使用 `compose up -d`，配置未触发 Compose 重建时不保证进程真的重启；首装或
`server.secret` 变化时必须额外确认 Python 已重新加载私有配置。

现有宿主机 Nginx 示例也只代理固定的 `127.0.0.1:8000/8002/8003`，没有第二组 upstream、
原子切流和旧连接排空能力。仅增加健康检查或加快镜像拉取可以缩短中断，但无法让固定端口的
单实例替换变成无中断发布。

### 5.2 无痕升级的目标定义

本项目的目标应分成两类：

- HTTP、页面和新建 WebSocket：新版本完全 ready 后才切流；新版本失败时旧版本继续服务，
  切流过程不产生可感知空窗。
- 已建立的设备 WebSocket：连接不能跨 Python 进程迁移。切流后必须让旧版本继续承载存量
  连接，等待当前语音轮次结束或设备空闲后再通知重连，不能直接停止旧容器。

目标拓扑为只复制应用层、继续共享持久化基础设施：

```text
设备 / 浏览器
      |
宿主机 Nginx 或云 SLB（80/443 稳定入口）
      |
      +-- active:   Green server + manager-api + manager-web
      +-- draining: Blue  server + manager-api + manager-web
                         |
                  共享 MySQL + Redis
```

Blue/Green 必须使用不同的 Compose project、容器名和仅绑定回环地址的宿主机端口，例如：

```text
Blue   18000 / 18002 / 18003
Green  28000 / 28002 / 28003
```

端口只是示例，实际实现必须通过生成的环境变量或 Compose override 管理，不能把颜色和端口
散落硬编码到多个脚本中。MySQL、Redis 不随颜色复制；应用容器通过独立颜色网络和稳定网络
别名访问共享基础设施。同一颜色的 manager-web、manager-api、xiaozhi-server 必须互相调用
同色实例，避免 Green manager-api 错误调用仍处于 draining 的 Blue Python。

### 5.3 蓝绿升级顺序

后续实现蓝绿部署时，升级器必须遵守以下顺序：

1. 使用 `flock` 或等效机制获取部署互斥锁，禁止两个升级进程同时修改运行状态。
2. 解析发布资产，拉取不可变版本镜像并记录镜像 digest；保留当前 active 和 last-good 版本。
3. 在非活动颜色启动候选应用，不修改 Nginx 当前 upstream，也不停止旧版本。
4. 完成候选版本数据库迁移和应用级 readiness 检查。健康检查不得真实调用外部模型，但至少覆盖：
   manager-api 应用就绪、manager-web 页面与 API 代理、Python 8003 健康接口和 WebSocket 握手。
5. readiness 全部通过后，生成新的 Nginx upstream；先执行 `nginx -t`，再原子替换 include
   或软链接并 reload。reload 失败时保持旧 upstream。
6. 新 HTTP 请求和新 WebSocket 进入候选版本；旧版本进入 draining，不再接收新连接。
7. 旧 Python 按活动连接数和语音轮次状态排空。空闲设备可使用 WebSocket
   `1012 Service Restart` 通知重连；不得在正在播报、识别或工具调用时强制切断。
8. 等待已有 HTTP 请求和 WebSocket 会话结束；达到明确的最大排空时间后，记录仍未排空的
   设备，再按发布策略终止旧版本。
9. 切流和排空验证成功后才写入 active、previous、last-good、版本、digest 和发布时间，
   然后停止旧应用。不得在候选版本 ready 前覆盖最后可用状态。

候选版本在切流前失败时，只清理候选应用，旧版本继续运行。切流后出现问题时，可以原子把
Nginx upstream 切回 previous，但仅当数据库仍兼容旧版本时才允许回切。镜像回滚不等于
数据库回滚，升级器不得自动执行反向 DDL 或恢复数据库备份。

### 5.4 蓝绿发布的兼容性门禁

#### 数据库

蓝绿期间新旧 manager-api 会短暂共享同一个数据库，所有迁移必须采用 expand/contract：

```text
Release N      只新增兼容表、可空字段、默认值和兼容索引，新旧代码均可运行
Release N+1    新代码完成读写切换，保留旧字段兼容
Release N+2    确认没有旧实例和回滚需求后，才允许清理旧字段
```

同一版本升级中禁止删除或重命名旧版本仍会访问的表、列、索引，不得立即改变字段语义或收紧
约束。可能长时间锁表的数据回填和 DDL 必须先在生产同构数据量上验证，并与应用切流解耦。
无法满足双版本兼容的迁移必须明确进入维护窗口，不能标记为无痕升级。

#### 定时任务与运行状态

manager-api 存在定时任务，Blue/Green 重叠期间可能重复执行。进入蓝绿前必须为定时任务增加
Redis/MySQL 分布式锁，或拆成独立的单实例 worker。Python 的在线设备注册表属于进程内状态，
不能在两个实例之间直接共享或迁移；发布期间必须保证同一个 WebSocket 始终固定在建立它的
实例上。

`data`、`music`、`uploadfile` 等共享目录也要逐项确认并发读写边界。`.auth_key` 和私有配置
必须在启动候选实例前准备好，避免候选启动后再次改配置并重启；会被多个实例写入的缓存、
元数据或临时文件必须改为原子写入、单写者或实例隔离目录。

#### 优雅关闭

- xiaozhi-server 需要 drain 状态、活动连接指标和最大排空时间；收到 SIGTERM 后先停止接入，
  再等待连接清理，不能立即取消 WebSocket 主任务。
- manager-api 必须启用 Spring graceful shutdown，并给正在处理的 HTTP 请求和后台事务合理的
  完成预算。
- manager-web Nginx 容器应使用优雅退出信号，宿主机 Nginx reload 必须保留已有 worker，
  让旧 WebSocket upstream 连接继续存活。

### 5.5 资源和实施顺序

当前默认资源上限按 2 核 4G 且 CPU FunASR 设计，五个容器的内存上限合计约 4G；仅再复制
server、manager-api、manager-web 就会增加约 3.1G 上限。在 2 核 4G 主机上直接运行完整
Blue/Green 不安全，可能因 FunASR 或 Java 冷启动触发 OOM。

可选生产方案：

| 方案 | 能力 | 资源与限制 |
|------|------|------------|
| 当前单实例优化 | 只更新实际变化的组件、完善 readiness、部署锁和失败恢复 | 能缩短中断，不能消除固定端口替换空窗 |
| 同机 Blue/Green | HTTP 接近零中断，WebSocket 可排空 | 建议至少 4 核 8G；或使用 lite profile 和远程 ASR |
| 双机应用层 + SLB | 升级无空窗并可承受单机故障 | 最稳妥；MySQL、Redis 应独立或使用托管高可用服务 |

建议按以下顺序实施，不把所有行为一次性塞进现有 `install` 巨型脚本：

1. 发布 manifest 记录每个组件的不可变 tag/digest，只替换实际变化的组件。
2. 增加部署锁、应用级 readiness、previous/last-good 状态和失败恢复。
3. 增加 Spring、Python、Nginx 优雅关闭及 WebSocket drain 能力。
4. 将蓝绿编排、健康检查、Nginx 切流、排空和状态提交拆成独立部署组件，由固定升级入口编排。
5. 增加 expand/contract 迁移审查和新旧两个版本的兼容测试。
6. 资源满足后启用同机蓝绿；需要主机级高可用时迁移到双机和 SLB。

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

首次引导安装和后续 `~/xiaozhi-upgrade` 都会在 manager-api 启动后自动读取数据库中的 `server.secret`，写入：

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
https://yh.wejoy.link/xiaozhi/ota/       -> 127.0.0.1:8002/xiaozhi/ota/
https://yh.wejoy.link/mcp/vision/explain -> 127.0.0.1:8003/mcp/vision/explain
https://yh.wejoy.link/mcp/vision/images/ -> 127.0.0.1:8003/mcp/vision/images/
wss://yh.wejoy.link/xiaozhi/v1/          -> 127.0.0.1:8000/xiaozhi/v1/
wss://yh.wejoy.link/xiaozhi/audio-preview/ -> 127.0.0.1:8000/xiaozhi/audio-preview/
```

在服务器 `~/xiaozhi-data/.env` 中设置：

```bash
XIAOZHI_PUBLIC_DOMAIN=yh.wejoy.link
XIAOZHI_PUBLIC_BASE_URL=https://yh.wejoy.link
XIAOZHI_CORS_ORIGINS=https://yh.wejoy.link
```

然后执行 `~/xiaozhi-upgrade`。HTTPS 打通后，在微信公众平台把
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

Certbot 默认不会把证书写到 `/etc/nginx/certs/`。如果模板或旧配置里还有下面这种
占位路径，必须改成上面的 `/etc/letsencrypt/live/yh.wejoy.link/`：

```nginx
ssl_certificate     /etc/nginx/certs/yh.wejoy.link/fullchain.pem;
ssl_certificate_key /etc/nginx/certs/yh.wejoy.link/privkey.pem;
```

可以用下面命令确认实际证书文件存在：

```bash
sudo ls -l /etc/letsencrypt/live/yh.wejoy.link/
```

模板中的关键映射必须保持一致：

```text
https://yh.wejoy.link/                   -> http://127.0.0.1:8002/
https://yh.wejoy.link/xiaozhi/           -> http://127.0.0.1:8002/xiaozhi/
https://yh.wejoy.link/xiaozhi/ota/       -> http://127.0.0.1:8002/xiaozhi/ota/
https://yh.wejoy.link/mcp/vision/explain -> http://127.0.0.1:8003/mcp/vision/explain
wss://yh.wejoy.link/xiaozhi/v1/          -> ws://127.0.0.1:8000/xiaozhi/v1/
wss://yh.wejoy.link/xiaozhi/audio-preview/ -> ws://127.0.0.1:8000/xiaozhi/audio-preview/
```

全模块生产部署中，`/xiaozhi/ota/` 必须反代到 `manager-api / manager-web`
容器暴露的 `8002`。Python `xiaozhi-server` 在读取后台配置时不会注册
`/xiaozhi/ota/` 路由，反代到 `8003` 会导致后台保存 `server.ota` 时出现
“OTA接口验证失败”。

如果曾经直接对 Nginx 默认站点执行过 `certbot --nginx -d yh.wejoy.link`，Certbot
可能已经把 `server_name yh.wejoy.link` 写入
`/etc/nginx/sites-enabled/default`。此时再启用 `xiaozhi.conf` 会形成同一域名的
重复 server block，`sudo nginx -t` 会出现类似警告：

```text
conflicting server name "yh.wejoy.link" on 0.0.0.0:80, ignored
conflicting server name "yh.wejoy.link" on 0.0.0.0:443, ignored
```

这个问题的典型现象是：`https://yh.wejoy.link/` 显示 Ubuntu/Nginx 默认页，
`https://yh.wejoy.link/xiaozhi/...` 返回 Nginx 404，但容器的 `8000/8002/8003`
端口和本机 `127.0.0.1:8002` 访问都正常。

启用 `xiaozhi.conf` 后，必须确保 `yh.wejoy.link` 只在一个启用站点中声明。
推荐禁用默认站点的软链接，保留 `sites-available/default` 原文件作为系统参考：

```bash
sudo grep -R "server_name .*yh.wejoy.link" -n /etc/nginx/sites-enabled /etc/nginx/conf.d
sudo unlink /etc/nginx/sites-enabled/default
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
VERSION=latest ~/xiaozhi-upgrade
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

构建缓存分布在构建机和 ACR，作用不同：

| 层 | 缓存方式 | 什么时候会重新构建或上传 |
|----|----------|--------------------------|
| 系统依赖 | Docker layer + ACR `xiaozhi-server-base-common:base-<依赖哈希>` | 改 Python 基础镜像、系统包或 common base Dockerfile |
| 普通 Python 依赖 | Docker layer + ACR `xiaozhi-server-base-lite:base-<依赖哈希>` | 改 `requirements-docker.txt` 或 lite base Dockerfile |
| FunASR 追加依赖 | Docker layer + ACR `xiaozhi-server-base-funasr:base-<依赖哈希>` | 改 `requirements-docker-funasr-extra.txt` 或 FunASR base Dockerfile |
| SenseVoiceSmall 模型 | 本地 `model.pt` + 镜像 layer | 第一次打包模型，或替换模型文件 |
| manager-api 完整内容 | ACR `xiaozhi-manager-api:api-<内容哈希>` + channel/version tag | 改 Java 源码、`pom.xml`、API Dockerfile 或 API 启动脚本 |
| manager-web 完整内容 | ACR `xiaozhi-manager-web:web-<内容哈希>` + channel/version tag | 改 Vue 源码、前端构建配置、Web Nginx 配置或前端基础镜像 |
| manager-api 构建中间层 | buildx 本地缓存 + ACR `xiaozhi-manager-api:buildcache-v1` | Maven 依赖输入、基础镜像、Dockerfile 步骤、builder 或目标平台变化 |
| manager-web 构建中间层 | buildx 本地缓存 + ACR `xiaozhi-manager-web:buildcache-v1` | npm 锁文件、基础镜像、Dockerfile 步骤、builder 或目标平台变化 |
| 服务端业务代码 | Docker layer + `:<git短哈希>` / channel tag | 每次 Python 服务代码变化 |

Docker engine 和每个 buildx builder 都有自己的本地 layer/cache 存储。Dockerfile 中的
BuildKit cache mount 也留在对应 builder 内，例如 `/root/.npm`、`/root/.m2`、
`/root/.cache/pip`、`/var/cache/apt` 和 `/var/lib/apt/lists`。这些 mount 能加速同一
builder 的后续构建，但不能当作生产镜像内容，也不能假定换一台构建机后仍然存在。
manager-api 的依赖预取和 JAR 打包共用具名 Maven cache mount，并为连接、响应和重试设置
有限预算；某个 Maven CDN 节点失效时应在预算内重连或明确失败，不得无限停在
`Scanning for projects...`。不得通过清理 Docker 或 Maven 缓存处理这类网络故障。

`base-<依赖哈希>` 按构建参数、逻辑文件路径和文件内容计算，不包含 Git worktree
的绝对路径。多个 worktree 构建同一份内容时，会复用同一批
`xiaozhi-server-base-*` ACR 缓存。脚本会兼容探测旧版路径相关 cache tag；
如果旧 tag 存在而新稳定 tag 不存在，会给旧镜像追加新 tag 并推送，不删除旧缓存。
需要只对比缓存 tag、不触发构建时，可以在不同 worktree 分别运行：

```bash
PRINT_BUILD_CONFIG_ONLY=true scripts/acr-build-push-lite
```

该命令只打印解析后的构建配置和缓存 tag，在创建或重建 buildx builder 之前退出；它不会
构建、推送、重打 tag 或删除镜像，也不会改动本地 builder。

manager-api / manager-web 还有一层跨构建机 BuildKit registry cache。开关和目标如下：

```text
BUILDKIT_REGISTRY_CACHE=auto|true|false
MANAGER_API_REGISTRY_CACHE_IMAGE=<manager-api仓库>:buildcache-v1
MANAGER_WEB_REGISTRY_CACHE_IMAGE=<manager-web仓库>:buildcache-v1
```

默认 `auto`。同时满足 `BUILD_ENGINE=buildx`、`PUSH=true` 且对应 `buildcache-v1` 已存在时，
脚本才给 manager-api / manager-web 构建传入 registry `cache-from` 和 `cache-to mode=max`。
缓存不存在时，`auto` 会跳过首次深缓存导出，避免正常业务发布被 Maven/npm 全量缓存上传阻塞。
`true` 用于显式创建或更新深缓存，`false` 用于显式关闭；`BUILD_ENGINE=docker` 或
`PUSH=false` 时不会读写 registry cache。也可以覆盖两个 cache image 变量，把缓存放到有推拉
权限的专用仓库。
内容哈希镜像与 registry cache 是两层机制：`api-/web-<内容哈希>` 命中时直接复用完整组件
镜像；组件必须重建时，`buildcache-v1` 才帮助复用 Maven/npm 等中间层。

显式 `BUILD_ENGINE=buildx` 时，manager-web builder 使用
固定 digest 的 `node:20-bookworm-slim`，减少本地镜像维护后需要重新拉取的基础层体积。
脚本通过 `docker buildx imagetools inspect` 检查 ACR 中的 OCI image/index；元数据命中时
不会为了“检查缓存是否存在”先把完整镜像拉入本地 Docker daemon。buildx 发布时远端
retag 失败会直接停止，避免用本地 `pull/tag/push` 把 OCI index 降成单平台 manifest。
默认 `PUSH=true` 发布路径的存在性检查不会执行完整 `docker pull`。显式使用 Docker engine
的单平台回退，或 `PUSH=false` 本地复用完整组件镜像时，才会按需拉取镜像。

正式发布使用 `xiaozhi-builder-v2`。脚本不会自动继承 Docker daemon 的 HTTP(S) proxy，
因为 daemon 中常见的 `127.0.0.1` / `localhost` 地址进入 docker-container builder 后会指向
容器自身。确实需要代理时，必须显式设置 builder 容器可访问的
`BUILDKIT_HTTP_PROXY`、`BUILDKIT_HTTPS_PROXY` 和 `BUILDKIT_NO_PROXY`。底层
`docker-build-private.sh` 独立运行仍默认 Docker engine；显式执行
`BUILD_ENGINE=buildx PUSH=false` 时必须设置 `BUILD_BASE=false` 使用 registry-backed base，
或显式传入 `SERVER_BASE_IMAGE`，否则脚本会拒绝不可消费的 daemon-only base 缓存组合。
如果 ACR 的 base tag manifest 可见但引用的 config/layer blob 暂时不可读，服务镜像步骤会
把该缓存视为 miss，强制重建对应 profile 的 base 并只重试一次；普通 Dockerfile 编译错误
不会触发这个重建重试。

以下情况即使源码改动不大，也可能重新下载依赖：

- 新建或显式重建 buildx builder；不同 builder 的本地缓存相互独立。
- 在 `docker` 与 `buildx` 间切换，或改变 builder 名称、driver、目标平台。
- 基础镜像、依赖锁文件、Dockerfile 步骤、构建参数或其他 cache key 输入发生变化。
- registry cache 被关闭、尚未由成功构建写入，或指定了新的 cache image/version。
- ACR 登录、仓库权限或网络异常，导致远端缓存无法导入或导出；脚本不会删除本地缓存来处理这类问题。

日常只改业务逻辑时，直接运行：

```bash
scripts/acr-build-push
```

不要执行 `docker builder prune`、`docker buildx prune`、`docker system prune`、
`docker image prune`，也不要删除 Docker/BuildKit/ACR 构建缓存或：

```text
main/xiaozhi-server/models/SenseVoiceSmall/model.pt
```

### 7.2 本地旧业务镜像维护

需要评估构建机上的旧项目镜像时，先运行默认 dry-run：

```bash
scripts/docker-image-maintain
```

脚本只读取本地 Docker 状态并列出候选项，默认每个项目仓库保留最新 8 个不同镜像；dry-run
不会删除、重打 tag 或推送任何镜像。它只管理本地的 xiaozhi server、manager-api、
manager-web、deploy-assets 及其 `-local` 仓库，并保护：

- `latest`、当前 Git 版本及其 profile tag、当前 `api-/web-<内容哈希>` 和 `buildcache-*`。
- 任何已有容器引用的镜像，以及每个项目仓库最新 N 个不同镜像。
- 所有 `xiaozhi-server-base-*`、非项目 tag、运行时镜像、卷和 ACR 远端镜像。

确认 dry-run 的候选引用无误后，才可以显式应用同一套规则：

```bash
scripts/docker-image-maintain --keep 8 --apply
```

`--apply` 是有删除效果的操作：它会对当次重新计算出的候选执行 `docker image rm`，删除列出的
本地项目镜像引用。它不会运行任何 prune，不会删除 BuildKit/Docker cache，不会删除 server
base、MySQL/Redis、卷或 ACR 远端镜像，也不会重打 tag。候选镜像可能共享 layer，dry-run
显示的 virtual size 是理论上限，不代表实际可回收空间。`--keep` 允许非负整数；减小它会扩大
候选范围，执行前必须再次审阅完整列表。

### 7.3 生产服务器 pull 缓存

生产服务器不会构建，只会 `docker pull`。Docker 会自动复用本机已有镜像层：

```text
基础依赖层没变   不重复下载 CPU PyTorch/FunASR
模型层没变       不重复下载 SenseVoiceSmall layer
业务层变了       只拉取对应组件的新业务层
```

所以升级速度取决于本次镜像和上次镜像有多少 layer 变化。拆分后，常见小改动只影响一个组件镜像：只改 Vue 页面时通常只拉 `xiaozhi-manager-web` 的新层；只改 Java 时只拉 `xiaozhi-manager-api`。

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
uploadfile  100:101   manager-api 写 OTA 固件与人物人脸照片（容器内 `/uploadfile/person-faces`）
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
docker logs -f xiaozhi-esp32-server-db
docker logs -f xiaozhi-esp32-server-redis
```

单服务重启：

```bash
docker restart xiaozhi-esp32-server
docker restart xiaozhi-esp32-server-web
docker restart xiaozhi-esp32-manager-api
```

---

## 10. 资源调优

默认按 2 核 4G、CPU FunASR 设计：

| 容器 | CPU | 内存 |
|------|-----|------|
| xiaozhi-server | 1.00 | 2048m |
| xiaozhi-manager-api | 0.50 | 896m |
| xiaozhi-manager-web | 0.20 | 192m |
| MySQL | 0.30 | 768m |
| Redis | 0.10 | 192m |

`memswap_limit` 默认等于 `mem_limit`，避免小机器进入长期 swap。Python 容器低于 2G 时，CPU FunASR/SenseVoiceSmall 更容易启动失败或被 OOM kill。

生产 Compose 显式启用 `SPRING_PROFILES_ACTIVE=prod`：manager-api 使用生产连接池、关闭
Knife4j，并把 Tomcat 工作线程限制为 100、最小空闲线程限制为 10。不要在生产环境改回
`dev`；开发 profile 的 1000 个 Tomcat 工作线程会在突发流量下放大线程栈内存，绕过 JVM
堆占比带来的保护。

Python 服务默认限制同时在线设备 WebSocket 为 64 个。单模块配置可通过
`server.max_device_connections` 调整；使用智控台时应在下发的服务配置中设置同名字段。扩容前
先按实际 ASR/TTS provider 测量单连接峰值内存，达到上限的新连接会收到 1013 并稍后重试。
`dialogue.max_history_turns` 限制发送给 LLM 的最近轮次；独立的
`dialogue.max_stored_messages` 限制 Python 进程内实体会话历史，默认 200 条，与长期记忆整理
当前最多消费的消息数一致。这样既不会把模型短上下文误当成完整会话缓存，也不会让长连接
历史无限增长。

每个连接还具有固定运行时预算：ASR 输入最多 300 帧/1 MiB、当前语句最多 2000 帧/2 MiB，
聊天上报最多 32 条/4 MiB，业务线程池最多 5 个运行任务和 16 个等待任务。ASR 过载时会
丢弃不连续的当前语音分段，避免把缺帧音频送去识别；上报或业务队列满载时拒绝新任务并
记录告警。manager-api 的专业评测使用 2 个 worker 和 32 个等待槽，满载任务会明确失败，
不会继续堆积在 JVM 中。

调整 `~/xiaozhi-data/.env` 后执行：

```bash
~/xiaozhi-upgrade
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
XIAOZHI_MYSQL_MEM_LIMIT=768m
XIAOZHI_REDIS_MEM_LIMIT=192m
XIAOZHI_LIQUIBASE_SOCKET_TIMEOUT_MS=0
XIAOZHI_MANAGER_API_STARTUP_TIMEOUT_SECONDS=900
```

`XIAOZHI_LIQUIBASE_SOCKET_TIMEOUT_MS=0` 只作用于 Liquibase 专用连接，表示迁移 SQL
不使用 JDBC 读超时；manager-api 普通业务连接仍保留 30 秒 `socketTimeout`。这是为了
避免历史数据回填或大表 DDL 超过 30 秒后连接失效，进而连解锁 SQL 也无法执行。安装器
仍有独立的 manager-api 启动等待上限，默认 900 秒，并以容器 8003 端口真实可用作为就绪
条件，而不是只检查 Java 进程存在或搜索历史启动日志。

旧部署如果 `.env` 里仍保留 `XIAOZHI_SERVER_MEM_LIMIT=768m`，升级脚本会尊重旧值。启用 FunASR 前必须手工调到至少 `2048m`。

---

## 11. 更换仓库或镜像

构建推送到其他 ACR namespace：

```bash
ACR_REGISTRY=你的registry \
ACR_NAMESPACE=你的namespace \
scripts/acr-build-push
```

使用其他 ACR 仓库时，在构建发布阶段统一指定业务镜像和部署资产仓库：

```bash
SERVER_IMAGE_REPOSITORY=你的仓库/xiaozhi-server \
MANAGER_API_IMAGE_REPOSITORY=你的仓库/xiaozhi-manager-api \
MANAGER_WEB_IMAGE_REPOSITORY=你的仓库/xiaozhi-manager-web \
DEPLOY_ASSETS_IMAGE_REPOSITORY=你的仓库/xiaozhi-deploy-assets \
MYSQL_IMAGE_REPOSITORY=你的仓库/mysql \
REDIS_IMAGE_REPOSITORY=你的仓库/redis \
scripts/acr-build-push
```

生产服务器只需要指定部署资产仓库，业务镜像和运行时镜像从 manifest 读取：

```bash
DEPLOY_ASSETS_IMAGE_REPOSITORY=你的仓库/xiaozhi-deploy-assets \
VERSION=<版本> ~/xiaozhi-upgrade
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
docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-deploy-assets:latest
docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/mysql:8.4
docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/redis:8.0

sudo docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-server:latest
sudo docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-manager-api:latest
sudo docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-manager-web:latest
sudo docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-deploy-assets:latest
sudo docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/mysql:8.4
sudo docker pull crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/redis:8.0
```

如果手工 `docker pull` 成功但首次引导安装失败，通常是普通用户和 `sudo` 使用了不同的 Docker config。按脚本打印的 `docker:` 行重新登录。

如果提示 repository does not exist，常见原因是：

```text
镜像还没有推送到 ACR
ACR namespace 写错，不是 yihuixiaozhi
登录的是普通 docker，但脚本实际使用 sudo docker
只推送了 xiaozhi-server，没有推送 xiaozhi-manager-api/xiaozhi-manager-web/mysql/redis
```

### 12.2 manager-api 启动超时

查看日志：

```bash
docker logs --tail 120 xiaozhi-esp32-manager-api
docker logs --tail 120 xiaozhi-esp32-server-db
```

重点检查 MySQL 是否启动、Liquibase 是否报错、数据库密码是否和 `.env` 一致。

如果日志持续出现 `Waiting for changelog lock`，先区分正在执行的正常迁移和异常退出留下的
遗留锁。处理遗留锁前必须备份生产数据库、停止所有 manager-api 实例，并通过 MySQL
`SHOW PROCESSLIST` 确认没有迁移 SQL 仍在运行。不得在迁移仍活跃时修改
`DATABASECHANGELOGLOCK`，也不得删除 `DATABASECHANGELOG`。如果同一 changeSet 已经部分
执行，还要先核对其字段、数据回填和登记状态；只清锁但不修复原始超时会让重启再次失败。

生产 Compose 为 Liquibase 使用独立的无读超时连接，并让升级器默认等待 900 秒。旧部署
如果仍使用普通业务连接的 30 秒超时，应先发布包含该部署修复的新版本，再按上述流程恢复
遗留锁；不要通过回改已经执行过的 changeSet 绕过问题。

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

#### 12.2.1 Spring 循环依赖事故复盘（2026-08-04）

版本 `44a9b00e` 在生产更新时，MySQL、Redis 和 Liquibase 均正常，但
`xiaozhi-esp32-manager-api` 始终不能通过健康检查。表面现象是 Compose 等待服务端口超时，
真实原因是新增 Realtime 模型评测组件在 Spring 启动阶段被完整实例化，形成了以下依赖闭环：

```text
SysParamsService
-> AgentPluginMappingService
-> KnowledgeBaseService
-> ModelConfigService
-> ModelTestService
-> Realtime ModelTester
-> PythonBenchmarkClient / BenchmarkRequestAuth
-> SysParamsService
```

修复分成两个提交：

- `713692c7`：为 `BenchmarkRequestAuth` 构造参数中的 `SysParamsService` 增加 `@Lazy`。
- `254aa2b5`：继续为 `PythonBenchmarkClient` 构造参数中的另一处 `SysParamsService` 增加 `@Lazy`。

第一处修复后仍失败，是因为同一功能链上存在两条直接回到 `SysParamsService` 的依赖边；Spring 每次只报告
当前无法打破的 Bean cycle，第一条延迟后第二条才成为新的闭环出口。单元测试此前没有发现问题，是因为相关测试使用
Mockito 或手工构造 tester/client，没有启动完整 Spring ApplicationContext，也没有覆盖所有 tester 在容器启动阶段
被扫描和实例化的真实顺序。Compose 只报告 dependent container unhealthy，因此如果只看端口、数据库和 Liquibase，
很容易把 Bean 装配失败误判为部署网络或健康检查问题。

后续规则：

- 新增 `ModelTestExecutor` 只能依赖最小的运行时调用客户端；不得直接依赖 `SysParamsService`、模型配置服务、知识库服务或安全过滤器。
- 启动关键链路需要系统参数时，在最靠近实际 HTTP 调用的位置延迟解析；不得为了启动成功开启 `spring.main.allow-circular-references`。
- 对同一个依赖图要检查所有返回边，不能看到第一处 `@Lazy` 后就假定闭环已经完全消失。
- 发布前除 `mvn test` 外，必须增加一次 manager-api 完整 Spring 启动冒烟检查，并等待健康端口真实可用后再制作生产版本。
- 健康检查失败时先读取 manager-api 完整日志中的 `BeanCurrentlyInCreationException` / dependency cycle，再检查数据库、Liquibase 和网络；Compose 最后一行不是根因。
- 回归测试既要保留构造参数 `@Lazy` 反射断言，也要补完整 ApplicationContext 启动覆盖，防止 Lombok 构造器改写或新依赖再次把闭环接回去。

#### 12.2.2 Liquibase 排序规则事故复盘（2026-08-04）

版本 `c19ccd45` 在生产更新时，`xiaozhi-esp32-manager-api` 因 Liquibase 执行
`202608041520` 失败而无法启动。生产数据库默认排序规则是 `utf8mb4_unicode_ci`，模型配置相关表
使用 `utf8mb4_0900_ai_ci`；迁移创建的临时表继承数据库默认排序规则，随后用字符串主键与业务表
执行 JOIN，MySQL 报 `Illegal mix of collations`。Spring 后续的 Bean 创建错误和 Compose 的
`dependency failed to start` 都是迁移失败的级联现象，不是新的根因。

这次事故也暴露了静态 SQL 断言和 H2/Mock 测试的边界：它们能确认迁移文本存在，却不能验证 MySQL
对 `INSERT ... SELECT ... ON DUPLICATE KEY UPDATE` 的真实解析规则、同名列的解析歧义，以及不同
collation 字符列之间的比较行为。因此，包含临时表、JSON、JOIN、UPSERT 或数据修复的迁移，不能只靠
字符串测试后直接发布。

修复原则：

- 已发布的 `202608041520.sql` 保持不变；旧 changeSet 通过兼容 checksum 和 `MARK_RAN` 停止执行，实际修复放入新的 `202608041530.sql`。
- 临时表通过目标业务表的空结果集创建，使字符串列继承相同字符集和排序规则，不再手写容易漂移的列定义。
- 数据补齐拆成 `INSERT IGNORE` 和独立 `UPDATE`，避免 MySQL UPSERT 解析差异和来源列/目标列歧义。
- 发布前使用 MySQL 8.4 一次性容器，显式制造“数据库默认 collation 与业务表 collation 不同”的生产同构状态，运行真实 manager-api JAR，等待 Liquibase 完成且 Tomcat 端口真实启动。
- 迁移验收既检查 `DATABASECHANGELOG` 的 `MARK_RAN` / `EXECUTED` 状态，也检查修复后的业务数据；密钥只验证“存在、未被覆盖”，禁止输出原文。
- 生产排障先只读核对 changeSet 是否登记、数据库和相关列的 collation、Liquibase 锁与完整异常链；确认没有活跃迁移后再发布补偿迁移，不删除 changelog、不手工改业务数据、不回滚数据库。

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
~/xiaozhi-upgrade
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
发布 commit 已完成评审且工作区干净
scripts/acr-build-push 成功
ACR 中存在三个业务镜像、xiaozhi-deploy-assets、mysql/8.4、redis/8.0 的目标版本
```

生产服务器：

```text
服务器已完成一次新版 install/upgrade 引导脚本更新
docker 或 sudo docker 已登录 ACR
`~/xiaozhi-upgrade` 成功
docker ps 显示 server、manager-api、manager-web、MySQL、Redis 等容器运行
manager-web 可访问
设备 WebSocket/OTA 地址已配置为设备可达地址
```
