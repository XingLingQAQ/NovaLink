# Admin REST API 参考

NovaLink 管理网关在同一个 HTTP 服务中提供认证、REST 与 WebSocket。除 `/api/auth/*` 外的 REST 请求都要求 `Authorization: Bearer <access-token>`；后端会验证 token 的 subject、username 与 role 声明，并拒绝将 refresh token 用作 API 凭据。[1] [2]

> **接口定位。** 本页是当前实现的操作目录，不是面向公网的默认开放 API 策略。部署时应将管理网关置于 HTTPS、访问控制和受限网络之后。对频道、玩家、客户端、设置或控制台有副作用的调用应纳入运维审计与回退流程。[3]

## 1. 基本约定

| 项目 | 约定 |
| --- | --- |
| 基础路径 | 管理网关上的 `/api`。生产环境通常由反向代理映射为与面板同源的 `/api`。 |
| 认证 | 除认证路由外，使用 `Authorization: Bearer <access-token>`。 |
| 内容类型 | 对含请求体的端点使用 `application/json`。 |
| 标识符 | 玩家参数为 UUID；频道和客户端参数为其运行时/配置标识。 |
| 成功响应 | 大多数操作返回 JSON 和 `2xx`；创建频道/Webhook 返回 `201 Created`。 |
| 失败响应 | 常见为 `400`（参数）、`401`（认证）、`404`（对象不存在）、`503`（子系统不可用）或 `500`（内部错误）。 |
| 跨域 | 后端处理 OPTIONS；实际跨域与前端部署仍须由安全策略控制。 |

所有 `POST`、`PUT` 和 `DELETE` 请求都可能改变运行时或持久化状态。调用前应核对目标环境、频道作用域、玩家 UUID 和回退条件。

## 2. 认证

| 方法与路径 | 是否需要 Bearer Token | 请求体 | 成功结果 |
| --- | --- | --- | --- |
| `POST /api/auth/login` | 否 | `{"username":"…","password":"…"}` | 返回 access token、refresh token 与用户信息。 |
| `POST /api/auth/refresh` | 否 | `{"refreshToken":"…"}` | 返回新的 access token。 |

登录与刷新由专门的 HTTP 认证处理器完成。登录账户来自后端认证管理器；超级管理员可映射到管理面板身份。不要把密码或 token 放进 URL、浏览器历史、公开脚本或日志。[2] [3]

## 3. 频道与消息

| 方法与路径 | 作用 | 请求体或参数 | 风险提示 |
| --- | --- | --- | --- |
| `GET /api/channels` | 列出全部频道 | 无 | 返回频道数组与数量。 |
| `GET /api/channels/{channelId}` | 获取频道详情 | 路径参数 | 频道不存在返回 `404`。 |
| `POST /api/channels` | 创建频道 | `id?`、`displayName?`、`scope?`、`maxCapacity?`、`permission?` | 这是管理操作；未给 ID 时会生成 ID。 |
| `PUT /api/channels/{channelId}` | 更新可变字段 | `displayName?`、`maxCapacity?`、`permission?` | 不应假设可修改所有频道结构或作用域。 |
| `DELETE /api/channels/{channelId}` | 删除频道 | 无 | 先移除成员；应先确认业务影响。 |
| `GET /api/channels/{channelId}/members` | 查看成员 | 无 | 暴露玩家 UUID/名称，控制访问范围。 |
| `POST /api/channels/{channelId}/invite` | 创建邀请 | `ttlMillis?` | 需要保护返回的邀请码。 |
| `POST /api/messages` | 以 API 身份向频道发送消息 | `channelId`、`content`、`senderName?` | 消息会进入路由；不要用于未审计批量推送。 |

创建频道的 `scope` 使用 `GLOBAL`、`SERVER` 或 `PRIVATE` 枚举语义。REST 管理创建的私有频道会使用后台的管理客户端标识；对外部系统而言，应先明确频道边界，再自动化创建。[1]

### 示例：发送受控系统消息

```bash
curl --request POST "https://panel.example.invalid/api/messages" \
  --header "Authorization: Bearer <access-token>" \
  --header "Content-Type: application/json" \
  --data '{
    "channelId": "global",
    "senderName": "Operations",
    "content": "Planned maintenance begins in 10 minutes."
  }'
```

该调用需要目标频道存在。API 会为消息使用系统 UUID，并经过消息路由；实际可见范围仍取决于频道与后端运行时规则。[1]

## 4. 玩家治理

| 方法与路径 | 作用 | 请求体或参数 | 风险提示 |
| --- | --- | --- | --- |
| `GET /api/players` | 列出在线玩家状态 | 无 | 返回当前状态管理器中的玩家。 |
| `GET /api/players/{uuid}` | 获取单个玩家状态 | UUID 路径参数 | 非 UUID 为 `400`，未找到为 `404`。 |
| `POST /api/players/{uuid}/mute` | 禁言 | `channelId?`、`durationMs?`、`reason?` | 明确全局/频道范围、期限和原因。 |
| `POST /api/players/{uuid}/unmute` | 解除禁言 | `channelId?` | 先确认目标禁言记录。 |
| `POST /api/players/{uuid}/ban` | 封禁 | `channelId?`、`durationMs?`、`reason?` | 高影响治理操作，应记录依据。 |
| `POST /api/players/{uuid}/unban` | 解除封禁 | `channelId?` | 避免误解除错误范围。 |
| `POST /api/players/{uuid}/kick` | 从指定频道移除成员 | **必需** `channelId` | 不会把“踢出频道”理解为网络层断开。 |
| `GET /api/mutes` | 查看活跃禁言 | 无 | 基于在线玩家状态聚合。 |
| `GET /api/bans` | 查看封禁 | 无 | 结合后台持久化状态审阅。 |

REST 层将这些操作视作后台发起的管理动作。自动化系统应在调用前完成 UUID、频道 ID、持续时间和理由校验，并保存操作请求与响应记录。[1]

## 5. 客户端、配置与功能设置

| 方法与路径 | 作用 | 请求体或参数 | 风险提示 |
| --- | --- | --- | --- |
| `DELETE /api/clients/{clientId}` | 发起对游戏服务器 TCP 客户端的断开 | 路径参数 | 仅影响对应游戏服务器连接，不处理 WebSocket 面板会话。 |
| `POST /api/reload` | 触发配置重载与客户端同步 | 无 | 应在受控窗口执行，并验证同步后的路由与权限。 |
| `GET /api/settings` | 读取功能开关 | 无 | 返回 `filterEnabled`、`messageLogEnabled`、`crossServerChatEnabled`。 |
| `PUT /api/settings` | 修改功能开关并尝试持久化 | 三个布尔字段均可选 | 会立即影响运行时；持久化失败需视为变更异常。 |
| `GET /api/status` | 获取后端状态摘要 | 无 | 返回在线状态、版本、频道数、玩家数和时间戳。 |

`PUT /api/settings` 会先修改内存中的 FeatureConfig，再通过配置管理器触发运行时应用，并尝试保存到配置文件。若运行时已应用而落盘失败，接口会返回服务器错误；运维人员必须检查文件与运行时是否一致，而非只看面板开关状态。[1]

### 示例：读取或修改功能开关

```bash
curl --request GET "https://panel.example.invalid/api/settings" \
  --header "Authorization: Bearer <access-token>"

curl --request PUT "https://panel.example.invalid/api/settings" \
  --header "Authorization: Bearer <access-token>" \
  --header "Content-Type: application/json" \
  --data '{"crossServerChatEnabled": false}'
```

第二个调用会改变跨服聊天分发的运行时行为。应在发布记录中说明预期范围，并准备恢复值。[1]

## 6. 通知、Webhook 与远程控制台

| 方法与路径 | 作用 | 请求体或参数 | 风险提示 |
| --- | --- | --- | --- |
| `GET /api/notifications` | 分页读取通知 | `page?`、`size?`、`unreadOnly?` | 默认页码为 1、大小为 20。 |
| `POST /api/notifications/{id}/read` | 标记单条已读 | 通知 ID | 仅影响通知状态。 |
| `POST /api/notifications/read-all` | 标记全部已读 | 无 | 会改变所有通知状态。 |
| `DELETE /api/notifications` | 清空通知 | 无 | 不可逆地删除当前通知记录。 |
| `GET /api/webhooks` | 列出运行时 Webhook | 无 | 不把 URL/密钥暴露给无关角色。 |
| `POST /api/webhooks` | 创建 Webhook | `url`、`event`、`secret?` | 需审查外部目标与数据泄露风险。 |
| `DELETE /api/webhooks/{id}` | 删除 Webhook | 路径参数 | 确认依赖自动化已迁移。 |
| `POST /api/console` | 远程执行受允许的控制台命令 | `{"command":"status"}` | `stop` 和 `shutdown` 被此端点显式拒绝。 |

Webhook REST 路由存在并操作运行时管理器，但示例 YAML 中的 `webhooks` 段目前被标注为未由配置加载器解析。请不要把“REST 可创建”误写成“编辑 YAML 后自动加载”。[1] [4]

## 7. 错误处理与集成建议

| 状态 | 常见含义 | 集成器应做什么 |
| --- | --- | --- |
| `400 Bad Request` | 缺少字段、UUID/枚举错误、无效 JSON 或被禁止的命令 | 修正请求，不要无条件重试。 |
| `401 Unauthorized` | 缺少、过期或无效 token | 按认证流程刷新/重新登录；不要记录 token。 |
| `404 Not Found` | 频道、玩家、客户端或 Webhook 不存在 | 先刷新本地缓存，检查是否为并发删除。 |
| `503 Service Unavailable` | 相关子系统未启用或不可用 | 进行健康检查与告警，而不是盲目重试。 |
| `500 Internal Server Error` | 后端操作失败 | 保存脱敏请求 ID/时间与响应，查阅后端日志。 |

集成方应将 API 调用设计为可审计、可重试但不重复执行高影响动作的工作流。对发送消息、治理、重载、设置与远程控制台，建议先呈现变更预览或加入人为审批。

## 参考资料

[1]: ../../StarLink/core/src/main/java/com/nova/link/api/RestApiHandler.java "REST 路由、参数与响应实现"
[2]: ../../StarLink/core/src/main/java/com/nova/link/websocket/HttpAuthHandler.java "登录与刷新接口"
[3]: ../operations/security.md "NovaLink 管理面安全基线"
[4]: ../../examples/novalink.yml "Webhook 示例段的当前解析边界"
[5]: ../../StarLink/core/src/main/java/com/nova/link/config/ConfigManager.java "配置重载与保存"
