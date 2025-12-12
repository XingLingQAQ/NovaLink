# Requirements Document

## Introduction

NovaChat & NovaLink 是一个分布式跨平台 Minecraft 聊天基础设施系统。NovaChat 作为前端插件部署在各类 Minecraft 服务端（Bukkit/Spigot、Velocity/BungeeCord、Nukkit、LeviLamina），NovaLink 作为独立 Java 后端负责消息路由、权限管理、数据持久化等核心逻辑。系统采用星型拓扑架构，实现逻辑中心化与呈现去中心化的设计哲学。

## Glossary

- **NovaChat**: 前端 Minecraft 插件，负责拦截聊天事件、渲染消息、与后端通信（命令：`/novachat` 或 `/nc`）
- **NovaLink**: 独立 Java 后端服务，负责消息路由、权限校验、数据持久化（命令：`/novalink` 或 `/nl`）
- **Client（客户端）**: 连接到 NovaLink 的 NovaChat 插件实例，需要用户名/密码认证，是频道和权限的基本管理单元
- **Channel（频道）**: 消息隔离单元，玩家只能收到所在频道的消息
- **Scope（作用域）**: 频道的消息传播范围，分为 GLOBAL（全网）和 SERVER（单服）
- **World Filter（世界过滤器）**: SERVER 频道的可选参数，限制频道仅在指定世界内生效
- **NovaProtocol**: 基于 TCP 的自定义二进制通信协议
- **PlaceholderAPI (PAPI)**: Bukkit 生态的变量占位符 API
- **EzColor**: 支持 `&` 符号和 Hex 颜色代码的颜色渲染库

### 频道类型层级（扁平化设计）

| 频道类型 | 作用域 (Scope) | 限制条件 (Filter) | 逻辑描述 |
|---------|---------------|------------------|---------|
| **全网频道** | GLOBAL | 无（或权限节点） | 跨服互通，所有连接到后端的服务器玩家都能看到 |
| **服务器频道** | SERVER | 无 | 单服互通，仅限当前服务器内的所有玩家 |
| **世界频道** | SERVER | `allowed_worlds` | 单服指定世界互通，仅限当前服务器内且身处指定世界的玩家 |
| **私有频道** | PRIVATE | 所属客户端 + 密码 | 单服私有，玩家自行创建，挂载在当前服务器下 |

### 管理员权限层级

| 角色 | 认证方式 | 管理范围 | 核心能力 |
|-----|---------|---------|---------|
| **超级管理员** | 后端配置 + 密码认证 | 全系统 | 跨服监听、管理所有频道/客户端、全局禁言 |
| **客户端管理员** | MC权限节点 `novachat.admin` | 所属客户端 | 管理本服频道、本服禁言、绕过世界限制 |
| **频道管理员** | 频道所有者或被授权 | 指定频道 | 踢出成员、修改密码、邀请玩家 |
| **普通玩家** | 无 | 自身 | 加入/退出频道、创建私有频道 |

## Requirements

---

## 第一部分：核心架构

### Requirement 1: 客户端认证系统

**User Story:** As a 服务器管理员, I want to 配置客户端凭据并安全连接到后端, so that 只有授权的服务器才能接入聊天网络。

#### Acceptance Criteria

1. WHEN 插件启动并尝试连接后端 THEN NovaChat SHALL 使用配置文件中的用户名和密码进行 SHA-256 哈希认证
2. WHEN 后端收到认证请求且凭据匹配 THEN NovaLink SHALL 建立连接并返回成功响应
3. WHEN 后端收到认证请求且凭据不匹配 THEN NovaLink SHALL 拒绝连接并返回错误代码 NC-401
4. WHEN 认证失败 THEN NovaChat SHALL 在控制台输出详细错误日志说明认证失败原因
5. WHEN 同一 IP 连续 3 次认证失败 THEN NovaLink SHALL 临时封禁该 IP（可配置时长）

### Requirement 2: 权限体系架构

**User Story:** As a 系统管理员, I want to 建立清晰的权限层级, so that 不同角色可以管理各自范围内的资源。

#### Acceptance Criteria

1. THE NovaLink SHALL 实现四级权限体系：超级管理员 > 客户端管理员 > 频道管理员 > 普通玩家
2. WHEN 超级管理员执行 `/nc auth <密码>` 且密码正确 THEN NovaLink SHALL 授予全系统临时管理权限
3. WHEN 玩家拥有 `novachat.admin` 权限节点 THEN NovaLink SHALL 识别为客户端管理员
4. WHEN 玩家拥有 `novachat.bypass.world` 权限节点 THEN NovaChat SHALL 允许绕过世界限制加入本服任何频道
5. WHEN 玩家创建私有频道 THEN NovaLink SHALL 自动授予该玩家频道管理员权限
6. THE NovaLink SHALL 使用玩家 UUID 存储和识别玩家权限状态
7. WHEN 低级权限用户尝试执行高级操作 THEN NovaLink SHALL 返回错误代码 NC-403

---

## 第二部分：频道系统

### Requirement 3: 频道核心架构

**User Story:** As a 玩家, I want to 加入不同的聊天频道, so that 我可以与特定群体的玩家交流。

#### Acceptance Criteria

1. THE NovaLink SHALL 实现扁平化频道架构：仅 GLOBAL 和 SERVER 两种基础作用域
2. WHEN 玩家发送消息到频道 THEN NovaLink SHALL 根据频道作用域将消息路由到对应范围的在线成员
3. WHEN 玩家退出游戏后重新登录 THEN NovaLink SHALL 恢复玩家之前的频道状态
4. THE NovaLink SHALL 为每个频道配置唯一 ID、显示名称、消息格式和最大容量
5. THE SERVER 频道数据流 SHALL 永远不跨越服务器边界（物理隔离）

### Requirement 4: 全网频道管理 (GLOBAL Scope)

**User Story:** As a 超级管理员, I want to 配置全网络可用的公共频道, so that 所有客户端的授权玩家都可以跨服交流。

#### Acceptance Criteria

1. WHEN 后端启动 THEN NovaLink SHALL 从配置文件 `global_channels` 节点加载全网频道定义
2. WHEN 玩家请求加入全网频道 THEN NovaLink SHALL 检查玩家的 MC 权限节点
3. WHEN 全网频道消息发送 THEN NovaLink SHALL 路由到所有客户端的所有符合条件的在线成员
4. THE 全网频道 SHALL 仅允许超级管理员进行创建、修改和删除操作
5. WHEN 全网频道配置变更 THEN NovaLink SHALL 通过 ConfigSyncPacket 同步到所有客户端

### Requirement 5: 服务器频道管理 (SERVER Scope)

**User Story:** As a 客户端管理员, I want to 配置仅限本服务器使用的频道, so that 我可以为本服务器创建专属聊天区域。

#### Acceptance Criteria

1. WHEN 客户端管理员创建服务器频道 THEN NovaLink SHALL 将频道配置存储在该客户端的 `channels` 节点下
2. WHEN 玩家请求加入服务器频道 THEN NovaLink SHALL 验证玩家是否通过该客户端连接
3. WHEN 服务器频道消息发送 THEN NovaLink SHALL 仅路由到同一客户端内的在线成员
4. THE 服务器频道 SHALL 允许客户端管理员和超级管理员进行创建、修改和删除操作
5. THE NovaLink SHALL 支持频道模板 (templates) 功能，避免重复配置

### Requirement 6: 世界频道管理 (World Filter)

**User Story:** As a 客户端管理员, I want to 为服务器频道配置世界限制, so that 频道仅在指定世界内生效。

#### Acceptance Criteria

1. WHEN 服务器频道配置 `allowed_worlds` 参数 THEN NovaLink SHALL 将其识别为世界频道
2. WHEN 玩家在指定世界中 THEN NovaChat SHALL 自动将玩家加入该世界频道
3. WHEN 玩家离开指定世界 THEN NovaChat SHALL 自动将玩家从该世界频道移除并加入默认频道
4. WHEN 服务器频道未配置 `allowed_worlds` THEN NovaLink SHALL 视为该服务器内所有世界通用
5. THE NovaChat-Bukkit 和 NovaChat-Nukkit SHALL 监听 `PlayerChangedWorldEvent` 实现自动路由
6. WHEN 拥有 `novachat.bypass.world` 权限的管理员 THEN NovaChat SHALL 允许强制加入任何世界频道

### Requirement 7: 私有频道管理 (PRIVATE Scope)

**User Story:** As a 玩家, I want to 创建和管理自己的私有频道, so that 我可以与朋友私密交流。

#### Acceptance Criteria

1. WHEN 玩家执行 `/nc create <名称> [密码]` THEN NovaChat SHALL 向后端请求创建私有频道
2. WHEN 私有频道创建成功 THEN NovaLink SHALL 生成简短唯一 ID（如 `NC-5A3F`）并返回给玩家
3. WHEN 私有频道未设置密码 THEN NovaLink SHALL 自动生成 6 位随机密码并返回给创建者
4. WHEN 玩家请求加入私有频道 THEN NovaLink SHALL 验证玩家属于该频道所在客户端且密码正确
5. WHEN 频道所有者执行删除命令 THEN NovaLink SHALL 解散频道并将所有成员移至默认频道
6. THE 私有频道 SHALL 挂载在创建者所在的客户端下，严格物理隔离
7. THE 私有频道所有者 SHALL 自动成为该频道的频道管理员

### Requirement 8: 频道邀请系统

**User Story:** As a 频道管理员, I want to 邀请其他玩家加入频道, so that 我可以方便地添加成员。

#### Acceptance Criteria

1. WHEN 频道管理员执行 `/nc invite <玩家> [频道ID]` THEN NovaLink SHALL 生成 6 位邀请码
2. WHEN 邀请码生成 THEN NovaLink SHALL 设置 24 小时有效期
3. WHEN 受邀玩家执行 `/nc accept <邀请码>` THEN NovaLink SHALL 验证邀请码并加入频道
4. WHEN 邀请码过期或已使用 THEN NovaLink SHALL 返回错误代码 NC-410 或 NC-411
5. WHEN 频道管理员执行 `/nc revoke <邀请码>` THEN NovaLink SHALL 撤销邀请码

### Requirement 9: 自动路由模式

**User Story:** As a 玩家, I want to 在切换世界时自动切换到对应频道, so that 我不需要手动管理频道。

#### Acceptance Criteria

1. WHEN 玩家从一个世界传送到另一个世界 THEN NovaChat SHALL 自动检测新世界适用的频道
2. WHEN 新世界属于某个世界频道的范围 THEN NovaChat SHALL 自动将玩家加入该频道
3. WHEN 新世界不属于任何特殊频道 THEN NovaChat SHALL 自动将玩家回退到无限制的服务器频道
4. THE NovaChat SHALL 在自动切换时向玩家发送提示消息


---

## 第三部分：消息处理

### Requirement 10: 消息格式与颜色渲染

**User Story:** As a 服务器管理员, I want to 在插件端自定义消息格式, so that 我可以根据服务器风格定制聊天外观。

#### Acceptance Criteria

1. WHEN 消息包含 PlaceholderAPI 变量 THEN NovaChat SHALL 在发送前解析变量值
2. WHEN 消息包含 `&` 颜色代码 THEN NovaChat SHALL 渲染为对应颜色
3. WHEN 消息包含 Hex 颜色代码（如 `&#FFA500`）THEN NovaChat SHALL 渲染为对应颜色
4. WHEN 插件配置频道消息格式 THEN NovaChat SHALL 在本地应用格式模板
5. THE NovaChat SHALL 支持 EzColor 库的所有颜色格式
6. THE 消息格式配置 SHALL 在插件端 config.yml 中定义，而非后端

### Requirement 11: 原版聊天兼容模式

**User Story:** As a 服务器管理员, I want to 控制插件是否替换原版聊天, so that 我可以灵活配置聊天行为。

#### Acceptance Criteria

1. WHEN 配置 `replace_vanilla: false` THEN NovaChat SHALL 保留原版聊天功能，仅命令消息发送到频道
2. WHEN 配置 `replace_vanilla: true` THEN NovaChat SHALL 拦截所有聊天消息并转发到当前频道
3. WHEN 玩家执行 `/nc toggle` THEN NovaChat SHALL 切换当前玩家的聊天模式
4. WHEN 玩家使用 `/nc <频道ID> msg <消息>` THEN NovaChat SHALL 强制发送到指定频道（无论模式）

### Requirement 12: 消息过滤系统

**User Story:** As a 服务器管理员, I want to 过滤敏感词汇, so that 聊天环境更加健康。

#### Acceptance Criteria

1. WHEN 消息包含敏感词 THEN NovaLink SHALL 将敏感词替换为 `***`
2. THE NovaLink SHALL 内置常见敏感词库（500+ 词汇）
3. WHEN 管理员配置自定义敏感词 THEN NovaLink SHALL 添加到过滤列表
4. THE NovaLink SHALL 支持正则表达式匹配敏感词

---

## 第四部分：管理功能

### Requirement 13: 禁言系统

**User Story:** As a 管理员, I want to 禁言违规玩家, so that 我可以维护聊天秩序。

#### Acceptance Criteria

1. WHEN 管理员执行 `/nc mute <玩家> <时间> [频道ID]` THEN NovaLink SHALL 记录禁言信息
2. WHEN 被禁言玩家发送消息 THEN NovaLink SHALL 拒绝消息并返回禁言提示
3. WHEN 频道管理员执行禁言 THEN NovaLink SHALL 限制范围为其管理的私有频道，最长 1 小时
4. WHEN 客户端管理员执行禁言 THEN NovaLink SHALL 限制范围为所属客户端所有频道，最长 24 小时
5. WHEN 超级管理员执行禁言 THEN NovaLink SHALL 允许任意频道禁言，无时间限制
6. WHEN 禁言时间到期 THEN NovaLink SHALL 自动解除禁言状态

### Requirement 14: 公告系统

**User Story:** As a 管理员, I want to 向频道发送公告, so that 我可以通知玩家重要信息。

#### Acceptance Criteria

1. WHEN 管理员执行 `/nc announce <频道ID> <内容>` THEN NovaLink SHALL 向频道发送公告
2. WHEN 配置定时公告 THEN NovaLink SHALL 按 Cron 表达式周期发送
3. WHEN 配置加入公告 THEN NovaLink SHALL 在玩家加入频道时发送
4. WHEN 频道管理员创建公告 THEN NovaLink SHALL 限制范围为其管理的私有频道
5. WHEN 客户端管理员创建公告 THEN NovaLink SHALL 限制范围为所属客户端所有频道
6. WHEN 超级管理员创建公告 THEN NovaLink SHALL 允许任意频道和跨频道广播

### Requirement 15: Title 发送功能

**User Story:** As a 管理员, I want to 向玩家发送 Title 消息, so that 我可以醒目地通知玩家。

#### Acceptance Criteria

1. WHEN 管理员执行 `/nc title <频道ID> <标题> [副标题]` THEN NovaChat SHALL 向频道玩家发送 Title
2. WHEN 频道管理员发送 Title THEN NovaLink SHALL 限制范围为其管理的私有频道
3. WHEN 客户端管理员发送 Title THEN NovaLink SHALL 限制范围为所属客户端所有频道
4. WHEN 超级管理员发送 Title THEN NovaLink SHALL 允许任意频道发送
5. THE NovaChat SHALL 支持 Title 中的颜色代码

### Requirement 16: 踢出成员功能

**User Story:** As a 管理员, I want to 将违规玩家踢出频道, so that 我可以维护频道秩序。

#### Acceptance Criteria

1. WHEN 管理员执行 `/nc kick <玩家> [频道ID]` THEN NovaLink SHALL 将玩家移出频道
2. WHEN 频道管理员执行踢出 THEN NovaLink SHALL 限制范围为其管理的私有频道
3. WHEN 客户端管理员执行踢出 THEN NovaLink SHALL 限制范围为所属客户端所有频道
4. WHEN 超级管理员执行踢出 THEN NovaLink SHALL 允许任意频道踢出
5. WHEN 玩家被踢出 THEN NovaLink SHALL 将玩家移至默认频道

### Requirement 17: 超级管理员远程监控

**User Story:** As a 超级管理员, I want to 远程监听任意服务器的任意频道, so that 我可以全局管理聊天网络。

#### Acceptance Criteria

1. WHEN 超级管理员执行 `/nc admin spy <server_name> <channel_id>` THEN NovaLink SHALL 开启远程监听
2. WHEN 远程监听开启 THEN NovaLink SHALL 将目标频道的消息转发给超级管理员
3. WHEN 超级管理员在监听状态下发送消息 THEN NovaLink SHALL 将消息发送到目标频道
4. THE 超级管理员 SHALL 能够同时监听多个频道
5. WHEN 超级管理员执行 `/nc admin spy off` THEN NovaLink SHALL 关闭所有远程监听


---

## 第五部分：系统配置

### Requirement 18: 热重载配置

**User Story:** As a 服务器管理员, I want to 在不重启服务的情况下重载配置, so that 我可以快速应用配置变更。

#### Acceptance Criteria

1. WHEN 管理员执行 `/nc reload` THEN NovaChat SHALL 重新加载本地配置文件
2. WHEN 管理员执行 `/nl reload` THEN NovaLink SHALL 重新加载后端配置并广播 ConfigSyncPacket
3. WHEN 配置文件缺少新增配置项 THEN NovaChat 和 NovaLink SHALL 自动补全缺失项并保留现有配置
4. THE NovaChat 和 NovaLink SHALL 在配置更新时保留用户注释和格式

### Requirement 19: 调试模式

**User Story:** As a 开发者, I want to 启用调试模式查看详细日志, so that 我可以排查问题。

#### Acceptance Criteria

1. WHEN 配置文件中 `debug: true` THEN NovaChat SHALL 输出详细的消息路由和数据包日志
2. WHEN 配置文件中 `debug: true` THEN NovaLink SHALL 输出数据库查询、WebSocket 握手和处理耗时日志
3. WHEN 管理员执行 `/nc debug [on|off]` THEN NovaChat SHALL 动态切换调试模式
4. WHEN 数据包处理超过 100ms THEN NovaLink SHALL 输出性能警告日志
5. THE 调试日志 SHALL 包含"玩家 X 尝试加入频道 Y，当前世界 Z，是否允许：是/否"格式

### Requirement 20: 配置文件结构

**User Story:** As a 服务器管理员, I want to 配置文件结构清晰易读, so that 我可以方便地修改配置。

#### Acceptance Criteria

1. THE NovaLink 配置 SHALL 包含三个主要节点：`global_channels`、`templates`、`clients`
2. THE `global_channels` 节点 SHALL 定义全网频道（GLOBAL 作用域）
3. THE `templates` 节点 SHALL 定义可复用的频道模板
4. THE `clients` 节点 SHALL 定义客户端配置及其下属的 SERVER 频道
5. THE 频道配置 SHALL 支持 `use_template` 属性引用模板并覆盖部分属性
6. THE 配置文件 SHALL 包含详细的中文注释说明

### Requirement 21: 后端控制台命令

**User Story:** As a 系统管理员, I want to 通过控制台管理后端, so that 我可以在无 GUI 环境下操作。

#### Acceptance Criteria

1. WHEN 管理员执行 `/nl reset` THEN NovaLink SHALL 重置配置至默认状态（需二次确认）
2. WHEN 管理员执行 `/nl backup <名称>` THEN NovaLink SHALL 创建数据库快照
3. WHEN 管理员执行 `/nl shutdown` THEN NovaLink SHALL 安全关闭后端服务
4. WHEN 管理员执行 `/nl status` THEN NovaLink SHALL 显示系统状态信息
5. WHEN 管理员执行 `/nl audit <类型> <ID>` THEN NovaLink SHALL 查询操作日志

---

## 第六部分：数据与平台

### Requirement 22: 数据库持久化

**User Story:** As a 服务器管理员, I want to 持久化存储聊天数据, so that 数据不会因重启丢失。

#### Acceptance Criteria

1. WHEN 后端配置 MySQL THEN NovaLink SHALL 存储频道配置、玩家记录、封禁列表
2. WHEN 后端配置 Redis THEN NovaLink SHALL 缓存玩家在线状态和频道成员列表
3. WHEN 数据库功能禁用 THEN NovaLink SHALL 使用内存存储（重启后数据丢失）
4. WHEN 玩家重新登录 THEN NovaLink SHALL 从数据库恢复玩家频道状态
5. THE NovaLink SHALL 使用 HikariCP 连接池管理数据库连接

### Requirement 23: 跨平台支持

**User Story:** As a 服务器管理员, I want to 在不同平台部署插件, so that 我可以统一管理多种服务端。

#### Acceptance Criteria

1. THE NovaChat-Bukkit SHALL 支持 Bukkit/Spigot/Paper 服务端
2. THE NovaChat-Velocity SHALL 支持 Velocity 代理端并处理聊天签名问题
3. THE NovaChat-BungeeCord SHALL 支持 BungeeCord 代理端
4. THE NovaChat-Nukkit SHALL 支持 Nukkit 基岩版服务端
5. THE NovaChat-LeviLamina SHALL 支持 LeviLamina (BDS) 基岩版服务端
6. THE 所有平台 SHALL 使用统一的命令格式和功能集

### Requirement 24: Web 管理面板

**User Story:** As a 系统管理员, I want to 通过 Web 界面管理系统, so that 我可以更方便地进行管理操作。

#### Acceptance Criteria

1. WHEN 后端配置启用 Web 面板 THEN NovaLink SHALL 启动 WebSocket 网关
2. THE Web 面板 SHALL 提供实时消息监控功能
3. THE Web 面板 SHALL 提供频道、玩家、客户端管理功能
4. WHEN 管理员登录 THEN Web 面板 SHALL 使用 JWT 进行身份验证
5. THE Web 面板 SHALL 使用 Vue.js 3 构建现代化界面

---

## 第七部分：扩展功能

### Requirement 25: API 接口

**User Story:** As a 开发者, I want to 通过 API 扩展插件功能, so that 我可以与其他系统集成。

#### Acceptance Criteria

1. THE NovaChat SHALL 提供 `ChannelMessageEvent` 事件供其他插件监听
2. THE NovaChat SHALL 提供 `PlayerChannelSwitchEvent` 事件供其他插件监听
3. THE NovaChat SHALL 提供 `NovaChatAPI.sendToChannel()` 方法发送跨频道消息
4. THE NovaLink SHALL 提供 REST API 供外部系统调用
5. THE NovaLink SHALL 支持 Webhook 回调通知

### Requirement 26: 帮助命令系统

**User Story:** As a 玩家, I want to 查看可用命令列表, so that 我知道如何使用插件。

#### Acceptance Criteria

1. WHEN 玩家执行 `/nc help` THEN NovaChat SHALL 显示当前玩家可用的命令列表
2. THE NovaChat SHALL 根据玩家权限动态过滤显示的命令
3. THE NovaChat SHALL 为每个命令显示用法说明和权限要求
4. THE NovaChat SHALL 不显示隐藏命令（如 `/nc auth`）

### Requirement 27: 错误处理与提示

**User Story:** As a 玩家, I want to 看到清晰的错误提示, so that 我知道操作失败的原因。

#### Acceptance Criteria

1. WHEN 操作失败 THEN NovaChat SHALL 显示美观的错误提示和错误代码
2. THE NovaChat SHALL 使用统一的错误代码体系（NC-4xx 客户端错误，NC-5xx 服务端错误）
3. WHEN 显示错误 THEN NovaChat SHALL 提供解决方案建议
4. THE NovaLink SHALL 记录所有错误到日志文件
