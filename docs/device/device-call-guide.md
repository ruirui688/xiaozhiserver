# 设备间相互呼叫插件使用指南

## 概述

设备呼叫功能允许两个已配置设备之间通过语音/数据通道进行双向通信。设备A呼叫设备B时，系统通过以下流程实现：

```
设备A → 授权校验 → MQTT网关 → 设备B远程唤醒 → 建立连接 → 通话建立
```
## 使用这个功能的前提条件
1. 你必须要有至少两个设备，每个设备型号必须是`ESP32-S3`，因为只有`ESP32-S3`才支持远程唤醒功能。
2. 你的设备必须要有`两个麦克风`。但是如果你的设备只有`单个麦克风`，你只是想体验一下这个功能，也是可以的，但是会有强烈的卡顿感。
3. 你必须使用全模块部署，因为需要智控台管理设备权限和通信；生产环境按[生产部署唯一入口](../deployment/private-docker-release.md)操作。
4. 你必须安装并配置好 2026 年 5 月 27 日以后的 MQTT 网关版本，具体步骤见本文“MQTT 网关”一节。

以上是使用这个功能的硬性条件，接下来会详细介绍。

## 配置步骤

### 第一步：开启通讯录功能

1. 确认你的智控台版本是`0.9.4`或以上版本。
2. 登录智控台后台
3. 进入 **系统功能配置**
4. 在左侧功能列表中勾选 **通讯录**
5. 点击 **保存配置** 确认

### 第二步：配置设备间呼叫权限

1. 在智控台顶部菜单点击 **通讯录**
2. 在左侧智能体下，设备列表中选择你的设备A（支持按 MAC地址 或 备注名 搜索）
3. 在右侧详情面板中，找到目标设备B的称呼设置，例如 **"小王"**
4. 勾选设备B的 **呼叫权限** 复选框
5. 点击 **保存**

**双向授权说明：** 如需设备A和设备B互相通信，必须在两侧智控台分别配置对方权限。例如：

- 在设备A的配置中勾选设备B → 设备A可与设备B通信
- 在设备B的配置中勾选设备A → 设备B可与设备A通信

### 第三步：在智能体配置添加呼叫工具

1. 在智控台顶部菜单点击 **智能体管理**
2. 在刚刚配置设备联系人的相关智能体中点击 **编辑角色**
3. 在右侧详情面板中，点击 **编辑功能**
4. 勾选 **设备呼叫设备** 工具
5. 点击 **保存配置** 确认
6. 在外侧再次点击 **保存配置** ，随即重启设备

### 第四步：固件端添加远程唤醒工具

1. 在[xiaozhi-esp32](https://github.com/78/xiaozhi-esp32) 代码的基础上增加远程唤醒工具MCP，版本支持为2.1.0至2.2.6（2026年5月29日的版本）
2. 在application.h文件中添加远程唤醒函数声明
    ```cpp
    void RemoteWakeup(const std::string& reason);
    ```
3. 在application.cc文件中添加远程唤醒函数
    ```cpp
    void Application::RemoteWakeup(const std::string& reason){
        if (!protocol_) {
            return;
        }

        auto state = GetDeviceState();
        
        if (state == kDeviceStateIdle) {
            audio_service_.EncodeWakeWord();

            if (!protocol_->IsAudioChannelOpened()) {
                SetDeviceState(kDeviceStateConnecting);
                if (!protocol_->OpenAudioChannel()) {
                    audio_service_.EnableWakeWordDetection(true);
                    return;
                }
            }
            std::string wake_word = reason;
    #if CONFIG_USE_AFE_WAKE_WORD || CONFIG_USE_CUSTOM_WAKE_WORD
            // Encode and send the wake word data to the server
            while (auto packet = audio_service_.PopWakeWordPacket()) {
                protocol_->SendAudio(std::move(packet));
            }
            // Set the chat state to wake word detected
            protocol_->SendWakeWordDetected(wake_word);
            SetListeningMode(aec_mode_ == kAecOff ? kListeningModeAutoStop : kListeningModeRealtime);
    #else
            // Set flag to play popup sound after state changes to listening
            // (PlaySound here would be cleared by ResetDecoder in EnableVoiceProcessing)
            play_popup_on_listening_ = true;
            SetListeningMode(aec_mode_ == kAecOff ? kListeningModeAutoStop : kListeningModeRealtime);
    #endif
        } else if (state == kDeviceStateSpeaking) {
            AbortSpeaking(kAbortReasonWakeWordDetected);
            SetDeviceState(kDeviceStateIdle);
        } else if (state == kDeviceStateActivating) {
            SetDeviceState(kDeviceStateIdle);
        }
    }
    ```
4. 在mcp_server.cc文件中添加远程唤醒工具
    ```cpp
    AddUserOnlyTool("self.remote_wakeup", "Remote wakeup function with configurable parameters",
        PropertyList({
            Property("reason", kPropertyTypeString, "Wakeup reason"),
        }),
        [this](const PropertyList& properties) -> ReturnValue {
            std::string reason = properties["reason"].value<std::string>();
            ESP_LOGI(TAG, "Wakeup reason=%s", reason.c_str());
            auto& app = Application::GetInstance();
            app.RemoteWakeup(reason);
            return true;
    ```
5. 按照 [固件编译烧录指南](firmware-build.md) 完成固件烧录
6. 无论你的设备是单麦还是双麦，请在编译环节，勾选开启AEC功能!
7. 无论你的设备是单麦还是双麦，请在编译环节，勾选开启AEC功能!
8. 无论你的设备是单麦还是双麦，请在编译环节，勾选开启AEC功能!

### 第五步：配置MQTT网关服务

1. 按下节部署 MQTT 网关。
2. 如果已经部署，确认代码版本不早于 2026 年 5 月 27 日。

## MQTT 网关

网关把设备的 MQTT/UDP 通道桥接到 `xiaozhi-server` WebSocket。克隆并安装官方网关：

```bash
git clone https://github.com/xinnan-tech/xiaozhi-mqtt-gateway.git
cd xiaozhi-mqtt-gateway
npm install
npm install -g pm2
cp config/mqtt.json.example config/mqtt.json
```

在 `config/mqtt.json` 的 `production.chat_servers` 中填写桥接地址。本机源码部署可用：

```json
{
  "production": {
    "chat_servers": ["ws://127.0.0.1:8000/xiaozhi/v1/?from=mqtt_gateway"]
  }
}
```

跨主机或公网桥接必须改用 TLS 反向代理后的 `wss://` 地址。网关 `.env` 使用自行生成的强随机密钥：

```dotenv
PUBLIC_IP=你的设备可访问域名或地址
MQTT_PORT=1883
UDP_PORT=8884
API_PORT=8007
MQTT_SIGNATURE_KEY=生成的高强度随机签名密钥
SERVER_SECRET=与服务端一致的共享密钥
```

`.env`、`MQTT_SIGNATURE_KEY` 和 `SERVER_SECRET` 禁止提交、截图或打印到日志。公网不要无条件开放管理 API；只向设备来源放行必需端口，优先为 MQTT 使用 TLS。启动并检查：

```bash
pm2 start ecosystem.config.js
pm2 logs xz-mqtt
```

全模块部署在智控台参数管理填写：

| 参数 | 值 |
| --- | --- |
| `server.mqtt_gateway` | `PUBLIC_IP:MQTT_PORT` |
| `server.mqtt_signature_key` | 与网关一致的签名密钥 |
| `server.udp_gateway` | `PUBLIC_IP:UDP_PORT` |
| `server.mqtt_manager_api` | 网关管理 API 的受控地址 |

单模块部署在私有 `data/.config.yaml` 的 `server` 下配置 `mqtt_gateway`、`mqtt_signature_key` 和 `udp_gateway`。设备通过 OTA 获得 MQTT 凭据；验证响应时只确认 `mqtt` 对象存在，`username`、`password`、token 等字段统一显示为 `***`，不得复制到工单或日志。

## 开机 WebSocket 与 MCP 可见性

支持常驻控制连接的固件在 OTA 成功后应主动连接 WebSocket，发送 `hello(features.mcp=true)`，再完成 MCP `initialize` 和 `tools/list`。控制连接与音频会话应分离：结束语音只关闭音频状态，网络中断才关闭底层连接并触发重连。

如果设备必须先唤醒一次才出现在服务端，检查固件的 `WebsocketProtocol::Start()` 是否主动连接、是否有重连机制，以及 OTA 是否按下面结构下发配置：

```json
{
  "websocket": {
    "url": "wss://你的域名/xiaozhi/v1/",
    "token": "***",
    "version": 1
  }
}
```

`Device-Id` 和设备本地持久化的 `Client-Id` 必须在 OTA 请求、WebSocket 握手及 token 绑定中一致。验证步骤：

1. 重启设备，不触发唤醒词或按键。
2. 串口应出现 WebSocket 连接和收到 server hello 的日志。
3. 服务端应依次记录认证成功、设备注册、`features.mcp=true`、`initialize` 和 `tools/list`。
4. 断开网络后恢复，确认设备自动重连且仍可发起语音会话。
5. 真机检查空闲功耗；常驻连接会影响深度休眠策略。

固件改动位于外部 `xiaozhi-esp32` 仓库时，必须在对应 ESP-IDF 环境完成 `idf.py build` 和真机验证，本服务端仓库的静态检查不能替代它。

## 呼叫流程说明

准备两个设备，在智控台上面配置好通讯权限和在智能体中添加呼叫工具之后，在其中一个小智对话那里对他说：”呼叫XXX“，观察设备B是否响应。

## 常见问题

### Q: 设备B没有响应呼叫？

- 检查设备B是否在线（智控台设备状态）
- 确认设备B的固件已正确集成远程唤醒工具
- 检查MQTT网关连接是否正常
- 验证双向权限配置是否完整

### Q: 提示"无呼叫权限"？

- 在智控台确认设备A已勾选设备B的呼叫权限
- 确认配置已保存（非仅修改未保存）

### Q: 如何确认通讯录功能已开启？

- 智控台顶部菜单如显示"通讯录"入口，则表示已开启

### Q: 我叫他呼叫"张山"，但是他老是识别成"张三"，怎么办？
- 可以查阅你使用的asr服务的文档，确认是否支持热词识别。
- 如果你用的是`FunASRServer`,可以在容器里的`热词文件`里添加"张山"，然后重启容器。
- 如果你用的是`火山引擎`的服务，可以在`火山引擎的控制台`里添加`热词文件`，然后回到智控台的`模型配置页面`，把`热词文件名称`配置在`火山引擎的tts`上去。
