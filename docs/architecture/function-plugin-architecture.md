---
status: supporting
owner: tools
last_verified: 2026-08-05
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

托管长期记忆不是 Function Plugin。选择 `managed_memory` 后，运行时不会注册 `manage_memory` 工具，而是在设备会话结束时沿用官方 Memory provider 的 `save_memory(dialogue)` 触发方式：

```text
managed_memory.save_memory(dialogue)
  -> /config/memory/process-session
  -> 记忆 LLM/SLM 根据完整会话主动输出维护动作
  -> manager-api 校验人物、容量、真实 ID 并写入事实和审计
```

旧 Intent 配置即使残留 `manage_memory` 也必须跳过。主动记忆不依赖 function-call 能力，记忆提示词及执行链路不得加入固定人物、关键词、条件任务、场景黑白名单或字符串相似度规则。

2026-09-24 起取消对话中的 `set_session_voice` 工具：不注册、不向模型暴露 schema，旧 Intent/声明式插件配置不能重新启用，残留调用返回 NOTFOUND。传统与 Realtime 对话均不可通过该工具更换音色或调整会话语速、音调、合成音量。后台默认音色配置、音色测试及设备音量控制保留；不删除已有音色数据或修改历史迁移。底层会话参数能力仍供内部配置使用，不作为模型可调用工具。

`reset_conversation_context` 仍是连接级必要内置工具，不依赖角色插件勾选；只清除当前连接的短期 Dialogue、游戏和语义软状态，保留 system prompt，不删除后台聊天记录、长期记忆或智能体配置；会议、设备通话和声纹采集期间拒绝清空。

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

### 提示词活动预设（开发版，2026-09-23）

`core/game/prompt_profiles.py` 提供 `idiom_chain`（成语接龙）、`riddle`（猜谜语）、
`standup`（脱口秀）、`crosstalk`（相声）四份软规则。规则覆盖模式、难度、纠错、
提示/计分约定、文稿局部修改与角色排练，但不实现裁判、隐藏答案库或播放状态机。
这四种活动只记录当前提示词名称；不生成旧版猜词的 `<game_state>`，避免成语接龙
被错误注入“用户想答案、机器人提问”的角色。旧猜词插件保持兼容，不在本次改写。

使用现有功能插件目录和智能体启用关系：provider code 使用上述名称，
`custom_plugin=true`，在 `instructions` 中编辑对应规则；没有 `instructions` 时使用
代码预设，已有管理员规则不会被自动替换。Python 配置还必须在当前 Intent 的
`functions` 白名单中启用对应名称；仅在 `plugins` 中增加配置不会自动启用。
本次没有数据库迁移，不会自动新增生产目录条目或改写已有角色配置。

复用现有 `FreeformGameManager` 的别名选取和当前活动续接，不新增关键词判题或流程状态机。
每轮普通文本请求把一份临时 system 提示插在最后用户消息前，不永久写入聊天历史。
明确退出或撤销启用后清除活动；切换活动不携带旧谜底。别名识别仍是现有实现，
不是模型自主选择插件；否定提及、复合退出/切换话语及工具回注轮需要后续独立验证。
换普通话题由软规则要求模型正常回答，不承诺服务器已自动清除活动名称。

限制：纯提示词不保证严格计分、固定谜底、成语真实性或多音字裁判绝对正确。
本语音链路不生成停顿/重音或舞台标记；写稿可用自然段和角色名，朗读模式仅台词。
没有新增字幕/音频分流、SSML、双音色或断点续播，仍需真机验收，不能声称已实现音频演绎控制。

离线契约测试为 `tests/test_prompt_activities.py`；真实 A/B 入口为
`tools/evaluate_activity_prompts.py`，必须显式 `--live`，最多 16 次请求，不自动重试。
它使用合成历史、同模型同参数比较普通 system 与额外活动提示，不包含生产角色、
工具、ASR/TTS 和记忆，是提示词小样本而非线上质量或时延结论。原始结果只能输出到
私有运行目录，不提交凭据或生产对话。
支持通过 `--ssh` 和 `--device` 使用生产绑定模型：只把合成提示送入临时诊断进程，
凭据不离开服务器、不写生产文件、失败不自动重试。开发验证与16次真实 A/B 的结果、
全部原始回答见[活动提示词对比报告](../reports/activity-prompt-comparison-2026-09-23.md)。

### 后续扩展型

如果未来需要接入更复杂的插件，应继续挂在 `Function Plugin` 顶层模型下，再新增实现模式，例如：

- 外部 HTTP 工具型
- MCP 桥接型
- 服务端状态型

### 服务端状态型

仅适用于必须访问服务端状态、外部接口或设备能力的能力。纯语言互动游戏不应放在这一层。

会议纪要属于服务端状态型插件。启用后注册真实 `meeting_minutes` function tool，会议开始、正文记录和结束全部由主 LLM 根据当前发言主动选择 `start / record / end`，服务端不维护进入或退出话术词表，也不通过语义控制中枢预判会议意图。会议激活期间每条 ASR 都串行等待上一轮工具执行完成，只向主 LLM 暴露会议工具，并在 provider 支持时要求本轮必须调用工具；具体选择 `record` 还是 `end` 仍由模型理解上下文。只有真实工具调用成功才改变状态，普通文字不能模拟开始或结束。结束后由主 LLM 基于冻结的完整结构化转写同时生成设备口语总结和飞书书面纪要，不引入独立 SLM 裁决。未启用时既不注册工具，也不插入否定式会议说明；即使 Intent functions 有旧残留，执行器仍会再次按当前智能体插件集合拒绝暴露。

普通 function-call 对话不通过关键词、正则或服务端预设意图选择工具。provider 支持时发送
`tool_choice=required`，由模型根据完整语义自主选择 `direct_answer` 或一个真实工具；这里要求的是结构化选择，
不是强制某个真实工具。服务端同时保留 `parallel_tool_calls=false` 的单出口约束。普通问答通过
`direct_answer(response=...)` 返回，真实控制只有模型选择并成功执行对应工具后才能确认完成。会议激活后的静默控制轮
继续只暴露会议工具。兼容端点不支持单工具约束时显式配置 `supports_tool_choice: false`，退回自由
content/tool_calls 响应。

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

### 资源中心工具目录

资源中心是静态 Server Plugin、连接级内置工具和 `direct_answer` 路由工具的统一目录。每个目录项都必须包含可查看、
可编辑的 `description`，并注明工具来源与启用方式。`catalog_only=true` 表示该项只用于目录维护，不进入角色插件勾选；
`runtime_builtin=true` 表示连接建立后按运行时能力自动注册。飞书、知识库和旧 Intent 白名单工具虽然不是角色勾选型插件，
也必须以 `catalog_only` 目录项展示，不能因为启用入口不同而从资源中心消失。

保存提示词后的运行链路：

```text
资源中心编辑 ai_model_provider.fields[].description.default
  -> manager-api 校验非空且最多 1000 字
  -> 向在线智能体发布安全轮次热刷新
  -> 配置下发 tool_prompt_overrides
  -> Python 按工具名覆盖本轮 function description
  -> 动态能力说明继续追加
```

提示词目录与工具授权是两条独立控制面。编辑 description 不新增角色映射，不改变 Intent 白名单、飞书动作权限、知识库绑定、
设备能力声明、MCP 连接状态或 SSRF 校验，也不能修改工具参数 Schema。

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
- 页面使用可纵向滚动的管理布局，不能用固定视口加 `overflow: hidden` 裁掉插件；默认提供紧凑列表，并可切换卡片视图。
- 左侧集中提供名称/编码/用户说法搜索、插件类型和系统能力分类筛选；内容区提供后台顺序/名称排序和刷新入口。
- 系统能力的实现、参数 Schema 和启用边界不在页面内编辑或删除；所有目录项的模型工具提示词可查看，superAdmin 可编辑；天气、联网搜索等白名单能力还可维护用户级共享凭据并执行真实连接测试。
- 系统能力的新增和实现仍以代码、数据库迁移或专用集成流程为准。
- 互动玩法允许新建、编辑、复制和删除。
- 新建互动玩法时可选择讲段子、热梗、成语接龙、你说我猜等预设规则，也可以从空白玩法开始。
- 玩法规则应包含触发场景、玩法流程、判断标准、主持技巧、异常处理和安全边界，不能只写一句简单描述。

用户级共享配置与插件启用保持分层：资源中心只维护可复用凭据和连接状态，角色配置仍决定该插件是否进入当前智能体的 function list，以及角色级行为参数。共享凭据使用 manager-api 的部署级 AES-GCM 密钥加密，Web API 只返回已配置状态和非敏感字段。真实测试链路为：

```text
manager-web
  -> manager-api /user/plugin-configs/{providerCode}/test（登录用户权限）
  -> 解密或合并当前用户的白名单配置
  -> xiaozhi-server /plugins/test（内部 Bearer 服务密钥）
  -> 真实天气 / 搜索实现
```

测试请求不得把 API Key 放进 URL、日志或响应；天气测试必须验证和风城市接口，不能因 Open-Meteo 兜底可用而把错误的和风凭据标记为通过。保存共享凭据不能自动启用任何角色插件。

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
