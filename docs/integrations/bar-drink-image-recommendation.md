# 酒类推荐图片展示集成

本文说明酒吧机器人推荐酒类时，如何把酒水外观图片发送给硬件端显示。

## 运行链路

```text
用户询问喝什么
  -> LLM 调用 recommend_drink
  -> xiaozhi-server 选择推荐酒水并直接回复
  -> 插件异步解析图片 URL 或生成图片
  -> 插件调用设备端 MCP 图片展示工具
  -> 硬件端显示酒水外观
```

插件文件：

```text
main/xiaozhi-server/plugins_func/functions/recommend_drink.py
```

后台插件定义：

```text
SYSTEM_PLUGIN_RECOMMEND_DRINK
provider_code = recommend_drink
```

## 硬件端 MCP 工具约定

推荐优先提供这个工具名：

```text
self_screen_display_image
```

建议 inputSchema：

```json
{
  "type": "object",
  "properties": {
    "image_url": {
      "type": "string",
      "description": "可下载的图片 URL"
    },
    "title": {
      "type": "string",
      "description": "图片标题"
    },
    "description": {
      "type": "string",
      "description": "图片说明或酒水外观描述"
    },
    "alt": {
      "type": "string",
      "description": "无障碍或下载失败时的替代描述"
    }
  },
  "required": ["image_url"]
}
```

插件也会自动识别这些常见工具名：

```text
self_screen_display_image
self_screen_show_image
display_image
show_image
render_image
```

参数字段会按 schema 自动适配：`image_url/url/src/image` 接收图片地址，`title/name` 接收酒名，`description/caption/alt/text` 接收外观描述。

## 图片来源

插件支持三种方式，优先级从高到低：

1. 角色插件参数 `image_urls` 中配置的酒水图片 URL。
2. 角色插件参数 `default_image_url`。
3. `image_provider=openai` 时，首次推荐该酒水自动生成图片并缓存。

OpenAI 图片生成配置示例：

```yaml
plugins:
  recommend_drink:
    enable_image_display: true
    display_tool_name: self_screen_display_image
    image_provider: openai
    openai_api_key: "你的 OpenAI API Key，放在私有配置中"
    openai_base_url: https://api.openai.com/v1
    image_model: gpt-image-1
    image_size: 1024x1024
    image_cache_dir: ./data/vision
```

生成图片会保存到 `main/xiaozhi-server/data/vision`。该目录是运行数据，不提交到 Git。图片 URL 复用现有接口：

```text
/mcp/vision/images/{filename}
```

因此生产环境必须正确配置 `server.vision_explain`，确保硬件端能访问同一域名或局域网地址。

## 当前推荐来源

运行时只从以下来源推荐：

```text
1. recommend_drink.drink_menu 中配置的本店酒单
2. recommend_drink.dataset_ids 指向的本店酒单知识库
```

没有配置 `drink_menu` 且没有配置酒单专用 `dataset_ids` 时，插件默认复用 `search_from_local_knowledge.dataset_ids`。本项目约定该知识库是本店酒类知识库。

知识库片段必须明确包含 `酒名`、`名称`、`品名`、`酒款` 字段，或 `推荐候选方向`、`候选酒款` 这类候选列表，才会被当作候选酒款。`用户问题`、`适配场景`、`检索字段` 这类说明性片段不会被当成酒名。

如果要给酒类推荐使用单独数据集，可以配置 `recommend_drink.dataset_ids`；否则默认使用 `search_from_local_knowledge.dataset_ids`。
