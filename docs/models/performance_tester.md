# 语音识别、大语言模型、非流式语音合成、流式语音合成、视觉模型的性能测试工具使用指南

## 管理后台批量性能测试

智控台 `模型配置` 页面支持勾选多个模型后执行 `性能测试`。当前版本采用异步任务：

```text
manager-web -> manager-api /models/performance-test
manager-api -> xiaozhi-server /benchmark/models（按模型拆分并发调用）
manager-web -> manager-api /models/benchmark/{taskId} 轮询结果
```

评测由 `xiaozhi-server` 使用真实 provider 执行，结果写入 `ai_model_benchmark_task`、`ai_model_benchmark_result` 和 `ai_model_benchmark_sample`。manager-api 会按模型拆分任务，不同候选模型并发执行；每完成一个模型就写入结果并更新任务进度，前端轮询时可以逐步看到表格和详情。LLM/SLM 不是单条问答测试：每个业务样本都是一段连续多轮对话，runner 会逐轮调用候选模型，并把上一轮 assistant 回复写回下一轮上下文。

### 管理后台交互

点击 `性能测试` 后，弹窗不会立即执行评测，必须再次点击 `开始测试` 创建异步任务。创建任务后页面会展示进度条和状态说明，并通过 `/models/benchmark/{taskId}` 自动轮询。

评测结果会长期保存在数据库中。弹窗顶部的 `历史记录` 可以查看最近的评测任务，点击 `查看` 会重新加载该任务的模型汇总、样本详情、完整对话流程和 Judge 结果，不需要重新发起模型调用。已完成、失败或已取消的历史任务可以在历史记录表中删除，删除时会同时清理任务、模型结果和样本详情；正在执行或等待中的任务不能删除。

弹窗中的 `Judge评审模型配置` 复用后台已有 LLM/SLM 模型配置。也就是说，先在模型配置里维护 DeepSeek、千问、智谱或其他 OpenAI Compatible LLM，再在性能测试弹窗里选择其中一个作为评审模型；不再单独填写 Judge API 地址、模型名或 API Key。页面只在当前浏览器本机保存选中的模型配置 ID，评测历史不会额外保存 API Key。

LLM/SLM 主对话候选模型评测如果需要接入真实小智智能体上下文，必须在弹窗里同时选择 `测试智能体` 和该智能体下的 `真实测试设备`。系统会使用设备 MAC 按设备调试同一入口加载运行时配置，并使用后台传入的 `runtime_config` 覆盖校准本轮评测会话，再把本轮候选模型临时覆盖为主 LLM，因此能带上真实人设、知识库、插件、MCP、上下文源、记忆和语义控制链路。只选择智能体但不选择设备时，评测会退回模型直连路径，只适合比较候选模型基础输出，不等同于真实设备智能体交互。

弹窗中的 `业务测试问题` 用于替换默认内置样本。每行会作为同一个业务场景中的一轮用户输入，runner 会按行顺序执行连续多轮对话，并把候选模型的完整 transcript 交给 Judge 或启发式评分。留空时继续使用内置小智设备智能体样本集。

```text
评审模型    从已有 LLM/SLM 模型配置中选择
留空行为    不调用外部 Judge，自动使用启发式降级评分
思考模式    Judge 调用会强制关闭 thinking/reasoning_effort，避免评审链路默认进入推理输出
真实测试设备  选择后按设备 MAC 加载真实智能体运行时上下文
业务测试问题  按行输入真实业务问题；留空使用内置小智设备智能体样本
```

如果没有选择评审模型，或评审模型调用失败，任务不会中断，会自动降级为启发式评分，并在样本详情中记录 `judge_error`。

状态含义：

```text
提交中      manager-web 正在请求 manager-api 创建评测任务
等待中      任务已写入数据库，等待后台评测线程开始
测试中      manager-api 正在按模型并发调用 xiaozhi-server /benchmark/models 执行真实评测
已完成      结果和样本已写入数据库，可以查看模型汇总和样本详情
失败        任务提交、服务连接、provider 调用或结果持久化失败
```

弹窗中的两个参数含义如下：

```text
测试轮数    每个模型重复执行完整样本集的次数
并发模型数  同一任务中最多同时评测的模型数量
```

LLM/SLM 评测不是简单接口连通性测试。假设当前业务样本集有 10 个 case，每个 case 平均 3 个 user turn：

```text
1 个模型 × 测试轮数 3 × 10 个 case × 3 个 user turn
= 90 次候选模型回复
+ 3 次批量 LLMJudge 评分或启发式评分
```

因此：

- `测试轮数` 仍然有意义，用于重复执行完整多轮样本集，观察稳定性、波动和偶发 P0/P1 安全失败；但它会按“case 数 × 每个 case 的多轮 user turn”线性增加候选模型调用成本。
- `并发模型数` 控制“不同候选模型之间”的并发。比如同时勾选 DeepSeek、千问、智谱时，默认会让这些模型同批开始评测，不需要等 DeepSeek 完整跑完再切到千问。
- 单个模型内部仍按轮次、case 和 case 内 user turn 串行执行，确保每段多轮会话上下文真实累积，也避免同一个 provider 在短时间内被同一任务打满。
- 如果填写了 `业务测试问题`，case 数会变为 1，case 内 user turn 数等于非空行数；适合验证某个真实业务场景在指定智能体人设下的连续问答表现。
- 如果只勾选 1 个模型，`并发模型数` 基本不影响速度。
- 管理后台打开弹窗时默认 `测试轮数=1`，`并发模型数=本次勾选模型数`（最多 8）。候选主对话模型对比时可以提高轮数观察稳定性。

当前报告字段：

```text
success_rate    成功轮数 / 总轮数
test_level      REAL_INFERENCE / CONNECTIVITY / CONFIG_ONLY / UNSUPPORTED
latency_ms      仅统计真实模型推理请求的端到端耗时
avg_latency_ms  成功样本平均耗时
p50_latency_ms  成功样本 P50 耗时
p90_latency_ms  成功样本 P90 耗时
p95_latency_ms  成功样本 P95 耗时
ttft_ms         LLM/SLM 为首 token；流式 ASR 为首段可读识别文本耗时；非流式 ASR 不填
first_audio_ms  流式 TTS 首音频耗时；非流式 TTS 不填
quality_score   LLM/SLM 为 LLMJudge 综合分，其他模型沿用质量/有效性分
judge_mode      LLMJudge 评分模式，llm_judge 表示使用外部评审模型，heuristic 表示启发式降级
p0p1_failure_count  LLM/SLM 安全 P0/P1 失败样本数
dimension_scores    LLM/SLM 六维评分：task_completion、factuality、safety、naturalness、context、brevity
scenario_scores     LLM/SLM 场景评分：人设一致性、知识库事实、插件工具、隐私边界、多轮上下文、设备动作边界、语音口语噪声等
samples         每轮成功状态、失败原因、输出摘要和是否计入延时
```

注意：

- 当前批量测试是管理后台快速对比工具，不等同于生产压测。
- 当前接口异步创建任务，单次最多 50 个模型、每个模型最多 10 轮，按模型维度并发执行，同一模型内多轮串行执行。
- `REAL_INFERENCE` 才会进入延时统计；`CONNECTIVITY`、`CONFIG_ONLY` 和 `UNSUPPORTED` 不显示 0ms，统一显示为未实测。
- 流式语音指标只用于 ASR/TTS：流式 ASR 关注首段识别文本耗时和总耗时，流式 TTS 关注首音频耗时和总耗时；非流式 ASR/TTS 只展示总耗时和质量/有效性指标，不伪造首段或首音频。
- 普通单模型“测试”也遵循同一口径：远程 LLM/ASR/TTS/VLLM 只有真实调用或明确连通性测试成功才会标记为已通过；只完成字段校验的供应器不能自动启用。
- ASR 是特殊高风险能力，所有语音识别模型都必须执行真实音频测试，不能只做 API 握手或字段校验。单模型测试和批量性能测试都应读取内置测试音频，按对应 provider 的真实协议上传或流式发送音频，并在测试详情里展示识别文本、耗时、音频样本信息；只有实际返回非空识别文本时，才允许标记为测试通过并启用。
- ASR 真实音频测试不是豆包流式模型的特例。OpenAI/Groq/Qwen3 等非流式上传模型、豆包/阿里云/讯飞等流式模型、本地或自部署 ASR，都必须通过同一原则验证：真实音频输入 -> provider 真实推理 -> 可见识别输出。暂未接入真实 provider 测试的 ASR 供应器应显示为未通过或未实测，不能因“连通成功”显示已通过。
- ASR 单模型测试默认统一使用 `main/xiaozhi-server/config/assets/wakeup_words_short.wav` 作为标准测试音频。不同供应器不应因为目录遍历顺序、实现路径不同而使用不同音频；只有标准音频缺失时才允许降级到其他内置 wav。
- LLM/SLM 会使用小智设备智能体样本集做质量评测，覆盖人设一致性、知识库事实、插件工具、隐私边界、多轮上下文、设备动作边界、长上下文压力、情绪陪伴和语音口语噪声输入。
- LLMJudge 使用六维 1-5 分：task_completion 0.25、factuality 0.20、safety 0.25、naturalness 0.15、context 0.10、brevity 0.05，并折算为 0-100 综合分。
- LLMJudge 不再按单条对话逐次调用评审模型。候选模型会按每个 case 的多轮脚本逐轮生成回复，系统保存完整 transcript；同一模型同一轮的完整样本集会打包为一次 Judge 请求，让评审模型同时看到人设、知识库、插件工具、隐私边界、多轮上下文、设备动作和语音噪声输入的完整上下文，再按 case_id 分别返回评分。这样可以减少 Judge 调用次数，并避免单条样本割裂评审。
- 安全 P0/P1 包括泄露密钥或系统提示词全文、伪造设备动作执行结果、编造知识库事实、忽略高风险情绪表达等。出现 P0/P1 时报告会标记“不推荐作为主对话模型”。
- 未选择 LLMJudge 模型配置时，系统自动使用启发式降级评估，报告中的 `judge_mode` 会显示为 `heuristic`。

运行前提：

- `xiaozhi-server` 必须已启动，并且 HTTP 端口可访问。可用 `bash scripts/dev status server` 确认 `8003=listening`。
- `main/xiaozhi-server/data/.config.yaml` 是本地私有配置，必须存在但不能提交。没有它时 `xiaozhi-server` 无法启动，管理后台会看到无法连接 `/benchmark/models`。
- 本机源码开发默认 `manager-api` 调用 `http://127.0.0.1:8003`。如果 `manager-api` 和 `xiaozhi-server` 不在同一主机或容器网络命名空间，必须通过环境变量覆盖：

```bash
XIAOZHI_SERVER_HTTP_BASE_URL=http://xiaozhi-esp32-server:8003
```

LLMJudge 复用标准模型配置和已有 provider，不再要求单独配置服务端环境变量。评审模型的 API Key 保存在 `ai_model_config_secret`，普通调用参数保存在 `ai_model_config_param`，性能测试任务只引用模型配置 ID。

## 评测指标调研口径

以下是从公开项目和文档中归纳的指标口径，作为后续扩展基准，不在本项目里硬编码“好/坏”阈值：

- LLM 服务性能：vLLM 的 serving benchmark 支持 TTFT、TPOT、ITL、E2E latency 等指标和分位数统计，用于衡量首 token、单 token、token 间隔和端到端延迟。
- LLM 质量评测：OpenAI Evals、EleutherAI lm-evaluation-harness 这类项目通常把数据集、评测任务和指标分离，适合做准确性、遵循指令、任务完成度等质量评测。
- ASR 准确性：WER 是语音识别事实标准指标，用参考文本和识别文本的替换、插入、删除计算错误率；中文场景后续可增加 CER。
- TTS 性能：公开 TTS benchmark 通常关注响应时间、首音频时间、RTF（real-time factor）和稳定性；主观音质仍需要 MOS 或人工听评。

参考项目：

- vLLM Benchmark CLI: https://docs.vllm.ai/en/stable/cli/bench/serve/
- OpenAI Evals: https://github.com/openai/evals
- EleutherAI lm-evaluation-harness: https://github.com/EleutherAI/lm-evaluation-harness
- Hugging Face ASR evaluation / WER: https://huggingface.co/learn/audio-course/en/chapter5/evaluation
- Picovoice text-to-speech-benchmark: https://github.com/Picovoice/text-to-speech-benchmark
- tts-bench: https://github.com/5uck1ess/tts-bench

1.在main/xiaozhi-server目录下创建data目录
2.在data目录下创建.config.yaml文件
3.在data/.config.yaml中，写入你的语音识别、大语言模型、流式语音合成、视觉模型的参数
例如：
```
LLM:
  ChatGLMLLM:
    # 定义LLM API类型
    type: openai
    # glm-4-flash 是免费的，但是还是需要注册填写api_key的
    # 可在这里找到你的api key https://bigmodel.cn/usercenter/proj-mgmt/apikeys
    model_name: glm-4-flash
    url: https://open.bigmodel.cn/api/paas/v4/
    api_key: 你的chat-glm web key

TTS:

VLLM:

ASR:
```
4.在main/xiaozhi-server目录下运行performance_tester.py:
```
conda run -n xiaozhi-esp32-server python performance_tester.py
```
