---
status: canonical
owner: models
last_verified: 2026-07-23
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/model
  - main/manager-web/src/views/ModelConfig.vue
  - main/xiaozhi-server/core/utils/model_config.py
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

规则：

- `selected_module` 只表示每类能力选中了哪个模型 ID。
- `model_config.<modelId>.typed_config` 是 Python provider 初始化的唯一配置来源。
- `typed_config.type` 来自 `adapter_code`，必须对应 `xiaozhi-server` provider 支持的类型。
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

## 9. 修改模型管理时必须守住的边界

### TTS 音色与音量

管理端可选音色来自 `ai_tts_voice`，不是 Python provider 的硬编码能力清单。某个 TTS 模型只显示一个音色，通常表示数据库只登记了一条已验证且有授权的 voice；新增音色应通过管理界面或新的 Liquibase 迁移登记有效厂商 voice ID，不能修改已发布迁移，也不能把“厂商 API 支持”直接当作“项目已验证可用”。

`ttsVolume` 是厂商参数，不存在跨 provider 通用的数值范围。设备喇叭音量由 Device MCP 控制，浏览器试听音量由前端播放器控制，二者都不是 `ttsVolume`。页面和配置适配器必须展示或转换对应 provider 的范围，不能把同一个滑块值原样解释给所有厂商。

- 不把模型配置、系统参数、智能体选择、插件参数、MCP 工具和知识库数据混在一张表里。
- 不把真实密钥写入 `docs/`、迁移说明、日志或前端回显。
- 不让 `config_json` 重新成为标准运行时来源。
- 不把 `modelCode` 当成上游模型名，也不把 `upstreamModelId` 当成项目内部编码。
- 不把同厂商不同协议强行套到同一个 adapter。
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
npm run build

cd main/xiaozhi-server
conda run -n xiaozhi-esp32-server python -m compileall .
```
