# 语音识别、大语言模型、非流式语音合成、流式语音合成、视觉模型的性能测试工具使用指南

## 管理后台批量性能测试

智控台 `模型配置` 页面支持勾选多个模型后执行 `性能测试`。当前版本采用异步任务：

```text
manager-web -> manager-api /models/performance-test
manager-api -> xiaozhi-server /benchmark/models
manager-web -> manager-api /models/benchmark/{taskId} 轮询结果
```

评测由 `xiaozhi-server` 使用真实 provider 执行，结果写入 `ai_model_benchmark_task`、`ai_model_benchmark_result` 和 `ai_model_benchmark_sample`。

当前报告字段：

```text
success_rate    成功轮数 / 总轮数
test_level      REAL_INFERENCE / CONNECTIVITY / CONFIG_ONLY / UNSUPPORTED
latency_ms      仅统计真实模型推理请求的端到端耗时
avg_latency_ms  成功样本平均耗时
p50_latency_ms  成功样本 P50 耗时
p90_latency_ms  成功样本 P90 耗时
p95_latency_ms  成功样本 P95 耗时
samples         每轮成功状态、失败原因、输出摘要和是否计入延时
```

注意：

- 当前批量测试是管理后台快速对比工具，不等同于生产压测。
- 当前接口异步创建任务，单次最多 50 个模型、每个模型最多 10 轮，按模型维度并发执行，同一模型内多轮串行执行。
- `REAL_INFERENCE` 才会进入延时统计；`CONNECTIVITY`、`CONFIG_ONLY` 和 `UNSUPPORTED` 不显示 0ms，统一显示为未实测。
- 普通单模型“测试”也遵循同一口径：远程 LLM/ASR/TTS/VLLM 只有真实调用或明确连通性测试成功才会标记为已通过；只完成字段校验的供应器不能自动启用。
- 当前只能统计现有后端测试链路可观测到的端到端耗时和成功率；流式 LLM 的 TTFT、tokens/s，TTS 的首音频时间和 RTF，ASR 的 WER 需要专门样本集和协议级采集。

运行前提：

- `xiaozhi-server` 必须已启动，并且 HTTP 端口可访问。可用 `bash scripts/dev status server` 确认 `8003=listening`。
- `main/xiaozhi-server/data/.config.yaml` 是本地私有配置，必须存在但不能提交。没有它时 `xiaozhi-server` 无法启动，管理后台会看到无法连接 `/benchmark/models`。
- 本机源码开发默认 `manager-api` 调用 `http://127.0.0.1:8003`。如果 `manager-api` 和 `xiaozhi-server` 不在同一主机或容器网络命名空间，必须通过环境变量覆盖：

```bash
XIAOZHI_SERVER_HTTP_BASE_URL=http://xiaozhi-esp32-server:8003
```

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
