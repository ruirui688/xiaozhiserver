# 自定义互动插件预留接口

## 概述

自定义互动插件用于在不新增 Python 插件文件的情况下，把热梗、成语接龙、你说我猜等轻量互动玩法挂到智能体的功能管理里。

这类插件由管理后台维护插件定义，由服务端在运行时动态暴露给支持 function call 的大模型。插件触发后不会执行用户上传代码，只会把后台配置的玩法说明交给大模型继续生成自然回复。

## 适用场景

- 热梗解释、热梗接话、酒吧互动话题。
- 成语接龙、你说我猜、猜谜、真心话等轻量小游戏。
- 规则主要靠大模型理解和对话维持，不需要严格服务端状态机的互动能力。

如果某个游戏需要计分、回合状态、排行榜、跨会话存档，应后续新增专用 Python 插件或独立服务，不建议只靠本接口承载。

## 后台配置方式

1. 登录智控台。
2. 进入模型供应器或插件供应器管理页面。
3. 新增一条供应器记录：

```text
modelType: Plugin
providerCode: idiom_chain
name: 成语接龙
sort: 90
```

`providerCode` 是函数调用名称，必须唯一，建议使用小写英文、数字和下划线，例如：

```text
hot_meme
idiom_chain
guess_what_i_say
```

## 字段配置约定

在 `fields` 中至少配置以下字段：

```json
[
  {
    "key": "custom_plugin",
    "type": "boolean",
    "label": "自定义互动插件",
    "default": true
  },
  {
    "key": "description",
    "type": "string",
    "label": "工具描述",
    "default": "当用户想玩成语接龙时调用。"
  },
  {
    "key": "instructions",
    "type": "string",
    "label": "玩法说明",
    "default": "你是成语接龙主持人；先给出一个成语；用户回答后检查最后一个字是否接上；如果用户接不上就给提示。"
  },
  {
    "key": "opening_message",
    "type": "string",
    "label": "开场白",
    "default": "来玩成语接龙，我先出题。"
  }
]
```

可选字段：

```json
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
      }
    },
    "required": ["user_message"]
  }
}
```

如果不配置 `parameters_schema`，服务端会使用默认参数结构。

## 启用方式

1. 进入角色配置。
2. 点击编辑功能。
3. 在未选功能中勾选新增的自定义插件。
4. 在右侧参数配置中确认 `custom_plugin=true`，并填写玩法说明。
5. 保存功能配置，再保存智能体配置。
6. 设备重新连接或重启后生效。

## 运行机制

设备连接后，管理后台会把智能体已选插件下发给 `xiaozhi-server`。

当插件参数中包含：

```json
{
  "custom_plugin": true
}
```

服务端会把该插件的 `providerCode` 动态注册为一个 function call 工具。大模型调用该工具时，服务端不会执行外部脚本，而是返回包含以下内容的工具结果：

- 插件名称。
- 玩法说明。
- 开场白。
- 大模型传入的工具参数。

随后大模型根据这段工具结果继续生成回复。

## 注意事项

- 本接口不涉及数据库结构变更，复用 `ai_model_provider` 和 `ai_agent_plugin_mapping`。
- 不要在字段默认值中写入真实密钥、Token、服务器密码或生产内网敏感地址。
- 自定义插件名称不要和内置 Python 插件函数同名，例如 `get_weather`、`play_music`。
- 生产部署前需要确认目标环境已经包含本次服务端代码变更。
- 如果使用管理后台新增插件定义，需要确保数据库已正常备份。
