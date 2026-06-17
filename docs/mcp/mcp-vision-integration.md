# 视觉模型使用指南
本教程分为两部分：
- 第一部分：单模块运行xiaozhi-server开启视觉模型
- 第二部分：全模块运行时，如何开启视觉模型

开启视觉模型前，你需要准备三件事：
- 你需要准备一台带摄像头的设备，而且这台设备已经在虾哥仓库里，实现了调用摄像头功能。例如`立创·实战派ESP32-S3开发板`
- 你设备固件的版本升级到1.6.6及以上
- 你已经成功跑通基础对话模块

## 单模块运行xiaozhi-server开启视觉模型

### 第一步确认网络
由于视觉模型会默认启动8003端口。

如果你是docker运行，请确认一下你的`docker-compose.yml`是否放了`8003`端口，如果没有就更新最新的`docker-compose.yml`文件

如果你是源码运行，确认防火墙是否放行`8003`端口

### 第二步选择你的视觉模型
打开你的`data/.config.yaml`文件，设置你的`selected_module.VLLM`设置为某个视觉模型。目前我们已经支持`openai`类型接口的视觉模型。`ChatGLMVLLM`就是其中一款兼容`openai`的模型。

```
selected_module:
  VAD: ..
  ASR: ..
  LLM: ..
  VLLM: ChatGLMVLLM
  TTS: ..
  Memory: ..
  Intent: ..
```

假设我们使用`ChatGLMVLLM`作为视觉模型，那我们需要先登录[智谱AI](https://bigmodel.cn/usercenter/proj-mgmt/apikeys)网站，申请密钥。如果你之前已经申请过了密钥，可以复用这个密钥。

在你的配置文件中，增加这个配置，如果已经有了这个配置，就设置好你的api_key。

```
VLLM:
  ChatGLMVLLM:
    api_key: 你的api_key
```

### 第三步启动xiaozhi-server服务
如果你是源码，就输入命令启动
```
python app.py
```
如果你是docker运行，就重启容器
```
docker restart xiaozhi-esp32-server
```

如果你使用的是全模块部署，且视觉分析地址需要下发给设备，请优先在智控台的「参数管理」中配置 `server.vision_explain`，例如：

```
server.vision_explain = http://10.19.1.209:8003/mcp/vision/explain
```

这个参数会写入 `sys_params`，由 `manager-api` 返回给 `xiaozhi-server`，再下发给设备。当前代码里本地配置只在你显式填写真实地址时才覆盖后台值；如果还是占位符、`null` 或 `你的ip或者域名...`，系统会继续使用后台参数或自动回退值。

启动后会输出以下内容的日志。

```
2025-06-01 **** - OTA接口是           http://192.168.4.7:8003/xiaozhi/ota/
2025-06-01 **** - 视觉分析接口是        http://192.168.4.7:8003/mcp/vision/explain
2025-06-01 **** - Websocket地址是       ws://192.168.4.7:8000/xiaozhi/v1/
2025-06-01 **** - =======上面的地址是websocket协议地址，请勿用浏览器访问=======
2025-06-01 **** - 如需测试WebSocket，请使用真实ESP32设备或WebSocket客户端
2025-06-01 **** - =============================================================
```

启动后，使用使用浏览器打开日志里`视觉分析接口`连接。看看输出了什么？如果你是linux,没有浏览器，你可以执行这个命令：
```
curl -i 你的视觉分析接口
```

正常来说会这样显示
```
MCP Vision 接口运行正常，视觉解释接口地址是：http://xxxx:8003/mcp/vision/explain
```

请注意，如果你是公网部署，或者docker部署，一定要改一下你的`data/.config.yaml`里这个配置
```
server:
  vision_explain: http://你的ip或者域名:端口号/mcp/vision/explain
```

为什么呢？因为视觉解释接口需要下发到设备，如果你的地址是局域网地址，或者是docker内部地址，设备是无法访问的。

假设你的公网地址是`111.111.111.111`，那么`vision_explain`应该这么配

```
server:
  vision_explain: http://111.111.111.111:8003/mcp/vision/explain
```

如果你的MCP Vision 接口运行正常，且你也试着用浏览器访问正常打开下发的`视觉解释接口地址`，请继续下一步

补充说明：
- 如果你在 WSL 上运行源码，`get_local_ip()` 可能返回 `172.28.*.*` 这类 WSL 地址，设备通常不能直接访问。
- 这时请把 `server.vision_explain` 明确改成 Windows 局域网 IP、可路由域名或反向代理地址。
- 修改后要让 `manager-api` 重新返回配置，必要时清理 Redis 的 `server:config` 和 `sys:params` 缓存，再重启 `xiaozhi-server`。

### 第四步 设备唤醒开启

对设备说“请打开摄像头，说你你看到了什么”

留意xiaozhi-server的日志输出，看看有没有报错。


## 全模块运行时，如何开启视觉模型

### 第一步 确认网络
由于视觉模型会默认启动8003端口。

如果你是docker运行，请确认一下你的`docker-compose_all.yml`是否映射了`8003`端口，如果没有就更新最新的`docker-compose_all.yml`文件

如果你是源码运行，确认防火墙是否放行`8003`端口

### 第二步 确认你配置文件

打开你的`data/.config.yaml`文件，确认一下你的配置文件的结构，是否和`data/config_from_api.yaml`一样。如果不一样，或缺少某项，请补齐。

全模块部署时，设备实际使用的视觉解释地址建议在智控台「参数管理」里配置 `server.vision_explain`。它必须是设备可以访问到的地址，不一定等于 Python 进程启动日志里打印的本机地址。

### 第三步 配置视觉模型密钥

那我们需要先登录[智谱AI](https://bigmodel.cn/usercenter/proj-mgmt/apikeys)网站，申请密钥。如果你之前已经申请过了密钥，可以复用这个密钥。

登录`智控台`，顶部菜单点击`模型配置`，在左侧栏点击`视觉打语言模型`，找到`VLLM_ChatGLMVLLM`，点击修改按钮，在弹框中，在`API密钥`输入你密钥，点击保存。

保存成功后，去到你需要测试的智能体哪里，点击`配置角色`，在打开的内容里，查看`视觉大语言模型(VLLM)`是否选择了刚才的视觉模型。点击保存。

### 第三步 启动xiaozhi-server模块
如果你是源码，就输入命令启动
```
python app.py
```
如果你是docker运行，就重启容器
```
docker restart xiaozhi-esp32-server
```

启动后会输出以下内容的日志。

```
2025-06-01 **** - 视觉分析接口是        http://192.168.4.7:8003/mcp/vision/explain
2025-06-01 **** - Websocket地址是       ws://192.168.4.7:8000/xiaozhi/v1/
2025-06-01 **** - =======上面的地址是websocket协议地址，请勿用浏览器访问=======
2025-06-01 **** - 如需测试WebSocket，请使用真实ESP32设备或WebSocket客户端
2025-06-01 **** - =============================================================
```

启动后，使用使用浏览器打开日志里`视觉分析接口`连接。看看输出了什么？如果你是linux,没有浏览器，你可以执行这个命令：
```
curl -i 你的视觉分析接口
```

正常来说会这样显示
```
MCP Vision 接口运行正常，视觉解释接口地址是：http://xxxx:8003/mcp/vision/explain
```

请注意，如果你是公网部署，或者docker部署，一定要改一下你的`data/.config.yaml`里这个配置
```
server:
  vision_explain: http://你的ip或者域名:端口号/mcp/vision/explain
```

为什么呢？因为视觉解释接口需要下发到设备，如果你的地址是局域网地址，或者是docker内部地址，设备是无法访问的。

假设你的公网地址是`111.111.111.111`，那么`vision_explain`应该这么配

```
server:
  vision_explain: http://111.111.111.111:8003/mcp/vision/explain
```

如果你的MCP Vision 接口运行正常，且你也试着用浏览器访问正常打开下发的`视觉解释接口地址`，请继续下一步

全模块部署排查时，可以用下面两个结果判断是否配置生效：

```
curl http://127.0.0.1:8003/mcp/vision/explain
```

返回内容中的“视觉解释接口地址”应当是 `server.vision_explain` 配置的地址。设备日志里如果仍然连接 `172.28.*.*`、Docker 内网 IP 或 `127.0.0.1`，说明下发地址仍不可达，需要重新检查后台参数、Redis 缓存和 `xiaozhi-server` 是否已重启。

### 第四步 设备唤醒开启

对设备说“请打开摄像头，说你你看到了什么”

留意xiaozhi-server的日志输出，看看有没有报错。
