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
| `GET /api/channels` | 列出可见频道 | 无 | 返回当前身份可见的频道数组与数量。 |
| `GET /api/channels/{channelId}` | 获取频道详情 | 路径参数 | 频道不存在返回 `404`。 |
| `POST /api/channels` | 创建频道 | `id?`、`displayName?`、`scope?`、`maxCapacity?`、`permission?` | 这是管理操作；未给 ID 时会生成 ID。 |
| `PUT /api/channels/{channelId}` | 更新可变字段 | `displayName?`、`maxCapacity?`、`permission?`、`permissionPresent?`、`slowModeSeconds?` | 不应假设可修改所有频道结构或作用域。 |
| `DELETE /api/channels/{channelId}` | 删除频道 | 无 | 持久化删除成功后清理成员；应先确认业务影响。 |
| `GET /api/channels/{channelId}/members` | 查看成员 | 无 | 暴露玩家 UUID/名称，控制访问范围。 |
| `POST /api/channels/{channelId}/invite` | 创建邀请 | `ttlMillis?` | 需要保护返回的邀请码。 |
| `DELETE /api/channels/{channelId}/invitations/{code}` | 撤销频道邀请 | 无 | 保留使用次数、使用人和首次撤销时间；记录不存在时返回 `404`。 |
| `POST /api/messages` | 以 API 身份向频道发送消息 | `channelId`、`content`、`senderName?` | 消息会进入路由；不要用于未审计批量推送。 |

创建频道的 `scope` 使用 `GLOBAL`、`SERVER` 或 `PRIVATE` 枚举语义。REST 管理创建的私有频道会使用后台的管理客户端标识；对外部系统而言，应先明确频道边界，再自动化创建。[1]

邀请的 `ttlMillis` 为正整数毫秒，省略或传 `null` 时使用 24 小时。`0`、负数、小数、超出整数范围或使到期时间溢出的值返回 `400`，不生成或保存邀请码。频道不存在或不可见仍返回 `404`。[1] [12]

频道更新先验证容量，再修改字段；提供非正容量时不会留下部分改名。邀请接受流程在同一频道锁内完成成员、容量检查和扣额入群；已有成员或频道满员返回 `NC-409`，保留邀请码剩余次数。频道删除持久化失败时保留运行时登记、索引、成员与玩家当前频道；创建或删除的存储失败返回 `500`，参数错误仍返回 `400`。[1] [12] [13]

删除频道会同时删除该频道的旧邀请码；同名重建后应创建新邀请。邀请创建与频道删除共用频道锁，避免删除后才保存旧邀请。JDBC 在同一连接事务中删除邀请和动态频道，任一删除失败都回滚。CONFIG 重载移除只清理邀请，不删除被配置覆盖的同名动态记录。[12] [13] [9]

协议 JOIN 也与频道删除协调：访问检查、成员加入和玩家频道状态更新绑定同一频道对象。删除后同名重建不会沿用旧 JOIN 的授权，旧对象返回 `NC-404`；JOIN 先完成时，删除再清理其成员与在线状态。[20]

协议 CREATE 的创建者登记、权限、持久化和玩家状态提交同样核对频道对象身份。删除后到达的旧创建结果返回 `NC-404`，不修改同名新频道。ACCEPT 在释放频道锁前完成玩家状态更新，避免删除清理后写回旧频道引用。[12] [20]

Redis 删除通过 `SCAN` 查找该频道的邀请码，再由 Lua 脚本核对归属并删除，动态频道定义和索引也在脚本中移除。需允许 `SCAN`、`GET`、`EVAL`、`DEL` 和 `SREM`；扫描范围为当前邀请键。[16]

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
| `GET /api/mutes` | 查看活跃禁言 | 无 | 仅返回可见范围的记录，包含离线玩家；`total` 为过滤后的条数。 |
| `GET /api/bans` | 查看封禁 | 无 | 按玩家聚合可见范围的封禁；没有可见封禁的玩家不会出现在列表中。 |

REST 层将这些操作视作后台发起的管理动作。自动化系统应在调用前完成 UUID、频道 ID、持续时间和理由校验，并保存操作请求与响应记录。[1]

频道级治理与频道访问共用 `PanelResourcePolicy`：VIEWER 只能读取全局限制及 `GLOBAL` 频道限制；ADMIN 可读取和管理全局、`GLOBAL`、`SERVER` 范围；`PRIVATE` 仅限 SUPER_ADMIN。单人禁言、解禁言、封禁、解封及踢出都检查目标频道，频道不可见与不存在统一返回 `404 Channel not found`。省略 `channelId` 仍表示全局禁言或封禁。[18]

已删除频道遗留的限制仅向 SUPER_ADMIN 展示，可通过单人 `/unmute` 或 `/unban` 清理。新增频道限制和批量治理仍要求目标频道存在。[1] [18]

禁言与封禁的 `durationMs` 使用非负整数毫秒，省略或传 `0` 表示永久。负数、非整数、超出整数范围或使到期时间溢出的值返回 `400`；批量接口（含 `dryRun`）执行相同校验。非法时长不会修改原限制、移除成员或发送成功通知。[1] [15]

频道级禁言或封禁过期后，仍检查有效的全局限制。查询即时忽略并淘汰过期缓存，数据库过期记录由每分钟清理任务回收；查询不会按已过期的旧快照删除刚续期的数据库记录。[14]

内存与 Redis 的定时清理只删除仍与扫描快照一致的过期条目，保留扫描后写入的续期记录。Redis 使用单键 Lua 脚本原子比对并删除，命令权限需允许 `EVAL`、`HGET` 和 `HDEL`。[16] [17]

禁言、解除禁言、封禁和解封先提交存储，再发布缓存及成功副作用；同一玩家的写入按相同顺序执行。存储失败返回 `500`（`NC-510`），保留本次操作前的限制，不发送成功通知，也不因失败封禁移除成员。无数据库的独立管理器仍可仅在内存中操作。[14]

封禁写入与协议侧 JOIN、ACCEPT、CREATE 按玩家串行。封禁先完成时，后续入群返回 `NC-403`，邀请不会扣额；先入群的请求可成功，后续封禁会移除其成员资格。全局封禁还拒绝创建私有频道，避免通过创建者自动入群绕过限制。[19]

封禁成功后，共享管理器同时清理在线玩家的 `joinedChannels` 与 `activeChannel`。频道封禁只移除指定频道，全局封禁清空全部频道引用，包括已删除频道的引用。清理结果标记为脏状态，沿现有玩家状态保存流程落库；封禁存储失败时保留原有成员与玩家状态。[19]

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

### 4.2 批量治理

`POST /api/moderation/batch` 需要 ADMIN 或以上身份。必需字段为 `action`（`mute/unmute/ban/unban`）、`targetIds`（最多 100 个、不重复的玩家 UUID）、`reason` 和 `idempotencyKey`；可选字段为 `channelId`、`durationMs`、`caseId` 和 `dryRun`。[1]

`dryRun=true` 返回预览，不执行治理、写审计或占用幂等 key。实际执行按目标分别处理，响应为 `200`，由 `results`、`succeeded` 和 `failed` 表达逐项结果。部分失败不会撤销其他目标的成功操作。[1]

预览、执行和幂等重放均在读取缓存结果前检查目标频道的资源权限，并使用账号当前角色。SUPER_ADMIN 降为 ADMIN 后，原私有频道批次的重放返回 `404`，不会返回旧结果中的受保护信息。[1] [18]

幂等 key 属于当前操作人，并绑定动作、目标顺序、频道、有效时长、原因和案件 ID。相同操作人的相同请求在缓存保留期间返回原响应，不再次执行或写审计；token 刷新不改变这一归属。同一 key 携带不同有效内容返回 `409`，应为新的批次使用新 key。批次仍在执行时，重复请求返回 `409`，应等待后使用原 key 和原内容重试。[1]

缓存位于当前后端进程，已完成结果保留最多 10 分钟，总容量为 1,024 个批次。容量不足时清理过期及最早完成的结果，执行中的记录不会被淘汰；全部记录都在执行时，新批次返回 `503`。重启或请求落到其他后端实例时，原进程的缓存不可用。对已返回的逐项失败，确认当前状态后用新 key 重试失败目标。[1]

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
| `404 Not Found` | 对象不存在或目标频道对当前身份不可见 | 刷新可见资源列表，核对目标与当前账号角色。 |
| `409 Conflict` | 设置版本过期、案件或申诉并发变化、决定已经完成，或批次 key 冲突/仍在执行 | 按错误原因刷新快照、调整批次 key，或等待原批次完成后重试。 |
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
[9]: ../../StarLink/storage/src/main/java/com/nova/link/database/AbstractJdbcProvider.java "JDBC 频道删除与审核事务"
[10]: ../../StarLink/core/src/main/java/com/nova/link/announcement/CampaignManager.java "活动投递、失败重试与恢复调度"
[11]: ../../StarLink/core/src/main/java/com/nova/link/NovaLinkCore.java "活动发送回调与路由结果"
[12]: ../../StarLink/core/src/main/java/com/nova/link/channel/InvitationManager.java "邀请校验、扣额与入群"
[13]: ../../StarLink/core/src/main/java/com/nova/link/channel/ChannelManager.java "频道修改同步与持久化失败处理"
[14]: ../../StarLink/core/src/main/java/com/nova/link/moderation/AbstractModerationManager.java "禁言与封禁共享缓存、查询与过期处理"
[15]: ../../StarLink/core/src/main/java/com/nova/link/moderation/DurationUtils.java "时长与到期时间校验"
[16]: ../../StarLink/storage/src/main/java/com/nova/link/database/RedisProvider.java "Redis 过期清理的条件删除"
[17]: ../../StarLink/storage/src/main/java/com/nova/link/database/MemoryProvider.java "内存过期清理的条件删除"
[18]: ../../StarLink/core/src/main/java/com/nova/link/auth/PanelResourcePolicy.java "频道与治理资源可见性"
[19]: ../../StarLink/core/src/main/java/com/nova/link/ban/BanManager.java "封禁与协议入群串行协调"
[20]: ../../StarLink/core/src/main/java/com/nova/link/network/ChannelActionHandler.java "JOIN 与频道删除的对象身份校验"
