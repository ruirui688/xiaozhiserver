# 本地开发到阿里云部署开发文档

本文档用于规范本项目从本地开发、Git 版本管理、远程仓库协作，到阿里云 ECS 服务器部署和后续运维的完整流程。

适用项目：`xiaozhi-esp32-server`

适用场景：

- 本地电脑负责开发和调试源码。
- Git 负责版本管理。
- 远程 Git 仓库负责保存源码和协作。
- 阿里云 ECS 服务器负责运行生产或测试环境。
- 项目会持续变大，需要避免手工复制文件、配置混乱、线上不可回滚等问题。

## 1. 总体原则

本项目建议采用以下模式：

```text
本地开发机
  |
  | git push
  v
远程 Git 仓库
  |
  | 手动部署 / 自动部署
  v
阿里云 ECS 服务器
  |
  v
Docker Compose / systemd / Nginx 运行服务
```

核心原则：

- 本地负责开发，不直接修改线上代码。
- Git 仓库负责保存源码，不把服务器当源码仓库。
- 服务器负责运行，不负责日常开发。
- 配置、密钥、模型文件、数据库数据不要直接提交到 Git。
- 每次上线都应该能追踪到具体 Git commit。
- 线上出现问题时，应该可以快速回滚到上一版。

## 2. 项目模块说明

当前仓库大致包含以下模块：

```text
xiaozhi-esp32-server/
  README.md
  docs/
  main/
    xiaozhi-server/    Python 后端服务，负责小智核心服务、WebSocket、OTA、模型调用等
    manager-api/       Java Spring Boot 管理后台 API
    manager-web/       Vue 2 管理后台 Web
    manager-mobile/    UniApp 移动端管理后台
  Dockerfile-server
  Dockerfile-web
  docker-setup.sh
```

常用端口：

```text
8000  xiaozhi-server WebSocket 服务
8003  xiaozhi-server HTTP/OTA/视觉接口
8002  manager-api / manager-web 管理后台
```

部署方式建议：

- 只运行核心服务：参考 `docs/Deployment.md`。
- 运行完整管理后台：参考 `docs/Deployment_all.md`。
- 自己长期维护：优先使用 Docker Compose，并保留服务器部署脚本。

## 3. 环境规划

建议至少区分三个环境：

```text
local       本地开发环境
test        测试环境，可部署在阿里云 ECS 或本地虚拟机
production 生产环境，正式给设备和用户使用
```

不同环境必须有独立配置：

```text
本地配置：      main/xiaozhi-server/data/.config.yaml
测试配置：      服务器 /opt/xiaozhi-esp32-server/data/.config.yaml
生产配置：      服务器 /opt/xiaozhi-esp32-server/data/.config.yaml
配置模板：      提交 .config.example.yaml 或在文档中说明
```

环境之间要区分四类东西：

```text
代码              进入 Git，例如 Python/Java/Vue 源码、Dockerfile、数据库迁移脚本
配置模板          可以进入 Git，例如 .env.example、.config.example.yaml
环境私有配置      不进入 Git，例如 .env、.config.yaml、数据库密码、API Key
运行数据          不进入 Git，例如 MySQL 数据、Redis 数据、上传文件、日志、模型大文件
```

不要提交以下内容：

- API Key
- 数据库密码
- Redis 密码
- 短信 AccessKey
- 大模型平台密钥
- 语音模型大文件
- 本地日志
- 数据库数据目录
- 上传文件目录

### 3.1 本地和服务器的配置边界

本项目的核心服务 `main/xiaozhi-server/config.yaml` 是默认配置文件。它适合保存通用默认值，但不适合保存本地或生产环境的私有配置。

正确方式：

```text
main/xiaozhi-server/config.yaml             默认配置，可提交
main/xiaozhi-server/data/.config.yaml       当前环境覆盖配置，不提交
```

本地开发时：

```text
server.websocket: ws://127.0.0.1:8000/xiaozhi/v1/
server.vision_explain: http://127.0.0.1:8003/mcp/vision/explain
manager-api.url: http://127.0.0.1:8002/xiaozhi
```

服务器生产环境时：

```text
server.websocket: wss://your-domain.com/xiaozhi/v1/
server.vision_explain: https://your-domain.com/mcp/vision/explain
manager-api.url: http://xiaozhi-esp32-server-web:8002/xiaozhi
```

注意：

- 本地用 `127.0.0.1` 或局域网 IP。
- Docker 内部服务互访用容器名，例如 `xiaozhi-esp32-server-web`。
- 设备访问公网服务时使用域名和 HTTPS/WSS。
- 不要把本地端口、服务器公网 IP、生产域名硬编码进源码。

### 3.2 manager-api 配置边界

`manager-api` 当前配置文件位于：

```text
main/manager-api/src/main/resources/application.yml
main/manager-api/src/main/resources/application-dev.yml
```

本地开发可以使用 `application-dev.yml` 中的本地 MySQL 和 Redis：

```text
MySQL: 127.0.0.1:3306
Redis: 127.0.0.1:6379
API:   127.0.0.1:8002/xiaozhi
```

服务器环境不要直接修改 `application-dev.yml` 来适配生产。生产环境应该通过环境变量覆盖：

```text
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_DRUID_URL=jdbc:mysql://...
SPRING_DATASOURCE_DRUID_USERNAME=...
SPRING_DATASOURCE_DRUID_PASSWORD=...
SPRING_DATA_REDIS_HOST=...
SPRING_DATA_REDIS_PORT=6379
SPRING_DATA_REDIS_PASSWORD=...
```

如果后续你要进一步规范，可以新增：

```text
application-prod.yml
application-test.yml
```

但这些文件里也只放结构化配置，不放真实密码。真实密码仍然通过环境变量、服务器 `.env` 文件或云厂商密钥管理服务注入。

### 3.3 端口管理原则

要区分“容器/程序内部端口”和“服务器对外端口”：

```text
内部端口：程序实际监听的端口，尽量保持稳定
外部端口：宿主机或公网暴露的端口，可按环境变化
```

本项目建议内部端口固定：

```text
xiaozhi-server WebSocket 内部端口：8000
xiaozhi-server HTTP/OTA 内部端口：8003
manager-api/web 内部端口：8002
MySQL 内部端口：3306
Redis 内部端口：6379
```

本地 Docker 可以映射成不冲突的端口：

```yaml
ports:
  - "18000:8000"
  - "18003:8003"
  - "18002:8002"
```

生产服务器建议由 Nginx 统一暴露 80/443，后端端口只在服务器内访问：

```text
https://your-domain.com/xiaozhi/v1/   -> 127.0.0.1:8000
https://your-domain.com/xiaozhi/ota/  -> 127.0.0.1:8003
https://your-domain.com/              -> 127.0.0.1:8002
```

这样即使本地和服务器端口不一致，也不会影响代码。变化的是环境配置和 Nginx/Compose 映射，不是源码。

### 3.4 推荐配置文件组织

建议后续增加一个部署配置目录：

```text
deploy/
  docker-compose.local.yml
  docker-compose.test.yml
  docker-compose.prod.yml
  env/
    .env.local.example
    .env.test.example
    .env.prod.example
```

可以提交：

```text
*.example
docker-compose.local.yml
docker-compose.test.yml
docker-compose.prod.yml
```

不能提交：

```text
.env.local
.env.test
.env.prod
真实 .config.yaml
真实数据库 dump
真实密钥
```

示例 `.env.prod.example`：

```dotenv
APP_ENV=prod
TZ=Asia/Shanghai

DOMAIN=your-domain.com

SERVER_WS_PUBLIC_URL=wss://your-domain.com/xiaozhi/v1/
SERVER_VISION_PUBLIC_URL=https://your-domain.com/mcp/vision/explain

MYSQL_HOST=xiaozhi-esp32-server-db
MYSQL_PORT=3306
MYSQL_DATABASE=xiaozhi_esp32_server
MYSQL_USERNAME=root
MYSQL_PASSWORD=change-me

REDIS_HOST=xiaozhi-esp32-server-redis
REDIS_PORT=6379
REDIS_PASSWORD=
```

### 3.5 本地测试与服务器安装差异矩阵

| 项目 | 本地开发 | 测试服务器 | 生产服务器 | 是否进入 Git |
| --- | --- | --- | --- | --- |
| 源码 | 本地工作区 | 从远程仓库拉取 | 从远程仓库拉取 | 是 |
| Python 配置 | `data/.config.yaml` | 服务器 `.config.yaml` | 服务器 `.config.yaml` | 否 |
| Java 配置 | `application-dev.yml` 或本地环境变量 | 环境变量 / `.env.test` | 环境变量 / `.env.prod` | 模板可进，真实值不进 |
| MySQL 地址 | `127.0.0.1:3306` | Docker 服务名或测试库地址 | Docker 服务名或生产库地址 | 否 |
| MySQL 数据 | 本地测试数据 | 测试数据 | 真实业务数据 | 否，只备份 |
| Redis 地址 | `127.0.0.1:6379` | Docker 服务名或测试 Redis | Docker 服务名或生产 Redis | 否 |
| WebSocket 访问 | `ws://127.0.0.1:8000/...` | `ws/wss://test-domain/...` | `wss://prod-domain/...` | 否 |
| OTA/HTTP 访问 | `http://127.0.0.1:8003/...` | `http/https://test-domain/...` | `https://prod-domain/...` | 否 |
| 管理后台 | `http://127.0.0.1:8002/xiaozhi` | 测试域名 | 生产域名 | 否 |
| 模型文件 | 本地模型目录 | 服务器模型目录 | 服务器模型目录或 OSS | 否 |
| 上传文件 | 本地目录 | 测试服务器目录 | 生产服务器目录 | 否，只备份 |
| 数据库结构变更 | Liquibase SQL | Liquibase SQL | Liquibase SQL | 是 |
| 数据库备份 | 可选 | 发布前备份 | 每日备份 + 发布前备份 | 否 |

判断一个文件是否应该推送到 Git，可以用这条规则：

```text
能在任意环境复用的规则、代码、模板，可以进 Git。
只属于某台机器、某个账号、某个域名、某个数据库的数据，不能进 Git。
```

## 4. Git 仓库规划

### 4.1 远程仓库选择

可选择任意一个作为远程仓库：

- GitHub
- Gitee
- GitLab
- 阿里云 Codeup

如果服务器在国内，且希望拉取速度稳定，可以优先考虑 Gitee、GitLab 私有部署或阿里云 Codeup。

### 4.2 分支模型

推荐使用简化分支模型：

```text
main              生产稳定分支，只放确认可上线的代码
develop           日常开发集成分支
feature/xxx       功能开发分支
fix/xxx           普通缺陷修复分支
hotfix/xxx        生产紧急修复分支
release/x.y.z     版本发布准备分支，可选
```

个人开发时，可以先简化为：

```text
main              可部署分支
feature/xxx       每个功能一个分支
```

### 4.3 提交规范

建议 commit message 使用以下格式：

```text
feat: 新增功能
fix: 修复问题
docs: 修改文档
style: 代码格式调整
refactor: 重构，不改变行为
test: 添加或修改测试
chore: 构建、依赖、脚本等杂项
deploy: 部署相关变更
```

示例：

```bash
git add main/xiaozhi-server/core/connection.py
git commit -m "fix: 修复 websocket 连接断开后的清理逻辑"
git push origin feature/fix-websocket-cleanup
```

### 4.4 禁止提交的内容

提交前务必检查：

```bash
git status
git diff
```

不要提交：

```text
*.log
.env
.config.yaml
.secrets.yaml
.private_config.yaml
node_modules/
target/
dist/
build/
main/xiaozhi-server/data/
main/xiaozhi-server/mysql/
main/xiaozhi-server/models/SenseVoiceSmall/model.pt
uploadfile/
```

如果必须管理大模型文件，建议使用以下方式之一：

- 阿里云 OSS 保存模型文件，服务器部署时下载。
- Git LFS 管理大文件。
- 手动放置在服务器固定目录，并通过 Docker volume 挂载。

### 4.5 数据库版本管理规范

数据库要拆成三类管理：

```text
数据库结构          用迁移脚本管理，进入 Git
基础初始化数据      用迁移脚本管理，谨慎进入 Git
业务运行数据        不进入 Git，只做备份和恢复
```

本项目已经有数据库变更目录：

```text
main/manager-api/src/main/resources/db/changelog/
main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
```

这里使用的是 Liquibase 风格的数据库变更管理。规范如下：

- 新增表、修改字段、增加索引、初始化系统参数，都通过新的 SQL 迁移文件完成。
- 每次数据库结构变化，只新增文件，不修改已经发布过的旧迁移文件。
- 新增 SQL 文件后，必须在 `db.changelog-master.yaml` 中追加一个新的 `changeSet`。
- 文件名建议使用时间戳，例如 `202606091530.sql`。
- `changeSet id` 与文件名保持一致。
- SQL 要尽量可重复发布，避免依赖本地临时状态。
- 生产环境不要手工执行 `ALTER TABLE` 后忘记补迁移脚本。

示例：

```text
main/manager-api/src/main/resources/db/changelog/202606091530.sql
```

```sql
ALTER TABLE agent_chat_history ADD COLUMN source VARCHAR(32) NULL COMMENT '来源';
```

然后在 `db.changelog-master.yaml` 追加：

```yaml
- changeSet:
    id: 202606091530
    author: your-name
    changes:
      - sqlFile:
          encoding: utf8
          path: classpath:db/changelog/202606091530.sql
```

### 4.6 哪些数据库内容不能进 Git

以下内容不能直接进入 Git：

```text
用户真实数据
设备真实绑定信息
聊天记录
声纹数据
上传文件记录对应的真实文件
生产数据库 dump
真实手机号、Token、AccessKey
生产环境 sys_params 中的密钥值
```

如果本地调试需要生产数据，只能使用脱敏后的数据：

```text
手机号 -> 13800000000
姓名 -> 测试用户
Token/API Key -> fake-token
设备 ID -> mock-device-id
聊天内容 -> 测试文本
```

### 4.7 数据库发布流程

只要改了数据库结构，上线前必须执行以下流程：

```text
1. 本地新增迁移 SQL
2. 本地启动 manager-api 验证迁移能执行
3. 提交 SQL 文件和 db.changelog-master.yaml
4. 合并到 main
5. 生产部署前备份数据库
6. 部署新版本 manager-api
7. 检查 Liquibase 是否执行成功
8. 检查功能是否正常
```

生产部署前备份：

```bash
mkdir -p /opt/xiaozhi-esp32-server/backup/mysql
docker exec xiaozhi-esp32-server-db mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" xiaozhi_esp32_server \
  > /opt/xiaozhi-esp32-server/backup/mysql/before_release_$(date +%F_%H%M%S).sql
```

如果迁移失败：

```text
1. 停止继续发布
2. 保留现场日志
3. 判断是否需要恢复数据库备份
4. 修复迁移脚本
5. 重新发 hotfix
```

不要在生产库上临时手工改表后就结束。临时修复也必须补成迁移脚本，否则下一台服务器或下一次重装会丢失这次变化。

### 4.8 数据库运行数据管理

生产数据库运行数据不做版本推送，只做备份。

推荐备份内容：

```text
MySQL 全量 dump
Redis 快照或关键数据导出
uploadfile 上传目录
xiaozhi-server/data 配置目录
模型文件清单
```

推荐备份频率：

```text
个人测试环境：重要操作前手动备份
长期测试环境：每天一次
生产环境：每天一次，重要发布前额外备份一次
```

推荐保留策略：

```text
最近 7 天每日备份
最近 4 周每周备份
重要版本发布前备份永久或长期保留
```

## 5. 本地开发流程

### 5.1 拉取源码

```bash
git clone <your-repo-url>
cd xiaozhi-esp32-server
```

### 5.2 创建功能分支

```bash
git checkout main
git pull origin main
git checkout -b feature/your-feature-name
```

### 5.3 开发 Python 核心服务

目录：

```text
main/xiaozhi-server
```

安装依赖：

```bash
cd main/xiaozhi-server
conda create -n xiaozhi-esp32-server python=3.10 -y
conda activate xiaozhi-esp32-server
pip install -r requirements.txt
```

准备配置：

```text
main/xiaozhi-server/data/.config.yaml
```

运行：

```bash
cd main/xiaozhi-server
conda activate xiaozhi-esp32-server
python app.py
```

### 5.4 开发 Java 管理 API

目录：

```text
main/manager-api
```

基础环境：

```text
JDK 21
Maven 3.8+
MySQL 8.0+
Redis 5.0+
```

常用命令：

```bash
cd main/manager-api
mvn clean package
mvn test
```

接口文档默认地址：

```text
http://localhost:8002/xiaozhi/doc.html
```

### 5.5 开发 Web 管理端

目录：

```text
main/manager-web
```

常用命令：

```bash
cd main/manager-web
npm install
npm run serve
npm run build
```

### 5.6 开发移动端管理后台

目录：

```text
main/manager-mobile
```

项目要求：

```text
Node.js >= 18
pnpm >= 7.30
```

常用命令：

```bash
cd main/manager-mobile
pnpm install
pnpm dev:h5
pnpm build:h5
pnpm lint
pnpm type-check
```

## 6. 本地提交前检查

每次提交前建议执行：

```bash
git status
git diff
```

按修改模块执行对应检查：

```bash
# Python 服务
cd main/xiaozhi-server
python app.py

# Java API
cd main/manager-api
mvn test
mvn clean package

# Web 管理端
cd main/manager-web
npm run build

# 移动端
cd main/manager-mobile
pnpm lint
pnpm type-check
pnpm build:h5
```

如果某个检查因为本地环境不完整无法执行，需要在提交说明或合并请求里写清楚原因。

## 7. 推送到远程仓库

```bash
git add <changed-files>
git commit -m "feat: 描述你的变更"
git push origin feature/your-feature-name
```

然后在远程仓库发起合并请求：

```text
feature/your-feature-name -> develop 或 main
```

如果是个人项目，也可以直接合并到 `main`，但仍建议保留功能分支，方便回滚和定位问题。

## 8. 阿里云 ECS 服务器准备

### 8.1 推荐目录

服务器上建议使用固定目录：

```text
/opt/xiaozhi-esp32-server/          项目源码
/opt/xiaozhi-esp32-server/data/     服务配置
/opt/xiaozhi-esp32-server/models/   模型文件
/opt/xiaozhi-esp32-server/logs/     日志，可选
/opt/xiaozhi-esp32-server/backup/   备份，可选
```

### 8.2 安装基础软件

```bash
sudo apt update
sudo apt install -y git curl vim
```

安装 Docker 和 Docker Compose 后确认：

```bash
docker --version
docker compose version
```

### 8.3 配置 SSH 拉取权限

服务器生成 SSH Key：

```bash
ssh-keygen -t ed25519 -C "aliyun-ecs-xiaozhi"
cat ~/.ssh/id_ed25519.pub
```

把公钥添加到远程 Git 仓库的 Deploy Key 或个人 SSH Key 中。

测试：

```bash
ssh -T git@github.com
```

如果使用 Gitee、GitLab 或 Codeup，把域名替换为对应 Git 服务。

### 8.4 首次拉取项目

```bash
cd /opt
sudo git clone <your-repo-url> xiaozhi-esp32-server
sudo chown -R $USER:$USER /opt/xiaozhi-esp32-server
cd /opt/xiaozhi-esp32-server
git checkout main
```

## 9. 服务器配置管理

### 9.1 配置文件

生产配置建议放在：

```text
/opt/xiaozhi-esp32-server/main/xiaozhi-server/data/.config.yaml
```

注意：

- `.config.yaml` 不提交 Git。
- 不要用新版本默认配置直接覆盖生产配置。
- 升级时手动对比新增配置项，再迁移必要字段。
- 本地、测试、生产分别维护自己的 `.config.yaml`。

推荐生产 `.config.yaml` 只覆盖与生产环境有关的字段，不复制完整 `config.yaml`：

```yaml
server:
  ip: 0.0.0.0
  port: 8000
  http_port: 8003
  websocket: wss://your-domain.com/xiaozhi/v1/
  vision_explain: https://your-domain.com/mcp/vision/explain
  timezone_offset: +8

manager-api:
  url: http://xiaozhi-esp32-server-web:8002/xiaozhi
  secret: your-production-server-secret
```

推荐本地 `.config.yaml`：

```yaml
server:
  ip: 0.0.0.0
  port: 8000
  http_port: 8003
  websocket: ws://127.0.0.1:8000/xiaozhi/v1/
  vision_explain: http://127.0.0.1:8003/mcp/vision/explain

manager-api:
  url: http://127.0.0.1:8002/xiaozhi
  secret: local-dev-secret
```

### 9.2 服务器 .env 文件

生产服务器建议使用 `.env` 保存 Docker Compose 需要的环境变量：

```text
/opt/xiaozhi-esp32-server/deploy/.env.prod
```

`.env.prod` 不进入 Git。可以提交 `.env.prod.example` 作为模板。

示例：

```dotenv
APP_ENV=prod
TZ=Asia/Shanghai

MYSQL_ROOT_PASSWORD=change-me
MYSQL_DATABASE=xiaozhi_esp32_server
MYSQL_USERNAME=root
MYSQL_PASSWORD=change-me

REDIS_PASSWORD=

SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_DRUID_URL=jdbc:mysql://xiaozhi-esp32-server-db:3306/xiaozhi_esp32_server?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&nullCatalogMeansCurrent=true
SPRING_DATASOURCE_DRUID_USERNAME=root
SPRING_DATASOURCE_DRUID_PASSWORD=change-me
SPRING_DATA_REDIS_HOST=xiaozhi-esp32-server-redis
SPRING_DATA_REDIS_PORT=6379
SPRING_DATA_REDIS_PASSWORD=
```

分工原则：

```text
.config.yaml   给 xiaozhi-server 使用，保存核心服务配置和模型平台配置
.env.prod      给 Docker Compose 使用，保存容器、数据库、Redis、Spring 环境变量
Nginx 配置      给公网访问使用，保存域名、HTTPS、反向代理规则
Git            只保存模板和代码，不保存真实值
```

### 9.3 端口和访问地址

服务器上要同时管理三种地址：

```text
程序监听地址      例如 0.0.0.0:8000
容器内部地址      例如 http://xiaozhi-esp32-server-web:8002/xiaozhi
公网访问地址      例如 https://your-domain.com/xiaozhi/ota/
```

推荐规则：

```text
程序内部端口保持默认：8000 / 8002 / 8003
服务器公网只开放：80 / 443
Nginx 负责把公网路径转发到内部端口
Docker Compose 负责容器之间互联
```

本地端口和生产端口可以不同。比如本地为了避免冲突：

```text
localhost:18000 -> 容器 8000
localhost:18003 -> 容器 8003
localhost:18002 -> 容器 8002
```

生产环境：

```text
https://your-domain.com/xiaozhi/v1/  -> 8000
https://your-domain.com/xiaozhi/ota/ -> 8003
https://your-domain.com/             -> 8002
```

不要因为本地用了 `18000`，就去改源码里的默认 `8000`。端口差异应该由 Compose 和 Nginx 消化。

### 9.4 模型文件

模型文件建议放在：

```text
/opt/xiaozhi-esp32-server/main/xiaozhi-server/models/SenseVoiceSmall/model.pt
```

或者生产部署目录：

```text
/opt/xiaozhi-esp32-server/models/SenseVoiceSmall/model.pt
```

具体位置取决于 Docker Compose 的 volume 映射。

### 9.5 数据库和上传文件

如果使用全模块部署，需要重点保护：

```text
mysql/data/
uploadfile/
Redis 数据
```

这些属于运行时数据，不应该提交 Git。

推荐服务器目录：

```text
/opt/xiaozhi-esp32-server/runtime/mysql/data
/opt/xiaozhi-esp32-server/runtime/redis/data
/opt/xiaozhi-esp32-server/runtime/uploadfile
/opt/xiaozhi-esp32-server/runtime/models
/opt/xiaozhi-esp32-server/runtime/logs
```

如果继续使用项目当前 Compose 中的相对目录，也可以，但必须明确这些目录是运行时数据：

```text
main/xiaozhi-server/mysql/
main/xiaozhi-server/uploadfile/
main/xiaozhi-server/data/
```

它们应该备份，不应该提交。

## 10. 手动部署流程

这是最容易落地的第一阶段方案。

### 10.1 本地推送

```bash
git checkout feature/your-feature-name
git add .
git commit -m "feat: 描述变更"
git push origin feature/your-feature-name
```

远程仓库合并到 `main` 后，服务器执行：

```bash
ssh root@your-server-ip
cd /opt/xiaozhi-esp32-server
git fetch origin
git checkout main
git pull origin main
```

### 10.2 只部署核心 Server

如果使用 `main/xiaozhi-server/docker-compose.yml`：

```bash
cd /opt/xiaozhi-esp32-server/main/xiaozhi-server
docker compose up -d
docker logs -f xiaozhi-esp32-server
```

如果你需要基于当前源码重新构建镜像，需要使用项目根目录的 Dockerfile 方案，或者维护自己的 `docker-compose.prod.yml`。

### 10.3 部署全模块

如果使用 `main/xiaozhi-server/docker-compose_all.yml`：

```bash
cd /opt/xiaozhi-esp32-server/main/xiaozhi-server
docker compose -f docker-compose_all.yml up -d
docker ps
```

查看日志：

```bash
docker logs -f xiaozhi-esp32-server
docker logs -f xiaozhi-esp32-server-web
```

## 11. 推荐部署脚本

服务器上可以创建：

```text
/opt/xiaozhi-esp32-server/scripts/deploy-prod.sh
```

示例：

```bash
#!/usr/bin/env bash
set -e

APP_DIR="/opt/xiaozhi-esp32-server"
BRANCH="main"
COMPOSE_DIR="$APP_DIR/main/xiaozhi-server"
COMPOSE_FILE="docker-compose_all.yml"

cd "$APP_DIR"

echo "==> Fetch latest code"
git fetch origin

echo "==> Checkout $BRANCH"
git checkout "$BRANCH"
git pull origin "$BRANCH"

echo "==> Current commit"
git --no-pager log -1 --oneline

echo "==> Restart services"
cd "$COMPOSE_DIR"
docker compose -f "$COMPOSE_FILE" up -d

echo "==> Containers"
docker ps

echo "==> Done"
```

赋权：

```bash
chmod +x /opt/xiaozhi-esp32-server/scripts/deploy-prod.sh
```

以后部署：

```bash
/opt/xiaozhi-esp32-server/scripts/deploy-prod.sh
```

## 12. 更稳的源码构建部署

如果你修改了源码，并希望服务器运行的就是你自己的代码，而不是官方预构建镜像，推荐维护自己的生产 Compose 文件。

示例：

```yaml
services:
  xiaozhi-esp32-server:
    build:
      context: ../..
      dockerfile: Dockerfile-server
    image: your-registry/xiaozhi-esp32-server:server-local
    container_name: xiaozhi-esp32-server
    restart: always
    ports:
      - "8000:8000"
      - "8003:8003"
    volumes:
      - ./data:/opt/xiaozhi-esp32-server/data
      - ./models/SenseVoiceSmall/model.pt:/opt/xiaozhi-esp32-server/models/SenseVoiceSmall/model.pt
```

部署：

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

当项目变大后，更推荐：

```text
CI/CD 构建镜像 -> 推送镜像仓库 -> 服务器只拉镜像运行
```

这样服务器不需要承担完整构建压力，部署速度更快，也更容易回滚。

## 13. CI/CD 升级路线

当项目稳定后，建议升级为自动部署：

```text
本地 git push
  |
  v
远程仓库 main 分支
  |
  v
CI 执行测试和构建
  |
  v
构建 Docker 镜像
  |
  v
推送到镜像仓库
  |
  v
阿里云 ECS 拉取镜像并重启
```

可选工具：

- GitHub Actions
- GitLab CI
- Gitee Go
- 阿里云云效流水线
- Jenkins

推荐镜像仓库：

- 阿里云容器镜像服务 ACR
- Docker Hub
- GitHub Container Registry
- 私有 Harbor

CI/CD 最低要求：

- 只有 `main` 分支合并后才部署生产。
- 部署前执行构建检查。
- 镜像 tag 包含 Git commit。
- 保留最近若干个镜像版本用于回滚。

## 14. 回滚流程

### 14.1 Git 回滚

查看最近提交：

```bash
git log --oneline -10
```

回到某个稳定提交：

```bash
cd /opt/xiaozhi-esp32-server
git checkout <commit-id>
cd main/xiaozhi-server
docker compose -f docker-compose_all.yml up -d
```

确认稳定后，可以创建 hotfix 分支修复：

```bash
git checkout -b hotfix/rollback-fix
```

### 14.2 Docker 镜像回滚

如果你使用镜像 tag 部署，例如：

```text
your-registry/xiaozhi-esp32-server:20260609-abcdef
```

回滚时只需要把 Compose 文件中的镜像 tag 改回上一版，然后：

```bash
docker compose -f docker-compose.prod.yml up -d
```

## 15. 数据备份

### 15.1 MySQL 备份

示例：

```bash
mkdir -p /opt/xiaozhi-esp32-server/backup/mysql
docker exec xiaozhi-esp32-server-db mysqldump -uroot -p123456 xiaozhi_esp32_server \
  > /opt/xiaozhi-esp32-server/backup/mysql/xiaozhi_$(date +%F_%H%M%S).sql
```

建议：

- 每天自动备份一次。
- 至少保留 7 到 30 天。
- 重要生产环境要异地备份，例如阿里云 OSS。

### 15.2 配置和上传文件备份

```bash
tar -czf /opt/xiaozhi-esp32-server/backup/config_$(date +%F_%H%M%S).tar.gz \
  /opt/xiaozhi-esp32-server/main/xiaozhi-server/data

tar -czf /opt/xiaozhi-esp32-server/backup/uploadfile_$(date +%F_%H%M%S).tar.gz \
  /opt/xiaozhi-esp32-server/main/xiaozhi-server/uploadfile
```

## 16. 阿里云安全建议

### 16.1 安全组

只开放必要端口：

```text
22    SSH，建议限制为你的固定 IP
80    HTTP，如果使用 Nginx
443   HTTPS，如果使用 Nginx + SSL
8000  WebSocket，若直接暴露
8002  管理后台，建议不要直接公网暴露
8003  OTA/HTTP 接口，按需开放
```

更推荐：

```text
公网只开放 80/443
Nginx 反向代理到 8000/8002/8003
```

### 16.2 SSH 安全

建议：

- 使用 SSH Key 登录。
- 禁止密码登录。
- 不直接使用 root 日常操作。
- 安全组限制 SSH 来源 IP。

### 16.3 密钥安全

不要把任何密钥提交到 Git。

包括：

```text
LLM API Key
ASR/TTS API Key
短信 AccessKey
数据库密码
Redis 密码
JWT/Token 密钥
第三方平台 Secret
```

如果不小心提交过密钥：

1. 立即在对应平台作废旧密钥。
2. 生成新密钥。
3. 清理 Git 历史或至少确保后续不再使用旧密钥。

## 17. Nginx 反向代理建议

生产环境建议使用 Nginx 统一入口：

```text
https://your-domain.com/xiaozhi/v1/   -> 127.0.0.1:8000
https://your-domain.com/xiaozhi/ota/  -> 127.0.0.1:8003
https://your-domain.com/              -> 127.0.0.1:8002
```

WebSocket 代理需要保留升级头：

```nginx
location /xiaozhi/v1/ {
    proxy_pass http://127.0.0.1:8000;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

## 18. 发布检查清单

上线前检查：

```text
[ ] 当前代码已提交
[ ] 已推送到远程仓库
[ ] 合并到 main 或指定部署分支
[ ] 本地构建或测试通过
[ ] 如果有数据库结构变化，已新增 Liquibase SQL
[ ] 如果有数据库结构变化，已更新 db.changelog-master.yaml
[ ] 生产配置未被覆盖
[ ] 服务器 .env.prod 已确认
[ ] 服务器 .config.yaml 已确认
[ ] 本地端口和生产端口差异已通过 Compose/Nginx 处理
[ ] 数据库已备份
[ ] 上传文件目录已确认
[ ] 模型文件存在
[ ] MySQL 容器或服务正常
[ ] Redis 容器或服务正常
[ ] Docker 容器启动正常
[ ] Nginx 配置已验证
[ ] 日志没有明显报错
[ ] WebSocket 地址可连接
[ ] OTA 地址可访问
[ ] 管理后台可登录
[ ] 设备可正常对话
```

上线后检查：

```bash
docker ps
docker logs -f xiaozhi-esp32-server
docker logs -f xiaozhi-esp32-server-web
```

## 19. 日常开发建议

推荐日常节奏：

```text
1. 从 main 拉最新代码
2. 创建 feature 分支
3. 本地开发和自测
4. 提交并推送到远程仓库
5. 合并到 main
6. 服务器部署 main
7. 验证服务
8. 记录本次发布内容
```

对应命令：

```bash
git checkout main
git pull origin main
git checkout -b feature/your-feature

# 开发、测试

git add .
git commit -m "feat: your feature"
git push origin feature/your-feature
```

服务器部署：

```bash
ssh root@your-server-ip
cd /opt/xiaozhi-esp32-server
git pull origin main
cd main/xiaozhi-server
docker compose -f docker-compose_all.yml up -d
```

## 20. 推荐最终形态

当项目进入长期维护阶段，建议最终演进为：

```text
本地开发
  -> feature 分支
  -> Pull Request / Merge Request
  -> main 分支
  -> CI 自动测试
  -> CI 构建 Docker 镜像
  -> 推送阿里云 ACR
  -> ECS 拉取指定镜像
  -> Docker Compose 重启服务
  -> Nginx 对外提供 HTTPS
  -> 日志、备份、监控持续运行
```

这套流程的好处：

- 本地开发和线上运行分离。
- 每次部署都可追踪。
- 配置和密钥不会污染源码。
- 线上问题可以回滚。
- 项目变大后仍然容易维护。

## 21. 当前阶段建议

如果你现在刚开始整理自己的开发部署流程，建议先这样落地：

```text
远程仓库：GitHub / Gitee / Codeup 三选一
主分支：main
开发分支：feature/xxx
服务器目录：/opt/xiaozhi-esp32-server
部署方式：Docker Compose
部署命令：git pull + docker compose up -d
配置管理：.config.yaml 只放服务器，不进 Git
模型文件：服务器本地保存或 OSS 下载，不进 Git
```

等项目稳定之后，再升级到：

```text
CI/CD + Docker 镜像仓库 + 自动部署 + 自动备份 + 监控告警
```

## 22. 相关规范

如果本项目同时需要跟进官方原始仓库的新版本，请阅读：

```text
docs/upstream-sync-technical-spec.md
```

推荐顺序：

```text
先按上游同步规范合并官方更新
再按本文档完成本地测试、服务器部署和生产发布
```
