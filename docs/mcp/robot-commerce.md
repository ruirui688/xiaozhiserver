---
status: supporting
owner: tools
last_verified: 2026-10-08
source_paths:
  - main/xiaozhi-server/core/providers/tools/commerce_policy.py
  - main/xiaozhi-server/core/providers/tools/unified_tool_manager.py
  - main/xiaozhi-server/core/providers/tools/device_mcp/mcp_handler.py
  - main/xiaozhi-server/core/api/device_mcp_handler.py
---

# 机器人点单与售货 MCP 接入

架构入口：[MCP 链路](../architecture/mcp-chains.md)、[功能插件](../architecture/function-plugin-architecture.md)。
产品范围是独立顾客微信小程序、单一微信商户、多设备现场取货。

## 1. 当前实现与尚未实现

当前实现了售货设备 MCP 的命名空间隔离：`self.commerce.*` 可通过设备 `tools/list` 发现，
但不会注册到模型工具列表，通用设备 MCP HTTP 调用和底层设备通用调用均拒绝执行。
点号规范化后的 `self_commerce_*` 和服务端 MCP 的 `mcp_` 别名同样受限；模型工具聚合
检查注册键、工具定义名和 schema 名，覆盖五类工具源。

这只是售货工具注册边界，不是已完成的点单或支付系统。目前没有上线商品目录、购买会话、
微信登录/支付回调、二维码生成、订单授权调度或出货任务存储；也没有新增可假装成功的占位插件。
现有角色插件选择和其他设备 MCP 工具行为保持原样。未执行数据库迁移、服务启动或生产发布。

## 2. 注册分层

| 层 | 计划能力 | 调用者 | 当前状态 |
| --- | --- | --- | --- |
| 服务端购买插件 | 查询商品、创建/替换临时购物车、请求显示购买入口 | 模型，受角色插件白名单约束 | 等待点单 API 契约后实现 |
| 设备显示 MCP | 展示服务端生成的小程序码、清除旧入口 | 经校验的购买会话服务 | 命名空间已保护，调度未实现 |
| 设备出货 MCP | 提交任务、查询真实执行结果 | 已支付订单的出货调度器 | 命名空间已保护，调度未实现 |

不可把机械臂工具作为普通“角色插件”勾选后直接交给 LLM。仅修改工具描述或标记 `paid=true`
不能获得权限。提示词目录也不应提前登记尚不存在的商品查询或下单插件。

## 3. 设备 MCP v1 建议契约

以下名称和参数是服务端建议，须与固件实现对齐后再启用。当前禁止通过通用接口调用，
不是已经可执行的订单专用接口。设备使用标准 `hello.features.mcp=true`、MCP 初始化及
`tools/list` 上报能力；不依赖自定义 annotations 自动获得权限。

| 原始工具名称 | 建议输入 | 建议结果 |
| --- | --- | --- |
| `self.commerce.display.show` | `displayId`、`imageUrl`、`expiresAt` | `displayId`、`status=displayed/failed` |
| `self.commerce.display.clear` | `displayId` | `displayId`、`status=cleared/not_current/failed` |
| `self.commerce.fulfillment.submit` | `taskId`、`orderId`、`itemId`、`slotId`、`quantity=1`、`authorization` | `taskId`、`status=accepted/running/succeeded/failed/unknown` |
| `self.commerce.fulfillment.get` | `taskId` | `taskId`、`status`、有界 `failureCode` |

约束：

- 一个出货任务对应一件商品，商品数量大于一时由订单服务生成多个任务。
- `authorization` 是未来订单服务签发的短期设备授权，绑定设备、任务、货位、数量及有效期；
  不能由模型提供。签名算法、密钥轮换和设备验签协议尚待实现，不能用此字段存在代替验证。
- 接收任务即返回，机械运动异步执行。`accepted` 仅表示已受理，不表示已交付。
- 设备必须持久化 `taskId` 及执行状态。相同任务、相同负载返回已有状态；相同任务、不同负载拒绝。
- 断电或超时导致结果不明时返回 `unknown`，不能自动重抓；先查询并核实交付结果。
- `displayId` 防止旧购物会话清除新顾客二维码；显示端必须执行过期清除。
- `imageUrl` 只允许经服务端校验的受控 HTTPS 图片来源；客户端不得随意访问模型提供的 URL。
- 返回结果不得包含支付凭据、顾客身份信息或授权原文。授权及二维码认领信息不得写入普通日志。
- 本地急停、限位与安全保护独立于 MCP、支付和云端对话。

## 4. 后续服务端接线条件

1. 对齐小程序项目的 `docs/api-contract.md`，明确商品、购物车、入口认领和订单接口归属。
2. Java 校验当前已认证设备与数据库中的 agent/workspace 绑定；设备身份不能由模型参数指定。
3. 购买工具只接收商品编号和数量；价格、库存、会话期限及二维码内容来自权威服务。
4. 微信回调验签并校验商户、金额与订单后，事务性写入支付事实和待出货任务。
5. 新增独立内部出货接口，重新校验订单授权与在线设备绑定。不能用通用 `/device/mcp/call`
   加一个客户端 `paid` 参数放开门控，也不能删掉当前门控来“临时联调”。
6. 专用调度器单次提交，后续查询任务状态。不能直接复用服务端 MCP 的通用自动重试来驱动机械臂。
7. 新增授权、展示和结果通道时，先完成日志脱敏；当前通用设备 MCP 会记录参数和结果，不可承载授权秘密。

## 5. 与现有工具的兼容边界

保护只覆盖约定的 `self.commerce.*` 名称及其规范化/别名形式，不能按描述猜测任意工具是否会出货。
若固件已经用其他名称暴露机械臂出货，必须先提供工具清单并迁移到受保护命名空间，
或者另行实现明确的名称映射和授权门控。不能因此声称任何已有机械臂都已被保护。

第一版不允许模型读取完整顾客订单或按任意订单号查询。顾客订单详情由小程序认证接口返回。
没有小程序接口和固件能力确认前，机器人不得播报“二维码已显示”“付款成功”或“已经出货”。

## 6. 本地验证

```bash
cd main/xiaozhi-server
conda run -n xiaozhi-esp32-server python -m compileall .
PYTHONNOUSERSITE=1 PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 conda run -n xiaozhi-esp32-server python -m pytest -q tests/test_commerce_tool_policy.py tests/test_device_mcp_executor.py tests/test_device_mcp_tool_protocol.py tests/test_prompt_context_budget.py
```

测试覆盖跨工具源注册、别名、schema 名称、通用 HTTP 调用、底层设备调用和原有 MCP 行为。
环境变量用于隔离用户目录中的 pytest 版本和无关的 ROS 自动加载插件，不替换项目 Conda 运行时。
真实显示、机械臂执行、断电恢复和支付闭环仍须在专用调度实现后做实机验证。
