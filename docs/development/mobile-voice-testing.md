---
status: supporting
owner: voice-runtime
last_verified: 2026-10-15
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

探针默认输出 `dry_run`，不读取用户凭据、不建立网络连接。`PYTHONNOUSERSITE=1` 防止宿主用户目录中的旧版依赖覆盖 Conda 依赖；不需要安装或升级系统包。Android 工程运行 `bash tools/test-voice-state.sh`、`bash tools/test-voice-readiness.sh`、`node tools/finance-test.cjs`；UI 与离线 Gradle 构建按其 README 执行。不要把 UI 模拟当作已经连接生产。

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

2026-10-15 本轮开始时生产为 `d84194c7-lite`，已包含手机接口。故障检查发现初始赠额实际配置为关闭、金额 0，发放池无可用余额；测试账户无赠额流水，ASR 被 `ALLOWANCE_EXHAUSTED` 拒绝，不是已经消费完 100。麦克风权限和录音状态不能证明真实转录成功。

本轮 App 0.11 新增一次性额度预检、服务端真正就绪协商、明确错误停止和本地音量/上传计数；服务端新增有界赠额重试及运营定向补发。初始赠额规则与池预算、账号实际到账分别验收；禁止用直接改余额、绕过准入或无限预算通过测试。

2026-10-15 发布前集成验证记录：

| 验证 | 结果 |
| --- | --- |
| 项目 Conda 全量 Python 测试与 compileall | 1363 项通过，6 个既有依赖警告 |
| `mvn -B test package` | 925 项，0 失败/错误，35 项跳过；跳过项依赖未配置的专项数据库或既有 Disabled 标记 |
| 初始赠额真实 MySQL 8.4 专项 | 20 项通过，使用隔离合成数据库 |
| 完整 Spring Boot 启动与重启 | 233 条迁移成功，新迁移只执行一次，HTTP 可用，匿名赠额管理请求被拒绝 |
| Web 单元测试与构建 | 468 项通过，构建成功；保留既有 CSS 顺序/包体积警告 |
| 部署默认值、保留旧覆盖与显式覆盖测试，Shell/Compose 检查 | 通过；无生产凭据用于测试 |
| Android 状态、额度预检、认证/注册/完整 UI 与离线构建 | 通过，详细记录在 App 工作区 `console-0.11-test-report.md` |

文档全仓检查未通过：既有生成事实过期、maintenance-agent README 链接失效、运行产物内第三方链接及两篇未被索引文档共 9 项；没有为通过检查清理用户运行产物或改动无关模块。本轮修改已通过 `git diff --check`。测试容器和合成数据库已清理，镜像与构建缓存保留。

截至本轮发布前验证，0.11 已完成离线构建和 UI 回归，但真机覆盖安装被系统拒绝，仍为 0.10。真实 ASR/LLM/TTS 与日程到期播报未验收。生产更新仍须按[唯一发布流程](../deployment/private-docker-release.md)，先备份再迁移和更新配套 Java/Python 镜像；不能只替换 APK。最终发布与真机结果须另记录，不能把上述构建结果改称端到端通过。

### 2026-10-15 后续真机复核

生产已更新至 `b49b0a14-lite`，业务容器及数据库、Redis 健康。vivo 原位安装 0.11 成功，包管理器确认版本，原登录与测试空间写权限保留。通过 App 原有 USER 请求桥为专用测试智能体追加 `device_reminder`，回读确认原三个插件仍保留；未导出 Token、修改认证白名单或直接写生产数据库。

用户运营操作后，测试账户只有一笔普通 `GRANT 100`；该账户没有注册赠额记录，不能把本次到账算作注册自动赠额验收，也未再次补发。池总额为 10801、已分配 901、可用 9900，是用户增加 10000 后的结果，未擅自调整。语音测试后账户快照为已用 0.046761、预占 0.416618；预占不是最终扣费。

真机已收到 ready，显示 AEC 启用和录音上传；集成复核实际页面保留 10 条 STT、2 段回复文字，并显示首段回复音频入缓冲。同期测试空间供应商计量有 ASR SUCCESS 13、LLM SUCCESS 6/CANCELLED 9、TTS SUCCESS 2/UNKNOWN 2；这些是观察窗口的调用计数，不是一一对应的完整对话次数。证明基础识别、回复及音频下行已有真实证据，不证明全部回复完成、AEC 实际抑制效果或用户听到。UNKNOWN 和取消仍需按具体调用继续核查，不能仅按聚合数归因为回声。

普通对话后测试空间没有日程记录。本次尚未收到启用插件后的提醒测试语音，创建、查询、取消及到期播报仍待真机验收；测试通话已结束，不保留后台录音。未创建模拟提醒冒充语音验收。

本次重跑项目 Conda 手机认证/生命周期/探针/日程工具专项 41 项通过；Android 状态 17、ready 23、服务策略 53 项通过。只更新验收文档，未修改业务源码，未重复全模块构建。安装检查生成的临时 UI 文件已清理，未删除用户数据或构建缓存。

### 2026-10-15 音频与日程本地修复（未发布）

后续用户提醒语音的 ASR 正确，但模型把“两分钟后”换算成了 2025 年的 Unix 时间戳，实际为 2026 年；因此触发“至少晚于现在五秒”校验，未创建安排。现在模型传结构化 `delay`，服务器计算绝对时间；具体日期改用 `remind_at` 与明确时区，旧时间戳只作运行时兼容。同调用重试保留原时间，缓存有界且不承担调度；具体合同见[任务中心](../architecture/reminder-task-center.md#5-安全并发与投递一致性)。

`% device_reminder` 是工具执行进度，不是用户转录。服务端保留兼容 `stt` 外形并增加 `source=tool`，App 0.12 独立显示处理状态，兼容旧服务器严格的工具进度格式，不再污染用户对话或首音频计时。

声音故障已定位至 App 捆绑的 Concentus Java：24 kHz HYBRID 解码时，CELT 降采样分支覆盖了已有 SILK 人声低频。按固定上游版本的 C 实现恢复定点累加和饱和处理，未改语速、采样率或生产音色配置。仓库外 App 保留源码补丁、固定提交、可复现 JAR 构建和旧库负对照；旧 JAR 与历史 APK 保留，新包不引用旧库。

本轮最终集成验证：项目 Conda 全量 Python **1428 项通过**，6 个既有依赖警告；全模块 `compileall` 通过。包括结构化时间、非法输入、夏令时、重试/并发/取消/容量边界、真实 chat→工具执行器的受控旅程，以及工具显示来源测试。受控旅程替换了外部模型和管理接口，不等于生产创建或到期播报验收。

Android 状态/ready/服务策略/消息来源分别 17/23/53/13 项通过，认证、注册和完整 UI 合成测试通过；离线 `assembleDebug lintDebug assembleRelease` 成功，Lint 0 错误、1 个既有警告。根集成者独立使用仓库公开中文语音复测解码波形，修复库与标准解码差值 SNR 60.66 dB、相关约 0.99999957；不是用户录音，也不是仅以非静音判断通过。详细编解码用例、APK SHA 与构建记录在仓库外 App 的 `console-0.12-test-report.md` 和 `tools/CONCENTUS.md`。

两个子智能体分别负责仓库外 App 编解码与 Python 日程插件/时间模块/测试；集成者负责消息分类、UI、文档、独立波形复验与全量回归。未修改 Java、数据库或 uni-app，本轮不重复其构建。保留原有验收文档改动及历史产物，无新增私密配置，无清理用户文件或缓存，无 commit/push。

**按用户要求，本轮只本地修复，不安装手机，也不部署服务器。** 0.12 测试包仅保存在电脑；修复后的真实听感、AEC、连续对话及日程创建/查询/取消/到期播放仍待配套版本发布后验收，不能把离线回归描述为已在线修复。
