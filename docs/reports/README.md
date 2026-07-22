---
status: canonical
owner: docs
last_verified: 2026-07-23
source_paths:
  - docs/reports/
---

# 分析与评测报告

本目录保存仍有工程价值的阶段性评测、审计和问题分析。报告描述特定时间点的事实，不是当前架构或强制规范的来源。

新增报告必须：

- 使用 `status: report`，填写责任领域、验证日期和主要事实来源。
- 明确测试环境、样本、版本、结论边界和未验证项。
- 将仍然生效的架构约束同步到 `docs/architecture/`，将强制规则同步到 `docs/standards/`。
- 在结论失效或问题解决且不再具有排障价值后删除，由 Git 历史保留追溯记录。

当前报告入口：

- [2026-07 功能可靠性维护报告](./functional-reliability-maintenance-2026-07.md) - 记录长期记忆、知识切片、智能体配置和小程序工作流的故障根因、修复边界与验证限制。
- [当前仓库与官方上游能力审计](./current-vs-upstream-capability-audit.md) - 对比具体提交下的官方、本地已提交和待集成能力，说明差异原因与同步策略。
- [FunASR / SenseVoiceSmall 资源与并发基准](../models/funasr-sensevoice-resource-benchmark.md) - 保留原路径以避免破坏现有引用，后续实质更新时按报告元数据规范治理。
- [语音管道延迟分析](../models/voice-pipeline-latency-analysis.md) - 2026-06 初始分析及 2026-07 真机复测，结论受设备、模型和部署版本限制。
- [语义打断与端到端语音模型演进分析](../architecture/semantic-interruption-and-e2e-voice-model-analysis.md) - 语义打断现状和演进路线的阶段性分析。
- [豆包 TTS 音色选择分析](../models/doubao-tts-voice-selection-analysis.md) - 2026-06 音色种子数据与运行链路诊断。
- [音量系统分析](../models/volume-system-analysis.md) - 2026-06 设备音量、TTS 响度和浏览器音量边界诊断。
- [2026-06 系统问题与优化审计](../reference/system-issues-and-optimization-backlog.md) - 历史问题快照，不能直接当作当前 backlog；实施前必须回查源码和 canonical 文档。
