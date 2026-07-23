---
status: supporting
owner: tools
last_verified: 2026-07-23
source_paths:
  - main/xiaozhi-server/core/providers/tools
  - main/xiaozhi-server/plugins_func
---

# 功能插件架构

## 顶层模型

功能插件是小智服务端对外暴露“可被智能体选择、配置、调用”的统一能力模型。

所有进入角色配置 `功能管理` 弹窗的能力，都必须先被视为同一种顶层对象：

```text
Function Plugin
```

功能插件不按“天气一类、小游戏一类、音乐一类”分散设计，而是统一进入同一条链路：

```text
插件定义 -> 角色启用 -> 运行时下发 -> 工具注册 -> 工具调用 -> 回复生成
```

## 分层职责

```text
管理后台 Web
  维护功能插件定义
  在角色配置中选择功能插件
  编辑该角色下的插件参数

管理后台 API
  读取 ai_model_provider 中 model_type = Plugin 的插件定义
  保存 ai_agent_plugin_mapping 中的角色插件启用关系和参数
  下发智能体运行配置给 xiaozhi-server

xiaozhi-server
  读取智能体已启用插件
  将插件注册为 function call 工具
  根据插件实现模式执行工具
  把结果交给 LLM 或直接返回给用户
```

## 数据模型

插件定义表：

```text
ai_model_provider
  model_type = Plugin
  provider_code = function call 函数名
  name = 功能显示名
  fields = 参数字段定义
  sort = 功能排序
```

角色启用关系：

```text
ai_agent_plugin_mapping
  agent_id = 智能体 ID
  plugin_id = ai_model_provider.id
  param_info = 当前智能体对该插件的参数覆盖
```

这两个表是功能插件的唯一管理模型。新增讲段子、热梗、成语接龙、你说我猜，不新增一套游戏表，也不新增单独的启用关系。

## 实现模式

功能插件按运行时执行方式分为不同模式。模式是插件属性，不是新的顶层模块。

### 内置执行型

适用于已有 Python 函数实现的插件，例如：

- 天气查询
- 新闻聚合
- 服务器音乐播放
- HomeAssistant 控制

运行方式：

```text
provider_code 命中 all_function_registry
  -> xiaozhi-server 执行对应 Python 函数
  -> 返回 ActionResponse
```

服务器音乐播放 `play_music` 属于内置执行型，但有单独的音频队列边界：

```text
LLM / intent_llm 选择 play_music
  -> play_music 等待异步选曲和入队完成
  -> 返回 Action.RECORD，聊天记录保留工具调用审计
  -> TTS 队列按 FIRST/ACTION -> MIDDLE/TEXT -> MIDDLE/FILE -> LAST/ACTION 播放
```

其中 `MIDDLE/TEXT` 是“即将为您播放/正在为您播放 具体歌曲”这类选曲提示语，必须按普通 TTS 播出；不要只发屏幕状态消息。`MIDDLE/FILE` 是真实本地音乐文件，流式 TTS provider 会依赖后续 `LAST/ACTION` 把暂存音频 flush 到设备。播放、唱某首歌、随机播放和曲库查询不应再由旧意图入口硬编码派发；模型应根据 `play_music` 工具描述、`<music_library>` 曲库上下文和用户表达决定是否调用工具。

托管长期记忆的 `manage_memory` 也是内置执行型工具，但不是后台可配置的业务插件。选择 `managed_memory` 且主聊天支持原生 function call 时，运行时把它作为系统能力注册给主 LLM：

```text
主 LLM 自主决定调用 manage_memory
  -> remember | forget
  -> private | shared
  -> manager-api 维护模型做结构化、去重和真实 ID 选择
```

工具注册只检查记忆 provider 和 function-call 能力，不检查用户说了什么。工具描述及执行链路不得加入固定人物、关键词、条件任务、场景黑白名单、TTL 或相似度规则。

### 声明式互动型

适用于不需要服务端代码执行、主要靠 LLM 维持规则的轻量互动玩法，例如：

- 讲段子
- 热梗解释
- 猜谜

注意：成语接龙、你说我猜、小智想词用户猜已回退为主 LLM 自由对话，不再作为状态机工具强制注入。旧 provider_code 只作为兼容标识保留。

它仍然是一条 `model_type = Plugin` 记录，但 `fields` 中包含兼容标记：

```json
{
  "key": "custom_plugin",
  "type": "boolean",
  "label": "声明式互动",
  "default": true
}
```

`custom_plugin` 是历史兼容字段名。架构含义不是“另一个自定义插件系统”，而是：

```text
该插件没有 Python 执行函数，由后台声明玩法规则，运行时交给 LLM 继续生成回复。
```

声明式互动型推荐字段：

```json
[
  {
    "key": "custom_plugin",
    "type": "boolean",
    "label": "声明式互动",
    "default": true
  },
  {
    "key": "description",
    "type": "textarea",
    "label": "工具描述",
    "default": "当用户想听一个短笑话时调用。"
  },
  {
    "key": "instructions",
    "type": "textarea",
    "label": "玩法说明",
    "default": "你是语音段子主持人。每次只讲一个短段子，适合 TTS 播报；用户说再来、换个类型或不好笑时，根据反馈换一个方向，不要重复同一个段子。"
  },
  {
    "key": "opening_message",
    "type": "textarea",
    "label": "开场白",
    "default": "给你来一个短一点、好懂一点的。"
  },
  {
    "key": "parameters_schema",
    "type": "json",
    "label": "函数参数 Schema",
    "default": {
      "type": "object",
      "properties": {
        "user_message": {
          "type": "string",
          "description": "用户当前触发这个互动插件时说的话"
        },
        "context": {
          "type": "string",
          "description": "可选的对话上下文或额外提示"
        }
      },
      "required": ["user_message"]
    }
  }
]
```

运行方式：

```text
provider_code 未命中 all_function_registry
  -> 插件参数 custom_plugin = true
  -> xiaozhi-server 动态生成 function call 工具描述
  -> LLM 调用该工具
  -> xiaozhi-server 返回玩法说明和工具参数
  -> LLM 继续生成自然回复
```

声明式互动型不运行用户上传代码，不访问任意外部接口，不负责严格计分或跨会话状态。

### 后续扩展型

如果未来需要接入更复杂的插件，应继续挂在 `Function Plugin` 顶层模型下，再新增实现模式，例如：

- 外部 HTTP 工具型
- MCP 桥接型
- 服务端状态型

### 服务端状态型

仅适用于必须访问服务端状态、外部接口或设备能力的能力。纯语言互动游戏不应放在这一层。

会议纪要属于服务端状态型插件。启用后注册真实 `meeting_minutes` function tool，会议开始、正文记录和结束全部由主 LLM 根据当前发言主动选择 `start / record / end`，服务端不维护进入或退出话术词表，也不通过语义控制中枢预判会议意图。会议激活期间每条 ASR 都串行等待上一轮工具执行完成，只向主 LLM 暴露会议工具，并在 provider 支持时要求本轮必须调用工具；具体选择 `record` 还是 `end` 仍由模型理解上下文。只有真实工具调用成功才改变状态，普通文字不能模拟开始或结束。结束后由主 LLM 基于冻结的完整结构化转写同时生成设备口语总结和飞书书面纪要，不引入独立 SLM 裁决。

不再强制注入的旧游戏 code：

```text
guess_what_i_say
idiom_chain
xiaozhi_thinks_you_guess
guess_xiaozhi_word
i_think_you_guess
```

运行方式：

```text
provider_code 命中真实服务端工具
  -> xiaozhi-server 生成工具描述，注入给模型用于触发 function call
  -> 工具执行器访问服务端状态、设备、MCP 或外部接口
  -> 返回 Action.RESPONSE 或 Action.REQLLM
```

当前原则：纯语言游戏交给主 LLM 自由对话，但规则来源仍是功能插件。智能体勾选某个游戏插件后，`instructions` / `opening_message` 会作为临时软提示词注入主 LLM；不要用小候选库、关键词规则或固定问答状态机压过模型能力。

旧游戏 code 只作为兼容标识保留在 `GameSessionManager.FREEFORM_GAME_TOOLS`，`ServerPluginExecutor` 跳过这些 code，
`handle_user_intent()` 也不得提前路由。只有必须访问设备、MCP、数据库、外部接口或事务状态的玩法才适合真实服务端工具；
成语接龙、猜谜、角色扮演、问答和讲故事等纯语言互动不新增状态机。

## 提示词注入位置

插件提示词并不全部写在 `agent-base-prompt.txt`。运行时有两类注入：

```text
工具描述注入：ServerPluginExecutor.get_tools() -> UnifiedToolHandler.get_functions() -> LLM tools 字段
声明式插件二次提示：_execute_declarative_interaction_plugin() -> Action.REQLLM -> 再请求 LLM
```

成语接龙、你说我猜、小智想词用户猜等纯语言游戏不再注入为工具。它们由主 LLM 根据对话上下文和当前功能插件的可编辑规则自然处理。

完整链路见：

```text
docs/architecture/project-architecture.md
docs/architecture/runtime-flows.md
docs/architecture/prompt-injection-engineering.md
```

新增模式前必须先定义：

- 插件定义字段
- 运行时执行器
- 权限和安全边界
- 角色参数如何保存
- 失败时的 ActionResponse 行为

## 后台入口

正式入口：

```text
参数字典 -> 智能体功能库
```

正式路由：

```text
/function-plugin-management
```

旧路由仅用于兼容跳转：

```text
/custom-plugin-management -> /function-plugin-management
```

智能体功能库页面负责维护所有 `Plugin` 定义，但页面表达必须面向用户任务，不直接暴露 `Function Plugin`、`custom_plugin`、`parameters_schema` 等实现概念。

一级筛选：

```text
全部功能
系统能力
互动玩法
```

系统能力必须继续按使用场景二级分类，避免天气、新闻、音乐、设备控制和搜索堆在一起：

```text
全部系统能力
生活服务    天气等生活查询能力
内容资讯    新闻、热榜、联网搜索等信息获取能力
音乐音频    服务器音乐、HomeAssistant 音乐播放等音频能力
设备控制    HomeAssistant 状态查询/修改、设备呼叫等控制能力
工具能力    其他暂未归类的工具
```

互动玩法不再进入系统能力二级分类，统一归入：

```text
聊天玩法
```

智能体功能库页面行为：

- 展示全部功能，但默认用“系统能力 / 互动玩法”解释来源。
- 系统能力只允许查看说明，不在页面内编辑或删除。
- 系统能力的新增和实现仍以代码、数据库迁移或专用集成流程为准。
- 互动玩法允许新建、编辑、复制和删除。
- 新建互动玩法时可选择讲段子、热梗、成语接龙、你说我猜等预设规则，也可以从空白玩法开始。
- 玩法规则应包含触发场景、玩法流程、判断标准、主持技巧、异常处理和安全边界，不能只写一句简单描述。

## 角色启用入口

角色配置页中的 `功能管理` 弹窗是唯一启用入口。

天气、新闻、音乐播放、HomeAssistant、讲段子、热梗、成语接龙、你说我猜都在同一个列表中选择和配置。

保存角色时，只保存统一结构：

```json
{
  "functions": [
    {
      "pluginId": "插件定义 ID",
      "paramInfo": {
        "description": "工具描述",
        "instructions": "玩法说明"
      }
    }
  ]
}
```

## 开发分支和提交规则

本次功能在独立分支开发：

```text
feature/custom-interactive-plugin-management
```

分支虽然沿用最初命名，但架构上已经收敛为功能插件平台。后续继续开发时建议使用更准确的分支名，例如：

```text
feature/function-plugin-platform
```

开发约定：

- 不直接在 `main` 上开发。
- 未经明确要求，不推送任何远端分支。
- 如果需要推送，只推送当前功能分支到 `origin`。
- 不推送到 `upstream` 官方仓库。
- 工作区里已有的无关改动不回滚、不混入本次提交。

## 适用边界

声明式互动型适合：

- 讲段子、热梗解释、热梗接话。
- 猜谜等不要求服务端稳定保存隐藏答案、候选集或计分状态的轻量小游戏。
- 主要靠 LLM 理解规则和维持短对话的互动能力。

不适合：

- 需要严格计分、回合状态、排行榜、跨会话存档的游戏。
- 需要访问外部系统、执行文件、调用私有接口的插件。
- 需要服务端强校验规则的复杂玩法。

这些能力应新增专用执行器或 Python 插件，再作为同一个 `Function Plugin` 接入功能管理。
