# 状态机游戏会话逻辑

本文档说明 xiaozhi-server 中语音互动游戏的真实运行逻辑，包括“你说我猜”“我想你猜”和“成语接龙”的触发、状态保存、每轮判定和退出规则。

相关架构先读：

```text
docs/integrations/function-plugin-architecture.md
docs/integrations/prompt-injection-engineering.md
```

## 1. 适用范围

状态机游戏用于高频、多轮、必须稳定保存局内状态的语音玩法。当前内置状态机游戏包括：

| 玩法 | 用户表达示例 | 工具名 |
| --- | --- | --- |
| 你说我猜 | 玩你说我猜、我描述你来猜、猜个词 | `guess_what_i_say` |
| 我想你猜 | 你想一个词我来猜、你出题我猜、我来猜你想的词 | `xiaozhi_thinks_you_guess`、`guess_xiaozhi_word`、`i_think_you_guess` |
| 成语接龙 | 玩成语接龙、来一局成语接龙 | `idiom_chain` |

泛化入口“玩个游戏”“来个游戏”“小游戏”会默认路由到 `idiom_chain`，避免 LLM 自己临场编一个没有服务端状态的游戏开场。

这些玩法仍属于 Function Plugin 顶层模型，但局内规则不再依赖 LLM 自己记忆，而是由 Python 服务端维护。

## 2. 运行链路

一次游戏请求的主链路：

```text
用户语音
  -> ASR 转文字
  -> Intent 或 function_call 判断工具
  -> ServerPluginExecutor 识别状态机游戏工具
  -> GameSessionManager 执行当前玩法状态机
  -> Action.RESPONSE 直接返回主持话术
  -> TTS 播报
```

关键文件：

```text
main/xiaozhi-server/core/game_session.py
main/xiaozhi-server/core/connection.py
main/xiaozhi-server/core/handle/intentHandler.py
main/xiaozhi-server/core/providers/intent/intent_llm/intent_llm.py
main/xiaozhi-server/core/providers/tools/server_plugins/plugin_executor.py
main/manager-web/src/views/FunctionPluginManagement.vue
```

## 3. 状态保存边界

每个设备连接初始化一个 `GameSessionManager`：

```text
ConnectionHandler.game_session_manager = GameSessionManager()
```

状态只保存在当前连接内存中：

```text
sessions      当前连接中活跃游戏状态
last_words    最近使用过的词或开局成语，用于降低重复
```

边界规则：

- 游戏状态不写入数据库。
- 隐藏答案不进入 LLM prompt。
- 连接断开后游戏状态自然丢失。
- 同一连接内如果有活跃游戏，后续短句优先回到当前游戏。
- 当前活跃游戏拥有最高优先级。只要 `GameSessionManager` 里还有活跃状态，后续输入就会继续回到当前工具，而不会重新走普通意图判断。
- 用户说“换游戏”只会结束当前局；用户明确说“玩成语接龙”“你想一个词我来猜”“玩你说我猜”等新玩法时，服务端会先清理旧状态并切换到对应游戏工具。
- 游戏工具返回 `Action.RESPONSE`，直接播报，不再交给 LLM 二次改写。
- 状态机游戏不把工具调用和工具结果作为聊天气泡上报，只保留用户输入和最终播报文本，避免前端显示 `idiom_chain(...)` 或 `{"result": ...}`。

## 4. 工具注册规则

`ServerPluginExecutor.get_tools()` 会把三类状态机工具强制加入工具列表：

```text
GameSessionManager.GUESS_MY_WORD_TOOLS
GameSessionManager.USER_DESCRIBES_TOOLS
GameSessionManager.IDIOM_CHAIN_TOOLS
```

工具描述只告诉模型“什么时候调用工具”和“必须原样传入 user_message”。具体答案、候选集、已给提示、上一问、已用成语等状态都由服务端保存。

如果用户已经在游戏中，`intentHandler.handle_user_intent()` 会优先获取当前活跃工具：

```text
active_game_tool = conn.game_session_manager.get_active_tool()
```

当存在活跃游戏时，后续用户输入会被包装成当前游戏的 function call：

```json
{
  "function_call": {
    "name": "当前活跃游戏工具名",
    "arguments": {
      "user_message": "用户当前原话"
    }
  }
}
```

这保证了“不是”“再猜”“提示”“接不上”等短反馈不会被误判成普通聊天。

## 5. 你说我猜

工具名：

```text
guess_what_i_say
```

处理入口：

```text
GameSessionManager.handle_user_describes_guess()
```

### 开局

没有活跃状态或用户要求重新开始时创建状态：

```text
active          True
game_type       user_describes_assistant_guesses
target_type     当前题目类型
facts           已识别线索
asked           已问过的问题字段
excluded_names  已排除的候选人名
last_question   上一轮问题或猜测
turn_count      当前轮次
```

首轮只要求用户给线索：

```text
我们玩你说我猜。你描述特点、身份或年代，我来猜；先别直接说答案。
```

首轮禁止问用户答案、目标词或“你心里想的是什么”。

否定语义只在有上下文时生效：用户说“不是”“不对”“错了”时，会结合上一轮是猜测还是事实问题处理。猜测会加入 `excluded_names`，事实问题会把对应线索写成相反值。单独的短否定不会自动跳出当前游戏。

### 当前实现重点

当前服务端状态机对“历史人物”分支实现最完整。它通过关键词把用户线索转成结构化事实：

| 线索 | facts 字段 |
| --- | --- |
| 中国、国内、我国、中华 | `region = china` |
| 外国、国外、欧洲、美国、西方 | `region = foreign` |
| 近代、现代、近现代、新中国、民国 | `period = modern` |
| 古代、秦朝、汉朝、唐朝、宋朝、明朝、清朝、三国 | `period = ancient` |
| 皇帝、君主、帝王 | `ruler = True` |
| 不是皇帝、不是君主 | `ruler = False` |
| 战争、军事、将军、打仗、建国 | `military = True` |
| 诗人、文学家、作家 | `culture = True`、`poet = True`、`military = False`、`ruler = False` |
| 文学、思想、文化、教育 | `culture = True` |
| 开国、建立、创建 | `founder = True` |
| 改革、变法、转折 | `reformer = True` |

候选人来自 `_HISTORICAL_PEOPLE` 内置列表。服务端按 `facts` 过滤候选，并排除用户明确否定过的猜测。

### 追问和猜测

当候选不唯一时，服务端按固定问题顺序选择能区分候选的问题：

```text
region
period
ruler
military
culture
poet
founder
reformer
```

服务端只会问还没确认、还没问过，并且能把当前候选分成“是/否”两组的问题。

当候选只剩一个，或没有更好的区分问题时，服务端会猜：

```text
按这些线索排下来，我猜是{name}。对吗？
```

用户回答“对、是、没错、正确”等，游戏结束并清理状态。用户回答“不是、不对、错了”等，服务端把上一轮猜测加入 `excluded_names`，继续过滤候选。

### 非历史题限制

当前实现只完整支持历史人物分支。非历史人物线索只会先做粗粒度类别确认，不会真的展开通用物品、地点、动物候选推理。对于这类输入，当前主要回复：

```text
我先缩小范围：它是人物、物品、地点，还是动物？
```

后续如需支持物品、地点或动物，应新增对应候选库、事实抽取字段、问题选择策略和答案判定逻辑，不应只修改前端模板文案。

## 6. 我想你猜

工具名：

```text
xiaozhi_thinks_you_guess
guess_xiaozhi_word
i_think_you_guess
```

处理入口：

```text
GameSessionManager.handle_guess_my_word()
```

### 开局

服务端从 `_WORD_BANK` 随机选择目标词，并避免最近 3 个词重复。

当前词库：

| 词 | 类别 |
| --- | --- |
| 雨伞 | 日用品 |
| 冰箱 | 家用电器 |
| 地铁 | 交通工具 |
| 铅笔 | 学习用品 |
| 西瓜 | 水果 |
| 熊猫 | 动物 |
| 书包 | 日用品 |
| 月亮 | 自然事物 |

局内状态：

```text
active        True
game_type     guess_my_word
target_word   隐藏答案
category      答案类别
hints         提示列表
hints_given   已给出的提示
user_guesses  用户猜过的答案
turn_count    当前轮次
```

首轮回复：

```text
我想好了，是一个{category}。第一个提示：{first_hint}。你来猜。
```

首轮必须直接带出类别和第一条提示；后续每次只追加一条新提示，不会临时改成追问答案。

### 判定

每轮先把用户文本归一化，去掉空格和常见标点，再从以下前缀中抽取猜测：

```text
我猜是、我觉得是、是不是、是、答案是、应该是、可能是、难道是、猜
```

再去掉以下语气后缀：

```text
吗、吧、呢、对不对、是不是
```

判定规则：

```text
guess == target_word
或 target_word in guess
```

猜对后清理当前局状态；猜错时记录用户猜测，并给下一个提示。提示给完后只提示用户继续猜，不更换答案。

### 提示、放弃和退出

提示请求：

```text
提示、给提示、给个提示、再提示一下、不知道
```

放弃或揭晓：

```text
猜不到、想不到、想不出来、不会、放弃、揭晓答案、公布答案、告诉我答案、答案是什么
```

放弃时服务端揭晓 `target_word` 并结束本局。

## 7. 成语接龙

工具名：

```text
idiom_chain
```

处理入口：

```text
GameSessionManager.handle_idiom_chain()
```

### 开局

服务端从 `_IDIOM_CHAIN_STARTS` 随机开局，并避免最近 3 个开局重复：

```text
一心一意
水到渠成
心想事成
```

局内状态：

```text
active         True
game_type      idiom_chain
last_idiom     当前最后一个成语
expected_char  用户下一轮必须接的字
used           已使用成语集合
user_correct   用户接对数量
hints_given    已给提示数量
```

首轮回复：

```text
成语接龙开始。我先出：{idiom}。你接“{idiom[-1]}”字。
```

### 用户答案判定

服务端会从用户原话中提取前 4 个字作为成语答案。支持前缀：

```text
我接、接、我想是、我觉得是、应该是、答案是
```

判定顺序：

```text
是否为内置四字成语
  -> 是否以 expected_char 开头
  -> 是否接上
  -> 服务端给出下一个成语
```

当前只接受 `_IDIOM_SET` 中的成语，不做在线词典查询。当前代码按字面精确匹配首字，不实现同音字接龙。
服务端会把已接和已出过的成语放进 `used`；用户如果重复说出一个已经用过的成语，本轮不计分，并提示换一个当前字开头的成语。

### 服务端出题

用户接对后，服务端根据用户成语最后一个字，从 `_IDIOM_NEXT` 查找下一个未使用成语：

```text
expected_char -> [候选成语列表]
```

如果找到，服务端更新：

```text
used
last_idiom
expected_char
```

并回复：

```text
接得上。{next_idiom}。你接“{next_idiom[-1]}”字。
```

如果找不到可接成语，服务端认输一回并询问是否重新开局。

### 提示和结束

用户说“猜不到、想不到、不会、接不上、不知道”等时，服务端会查看 `expected_char` 对应的一个可接成语作为提示。没有提示时，重新换一个常见开局。

用户说“不玩了、结束、退出、换游戏”等时，服务端结束当前局，并返回用户接对数量。

## 8. 通用口令

结束当前游戏：

```text
不玩了、结束、退出、不猜了、换游戏、停
```

重新开局：

```text
再来、再来一局、重新开始、重新来、新的一局、换一个、换题、换个词
```

提示或不知道：

```text
提示、给提示、给个提示、再提示一下、不知道、没思路、没有思路
```

放弃或揭晓：

```text
猜不到、想不到、想不出来、不会、接不上、放弃、揭晓答案、公布答案、告诉我答案、答案是什么
```

## 9. 与后台模板的关系

管理后台的功能插件模板位于：

```text
main/manager-web/src/views/FunctionPluginManagement.vue
```

模板用于给管理员展示默认名称、示例、开场白和玩法说明。状态机游戏运行时以 `GameSessionManager` 的代码逻辑为准。

维护规则：

- 前端模板可以描述玩法意图，但不能承诺服务端尚未实现的判定能力。
- 如果扩展词库、人物库或成语库，应同步更新本文档。
- 如果改变触发短语、退出口令、判定规则或状态字段，应同步更新本文档、功能插件架构和本地定制记录。
- 不能把隐藏答案、候选集、上一问状态改回由 LLM 自己维护。

## 10. 扩展新游戏的规范

新增状态机游戏时必须保持 Function Plugin 顶层模型：

```text
新增 provider_code
  -> 加入 GameSessionManager 对应工具集合
  -> 在 ServerPluginExecutor 中生成工具描述
  -> 在 GameSessionManager 中实现状态机
  -> 如需后台预设，在 FunctionPluginManagement.vue 增加模板
  -> 更新 docs/integrations/stateful-game-session.md
```

状态机实现必须明确：

- 开局状态字段。
- 每轮输入归一化规则。
- 用户答案判定规则。
- 提示、放弃、结束、重新开局规则。
- 状态清理时机。
- 是否直接 `Action.RESPONSE` 返回。
- 是否需要避免重复出题。

不要只靠大段 prompt 实现需要稳定记忆的多轮游戏。
