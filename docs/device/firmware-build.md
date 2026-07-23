# ESP32 固件配置、编译与 OTA

本文覆盖两条首次接入路径和单模块固件升级：使用官方预编译固件时在配网页面填写 OTA 地址；需要定制板型或设备能力时自行编译。公网环境必须使用受信任证书的 `https://` OTA 与 `wss://` WebSocket 地址。

## 使用预编译固件

烧录 `xiaozhi-esp32` 1.6.1 或更高版本的[预编译固件](https://github.com/78/xiaozhi-esp32/releases)，让设备进入配网模式，在页面顶部“高级选项”填写自己的 OTA 地址并保存重启。

![OTA 地址设置](../images/firmware-setting-ota.png)

全模块部署先在智控台“参数管理”确认 `server.websocket`。刷新 OTA 地址后应显示接口正常和 WebSocket 集群数量；单模块部署则确认 `data/.config.yaml` 中的 `server.websocket`。设备重启后同时观察串口和服务端日志。

## 自行编译固件

### 第 1 步：准备 OTA 地址

如果你，使用的是本项目0.3.12版本，不管是简单Server部署还是全模块部署，都会有ota地址。

由于简单Server部署和全模块部署的OTA地址设置方式不一样，请你选择下面的具体方式：

### 如果你用的是简单Server部署
此刻，请你用浏览器打开你的ota地址，例如我的ota地址
```
http://192.168.1.25:8003/xiaozhi/ota/
```
如果显示“OTA接口运行正常，向设备发送的websocket地址是：ws://xxx:8000/xiaozhi/v1/

你可以使用真实 ESP32 设备，或自建 WebSocket 客户端测试是否能连接 OTA 页面输出的 WebSocket 地址。

如果访问不到，你需要到配置文件`.config.yaml`里修改`server.websocket`的地址，重启后再重新测试，直到`index.html`能正常访问。

成功后，请往下进行第2步

### 如果你用的是全模块部署
此刻，请你用浏览器打开你的ota地址，例如我的ota地址
```
http://192.168.1.25:8002/xiaozhi/ota/
```

如果显示“OTA接口运行正常，websocket集群数量：X”。那就往下进行2步。

如果显示“OTA接口运行不正常”，大概是你还没在`智控台`配置`Websocket`地址。那就：

- 1、使用超级管理员登录智控台

- 2、顶部菜单点击`参数管理`

- 3、在列表中找到`server.websocket`项目，输入你的`Websocket`地址。例如我的就是

```
ws://192.168.1.25:8000/xiaozhi/v1/
```

配置完后，再使用浏览器刷新你的ota接口地址，看看是不是正常了。如果还不正常就，就再次确认一下Websocket是否正常启动，是否配置了Websocket地址。

### 第 2 步：配置环境
先按照这个教程配置项目环境[《Windows搭建 ESP IDF 5.3.2开发环境以及编译小智》](https://icnynnzcwou8.feishu.cn/wiki/JEYDwTTALi5s2zkGlFGcDiRknXf)

### 第 3 步：打开配置文件
配置好编译环境后，下载虾哥iaozhi-esp32项目源码，

从这里下载虾哥[xiaozhi-esp32项目源码](https://github.com/78/xiaozhi-esp32)。

下载后，打开`xiaozhi-esp32/main/Kconfig.projbuild`文件。

### 第 4 步：修改 OTA 地址

找到`OTA_URL`的`default`的内容，把`https://api.tenclass.net/xiaozhi/ota/`
   改成你自己的地址，例如，我的接口地址是`http://192.168.1.25:8002/xiaozhi/ota/`，就把内容改成这个。

修改前：
```
config OTA_URL
    string "Default OTA URL"
    default "https://api.tenclass.net/xiaozhi/ota/"
    help
        The application will access this URL to check for new firmwares and server address.
```
修改后：
```
config OTA_URL
    string "Default OTA URL"
    default "http://192.168.1.25:8002/xiaozhi/ota/"
    help
        The application will access this URL to check for new firmwares and server address.
```

### 第 5 步：设置编译参数

设置编译参数

```
# 终端命令行进入xiaozhi-esp32的根目录
cd xiaozhi-esp32
# 例如我使用的板子是esp32s3，所以设置编译目标为esp32s3，如果你的板子是其他型号，请替换成对应的型号
idf.py set-target esp32s3
# 进入菜单配置
idf.py menuconfig
```

进入菜单配置后，再进入`Xiaozhi Assistant`，将`BOARD_TYPE`设置你板子的具体型号
保存退出，回到终端命令行。

### 第 6 步：编译固件

```
idf.py build
```

### 第 7 步：打包全量固件

```
cd scripts
python release.py
```

上面的打包命令执行完成后，会在项目根目录下的`build`目录下生成固件文件`merged-binary.bin`。
这个`merged-binary.bin`就是要烧录到硬件上的固件文件。

注意：如果执行到第二命令后，报了“zip”相关的错误，请忽略这个错误，只要`build`目录下生成固件文件`merged-binary.bin`
，对你没有太大影响，请继续。

### 第 8 步：烧录固件
   将esp32设备连接电脑，使用chrome浏览器，打开以下网址

```
https://espressif.github.io/esp-launchpad/
```

打开这个教程，[Flash工具/Web端烧录固件（无IDF开发环境）](https://ccnphfhqs21z.feishu.cn/wiki/Zpz4wXBtdimBrLk25WdcXzxcnNS)。
翻到：`方式二：ESP-Launchpad 浏览器WEB端烧录`，从`3. 烧录固件/下载到开发板`开始，按照教程操作。

烧录成功且联网成功后，通过唤醒词唤醒小智，留意server端输出的控制台信息。

## 单模块 OTA 自动升级

全模块部署通过智控台管理固件；以下只适用于单模块 `xiaozhi-server`。将升级分区生成的 `xiaozhi.bin` 放入 `main/xiaozhi-server/data/bin/`，不要使用首次烧录用的 `merged-binary.bin`。

文件名必须是 `{设备型号}_{版本号}.bin`，版本号以数字开头，可包含字母、点、下划线和短横线。例如：

```bash
cd main/xiaozhi-server
mkdir -p data/bin
cp /可信路径/xiaozhi.bin data/bin/bread-compact-wifi_1.6.6.bin
```

设备型号必须与上报型号一致，新版本必须高于当前版本。固件缓存默认约 30 秒；更新文件后等待缓存失效或通过正常服务管理流程重启。

公网下载地址必须是设备可访问的外部 TLS 地址，不能写 `127.0.0.1`、`localhost` 或 Docker 内部主机名。若当前版本仍从 `server.vision_explain` 推导下载基址，应在私有 `data/.config.yaml` 中填写反向代理后的外部地址；不要因此开放内部管理端口。

验证时检查 OTA 日志中的设备型号、当前版本、匹配文件和最终下载 URL，并从设备所在网络实际下载一次。失败时依次检查文件名、版本比较、缓存、反向代理、证书和防火墙。

## 常见问题

- 能访问 OTA 但设备连不上：检查 OTA 返回的 WebSocket URL 是否从设备网络可达，公网是否使用 WSS 和受信任证书。
- 烧录后板型异常：重新核对 `idf.py set-target`、`BOARD_TYPE` 与硬件型号，清理的是固件构建目录，不是服务端 Docker 缓存。
- OTA 没有升级：核对上报型号、当前版本、`xiaozhi.bin` 文件名和下载 URL，不要上传 `merged-binary.bin`。
- 需要设备互呼、MQTT 或常驻 MCP：继续阅读[设备通信与呼叫](device-call-guide.md)。
