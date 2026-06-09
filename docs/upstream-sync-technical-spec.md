# 官方上游同步与本地二次开发技术规格

本文档用于规范本项目在长期本地二次开发过程中，如何持续跟进官方原始仓库的新版本，同时保护本地自定义功能、生产配置、数据库数据和阿里云部署环境。

适用项目：`xiaozhi-esp32-server`

适用场景：

- 本地仓库基于官方开源仓库二次开发。
- 官方仓库后续会持续发布新版本。
- 本地项目也会不断增加私有功能、修复和部署配置。
- 需要在“参考官方更新”“合并官方更新”“局部覆盖官方文件”“保留本地修改”之间建立清晰规则。

## 1. 核心目标

本规格要解决的问题：

```text
官方仓库继续更新
  |
  v
本地项目需要评估、吸收、合并或局部覆盖
  |
  v
不能破坏本地定制功能
不能覆盖服务器私有配置
不能误删生产数据
不能让数据库迁移失控
不能让线上版本无法回滚
```

最终目标：

- 能知道当前本地版本基于官方哪个 commit。
- 能安全查看官方新增内容。
- 能选择性合并官方更新。
- 能解决冲突并保留本地定制。
- 能在测试环境验证后再发布到阿里云。
- 能在官方更新引入问题时快速回滚。

## 2. Git remote 命名规范

### 2.1 推荐命名

推荐使用行业常见命名：

```text
origin    你自己的远程仓库，用于 push 本地二次开发代码
upstream  官方原始仓库，只用于 fetch/pull 官方更新
```

推荐状态：

```text
origin    git@your-git-server:your-name/xiaozhi-esp32-server.git
upstream  https://github.com/xinnan-tech/xiaozhi-esp32-server.git
```

### 2.2 当前仓库状态

当前本地仓库检测到的 remote 是：

```text
origin  https://github.com/xinnan-tech/xiaozhi-esp32-server.git
gitea   http://192.168.0.151:3001/gaoruirui688/xiaozhi-esp32-server.git
```

也就是说：

```text
origin 当前是官方仓库
gitea 当前是你的私有仓库
```

为了长期维护，建议调整为：

```bash
git remote rename origin upstream
git remote rename gitea origin
git remote -v
```

调整后应该类似：

```text
origin    http://192.168.0.151:3001/gaoruirui688/xiaozhi-esp32-server.git
upstream  https://github.com/xinnan-tech/xiaozhi-esp32-server.git
```

如果暂时不调整命名，也可以继续使用现状，但必须记住：

```text
当前 origin = 官方
当前 gitea  = 自己
```

后续文档默认使用推荐命名：`origin` 是自己的仓库，`upstream` 是官方仓库。

## 3. 分支设计

推荐分支结构：

```text
main                         本地生产稳定分支，可部署到阿里云
develop                      本地开发集成分支，可选
feature/xxx                  本地功能开发分支
fix/xxx                      本地普通修复分支
hotfix/xxx                   本地生产紧急修复分支
integrate/upstream-YYYYMMDD  合并官方更新的临时集成分支
backup/before-upstream-xxx   合并官方更新前的备份分支，可选
```

远程跟踪关系：

```text
origin/main      你自己的生产分支
upstream/main    官方主分支
```

不要直接在 `main` 上合并官方更新。正确流程是：

```text
main
  |
  | 创建集成分支
  v
integrate/upstream-YYYYMMDD
  |
  | 合并 upstream/main
  | 解决冲突
  | 本地测试
  | 测试服务器验证
  v
main
```

## 4. 上游同步模式

官方更新不是每次都必须完整合并。按风险从低到高分为四种模式。

### 4.1 只参考，不合并

适合场景：

- 想看看官方最近改了什么。
- 暂时不想影响本地代码。
- 只做技术参考。

命令：

```bash
git fetch upstream --tags
git log --oneline main..upstream/main
git diff main..upstream/main --stat
```

查看某个文件的官方变化：

```bash
git diff main..upstream/main -- path/to/file
```

### 4.2 选择性 cherry-pick

适合场景：

- 官方有某个 bugfix 你想单独拿过来。
- 不想合并官方全部更新。
- 官方大版本变化太大，暂时只取关键修复。

流程：

```bash
git fetch upstream
git checkout -b integrate/pick-upstream-fix main
git cherry-pick <official-commit-id>
```

处理冲突后：

```bash
git status
git add <resolved-files>
git cherry-pick --continue
```

验证通过后再合并到 `main`。

### 4.3 完整 merge 官方更新

适合场景：

- 希望跟随官方最新版本。
- 本地改动和官方差异可控。
- 有时间做完整测试。

流程：

```bash
git fetch upstream --tags
git checkout main
git pull origin main
git checkout -b integrate/upstream-20260609
git merge --no-ff upstream/main
```

如果有冲突，解决后：

```bash
git status
git add <resolved-files>
git commit
```

验证通过后：

```bash
git checkout main
git merge --no-ff integrate/upstream-20260609
git push origin main
```

### 4.4 局部覆盖官方文件

适合场景：

- 某些文件你希望完全以官方为准。
- 本地对这些文件没有定制。
- 只想覆盖文档、静态资源、某个模块等。

示例：

```bash
git fetch upstream
git checkout -b integrate/replace-docs-from-upstream main
git checkout upstream/main -- docs/Deployment.md
git checkout upstream/main -- main/manager-web/package.json
```

然后检查：

```bash
git diff
git status
```

确认无误后提交：

```bash
git add docs/Deployment.md main/manager-web/package.json
git commit -m "chore: sync selected files from upstream"
```

禁止在未备份、未建分支的情况下执行全量覆盖：

```bash
git reset --hard upstream/main
```

除非你的明确目标是完全放弃本地所有二次开发。

## 5. 官方更新评估流程

每次同步官方更新前，先做评估。

### 5.1 获取官方更新

```bash
git fetch upstream --tags
```

查看本地落后官方多少：

```bash
git log --oneline main..upstream/main
```

查看官方改动范围：

```bash
git diff main..upstream/main --stat
```

查看关键目录是否变化：

```bash
git diff main..upstream/main -- main/xiaozhi-server
git diff main..upstream/main -- main/manager-api
git diff main..upstream/main -- main/manager-web
git diff main..upstream/main -- main/manager-mobile
git diff main..upstream/main -- main/manager-api/src/main/resources/db/changelog
```

### 5.2 风险分级

低风险：

```text
README、docs 文档变化
注释变化
非核心静态资源变化
小范围 bugfix
```

中风险：

```text
依赖版本变化
前端 API 调用变化
manager-api controller/service 变化
Dockerfile 或 docker-compose 变化
config.yaml 新增配置项
```

高风险：

```text
数据库迁移变化
认证/权限变化
设备协议变化
WebSocket 消息处理变化
ASR/LLM/TTS/VAD 核心链路变化
大范围目录重构
删除旧配置项或旧接口
```

风险越高，越应该先合并到 `integrate/upstream-YYYYMMDD`，再部署测试环境验证。

## 6. 冲突处理规则

### 6.1 冲突优先级

遇到冲突时按以下优先级判断：

```text
生产安全 > 数据安全 > 本地核心定制 > 官方 bugfix > 官方格式调整
```

### 6.2 三种处理策略

本地优先：

```text
本地业务定制
本地接口兼容逻辑
本地阿里云部署配置
本地安全策略
```

官方优先：

```text
官方明确修复的 bug
官方协议升级
官方数据库结构必需变更
官方依赖安全升级
```

人工合并：

```text
同一文件既有本地业务定制，又有官方重要修复
数据库迁移冲突
配置结构变化
前后端接口一起变化
```

### 6.3 常用冲突命令

查看冲突文件：

```bash
git status
```

查看冲突差异：

```bash
git diff
```

保留本地版本：

```bash
git checkout --ours path/to/file
git add path/to/file
```

采用官方版本：

```bash
git checkout --theirs path/to/file
git add path/to/file
```

人工编辑完成后：

```bash
git add path/to/file
git commit
```

注意：`--ours` 和 `--theirs` 在 merge 场景下分别代表：

```text
ours    当前分支，也就是你的本地集成分支
theirs  被合并进来的分支，也就是 upstream/main
```

## 7. 配置文件同步规则

### 7.1 默认配置文件

官方可能更新：

```text
main/xiaozhi-server/config.yaml
main/xiaozhi-server/config_from_api.yaml
main/manager-api/src/main/resources/application.yml
main/manager-api/src/main/resources/application-dev.yml
docker-compose.yml
docker-compose_all.yml
```

这些文件可以参考或合并，但要遵守：

- 官方新增配置项要评估是否需要同步到本地模板。
- 生产 `.config.yaml` 不被 Git 管理，不能被覆盖。
- 生产 `.env.prod` 不被 Git 管理，不能被覆盖。
- 本地端口和生产端口差异由 Compose/Nginx 管理，不靠改源码。

### 7.2 官方新增配置项处理

如果官方 `config.yaml` 新增字段：

```text
1. 合并官方 config.yaml
2. 阅读新增字段含义
3. 判断本地 .config.yaml 是否需要覆盖
4. 如果生产环境需要，手动添加到服务器 .config.yaml
5. 如果不需要，保持默认值
```

不要直接把官方整份 `config.yaml` 复制成生产 `.config.yaml`。

## 8. 数据库同步规则

### 8.1 官方数据库迁移

官方数据库迁移位于：

```text
main/manager-api/src/main/resources/db/changelog/
main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
```

合并官方更新时必须检查这个目录：

```bash
git diff main..upstream/main -- main/manager-api/src/main/resources/db/changelog
```

如果官方新增迁移：

```text
1. 合并官方迁移文件
2. 检查 db.changelog-master.yaml 是否包含新增 changeSet
3. 检查是否和本地自定义迁移 id 冲突
4. 本地空库测试迁移
5. 本地已有数据测试迁移
6. 测试服务器验证
7. 生产发布前备份数据库
```

### 8.2 本地迁移与官方迁移冲突

可能出现：

```text
官方新增 202606091530.sql
本地也新增 202606091530.sql
```

处理规则：

- 如果本地迁移还没有部署到任何服务器，可以重命名本地迁移文件和 `changeSet id`。
- 如果本地迁移已经部署到测试或生产，不要直接改旧迁移。
- 已部署的迁移如需修正，应新增一个新的迁移文件。
- `changeSet id` 必须全局唯一。

### 8.3 真实数据不跟随官方同步

官方更新只能同步数据库结构和初始化数据，不能覆盖你的生产数据。

禁止操作：

```bash
mysql < official_full_dump.sql
```

除非你明确是在初始化一套全新测试环境。

生产环境必须遵守：

```text
结构用迁移脚本升级
数据用备份保护
真实业务数据不进 Git
真实业务数据不被官方示例数据覆盖
```

## 9. 依赖与构建同步规则

官方可能更新依赖文件：

```text
main/xiaozhi-server/requirements.txt
main/manager-api/pom.xml
main/manager-web/package.json
main/manager-web/package-lock.json
main/manager-mobile/package.json
main/manager-mobile/pnpm-lock.yaml
Dockerfile-server
Dockerfile-web
Dockerfile-server-base
```

同步这些文件后必须执行对应构建验证：

```bash
# Python
cd main/xiaozhi-server
pip install -r requirements.txt
python app.py

# Java
cd main/manager-api
mvn test
mvn clean package

# Web
cd main/manager-web
npm install
npm run build

# Mobile
cd main/manager-mobile
pnpm install
pnpm type-check
pnpm build:h5
```

如果官方更新了 lock 文件，不要只合并 `package.json` 而漏掉 lock 文件。

## 10. 本地定制清单

长期二次开发必须维护一份本地定制清单，建议新增：

```text
docs/local-customizations.md
```

记录内容：

```text
定制模块
改动原因
关键文件
是否允许被官方覆盖
合并官方更新时的注意事项
相关测试方法
```

示例：

```markdown
## 聊天历史增强

- 文件：
  - main/manager-api/src/main/java/xiaozhi/modules/agent/...
  - main/manager-web/src/components/ChatHistoryDialog.vue
- 原因：本地需要更完整的会话历史展示。
- 上游同步策略：本地优先，除非官方修复同模块严重 bug。
- 验证：登录管理后台，打开聊天历史弹窗，检查会话列表和详情。
```

没有本地定制清单时，后续合并官方更新会越来越难，因为你会忘记哪些地方是自己刻意改的。

## 11. 推荐同步流程

完整流程：

```text
1. 确认本地 main 干净
2. fetch 官方 upstream
3. 查看官方更新范围
4. 创建备份分支
5. 创建 integrate/upstream-YYYYMMDD
6. merge upstream/main
7. 解决冲突
8. 检查配置变化
9. 检查数据库迁移变化
10. 执行本地构建和测试
11. 部署测试服务器
12. 验证设备、管理后台、OTA、WebSocket
13. 合并到 main
14. 打 tag 或记录发布说明
15. 部署生产服务器
```

命令示例：

```bash
git checkout main
git pull origin main
git status

git fetch upstream --tags
git diff main..upstream/main --stat

git checkout -b backup/before-upstream-20260609
git checkout main
git checkout -b integrate/upstream-20260609

git merge --no-ff upstream/main
```

解决冲突并测试后：

```bash
git checkout main
git merge --no-ff integrate/upstream-20260609
git tag local-20260609-upstream-sync
git push origin main --tags
```

## 12. 发布记录规范

每次同步官方更新后，建议记录：

```text
同步日期
官方仓库 commit id
本地合并 commit id
同步方式：merge / cherry-pick / selected files
冲突文件
数据库迁移变化
配置变化
测试结果
是否已部署测试环境
是否已部署生产环境
回滚点
```

可以记录在：

```text
docs/release-notes/
  2026-06-09-upstream-sync.md
```

示例：

```markdown
# 2026-06-09 官方同步记录

- 官方基线：upstream/main abc1234
- 本地合并：main def5678
- 同步方式：merge
- 冲突文件：
  - main/xiaozhi-server/config.yaml
  - main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
- 数据库变化：新增 2 个 changeSet
- 配置变化：新增 server.xxx
- 测试：
  - manager-api mvn test 通过
  - manager-web npm run build 通过
  - 测试服务器设备连接通过
- 回滚点：tag local-before-upstream-20260609
```

## 13. 回滚策略

### 13.1 代码回滚

如果官方同步后出现问题：

```bash
git log --oneline
git revert -m 1 <merge-commit-id>
git push origin main
```

如果还没有推送到远程，可以直接放弃集成分支：

```bash
git checkout main
git branch -D integrate/upstream-20260609
```

### 13.2 数据库回滚

数据库回滚比代码回滚更危险。原则：

- 发布前必须备份数据库。
- 能用新的修复迁移解决，就不要直接回滚数据库。
- 如果迁移破坏数据，优先从备份恢复到发布前状态。
- 数据库恢复必须和代码版本匹配。

生产发布前：

```bash
docker exec xiaozhi-esp32-server-db mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" xiaozhi_esp32_server \
  > /opt/xiaozhi-esp32-server/backup/mysql/before_upstream_sync_$(date +%F_%H%M%S).sql
```

## 14. 哪些文件不能被官方覆盖

以下文件或目录属于本地/服务器私有内容，不能被官方更新覆盖：

```text
main/xiaozhi-server/data/.config.yaml
.env
.env.*
.secrets.yaml
.private_config.yaml
main/xiaozhi-server/mysql/
uploadfile/
main/xiaozhi-server/models/SenseVoiceSmall/model.pt
服务器 Nginx 生产配置
服务器 deploy/.env.prod
生产数据库数据
生产上传文件
```

如果这些内容被 Git 追踪，应该先从 Git 管理中移除，并加入 `.gitignore`。

## 15. 决策表

| 场景 | 推荐操作 |
| --- | --- |
| 只想看官方改了什么 | `git fetch upstream` + `git diff` |
| 只要官方某个修复 | `cherry-pick` |
| 想完整升级官方版本 | 新建 `integrate/upstream-YYYYMMDD` 后 merge |
| 只想用官方某个文件 | `git checkout upstream/main -- path` |
| 本地文件和官方冲突 | 按本地优先/官方优先/人工合并判断 |
| 官方新增数据库迁移 | 合并迁移，测试库先跑，生产前备份 |
| 官方改了配置文件 | 合并默认配置，手动更新本地/生产覆盖配置 |
| 官方改了 Docker Compose | 检查端口、volume、env，不直接覆盖生产 `.env` |
| 合并后测试失败 | 修复集成分支，不进 main |
| 合并后生产异常 | revert merge commit 或回到上一 tag，数据库按备份策略处理 |

## 16. 最终推荐工作流

日常开发：

```text
origin/main -> feature/xxx -> 本地测试 -> merge origin/main -> 部署
```

官方同步：

```text
upstream/main
  |
  v
integrate/upstream-YYYYMMDD
  |
  | 合并、冲突处理、测试、测试服务器验证
  v
origin/main
  |
  v
阿里云生产部署
```

一句话原则：

```text
官方更新先进入集成分支，本地验证后再进入 main；配置和数据永远不被官方覆盖。
```

