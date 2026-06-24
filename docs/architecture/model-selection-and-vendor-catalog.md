# 模型选择与厂商目录架构

本文定义智控台模型配置页的目标形态：用户不应先选择底层 `adapter`，再猜厂商控制台里哪个服务能用；系统应提前整理厂商产品、模型系列、协议、适配状态和推荐场景，让用户先选业务目标，再选择经过标注的模型目录项。

调研时间：2026-06-23。厂商模型和价格会变化，目录数据必须带来源链接和最后核验时间，不能把一次调研写死成永久事实。

## 1. 目标体验

模型配置页应按以下顺序引导：

```text
选择能力
  ASR / TTS / LLM / VLLM / Memory / Intent / RAG / VAD
选择使用场景
  实时设备对话 / 短音频识别 / 会议转写 / 低成本批量 / 高质量合成 / 主对话模型
系统展示推荐目录
  厂商产品、模型系列、协议、适配状态、推荐理由、限制、官方开通入口
选择目录项后渲染表单
  只显示该产品需要的字段，密钥字段脱敏，字段说明来自模板
保存后真实测试
  ASR 必须真实音频识别；TTS 必须生成可播放音频；LLM/VLLM 必须真实调用
测试通过后才允许启用
```

界面上必须避免以下问题：

- 只展示“讯飞开放平台 / 语音听写流式 WebAPI”这种宽泛名称，却不说明它对应控制台哪个服务。
- 把同厂商不同协议混为一类，例如把讯飞实时语音转写当成当前 `xunfei_stream` 适配器可用。
- 把“字段完整”“鉴权握手成功”“WebSocket close 1000”当成 ASR 通过。
- 把旧版、2.0、网关、OpenAI 兼容、HTTP、WebSocket 双向流式混在同一个下拉项里。

## 2. 分层设计

```text
官方厂商目录层
  厂商、产品线、模型系列、模型 ID / Resource ID、协议、开通入口、官方文档、最后核验时间

项目适配目录层
  template_code、adapter_code、运行时 provider、字段模板、密钥字段、测试方式、适配状态

推荐策略层
  按能力、实时性、成本、质量、设备场景、已有适配状态给出排序和默认推荐

配置保存层
  ai_model_config 通用字段 + ai_model_config_param 普通参数 + ai_model_config_secret 密钥

运行时验证层
  manager-api 创建测试/评测任务，xiaozhi-server 用真实 provider 产出可审计证据
```

现有代码已有 `ModelCatalogServiceImpl` 和 `/models/catalog`，后续不要继续把厂商目录硬塞进 `ModelConfig.vue` 或 `ModelBenchmarkServiceImpl`。长期目标是让数据库 `ai_model_template` / `ai_model_template_field` 成为可维护事实来源，Java 内置目录只作为首次初始化兜底。

## 3. 标准目录字段

每个目录项至少应包含：

```text
catalog_id / template_code
model_type              ASR / TTS / LLM / VLLM ...
vendor_name             讯飞开放平台 / 火山引擎 / 阿里云百炼 ...
product_name            官方产品名，不等于后台配置名
model_family            模型系列，例如 Seed ASR 2.0、星火中英识别大模型
upstream_model_id       发给上游的 model / resource_id / domain / cluster
adapter_code            本项目运行时 provider type
provider_file           Python provider 文件或 OpenAI 兼容 provider
protocol                HTTP / WebSocket / OpenAI Compatible / DashScope WebSocket
access_mode             非流式 / 单向流式 / 双向流式 / 应用 API / 本地模型
supported_status        SUPPORTED / PARTIAL / NEEDS_ADAPTER / DEPRECATED
recommended_for         推荐场景
avoid_when              不适用场景
required_service        厂商控制台需要开通的服务
required_fields         必填普通字段
credential_fields       密钥字段
test_contract           单模型测试必须证明什么
source_url              官方文档
source_checked_at       最后核验日期
```

`model_name`、`resource_id`、`cluster`、`domain` 不能混用：

- LLM/VLLM 使用上游 `model_name` 或 `model`。
- 豆包语音服务使用 `resource_id` 或旧 `cluster`。
- 讯飞中英识别大模型使用 `domain=slm` 这类产品参数。
- 讯飞实时语音转写使用完全不同的 `rtasr` WebSocket 鉴权与数据协议。

## 4. 讯飞目录口径

### 4.1 ASR

| 官方产品 | 典型协议/地址 | 当前适配状态 | 页面应如何提示 |
| --- | --- | --- | --- |
| 中英识别大模型 | WebSocket，`iat.cn-huabei-1.xf-yun.com/v1`，短音频，`domain=slm` | `SUPPORTED`，当前 `xunfei_stream` provider 实际更接近此产品 | 适合 1 分钟内语音识别和设备一句话/短句交互；选择此项时显示 `app_id/api_key/api_secret/domain/language/accent` |
| 大模型多语种语音识别 | WebSocket，`iat.cn-huabei-1.xf-yun.com/v1`，最长 60 秒 | `PARTIAL`，需确认当前 provider 参数是否覆盖多语种字段 | 可作为后续同 adapter 扩展，必须单独目录项和测试样本 |
| 方言大模型 | WebSocket，讯飞方言大模型协议 | `PARTIAL` 或 `NEEDS_ADAPTER`，取决于字段支持 | 不能只靠 `accent=mandarin` 冒充方言模型；需要明确方言参数和测试样本 |
| 语音听写流式版 WebAPI | WebSocket，标准听写流式协议 | `NEEDS_REVIEW`，当前 provider 默认地址不是传统 `v2/iat` | 后台名称不能继续笼统写成“语音听写流式 WebAPI”，需要拆成标准听写和大模型听写 |
| 实时语音转写标准版 | WebSocket，`rtasr.xfyun.cn/v1/ws` | `NEEDS_ADAPTER` | 这是会议/直播长音频转写，不是当前短音频 ASR adapter；页面可展示“未适配，不能选择启用” |
| 实时语音转写大模型 | WebSocket，大模型转写协议 | `NEEDS_ADAPTER` | 支持更长实时转写，但协议和当前 `xunfei_stream` 不同 |
| 语音转写/录音文件转写 | HTTP，异步文件转写 | `NEEDS_ADAPTER` | 适合录音文件，不适合设备实时对话，不能放在 ASR 实时推荐第一位 |

推荐规则：

- 设备实时对话优先推荐“中英识别大模型”或其他已适配低延迟流式 ASR。
- 会议、直播、长音频转写应推荐实时语音转写标准版/大模型，但当前必须显示“未适配”。
- 录音文件转写是批处理能力，不应作为设备 ASR 选项启用。

### 4.2 TTS

| 官方产品 | 典型协议/地址 | 当前适配状态 | 页面应如何提示 |
| --- | --- | --- | --- |
| 超拟人语音合成 V3 | WebSocket 双向流式，`cbm01.cn-huabei-1.xf-yun.com/v1/private/...` | `SUPPORTED`，`xunfei_stream` TTS | 适合低延迟、自然口语化合成；显示音色、语速、音量、音高、口语化字段 |
| 在线语音合成标准版 | 讯飞普通 TTS API | `NEEDS_ADAPTER` 或单独普通 TTS adapter | 不要和超拟人合成混为一个模板 |
| 一句话复刻/私有音色 | 通常作为音色资源接入 | `PARTIAL` | 作为 `voice/private_voice` 或音色资源管理，不是新 TTS adapter |

### 4.3 LLM/VLLM

| 官方产品 | 当前适配状态 | 页面应如何提示 |
| --- | --- | --- |
| 星火认知大模型 HTTP OpenAI 兼容接口 | `SUPPORTED`，可走 `openai` LLM provider | 使用 `base_url=https://spark-api-open.xf-yun.com/v1`、`model_name`、`api_password`；字段名不能误写为普通 `api_key` 后丢失 |
| 星火 WebSocket 原生接口 | `NEEDS_ADAPTER` | 不要说 OpenAI 兼容 adapter 可直接支持 |
| 星辰 MaaS Token/Coding Plan | `PARTIAL`，取决于 OpenAI 兼容 endpoint 和用途 | Coding Plan 面向编码模型，不应默认推荐为设备主对话模型 |
| 图片理解/星火视觉 | `NEEDS_REVIEW` | 当前 VLLM OpenAI 兼容模板需用真实图片测试证明，不应只因同厂商命名显示可用 |

## 5. 豆包/火山目录口径

### 5.1 ASR

| 官方产品 | 上游标识 | 当前适配状态 | 推荐 |
| --- | --- | --- | --- |
| 豆包旧版 ASR HTTP | `cluster=volcengine_input_common` | `SUPPORTED`，`doubao` | 兼容旧配置，不作为新设备实时对话首选 |
| 豆包大模型流式 ASR | `resource_id=volc.bigasr.sauc.duration` 或并发版 | `SUPPORTED`，`doubao_stream` | 需要保留，但应标注为 1.0/旧大模型流式 |
| Seed ASR 2.0 | `resource_id=volc.seedasr.sauc.duration` 或并发版 | `SUPPORTED`，`doubao_stream` | 新配置优先推荐；适合设备实时对话和成本敏感场景 |
| 方舟音频理解 | `doubao-seed-2-0-lite-*` 等 Chat/Responses 模型 | `NEEDS_ADAPTER` for ASR | 这是音频理解问答，不等同于设备 ASR provider；可作为 LLM/VLLM 多模态能力另列 |

### 5.2 TTS

| 官方产品 | 上游标识 | 当前适配状态 | 推荐 |
| --- | --- | --- | --- |
| 豆包旧版 HTTP TTS | `cluster=volcano_tts` | `SUPPORTED`，`doubao` | 兼容旧配置，不作为低延迟首选 |
| 豆包语音合成大模型 1.0 | `resource_id=volc.service_type.10029` | `SUPPORTED`，`huoshan_double_stream` | 可用，需区分 1.0 音色和混音能力 |
| Seed TTS 2.0 | `resource_id=seed-tts-2.0` | `SUPPORTED`，`huoshan_double_stream` | 新配置优先推荐；页面要明确资源 ID 固定值 |
| Seed-ICL 2.0 声音复刻 | `resource_id=seed-icl-2.0` | `NEEDS_ADAPTER` 或 `PARTIAL` | 声音复刻不是普通 TTS 音色字段，需单独接入和权限说明 |
| 火山 AI Gateway TTS | `model=doubao-tts` | `SUPPORTED`，`openai` TTS | 用于网关场景，字段和火山语音 appid/access_token 不同 |

### 5.3 LLM/VLLM

| 官方产品 | 当前适配状态 | 页面应如何提示 |
| --- | --- | --- |
| 火山方舟 Chat/Responses 模型 | `SUPPORTED`，OpenAI 兼容 LLM | 通过 `https://ark.cn-beijing.volces.com/api/v3`、`model_name`、API Key 接入 |
| Doubao Seed 2.1/2.0 系列 | `SUPPORTED`，取决于账号开通和模型 ID | 当前目录列出 Doubao-Seed-Evolving、2.1-turbo、2.1-pro、2.0-pro/mini/lite/code 和 1.8；模型名称必须填写方舟实际调用 ID。短 ID 在部分账号或套餐下会返回 NotFound，优先使用官方模型列表中的完整版本 ID，例如 `doubao-seed-2-1-turbo-260628` |
| Doubao 1.5/1.6 旧模型 | `DEPRECATED`，仅兼容已有配置 | 旧“豆包方舟”目录项不再作为新增配置入口展示；新增豆包 LLM 统一选择“豆包 Seed 系列” |
| Doubao Vision / 多模态模型 | `PARTIAL`，走 OpenAI 兼容 VLLM 后必须图片测试 | 需要单独 VLLM 目录项，不能隐藏在通用 OpenAI 兼容里 |

## 6. 推荐排序

ASR 实时设备对话：

```text
1. 已适配的双向/流式 ASR
2. 支持真实音频测试且返回非空文本
3. 网络地域离设备服务器更近
4. 支持短句断句、热词、方言或多语种
5. 成本和并发模式匹配当前业务
```

当前可给出的推荐：

```text
中文普通话设备对话：
  豆包 Seed ASR 2.0
  讯飞中英识别大模型
  阿里云百炼 Paraformer 实时识别

需要会议/直播长音频：
  讯飞实时语音转写标准版或大模型，但当前先标为未适配

只做离线或私有化：
  Sherpa-ONNX / FunASR / Vosk，按部署成本和识别质量评测选择
```

### 6.1 阿里云百炼 Realtime / Omni 边界

`qwen3.5-omni-plus-realtime`、`qwen3.5-omni-flash-realtime` 等 Realtime WebSocket 模型可以通过截图、百炼 CLI、官方文档和实际 WebSocket 握手识别出协议线索，但不能自动归入当前普通 LLM OpenAI Chat 目录。

当前处理规则：

```text
火山方舟 Doubao Seed Chat
  -> 使用已有 openai LLM 模板，字段为 base_url、model_name、api_key
  -> model_name 必须是方舟控制台或官方模型列表中的实际调用 ID，不能填 doubao-seed-2-0 这类泛化系列名
  -> 当前下拉只保留已验证可调用的完整版本 ID，例如 doubao-seed-2-1-turbo-260628、doubao-seed-2-0-mini-260428；短 ID 若返回 InvalidEndpointOrModel.NotFound，应改用完整版本 ID 或检查 API Key 项目权限
  -> 旧“火山引擎 / 豆包方舟”目录项只用于兼容历史 DoubaoLLM 配置，新增/编辑选择器中隐藏，避免和 Seed 系列形成两个看似可选的豆包入口

普通通义千问 Chat / 视觉 OpenAI 兼容
  -> 使用已有 openai LLM/VLLM 模板，字段为 base_url、model_name、api_key
  -> LLM 模型下拉按阿里云百炼文本生成官方文档维护，覆盖推荐模型、Qwen3.7、Qwen3.6、Qwen3.5、Qwen3、Qwen Coder、Qwen MT、Qwen Long、Character、结构化输出、Omni Turbo、Qwen-Plus/Max/Flash/Turbo 和 QwQ-Plus 等当前文本生成模型
  -> 不列带日期的历史快照、Preview 或控制台标注即将下线的版本

百炼 Paraformer 实时识别
  -> 使用已有 aliyunbl_stream ASR provider，必须真实音频测试

Qwen Omni Realtime WebSocket
  -> 目录可展示为 NEEDS_ADAPTER
  -> 不允许保存为可启用模型
  -> 需要新增独立 realtime provider、浏览器/后端代理协议和真实音频/文本/工具调用测试后才能转为 SUPPORTED
```

因此，模型列表来源可以来自截图、CLI、官方文档和探针验证，但“可配置启用”必须以项目目录状态、provider 实现和真实测试结果为准。

截至 2026-06-24，百炼文本生成官方文档列出的当前主线模型应优先进入 `LLM_ALI_QWEN_OPENAI`；文档中的历史快照模型只用于锁定特定版本，不进入标准下拉。后台 `LLM_ALI_QWEN_OPENAI` 模板只承接普通通义千问 Chat 文本模型；Audio、Realtime/Omni、视觉专用模型、应用 API 分别需要独立目录项或 adapter，不能套用普通 OpenAI Chat。

TTS 实时设备播报：

```text
1. 已适配的流式或双向流式 TTS
2. 首音频延迟和稳定性优先
3. 音色资源已购买且可商用
4. 支持设备需要的音频格式
```

当前可给出的推荐：

```text
自然度优先：
  豆包 Seed TTS 2.0
  讯飞超拟人语音合成 V3

兼容旧配置：
  豆包旧版 HTTP TTS
  豆包语音合成大模型 1.0

自部署：
  Fish Speech / GPT-SoVITS / IndexStreamTTS，必须先完成服务部署和音频测试
```

LLM 主对话：

```text
1. 支持稳定工具调用或可被 Intent 层可靠兜底
2. 语音场景低延迟，默认不要长思考
3. 真实设备智能体评测通过
4. 不泄露系统提示词、密钥或伪造设备动作
```

## 7. 助手行为

模型配置助手收到厂商文档或截图时必须：

- 先识别官方产品名、协议、地址、模型 ID/Resource ID。
- 再匹配标准目录项，返回 `SUPPORTED`、`PARTIAL`、`NEEDS_ADAPTER` 或 `DEPRECATED`。
- 如果同厂商但协议不同，明确说“当前不支持”，并给出实现任务，而不是套用相近 adapter。
- 只生成草案，不保存配置、不启用模型、不标记测试通过。
- 对图片或用户粘贴内容中的 APIKey、APISecret、Token 做脱敏；如果用户已经暴露真实密钥，应提示立即轮换。

助手返回结构建议：

```json
{
  "recognized_vendor": "讯飞开放平台",
  "recognized_product": "中英识别大模型",
  "matched_catalog_id": "ASR_XUNFEI_ZH_IAT_LLM",
  "supported_status": "SUPPORTED",
  "recommendation": "适合 1 分钟内设备语音识别；不是实时语音转写 rtasr。",
  "required_fields": ["app_id", "api_key", "api_secret", "domain", "language", "accent"],
  "warnings": ["保存后必须执行标准音频真实识别测试"],
  "implementation_tasks": []
}
```

## 8. 验证规则

- ASR：必须上传或流式发送 `wakeup_words_short.wav` 等标准音频，返回非空识别文本才算通过。
- TTS：必须生成非空音频，记录音频格式、大小、首包耗时或总耗时。
- LLM/SLM：必须执行真实 prompt 调用；候选主对话模型需要真实设备智能体评测后再推荐。
- VLLM：必须用真实图片调用并返回可读描述，不能只测试文本接口。
- 未适配目录项：页面可以展示“未适配/待接入”，但不能被保存为已启用模型。

## 9. 落地步骤

```text
阶段 1：目录校正
  拆分讯飞中英识别大模型、语音听写流式版、实时语音转写、录音文件转写。
  拆分豆包旧版 ASR、BigASR、Seed ASR 2.0、方舟音频理解。

阶段 2：页面重构
  模型编辑弹窗先展示推荐目录，不把 adapter 下拉作为第一选择。
  每个目录项显示“已适配/部分适配/未适配/旧版兼容”。

阶段 3：助手增强
  助手用目录项做协议匹配，不再仅按厂商名匹配。
  未适配时输出实现任务和测试要求。

阶段 4：评测闭环
  保存后强制真实测试。
  推荐排序参考最近一次专业评测结果、成功率、延迟和质量评分。

阶段 5：目录维护
  为每个目录项保存 source_url 和 source_checked_at。
  厂商模型列表更新时，先更新目录和文档，再考虑代码/迁移。
```

## 10. 官方资料入口

讯飞：

- 语音听写流式版：https://www.xfyun.cn/doc/asr/voicedictation/API.html
- 中英识别大模型：https://www.xfyun.cn/doc/spark/spark_zh_iat.html
- 大模型多语种语音识别：https://www.xfyun.cn/doc/spark/spark_mul_cn_iat.html
- 方言大模型：https://www.xfyun.cn/doc/spark/spark_slm_iat.html
- 实时语音转写标准版：https://www.xfyun.cn/doc/asr/rtasr/API.html
- 实时语音转写大模型：https://www.xfyun.cn/doc/spark/asr_llm/rtasr_llm.html
- 语音转写/录音文件转写：https://www.xfyun.cn/doc/asr/lfasr/API.html
- 超拟人语音合成：https://docs.iflyaicloud.com/doc/31/
- 星火认知大模型 HTTP：https://www.xfyun.cn/doc/spark/HTTP%E8%B0%83%E7%94%A8%E6%96%87%E6%A1%A3.html
- 星火认知大模型 WebSocket：https://www.xfyun.cn/doc/spark/Web.html
- 讯飞星辰 MaaS Token Plan：https://www.xfyun.cn/doc/spark/TokenPlan.html
- 讯飞星辰 MaaS Coding Plan：https://www.xfyun.cn/doc/spark/CodingPlan.html
- 图片理解：https://www.xfyun.cn/doc/spark/ImageUnderstanding.html

火山/豆包：

- 火山方舟模型列表：https://www.volcengine.com/docs/82379/1554709
- 火山方舟 Chat API：https://www.volcengine.com/docs/82379/1302010
- 火山方舟音频理解：https://www.volcengine.com/docs/82379/2377589
- 豆包流式 ASR / Seed ASR 2.0：https://www.volcengine.com/docs/6561/1354869
- Seed ASR 2.0 RTC 参考：https://www.volcengine.com/docs/6348/1581712
- 豆包语音合成大模型 WebSocket：https://www.volcengine.com/docs/6561/1329505
- 豆包 HTTP 单向流式语音合成：https://www.volcengine.com/docs/6561/2528925?lang=zh
