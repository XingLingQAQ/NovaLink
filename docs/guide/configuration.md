# 配置指南

NovaLink 后端启动时读取 YAML 配置，并将其映射为服务器、数据库、安全、管理员、频道、客户端和功能开关等运行时对象。配置文件变化会被监听；成功重载后，后端会更新配置化频道和已实现的功能开关，并向已连接客户端发送不包含密码的配置同步数据。[1] [2]

> **先读这一节。** `examples/novalink.yml` 是字段位置和示例值的参考，不是可直接复制到生产环境的成品。尤其不要保留其中的弱密钥、默认账号、测试网段或外部 URL。[3]

## 配置文件与重载

后端默认从当前工作目录的 `novalink.yml` 读取配置，也接受首个命令行参数作为配置路径。启动后会监听配置所在目录的创建和修改事件；手动重载会触发客户端配置同步。同步内容只包含全局频道、频道模板，以及客户端的名称和频道信息，不包含客户端密码。[1] [2]

| 操作 | 作用 | 注意事项 |
| --- | --- | --- |
| 启动时加载 | 创建后端运行时配置 | 配置解析或数据库初始化失败会阻止正常服务启动。 |
| 编辑文件后监听重载 | 使配置变更进入运行时 | 应先在测试环境验证 YAML 与业务影响；文件保存不等于所有能力均支持热应用。 |
| `reload` 控制台命令 | 显式重载并同步配置 | 适合受控变更窗口；执行后检查日志与客户端状态。 |
| REST `POST /api/reload` | 通过管理控制面触发重载 | 需要有效 access token；应限制调用者与网络暴露面。 |

## `server`：监听与服务身份

```yaml
server:
  bind-address: 0.0.0.0
  port: 8888
  websocket-port: 8889
  secret-key: "replace-with-a-long-random-secret"
  worker-threads: 4
```

| 字段 | 作用 | 生产建议 |
| --- | --- | --- |
| `bind-address` | TCP 与管理网关绑定的本地地址 | 优先绑定私有接口或反向代理可达接口，而非无条件暴露到公网。 |
| `port` | NovaProtocol TCP 监听端口 | 仅对已批准的平台接入端放行。 |
| `websocket-port` | HTTP、REST 与 WebSocket 管理网关端口 | 将其置于受控网络或反向代理之后。 |
| `secret-key` | JWT 服务签名使用的密钥 | 使用独立、长且随机的密钥；泄露后应轮换并使旧会话失效。 |
| `worker-threads` | 网络处理工作线程数 | 先保持保守，结合负载和 JVM 指标调整。 |
| `locale` | 后端控制台语言（可选） | 未显式配置时使用后端的默认语言解析策略。 |

后端会以同一个绑定地址分别启动 TCP 服务与 WebSocket 网关。不要将 `websocket-port` 误解为只提供 WebSocket：管理网关也承载 `/api/auth/*` 和 `/api/*` 路由。[4] [5]

## `database`：持久化与缓存

```yaml
database:
  type: sqlite
  sqlite:
    file-path: data/novalink.db
    pool-size: 5
```

当前后端根据 `database.type` 选择 MySQL、PostgreSQL、SQLite、Redis 或内存提供者。示例配置把 Redis 描述为缓存选项，生产部署应在选择数据层前确认所需的持久化语义、备份路径与恢复流程。[3] [6]

| 类型 | 适用起点 | 运维注意事项 |
| --- | --- | --- |
| `sqlite` | 本地、小型、单节点验证 | 数据文件位于运行目录的相对路径时，必须纳入备份与权限管理；SQLite 适合保守的连接池设置。 |
| `mysql` | 需要独立关系数据库的部署 | 使用受控账号、最小权限、TLS/网络访问控制和定期备份。MariaDB 使用 `mysql` 配置段。 |
| `postgresql` | PostgreSQL 运维体系 | 明确主机、端口、账号、备份与连接池上限。 |
| `redis` | 需要 Redis 提供者的场景 | 不应把无备份的缓存实例当作持久化替代品。 |
| `memory` | 短暂测试 | 重启即丢失数据，禁止作为生产数据保存方案。 |

## `security` 与 `super-admins`：先划边界，再授予权限

```yaml
security:
  allowed-ips:
    - 10.20.0.0/24
  ip-ban-duration: 300

super-admins:
  - uuid: "00000000-0000-0000-0000-000000000000"
    password-hash: "<sha-256-hex>"
```

`allowed-ips` 用于限制客户端连接来源；连续认证失败会触发 IP 封禁逻辑。超级管理员使用 UUID 与密码哈希注册，并可同时作为管理面板账户来源。这里的防护并不替代网络层 ACL、反向代理访问控制、TLS 与凭据轮换。[3] [4]

| 配置项 | 运行时影响 | 推荐做法 |
| --- | --- | --- |
| `allowed-ips` | 决定可尝试连接后端的 IP/CIDR 范围 | 使用最小网段，随基础设施变更同步维护。 |
| `ip-ban-duration` | 认证连续失败后的封禁时长（秒） | 结合预期运维来源、自动化行为和误封处理流程配置。 |
| `super-admins[].uuid` | 绑定管理员身份 | 使用真实受控的身份源，不复用示例 UUID。 |
| `super-admins[].password-hash` | 管理员密码哈希 | 仅保存受控的哈希值；不要向日志、Issue 或 Wiki 粘贴凭据。 |
| `super-admins[].username` | 可选的管理面板登录名 | 为空时后端会回退使用 UUID 字符串作为登录标识。 |

## 频道模型：`global_channels`、`templates` 与 `clients[].channels`

频道定义决定消息边界，而不是前端展示名称。全局频道以 `GLOBAL` 作用域创建，可由客户端权限节点控制；每个客户端下的频道默认作为该客户端的 `SERVER` 频道，并且即使 YAML 误写为 `GLOBAL`，后端也会为了隔离而强制为 `SERVER`。私有频道在运行时管理，不应依赖配置热重载改写。[4]

```yaml
global_channels:
  global:
    display_name: "Global"
    permission: "novachat.channel.global"
    max_capacity: 0

templates:
  standard_local:
    display_name: "Local"
    scope: SERVER
    max_capacity: 100

clients:
  - username: "survival"
    display_name: "Survival"
    channels:
      local:
        use_template: "standard_local"
      resource:
        display_name: "Resource World"
        scope: SERVER
        allowed_worlds:
          - "resource_world"
```

| 配置区域 | 作用 | 关键约束 |
| --- | --- | --- |
| `global_channels` | 定义跨已授权客户端的 `GLOBAL` 频道 | 可设置权限与容量；需结合客户端权限授予检查。 |
| `templates` | 复用频道显示名、作用域、权限、容量和世界范围 | 模板提供默认值，客户端频道可覆盖。 |
| `clients` | 定义可认证的接入端身份 | `username`/`password` 是认证边界；显示名仅用于展示。 |
| `clients[].permissions` | 为已认证客户端引导全局频道权限 | 未配置或为空时，后端保留向后兼容的通配授权行为；生产环境应显式最小化授权。 |
| `clients[].channels` | 定义该客户端持有的频道 | 不能通过该段创建跨客户端的 `GLOBAL` 频道。 |
| `allowed_worlds` | 限制频道适用世界 | 依赖平台侧上报与频道状态，必须在目标平台实测。 |

频道成员、世界条件、权限和消息路由共同决定“谁会收到消息”。因此，改动频道定义后应检查后端频道列表、已连接客户端的配置同步结果以及实际消息路径，而不应只检查 YAML 是否能加载。[2] [7]

## `features`：当前热应用开关

当前示例和后端实现包含以下开关：

```yaml
features:
  filter-enabled: true
  message-log-enabled: false
  cross-server-chat-enabled: true
```

配置重载监听器会把这三个值分别应用到敏感词过滤、消息日志与跨服聊天分发的运行时开关。变更前应明确预期影响，特别是关闭跨服分发后消息可见范围会发生变化。[4]

## 示例中存在但当前不由配置加载器解析的段落

`examples/novalink.yml` 还保留了 `webhooks`、`filter` 与 `announcements` 段落的前瞻性示例，并明确说明当前 `ConfigLoader` 只解析本页列出的核心顶级段。尽管后端包含 Webhook、过滤和公告相关的运行时类或管理 API，**不要据此推断编辑这些 YAML 段即可启用对应能力。** 在使用这些功能前，请以当前代码路径、管理接口和测试结果为准。[3] [8]

## 变更核对清单

| 变更类型 | 变更前 | 变更后 |
| --- | --- | --- |
| 密钥或管理员凭据 | 准备轮换与回退方案 | 验证旧令牌、面板登录与审计日志策略。 |
| 允许 IP | 确认所有接入端出口地址 | 验证合法客户端可连、非授权来源被拒。 |
| 数据库 | 备份并验证恢复 | 检查迁移、连接、读写和备份任务。 |
| 频道与权限 | 列出受影响服务器与玩家群体 | 核对频道、权限授予与实际消息扇出。 |
| 功能开关 | 说明运营预期和回退条件 | 在受控时段重载并观察日志、面板与消息行为。 |

## 参考资料

[1]: ../../StarLink/core/src/main/java/com/nova/link/NovaLinkMain.java "配置路径与运行时装配"
[2]: ../../StarLink/core/src/main/java/com/nova/link/config/ConfigManager.java "监听、重载与配置同步"
[3]: ../../examples/novalink.yml "完整后端示例配置与未解析段声明"
[4]: ../../StarLink/core/src/main/java/com/nova/link/NovaLinkMain.java "配置化频道、权限引导与功能开关应用"
[5]: ../../StarLink/core/src/main/java/com/nova/link/websocket/WebSocketGateway.java "管理网关的 REST/WS 组合"
[6]: ../../StarLink/core/src/main/java/com/nova/link/NovaLinkMain.java "数据库提供者选择"
[7]: ../../StarLink/core/src/main/java/com/nova/link/channel/MessagePipeline.java "消息路由处理阶段"
[8]: ../../StarLink/core/src/main/java/com/nova/link/config/ConfigLoader.java "当前 YAML 解析范围"
