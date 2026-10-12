---
status: canonical
owner: models
last_verified: 2026-10-12
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/model
  - main/manager-api/src/main/java/xiaozhi/modules/config
  - main/manager-api/src/main/java/xiaozhi/modules/hotword
  - main/manager-api/src/main/java/xiaozhi/modules/billing
  - main/manager-api/src/main/java/xiaozhi/common/llm
  - main/manager-web/src/views/ModelBillingManagement.vue
  - main/xiaozhi-server/core/metering
  - main/manager-web/src/views/AsrHotwordManagement.vue
  - main/manager-web/src/views/ModelConfig.vue
  - main/manager-web/src/components/role/RoleEditDialog.vue
  - main/manager-web/src/components/model/RealtimeModelNotice.vue
  - main/xiaozhi-server/core/utils/model_config.py
  - main/xiaozhi-server/core/providers/realtime
---

# 模型管理与选择契约

本文用于解释智控台 `模型配置` 页面背后的模型管理方式：页面上每一列从哪里来，新增/编辑时提交什么，数据库怎么存，最后如何下发给 `xiaozhi-server` 初始化 provider。

相关专题：

- [模型专业评测与配置智能助手架构](../architecture/model-benchmark-and-assistant.md)：批量评测、助手和草案治理。

专项文档只保留无法由模型管理契约替代的接入步骤或带日期的环境证据：

| 文档 | 独立保留原因 |
| --- | --- |
| [豆包 Seed TTS 2.0](./huoshan-streamTTS-voice-cloning.md) | 当前唯一有效 Seed TTS 凭据、音色和验证步骤 |
| [Fish Speech](./fish-speech-integration.md) / [IndexStreamTTS](./index-stream-integration.md) | 两套自部署 TTS 的外部服务安装与启动步骤不同 |
| [PowerMem](./powermem-integration.md) | 独立第三方 Memory provider 的安装、存储和故障排查 |
| [RAGFlow](./ragflow-integration.md) | 外部 RAGFlow 服务部署与智控台连接指南，本地知识库实现不覆盖它 |
| [外置声纹](./voiceprint-integration.md) | 独立服务协议、可信 agent 作用域、迁移和五条上限 |
| [FunASR 资源报告](./funasr-sensevoice-resource-benchmark.md) | 带环境和日期的容量/并发证据，不作为通用配置规则 |
| [语音延迟报告](./voice-pipeline-latency-analysis.md) | 真机和生产日志的阶段性延迟证据 |

模型目录和可运行配置不是同一事实。目录项描述厂商产品、协议和项目适配状态；配置实例保存当前环境的端点、
上游模型 ID、普通参数和密钥。厂商列表、价格和具体版本会变化，页面目录必须来自 `ModelCatalogServiceImpl` 与模板数据，
并带来源和核验时间，不能把一次调研的型号清单写死在文档或前端。

目录项至少维护 `model_type`、厂商/产品、模型系列、`upstream_model_id`、`adapter_code`、协议、接入方式、
`SUPPORTED | PARTIAL | NEEDS_ADAPTER | DEPRECATED`、推荐/避用场景、必填字段、密钥字段、真实测试契约、官方来源和
最后核验时间。保存和启用必须遵守：

- 同一厂商的 HTTP、普通 WebSocket、Realtime、OpenAI Compatible 和双向流式协议不能因为名称相近共用 adapter；
- LLM/VLLM 的 `model`、语音服务的 `resource_id` / `cluster`、讯飞等产品参数不能互换；
- 未适配项可以展示并输出接入任务，但不能保存为已启用；
- 设备实时 ASR 优先已适配流式协议，会议/文件转写不能冒充设备短句 ASR；
- TTS 推荐必须同时考虑首音频、稳定性、音色授权和设备格式；主 LLM 必须验证工具调用、延迟和安全边界；
- 新厂商版本先更新目录事实和模板，再通过真实测试决定是否推荐，不在本文维护易漂移型号清单。

## 会话模式契约

主模型继续使用智能体已有的一个 `llmModelId`，不新增“Realtime 模型 ID”字段。运行时根据所选 LLM 的
`typed_config.conversation_mode` 选择互斥管线：

```text
conversation_mode 缺省或 pipeline
  -> VAD -> ASR -> SemanticCenter -> Intent/LLM -> TTS

conversation_mode = realtime
  -> 设备音频 -> Realtime 会话 -> 实时音频
     图片与工具回注能力由具体 adapter 声明，不能假设所有 Realtime 模型都支持
```

Realtime 目录项必须使用独立 adapter，例如 `qwen_omni_realtime`、`doubao_realtime`。不能把普通阿里云百炼 OpenAI Compatible adapter、
普通 DashScope HTTP adapter 或名称中含 Qwen/Omni 的模型配置推断成实时会话。目录、保存和下发必须显式包含：

| 字段 | 契约 |
| --- | --- |
| `adapter_code` / `typed_config.type` | 对应已实现的 Realtime provider，不能复用普通 Chat Completions adapter |
| `conversation_mode` | 固定为 `realtime`；缺省保持传统拼接兼容 |
| `base_url` | 受支持的 `wss://` 官方 Realtime 端点；不得允许把 API Key 发往任意主机 |
| `model_name` | 厂商真实 Realtime 模型 ID |
| `api_key` | 密钥表保存、只在运行时下发、只进入厂商规定的鉴权 Header |
| 音频参数 | 输入 PCM16 16 kHz 单声道、输出 PCM16 24 kHz 单声道等 adapter 已验证格式 |
| 判停参数 | 字段名和范围按 adapter 协议限制，例如 Qwen `turn_detection_*`、豆包 `input_mode/end_smooth_window_ms` |
| 超时/事件大小 | 建连、响应和单事件字节上限，防止连接永久占用或超大事件耗尽内存 |

智控台模型列表、编辑/测试弹窗和智能体主模型下拉必须显示“实时多模态”标签，并明确：

- 选中后当前传统 VAD、ASR、TTS 和独立 VLLM 不参与实时主链路，但配置仍保留供切回普通 LLM。
- Realtime 失败不自动静默改走传统链路；管理员修复配置或手动切回普通 LLM。
- Realtime 主模型是会话型 provider。它可以在会话建立时接收已有静态摘要并继续上报对话事件，但不自动获得传统主链路的逐轮动态记忆查询，也不充当知识库切片、Judge、短期总结或主动记忆维护所需的同步文本 LLM；这些任务应选择独立且测试通过的传统 LLM/SLM，或使用托管/仅上报模式。

### 豆包 3.0 Seeduplex 全双工

模型配置 → LLM → 选择“豆包3.0 Seeduplex 全双工”，填写新版豆包语音控制台的 API Key 和已开通音色，保存后运行单模型测试。
测试通过并启用后，在智能体主模型中选择该配置。迁移 `202610081200.sql` 只新增独立模板和默认禁用的配置，
不会覆盖旧版豆包配置、复制密钥或自动启用模型。服务发布时须同时更新 manager-api 与 xiaozhi-server。

| 字段 | 值 |
| --- | --- |
| 模板 / adapter | `LLM_DOUBAO_DUPLEX` / `doubao_duplex` |
| 模型版本 | `1.2.6.1` |
| 会话模式 | `realtime` |
| 地址 | `wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue` |
| 鉴权 | 新控制台 API Key，通过 `X-Api-Key` Header 发送，无需 App ID |
| 音频 | 输入16kHz单声道PCM16，输出24kHz单声道PCM16，由服务端转换设备Opus |

该 adapter 使用 JSON 事件，与旧版 `doubao_realtime` 的二进制协议独立。工具只来自当前已授权的统一工具运行时；
多工具结果以各自 call_id 聚合回传。音频按20ms节奏发送，未收音时发送静音事件，恢复音频时取消静音；
自然打断保留新用户音频，显式取消后忽略已退役响应，关闭等待 session.closed。清空上下文会创建新的上游会话。

单模型测试发送仓库内置 `wakeup_words_short.wav` 语音，必须收到有效音频及回复结束事件才通过，
并记录首文本、首音频及总耗时。它不是自定义文字问题评测，不调用额外TTS把文字转换成音频，也不以打招呼合成替代推理。
当前文字对话、文字批量评测和图片输入明确不支持；动态记忆边界与其他Realtime模型相同。
设备端ASR/TTS路由不能与Realtime混用。全双工实机需具备持续收音、同时播放和回声消除能力，服务端适配不等于固件已完成验收。

协议依据：[火山引擎全双工API](https://docs.volcengine.com/docs/DoubaoVoice/endtoend-realtime-voice-full-duplex-version?lang=zh)，核验日期2026-10-08。

## 1. 页面字段对应关系

智控台 `模型配置` 页面左侧能力分类，对应接口参数 `modelType`：

```text
vad       -> VAD
asr       -> ASR
llm       -> LLM
slm       -> SLM
vllm      -> VLLM
intent    -> Intent
tts       -> TTS
memory    -> Memory
rag       -> RAG
```

标准操作流程：

```text
选择能力分类
  -> 选择模型目录项（厂商产品、协议、适配状态）
  -> 按模板字段填写配置、上游模型/资源 ID、端点和凭据
  -> 保存到标准字段、普通参数表和密钥表
  -> 执行单模型真实测试或批量专业评测
  -> 测试通过后才允许启用
```

模型目录项和可运行配置不是同一个概念。截图、CLI、官方网页或探针可以帮助识别厂商产品和协议，但只有目录项标记为 `SUPPORTED`、项目已有运行时 adapter，并通过真实测试后，才可以成为启用模型。

列表数据来自：

```text
GET /models/list?modelType=<activeTab>&modelName=<search>&page=<page>&limit=<limit>
```

页面列与后端字段关系：

| 页面列 | 后端 DTO 字段 | 主表字段 | 含义 |
| --- | --- | --- | --- |
| 配置名称第一行 | `modelName` | `ai_model_config.model_name` | 管理后台展示名，例如“讯飞语音识别(流式)” |
| 配置名称第二行 | `modelCode`，为空时用 `id` | `ai_model_config.model_code` | 本项目内部模型编码，例如 `XunfeiStreamASR`、`DeepSeekLLM` |
| 上游模型 | `upstreamModelId` | `ai_model_config.upstream_model_id` | 发给厂商的真实模型、资源或服务 ID，例如 `glm-4-flash`、`volc.seedasr.sauc.duration`、`qwen2.5` |
| 厂商/产品第一行 | `vendorName` + `productName` | `vendor_name` + `product_name` | 厂商和产品线，例如“火山引擎 / 豆包语音识别 Seed ASR 2.0” |
| 厂商/产品标签 | `supportedStatus` | 目录层派生 | 适配状态，例如 `SUPPORTED`、`PARTIAL`、`NEEDS_ADAPTER` |
| 厂商/产品第二行 | `accessModeLabel`、`protocolLabel`、`adapterLabel` | 模板/目录派生 | 接入方式、协议和 adapter 展示摘要 |
| 是否启用 | `isEnabled` | `ai_model_config.is_enabled` | 是否允许被选择或运行 |
| 测试状态 | `testStatus`、`testMessage`、`testTime` | 同名字段 | 单模型测试结果，`0=未测试`、`1=通过`、`2=失败` |
| 操作 | 当前行 `id` | `ai_model_config.id` | 测试、编辑、复制、删除等操作目标 |

注意两个容易混淆的字段：

```text
modelCode        本项目内部编码，用于标识这条配置是什么类别的实现
upstreamModelId  上游厂商真正需要的模型名、资源 ID、音色 ID、集群名或本地模型路径
```

例如：

```text
配置名称: 豆包语音识别2.0(流式)
modelCode: DoubaoStreamASRV2
adapterCode: doubao_stream
upstreamModelId: volc.seedasr.sauc.duration
vendorName/productName: 火山引擎 / 豆包语音识别 Seed ASR 2.0
```

## 2. 核心数据表

模型管理不是一张 JSON 表。当前标准存储拆成主表、普通参数表、密钥表、模板表和模板字段表。

### ai_model_config

模型配置主表，保存一条“可展示、可测试、可下发”的模型配置实例。

| 字段 | 含义 |
| --- | --- |
| `id` | 模型配置 ID，常见格式如 `LLM_DeepSeekLLM`、`ASR_XunfeiStream` |
| `model_type` | 能力分类，例如 `ASR`、`LLM`、`TTS`、`VLLM` |
| `model_code` | 项目内部模型编码，例如 `AliLLM`、`DoubaoTTS` |
| `model_name` | 后台展示名称 |
| `adapter_code` | Python 运行时 provider 类型，例如 `openai`、`doubao_stream`、`xunfei_stream` |
| `template_code` | 模型模板编码，用于决定表单字段和运行时字段映射 |
| `vendor_name` | 厂商名称 |
| `product_name` | 产品/API 名称 |
| `protocol` | 协议编码或协议说明，例如 `OpenAI Chat`、`WebSocket` |
| `upstream_model_id` | 上游真实模型、资源、服务 ID 或本地模型路径 |
| `endpoint_url` | API / WebSocket / 本地服务端点 |
| `is_default` | 是否默认配置 |
| `is_enabled` | 是否启用 |
| `test_status` | 测试状态，`0未测试 / 1通过 / 2失败` |
| `test_message` | 测试结果说明 |
| `test_time` | 测试时间 |
| `config_json` | 旧兼容字段。标准保存后应为 `null`，运行时不应继续依赖它 |
| `doc_link` | 官方文档链接 |
| `remark` | 备注 |
| `sort` | 排序 |

### ai_model_config_param

模型普通参数表，保存非密钥 provider 参数。

| 字段 | 含义 |
| --- | --- |
| `model_id` | 对应 `ai_model_config.id` |
| `param_key` | 表单字段名，例如 `temperature`、`voice`、`sample_rate`、`resource_id` |
| `param_value` | 参数值，统一字符串保存；JSON 参数序列化为字符串 |
| `value_type` | `string`、`number`、`boolean`、`json` |
| `param_group` | 参数分组，例如 `endpoint`、`generation`、`audio`、`provider`、`advanced` |

### ai_model_config_secret

模型密钥表，保存 API Key、Token、Secret 等敏感字段。

| 字段 | 含义 |
| --- | --- |
| `model_id` | 对应 `ai_model_config.id` |
| `secret_key` | 密钥字段名，例如 `api_key`、`access_token`、`api_secret` |
| `secret_value` | 密钥原文，只在服务端运行时使用 |
| `masked_value` | 脱敏回显值 |
| `value_type` | 固定为 `secret` |

前端编辑时只看到 `masked_value`。如果用户保存时没有改动脱敏占位值，后端会保留原 `secret_value`。

### ai_model_template

模型模板表，描述某类厂商产品如何渲染表单和如何映射运行时字段。

| 字段 | 含义 |
| --- | --- |
| `template_code` | 模板编码 |
| `model_type` | 能力分类 |
| `adapter_code` | 运行时适配器编码 |
| `vendor_name` | 厂商 |
| `product_name` | 产品/API |
| `capability` | 能力编码 |
| `access_mode` | 接入方式，例如 HTTP、WebSocket、本地模型 |
| `deployment` | 部署形态，例如云服务、本地、自部署 |
| `protocol` | 协议编码 |
| `upstream_model_field` | 上游模型字段名，例如 `model_name`、`model`、`resource_id` |
| `doc_link` | 官方文档 |
| `enabled` | 模板是否启用 |
| `supported_status` | 目录适配状态：`SUPPORTED`、`PARTIAL`、`NEEDS_ADAPTER`、`DEPRECATED` |
| `selectable` | 是否允许在编辑弹窗中选择并保存 |
| `hidden` | 是否从普通配置入口隐藏 |
| `deprecated` | 是否仅作为旧版兼容目录项 |
| `recommended_for` | 推荐使用场景 |
| `avoid_when` | 不适用场景 |
| `test_contract` | 单模型测试必须证明的内容 |
| `source_checked_at` | 厂商目录信息最后核验日期 |
| `setup_hints_json` | 配置提示列表 |
| `known_limitations_json` | 已知限制列表 |
| `aliases_json` | 旧配置名、厂商别名或模型别名 |

### ai_model_template_field

模板字段表，决定编辑弹窗显示哪些字段、字段是否密钥、保存后映射到 provider 的哪个 key。

| 字段 | 含义 |
| --- | --- |
| `template_id` | 对应 `template_code` |
| `field_key` | 表单字段键 |
| `field_label` | 页面显示标签 |
| `field_group` | 字段分组 |
| `value_type` | 值类型 |
| `input_type` | 输入控件类型，例如 text、password、select、switch、textarea |
| `runtime_key` | 下发给 provider 的字段名；为空时使用 `field_key` |
| `advanced` | 是否高级字段 |
| `visible_when` | 显示条件 |
| `required` | 是否必填 |
| `secret` | 是否密钥 |
| `default_value` | 默认值 |
| `options_json` | 下拉选项 |
| `help_text` | 字段说明 |

## 3. 新增/编辑保存协议

前端新增或编辑模型时，会把表单转换为 `ModelConfigBodyDTO`，请求：

```text
POST /models/{modelType}/{adapterCode}
PUT  /models/{modelType}/{adapterCode}/{id}
```

请求体核心结构：

```json
{
  "id": "LLM_DeepSeekLLM",
  "modelCode": "DeepSeekLLM",
  "modelName": "deepseek-v4-pro",
  "adapterCode": "openai",
  "providerCode": "openai",
  "templateCode": "LLM_OPENAI_COMPATIBLE",
  "vendorName": "DeepSeek",
  "productName": "DeepSeek API",
  "protocol": "OpenAI Chat",
  "upstreamModelId": "deepseek-v4-pro",
  "endpointUrl": "https://api.deepseek.com/v1",
  "params": {
    "temperature": 0.7,
    "max_tokens": 1024
  },
  "secrets": {
    "api_key": "sk-..."
  },
  "isDefault": 0,
  "isEnabled": 0,
  "docLink": "https://...",
  "remark": "",
  "sort": 10
}
```

保存规则：

1. `modelCode`、`modelName`、`adapterCode`、`templateCode`、`vendorName`、`productName`、`protocol`、`upstreamModelId`、`endpointUrl` 写入 `ai_model_config`。
2. `params` 写入 `ai_model_config_param`。
3. `secrets` 写入 `ai_model_config_secret`，并生成 `masked_value`。
4. 编辑保存会重置 `test_status=0`、`test_message=null`、`test_time=null`、`is_enabled=0`。
5. 标准保存后 `config_json=null`，避免继续维护旧 JSON 配置。
6. 保存后 `ConfigServiceImpl` 会刷新运行时配置缓存，使 `xiaozhi-server` 后续拉取到新配置。
7. 编辑已有配置时，数据库中明确存在的 `templateCode` 优先于厂商名、产品名或模型名推断；名称推断只兼容没有模板标识的旧配置，不能把 Realtime 模板改成普通 OpenAI Compatible 模板。
8. Realtime 配置保存前必须同时校验 `templateCode`、`adapterCode/type`、`conversation_mode`、请求路径 provider 和 WebSocket 端点；任一字段不一致都拒绝保存。

后端保存时还会兼容旧字段：

```text
endpointUrl      同时可映射为 base_url / api_url / url / ws_url / host / endpoint_url
upstreamModelId  同时可映射为 model_name / model / resource_id
adapterCode      同时写入运行时 type
```

真正下发给 provider 时，优先按模板字段的 `runtime_key` 重新映射。

## 4. 普通参数与密钥参数如何拆分

`ModelRuntimeAssembler.saveRuntimeConfig()` 会把标准运行时配置拆成参数表和密钥表。

以下 key 被视为元数据，不进入参数表：

```text
type
base_url / api_url / url / ws_url / server_url / host / endpoint_url
model_name / model / resource_id
protocol
```

以下 key 被视为密钥，进入 `ai_model_config_secret`：

```text
api_key
api_password
access_token
token
secret_key
api_secret
access_key_secret
personal_access_token
```

其他 key 进入 `ai_model_config_param`，并按值类型保存为：

```text
Number  -> value_type=number
Boolean -> value_type=boolean
Object/List/JSON -> value_type=json
其他 -> value_type=string
```

参数分组是展示和治理用途，不是运行时必须字段：

```text
endpoint / generation / audio / provider / advanced
```

## 5. 运行时下发协议

`manager-api` 给 `xiaozhi-server` 下发模型配置时，不直接下发表单结构，而是组装：

```yaml
selected_module:
  ASR: ASR_XunfeiStream
  LLM: LLM_DeepSeekLLM
  TTS: TTS_HuoshanDoubleStreamTTS

model_config:
  LLM_DeepSeekLLM:
    id: LLM_DeepSeekLLM
    model_type: LLM
    display_name: deepseek-v4-pro
    model_code: DeepSeekLLM
    adapter_code: openai
    template_code: LLM_OPENAI_COMPATIBLE
    vendor_name: DeepSeek
    product_name: DeepSeek API
    protocol: OpenAI Chat
    typed_config:
      type: openai
      base_url: https://api.deepseek.com/v1
      model_name: deepseek-v4-pro
      api_key: sk-...
      temperature: 0.7
      max_tokens: 1024
```

Realtime LLM 使用同一外层契约，仅 `typed_config` 表示不同会话能力：

```yaml
selected_module:
  LLM: LLM_QwenOmniRealtime
model_config:
  LLM_QwenOmniRealtime:
    model_type: LLM
    adapter_code: qwen_omni_realtime
    typed_config:
      type: qwen_omni_realtime
      conversation_mode: realtime
      base_url: wss://<WorkspaceId>.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime
      model_name: qwen3.5-omni-flash-realtime
      api_key: "<运行时密钥>"
```

规则：

- `selected_module` 只表示每类能力选中了哪个模型 ID。
- `model_config.<modelId>.typed_config` 是 Python provider 初始化的唯一配置来源。
- `typed_config.type` 来自 `adapter_code`，必须对应 `xiaozhi-server` provider 支持的类型。
- Qwen 3.5 Realtime 的 `base_url` 应使用百炼控制台业务空间对应地域的官方地址；服务端仅放行北京、新加坡业务空间域名及旧版官方 DashScope 域名，防止 API Key 外发。
- Qwen 3.5 Realtime 的普通用户输入以音频缓冲区提交；`conversation.item.create` 只用于 `function_call_output`。后台文本测试使用受限的本轮 instructions 加非空 PCM 手动提交，不能伪造普通 user 文本 item。
- Qwen 3.5 Realtime 图片仅接受 JPG/JPEG，Base64 后不得超过 256 KiB；发送图片前必须至少追加一次音频，并通过音频 commit 一起提交。
- 豆包端到端实时语音使用 `doubao_realtime` adapter，固定连接 `wss://openspeech.bytedance.com/api/v3/realtime/dialogue`；服务端拒绝其他主机、路径、查询参数、凭据 URL 和非标准端口。
- 豆包鉴权使用 `X-Api-App-ID` 与 `X-Api-Access-Key` Header；`app_id` 保存为普通参数，`api_key` 只进入密钥表。输入固定为 16 kHz PCM16 单声道，输出固定为 24 kHz `pcm_s16le`。
- 豆包模型版本当前支持 O2.0 `1.2.1.1` 与 SC2.0 `2.2.0.0`。当前官方协议没有项目本地 Function Calling 结果回注契约，也不支持本项目图片输入，因此工具不向上游暴露，图片请求明确失败而不静默转 VLLM。
- 密钥原文只在运行时下发给服务端使用，不应回显给前端。
- `config.yaml` 仍可作为默认或兼容配置，但标准后台配置应走 `model_config`。

## 6. 启用与测试规则

页面上的 `是否启用` 不是纯手工开关。当前前端和后端都有约束：

- 新增/编辑保存后默认 `is_enabled=0`，测试状态重置为未测试。
- 单模型测试接口是 `POST /models/test/{id}`。
- 测试通过后后端会写入 `test_status=1`、`test_message`、`test_time`，并把 `is_enabled=1`。
- 测试失败或未测试的模型不能直接视为可用。
- 前端对未适配目录项、测试未通过项会限制选择、启用或性能测试。

测试口径：

- ASR 必须使用真实音频并返回非空识别文本。
- TTS 必须生成真实可播放音频。
- LLM/VLLM 必须真实调用 provider。
- 只做字段校验、鉴权握手、HTTP 200 或 WebSocket close 1000，不能等同测试通过。
- Realtime LLM 必须真实建立 Realtime WebSocket，会话配置成功后发起真实响应，记录连接耗时、首文本、首音频和总耗时；只有握手或普通 Chat Completions 成功不能启用。
- Realtime 测试必须验证取消/超时/错误可以结束当前会话且不泄漏连接；没有真实 API Key 时明确未通过，不能生成伪造结果。
- 单模型测试会在任何外部请求之前重复执行 Realtime 一致性守卫；例如 `conversation_mode=realtime + adapter=openai` 会直接失败，API Key 不会被发送到 HTTP Chat Completions 地址。
- 历史错误保存记录通过后续 Liquibase 补偿迁移修复模板、adapter、协议和普通参数，并重置为禁用/未测试。无法安全推断的 HTTP 端点会被清空，管理员需重新填写官方 `wss://` 地址；迁移保留密钥表中已有 `api_key` 原文，不在 SQL、日志或前端回显。

## 7. 配置名称、编码、领域和协议怎么理解

### 配置名称 modelName

给管理员看的名字。可以是业务可读名称，例如：

```text
智谱AI
deepseek-v4-pro
讯飞语音识别(流式)
豆包语音识别2.0(流式)
```

### 模型编码 modelCode

本项目内部模型实现编码。它不是上游模型名。常见用途是兼容旧数据、展示第二行、表达这条配置属于哪个项目内置模型类型。

示例：

```text
DeepSeekLLM
AliLLM
DoubaoStreamASRV2
FunASR
HuoshanDoubleStreamTTS
```

### 运行时适配器 adapterCode

Python provider 类型，决定 `xiaozhi-server` 用哪段 provider 代码初始化。

示例：

```text
openai
ollama
doubao_stream
xunfei_stream
fun_local
aliyun_stream
huoshan_double_stream
```

### 领域/能力 modelType

模型能力分类，决定它出现在左侧哪个页签，以及智能体可以把它选到哪个槽位。

示例：

```text
ASR     语音识别
TTS     语音合成
LLM     主对话大模型
SLM     小参数模型
VLLM    视觉大语言模型
Intent  意图识别
Memory  记忆
RAG     检索增强
VAD     语音活动检测
```

### 上游模型 upstreamModelId

真正发给厂商或本地服务的模型/资源标识。不同厂商含义不同：

```text
OpenAI 兼容 LLM: model_name，例如 glm-4-flash、deepseek-v4-pro
火山 ASR/TTS: resource_id，例如 volc.seedasr.sauc.duration、seed-tts-2.0
Ollama: 本地模型名，例如 qwen2.5
本地 ASR: 模型目录，例如 models/SenseVoiceSmall
讯飞: 可能是 domain、模型系列或由模板字段映射的产品参数
```

### 协议 protocol

说明接入协议，不等于 adapter。一个 adapter 可能支持多个产品口径，一个厂商也可能有多个互不兼容协议。

示例：

```text
OpenAI Chat
OpenAI Compatible
WebSocket
HTTP 非流式
HTTP SSE
DashScope Application
讯飞流式语音识别
```

## 8. 一个配置从页面到运行时的例子

以截图里的“豆包语音识别2.0(流式)”为例：

```text
页面分类: 语音识别
modelType: ASR
modelName: 豆包语音识别2.0(流式)
modelCode: DoubaoStreamASRV2
adapterCode: doubao_stream
vendorName: 火山引擎
productName: 豆包语音识别 Seed ASR 2.0
protocol: WebSocket
upstreamModelId: volc.seedasr.sauc.duration
```

保存后：

```text
ai_model_config:
  id / model_type / model_code / model_name / adapter_code / vendor_name / product_name / protocol / upstream_model_id

ai_model_config_param:
  appid、language、sample_rate、audio_format、output_dir 等普通字段

ai_model_config_secret:
  access_token 或 api_key 等密钥字段
```

运行时下发：

```yaml
model_config:
  ASR_DoubaoStreamASRV2:
    model_type: ASR
    adapter_code: doubao_stream
    typed_config:
      type: doubao_stream
      resource_id: volc.seedasr.sauc.duration
      appid: "..."
      access_token: "..."
```

`xiaozhi-server` 根据 `typed_config.type=doubao_stream` 找到对应 ASR provider，再用 `resource_id` 和密钥调用火山服务。

### 智能体级豆包 ASR 热词

资源中心的“热词库”支持在智控台录入词条、发布到火山并应用到智能体。词库按当前工作空间隔离，
词库绑定火山凭据来源，不绑定识别模型。服务端从已配置的豆包 ASR 运行参数中发现管理凭据，按凭据指纹去重；浏览器只接收不含密钥的来源标识和名称。
来源发现不依赖模型是否启用或测试通过，停用一个模型不应隐藏已有词库。应用时才检查目标智能体的 ASR 是否启用、测试通过、支持词表且使用同一来源；同一来源下的流式和录音识别模型可以复用词库。不同 API Key 不自动视作同一账号，不能凭厂商名称推定资源互通。

操作顺序为“保存草稿 → 发布 → 应用”。发布通过火山官方 `CreateBoostingTable` 注册独立内容版本，
读取 `GetBoostingTable` 核对词条后才标记已发布；不原地覆盖已被智能体使用的版本。
发布新版本后，已有智能体继续使用先前版本，用户必须再次选择智能体并应用；发布不代表设备已经识别过新词。
草稿和失败状态保留上次成功发布的 ID。服务端以稳定的内容版本名称记录远端创建意图，
超时重试先查询同名词表，避免盲目重复注册。同步中禁止并发编辑；中断超过五分钟后，刷新可重试原操作。

词条每行一个，可写 `词条|权重`，默认权重 4，范围 1–10；每库最多 5000 条，
单词按当前火山词表限制校验为不超过 30 个 UTF-8 字节（通常 10 个汉字）。重复词和控制字符会拒绝。
这些是词表管理限制，不代表模型能利用全部词条：官方说明双向流式热词容量为 100 tokens，
非流式／二遍最多 5000 个词，实际识别效果需使用目标模型和录音验证。

解除应用会移除所选智能体对该词库所有历史版本的引用。删除先检查所有版本的智能体候选及模板引用，
存在引用时拒绝；无引用后清理本项目发布的远端版本，再删除本地词库。从火山导入的原始版本保留在火山，不随本地删除清理。不能把整个火山账号下的词表列表作为工作空间资源列表。
旧角色和模板保存路径也校验已托管词表的工作空间归属和模型的来源兼容性；跨工作空间公开模板剥离热词引用。
历史版本保留到删除整个词库，因此内容更新会占用额外火山词表配额；当前不自动清理历史版本。
新词库记录凭据的 SHA-256 指纹而不复制密钥，也不保存模型绑定。原来源仍在其他模型配置中时可以继续管理；所有配置都移除或替换该凭据后，词库仍显示，但远端操作拒绝执行，避免跨账号误注册或遗失远端清理记录。历史 `model_id` 仅用于兼容未记录来源的旧草稿；已发布但缺失凭据指纹的异常记录不能自动认领当前凭据。

历史上在火山控制台创建的词表仍可通过角色配置填写 ID。平台管理员点击“从火山同步”后直接读取所有已配置来源，无须选择识别模型。相同凭据只查询一次，重复远端词库合并展示；部分来源失败时保留其他来源结果并显示可重试错误。普通用户不能枚举共享账号的远端词库；导入还需要当前工作空间写权限。权限与来源是否配置分别返回，不能用模型列表为空推定用户无权限。
导入保留原远端 ID、名称和完整词条，记录为已发布，并通过原 ID 显示已有智能体引用；不重新注册、不改智能体配置、不覆盖已导入词库的本地草稿。重复导入返回已有记录，远端 ID 唯一约束防止并发重复认领。如果该 ID 已由其他工作空间或不兼容来源管理、引用，拒绝认领；同工作空间内使用同一来源的不同识别模型不构成冲突。
词条优先读取火山返回的原始 `File`；接口只返回 `Preview` 时须核对完整词条数量。未返回权重的词条在本地按默认权重 4 编辑，导入本身不改变远端权重；后续发布新版本才使用本地权重。远端读取失败或词条不完整时不创建半成品本地词库。“刷新”读取本地记录，“从火山同步”用于发现和导入已有远端词库，不自动覆盖已导入内容。
智控台角色配置在选择
`doubao_stream` 或 `doubao_recording` 后可维护最多 10 个候选热词表 ID，并选择一个当前生效项。
候选列表保存到 `ai_agent.asr_hotword_ids`，当前生效项继续保存到兼容字段
`ai_agent.asr_hotword_id`。
`ConfigServiceImpl` 只在该智能体的 ASR `typed_config` 中增加：

```yaml
boosting_table_id: example-hotword-table-id
```

Python provider 按火山 Seed ASR 2.0 协议映射为
`request.corpus.boosting_table_id`。角色留空时不发送热词表；选择非豆包 ASR 时该字段不参与运行时配置。
火山官方限制一个识别请求只支持生效一张词表，因此候选列表不能作为数组下发；切换当前生效项并保存后，
manager-api 通过运行配置刷新事件在线更新会话，下一轮识别使用新词表，无需设备重连。
已有模型级 `context_text` 映射到 `request.corpus.context`，它可以承载序列化为 JSON 字符串的直传热词或对话上下文，但不能用热词表
ID 字符串替代。两者同时存在时遵循火山协议的优先级。

官方依据：[热词管理 API](https://www.volcengine.com/docs/6561/1742791)、
[热词与上下文](https://www.volcengine.com/docs/6561/2604976)。管理调用固定使用官方 HTTPS 代理端点及
`X-Api-Key` 请求头；不接受用户提供的管理 URL，不记录凭据、词条正文或原始厂商错误。

## 9. 修改模型管理时必须守住的边界

### TTS 音色与音量

管理端可选音色来自 `ai_tts_voice`，不是 Python provider 的硬编码能力清单。某个 TTS 模型只显示一个音色，通常表示数据库只登记了一条已验证且有授权的 voice；新增音色应通过管理界面或新的 Liquibase 迁移登记有效厂商 voice ID，不能修改已发布迁移，也不能把“厂商 API 支持”直接当作“项目已验证可用”。

通过管理界面手动新增或修改音色后，应先保存，再点击该行的“真实测试”。测试接口会复制当前 TTS 模型运行配置，以该行 `tts_voice` 作为 `private_voice` 覆盖值，调用 `xiaozhi-server /benchmark/models` 中的真实 Python provider 合成固定测试文本。只有厂商调用成功且返回浏览器可播放的音频 Data URL 才算通过；页面会显示试听播放器、首音频耗时、总耗时和厂商错误。该测试不修改模型整体的测试状态、启用状态或默认音色。

音色必须登记到协议与资源匹配的 TTS 模型下。例如火山 `mars` / `moon` BigTTS 音色应绑定使用 `volc.service_type.10029` 的 BigTTS 模型，不能因为接口地址相同就挂到 `seed-tts-2.0`。错误绑定可能完成 WebSocket 握手却收不到下行音频，最终表现为外部 TTS 超时或卡住。

`ttsVolume` 是厂商参数，不存在跨 provider 通用的数值范围。设备喇叭音量由 Device MCP 控制，浏览器试听音量由前端播放器控制，二者都不是 `ttsVolume`。页面和配置适配器必须展示或转换对应 provider 的范围，不能把同一个滑块值原样解释给所有厂商。

- 不把模型配置、系统参数、智能体选择、插件参数、MCP 工具和知识库数据混在一张表里。
- 不把真实密钥写入 `docs/`、迁移说明、日志或前端回显。
- 不让 `config_json` 重新成为标准运行时来源。
- 不把 `modelCode` 当成上游模型名，也不把 `upstreamModelId` 当成项目内部编码。
- 不把同厂商不同协议强行套到同一个 adapter。
- 不把 Realtime 会话型主模型当成所有后台同步文本任务的隐式 LLM。
- 不把未测试或只连通成功的 ASR/TTS/LLM 标记为可启用。
- 声纹、TTS Voice、Knowledge Dataset、Plugin、MCP 和 No-code HTTP Tool 是关联资源或系统能力，不因使用模型而写入模型配置主表。

## 10. 快速定位

代码入口：

```text
main/manager-web/src/views/ModelConfig.vue
main/manager-web/src/components/ModelEditDialog.vue
main/manager-web/src/apis/module/model.js
main/manager-api/src/main/java/xiaozhi/modules/model/controller/ModelController.java
main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelConfigServiceImpl.java
main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelRuntimeAssembler.java
main/manager-api/src/main/java/xiaozhi/modules/config/service/impl/ConfigServiceImpl.java
```

核心实体：

```text
ModelConfigEntity          -> ai_model_config
ModelConfigParamEntity     -> ai_model_config_param
ModelConfigSecretEntity    -> ai_model_config_secret
ModelTemplateEntity        -> ai_model_template
ModelTemplateFieldEntity   -> ai_model_template_field
```

关键迁移：

```text
main/manager-api/src/main/resources/db/changelog/202606221645.sql
main/manager-api/src/main/resources/db/changelog/202606221930.sql
```

验证命令：

```bash
git diff --check

cd main/manager-api
mvn test

cd main/manager-web
npm run test:unit
npm run build

cd main/xiaozhi-server
conda run -n xiaozhi-esp32-server python -m compileall .
```

## 11. 模型用量与实际消费管理

改版依据见 [2026-10-12 审计与规划](../reports/model-billing-audit-and-redesign-2026-10-12.md)。本节描述当前代码，报告中的历史抵扣诊断等后续规划不代表已经实现；代码合入与生产部署分别验收。

当前实现提供供应商账号账期总账，以及工作空间、智能体、设备三个维度的用量查询。费用来自供应商账单，不包含用户充值、收款或按配置单价估价。首次启用需执行已登记的 Liquibase 迁移、配置账单凭据；查看账号总账不需要建立工作空间绑定，只有向空间或设备归属费用时才需要证明独占范围。

### 页面与筛选

「计费管理」入口位于控制台导航，路由为 `/model-billing`，复用现有工作空间选择器和 `X-Workspace-Id` 请求上下文。智能体与设备卡片提供带筛选条件的入口，页面逻辑独立于 `ModelConfig.vue`。

| 视图 | 主要内容 | 下钻方式 |
| --- | --- | --- |
| 费用总览 | 平台管理员默认查看供应商账号的本账期官方费用，各供应商/币种分开展示；空间视图仅展示可核实归属 | 按账号、账期、产品查看主账单，再展开明细及核对状态 |
| 余额与资源包 | 最新资金和额度快照；同购买实例合并官方来源，音色权益独立展示 | 按产品/状态筛选，查看有效期、共享条件、来源与待核实事项 |
| 用量分析 | 当前空间的调用与分类型用量，按智能体、设备、调用记录分组 | 日期和高级筛选；按设备型号分类，查看设备在不同智能体下的历史 |
| 数据与归属管理 | 平台管理员的同步、导入、归属和采集状态入口 | 官方账期同步与额度同步分开，归属保留有效期和权限校验 |

三个主入口是费用总览、余额与资源包、用量分析，智能体/设备/调用明细移入用量页。平台管理员即 `superAdmin`，工作空间 OWNER/ADMIN 不因此获得账号总账权限；普通成员默认查看空间用量。页面明确区分账号与空间范围，范围内标题、卡片和列表保持一致；账号视图明确不受全局工作空间选择影响。高级筛选按所在视图展开，URL 保留有效筛选及旧钻取链接。

账号账单按官方账期查询，不套用调用日期或设备关键词；空间费用仍只接纳可核实的完整消费区间，跨区间账单不按天数分摊。用量日期为北京时间且包含起止两天。额度页独立展示当前快照，不套用历史日期。明细由后端查询、分页，不能在浏览器用当前设备列表重算历史归属。跨币种金额分开展示，金额经接口以十进制字符串传递。用量指标未知与计量覆盖不足必须明确标记，不能把空事件列表解释成完整的零使用。

用量不能跨单位相加：LLM 分输入、输出、缓存命中/未命中 Token（缓存通常是输入的子项，不能重复计入总数）；ASR 显示供应商返回的识别音频时长，计费口径需按产品确认；TTS 按产品显示字符、Token 或音频时长。未返回官方用量时，可单列实际发送字符数、音频时长等本地观测，但必须标明来源，不能冒充供应商计费用量。调用次数指实际发出的上游调用尝试，另行统计会话数与成功/失败/取消次数。

端到端实时语音模型单列 Realtime 服务类型，保留厂商文本/音频输入输出的计量类别，不虚构拆成 ASR、LLM、TTS 三笔费用。ASR 返回的识别音频时长是否等于计费时长需要按产品协议确认；TTS 文本会经过清理和替换，不能用聊天展示字数代替厂商计量。本地缓存命中、设备端语音未发起云端调用时，不新增供应商用量；录音识别结果轮询也不作为新的识别调用计数。

### 归属与历史规则

本项目设备可以切换智能体，详见[设备多场景管理](../architecture/device-scene-management.md)。所以工作空间、智能体、设备是每次调用的归属维度，设备不是某个智能体永远不变的子节点。

- 调用发出时固定工作空间、当时实际生效的智能体、数据库设备 ID、会话、轮次、模型及配置版本。设备由 A 切换到 B 后，A 的历史用量仍归 A；设备视图可以汇总这台设备在 A、B 下的使用情况。
- `conn.device_id` 当前是协议设备标识/MAC，不能直接写入数据库设备 ID 字段。管理端须签发经过资源校验的运行上下文，Python 接收并在调用开始时保存其快照；异步上报按该快照验证归属，不能按设备此刻的绑定反推。
- 改名、解绑、删除设备或智能体不抹除历史消费归属。记录稳定 ID 和必要名称快照，查询包括有历史用量的已停用资源。
- 无设备的智能体任务单列“后台任务”；共享知识库任务按知识库所属工作空间记录，不重复计入所有引用它的智能体。模型测试、评测、配置助手单列调用用途，平台级任务不能仅因操作人选中了某个工作空间就归给它。
- 工作空间总量包括设备调用、无设备的智能体任务和空间公共任务；按智能体汇总、按设备汇总是同一批事件的不同视图，不能把两个视图的合计再相加。

现有聊天上报会按 MAC 查询当前智能体，并受聊天记录开关、容量和删除操作影响，不能直接作为计费台账。计量记录应独立保存，仅记录归属、用量和调用状态，不依赖保留聊天正文或音频。

### 实际费用与用量的对应关系

火山 `ListBill` / `ListBillDetail` 已完成只读接口验证，能取得官方金额和用量明细；供应商费用有出账延迟。`PayableAmount`、`PaidAmount`、`UnpaidAmount` 展示为官方应付、官方已付及未付，结合结算类型、币种、抵扣及退款/调账理解。已付是所选账期账单截至快照的金额，不等于该月支付流水或净现金流。账单币种与结算币种关系未核实时，已付/未付不按账单币种强行归并。两个接口的费用不能重复相加，资源包购买金额与后续抵扣用量也不能重复计费。

账号总额以官方 `BILL` 主账单为依据，`DETAIL` 用于下钻和核对。条数、币种、应付金额分开核验；孤立明细、金额差异、缺失计数和历史快照覆盖不明明确标记。历史同步任务无账号标识时不猜测回填，已有真实账单仍可查询，重新成功同步后才更新该账号账期的覆盖状态。

账单请求明确包含零金额记录；若供应商声明的 `Total` 与实际返回行数不一致，或同步期间总数变化，会保留既有快照并报告失败，不能将该批数据标记为完整账单。

部分官方预付费账单提供消费开始时间，但不提供结束时间。这些记录保留原始金额与时间证据，结束时间存为未知，在管理员账号账期视图和待归属列表可查询；不补造结束时间，也不自动归属到工作空间、智能体或设备。CSV 导入仍要求完整有效的消费区间。此兼容通过新增补偿迁移实现，不改写已执行的计费迁移。

页面只在有证据归属到当前筛选范围时展示金额。供应商账单同步状态（已出账/待出账/同步失败）与内部归属状态（已关联/归属待确认/供应商未提供该粒度）分开记录；未知金额显示状态或 `—`，不能显示为零。

- 共享供应商账号的总账不能直接作为任一工作空间的消费。尚未归属空间的账单仅在平台管理范围展示，不能泄露给其他工作空间成员。
- 火山语音可按 Project 取得费用。一个 Project 若只对应一个工作空间，可以归属该空间；同一 Project 若混用多个智能体或设备，仅凭项目小时账单不能精确拆分到它们。单独按设备配置 Project 后，设备在同一计费期间切换智能体，也仍需处理智能体归属问题。
- 要显示智能体、设备级的实际金额，必须让供应商可出账的最小范围对应唯一调用归属，或取得能直接匹配调用的官方费用明细。新增计费绑定和凭据路由前，应验证供应商项目/Key 的出账粒度、资源隔离和配额；不能假设更换 Key 就能取得更细账单。
- 如果官方只支持较粗粒度，保留该层级的实际金额，智能体/设备层显示用量和“费用暂无法细分”。不得按字符数、Token 或时长比例分摊后称为实际扣费。历史混用记录没有充分证据时不自动补造精细费用。
- DeepSeek 官方余额查询已验证可用，但余额不等于消费。2026-10-12 核对公开官方 API 文档，仅确认 `GET /user/balance` 可通过模型 API Key 查询余额，尚未确认可用的公开消费账单接口。官方控制台支持按 Key 查看和导出用量；FAQ 说明导出压缩包包含两个 CSV，但未定义金额列、币种及结算口径，不能仅凭文件名 `amount` 判定其包含实际费用。仍需用官方导出样本验证金额字段、粒度与关联方式，再接入原生格式。

官方依据：[火山账单流水](https://www.volcengine.com/docs/6269/1127841)、[火山账单明细](https://www.volcengine.com/docs/6269/1127842)、[语音项目账单](https://www.volcengine.com/docs/6561/1339608)、[账单说明](https://www.volcengine.com/docs/6269/94010)、[DeepSeek 余额接口](https://api-docs.deepseek.com/api/get-user-balance)、[DeepSeek 按 Key 用量说明](https://static.deepseek.com/faq/index.html?lang=zh#/question/key-usage)。

### 实现分层与接口

1. **Python `core/metering/`**：不可变调用归属、真实请求事件、SQLite 持久队列和应用级异步投递。接入 OpenAI 兼容大模型、豆包流式/录音识别、火山双流合成及已接入的 Realtime 通道。每次重试有独立事件，厂商状态轮询不重复计算；失败和打断保留已取得用量，缺失字段不写零。采集不依赖聊天记录开关。原有 `connection.py` 只负责调用边界的上下文传递，队列、指标和上报职责留在新模块。
2. **Java `common/llm/`**：为标题、长期记忆、知识库分块、配置助手、模型测试和评测用例生成的真实 HTTP 调用记录用量。既有业务类只安装归属上下文、委托计量器，不内嵌账单逻辑；`LocalKnowledgeAdapter` 中的改动仅接入真实分块调用。模型测试与评测的 Python 请求签发平台用途上下文，即使使用真实设备测试，也不复用该设备的日常计费归属。
3. **Java `modules/billing/`**：上下文签发/校验、幂等入账、聚合查询、账单读取、CSV 导入、有效期绑定与补传。火山使用固定官方 HTTPS 地址和独立账单 AK/SK，金额不由调用用量推算。DeepSeek 当前提供官方金额模板导入；未接入未经核实的消费 API，余额接口不参与费用计算。
4. **Web 页面与 `components/billing/`**：页面壳维护三个入口与范围，账号总览、账单、空间用量和权益各由领域组件展示；平台管理员通过「数据与归属管理」同步、导入和绑定。普通成员不可读取平台账号金额或未归属数量。页面对用量缺失、各队列积压和读取失败分别显示状态。

表结构位于 `main/manager-api/src/main/resources/db/changelog/model-billing-management.sql`，已登记到主 changelog。调用事件和用量指标分别存储，账单独立保留供应商粒度、币种、消费区间和证据哈希，不强制绑定到一次调用。稳定 ID、名称快照和归属证据不随业务资源删除而级联删除。上下文凭据在数据库只保存哈希。

查询接口为 `GET /billing/overview`、`/billing/groups?groupBy=agent|device`、`/billing/events`、`/billing/bills`、`/billing/options`。管理员使用 `/billing/sync`、`/billing/bindings`、`/billing/import-template`、`/billing/import`。沿用[工作空间权限边界](../architecture/workspace-organization-management.md)，服务端校验当前空间；`agentId/deviceId` 只作为筛选，不能替代授权。

账号读取接口为 `GET /billing/accounts`、`/billing/account-overview`、`/billing/account-bills`、`/billing/sync-status`，全部强制平台管理员权限。账号账单参数明确为 `provider/accountRef/billPeriod`；默认查询主账单，`level=DETAIL&billId=...` 查看下级明细。`model-billing-read-model.sql` 以新迁移增加同步任务账号标识、索引和采集健康表。

账号列表、总览和同步状态分别返回 `capabilities`，区分自动账单同步、余额查询及项目模板导入的支持与配置状态。DeepSeek 未取得金额凭证时为 `STATEMENT_REQUIRED`，不展示“等待同步”；取得模板导入记录后为 `IMPORTED_STATEMENT`，显示 `lastImportedAt`，不把导入时间冒充自动同步成功时间，也不据此宣称完整覆盖账期。`POST /billing/sync` 显式指定账号时必须同时提供匹配已配置火山账号的 `provider/accountRef`；DeepSeek 请求直接拒绝，不会误触发火山同步。历史仅传账期的请求仍同步已配置火山账号。

Python 上报 `POST /config/billing/usage`，同时要求服务间共享密钥和管理端签发的调用归属凭据。每批响应中的 `accepted` 包含成功保存或已存在且内容一致的事件 ID，`rejected` 包含永久拒绝代码；网络和存储故障保留待发记录，永久拒绝进入本地隔离记录，不能阻塞后续上报。同 ID 不同内容不会覆盖已入账事件。

独立的 `POST /config/billing/health` 每 30 秒使用现有服务间鉴权报告脱敏实例状态，不接收客户端自报的空间归属；实例 ID 持久在 SQLite 元数据，进程计数以启动时间为界。`GET /billing/collection-health?accountView=true` 的实例明细仅平台管理员可见；成员仅获得已授权空间的数据库上下文/事件证据。Python 心跳、上下文、事件入库和 Java 队列分别展示，心跳正常不等于端到端计量通过；缺上下文、积压、拒绝、持久化失败和未知队列单独表达，首次事件不证明此前覆盖完整。不报告能力凭据、聊天正文、设备标识或私有配置。

### 首次启用与运行维护

#### 预付费、资源包和智能提醒

「余额与资源包」读取三种官方数据源，并保留各自最近成功同步时间；同步某一源失败不会覆盖其上次成功快照。

| 数据源 | 实现接口 | 页面内容 |
| --- | --- | --- |
| 火山通用资源包 | `ListResourcePackages`，`2022-01-01`，`billing` | 总额度、可用额度、单位、状态、有效期、周期重置信息 |
| 火山语音资源包 | `ResourcePacksStatus`，`2025-05-20`，`speech_saas_prod` | 包实例、适用产品、购买量、供应商当前用量、单位、到期时间、是否可共享 |
| DeepSeek 账户余额 | 官方 `GET /user/balance`，复用所选已保存模型的密钥 | 总余额、充值余额、尚未过期的赠送余额、账户可用状态 |

语音接口不单独返回已确认的剩余额度，页面不把“购买量 − 当前用量”冒充独立官方余额。优先读取 `Harvest` 的结构化购买量、当前用量及单位；顶层字段可能含千分位或单位。`ResourceID` 和 `Code` 不是唯一购买包实例，只有供应商返回的 `InstanceNumber` 可用于实例归属；实例号为空的赠送包仍可展示，展示行标识不能用于归属绑定。语音配置必须明确指定 `speech-project` 及逗号分隔的 `speech-resource-ids`，不能从推理 API Key 推导，也不能假定推理资源 ID 与控制面服务 ID 通用。查询明确传入 `Types=[access,quota,prepaid]`，覆盖范围仅为配置项目和服务。通用包与语音包可能是不同接口下的同一权益，页面不跨来源累加额度。

当前提醒为：可识别日期的 7 天内到期、已到期、官方剩余额度不超过总额度的 10%、已耗尽，以及同步失败。语音没有独立余额时仍提示临期，同时说明余额口径未知；未知日期保留原文。周期重置的包不将当前额度差值称为累计消耗。提醒依据最近同步快照，不自动充值、续费、购买或切换模型。

账户资金仅在平台管理员的账号视图显示；工作空间资源包需要明确账号或包实例的有效期绑定，项目筛选本身不能证明共享包独占。2026-10-12 只读联调确认：通用资源包查询的 `MaxResults` 为字符串、上限 20，超限返回 `InvalidParam`；按 `NextToken` 翻页，空列表表示完成（末页可能仍返回 `"0"` 游标），非空页的重复游标仍拒绝；语音专用查询返回的权限动作为 `speech_saas_prod:ResourcePacksStatus`，资源为 `trn:iam::<主账号ID>:project/<项目名>`。此处主账号和项目须使用实际错误或控制台确认的值，不能套用他人账号。已有 `BillingCenterReadOnlyAccess` 不代表拥有该语音查询权限。参数错误、签名错误与权限拒绝分开处理，失败不能显示零额度。通用接口对具体 Ark/语音产品的覆盖仍以账号实际返回与产品控制台为准。

官方账单的计费方式、结算类型与 `DeductionCount` / `DeductionUseDuration` 单独展示；抵扣量与现金金额不相加。要比较资源包是否划算，还需核对同产品、同单位下的实际购买金额、已使用额度和到期浪费，当前不根据标价自动生成节省金额。

实现依据：[官方 Billing SDK](https://github.com/volcengine/volcengine-python-sdk/tree/master/volcenginesdkbilling)、[官方语音 SDK](https://github.com/volcengine/volcengine-python-sdk/tree/master/volcenginesdkspeechsaasprod)、[DeepSeek 官方余额接口](https://api-docs.deepseek.com/api/get-user-balance)。额度快照表通过 `model-billing-benefits.sql` 登记迁移，接口为 `GET /billing/benefits` 和管理员专用 `POST /billing/benefits/sync`。

#### 配置与持久化

管理端私有 Spring 配置示例（环境变量由部署环境注入，不在仓库保存真实值）：

```yaml
xiaozhi:
  billing:
    volcengine:
      enabled: true
      access-key: ${XIAOZHI_BILLING_VOLCENGINE_ACCESS_KEY}
      secret-key: ${XIAOZHI_BILLING_VOLCENGINE_SECRET_KEY}
      account-ref: ${XIAOZHI_BILLING_VOLCENGINE_ACCOUNT_REF}
      region: cn-north-1
      speech-project: ${XIAOZHI_BILLING_VOLCENGINE_SPEECH_PROJECT:}
      speech-resource-ids: ${XIAOZHI_BILLING_VOLCENGINE_SPEECH_RESOURCE_IDS:}
    deepseek:
      model-id: ${XIAOZHI_BILLING_DEEPSEEK_MODEL_ID:}
      account-ref: ${XIAOZHI_BILLING_DEEPSEEK_ACCOUNT_REF:}
    outbox-root: uploadfile/billing-outbox
```

`account-ref` 是管理员选择的非敏感账号标识，用于与绑定和导入保持一致。仅查账单可用 `BillingCenterBillReadOnlyAccess`，同时查询通用资源包可用 `BillingCenterReadOnlyAccess`（整个费用中心的查询/导出权限，权限矩阵见[官方权限管理](https://www.volcengine.com/docs/6269/1186807)）。语音专用查询另外授权上述项目范围的单一只读动作，不授予购买、续费或配置修改权限。不使用模型 API Key 替代账单 AK/SK。未配置时页面仍能查看已采集用量及导入账单，同步按钮会显示未启用。

默认 `application.yml` 已提供上述环境变量占位符；生产 Compose 已透传到 manager-api，示例见 `deploy/production/env.example`。真实值仅写部署目录私有 `.env`。`XIAOZHI_BILLING_VOLCENGINE_ENABLED` 默认为 `false`，需要读取火山时显式启用。DeepSeek 的 `model-id` 是项目现有官方直连模型配置 ID，不是上游模型名，也不需要在部署文件重复保存推理密钥。

火山先同步所需账期，即可在账号视图查看官方费用；DeepSeek 则需导入可核实的官方金额凭证。若需要空间归属，再确认供应商 Project/资源的独占范围并建立有生效时间的绑定。账户共享时不得把整账户绑定到某一空间；只绑定 Project 仍混用多个智能体或设备时，留在工作空间级。新增绑定后需要重同步或重导入有关账单。已有账单引用的绑定不允许改写历史归属，可在不截断既有账单区间的前提下结束有效期。

DeepSeek 导入要求按下载模板整理**官方金额**、币种、消费期间、账单标识和官方凭证引用；只有 Token/调用次数的导出不能导入费用。此模板是本项目的统一导入格式，不声称与尚未核实的 DeepSeek 原生导出格式一致。

DeepSeek 费用卡提供余额、[官方用量控制台](https://platform.deepseek.com/usage)和定向导入入口。定向导入保留供应商、账号与所选账期，并直接展开表单；已导入金额仍按币种显示。工作空间用量是本项目已采集调用的范围，不能称为供应商账号的完整用量。补齐这些入口不代表已自动取得 DeepSeek 历史消费，金额凭证尚未提供时仍显示未知。

Python 队列默认位于 `main/xiaozhi-server/data/billing/usage.sqlite3`，可通过 `XIAOZHI_BILLING_OUTBOX_PATH` 覆盖。管理端队列默认位于 `uploadfile/billing-outbox`。两者需要持久磁盘、写权限和备份保护，不能作为缓存清理；Python 每个独立服务实例需使用独立队列路径。队列包含归属数据，Python 队列还包含上报所需能力凭据，禁止提交或直接输出到日志。

升级前的调用没有内部计量事件，不能从现有聊天历史补造。历史官方账单可同步并按有证据的有效期绑定归属；没有证据的记录保留未归属。尚未接入采集的其他 provider、进程/磁盘同时故障和供应商未返回用量都会影响内部覆盖度，官方账单仍是实际费用依据。

验证入口为 Java `mvn test`、Python 项目 Conda 下的 `compileall`/`pytest`、Web `npm run test:unit` 后执行 `npm run build`。计费专项测试覆盖上报幂等、可信归属、断线补传、未知值、账单处理和页面筛选。离线测试不代替生产迁移与真实供应商账单联调，测试默认不发起付费推理。
