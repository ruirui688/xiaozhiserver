# 标准模型配置目录

本文档定义标准模型配置的分类目录、存储结构和字段分层，用于收敛智控台模型配置、`xiaozhi-server` 运行配置、知识库 RAG、声纹和插件 RAG 的配置边界。

当前运行时下发以 `model_config` 为模型配置源。`selected_module` 只表达每类能力选中的模型 ID；Python 入口按 `model_config.<modelId>.typed_config` 初始化 provider。

## 1. 当前配置入口

当前模型相关配置分散在以下几层：

```text
ai_model_provider
  model_type      能力分类，例如 ASR、TTS、LLM、RAG、Plugin
  provider_code   供应器或插件编码，通常对应 adapter_code 或函数名
  fields          智控台表单字段定义

ai_model_config
  model_type      模型能力分类
  model_code      运行时模块名，例如 AliLLM、DoubaoTTS
  model_name      后台配置展示名
  adapter_code    运行时适配器编码，对应 Python provider type
  template_code   模板编码
  vendor_name     厂商名称
  product_name    产品/API名称
  protocol        协议编码
  upstream_model_id 上游真实模型、资源或服务 ID
  endpoint_url    API / WebSocket / 本地服务端点

ai_model_config_param
  model_id        模型配置 ID
  param_key       供应器普通字段
  param_value     字段值
  value_type      string / number / boolean / json
  param_group     provider / generation / audio / advanced

ai_model_config_secret
  model_id        模型配置 ID
  secret_key      密钥字段名
  secret_value    密钥原文，仅服务端运行时使用
  masked_value    前端回显脱敏值
  value_type      secret

ai_agent / ai_agent_template
  asr_model_id
  vad_model_id
  llm_model_id
  tts_model_id
  tts_voice_id
  mem_model_id
  intent_model_id

ai_rag_dataset
  rag_model_id    知识库使用的 RAG 模型配置
  parser_config   知识库级切片覆盖配置

ai_agent_plugin_mapping
  plugin_id       插件定义 ID，对应 ai_model_provider
  param_info      当前智能体对插件参数的覆盖

sys_params / xiaozhi-server 配置
  server.voice_print / voiceprint
  server.voiceprint_similarity_threshold
  selected_module  每类能力当前选择的模型 ID
  model_config     运行时 typed_config 契约
```

标准模型配置的目标不是把所有配置塞进一张 JSON，而是明确每一类配置的归属，避免模型 ID、展示名、上游模型名、协议字段、密钥和运行参数互相混用。

## 运行时契约

`manager-api` 向 `xiaozhi-server` 下发模型时必须提供：

```text
selected_module:
  ASR: ASR_xxx
  TTS: TTS_xxx
  LLM: LLM_xxx

model_config:
  ASR_xxx:
    id
    model_type
    display_name
    model_code
    adapter_code
    template_code
    vendor_name
    product_name
    protocol
    typed_config
```

规则：

- `selected_module` 不再承载配置内容，只作为选择关系。
- `typed_config` 是 Python provider 初始化的唯一配置来源。
- `typed_config` 由 `ModelRuntimeAssembler` 从 `ai_model_config` 通用字段、`ai_model_config_param` 和 `ai_model_config_secret` 组装。
- `model_config` 是 manager-api 到 xiaozhi-server 的唯一模型运行时契约。

## 2. 分类目录

### ASR

语音识别能力，输入音频，输出用户文本。

当前归属：

```text
model_type = ASR
selected_module.ASR
ai_agent.asr_model_id
config.yaml ASR.*
```

当前典型适配器：

```text
sherpa_onnx_local
doubao
doubao_stream
aliyun
aliyun_stream
aliyunbl_stream
tencent
baidu
openai
vosk
qwen3_asr_flash
xunfei_stream
```

标准归类原则：

- 非流式和流式 ASR 仍属于同一个 `ASR` 顶层能力，用字段标识 `streaming`、协议和断句能力，不新增 `StreamASR` 顶层分类。
- 本地离线模型字段归入本地模型层，例如 `model_dir`、`model_path`、`model_type`、`output_dir`。
- ASR 服务商自己的热词、多语种、ITN、VAD 断句等参数归入供应器扩展层。
- 火山引擎豆包 ASR 必须区分 HTTP 接口、豆包大模型流式 ASR 和 Seed ASR 2.0；Resource ID 是上游服务资源，不是本地配置名。
- 阿里云百炼 Paraformer 实时语音识别是 DashScope WebSocket 接入，官方文档固定服务端点为 `wss://dashscope.aliyuncs.com/api-ws/v1/inference`，鉴权通过 `Authorization: Bearer <api_key>` 请求头。
- 讯飞语音听写是讯飞开放平台 WebSocket API，控制台获取 AppID、APIKey、APISecret，不能和星火大模型或普通 OpenAI 兼容字段混用。

### TTS

语音合成能力，输入文本，输出音频。

当前归属：

```text
model_type = TTS
selected_module.TTS
ai_agent.tts_model_id
ai_agent.tts_voice_id
ai_tts_voice
config.yaml TTS.*
```

当前典型适配器：

```text
edge
doubao
huoshan_double_stream
siliconflow
cozecn
openai
fishspeech
gpt_sovits_v2
gpt_sovits_v3
minimax_httpstream
aliyun
aliyun_stream
alibl_stream
tencent
custom
paddle_speech
index_stream
xunfei_stream
```

标准归类原则：

- 非流式、HTTP 流式、WebSocket 双向流式都归入 `TTS`，通过 `streaming_mode`、`protocol`、`transport` 或供应器类型区分。
- 音色是 TTS 的子资源，优先放入 `ai_tts_voice`；供应器运行必须携带的默认音色应作为 `voice`、`speaker` 或 `voice_id` 普通参数保存到 `ai_model_config_param`。
- 语速、音量、音高、情感、混音、输出格式归入音频生成层；供应器原生复杂结构可保留在 `audio_params`、`additions`、`voice_setting` 等嵌套对象里。
- 火山引擎豆包 TTS 必须区分 HTTP 接口、豆包大模型 WebSocket 双向流式和 Seed TTS 2.0；Seed TTS 2.0 的 `resource_id` 不应和集群、音色或配置编码混用。
- 百炼 CosyVoice、阿里云 NLS TTS、讯飞超拟人合成分别属于不同平台/产品线，后台展示必须拆分“服务商/部署方式”和“产品/系列”。

### LLM

主对话大语言模型，负责普通聊天、工具调用规划、工具结果二次生成和回复组织。

当前归属：

```text
model_type = LLM
selected_module.LLM
ai_agent.llm_model_id
config.yaml LLM.*
```

当前典型适配器：

```text
openai
AliBL
ollama
dify
gemini
coze
fastgpt
xinference
```

标准归类原则：

- OpenAI 兼容供应器使用统一字段：`base_url`、`model_name`、`api_key`、`temperature`、`max_tokens`、`top_p`、`frequency_penalty`。
- Dify、Coze、FastGPT 这类应用型 LLM 仍归入 `LLM`，但字段应明确标注为应用接口，不强行套用纯模型字段。
- 是否支持原生 function call 是能力标记，不是新的模型分类；运行时可根据 provider 能力降级到 `intent_llm`。

### VLLM

视觉语言模型，负责图片理解和视觉解释。

当前归属：

```text
model_type = VLLM
selected_module.VLLM
config.yaml VLLM.*
server.vision_explain
```

当前典型适配器：

```text
openai 兼容视觉模型
qwen-vl
讯飞星火视觉模型
```

标准归类原则：

- VLLM 是多模态理解能力，不并入 LLM。
- `prompt`、`max_tokens`、`temperature`、`top_p` 属于视觉生成参数层。
- 下发给设备访问的 `server.vision_explain` 是服务地址参数，不是 VLLM 模型字段；模型配置只保存视觉上游接口和生成参数。

### SLM

小参数模型，面向低成本、低延迟的辅助推理。

当前归属：

```text
model_type = SLM
ai_model_provider SYSTEM_SLM_*
ai_model_config SLM_*
benchmark 中按 LLM/SLM 同类测试
```

当前典型适配器：

```text
openai
ollama
xinference
```

标准归类原则：

- SLM 不再混放到 `LLM` 下的 `XinferenceSmallLLM` 这类命名里；新增或迁移时优先使用 `model_type = SLM`。
- SLM 用于意图、摘要、切片、轻量判断等辅助任务时，由上层配置引用 SLM 模型 ID，而不是复制一份接口参数。
- 字段结构应尽量与 LLM 保持兼容，方便测试和替换。

### Memory

记忆能力，负责长期记忆、短期记忆、用户画像或关闭记忆。

当前归属：

```text
model_type = Memory
selected_module.Memory
ai_agent.mem_model_id
config.yaml Memory.*
```

当前典型适配器：

```text
nomem
mem_local_short
mem0ai
powermem
```

标准归类原则：

- `Memory` 是记忆系统，不是 LLM 或 RAG；它可以引用 LLM、SLM、Embedding、向量库，但不拥有这些模型的全量字段。
- `mem_local_short.llm`、`powermem.llm` 等字段应逐步迁移为模型引用 ID，减少重复维护 API Key。
- 向量库、画像开关、记忆总结策略属于 Memory 自身字段。

### Intent

意图识别能力，负责决定用户文本是否进入工具调用、继续聊天或特殊控制。

当前归属：

```text
model_type = Intent
selected_module.Intent
ai_agent.intent_model_id
config.yaml Intent.*
```

当前模式：

```text
nointent
intent_llm
function_call
```

标准归类原则：

- `Intent` 是路由策略，不是普通 LLM；它可以引用 LLM 或 SLM 作为思考模型。
- `function_call` 是主 LLM 原生工具调用模式，要求主 LLM 支持结构化工具调用；不支持时运行时可降级为 `intent_llm`。
- 工具列表不应长期保存在 Intent 模型配置里；插件、MCP 和设备工具应由各自系统注册，Intent 只选择路由模式和引用模型。

### RAG

知识库后端适配器，负责知识库创建、文档解析、召回测试和知识检索。

当前归属：

```text
model_type = RAG
ai_rag_dataset.rag_model_id
ai_rag_dataset.parser_config
config.yaml plugins.search_from_ragflow
config.yaml plugins.search_from_local_knowledge
```

当前适配器：

```text
ragflow
local_knowledge
```

标准归类原则：

- `RAG` 表示知识库后端，不表示“一个可直接对话的大模型”。
- `ragflow` 这类外部 RAG 服务保留 `base_url`、`api_key`、超时等服务连接字段。
- `local_knowledge` 这类本地知识库保留切片、召回和本地存储字段。
- 知识库级 `parser_config` 可以覆盖 RAG 默认字段，但只能覆盖切片和解析相关字段，例如 `max_chunk_chars`、`llm_enabled`、`llm_base_url`、`llm_api_key`、`llm_model`、`llm_timeout_seconds`、`llm_max_input_chars`、`llm_max_tokens`。
- `llm_*` 覆盖字段应表达为 `LLM` 或 `SLM` 模型 ID 引用，避免在知识库或记忆配置里直填密钥。

### VAD

语音活动检测，负责判断用户语音开始、结束和静音边界。

当前归属：

```text
model_type = VAD
selected_module.VAD
ai_agent.vad_model_id
config.yaml VAD.*
```

当前典型适配器：

```text
silero
```

标准归类原则：

- VAD 是音频前处理模型，不并入 ASR。
- `threshold`、`threshold_low`、`min_silence_duration_ms` 属于检测策略字段。
- `model_dir` 属于本地模型资产字段，模型大文件不得进入 Git。

### 声纹

声纹识别用于说话人识别、说话人标签和记忆上下文增强。

当前归属：

```text
voiceprint
server.voice_print
server.voiceprint_similarity_threshold
ai_voiceprint
```

当前字段：

```text
url
speakers
similarity_threshold
```

标准归类原则：

- 声纹不是 ASR；它依赖音频输入，但输出说话人身份。
- 声纹服务地址和阈值属于系统能力配置；说话人注册数据属于业务数据，不进入模型配置表。
- 如果后续纳入模型配置，建议新增 `model_type = Voiceprint`，不要复用 `ASR`。
- `speakers` 是环境或业务配置，不能把真实人员身份、描述和私有声纹数据写入默认配置。

### 插件 RAG

插件 RAG 是“通过功能插件触发知识检索”的运行方式，不是新的 RAG 后端。

当前归属：

```text
Plugin:
  search_from_ragflow
  search_from_local_knowledge

RAG:
  RAG_RAGFlow
  RAG_LocalKnowledge

Knowledge:
  ai_rag_dataset
  ai_agent_knowledge_mapping / 插件映射关系
```

标准归类原则：

- `Plugin` 负责“什么时候触发工具”和“当前智能体是否启用工具”。
- `RAG` 负责“用哪个知识库后端检索”和“如何连接、解析、召回”。
- 知识库绑定负责“当前智能体可以查哪些知识库”。
- 不新增 `PluginRAG` 顶层 `model_type`。如果后台需要展示“插件 RAG”，应作为视图或标签组合展示：`Plugin + RAG + KnowledgeBinding`。

## 3. 字段分层

标准模型字段按职责分层，新增字段时先判断落在哪一层。

### 元数据层

用于后台展示、排序和生命周期管理。

```text
id
model_type
model_code
model_name
adapter_code
template_code
vendor_name
product_name
protocol
upstream_model_id
endpoint_url
is_default
is_enabled
sort
doc_link
remark
test_status
test_message
test_time
```

规则：

- `model_type` 使用固定能力分类，大小写保持当前约定。
- `model_code` 对应运行时模块名或配置名。
- `model_name` 是后台配置展示名，不等于上游真实模型名。
- `adapter_code` 来源于 `ai_model_provider.provider_code`，运行时进入 `typed_config.type`。
- `upstream_model_id` 是发给上游 API 的真实模型、资源或服务 ID，运行时按 provider 需要映射为 `model_name`、`model` 或 `resource_id`。
- `endpoint_url` 是 API、WebSocket 或本地服务端点，运行时按 provider 需要映射为 `base_url`、`api_url`、`url`、`ws_url` 或 `host`。

### 供应器层

用于确定由哪个适配器处理。

```text
ai_model_config.adapter_code
ai_model_provider.provider_code
ai_model_provider.fields
```

规则：

- `adapter_code` 必须能映射到运行时 provider。
- `fields` 只描述表单，不承担运行时默认值的唯一来源。
- 供应器能力标签，例如是否流式、是否支持 function call、是否本地模型，应逐步从文案迁移为结构化能力字段。

### 连接与认证层

用于连接外部或本地服务。

```text
base_url
api_url
url
ws_url
host
port
api_key
access_token
secret_key
secret_id
appid
appkey
token
authorization
```

规则：

- `endpoint_url` 放在 `ai_model_config` 主表。
- `api_key`、`access_token`、`secret_key`、`api_secret`、`personal_access_token` 等密钥字段放在 `ai_model_config_secret`。
- 非密钥连接参数，例如 `port`、`appid`、`app_id`、`secret_id`、`authorization` 等按是否敏感选择 `ai_model_config_param` 或 `ai_model_config_secret`。
- 前端保存时提交 `params` 和 `secrets`；后端返回 `secrets` 时只返回 `masked_value`，未修改的脱敏值保存时保留原密钥。
- 密钥字段必须按敏感信息处理，不写真实值进默认文档或 Git。
- `base_url` 表示 API 根地址，`api_url` / `url` 表示具体接口地址，`ws_url` 表示 WebSocket 地址。
- 服务下发地址，例如 `server.vision_explain`，属于系统参数，不放入模型供应器字段。

### 上游模型层

用于标识真实模型、资源或本地模型资产。

```text
model_name
model
resource_id
cluster
voice
voice_id
speaker
model_dir
model_path
reference_id
reference_audio
```

规则：

- 大模型统一优先使用 `model_name`；供应器原生字段为 `model` 时可保留，但展示层要归一为上游模型 ID。
- TTS 音色优先进入 `ai_tts_voice`，供应器默认值保留在模型配置。
- 本地模型路径只保存相对路径或环境可配置路径，模型大文件不提交。

### 推理参数层

用于控制文本或多模态生成。

```text
temperature
max_tokens
top_p
top_k
frequency_penalty
reasoning_effort
thinking
prompt
context
```

规则：

- LLM、SLM、VLLM 的通用参数尽量同名。
- 供应器特有参数可以保留，但不要把同义字段无限扩散。
- 视觉自然表达提示词归 VLLM 推理参数层，不写死在插件或设备参数里。

### 音频参数层

用于 ASR/TTS/VAD 的音频输入输出和实时交互。

```text
format
response_format
audio_format
sample_rate
rate
channels
speed
speed_ratio
volume
volume_ratio
pitch
pitch_ratio
speech_rate
loudness_rate
min_silence_duration_ms
max_sentence_silence
end_window_size
semantic_punctuation_enabled
punctuation_prediction_enabled
inverse_text_normalization_enabled
```

规则：

- 断句、静音和 VAD 参数保留在 ASR/VAD 自身字段，不放入 Intent。
- TTS 的音频格式、语速、音量、音高、情感和混音放在 TTS 配置或音色配置中。
- 火山、MiniMax 等复杂结构可用嵌套对象承载，避免拆成大量顶层散字段。

### 存储与检索层

用于 Memory 和 RAG。

```text
embedder
vector_store
database
max_chunk_chars
parser_config
llm_enabled
llm_model
llm_max_input_chars
llm_max_tokens
top_k
similarity_threshold
dataset_id
rag_model_id
```

规则：

- Memory 的向量库和 RAG 的知识库是不同系统，不能共用一组字段名但语义不同。
- RAG 的 `dataset_id` 是知识库实例 ID，不是模型 ID。
- 知识库切片模型通过模型引用表达，不在知识库实例里直填 `llm_base_url`、`llm_api_key`。

### 工具与插件层

用于功能插件、声明式互动、插件 RAG 和 MCP/HTTP 工具。

```text
provider_code
description
instructions
opening_message
parameters_schema
custom_plugin
param_info
functions
```

规则：

- `Plugin` 只保存工具定义和智能体参数覆盖，不保存 RAG 后端连接密钥。
- `parameters_schema` 是工具调用参数 schema，不是模型配置 schema。
- `custom_plugin` 是声明式互动字段，不代表另一套插件系统。

## 4. 标准化原则

### 标准字段作为事实来源

后台新增和编辑模型配置时，必须写入标准字段：

```text
ai_model_config.adapter_code
ai_model_config.protocol
ai_model_config.upstream_model_id
ai_model_config.endpoint_url
ai_model_config_param
ai_model_config_secret
```

运行时、模型测试、模型评测和助手 LLM 调用都必须通过标准字段组装 typed config。

### 先分类，后抽象

不要因为多个供应器都有 `api_key`、`model`、`url` 就把所有字段重新塞回一个 JSON。标准结构优先统一：

```text
能力分类
字段职责
展示口径
存储边界
```

只有当多个供应器的行为一致，且后台、测试、运行时都能复用时，才抽公共字段。

### 引用优先于复制

以下场景应优先保存模型 ID 引用：

```text
Intent 引用 LLM/SLM
Memory 引用 LLM/SLM/Embedding
RAG 本地知识库切片引用 LLM/SLM
插件需要调用模型时引用 LLM/SLM
```

只有外部系统不支持模型 ID 时，才在局部配置里复制 `base_url`、`api_key`、`model_name`。

### 系统参数不混入模型配置

以下字段不是模型配置：

```text
server.websocket
server.ota
server.vision_explain
server.voice_print
server.voiceprint_similarity_threshold
manager-api.url
manager-api.secret
```

它们可以影响模型或设备能力启用，但归属系统参数或运行环境配置。

### 插件和模型分离

`Plugin` 是功能能力，不是模型能力。插件可以调用 RAG、LLM、MCP 或外部 HTTP，但不能把这些后端连接字段私藏在插件定义里。

插件 RAG 迁移时按三段拆分：

```text
是否启用检索工具 -> Plugin
检索后端和解析参数 -> RAG
当前智能体可查哪些知识库 -> KnowledgeBinding
```

### 安全字段不落默认值

文档、默认配置和迁移 SQL 不得写入真实：

```text
api_key
access_token
secret_key
secret_id
authorization
数据库密码
Redis 密码
生产地址
真实声纹人员信息
```

默认值只能使用空字符串、占位说明或脱敏示例。

## 5. 标准推荐目录结构

后续后台或配置服务可以按以下逻辑展示和维护：

```text
语音输入
  VAD
  ASR
  声纹

语义理解
  Intent
  SLM
  LLM

视觉理解
  VLLM

语音输出
  TTS
  TTS Voice

上下文与知识
  Memory
  RAG
  Knowledge Dataset
  Plugin RAG View

工具与扩展
  Plugin
  MCP
  No-code HTTP Tool
```

其中只有以下项进入模型配置主表：

```text
VAD
ASR
LLM
VLLM
SLM
TTS
Memory
Intent
RAG
```

以下项保留为关联资源或系统配置：

```text
TTS Voice
Knowledge Dataset
Plugin
Plugin RAG View
Voiceprint
MCP
No-code HTTP Tool
```

如果未来要把声纹升级为可选供应器，建议新增 `Voiceprint` 顶层分类，并补齐 provider、config、测试接口和运行时初始化链路后再迁移。

## 6. 新增字段检查清单

新增模型或字段前必须确认：

1. 它属于哪个顶层分类。
2. 它是模型配置、系统参数、知识库实例、插件参数还是智能体覆盖。
3. 它是否包含密钥、内网地址或真实业务数据。
4. 它是否需要进入 `ai_model_provider.fields` 供后台编辑。
5. 它是否属于主表通用字段、`ai_model_config_param` 普通参数或 `ai_model_config_secret` 密钥参数。
6. 它是否需要 per-agent 或 per-knowledge-base 覆盖。
7. 它是否影响模型测试、性能测试或启用状态。
8. 它是否需要数据库迁移、测试接口或运行时组装同步更新。

不满足以上检查时，不应直接新增散字段。
