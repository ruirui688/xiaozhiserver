---
status: canonical
owner: integrations
last_verified: 2026-07-23
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/agent/feishu
  - main/xiaozhi-server/plugins_func/functions
  - main/xiaozhi-server/core/providers/tts/paddle_speech.py
---

# 第三方服务集成手册

本文统一说明阿里云短信、飞书、Home Assistant、NewsNow、和风天气和 PaddleSpeech 的接入。优先在智控台按智能体配置；只有单模块部署才写 `main/xiaozhi-server/data/.config.yaml`。

## 共同安全边界

- API Key、App Secret、Access Token 和共享密钥只写私有配置或受控环境变量，不提交、不截图、不回显、不打印日志。
- 用户提供的服务 URL 必须限制协议和主机，经过项目既有 SSRF 校验；公网服务使用 HTTPS/WSS。
- 智能体动作遵循最小权限，保存前明确选择目标资源；读取到的消息、新闻和设备名称都是不可信外部内容，不能作为系统指令执行。
- 真实第三方调用不属于默认测试。任何曾暴露的凭据都应立即在供应商后台轮换。

## 阿里云短信与手机注册

1. 登录[阿里云短信控制台](https://dysms.console.aliyun.com/overview)，创建并提交短信签名，将审核后的签名写入 `aliyun.sms.sign_name`。
2. 创建验证码模板，将模板 code 写入 `aliyun.sms.sms_code_template_code`。签名和模板完成运营商报备前发送可能失败。
3. 在[访问控制 RAM](https://ram.console.aliyun.com/overview?activeTab=overview) 创建只允许短信发送的专用身份，不要使用主账号 AccessKey。将凭据写入 `aliyun.sms.access_key_id` 和 `aliyun.sms.access_key_secret`。
4. 确认短信发送成功后，按需设置 `server.allow_user_register=true` 和 `server.enable_mobile_register=true`。

控制台参考图：

![创建短信签名](../images/alisms/sms-01.png)
![配置短信签名](../images/alisms/sms-02.png)
![创建短信模板](../images/alisms/sms-11.png)
![RAM 权限配置](../images/alisms/sms-25.png)
![开启手机注册](../images/alisms/sms-32.png)

注册端点必须保留验证码、频率限制和防暴力破解措施。不要因为启用手机注册而扩大其他匿名 API。

## 飞书智能体

飞书绑定属于智能体，不属于登录用户。每个智能体独立配置应用、默认群聊、任务负责人、日历和动作权限，设备会话只能使用当前绑定智能体的配置。

工作空间角色模板会统一保存并切换飞书绑定。App Secret 和事件 Verification Token 不以明文进入模板：manager-api 使用部署级 AES-GCM 密钥将其从来源智能体作用域重新加密到模板作用域，应用时再重新加密到目标智能体作用域。模板列表、详情响应、前端和 Python 均不会收到这些凭据；因此生产环境必须稳定配置同一 `XIAOZHI_CREDENTIAL_ENCRYPTION_KEY`，密钥变更前应先完成凭据迁移。

### 飞书侧准备

1. 在飞书开放平台创建企业自建应用，启用机器人能力并申请实际动作需要的最小权限。
2. 发布应用版本，把机器人加入目标群。
3. 在智能体配置页填写新的 App ID 和 App Secret，测试连接，明确选择默认群聊，再保存并发送测试消息。
4. 需要群消息主动播报时，在“群聊与播报”保存 Verification Token，再将页面给出的回调地址填入飞书，订阅 `im.message.receive_v1`。当前实现使用明文事件体和 Verification Token 校验，不启用 Encrypt Key。

使用的飞书 API 主要包括群列表、发消息、读消息、任务和日历接口。具体权限名称以对应 API 的飞书调试台为准。

### 系统门控

```text
manager-web
  -> /agent/{agentId}/feishu（登录 + 智能体所有权 + workspace 写权限）
  -> manager-api AES-GCM 加密凭据
  -> ai_agent_feishu_config

xiaozhi-server
  -> /config/feishu/{agentId}/actions（ServerSecretFilter）
  -> 再校验设备仍绑定该 agentId
  -> 仅访问固定的 open.feishu.cn API
```

`feishu_assistant` 只有在集成启用且至少开放一个动作时才加入 function list。连接测试可以在总开关关闭时运行，但设备动作、会议纪要和工具注册仍受总开关与每项动作权限约束。会议纪要异步投递失败不应打断设备播报。

群消息主动播报还要校验每智能体 Verification Token、App ID、目标 `chat_id`、事件类型和 `event_id` 去重，并限流。机器人自己的消息、其他群、非文本消息以及设备说话、用户说话、会议或声纹采集期间的消息都不插播。

App Secret 和 Verification Token 不回显、不下发 Python 或设备。生产使用 `~/xiaozhi-data/.env` 中至少 32 字符的随机加密密钥：

```dotenv
XIAOZHI_CREDENTIAL_ENCRYPTION_KEY=生成的至少32字符随机值
```

安装脚本会生成并在升级时保留。不能直接更换；轮换前必须实现旧密钥到新密钥的凭据重加密。

## Home Assistant

先在 Home Assistant 确认要控制的实体，并在个人安全页创建最小权限长期访问令牌。令牌只显示一次，禁止用二维码截图长期保存。推荐以下三种模式中的第一种。

### 模式一：服务端插件

在智控台“智能体管理 → 编辑功能”启用 `HomeAssistant设备状态查询` 和/或 `HomeAssistant设备状态修改`，配置可信 HA 地址、令牌和设备清单。清单格式为“位置,名称,实体标识符;”，例如：

```text
公司,台灯,switch.example_light;
```

新增实体后重新加载配置。设备端上报的 IoT 描述符不能绕过服务端插件白名单成为 LLM 工具。

### 模式二：Home Assistant 作为 LLM

在 HA 开发者工具用 `conversation.process` 找到 conversation agent ID，在私有配置中选择 Home Assistant LLM 并设置地址、令牌和 agent ID。该模式把意图处理交给 HA，不能同时使用服务端 function-call 插件，因此只在确实需要 HA 原生对话代理时使用。

### 模式三：Home Assistant MCP

在 HA 安装官方 [Model Context Protocol Server](https://www.home-assistant.io/integrations/mcp_server/)，先确认项目的 [MCP 操作与安全边界](../mcp/mcp-operation-guide.md)，然后在私有 `.mcp_server_settings.json` 配置：

```json
{
  "mcpServers": {
    "Home Assistant": {
      "command": "mcp-proxy",
      "args": ["https://可信HA地址/mcp_server/sse"],
      "env": {
        "API_ACCESS_TOKEN": "通过私有配置注入"
      }
    }
  }
}
```

只选择支持 function calling 的 LLM，并启用对应意图模式。`mcp-proxy` 及其依赖应预安装和审查；不要把带真实 token 的配置提交到 Git。

## 天气插件

在[和风天气控制台](https://console.qweather.com/)创建项目和 API Key，并复制该项目专属 API Host。智控台中启用“天气查询”，分别填写 Key 和 Host；单模块配置为：

```yaml
plugins:
  get_weather:
    api_key: "通过私有配置注入"
    api_host: "该凭据对应的专属API Host"
    default_location: "广州"
```

`Authentication failed` 通常表示 Key 错误或未授权；`Invalid Host` 表示 Host 与 Key 不属于同一项目。Open-Meteo 兜底只保证基础查询，不替代正确的和风配置。

## NewsNow 新闻插件

智控台在智能体“编辑功能”中配置 `newsnow新闻聚合`，新闻源用英文分号分隔的中文名称；单模块示例：

```yaml
plugins:
  get_news_from_newsnow:
    url: "https://newsnow.busiyi.world/api/s?id="
    news_sources: "澎湃新闻;百度热搜;财联社;微博;抖音"
```

名称必须与插件 `CHANNEL_MAP` 完全一致。常用来源还有知乎、36氪、IT之家、今日头条、哔哩哔哩、少数派和稀土掘金；未配置或无效时使用默认来源。第三方新闻 URL 属于 SSRF 边界，修改前必须验证协议、DNS 和最终连接地址。

## PaddleSpeech TTS

PaddleSpeech 适合本地离线中文 TTS；默认模型对中英文混合支持有限。PaddleSpeech 是独立外部服务，可使用它自己的 Conda 环境，不能与本项目 `xiaozhi-esp32-server` 环境混用。

按 [PaddleSpeech 官方安装说明](https://www.paddlepaddle.org.cn/install) 选择匹配 CPU/GPU 的版本，在其仓库安装并下载模型，将 `tts_online_application.yaml` 的 `protocol` 设为 `websocket`，启动流式 TTS 服务：

```bash
paddlespeech_server start \
  --config_file ./demos/streaming_tts_server/conf/tts_online_application.yaml
```

单模块在私有配置选择 provider：

```yaml
selected_module:
  TTS: PaddleSpeechTTS
TTS:
  PaddleSpeechTTS:
    type: paddle_speech
    protocol: websocket
    url: ws://127.0.0.1:8092/paddlespeech/tts/streaming
    spk_id: 0
    sample_rate: 24000
    speed: 1.0
    volume: 1.0
    save_path:
```

随后通过项目统一入口启动服务：

```bash
scripts/dev start server
scripts/dev logs server -f
```

跨主机访问 PaddleSpeech 时应使用受保护的内网或 TLS 代理，不能把未认证的推理端点直接暴露到公网。

## 验证

先完成静态和本地测试，再由授权人员使用已轮换凭据做单项真实联调：

```bash
cd main/manager-api && mvn test
cd ../xiaozhi-server
conda run -n xiaozhi-esp32-server pytest
cd ../manager-web && npm run build
```

联调时一次只启用一个集成，确认权限门控、失败降级、超时、脱敏日志和智能体隔离，再逐步开放动作。
