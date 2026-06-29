# 模型管理与配置体系完整分析

> 分析日期：2026-06-25 | 分支：feature/model-performance-benchmark | 状态：仅记录，不修复

---

## 目录

- [一、总览：五个维度的混乱](#一总览五个维度的混乱)
- [二、模型类型与适配器全景](#二模型类型与适配器全景)
- [三、协议类型对照](#三协议类型对照)
- [四、新旧版本与废弃状态](#四新旧版本与废弃状态)
- [五、配置链路：从 DB 到 Runtime](#五配置链路从-db-到-runtime)
- [六、模型注册与发现机制](#六模型注册与发现机制)
- [七、核心混乱问题](#七核心混乱问题)
- [八、涉及文件清单](#八涉及文件清单)

---

## 一、总览：五个维度的混乱

当前项目的模型管理体系存在**五个维度的混乱**：

| 维度 | 现状 | 问题 |
|---|---|---|
| **模型类型** | 9 种（ASR/TTS/LLM/SLM/VLLM/Intent/Memory/VAD/RAG），类型定义散落在 3 处 | 各处枚举不一致，SLM 和 LLM 界限模糊 |
| **适配器/Provider** | 52 个 Python 提供商文件 + Java 侧 provider 表 | Python 侧按文件系统发现，Java 侧按 DB 管理，两套注册体系互不对齐 |
| **协议类型** | WebSocket 双向流、HTTP SSE 流、HTTP POST、本地推理等 7+ 种 | 同一模型类型的不同提供商可能用不同协议，无统一标注 |
| **新旧版本** | 火山引擎 TTS 有 HTTP→WebSocket 迁移，ASR 有流式→大模型流式演进 | 旧版不删除（仅抛 RuntimeError），新版不标注，配置文件两套并存 |
| **配置来源** | config.yaml（本地）vs manager-api（远程），config_from_api.yaml 模板 | `save_to_file` 标志决定数据流向，行为完全改变 |

---

## 二、模型类型与适配器全景

### 2.1 九种模型类型

在项目中，模型被分为以下 9 种类型：

| 模型类型 | 缩写 | 作用 | Python 工厂 | Java Entity 字段 |
|---|---|---|---|---|
| **语音活动检测** | VAD | 检测用户是否在说话 | `core/utils/vad.py` | `ai_model_config.model_type='VAD'` |
| **语音识别** | ASR | 语音 → 文字 | `core/utils/asr.py` | `ai_model_config.model_type='ASR'` |
| **大语言模型** | LLM | 主对话推理 + 工具调用 | `core/utils/llm.py` | `ai_model_config.model_type='LLM'` |
| **小语言模型** | SLM | 轻量级推理（记忆总结等） | `core/utils/llm.py` | `ai_model_config.model_type='SLM'` |
| **视觉大模型** | VLLM | 图片识别与分析 | `core/utils/vllm.py` | `ai_model_config.model_type='VLLM'` |
| **意图识别** | Intent | 用户对话意图分类 | `core/utils/intent.py` | `ai_model_config.model_type='Intent'` |
| **语音合成** | TTS | 文字 → 语音 | `core/utils/tts.py` | `ai_model_config.model_type='TTS'` |
| **记忆模块** | Memory | 对话记忆存储/检索 | `core/utils/memory.py` | `ai_model_config.model_type='Memory'` |
| **知识库** | RAG | 检索增强生成 | — | `ai_model_config.model_type='RAG'` |

**混乱点**：
- SLM 和 LLM 在 Python 侧共用 `core/utils/llm.py` 工厂、共用 `LLMProviderBase` 基类，无法区分
- 移动端 SLM 选择器实际上用的是 LLM 的模型列表（`modelOptions.LLM`）
- `config.yaml` 中没有独立的 SLM 配置段，`selected_module` 里也没有 SLM key

---

### 2.2 ASR（语音识别）— 14 个适配器

| # | 适配器文件 | 接口模式 | 外部服务 | 状态 | Python type |
|---|---|---|---|---|---|
| 1 | `asr/sherpa_onnx_local.py` | 本地推理 (sherpa-onnx) | 无（本地模型） | ✅ 当前 | `sherpa_onnx_local` |
| 2 | `asr/fun_local.py` | 本地推理 (FunASR) | 无（本地模型） | ✅ 当前 | `fun_local` |
| 3 | `asr/vosk.py` | 本地推理 (VOSK) | 无（本地模型） | ✅ 当前 | `vosk` |
| 4 | `asr/doubao.py` | WebSocket 全双工（非流式） | 火山引擎 豆包 ASR | ✅ 当前 | `doubao` |
| 5 | `asr/doubao_stream.py` | WebSocket 流式 | 火山引擎 豆包流式 ASR / Seed ASR | ✅ 当前 | `doubao_stream` |
| 6 | `asr/aliyun.py` | HTTP POST（非流式） | 阿里云 NLS | ✅ 当前 | `aliyun` |
| 7 | `asr/aliyun_stream.py` | WebSocket 流式 | 阿里云 NLS 实时 | ✅ 当前 | `aliyun_stream` |
| 8 | `asr/aliyunbl_stream.py` | WebSocket 流式 | 阿里百炼 Paraformer | ✅ 当前 | `aliyunbl_stream` |
| 9 | `asr/baidu.py` | HTTP RPC | 百度 AI Cloud | ✅ 当前 | `baidu` |
| 10 | `asr/tencent.py` | HTTP POST | 腾讯云 ASR | ✅ 当前 | `tencent` |
| 11 | `asr/xunfei_stream.py` | WebSocket 流式 | 讯飞开放平台 | ✅ 当前 | `xunfei_stream` |
| 12 | `asr/openai.py` | HTTP POST | OpenAI Whisper | ✅ 当前 | `openai` |
| 13 | `asr/qwen3_asr_flash.py` | HTTP 流式 | 阿里百炼 Qwen3-ASR | ✅ 当前 | `qwen3_asr_flash` |
| 14 | `asr/fun_server.py` | WebSocket | FunASR 服务器 | ✅ 当前 | `fun_server` |

**config.yaml 中的条目**（@2026-06-25）：
```
SherpaASR, SherpaParaformerASR, DoubaoASR, DoubaoStreamASR, DoubaoStreamASRV2,
TencentASR, AliyunASR, AliyunStreamASR, BaiduASR, OpenaiASR, GroqASR,
VoskASR, Qwen3ASRFlash, XunfeiStreamASR, AliyunBLStreamASR
```
共 15 个条目，比实际适配器文件多（因为 GroqASR 复用 `openai` type、Sherpa 有 paraformer 变体）。

---

### 2.3 TTS（语音合成）— 19 个适配器

| # | 适配器文件 | 接口模式 | 外部服务 | 状态 | Python type |
|---|---|---|---|---|---|
| 1 | `tts/huoshan_double_stream.py` | WebSocket 双向流 | 火山引擎 Seed TTS 2.0 | ✅ 当前 | `huoshan_double_stream` |
| 2 | `tts/aliyun_stream.py` | WebSocket 双向流 | 阿里云 NLS 流式 | ✅ 当前 | `aliyun_stream` |
| 3 | `tts/alibl_stream.py` | WebSocket 双向流 | 阿里百炼 CosyVoice | ✅ 当前 | `alibl_stream` |
| 4 | `tts/xunfei_stream.py` | WebSocket 双向流 | 讯飞开放平台 | ✅ 当前 | `xunfei_stream` |
| 5 | `tts/index_stream.py` | HTTP 流式 (SSE) | 自定义端点 | ✅ 当前 | `index_stream` |
| 6 | `tts/minimax_httpstream.py` | HTTP SSE 流式 | MiniMax T2A v2 | ✅ 当前 | `minimax_httpstream` |
| 7 | `tts/aliyun.py` | HTTP POST（非流式） | 阿里云 NLS | ✅ 当前 | `aliyun` |
| 8 | `tts/tencent.py` | HTTP POST（非流式） | 腾讯云 TTS | ✅ 当前 | `tencent` |
| 9 | `tts/openai.py` | HTTP POST（非流式） | OpenAI TTS API | ✅ 当前 | `openai` |
| 10 | `tts/siliconflow.py` | HTTP POST（非流式） | SiliconFlow | ✅ 当前 | `siliconflow` |
| 11 | `tts/edge.py` | HTTP 异步 | Microsoft Edge TTS | ✅ 当前 | `edge` |
| 12 | `tts/cozecn.py` | HTTP POST | Coze CN | ✅ 当前 | `cozecn` |
| 13 | `tts/custom.py` | HTTP GET/POST | 自定义端点 | ✅ 当前 | `custom` |
| 14 | `tts/fishspeech.py` | HTTP POST (msgpack) | FishSpeech 服务器 | ✅ 当前 | `fishspeech` |
| 15 | `tts/gpt_sovits_v2.py` | HTTP POST | GPT-SoVITS v2 | ✅ 当前 | `gpt_sovits_v2` |
| 16 | `tts/gpt_sovits_v3.py` | HTTP GET | GPT-SoVITS v3 | ✅ 当前 | `gpt_sovits_v3` |
| 17 | `tts/paddle_speech.py` | WebSocket | PaddleSpeech | ✅ 当前 | `paddle_speech` |
| 18 | `tts/default.py` | N/A（兜底报错） | 无 | ✅ 当前 | `default` |
| 19 | `tts/doubao.py` | HTTP POST | 豆包 HTTP TTS | ❌ **已废弃** | `doubao`（抛 RuntimeError） |

**config.yaml 中仅有 1 个条目**：`DoubaoSeedTTS2Stream`（火山引擎双向流）。其他 18 个 TTS 适配器在 config.yaml 中完全没有配置段。

**废弃逻辑**（`tts/doubao.py:16`）：
```python
DEPRECATION_MESSAGE = (
    "DoubaoTTS HTTP provider 已不再作为本项目有效豆包 TTS 入口；"
    "请使用 TTS.DoubaoSeedTTS2Stream..."
)
raise RuntimeError(DEPRECATION_MESSAGE)
```

---

### 2.4 LLM（大语言模型）— 9 个适配器

| # | 适配器文件 | 协议 | 外部服务 | 是否支持 Tool Call | Python type |
|---|---|---|---|---|---|
| 1 | `llm/openai/openai.py` | HTTP SSE 流式 | OpenAI 兼容 API | ✅ 原生支持 | `openai` |
| 2 | `llm/gemini/gemini.py` | HTTP SSE 流式 | Google Gemini | ✅ 支持 | `gemini` |
| 3 | `llm/ollama/ollama.py` | HTTP SSE 流式 | Ollama（本地） | ✅ 支持 | `ollama` |
| 4 | `llm/xinference/xinference.py` | HTTP SSE 流式 | Xinference（本地） | ✅ 支持 | `xinference` |
| 5 | `llm/AliBL/AliBL.py` | HTTP SSE 流式 | 阿里百炼 App | ❌ 回退到 intent | `AliBL` |
| 6 | `llm/coze/coze.py` | HTTP SSE 流式 | Coze CN | ❌ 回退到 intent | `coze` |
| 7 | `llm/dify/dify.py` | HTTP SSE 流式 | Dify | ❌ 回退到 intent | `dify` |
| 8 | `llm/fastgpt/fastgpt.py` | HTTP SSE 流式 | FastGPT | ✗ Stub（打日志） | `fastgpt` |
| 9 | `llm/homeassistant/homeassistant.py` | HTTP POST | Home Assistant | ✗ Stub（打日志） | `homeassistant` |

**config.yaml 中的条目**（14 个）：
```
AliLLM, AliAppLLM, DoubaoLLM, DeepSeekLLM, ChatGLMLLM, OllamaLLM, DifyLLM,
GeminiLLM, CozeLLM, VolcesAiGatewayLLM, LMStudioLLM, HomeAssistant,
FastgptLLM, XinferenceLLM, XinferenceSmallLLM
```

**混乱点**：
- `ChatGLMLLM`、`DoubaoLLM`、`DeepSeekLLM`、`LMStudioLLM`、`VolcesAiGatewayLLM`、`XunfeiSparkLLM` 全部复用 `type: openai`，仅在 `model_name` 和 `base_url` 上有区别
- `XunfeiSparkLLM` 放在 `VLLM` 配置段里，但实际是 LLM
- `AliAppLLM` 的 type 是 `AliBL`，是唯一不走 OpenAI 兼容协议的阿里系 LLM

---

### 2.5 VAD（语音活动检测）— 1 个适配器

| # | 适配器文件 | 接口模式 | 外部服务 | Python type |
|---|---|---|---|---|
| 1 | `vad/silero.py` | 本地推理 (ONNX) | 无 | `silero` |

**config.yaml 中仅有 `SileroVAD`**，type 为 `silero`。只有一个选项，无选择空间。

---

### 2.6 VLLM（视觉大模型）— 1 个适配器

| # | 适配器文件 | 协议 | 外部服务 | Python type |
|---|---|---|---|---|
| 1 | `vllm/openai.py` | HTTP POST（非流式） | OpenAI 兼容视觉 API | `openai` |

**config.yaml 中 VLLM 段有**：`ChatGLMVLLM`、`QwenVLVLLM`、`XunfeiSparkLLM`（第三个实为 LLM，放错位置）。

---

### 2.7 Intent（意图识别）— 3 个适配器

| # | 适配器文件 | 模式 | Python type |
|---|---|---|---|
| 1 | `intent/function_call/function_call.py` | 始终返回 `continue_chat` | `function_call` |
| 2 | `intent/intent_llm/intent_llm.py` | 调用独立 LLM 做意图分类 | `intent_llm` |
| 3 | `intent/nointent/nointent.py` | 始终返回 `continue_chat`（不做意图识别） | `nointent` |

**混乱点**：`function_call` 和 `nointent` 的实现完全相同（都硬编码返回 `continue_chat`），但名字不同。`function_call` 这个名字极具误导性——它并非真正的 Function Call 意图识别，只是一个占位符。

---

### 2.8 Memory（记忆模块）— 5 个适配器

| # | 适配器文件 | 模式 | 外部服务 | Python type |
|---|---|---|---|---|
| 1 | `memory/mem_local_short/mem_local_short.py` | LLM 本地总结 + YAML 文件/Java API | 本地 LLM | `mem_local_short` |
| 2 | `memory/mem0ai/mem0ai.py` | 云端 SDK (Mem0 v1.1) | Mem0.ai | `mem0ai` |
| 3 | `memory/powermem/powermem.py` | 本地 LLM + 向量数据库 | PowerMem (OceanBase 开源) | `powermem` |
| 4 | `memory/mem_report_only/mem_report_only.py` | 仅上报不总结 | 无 | `mem_report_only` |
| 5 | `memory/nomem/nomem.py` | 不做任何记忆 | 无 | `nomem` |

**config.yaml 中的条目**：`mem0ai`、`powermem`、`nomem`、`mem_local_short`。缺少 `mem_report_only` 的独立配置段。

---

### 2.9 统计摘要

| 模型类型 | 适配器数 | 废弃 | 本地推理 | 云端 API |
|---|---|---|---|---|
| ASR | 14 | 0 | 3 (sherpa, fun_local, vosk) | 11 |
| TTS | 19 | 1 (doubao) | 0 | 18 |
| LLM | 9 | 0 | 2 (ollama, xinference) | 7 |
| VAD | 1 | 0 | 1 (silero) | 0 |
| VLLM | 1 | 0 | 0 | 1 |
| Intent | 3 | 0 | 3 (all in-process) | 0 |
| Memory | 5 | 0 | 3 (local/nomem/report) | 2 |
| **合计** | **52** | **1** | **12** | **39** |

---

## 三、协议类型对照

项目使用了 **7 种以上的通信协议**，同一模型类型的不同提供商可能用完全不同的协议：

### 3.1 协议分类

| 协议类型 | 说明 | 使用场景 |
|---|---|---|
| **WebSocket 双向流** | 全双工，边发边收 | 豆包 Seed TTS 2.0、阿里云/讯飞流式 TTS、阿里百炼流式 ASR |
| **WebSocket 单向流** | 客户端发、服务端返回 | 豆包流式 ASR、讯飞流式 ASR |
| **HTTP SSE 流式** | HTTP 长连接，逐 chunk 推送 | 所有 LLM 提供商、MiniMax TTS、Index TTS |
| **HTTP POST 非流式** | 单次请求/响应 | 阿里云/百度/腾讯 ASR、OpenAI TTS、SiliconFlow TTS |
| **HTTP GET 非流式** | URL 参数触发 | GPT-SoVITS v3、Custom TTS |
| **本地 ONNX 推理** | 进程内模型推理 | Silero VAD、sherpa-onnx ASR |
| **本地库调用** | Python SDK 直接调用 | VOSK ASR、FunASR 本地 |

### 3.2 协议与适配器映射

```
                     ┌─ WebSocket 双向流 ─── huoshan_double_stream (火山TTS)
                     │                       aliyun_stream (阿里TTS)
                     │                       xunfei_stream (讯飞TTS)
                     │                       alibl_stream (百炼TTS)
                     │
   TTS ──────────────┼─ HTTP SSE 流式 ────── index_stream, minimax_httpstream
                     │
                     ├─ HTTP POST ────────── aliyun, tencent, openai, siliconflow
                     │                       cozecn, fishspeech, gpt_sovits_v2, edge
                     │
                     └─ HTTP GET ─────────── gpt_sovits_v3, custom

                     ┌─ WebSocket 流式 ───── aliyun_stream, aliyunbl_stream
                     │                       doubao, doubao_stream, xunfei_stream
   ASR ──────────────┤
                     ├─ HTTP POST ────────── aliyun, baidu, tencent, openai, qwen3_asr_flash
                     │
                     └─ 本地推理 ──────────── sherpa_onnx_local, fun_local, vosk

   LLM ────────────── HTTP SSE 流式 ──────── 所有 9 个 LLM 提供商统一使用

   VAD ────────────── 本地 ONNX ──────────── silero (唯一)
   VLLM ───────────── HTTP POST ──────────── openai (唯一)
```

### 3.3 协议混乱问题

1. **TTS 协议最混乱**：同一模型类型用 4 种完全不同的协议。`base.py` 中 `to_tts_stream()` 和 `to_tts()` 两套 TTS 接口需要适配完全不同底层通信方式。
2. **火山引擎 TTS 独特性**：`huoshan_double_stream` 是唯一使用火山引擎双向流协议的，在 `ModelRuntimeAssembler.java` 中有专门的 `normalizeHuoshanDoubleStreamTtsConfig()` 方法做特殊处理。
3. **无统一协议标注**：Java `ai_model_config.protocol` 字段有值（如 "WebSocket"、"OpenAI Chat"），但没有被 Python 侧读取或用于校验。

---

## 四、新旧版本与废弃状态

### 4.1 新旧版本演进

| 演进路径 | 旧版 | 新版 | 迁移状态 |
|---|---|---|---|
| **豆包 TTS** | `doubao.py`（HTTP 非流式） | `huoshan_double_stream.py`（WebSocket 双向流） | 旧版抛 RuntimeError，DB 迁移清空旧音色 |
| **豆包 ASR** | `DoubaoASR`（非流式，按次计费） | `DoubaoStreamASR`（流式，按时计费）→ `DoubaoStreamASRV2`（Seed ASR） | 三代并存，config.yaml 均有配置段 |
| **阿里云 ASR** | `AliyunASR`（HTTP 非流式） | `AliyunStreamASR`（WebSocket 流式） | 两代并存 |
| **阿里云 TTS** | `aliyun.py`（HTTP 非流式） | `aliyun_stream.py`（WebSocket 流式） | 两代并存 |
| **GPT-SoVITS** | `gpt_sovits_v2.py` | `gpt_sovits_v3.py` | 两代并存 |

### 4.2 废弃但未删除的代码

- **`tts/doubao.py`**：约 20 行，import 即抛 RuntimeError，但文件仍在仓库
- **`config.yaml`**：废旧的 `type` 标记（如 `AliAppLLM` 的 `AliBL` 不再推荐使用）仍然保留
- **DB 迁移 `202606251815.sql`**：DELETE 了旧音色但旧音色名称散落在早期迁移脚本中

### 4.3 版本标注混乱

Java `ai_model_template` 表有 `deprecated`、`supported_status`、`selectable`、`hidden` 等字段来管理版本状态，但：
- 这些字段是 2026-06-23 才加的（`202606232230.sql`）
- 大部分模板的 `deprecated` 默认为 0，即使对应的 Python provider 已废弃
- Python 侧的废弃标记是代码级别的（`RuntimeError`、注释），Java 侧不会读取

---

## 五、配置链路：从 DB 到 Runtime

### 5.1 完整数据流

```
┌─ 管理界面层 ──────────────────────────────────────────────────────────────┐
│                                                                           │
│  manager-web (Vue 2)                   manager-mobile (Vue 3 uni-app)     │
│  ├─ ModelConfig.vue — 模型配置 CRUD    ├─ agent/edit.vue — 角色配置      │
│  ├─ ProviderManagement.vue — 供应商    │   ├─ VAD/ASR/LLM/TTS 选择器     │
│  ├─ TtsModel.vue — 音色管理            │   └─ 音色选择器                  │
│  └─ ModelEditDialog.vue — 模型编辑     │                                  │
│                                                                           │
└──────────────────────────────┬────────────────────────────────────────────┘
                               │ HTTP REST
                               ▼
┌─ Java API 层 ────────────────────────────────────────────────────────────┐
│                                                                           │
│  ModelController / ModelProviderController                                │
│    ├─ GET  /models/list         → ModelConfigService                     │
│    ├─ GET  /models/catalog      → ModelCatalogService                    │
│    ├─ POST /models/{type}/{adapter} → 创建模型配置                       │
│    └─ POST /models/test/{id}   → 测试模型                                │
│                                                                           │
│  ModelConfigServiceImpl                                                   │
│    ├─ 模型 CRUD 操作                                                      │
│    ├─ 模型缓存（Caffeine Cache）                                          │
│    └─ buildModelConfig() → 组装完整配置                                   │
│                                                                           │
│  ModelRuntimeAssembler                                                   │
│    ├─ buildRuntimeModel() → 构建运行时配置 Map                            │
│    ├─ buildTypedConfig() → 构建 typed_config JSON                        │
│    ├─ normalizeHuoshanDoubleStreamTtsConfig() → 火山 TTS 特殊处理         │
│    └─ saveRuntimeConfig() → 保存配置回 DB                                │
│                                                                           │
│  ConfigServiceImpl                                                       │
│    ├─ getAgentModelsConfig() → 按 VAD/ASR/TTS/Memory/Intent/LLM/          │
│    │                           VLLM/SLM/RAG 顺序组装 selected_module      │
│    └─ POST /config/agent-models → 返回给 Python                          │
│                                                                           │
│  存储层：                                                                 │
│    ai_model_config        — 模型主表（id, modelType, modelCode,           │
│                             adapterCode, protocol, endpointUrl, ...）     │
│    ai_model_config_param  — 模型参数（model_id, paramKey, paramValue）     │
│    ai_model_config_secret — 模型密钥（model_id, secretKey, secretValue）   │
│    ai_model_template      — 模型模板（templateCode, adapterCode,          │
│                             vendorName, supportedStatus, deprecated...）  │
│    ai_model_template_field — 模板字段定义（fieldKey, runtimeKey,           │
│                              fieldLabel, valueType, inputType...）        │
│    ai_model_provider      — 供应器（providerCode, modelType, fields）     │
│    ai_tts_voice           — TTS 音色（tts_model_id, tts_voice, name）    │
│    ai_agent               — 智能体表引用:                                 │
│      ├─ vad_model_id / asr_model_id / llm_model_id / slm_model_id        │
│      ├─ vllm_model_id / intent_model_id / mem_model_id / tts_model_id    │
│      └─ tts_voice_id / tts_volume / tts_rate / tts_pitch                 │
│                                                                           │
└──────────────────────────────┬────────────────────────────────────────────┘
                               │ HTTP POST /config/agent-models
                               ▼
┌─ Python Runtime 层 ──────────────────────────────────────────────────────┐
│                                                                           │
│  manage_api_client.py                                                     │
│    └─ get_agent_models(mac_address, client_id, selected_module)           │
│       → POST /config/agent-models                                        │
│       → 返回 {selected_module: {ASR: "xxx", TTS: "yyy", ...},            │
│               model_configs: {modelId: {type: "xxx", ...}}}               │
│                                                                           │
│  connection.py                                                            │
│    ├─ _initialize_private_config_async() → 拉取 config                   │
│    ├─ _initialize_tts() → tts_utils.create_instance(...)                 │
│    ├─ _initialize_asr() → asr_utils.create_instance(...)                 │
│    ├─ _initialize_memory() → memory_utils.create_instance(...)           │
│    └─ _initialize_intent() → intent_utils.create_instance(...)           │
│                                                                           │
│  core/utils/*.py（工厂函数）                                              │
│    ├─ asr.py → 动态 import core/providers/asr/{type}.py                  │
│    ├─ tts.py → 动态 import core/providers/tts/{type}.py                  │
│    ├─ llm.py → 动态 import core/providers/llm/{type}/{type}.py           │
│    ├─ vllm.py → 动态 import core/providers/vllm/{type}.py                │
│    ├─ vad.py → 动态 import core/providers/vad/{type}.py                  │
│    ├─ memory.py → 动态 import core/providers/memory/{type}/{type}.py     │
│    └─ intent.py → 动态 import core/providers/intent/{type}/{type}.py     │
│                                                                           │
│  本地配置模式（config.yaml）：                                            │
│    connection.py:908                                                      │
│      save_to_file=not self.read_config_from_api                           │
│      → read_config_from_api=False 时，使用 config.yaml 配置               │
│      → 直接从 config["selected_module"] 键值初始化模型                    │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

### 5.2 两条配置路径

| 模式 | `read_config_from_api` | 配置来源 | `save_to_file` | 记忆存储 |
|---|---|---|---|---|
| **本地/开发** | `False` | `config.yaml` / `data/.config.yaml` | `True` | YAML 文件 |
| **生产/智控台** | `True` | Java API → MySQL | `False` | Java API → MySQL |

### 5.3 config.yaml 的 selected_module 示例

```yaml
selected_module:
  VAD: SileroVAD
  ASR: DoubaoStreamASR
  LLM: AliLLM
  TTS: DoubaoSeedTTS2Stream
  Memory: mem_local_short
  Intent: intent_llm
  VLLM: ChatGLMVLLM
```

`selected_module` 的 key 是模型类型的缩写（VAD/ASR/LLM/TTS/Memory/Intent/VLLM），value 是 config.yaml 中该类型的配置段名称。

### 5.4 Java API 返回的 selected_module 格式

Java `ConfigServiceImpl` 按固定顺序遍历模型类型数组：
```java
String[] modelTypes = {"VAD", "ASR", "TTS", "Memory", "Intent", "LLM", "VLLM", "SLM", "RAG"};
```

每个类型取 agent 绑定的 model ID → 查 `ai_model_config` 表 → 用 `ModelRuntimeAssembler.buildRuntimeModel()` 组装，最终返回：

```json
{
  "selected_module": {
    "VAD": {"id": "VAD_xxx", "type": "silero", ...},
    "ASR": {"id": "ASR_xxx", "type": "doubao_stream", ...},
    "LLM": {"id": "LLM_xxx", "type": "openai", "model_name": "qwen-flash", ...},
    "TTS": {"id": "TTS_xxx", "type": "huoshan_double_stream", ...}
  },
  "model_configs": {
    "VAD_xxx": {"type": "silero", "threshold": 0.5, ...},
    "ASR_xxx": {"type": "doubao_stream", "resource_id": "...", ...}
  }
}
```

---

## 六、模型注册与发现机制

### 6.1 Python 侧：文件系统约定

Python 不做中心化注册，工厂函数通过**文件路径约定**动态发现 provider：

```python
# core/utils/asr.py
def create_instance(class_name: str, *args, **kwargs):
    module = importlib.import_module(f"core.providers.asr.{class_name}")
    return module.ASRProvider(*args, **kwargs)
```

约定：
- 文件名 = type 值（如 `type: doubao_stream` → `core/providers/asr/doubao_stream.py`）
- 类名固定（如 ASR 都是 `ASRProvider`，TTS 都是 `TTSProvider`）
- LLM/Memory/Intent 子目录规则：`type: intent_llm` → `core/providers/intent/intent_llm/intent_llm.py`

**问题**：如果 Java 侧配置了一个 `adapterCode` 但 Python 侧没有对应文件，会在运行时 `ModuleNotFoundError` 崩溃。

### 6.2 Java 侧：DB 驱动

Java 侧通过 `ai_model_provider`、`ai_model_template`、`ai_model_config` 三张表管理模型注册：

- **Provider** 定义供应器类型（如 `openai`、`doubao_stream`）
- **Template** 定义模型模板（如 `LLM_ALI_QWEN_OPENAI`），含字段 schema、厂商、协议等元数据
- **Config** 是用户实际创建的模型实例，绑定到一个 Template

**问题**：Provider 表的 `providerCode` 不一定与 Python 的 `type` 对应。例如 `providerCode: "openai"` 在 Python 侧映射到 `core/providers/llm/openai/openai.py`，但 `providerCode: "AliBL"` 则映射到 `core/providers/llm/AliBL/AliBL.py` ——大小写敏感，且无校验。

### 6.3 两套注册体系的不对齐

| 方面 | Python 侧 | Java 侧 |
|---|---|---|
| 注册方式 | 文件系统约定 | DB 表记录 |
| 发现机制 | importlib 动态加载 | SQL 查询 + 缓存 |
| 新增模型 | 写 Python 文件 + 在 config.yaml 加配置段 | 在 DB 插入 `ai_model_config` 行 |
| 废弃模型 | 代码里抛 RuntimeError | 设 `deprecated=1` |
| 字段校验 | config.yaml 的结构即文档 | `ai_model_template_field` 表定义 schema |
| 密钥管理 | config.yaml 明文 | `ai_model_config_secret` 表 + masking |

**核心矛盾**：Python 完全不感知 Java 的 template/field schema 体系，只认 `type` 和配置 dict。Java 精心设计的字段校验、选项列表、帮助文本等元数据，到了 Python 侧全部丢失。

---

## 七、核心混乱问题

### 问题 1：Model Type 枚举不一致

三处定义，互不对齐：

| 位置 | 枚举值 |
|---|---|
| Java `ModelConfigEntity.modelType` | Memory/ASR/VAD/LLM/TTS（注释中的 5 种） |
| Java `ConfigServiceImpl` 遍历数组 | VAD, ASR, TTS, Memory, Intent, LLM, VLLM, SLM, RAG（9 种） |
| 前端 `ModelConfig.vue` 导航菜单 | vad, asr, llm, slm, vllm, intent, tts, memory, rag（9 种，小写） |
| 前端 `ProviderManagement.vue` | ASR, TTS, LLM, SLM, VLLM, Intent, Memory, VAD, Plugin, RAG（10 种） |
| Python `config.yaml` | VAD, ASR, LLM, TTS, VLLM, Memory, Intent（7 种，无 SLM/RAG） |

### 问题 2：SLM 概念模糊

- Java 侧有 `SLM` 模型类型，也有 `slm_model_id` 字段
- Python 侧完全没有 SLM 概念——SLM 和 LLM 共用 `llm.py` 工厂和 `LLMProviderBase`
- 移动端 SLM 选择器实际用 LLM 的模型列表
- config.yaml 的 `selected_module` 中没有 SLM key

### 问题 3：Python config.yaml 与 Java DB 数据不同步

- `config.yaml` 定义了 15 个 ASR 配置段、14 个 LLM 配置段、5 个 Memory 配置段、1 个 TTS 配置段
- Java DB 通过迁移脚本插入模型数据，但和 config.yaml 的配置段名称不是一一对应
- 例如：`config.yaml` 中有 `DoubaoSeedTTS2Stream` 配置段，DB 中有 `model_code='DoubaoSeedTTS2Stream'` 行——但字段名不完全一致（config.yaml 用 `speaker`，DB 用 `ai_model_config_param.param_key='speaker'`）

### 问题 4：TTS 管理盲区

config.yaml 的 TTS 段只有 1 个模型（`DoubaoSeedTTS2Stream`），但 Python 侧有 19 个 TTS 适配器文件。用户如果想用其他 18 个 TTS（如 Edge TTS、OpenAI TTS），必须：
1. 在 Java DB 中手动创建 model_config
2. 知道正确的 `adapterCode`（如 `edge`、`openai`、`siliconflow`）
3. 配置对应的参数（api_key、voice 等）

没有任何 UI 或文档引导这个流程。

### 问题 5：废弃管理缺失

- `tts/doubao.py` 抛出 RuntimeError 但文件仍在
- DB 迁移 `202606251815.sql` DELETE 了旧音色，但旧 `ai_model_config` 行的 `adapterCode` 可能还指向废弃代码
- config.yaml 中无任何 deprecated 标记

### 问题 6：火山引擎 TTS 特殊处理过多

`ModelRuntimeAssembler.java` 中有 **7 个**专为火山引擎双向流 TTS 设计的方法：
- `normalizeHuoshanDoubleStreamTtsConfig()`
- `isHuoshanDoubleStreamTtsConfig()`
- `resolveDefaultHuoshanTtsResourceId()`
- `resolveDefaultHuoshanTtsSpeaker()`
- `isManagedDoubaoTtsTemplate()`
- `hasUsableHuoshanTtsSpeaker()`
- `hasUsableHuoshanTtsField()`
- `isLegacyHuoshanTtsEndpoint()`

以及 4 个硬编码常量：
```java
private static final String HUOSHAN_DOUBLE_STREAM_TYPE = "huoshan_double_stream";
private static final String HUOSHAN_DOUBLE_STREAM_WS_URL = "wss://openspeech.bytedance.com/api/v3/tts/bidirection";
private static final String DOUBAO_SEED_TTS_2_RESOURCE_ID = "seed-tts-2.0";
private static final String DOUBAO_SEED_TTS_2_DEFAULT_SPEAKER = "zh_female_sophie_uranus_bigtts";
```

这些逻辑本应在 provider 层面配置，而非硬编码在通用 Assembler 中。如果以后支持更多火山引擎 TTS 服务，Assembler 会进一步膨胀。

### 问题 7：配置键名混乱

同一概念在不同层用不同键名：

| 概念 | config.yaml | Java DB param_key | Python config.get() | 模板 field_key |
|---|---|---|---|---|
| API 端点 | `base_url` / `ws_url` / `url` | `endpoint_url` | `base_url` / `ws_url` | `base_url` / `ws_url` |
| 模型名称 | `model_name` | `upstream_model_id` | `model_name` / `model` | `model_name` |
| API 密钥 | `api_key` | `secret_value` (ai_model_config_secret) | `api_key` | `api_key` |

### 问题 8：前端硬编码泛滥

前端代码中有大量 provider 相关的硬编码判断：

| 位置 | 硬编码内容 |
|---|---|
| `ModelEditDialog.vue:609-637` | 阿里百炼/通义千问 模型匹配逻辑 |
| `TtsModel.vue:194` | `fishspeech`/`gpt_sovits_v2`/`gpt_sovits_v3` 特殊参考音频列 |
| `ModelEditDialog.vue:692-709` | 各种字段的 placeholder 中文文本 |
| `ModelEditDialog.vue:857-874` | 各种字段的 help text 中文文本 |
| `ModelConfig.vue:786-800` | `supportedStatus` 中文字符串 |
| `ProviderManagement.vue:163` | `sensitiveFieldKeys` 列表 |

新增 provider 时，这些硬编码都可能成为遗漏点。

---

## 八、涉及文件清单

### Python 服务端 (xiaozhi-server)

**模型工厂（动态发现）**：
| 文件 | 说明 |
|---|---|
| `core/utils/asr.py` | ASR 工厂，动态加载 `core.providers.asr.{type}` |
| `core/utils/tts.py` | TTS 工厂，动态加载 `core.providers.tts.{type}` |
| `core/utils/llm.py` | LLM 工厂，动态加载 `core.providers.llm.{type}.{type}` |
| `core/utils/vllm.py` | VLLM 工厂，动态加载 `core.providers.vllm.{type}` |
| `core/utils/vad.py` | VAD 工厂，动态加载 `core.providers.vad.{type}` |
| `core/utils/intent.py` | Intent 工厂，动态加载 `core.providers.intent.{type}.{type}` |
| `core/utils/memory.py` | Memory 工厂，动态加载 `core.providers.memory.{type}.{type}` |
| `core/utils/modules_initialize.py` | 编排层，读取 `selected_module` 并调用各工厂 |

**模型配置与连接**：
| 文件 | 说明 |
|---|---|
| `core/connection.py` | 连接处理器，`_initialize_components()` 初始化所有模型 |
| `config/manage_api_client.py` | Java API 客户端，`get_agent_models()` 获取远程配置 |
| `config.yaml` | 本地完整配置模板（800 行），含所有模型配置段 |
| `config_from_api.yaml` | 生产模式最小配置模板 |

**Provider 基类**：
| 文件 | 说明 |
|---|---|
| `core/providers/asr/base.py` | ASR 基类，接口：STREAM/NON_STREAM/LOCAL |
| `core/providers/tts/base.py` | TTS 基类，接口：DUAL_STREAM/SINGLE_STREAM/NON_STREAM |
| `core/providers/llm/base.py` | LLM 基类，HTTP SSE 流式生成 |
| `core/providers/vllm/base.py` | VLLM 基类，HTTP POST |
| `core/providers/vad/base.py` | VAD 基类，ONNX 本地推理 |
| `core/providers/intent/base.py` | Intent 基类 |
| `core/providers/memory/base.py` | Memory 基类 |

### Java 管理 API (manager-api)

| 文件 | 说明 |
|---|---|
| `modules/model/entity/ModelConfigEntity.java` | 模型配置实体（ai_model_config 表） |
| `modules/model/entity/ModelProviderEntity.java` | 供应器实体（ai_model_provider 表） |
| `modules/model/entity/ModelTemplateEntity.java` | 模型模板实体（ai_model_template 表） |
| `modules/model/entity/ModelTemplateFieldEntity.java` | 模板字段实体（ai_model_template_field 表） |
| `modules/model/entity/ModelConfigParamEntity.java` | 模型参数实体（ai_model_config_param 表） |
| `modules/model/entity/ModelConfigSecretEntity.java` | 模型密钥实体（ai_model_config_secret 表） |
| `modules/model/service/impl/ModelConfigServiceImpl.java` | 模型配置 CRUD + 缓存 |
| `modules/model/service/impl/ModelRuntimeAssembler.java` | **核心**：运行时配置组装器（540 行） |
| `modules/model/service/impl/ModelCatalogServiceImpl.java` | 模型目录（前端模板下拉） |
| `modules/model/service/impl/ModelProviderServiceImpl.java` | 供应器管理 |
| `modules/model/controller/ModelController.java` | 模型管理 API 控制器 |
| `modules/model/controller/ModelProviderController.java` | 供应器管理 API 控制器 |
| `modules/config/service/impl/ConfigServiceImpl.java` | **核心**：设备配置输出（含 `getAgentModelsConfig()`） |
| `modules/timbre/service/impl/TimbreServiceImpl.java` | TTS 音色管理 |
| `modules/agent/entity/AgentEntity.java` | 智能体实体，含 9 个 model_id 字段 |

### 数据库迁移（关键模型相关）

| 文件 | 说明 |
|---|---|
| `db/changelog/202606241130.sql` | 阿里百炼通义千问模板定义（模板字段 schema） |
| `db/changelog/202606232230.sql` | 模板状态字段（deprecated/selectable/hidden/...） |
| `db/changelog/202606251815.sql` | 豆包 Seed TTS 2.0 迁移（DELETE 旧音色、INSERT 唯一音色） |
| `db/changelog/202606251100.sql` | 火山两个 TTS 模板元数据更新 |
| `db/changelog/202606250930.sql` | 模型模板定义（初始化/更新模板元数据） |
| `db/changelog/202606251720.sql` | TTS 音色种子数据迁移 |
| `db/changelog/202606251645.sql` | 模型模板设置提示和限制信息 |
| `db/changelog/202606251130.sql` | 模型供应器（ai_model_provider）初始化 |
| `db/changelog/202606251115.sql` | 模型模板字段批量更新 |
| `db/changelog/202606251055.sql` | 模型模板定义初始化 |
| `db/changelog/202606251010.sql` | 模型配置和模板标准化 |
| `db/changelog/202602061650.sql` | tts_volume/tts_rate/tts_pitch 列 |

### 前端 (manager-web)

| 文件 | 说明 |
|---|---|
| `src/views/ModelConfig.vue` | **主模型管理页**（9 个模型类型 tab，表格 + 编辑/测试/基准） |
| `src/views/ProviderManagement.vue` | **供应器管理页**（10 个模型类型 + Plugin） |
| `src/components/ModelEditDialog.vue` | **模型编辑弹窗**（动态字段 schema，含大量硬编码） |
| `src/components/AddModelDialog.vue` | 旧版加模型弹窗 |
| `src/components/TtsModel.vue` | **TTS 音色管理弹窗**（含 clone provider 特殊处理） |
| `src/components/ModelBenchmarkDialog.vue` | 性能基准测试弹窗 |
| `src/components/ModelConfigAssistant.vue` | AI 配置助手侧面板 |
| `src/components/TtsAdvancedSettings.vue` | TTS 高级设置抽屉 |
| `src/components/ProviderDialog.vue` | 供应器编辑弹窗 |
| `src/apis/module/model.js` | 模型 API 客户端（20+ 端点） |
| `src/apis/module/timbre.js` | 音色 API 客户端 |

### 前端 (manager-mobile)

| 文件 | 说明 |
|---|---|
| `src/pages/agent/edit.vue` | 智能体编辑页（含模型类型选择器） |
| `src/pages/agent/speedPitch.vue` | TTS 速度/音高/音量设置 |
| `src/api/agent/agent.ts` | 智能体 API（含模型选择接口） |

---

## 附录：config.yaml 中的模型配置段完整列表

### ASR（15 个配置段）
```
SherpaASR, SherpaParaformerASR, DoubaoASR, DoubaoStreamASR, DoubaoStreamASRV2,
TencentASR, AliyunASR, AliyunStreamASR, BaiduASR, OpenaiASR, GroqASR,
VoskASR, Qwen3ASRFlash, XunfeiStreamASR, AliyunBLStreamASR
```

### TTS（1 个配置段）
```
DoubaoSeedTTS2Stream
```

### LLM（14 个配置段）
```
AliLLM, AliAppLLM, DoubaoLLM, DeepSeekLLM, ChatGLMLLM, OllamaLLM, DifyLLM,
GeminiLLM, CozeLLM, VolcesAiGatewayLLM, LMStudioLLM, HomeAssistant,
FastgptLLM, XinferenceLLM, XinferenceSmallLLM
```

### VLLM（3 个配置段）
```
ChatGLMVLLM, QwenVLVLLM, XunfeiSparkLLM  ← 第三个实为 LLM
```

### VAD（1 个配置段）
```
SileroVAD
```

### Memory（4 个配置段）
```
mem0ai, powermem, nomem, mem_local_short
```

### Intent（3 个配置段）
```
nointent, intent_llm, function_call
```

---

> 相关文档：
> - [[system-issues-and-optimization-backlog]] — 系统问题与优化清单（20 个问题）
> - [[doubao-tts-voice-selection-analysis]] — 豆包 TTS 音色选择问题
> - [[volume-system-analysis]] — 音量系统混乱分析
> - [[voice-pipeline-latency-analysis]] — 语音管道延迟分析
> - [[memory-summary-display-issue-analysis]] — 记忆总结显示问题
