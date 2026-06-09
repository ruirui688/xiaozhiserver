# 小智服务器开发规范

本文档是本项目的强制开发规范，用于约束本地开发、Git 管理、数据库变更、环境配置、阿里云部署和官方上游同步流程。

本项目基于官方 `xiaozhi-esp32-server` 二次开发。所有开发、部署和同步操作必须遵守本文档。

## 1. 仓库职责

本项目必须区分三个对象：

```text
本地开发仓库    编写代码、调试、提交 commit
私有远程仓库    保存本地二次开发代码，作为部署来源
官方上游仓库    只用于拉取官方更新，不能作为本地开发推送目标
```

远程仓库的目标状态必须使用行业通用命名：

```text
origin    私有远程仓库，允许 push，阿里云服务器也从这里拉取代码
upstream  官方原始仓库，只允许 fetch，不允许 push
```

目标状态必须遵守：

- 本地开发代码必须推送到 `origin`。
- 官方仓库 `upstream` 只允许用于查看和同步官方更新。
- 禁止把本地二次开发代码推送到官方仓库。
- 阿里云服务器必须从私有远程仓库拉取代码。

当前仓库如果仍是 `gitea = 私有仓库`、`origin = 官方仓库`，属于过渡状态。远端标准化必须由用户明确授权后执行：

```bash
git remote rename origin upstream
git remote rename gitea origin
git remote -v
```

调整后必须满足：

```text
origin    http://192.168.0.151:3001/gaoruirui688/xiaozhi-esp32-server.git
upstream  https://github.com/xinnan-tech/xiaozhi-esp32-server.git
```

过渡状态下必须遵守：

- 私有远端是 `gitea`，允许在用户授权后 push。
- 官方远端是 `origin`，只允许 fetch，不允许 push。
- AI 不得假设 `origin` 一定是私有仓库，必须以 `git remote -v` 的实际结果判断。
- AI 不得自动重命名远端；远端标准化属于仓库配置变更，必须先获得用户明确授权。
- 推送时必须推送当前任务分支，不得固定推送 `main`。

## 2. 分支规则

本项目采用简化主干开发规则。长期存在的分支只能有 `main`，其他分支都是短生命周期临时分支。

```text
main                    唯一长期分支，可部署稳定分支
feature/xxx             本地功能开发或普通修复分支
sync/upstream-YYYYMMDD  官方上游同步分支
```

必须遵守：

- `main` 必须始终保持可部署状态。
- 私有仓库 `origin/main` 必须开启分支保护；如果平台不支持分支保护，必须通过流程约束禁止直接推送。
- 普通开发禁止直接在 `main` 上修改。
- 合并到 `main` 前必须完成本地检查；多人协作时必须通过 Merge Request / Pull Request 合并。
- 每个功能必须从 `main` 创建独立 `feature/xxx` 分支。
- 普通修复和紧急修复都使用 `feature/xxx`，不单独建立 `fix/xxx` 或 `hotfix/xxx` 规则。
- 官方更新必须先进入 `sync/upstream-YYYYMMDD` 分支。
- 官方更新验证通过后，才允许合并回 `main`。
- 禁止在未测试的情况下把官方更新直接合并到 `main`。
- 禁止为了“备份”长期保留大量分支；备份必须优先使用 tag 或远程 commit。

## 3. 提交流程

本地开发流程必须按以下顺序执行：

```bash
git checkout main
git pull --ff-only origin main
git checkout -b feature/your-feature

# 修改代码

git status
git diff
git add <changed-files>
git commit -m "feat: describe change"
git push origin feature/your-feature
```

如果仓库仍处于 `gitea = 私有仓库`、`origin = 官方仓库` 的过渡状态，最后一步必须改为：

```bash
git push -u gitea feature/your-feature
```

禁止把 `feature/xxx`、`sync/upstream-YYYYMMDD` 或本地 `main` 推送到官方远端。

提交信息必须使用以下前缀之一：

```text
feat      新功能
fix       修复问题
docs      文档变更
refactor  重构
test      测试
chore     构建、依赖、脚本等杂项
deploy    部署相关
```

禁止提交：

- 未确认用途的批量格式化。
- 本地临时文件。
- 日志文件。
- 密钥文件。
- 数据库运行数据。
- 模型大文件。
- 与本次任务无关的改动。

## 4. 项目模块

项目结构：

```text
main/
  xiaozhi-server/    Python 核心服务
  manager-api/       Java Spring Boot 管理后台 API
  manager-web/       Web 管理后台
  manager-mobile/    移动端管理后台
docs/                项目文档
```

默认内部端口：

```text
8000  xiaozhi-server WebSocket
8003  xiaozhi-server HTTP / OTA / 视觉接口
8002  manager-api / manager-web 管理后台
3306  MySQL
6379  Redis
```

内部端口必须保持稳定。不同环境的端口差异必须通过 Docker Compose、Nginx 或环境变量处理，禁止为了适配某台机器随意修改源码默认端口。

## 5. 环境规则

必须区分三个环境：

```text
local       本地开发环境
test        测试环境
production 生产环境
```

四类内容必须分开管理：

```text
代码              进入 Git
配置模板          进入 Git
环境私有配置      不进入 Git
运行数据          不进入 Git
```

允许进入 Git：

- Python / Java / Vue 源码。
- Dockerfile。
- Docker Compose 模板。
- 数据库迁移脚本。
- `.example` 配置模板。
- 文档。

禁止进入 Git：

- `.env`
- `.env.local`
- `.env.dev`
- `.env.prod`
- `.env.production`
- `.config.yaml`
- `.secrets.yaml`
- `.private_config.yaml`
- API Key
- 数据库密码
- Redis 密码
- 含身份信息、内网地址、访问凭据或安全敏感信息的生产配置
- MySQL 数据目录
- Redis 数据目录
- 上传文件目录
- 真实业务数据
- 模型大文件
- 日志文件

允许提交：

```text
.env.example
.env.template
.env.sample
```

## 6. 配置规则

`xiaozhi-server` 配置规则：

```text
main/xiaozhi-server/config.yaml        默认配置，可以提交
main/xiaozhi-server/data/.config.yaml  环境私有配置，禁止提交
```

必须遵守：

- `config.yaml` 只保存默认值和模板配置。
- `.config.yaml` 保存本地、测试或生产环境覆盖配置。
- 生产 `.config.yaml` 必须只存在于服务器。
- 禁止把生产 `.config.yaml` 复制进 Git。
- 官方更新 `config.yaml` 后，必须人工检查新增配置项，再决定是否同步到 `.config.yaml`。

本地配置示例：

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

生产配置示例：

```yaml
server:
  ip: 0.0.0.0
  port: 8000
  http_port: 8003
  websocket: wss://your-domain.com/xiaozhi/v1/
  vision_explain: https://your-domain.com/mcp/vision/explain

manager-api:
  url: http://xiaozhi-esp32-server-web:8002/xiaozhi
  secret: your-production-server-secret
```

## 7. 端口与访问地址规则

必须区分三类地址：

```text
程序监听地址      程序内部使用
容器内部地址      Docker 服务之间访问
公网访问地址      设备和用户访问
```

生产环境必须优先使用 Nginx 暴露统一入口：

```text
https://your-domain.com/xiaozhi/v1/   -> 127.0.0.1:8000
https://your-domain.com/xiaozhi/ota/  -> 127.0.0.1:8003
https://your-domain.com/              -> 127.0.0.1:8002
```

必须遵守：

- 公网优先只开放 `80` 和 `443`。
- 管理后台禁止无保护直接暴露到公网。
- WebSocket 生产环境必须使用 `wss://`。
- 本地端口冲突必须通过 Compose 映射解决。
- 禁止把本地端口写死到源码。

## 8. 数据库规则

数据库内容分为三类：

```text
数据库结构          通过迁移脚本管理，进入 Git
基础初始化数据      通过迁移脚本管理，谨慎进入 Git
业务运行数据        只备份，不进入 Git
```

本项目数据库迁移目录：

```text
main/manager-api/src/main/resources/db/changelog/
main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
```

必须遵守：

- 所有表结构变化必须新增迁移 SQL。
- 所有迁移 SQL 必须登记到 `db.changelog-master.yaml`。
- 已发布的迁移文件禁止修改。
- 已部署到测试或生产的 `changeSet id` 禁止修改。
- 手工修改生产数据库后，必须补充迁移脚本。
- 生产发布前必须备份数据库。
- 禁止把生产数据库 dump 提交到 Git。

新增迁移示例：

```text
main/manager-api/src/main/resources/db/changelog/202606091530.sql
```

```sql
ALTER TABLE agent_chat_history ADD COLUMN source VARCHAR(32) NULL COMMENT '来源';
```

`db.changelog-master.yaml` 必须追加：

```yaml
- changeSet:
    id: 202606091530
    author: your-name
    changes:
      - sqlFile:
          encoding: utf8
          path: classpath:db/changelog/202606091530.sql
```

生产备份命令：

```bash
docker exec xiaozhi-esp32-server-db mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" xiaozhi_esp32_server \
  > /opt/xiaozhi-esp32-server/backup/mysql/before_release_$(date +%F_%H%M%S).sql
```

## 9. 运行数据规则

以下内容属于运行数据：

```text
MySQL 数据
Redis 数据
上传文件
日志
模型文件
用户数据
设备绑定数据
聊天记录
声纹数据
```

必须遵守：

- 运行数据禁止提交到 Git。
- 运行数据必须通过备份保护。
- 生产数据禁止直接复制到本地开发环境。
- 如需本地调试生产问题，必须使用脱敏数据。

服务器运行数据必须集中放在固定目录，例如：

```text
/opt/xiaozhi-esp32-server/runtime/mysql/data
/opt/xiaozhi-esp32-server/runtime/redis/data
/opt/xiaozhi-esp32-server/runtime/uploadfile
/opt/xiaozhi-esp32-server/runtime/models
/opt/xiaozhi-esp32-server/runtime/logs
/opt/xiaozhi-esp32-server/backup
```

## 10. 部署规则

阿里云服务器必须只作为运行环境，不作为开发环境。

必须遵守：

- 服务器禁止直接开发功能代码。
- 服务器必须从 `origin` 私有仓库拉取代码。
- 服务器真实配置必须保存在服务器本地。
- 生产部署必须使用发布 tag，禁止直接部署浮动的 `main`。
- 发布 tag 必须从已验证的 `main` 创建。
- 部署前必须备份数据库。
- 部署后必须检查容器状态和日志。

发布 tag 规则：

```text
release-YYYYMMDD-N
```

示例：

```bash
git checkout main
git pull --ff-only origin main
git tag release-20260609-1
git push origin release-20260609-1
```

生产部署流程：

```bash
cd /opt/xiaozhi-esp32-server
git fetch origin
git checkout release-20260609-1

cd main/xiaozhi-server
docker compose -f docker-compose_all.yml up -d
docker ps
```

发布后必须检查：

```bash
docker logs -f xiaozhi-esp32-server
docker logs -f xiaozhi-esp32-server-web
```

## 11. 官方上游同步规则

官方仓库只允许作为更新来源。

upstream 比对责任：

```text
人类开发者执行任务时，由人类开发者检查。
AI 工具执行任务时，由 AI 工具检查。
CI/CD 建立后，可以由 CI 定时或在合并前自动检查。
阿里云生产服务器不负责 upstream 比对。
```

当前 remote 规则：

```text
origin/main    私有仓库主分支
upstream/main  官方上游主分支
```

官方同步必须使用 `sync/upstream-YYYYMMDD` 分支：

```bash
git checkout main
git pull --ff-only origin main
git fetch upstream --tags
git checkout -b sync/upstream-YYYYMMDD
git merge --no-ff upstream/main
```

必须遵守：

- 禁止直接在 `main` 合并官方更新。
- 禁止用 `git reset --hard upstream/main` 覆盖本地项目。
- 官方更新必须先评估差异。
- 官方更新必须先在本地和测试环境验证。
- 官方更新验证通过后才允许合并到 `main`。

查看官方差异：

```bash
git fetch upstream --tags
BASE=$(git merge-base main upstream/main)
git log --oneline "$BASE"..upstream/main
git diff --stat "$BASE"..upstream/main
```

关键目录必须单独检查：

```bash
git diff "$BASE"..upstream/main -- main/xiaozhi-server
git diff "$BASE"..upstream/main -- main/manager-api
git diff "$BASE"..upstream/main -- main/manager-web
git diff "$BASE"..upstream/main -- main/manager-mobile
git diff "$BASE"..upstream/main -- main/manager-api/src/main/resources/db/changelog
```

## 12. 官方更新处理规则

官方更新只能按以下四种方式处理。

只参考：

```bash
git fetch upstream
BASE=$(git merge-base main upstream/main)
git diff --stat "$BASE"..upstream/main
```

选择性合入某个 commit：

```bash
git checkout -b sync/pick-official-fix main
git cherry-pick <official-commit-id>
```

完整合并官方更新：

```bash
git checkout -b sync/upstream-YYYYMMDD main
git merge --no-ff upstream/main
```

局部采用官方文件：

```bash
git checkout -b sync/replace-selected-files main
git checkout upstream/main -- path/to/file
```

必须遵守：

- 只合入需要的官方内容。
- 冲突必须人工确认。
- 涉及数据库、配置、Docker、认证、设备协议的官方更新必须进入测试环境验证。
- 官方示例配置禁止覆盖生产配置。
- 官方示例数据禁止覆盖生产数据。

## 13. 冲突处理规则

冲突处理优先级：

```text
生产安全 > 数据安全 > 本地定制 > 官方 bugfix > 官方格式调整
```

本地优先的内容：

```text
本地业务定制
本地部署配置
本地安全策略
本地兼容逻辑
```

官方优先的内容：

```text
官方明确修复的 bug
官方协议升级
官方依赖安全升级
官方必要数据库迁移
```

必须人工合并的内容：

```text
数据库迁移冲突
配置结构变化
认证权限变化
设备协议变化
同一模块内同时存在本地定制和官方修复
```

禁止为了快速解决冲突无脑选择 `ours` 或 `theirs`。

## 14. 本地定制记录规则

长期二次开发必须维护本地定制记录。

记录文件：

```text
docs/local-customizations.md
```

每个本地定制必须记录：

```text
定制模块
改动原因
关键文件
是否允许被官方覆盖
官方同步时的处理规则
验证方法
```

没有记录的本地定制，在官方同步时无法被可靠保护。

## 15. 发布检查清单

每次发布前必须检查：

```text
[ ] 当前分支是 main
[ ] 当前代码已提交
[ ] main 已同步到 origin/main
[ ] 本地构建或测试已通过
[ ] 发布 tag 已创建
[ ] 发布 tag 已推送到 origin
[ ] 如果有数据库变化，迁移 SQL 已新增
[ ] 如果有数据库变化，db.changelog-master.yaml 已更新
[ ] 生产数据库已备份
[ ] 生产 .config.yaml 未被覆盖
[ ] 生产 .env 未被覆盖
[ ] 模型文件存在
[ ] MySQL 正常
[ ] Redis 正常
[ ] Docker 容器正常
[ ] Nginx 配置正常
[ ] WebSocket 可连接
[ ] OTA 地址可访问
[ ] 管理后台可登录
[ ] 设备可正常对话
```

官方同步发布还必须额外检查：

```text
[ ] 官方差异已评估
[ ] 数据库迁移冲突已检查
[ ] 配置变化已检查
[ ] Docker / Compose 变化已检查
[ ] 本地定制未被误覆盖
[ ] 测试环境已验证
```

## 16. 回滚规则

代码回滚必须优先使用 Git revert：

```bash
git revert <commit-id>
git push origin main
```

如果回滚官方合并提交：

```bash
git revert -m 1 <merge-commit-id>
git push origin main
```

如果仓库仍处于远端过渡状态，回滚推送目标必须使用真实私有远端，例如 `gitea`。禁止把回滚提交推送到官方远端。

数据库回滚必须遵守：

- 发布前必须有备份。
- 能用新迁移修复的，不直接恢复数据库。
- 数据已损坏时，必须使用发布前备份恢复。
- 数据库版本必须和代码版本匹配。

## 17. 绝对禁止项

以下操作禁止执行：

```text
禁止把生产密钥提交到 Git
禁止把生产数据库提交到 Git
禁止把上传文件提交到 Git
禁止把模型大文件直接提交到 Git
禁止服务器直接开发代码
禁止 main 上直接试验官方更新
禁止 reset --hard upstream/main 覆盖本地项目
禁止生产服务器直接部署浮动的 main
禁止未备份数据库就发布数据库变更
禁止官方示例配置覆盖生产配置
禁止官方示例数据覆盖生产数据
```

未经用户明确授权，还禁止执行以下 Git 命令：

```bash
git reset --hard
git clean -fd
git clean -fdx
git restore .
git checkout -- .
git branch -D <branch>
git push --force
git push --force-with-lease
git rebase <shared-branch>
git stash
git stash pop
git tag -d <tag>
git push origin --delete <branch>
```

## 18. 标准流程

日常开发：

```text
main
  -> feature/xxx
  -> 本地测试
  -> 合并 main
  -> 推送 origin
  -> 测试环境验证
  -> 创建 release tag
  -> 生产部署 tag
```

官方同步：

```text
upstream/main
  -> sync/upstream-YYYYMMDD
  -> 冲突处理
  -> 本地测试
  -> 测试环境验证
  -> main
  -> origin/main
  -> 创建 release tag
  -> 生产部署 tag
```

生产部署：

```text
备份数据库
  -> 拉取 origin 的 release tag
  -> 启动容器
  -> 检查日志
  -> 验证接口
  -> 验证设备
```
