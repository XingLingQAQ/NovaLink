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
| 同连接请求顺序 | HTTP/1.1 在前一响应写出后处理下一请求；等候期间暂停继续读取，最多保留 32 个已解码的排队请求，超限关闭连接。 |

所有 `POST`、`PUT` 和 `DELETE` 请求都可能改变运行时或持久化状态。调用前应核对目标环境、频道作用域、玩家 UUID 和回退条件。

## 2. 认证

| 方法与路径 | 是否需要 Bearer Token | 请求体 | 成功结果 |
| --- | --- | --- | --- |
| `POST /api/auth/login` | 否 | `{"username":"…","password":"…"}` | 返回 access token、refresh token 与用户信息。 |
| `POST /api/auth/refresh` | 否 | `{"refreshToken":"…"}` | 返回新的 access token。 |

登录与刷新由专门的 HTTP 认证处理器完成。登录账户来自后端认证管理器；超级管理员可映射到管理面板身份。不要把密码或 token 放进 URL、浏览器历史、公开脚本或日志。[2] [3]

面板请求绑定发出时的登录会话和后端地址。退出、重新登录或切换后端后，旧请求以 `AUTH_SESSION_CHANGED` 结束，不会使用新账号凭据刷新或重发。同一登录会话中的正常 token 轮换仍可触发一次 401 刷新重试。业务请求和原始文本格式的 `/api/metrics` 使用同一规则。[6] [7]

刷新请求只在同一认证代际、后端地址和连接代际内复用。切换账号后，新账号独立刷新；前一个退出请求未完成时，新账号退出也会使用自己的凭据单独撤销。旧请求完成后只能释放自己的进行中状态。[7]

## 3. 频道与消息

| 方法与路径 | 作用 | 请求体或参数 | 风险提示 |
| --- | --- | --- | --- |
| `GET /api/channels` | 列出全部频道 | 无 | 返回频道数组与数量。 |
| `GET /api/channels/{channelId}` | 获取频道详情 | 路径参数 | 频道不存在返回 `404`。 |
| `POST /api/channels` | 创建频道 | `id?`、`displayName?`、`scope?`、`maxCapacity?`、`permission?` | 这是管理操作；未给 ID 时会生成 ID。 |
| `PUT /api/channels/{channelId}` | 更新可变字段 | `displayName?`、`maxCapacity?`、`permission?`、`permissionPresent?`、`slowModeSeconds?` | 不应假设可修改所有频道结构或作用域。 |
| `DELETE /api/channels/{channelId}` | 删除频道 | 无 | 持久化删除成功后清理成员；应先确认业务影响。 |
| `GET /api/channels/{channelId}/members` | 查看成员 | 无 | 暴露玩家 UUID/名称，控制访问范围。 |
| `POST /api/channels/{channelId}/invite` | 创建邀请 | `ttlMillis?` | 需要保护返回的邀请码。 |
| `DELETE /api/channels/{channelId}/invitations/{code}` | 撤销频道邀请 | 无 | 保留使用次数、使用人和首次撤销时间；记录不存在时返回 `404`。 |
| `POST /api/messages` | 以 API 身份向频道发送消息 | `channelId`、`content`、`senderName?` | 消息会进入路由；不要用于未审计批量推送。 |

创建频道的 `scope` 使用 `GLOBAL`、`SERVER` 或 `PRIVATE` 枚举语义。REST 管理创建的私有频道会使用后台的管理客户端标识；对外部系统而言，应先明确频道边界，再自动化创建。[1]

频道更新先验证容量，再修改字段；提供非正容量时不会留下部分改名。邀请接受流程在同一频道锁内完成成员、容量检查和扣额入群；已有成员或频道满员返回 `NC-409`，保留邀请码剩余次数。频道删除持久化失败时保留运行时登记、索引、成员与玩家当前频道；创建或删除的存储失败返回 `500`，参数错误仍返回 `400`。[1] [12] [13]

`slowModeSeconds` 为非负整数秒，0 表示关闭；省略时保留当前值。它与名称、容量、权限一起保存并更新 revision，启动恢复也保留该值。更新持久化失败返回 `500` 并恢复本次修改的字段；参数错误仍返回 `400`。[1] [11] [13]

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

### 4.1 案件与申诉

| 方法与路径 | 请求体 | 状态约束 |
| --- | --- | --- |
| `POST /api/moderation/cases/{id}/assign` | `moderator` | 仅 `OPEN`、`UNDER_REVIEW` 可指派，成功后为 `UNDER_REVIEW`。 |
| `POST /api/moderation/cases/{id}/resolve` | `action`、`reason?`、`targetChannelId?`、`durationMs?` | `action` 为 `warn/mute/ban/kick/dismiss`；仅 `OPEN`、`UNDER_REVIEW` 可处理，`dismiss` 进入 `CLOSED`，其余进入 `RESOLVED`。 |
| `POST /api/appeals` | `caseId`、`appellantId`、`reason` | 仅 `RESOLVED` 可申诉；创建 `PENDING` 申诉并将案件改为 `APPEALED`。 |
| `GET /api/appeals` | 查询参数 `page?`、`size?`、`status?` | 分页读取申诉队列。 |
| `POST /api/appeals/{id}/review` | `decision`、`note?` | `PENDING` 可审核；`ESCALATED` 仅可由 `SUPER_ADMIN` 提交最终决定。 |

案件处理接口记录处理结论；`targetChannelId`、`durationMs` 随处理意图写入备注。实际禁言、封禁和踢出仍使用本节前面的玩家治理接口。[1]

指派、处理、创建申诉和审核需要 ADMIN 或以上的管理身份。原案件处理人的身份在处理完成后保留，`RESOLVED`、`APPEALED`、`CLOSED` 不能通过指派退回处理中；原处理人始终不能审核该案件申诉，包括使用 `SUPER_ADMIN` 身份时。[1] [8]

`decision` 可取 `APPROVED`、`DENIED`、`REJECTED` 或 `ESCALATED`。前三项为最终决定，申诉与案件一起提交，案件变为 `CLOSED` 并记录 `closedAt`。`ESCALATED` 保持案件为 `APPEALED`；后续高级审核必须选择最终决定，不能再次升级。已完成决定或并发写入冲突返回 `409`（`NC-409`），调用方应刷新队列后重新判断操作。[8]

申诉创建与案件状态转换、审核结论与案件结案分别在同一存储操作中完成。内存实现使用同一同步边界；JDBC 实现使用单连接事务和带原状态、更新时间及责任人的条件更新。写入失败回滚，不留下单边状态；并发创建或审核只有一方提交。[9]

## 5. 客户端、配置与功能设置

| 方法与路径 | 作用 | 请求体或参数 | 风险提示 |
| --- | --- | --- | --- |
| `DELETE /api/clients/{clientId}` | 发起对游戏服务器 TCP 客户端的断开 | 路径参数 | 仅影响对应游戏服务器连接，不处理 WebSocket 面板会话。 |
| `POST /api/reload` | 触发配置重载与客户端同步 | 无 | 应在受控窗口执行，并验证同步后的路由与权限。 |
| `GET /api/settings` | 读取功能开关与版本 | 无 | 返回 4 个功能开关、`messageLogRetentionDays` 和 `revision`。 |
| `PUT /api/settings` | 保存功能设置并生效 | 功能字段、`messageLogRetentionDays` 和 `baseRevision` 均可选 | 显式提供旧 revision 时返回 `409`；保存失败时保持原配置与运行时。 |
| `GET /api/status` | 获取后端状态摘要 | 无 | 返回在线状态、版本、频道数、玩家数和时间戳。 |

功能开关为 `filterEnabled`、`messageLogEnabled`、`crossServerChatEnabled` 和 `privateMessagesEnabled`。`messageLogRetentionDays` 限制在 0—365；0 表示不按天数清理。[1]

配置管理器在同一提交锁内校验版本、构造候选配置并保存。保存成功后，才更新内存配置、revision、运行时和广播。设置、过滤规则、草稿发布、备份恢复与历史回滚都使用此提交流程；局部设置更新不会重新应用频道配置。[1] [5]

编辑器应把最近快照的 `revision` 放入 `baseRevision`，或使用 `If-Match: W/"<revision>"`。响应包含新的设置和 revision，下一次保存应使用该版本。`409` 响应包含当前快照与 `currentRevision`，供调用方恢复显示；不传版本仍保留旧客户端的调用约定。[1]

### 示例：读取或修改功能开关

```bash
curl --request GET "https://panel.example.invalid/api/settings" \
  --header "Authorization: Bearer <access-token>"

curl --request PUT "https://panel.example.invalid/api/settings" \
  --header "Authorization: Bearer <access-token>" \
  --header "Content-Type: application/json" \
  --data '{"crossServerChatEnabled": false, "baseRevision": 10}'
```

将示例中的 10 替换为 GET 返回的 revision。第二个调用保存成功后会改变跨服聊天分发行为。[1]

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

### 6.1 公告活动

| 方法与路径 | 作用 | 参数与结果 |
| --- | --- | --- |
| `POST /api/campaigns` | 创建 `PREVIEW` 活动 | `channelId`、`content`、`platforms` 必需；`deliveryPolicy?`、`startAt?`、`endAt?`、`rateLimitPerHour?` 可选。 |
| `GET /api/campaigns`、`GET /api/campaigns/{id}` | 查看活动 | 列表可用 `channelId` 筛选。 |
| `POST /api/campaigns/{id}/schedule` | 安排活动 | `startAt=0` 时立即尝试投递；其他值进入 `SCHEDULED` 并安排一次激活。 |
| `POST /api/campaigns/{id}/activate` | 立即激活或重试 | 接受 `PREVIEW`、`SCHEDULED`；成功投递后进入 `ACTIVE`。 |
| `POST /api/campaigns/{id}/revoke` | 撤销活动 | 需要 `SUPER_ADMIN`；撤销后取消任务。 |

创建、安排和激活需要 ADMIN 或以上身份，并通过目标频道的资源权限检查。发送回调不可用、抛错或路由拒绝时，立即激活返回 `500`；频道当前小时配额用尽返回 `429`（业务层 `NC-429`）。失败保留原有 `PREVIEW` 或 `SCHEDULED` 状态，不消耗成功发送配额。排除故障或等待配额恢复后，在活动结束前调用同一活动的 `/activate` 重试；已经 `ACTIVE` 的活动拒绝再次激活，避免重复发送。[1] [10]

`startAt`、`endAt` 使用 Unix 毫秒，`endAt=0` 表示不设结束时间。启动恢复时，超过结束时间的记录直接变为 `EXPIRED`，不补发；未到期的 `ACTIVE` 只恢复到期任务，`SCHEDULED` 恢复激活任务。定时激活失败后保留可重试状态，并在有结束时间时安排到期任务。[10]

活动投递成功表示发送回调完成、核心消息路由接受消息；当前没有玩家端送达确认。活动持久化沿用现有尽力写入约定，失败记日志；接口结果主要描述本次运行时投递与状态变化。[10] [11]

## 7. 错误处理与集成建议

| 状态 | 常见含义 | 集成器应做什么 |
| --- | --- | --- |
| `400 Bad Request` | 缺少字段、UUID/枚举错误、无效 JSON 或被禁止的命令 | 修正请求，不要无条件重试。 |
| `401 Unauthorized` | 缺少、过期或无效 token | 按认证流程刷新/重新登录；不要记录 token。 |
| `403 Forbidden` | 角色不足、状态不允许操作或原处理人尝试审核申诉 | 按实际错误原因调整操作人或流程。 |
| `404 Not Found` | 频道、玩家、客户端或 Webhook 不存在 | 先刷新本地缓存，检查是否为并发删除。 |
| `409 Conflict` | 设置版本过期、案件或申诉并发变化、决定已经完成 | 刷新当前快照，保留未提交内容后重新判断。 |
| `429 Too Many Requests` | 活动当前小时发送配额用尽 | 等待配额恢复，并在结束时间前重试原活动。 |
| `503 Service Unavailable` | 相关子系统未启用或不可用 | 进行健康检查与告警，而不是盲目重试。 |
| `500 Internal Server Error` | 后端操作失败 | 保存脱敏请求 ID/时间与响应，查阅后端日志。 |

集成方应将 API 调用设计为可审计、可重试但不重复执行高影响动作的工作流。对发送消息、治理、重载、设置与远程控制台，建议先呈现变更预览或加入人为审批。

## 参考资料

[1]: ../../StarLink/core/src/main/java/com/nova/link/api/RestApiHandler.java "REST 路由、参数与响应实现"
[2]: ../../StarLink/core/src/main/java/com/nova/link/websocket/HttpAuthHandler.java "登录与刷新接口"
[3]: ../operations/security.md "NovaLink 管理面安全基线"
[4]: ../../examples/novalink.yml "Webhook 示例段的当前解析边界"
[5]: ../../StarLink/core/src/main/java/com/nova/link/config/ConfigManager.java "配置重载与保存"
[6]: ../../Panel/web/src/services/api.js "请求会话绑定与刷新重试"
[7]: ../../Panel/web/src/services/auth.js "登录会话与 token 轮换"
[8]: ../../StarLink/core/src/main/java/com/nova/link/moderation/ModerationManager.java "审核隔离、状态转换与冲突处理"
[9]: ../../StarLink/storage/src/main/java/com/nova/link/database/AbstractJdbcProvider.java "案件与申诉事务"
[10]: ../../StarLink/core/src/main/java/com/nova/link/announcement/CampaignManager.java "活动投递、失败重试与恢复调度"
[11]: ../../StarLink/core/src/main/java/com/nova/link/NovaLinkCore.java "活动发送回调与路由结果"
[12]: ../../StarLink/core/src/main/java/com/nova/link/channel/InvitationManager.java "邀请校验、扣额与入群"
[13]: ../../StarLink/core/src/main/java/com/nova/link/channel/ChannelManager.java "频道修改同步与持久化失败处理"
