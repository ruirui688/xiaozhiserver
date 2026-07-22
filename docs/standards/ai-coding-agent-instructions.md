# AI 编程工具工作指令

本文档面向 Codex、Cursor、Copilot、Claude Code 等 AI 编程工具，用于规定 AI 在本项目中新开发功能、修改功能、修复问题、同步官方更新或整理文档时必须遵守的执行流程。

AI 工具必须先读本文档，再开始修改代码。

## 1. 必读文档

每次开始任务前，AI 必须读取：

```text
AGENTS.md
README.md
docs/README.md
docs/standards/development-specification.md
docs/standards/ai-coding-agent-instructions.md
```

如果修改具体模块，还必须读取对应模块文档：

```text
main/manager-api/README.md      修改 Java 管理 API 时必读
main/manager-web/README.md      修改 Web 管理后台时必读
main/manager-mobile/README.md   修改移动端管理后台时必读
```

如果仓库中存在本地定制记录，还必须读取：

```text
docs/local-customizations.md
```

如果任务涉及数据库，还必须检查：

```text
main/manager-api/src/main/resources/db/changelog/
main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
```

如果任务涉及运行配置，还必须检查：

```text
main/xiaozhi-server/config.yaml
main/xiaozhi-server/config_from_api.yaml
main/manager-api/src/main/resources/application.yml
main/manager-api/src/main/resources/application-dev.yml
```

## 2. 权限分级

AI 可以自动执行：

```text
读取文件
搜索代码
查看 git status
查看 git diff
查看 remote
创建本地 feature 分支
修改本次任务相关文件
执行非破坏性测试或构建命令
```

AI 执行前必须说明原因：

```text
安装依赖
执行可能访问外部服务的命令
启动长期运行服务
执行数据库迁移
修改锁文件
修改大量文件
```

未经用户明确授权，AI 禁止执行：

```text
commit
push
创建或合并 Pull Request / Merge Request
创建 tag
删除分支
删除 tag
删除远程分支
修改远程仓库配置
执行生产部署
修改生产数据库
执行破坏性 Git 命令
```

## 3. 开工前检查

AI 在修改任何文件前，必须先执行：

```bash
git status --short --branch
git remote -v
```

必须确认：

```text
当前分支
当前分支是否属于本次任务
是否存在未提交改动
哪个远端是私有仓库
哪个远端是官方仓库
```

远端判断必须以 `git remote -v` 的实际结果为准：

```text
origin    ssh://git@10.144.92.32:222/gaoruirui688/xiaozhi-esp32-server.git
upstream  https://github.com/xinnan-tech/xiaozhi-esp32-server.git
```

标准状态必须是：

```text
origin = 私有仓库，允许 push
upstream = 官方仓库，只允许 fetch
```

如果发现远端命名或 URL 不符合标准，AI 必须在输出中明确说明差异，并在获得用户明确授权后才能执行 remote rename 或 set-url。AI 不得在没有确认真实私有远端前执行 push。

如果当前位于 `main`，且任务需要修改文件，AI 可以自动创建本地 `feature/xxx` 分支，但必须先说明分支名称。

如果当前处于其他任务的 `feature/xxx` 分支：

```text
工作区干净时，切回 main，更新 main，再创建新的 feature 分支。
工作区存在未提交改动时，禁止强行切换、stash 或提交。
必须向用户说明现状，避免把不同任务混入同一分支。
```

如果发现工作区有用户已有改动：

- 禁止回滚用户改动。
- 禁止覆盖不相关文件。
- 禁止自动 stash。
- 禁止自动提交。
- 必须只修改本次任务相关文件。
- 如果用户已有改动与本次任务冲突，必须先说明冲突点。

## 4. 分支命名

普通功能或修复问题必须使用：

```text
feature/xxx
```

官方同步必须使用：

```text
sync/upstream-YYYYMMDD
```

`feature/xxx` 的 `xxx` 必须使用小写英文和连字符，并表达任务目的。

正确示例：

```text
feature/add-device-auth
feature/fix-websocket-timeout
feature/update-tts-config
```

错误示例：

```text
feature/test
feature/update
feature/new
feature/mybranch
```

AI 不得创建：

```text
develop
release
fix/xxx
hotfix/xxx
backup/xxx
```

## 5. upstream 比对责任

`upstream` 是官方原始仓库，它不会自动替本地项目做比对。

upstream 比对由当前执行开发任务的一方负责：

```text
人类开发者执行任务时，由人类开发者检查。
AI 工具执行任务时，由 AI 工具检查。
CI/CD 建立后，可以由 CI 定时或在合并前自动检查。
阿里云生产服务器不负责 upstream 比对。
```

AI 在以下场景必须检查 upstream：

- 用户明确要求跟进官方更新。
- 修改涉及核心链路：WebSocket、OTA、设备协议、认证、配置、数据库、Docker。
- 预计修改官方核心文件，且可能与官方近期更新产生冲突。
- 距离上一次 upstream 检查超过项目约定周期。
- 准备创建 `sync/upstream-YYYYMMDD` 分支。

单纯修改本地文档、样式、文案或明确属于本地扩展模块时，可以不执行 upstream fetch，但最终必须说明：

```text
本次未检查 upstream，因为修改不涉及官方核心实现。
```

如果当前仓库还没有按标准命名配置 `upstream`，AI 必须先提示需要执行 remote 标准化，不得假装已完成 upstream 比对。

## 6. upstream 比对命令

必须优先使用共同祖先作为比较基线：

```bash
git fetch upstream --tags
BASE=$(git merge-base main upstream/main)
git log --oneline "$BASE"..upstream/main
git diff --stat "$BASE"..upstream/main
```

如果任务只涉及部分目录，必须对相关目录做局部比对：

```bash
git diff "$BASE"..upstream/main -- main/xiaozhi-server
git diff "$BASE"..upstream/main -- main/manager-api
git diff "$BASE"..upstream/main -- main/manager-web
git diff "$BASE"..upstream/main -- main/manager-mobile
git diff "$BASE"..upstream/main -- main/manager-api/src/main/resources/db/changelog
```

只有在需要查看本地 `main` 与官方 `upstream/main` 最终状态差异时，才使用：

```bash
git diff main..upstream/main
```

## 7. 功能开发流程

AI 新开发功能或修改功能时，必须按以下流程执行：

```text
1. 读取必读文档
2. 检查 git 状态和 remote
3. 确认当前分支是否适合本任务
4. 必要时创建本地 feature 分支
5. 判断影响模块
6. 搜索现有实现和调用链
7. 判断是否需要 upstream 比对
8. 制定最小改动范围
9. 修改代码或文档
10. 执行对应模块验证
11. 检查 diff
12. 汇总改动和验证结果
```

禁止：

- 未搜索现有实现就新增重复模块。
- 未确认调用链就删除代码。
- 未确认配置来源就硬编码端口、域名、密钥。
- 未确认数据库迁移规则就直接改实体或 SQL。
- 为了完成任务修改无关格式。

## 8. 验证命令

验证必须优先使用静态检查、单元测试和可自动结束的命令。

只有用户明确要求启动服务，或确认存在隔离开发环境时，AI 才能启动长期运行服务。

Python 核心服务：

```bash
cd main/xiaozhi-server
conda run -n xiaozhi-esp32-server python -m compileall .
```

如果项目存在可用测试，再执行：

```bash
conda run -n xiaozhi-esp32-server pytest
```

强制约束：

- `main/xiaozhi-server` 的运行时使用 Conda 环境 `xiaozhi-esp32-server`。
- AI 执行 Python 验证、脚本、单测、导入检查时，必须使用 `conda run -n xiaozhi-esp32-server ...`，或先确认当前 shell 已 `conda activate xiaozhi-esp32-server`。
- 禁止直接使用系统 `python`、`python3`、`pip`、`pytest` 验证 `main/xiaozhi-server`。系统 Python 缺少运行时依赖时得到的错误不代表项目运行时错误。
- 如果当前机器没有 Conda 或目标环境不存在，必须如实说明“未在项目 Conda 运行时验证”，不得退回系统 Python 冒充验证。
- 本地启动必须优先使用 `scripts/dev start server`；该脚本会拒绝退回系统 Python。如需使用非标准 Conda 安装路径，必须显式设置 `XIAOZHI_SERVER_PYTHON=/path/to/env/bin/python`。

禁止默认直接执行长期运行命令：

```bash
conda run -n xiaozhi-esp32-server python app.py
```

Java 管理 API：

```bash
cd main/manager-api
mvn test
```

完整验证时使用：

```bash
mvn clean package
```

Web 管理后台：

```bash
cd main/manager-web
npm ci
npm run test:unit
npm run build
```

Web 功能测试必须在生产构建前执行；测试失败时不得用构建成功替代测试结论。

如果 `package-lock.json` 不存在，AI 必须说明无法使用 `npm ci`，不得无说明执行可能更新锁文件的安装命令。

移动端管理后台：

```bash
cd main/manager-mobile
pnpm install --frozen-lockfile
pnpm test:unit
pnpm type-check
pnpm build:h5
pnpm build:mp-weixin
```

Mobile 功能测试必须先于类型检查和目标平台构建执行；微信相关变更不能只用 H5 构建代替微信小程序构建。

只有在明确需要修改依赖时，才能执行可能更新锁文件的安装命令。

AI 必须根据实际修改范围选择验证命令。无法执行验证时，必须说明原因。

## 9. 数据库修改规则

如果任务涉及表结构、初始化数据、系统参数或数据访问逻辑，AI 必须检查 Liquibase 迁移目录：

```text
main/manager-api/src/main/resources/db/changelog/
main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
```

必须遵守：

- 表结构变化必须新增迁移 SQL。
- 新迁移必须登记到 `db.changelog-master.yaml`。
- 已发布迁移禁止修改。
- 生产数据禁止进入 Git。
- 示例数据必须脱敏。

AI 不得直接建议把生产数据库 dump 提交到仓库。

## 10. 配置修改规则

AI 修改配置时必须区分：

```text
默认配置        可以提交
配置模板        可以提交
环境私有配置    禁止提交
运行数据        禁止提交
```

禁止提交：

```text
.env
.env.local
.env.dev
.env.prod
.env.production
.config.yaml
.secrets.yaml
.private_config.yaml
真实 API Key
真实数据库密码
真实 Redis 密码
含身份信息、内网地址、访问凭据或安全敏感信息的生产配置
```

允许提交：

```text
.env.example
.env.template
.env.sample
```

如果需要新增配置项，AI 必须：

```text
1. 修改默认配置或模板
2. 在文档中说明本地、测试、生产应如何覆盖
3. 禁止写入真实密钥
```

## 11. 官方同步流程

AI 只有在用户明确要求同步官方更新时，才执行官方同步。

必须使用：

```bash
git checkout main
git pull --ff-only origin main
git fetch upstream --tags
git checkout -b sync/upstream-YYYYMMDD
git merge --no-ff upstream/main
```

同步后必须检查：

```text
配置文件变化
数据库迁移变化
Docker / Compose 变化
认证和权限变化
设备协议变化
本地定制是否被覆盖
```

## 12. 破坏性命令禁用清单

未经用户明确授权，AI 禁止执行：

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

AI 不得用这些命令解决工作区不干净、冲突或用户已有改动问题。

## 13. 文件格式规则

AI 修改文件时不得无故改变：

```text
文件换行格式
文件编码
文件权限
符号链接
与任务无关的缩进或格式
```

如果 diff 出现大量无关换行变化、权限变化或格式化变化，AI 必须停止并修正。

## 14. 修改后检查

AI 完成修改后必须执行：

```bash
git diff --check
git diff --stat
git diff
git status --short
```

必须确认：

```text
没有无关文件
没有密钥
没有意外锁文件变化
没有大面积格式化
没有生成运行数据
没有覆盖用户已有改动
```

## 15. Pull Request 规则

AI 完成功能开发后，可以提供 PR 标题、PR 描述和测试说明。

未经用户明确授权，AI 不得：

```text
自动创建 PR
自动批准 PR
自动合并 PR
自动删除远程分支
```

PR 来源分支必须是：

```text
feature/xxx
sync/upstream-YYYYMMDD
```

PR 目标分支通常为：

```text
main
```

功能分支是一份完整项目版本，但 PR 合并的是该分支相对于 `main` 的修改差异，不是重新复制整个项目。

## 16. AI 输出要求

AI 完成任务后必须说明：

```text
修改了哪些文件
为什么这样改
是否检查 upstream
执行了哪些验证
哪些验证未执行以及原因
是否涉及配置、数据库、部署或生产风险
是否存在未提交改动
```

如果用户明确要求并且 AI 已创建 commit、push、tag 或 PR，必须说明对应 commit id、远程分支、tag 或 PR 地址。
