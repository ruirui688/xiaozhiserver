---
status: canonical
owner: docs
last_verified: 2026-07-12
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

- [当前仓库与官方上游能力审计](./current-vs-upstream-capability-audit.md) - 对比具体提交下的官方、本地已提交和待集成能力，说明差异原因与同步策略。
- [FunASR / SenseVoiceSmall 资源与并发基准](../models/funasr-sensevoice-resource-benchmark.md) - 保留原路径以避免破坏现有引用，后续实质更新时按报告元数据规范治理。
