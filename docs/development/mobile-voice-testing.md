---
status: supporting
owner: voice-runtime
last_verified: 2026-10-13
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/mobile/
  - main/xiaozhi-server/core/websocket_server.py
  - main/xiaozhi-server/tools/mobile_voice_probe.py
  - main/xiaozhi-server/tests/test_device_reminder_journey.py
  - main/xiaozhi-server/tests/test_mobile_voice_lifecycle.py
---

# 手机语音与日程链路验收

架构事实见[设备交互链路 §14](../architecture/device-interaction-chain.md#14-手机连续语音调试2026-10-13)，调度预算及回执边界见[提醒任务中心](../architecture/reminder-task-center.md)。Android 工程位于 Windows `D:\Codex_ws\手机app`（WSL `/mnt/d/Codex_ws/手机app`），不属于服务器 Git 仓库。

## 验证层次

| 层次 | 实际经过什么 | 不证明什么 |
| --- | --- | --- |
| Android 状态与 UI 回归 | 生产使用的 Java 取消代际、上传门控；WebView 界面与模拟原生桥接 | 手机麦克风、网络、声学效果 |
| Python 手机生命周期 | 真实 ConnectionHandler、hello/listen、忙闲提醒准入、关闭清理 | 供应商识别/生成结果 |
| 日程模型请求回归 | 真实 chat、工具注册/调用、实际模型请求格式；外部模型与管理响应受控 | 模型能正确理解任意自然语言、真实数据库落库 |
| 手机接口 MySQL 回归 | 跨实例并发创建、额度、锁等待撤权、签名失败回滚、索引 | 公网与手机音频 |
| 本地协议探针回归 | 回环 WebSocket、真实 Opus 编解码、无前置 STT 的主动提醒接收 | 真正的到期调度或物理播放 |
| 显式真实联调 | USER API 签发、WSS、预录音频、真实 ASR/LLM/工具/TTS、提醒回传 | Android AEC、实际扬声器响声 |
| 真机验收 | 手机麦克风、音频路由、AEC、扬声器与完整服务链路 | 后台唤醒；当前仍只支持前台通话 |

日程不会每轮全部注入系统提示词。普通讨论未来时间不自动创建安排；模型明确调用 `device_reminder` 才访问日程。列表以 `untrusted_device_reminders` 数据块进入工具返回，不能把提醒标题里的指令当系统指令。创建/取消必须依据管理服务成功结果确认。

## 离线回归

在服务器 `main/xiaozhi-server` 运行：

```bash
PYTHONNOUSERSITE=1 PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 \
  conda run -n xiaozhi-esp32-server python -m pytest \
  tests/test_mobile_voice_auth.py tests/test_mobile_voice_lifecycle.py \
  tests/test_mobile_voice_probe.py tests/test_device_reminder_journey.py
PYTHONNOUSERSITE=1 conda run -n xiaozhi-esp32-server python tools/mobile_voice_probe.py
```

探针默认输出 `dry_run`，不读取用户凭据、不建立网络连接。`PYTHONNOUSERSITE=1` 防止宿主用户目录中的旧版依赖覆盖 Conda 依赖；不需要安装或升级系统包。Android 工程运行 `bash tools/test-voice-state.sh`、`node tools/finance-test.cjs`；UI 与离线 Gradle 构建按其 README/0.7 报告执行。不要把 UI 模拟当作已经连接生产。

## 显式真实语音探针

先确认 Java 手机接口与 Python 手机认证为配套版本、服务有有效 ASR/LLM/TTS 通道，测试智能体启用了 `device_reminder`。使用专门测试账号、测试空间与测试智能体：语音可能触发该智能体其他工具、产生记忆/日志并消费服务额度。没有预算或账号授权时，不执行真实探针。

输入 WAV 必须为单声道、16 kHz、PCM16，最长 30 秒。例如自己录制“请一分钟后提醒我联调测试”，不要使用用户私密录音。工具追加短静音，按 60 ms raw Opus 实时发送，不使用文本注入冒充 ASR。

从安全凭据来源向本进程环境提供普通 USER 的 `MOBILE_TEST_TOKEN`，禁止把真实 token 写进命令行、文档或仓库。一次验收示例：

```bash
PYTHONNOUSERSITE=1 conda run -n xiaozhi-esp32-server python tools/mobile_voice_probe.py \
  --execute --api https://your-service.example/xiaozhi \
  --workspace-id TEST_WORKSPACE --agent-id TEST_AGENT \
  --installation-id 3ac017a1-6b0e-46de-af52-cfbd38cd0302 \
  --wav /absolute/path/test-utterance.wav --wait-seconds 120 --expect-reminder
```

固定复用测试安装 UUID，避免每次新增手机身份。若 WSS 与 API 域名不同，需通过 `--ws-host` 显式指定可信 WSS 主机；不接收 URL 中的凭据、不跟随 API 重定向。语音连接凭据仅在请求头发送，原生 App 也遵循此约定。探针不会监听麦克风，也不播放或保存回复音频。

探针最长观察 180 秒，只有实际上传完整音频、收到 STT、解码回复音频并观察到 TTS stop 才可通过；`--expect-reminder` 还要求“日程提醒：”文本后的可解码音频。输出仅含计数与错误类型，无 token、转录正文或回复正文。它没有验证提醒记录 ID 与本次音频的因果关系：仍须在日程列表与执行记录中核对本次安排的时间、目标和状态，不能以任意一条提醒回传冒充本次安排成功。

测试会创建/复用持久手机设备并可能留下日程、对话和扣费记录，工具不自动清除真实业务数据；用用户管理入口取消测试安排、按需清理测试身份。要验查询和取消，可分别输入相应 WAV，再在列表检查，不在测试程序中绕过授权直接改生产库。

## 真机验收与发布状态

安装测试 APK 后，用相同测试空间开始通话，依次核对创建确认、安排记录、保持前台在线空闲、到期播报、执行记录。覆盖静音、点击打断、重复点击开始/结束、无 AEC 回退、取消日程、断线、1013 繁忙和额度不足。手机关闭通话后就是离线，本轮没有后台提醒或推送唤醒能力。

2026-10-13 已发布的 `94718079-lite` 包含提醒任务中心，但不包含本轮手机接入修改。App 0.7 构建完成不等于手机接口已经发布，也不代表真实语音验收通过。生产更新仍须按[唯一发布流程](../deployment/private-docker-release.md)，先备份再迁移和更新配套 Java/Python 镜像；不能只替换 APK。
