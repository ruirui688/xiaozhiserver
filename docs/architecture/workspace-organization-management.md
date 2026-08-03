---
status: supporting
owner: manager-api
last_verified: 2026-07-12
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/workspace
  - main/manager-api/src/main/resources/db/changelog
---

# 工作空间组织管理与数据隔离

本文档说明 manager-api / manager-web 中的组织工作空间模型、资源共享范围、权限规则、数据隔离方式和验证要求。后续新增智能体、设备、记忆、知识库等业务接口时，必须按本文档补齐工作空间鉴权。

## 1. 目标

工作空间用于把用户创建和管理的业务资源从单用户私有模型升级为组织共享模型。

核心目标：

- 用户注册后自动拥有一个默认工作空间。
- 每个用户只能拥有注册时自动创建的默认工作空间，不能再手动创建额外工作空间。
- 用户可以加入他人的工作空间，也可以邀请别人加入自己的默认工作空间。
- 每个工作空间最多 5 名 active 成员；发起邀请时会同时统计未过期的待接受邀请，避免超额邀请。
- 工作空间成员共享业务资源，包括智能体、设备、记忆、聊天历史、知识库、声纹、上下文源、替换词、智能体 MCP HTTP 工具等。
- 设备仍只能绑定到一个用户/一条设备记录，但同一工作空间内的成员可共同查看和管理该工作空间内的设备。
- 系统级资源不纳入工作空间隔离，例如模型配置、系统参数、OTA、全局音乐、管理员用户管理。

## 2. 数据模型

核心表：

```text
sys_workspace              工作空间
sys_workspace_member       工作空间成员
sys_workspace_invitation   工作空间邀请
```

主要字段：

```text
sys_workspace.id
sys_workspace.name
sys_workspace.owner_user_id
sys_workspace.default_workspace
sys_workspace.status

sys_workspace_member.workspace_id
sys_workspace_member.user_id
sys_workspace_member.role        OWNER / ADMIN / MEMBER
sys_workspace_member.status

sys_workspace_invitation.workspace_id
sys_workspace_invitation.invitee_username
sys_workspace_invitation.invitee_user_id
sys_workspace_invitation.role
sys_workspace_invitation.code
sys_workspace_invitation.status
sys_workspace_invitation.expires_at
```

业务资源表通过 `workspace_id` 归属到工作空间：

```text
ai_agent
ai_device
ai_rag_dataset
ai_rag_knowledge_document
ai_rag_knowledge_chunk
ai_agent_chat_history
ai_memory_fact
ai_memory_candidate
ai_memory_source_event
ai_memory_profile
ai_memory_tool_behavior
ai_memory_forget_tombstone
ai_memory_revision
```

迁移文件：

```text
main/manager-api/src/main/resources/db/changelog/202607091100.sql
main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
```

迁移会为历史用户创建默认工作空间和 OWNER 成员关系，并按 `user_id`、`creator`、`agent_id` 回填历史业务资源的 `workspace_id`。

## 3. 权限模型

工作空间角色：

| 角色 | 读 | 写 | 成员管理 |
|------|----|----|----------|
| OWNER | 是 | 是 | 是，可管理 OWNER |
| ADMIN | 是 | 是 | 可移除非 OWNER 成员；不能修改成员角色，不能管理 OWNER |
| MEMBER | 是 | 否 | 否 |

当前实现中 OWNER 和 ADMIN 对业务资源的写权限一致。MEMBER 只能查看，不能新增、修改、删除或调用会改变设备状态的操作。

成员管理额外约束：

- ADMIN 不能移除 OWNER。
- ADMIN 不能授予 OWNER，也不能撤销 OWNER。
- OWNER 可以调整其他成员角色或移除其他成员，但不能修改自己的角色，也不能让工作空间失去最后一个 OWNER。
- 邀请不能直接邀请为 OWNER，只能邀请为 ADMIN 或 MEMBER；OWNER 需要由现有 OWNER 后续授予。
- 邀请时确定新成员初始角色；被邀请人接受后不能自行修改自己的角色。
- 工作空间成员上限为 5 人。邀请创建时按 active 成员 + 未过期待接受邀请计数；接受邀请时再次按 active 成员计数。

权限入口：

```text
WorkspaceService.canRead(workspaceId, userId)
WorkspaceService.canWrite(workspaceId, userId)
WorkspaceService.requireRead(workspaceId)
WorkspaceService.requireWrite(workspaceId)
WorkspacePermissionService.currentWorkspaceId()
WorkspacePermissionService.requireRead(workspaceId)
WorkspacePermissionService.requireWrite(workspaceId)
```

超级管理员仍保留系统级跨工作空间权限。这是当前管理后台的管理员模型，不等同于普通成员权限。

## 4. 请求上下文

manager-web 在所有请求中追加当前工作空间：

```text
X-Workspace-Id: <workspace_id>
```

后端不得信任该 header。服务端必须通过成员表验证当前登录用户是否属于该工作空间。

当前工作空间解析规则：

```text
1. 请求包含 X-Workspace-Id
   -> 查询工作空间是否存在且启用
   -> requireRead(workspaceId)
   -> 返回该工作空间

2. 请求不包含 X-Workspace-Id
   -> 获取或创建当前用户默认工作空间
```

因此，伪造他人的 `workspace_id` 不能绕过后端权限。

## 5. 资源隔离规则

新增业务资源时：

```text
workspace_id = WorkspaceService.getCurrentWorkspaceEntity().getId()
```

列表查询时：

```text
WHERE workspace_id = 当前工作空间
```

按资源 ID 读写时：

```text
1. 先根据资源 ID 查询资源实体
2. 从实体读取 workspace_id
3. 读操作调用 requireRead(workspace_id)
4. 写操作调用 requireWrite(workspace_id)
5. 不允许直接相信请求体中的 agentId / deviceId / workspaceId
```

智能体权限兼容规则：

```text
1. 超级管理员直接放行
2. 历史数据 workspace_id 为空时，回退 user_id 所有者校验
3. 正常数据按 workspace membership 校验
```

设备权限规则：

```text
1. 设备记录仍唯一，不能被多个用户重复绑定成多条业务记录
2. 设备列表按当前 workspace_id 和 agent_id 查询
3. 设备工具列表是读操作，调用 requireRead(device.workspace_id)
4. 设备工具调用、解绑、调试、更新配置是写操作，调用 requireWrite(device.workspace_id)
```

记忆、聊天历史、知识库和声纹等资源必须跟随所属智能体或知识库的工作空间，不允许只用当前用户 ID 过滤。

## 6. 数据安全保障

数据安全由服务端保证，前端只提供交互约束。

已落地的保障：

- 工作空间 header 只作为选择器，不作为授权凭据。
- 所有主要业务资源持久化 `workspace_id`。
- 列表接口按当前工作空间过滤。
- 写接口按资源所属工作空间执行 `requireWrite`。
- MEMBER 写操作在后端被拒绝。
- 新注册用户和微信自动创建用户会立即拥有默认工作空间。
- Liquibase 迁移会回填历史数据，减少旧数据裸露在工作空间模型之外。

仍需持续注意：

- 新增接口必须纳入工作空间鉴权审查。
- 超级管理员可以跨工作空间访问，账号安全必须按系统管理员级别保护。
- 系统级资源未做工作空间隔离，不能把本模型理解为全系统多租户隔离。
- 历史库上线前应备份数据库，并抽样检查 `workspace_id IS NULL` 的业务数据。

## 7. 用户注销、踢出与退出

### 7.1 用户注销 / 删除账号

删除用户账号前必须先执行工作空间注销准备：

```text
WorkspaceService.prepareUserDeletion(userId)
```

处理规则：

```text
1. 查询用户所有 active 工作空间成员关系
2. 如果用户在任一工作空间是最后一个 OWNER
   -> 拒绝删除账号
   -> 要求先转让 OWNER
3. 如果不存在最后 OWNER 风险
   -> 将该用户所有工作空间成员关系 status 置为 0
4. 删除 sys_user
```

账号删除不再按 `user_id` 直接删除智能体和设备，避免误删已经属于组织工作空间的共享资源。共享资源的后续归属由工作空间继续承载。

### 7.2 OWNER 踢出成员

移除成员接口：

```text
DELETE /workspace/{workspaceId}/members/{userId}
```

规则：

```text
1. 操作者必须有工作空间写权限
2. 如果目标成员是 OWNER，操作者必须也是 OWNER
3. 不能移除最后一个 OWNER
4. 操作者不能移除自己，应使用主动退出接口
5. 成员关系 status 置为 0，不做物理删除
6. 被移除成员重新接受有效邀请时，复用原成员关系并恢复 status 为 1，同时按新邀请更新角色，不重复插入成员记录
```

### 7.3 修改角色

修改角色接口：

```text
PUT /workspace/{workspaceId}/members/{userId}/role
```

规则：

```text
1. 操作者必须是 OWNER
2. 角色只能是 OWNER / ADMIN / MEMBER
3. 操作者不能修改自己的角色
4. 不能撤销最后一个 OWNER
```

### 7.4 主动退出工作空间

退出接口：

```text
DELETE /workspace/{workspaceId}/members/me
```

规则：

```text
1. 当前用户必须是该工作空间 active 成员
2. 如果当前用户是 OWNER
   -> 拒绝退出
   -> 要求先转让或调整所有者身份
3. 退出时只停用成员关系，不删除工作空间资源
```

## 8. 开发约束

新增或修改业务接口时必须检查：

```text
是否读取了智能体、设备、知识库、记忆、聊天历史、声纹等工作空间资源？
是否只按 user_id 过滤？如果是，应改为 workspace_id。
是否从请求体信任 agentId / deviceId / workspaceId？如果是，应改为先查实体再鉴权。
读接口是否调用 requireRead？
写接口是否调用 requireWrite？
新建资源是否写入当前 workspace_id？
数据库迁移是否登记到 db.changelog-master.yaml？
是否需要补跨工作空间访问测试？
```

禁止模式：

```text
// 错误：只按当前用户过滤
WHERE user_id = currentUserId

// 错误：直接信任请求体 workspaceId
resource.setWorkspaceId(dto.getWorkspaceId())

// 错误：只在前端隐藏按钮，后端不校验
if (frontEndCanWrite) save()
```

推荐模式：

```text
workspaceId = workspaceService.getCurrentWorkspaceEntity().getId()
query.eq("workspace_id", workspaceId)

resource = dao.selectById(id)
workspacePermissionService.requireWrite(resource.getWorkspaceId())
```

## 9. 功能验证

当前功能验证覆盖：

```text
mvn -Dtest=WorkspaceServiceImplTest,AgentWorkspaceIsolationTest,DeviceWorkspaceIsolationTest test
```

验证点：

- 非成员伪造 `X-Workspace-Id` 访问其他工作空间会被拒绝。
- MEMBER 可以读但不能写。
- 新用户会创建默认工作空间并成为 OWNER。
- ADMIN 不能移除 OWNER，不能授予 OWNER。
- OWNER 不能退出最后 OWNER 身份。
- 删除账号前会拒绝最后 OWNER，并停用可注销用户的成员关系。
- 智能体列表查询包含 `workspace_id` 条件。
- 设备列表查询包含 `workspace_id` 和 `agent_id` 条件。

全量 Java 验证：

```bash
cd main/manager-api
mvn test
```

镜像和迁移验证：

```bash
docker build -f Dockerfile-manager-api -t xiaozhi-manager-api:workspace-local .
docker build -f Dockerfile-manager-web -t xiaozhi-manager-web:workspace-local .
```

使用临时 MySQL / Redis 启动 manager-api 后，应确认：

```sql
SELECT ID, AUTHOR FROM DATABASECHANGELOG WHERE ID = '202607091100';
SHOW TABLES LIKE 'sys_workspace';
SHOW COLUMNS FROM ai_agent LIKE 'workspace_id';
```

前端构建验证可通过 manager-web 镜像完成，容器内会执行 `npm install` 和 `npm run build`。

## 10. 已知边界

当前版本不是全系统 SaaS 多租户隔离，范围限定在管理后台业务资源共享与隔离。

不纳入工作空间的资源：

```text
模型配置
模型供应商
系统参数
OTA 管理
全局音乐库
管理员用户管理
服务端运行配置
Python xiaozhi-server 实时连接内部状态
```

这些资源仍按现有系统权限模型管理。若后续需要租户级模型配置或系统参数隔离，应单独设计资源归属和配置下发链路。
