# Requirements Document

## Introduction

本需求文档描述 NovaChat 平台扩展计划，包括：
1. **Java Edition Mod 支持**: 为 Fabric、NeoForge、Quilt、Forge 模组加载器开发 NovaChat 客户端
2. **Bedrock Edition 扩展**: 为 PocketMine-MP 和 Endstone 开发 NovaChat 插件
3. **Go 版本后端**: 开发与 Java 版本功能对等的 Go 语言后端（NovaLink-Go）
4. **测试覆盖增强**: 为现有模块编写更多单元测试、属性测试和端到端对接测试

这些扩展将使 NovaChat 覆盖几乎所有主流 Minecraft 服务端平台，并提供多语言后端选择，实现真正的全平台跨服聊天。

## Glossary

- **NovaChat**: 前端 Minecraft 插件/Mod，负责拦截聊天事件、渲染消息、与后端通信
- **NovaLink**: 独立 Java 后端服务，负责消息路由、权限校验、数据持久化
- **NovaProtocol**: 基于 TCP 的自定义二进制通信协议
- **Fabric**: 轻量级 Minecraft Java 版模组加载器
- **NeoForge**: Forge 的现代分支，支持 Minecraft 1.20.2+
- **Quilt**: 基于 Fabric 的模组加载器分支，提供额外功能
- **Forge**: 历史悠久的 Minecraft Java 版模组加载器
- **PocketMine-MP (PMMP)**: PHP 编写的 Minecraft 基岩版服务端
- **Endstone**: Python 编写的 Minecraft 基岩版服务端，兼容 BDS 插件
- **PowerNukkitX (PNX)**: Nukkit 的现代分支，支持 Minecraft 基岩版最新特性
- **Architectury**: 跨模组加载器开发框架，支持 Fabric/Forge/NeoForge/Quilt

### 平台支持矩阵

| 平台 | 语言 | Minecraft 版本 | 状态 |
|-----|------|---------------|------|
| Bukkit/Spigot/Paper | Java | Java 1.8+ | ✅ 已实现 |
| Velocity | Java | 代理端 | ✅ 已实现 |
| BungeeCord | Java | 代理端 | ✅ 已实现 |
| Nukkit | Java | Bedrock | ✅ 已实现 |
| LeviLamina (BDS) | C++ | Bedrock | ✅ 已实现 |
| **Fabric** | Java | Java 1.14+ | 🆕 待实现 |
| **PowerNukkitX** | Java | Bedrock | 🆕 待实现 |
| **NeoForge** | Java | Java 1.20.2+ | 🆕 待实现 |
| **Quilt** | Java | Java 1.14+ | 🆕 待实现 |
| **Forge** | Java | Java 1.7+ | 🆕 待实现 |
| **PocketMine-MP** | PHP | Bedrock | 🆕 待实现 |
| **Endstone** | Python | Bedrock | 🆕 待实现 |

## Requirements

---

## 第一部分：Java Edition Mod 支持

### Requirement 1: Architectury 跨平台 Mod 架构

**User Story:** As a 服务器管理员, I want to 在 Fabric/NeoForge/Quilt/Forge 服务端使用 NovaChat, so that 我可以在模组服务器上实现跨服聊天。

#### Acceptance Criteria

1. THE NovaChat-Mod 项目 SHALL 使用 Architectury 框架实现跨加载器兼容
2. THE 项目结构 SHALL 包含 common（公共代码）、fabric、neoforge、quilt、forge 子模块
3. WHEN 构建项目 THEN 构建系统 SHALL 生成四个独立的 JAR 文件（每个加载器一个）
4. THE common 模块 SHALL 包含所有与加载器无关的业务逻辑
5. THE 各平台模块 SHALL 仅包含平台特定的适配代码

### Requirement 2: Fabric Mod 实现

**User Story:** As a Fabric 服务器管理员, I want to 安装 NovaChat Fabric Mod, so that 我的模组服务器可以接入聊天网络。

#### Acceptance Criteria

1. THE NovaChat-Fabric SHALL 支持 Minecraft 1.20.x 及以上版本
2. WHEN Mod 加载 THEN NovaChat-Fabric SHALL 注册聊天事件监听器
3. WHEN 玩家发送聊天消息 THEN NovaChat-Fabric SHALL 拦截消息并转发到后端
4. THE NovaChat-Fabric SHALL 使用 Fabric API 的 ServerMessageEvents 处理聊天
5. WHEN 收到后端消息 THEN NovaChat-Fabric SHALL 使用 Text API 渲染富文本消息
6. THE NovaChat-Fabric SHALL 在 fabric.mod.json 中声明正确的依赖和元数据

### Requirement 3: NeoForge Mod 实现

**User Story:** As a NeoForge 服务器管理员, I want to 安装 NovaChat NeoForge Mod, so that 我的模组服务器可以接入聊天网络。

#### Acceptance Criteria

1. THE NovaChat-NeoForge SHALL 支持 Minecraft 1.20.2 及以上版本
2. WHEN Mod 加载 THEN NovaChat-NeoForge SHALL 使用 NeoForge 事件总线注册监听器
3. WHEN 玩家发送聊天消息 THEN NovaChat-NeoForge SHALL 通过 ServerChatEvent 拦截消息
4. THE NovaChat-NeoForge SHALL 使用 Component API 渲染富文本消息
5. THE NovaChat-NeoForge SHALL 在 mods.toml 中声明正确的依赖和元数据

### Requirement 4: Quilt Mod 实现

**User Story:** As a Quilt 服务器管理员, I want to 安装 NovaChat Quilt Mod, so that 我的模组服务器可以接入聊天网络。

#### Acceptance Criteria

1. THE NovaChat-Quilt SHALL 支持 Minecraft 1.20.x 及以上版本
2. THE NovaChat-Quilt SHALL 兼容 Fabric API（通过 Quilted Fabric API）
3. WHEN Mod 加载 THEN NovaChat-Quilt SHALL 使用 Quilt 的模组初始化入口点
4. THE NovaChat-Quilt SHALL 在 quilt.mod.json 中声明正确的依赖和元数据

### Requirement 5: Forge Mod 实现

**User Story:** As a Forge 服务器管理员, I want to 安装 NovaChat Forge Mod, so that 我的模组服务器可以接入聊天网络。

#### Acceptance Criteria

1. THE NovaChat-Forge SHALL 支持 Minecraft 1.20.x 版本
2. WHEN Mod 加载 THEN NovaChat-Forge SHALL 使用 MinecraftForge.EVENT_BUS 注册监听器
3. WHEN 玩家发送聊天消息 THEN NovaChat-Forge SHALL 通过 ServerChatEvent 拦截消息
4. THE NovaChat-Forge SHALL 使用 Forge 的 Component API 渲染富文本消息
5. THE NovaChat-Forge SHALL 在 mods.toml 中声明正确的依赖和元数据

### Requirement 6: Mod 配置系统

**User Story:** As a 服务器管理员, I want to 配置 NovaChat Mod 的连接参数, so that 我可以连接到正确的后端服务器。

#### Acceptance Criteria

1. THE NovaChat-Mod SHALL 从 config/novachat.yml 读取配置
2. THE 配置文件 SHALL 包含后端连接信息（host、port、username、password）
3. THE 配置文件 SHALL 包含聊天设置（replace_vanilla、default_channel）
4. THE 配置文件 SHALL 包含消息格式模板
5. WHEN 配置文件不存在 THEN NovaChat-Mod SHALL 生成默认配置文件
6. WHEN 执行 /nc reload 命令 THEN NovaChat-Mod SHALL 重新加载配置

### Requirement 7: Mod 命令系统

**User Story:** As a 玩家, I want to 使用与 Bukkit 版相同的命令, so that 我可以无缝切换服务器类型。

#### Acceptance Criteria

1. THE NovaChat-Mod SHALL 注册 /novachat 和 /nc 命令
2. THE 命令系统 SHALL 支持所有标准子命令（help、join、leave、create、invite、accept、toggle）
3. THE 管理员命令 SHALL 支持 mute、kick、announce、title、reload、debug
4. WHEN 玩家没有权限 THEN 命令系统 SHALL 隐藏对应的子命令
5. THE 命令系统 SHALL 提供 Tab 补全功能

---

## 第二部分：Bedrock Edition 扩展

### Requirement 8: PocketMine-MP 插件实现

**User Story:** As a PocketMine-MP 服务器管理员, I want to 安装 NovaChat 插件, so that 我的基岩版服务器可以接入聊天网络。

#### Acceptance Criteria

1. THE NovaChat-PMMP SHALL 使用 PHP 8.1+ 编写
2. THE NovaChat-PMMP SHALL 兼容 PocketMine-MP 5.x API
3. WHEN 插件启用 THEN NovaChat-PMMP SHALL 建立与后端的 TCP 连接
4. WHEN 玩家发送聊天消息 THEN NovaChat-PMMP SHALL 通过 PlayerChatEvent 拦截消息
5. THE NovaChat-PMMP SHALL 使用 libasyncsocket 或 pmmpthread 实现异步网络通信
6. THE NovaChat-PMMP SHALL 在 plugin.yml 中声明正确的 API 版本和依赖
7. WHEN 收到后端消息 THEN NovaChat-PMMP SHALL 使用 TextFormat 类渲染颜色代码

### Requirement 9: PocketMine-MP 协议实现

**User Story:** As a 开发者, I want to NovaChat-PMMP 正确实现 NovaProtocol, so that 它可以与后端正常通信。

#### Acceptance Criteria

1. THE NovaChat-PMMP SHALL 实现 VarInt 编解码器
2. THE NovaChat-PMMP SHALL 实现所有核心数据包类型（Handshake、ChatMessage、ChannelAction、KeepAlive）
3. THE NovaChat-PMMP SHALL 使用大端序进行网络传输
4. WHEN 连接断开 THEN NovaChat-PMMP SHALL 实现指数退避重连机制
5. THE NovaChat-PMMP SHALL 每 15 秒发送心跳包维持连接

### Requirement 10: Endstone 插件实现

**User Story:** As a Endstone 服务器管理员, I want to 安装 NovaChat 插件, so that 我的基岩版服务器可以接入聊天网络。

#### Acceptance Criteria

1. THE NovaChat-Endstone SHALL 使用 Python 3.10+ 编写
2. THE NovaChat-Endstone SHALL 兼容 Endstone 最新 API
3. WHEN 插件启用 THEN NovaChat-Endstone SHALL 建立与后端的 TCP 连接
4. WHEN 玩家发送聊天消息 THEN NovaChat-Endstone SHALL 通过事件系统拦截消息
5. THE NovaChat-Endstone SHALL 使用 asyncio 实现异步网络通信
6. THE NovaChat-Endstone SHALL 在 plugin.toml 中声明正确的元数据

### Requirement 11: Endstone 协议实现

**User Story:** As a 开发者, I want to NovaChat-Endstone 正确实现 NovaProtocol, so that 它可以与后端正常通信。

#### Acceptance Criteria

1. THE NovaChat-Endstone SHALL 实现 VarInt 编解码器
2. THE NovaChat-Endstone SHALL 实现所有核心数据包类型
3. THE NovaChat-Endstone SHALL 使用 struct 模块处理大端序字节
4. WHEN 连接断开 THEN NovaChat-Endstone SHALL 实现指数退避重连机制
5. THE NovaChat-Endstone SHALL 使用 asyncio.create_task 处理心跳

### Requirement 28: PowerNukkitX 插件实现

**User Story:** As a PowerNukkitX 服务器管理员, I want to 安装 NovaChat 插件, so that 我的基岩版服务器可以接入聊天网络并使用最新特性。

#### Acceptance Criteria

1. THE NovaChat-PNX SHALL 使用 Java 17+ 编写
2. THE NovaChat-PNX SHALL 兼容 PowerNukkitX 2.x API
3. WHEN 插件启用 THEN NovaChat-PNX SHALL 建立与后端的 TCP 连接
4. WHEN 玩家发送聊天消息 THEN NovaChat-PNX SHALL 通过 PlayerChatEvent 拦截消息
5. THE NovaChat-PNX SHALL 复用 novachat-common 模块的协议实现
6. THE NovaChat-PNX SHALL 在 plugin.yml 中声明正确的 API 版本和依赖
7. WHEN 收到后端消息 THEN NovaChat-PNX SHALL 使用 TextFormat 类渲染颜色代码
8. THE NovaChat-PNX SHALL 支持 PowerNukkitX 的表单 UI 系统进行频道管理

### Requirement 29: PowerNukkitX 命令与配置

**User Story:** As a PowerNukkitX 服务器管理员, I want to 使用与其他平台相同的命令和配置, so that 我可以无缝管理跨平台聊天网络。

#### Acceptance Criteria

1. THE NovaChat-PNX SHALL 注册 /novachat 和 /nc 命令
2. THE 命令系统 SHALL 支持所有标准子命令（help、join、leave、toggle、reload、debug）
3. THE NovaChat-PNX SHALL 从 config.yml 读取配置
4. THE 配置文件 SHALL 包含后端连接信息（host、port、username、password）
5. WHEN 配置文件不存在 THEN NovaChat-PNX SHALL 生成默认配置文件
6. THE NovaChat-PNX SHALL 支持世界监控和自动频道切换功能

---

## 第三部分：Go 版本后端 (NovaLink-Go)

### Requirement 12: NovaLink-Go 核心架构

**User Story:** As a 系统管理员, I want to 选择使用 Go 版本的后端, so that 我可以获得更低的资源占用和更简单的部署。

#### Acceptance Criteria

1. THE NovaLink-Go SHALL 使用 Go 1.21+ 编写
2. THE NovaLink-Go SHALL 实现与 Java 版本完全相同的 NovaProtocol
3. THE NovaLink-Go SHALL 支持相同的配置文件格式（novalink.yml）
4. THE NovaLink-Go SHALL 编译为单一可执行文件，无需额外依赖
5. THE NovaLink-Go SHALL 支持 Linux、Windows、macOS 三个平台

### Requirement 13: NovaLink-Go 网络层

**User Story:** As a 开发者, I want to NovaLink-Go 实现高性能网络层, so that 它可以处理大量并发连接。

#### Acceptance Criteria

1. THE NovaLink-Go SHALL 使用 goroutine 处理每个客户端连接
2. THE NovaLink-Go SHALL 实现 VarInt 编解码器（与 Java 版本兼容）
3. THE NovaLink-Go SHALL 实现所有核心数据包类型
4. THE NovaLink-Go SHALL 使用 channel 进行协程间通信
5. WHEN 客户端断开连接 THEN NovaLink-Go SHALL 正确清理资源

### Requirement 14: NovaLink-Go 频道系统

**User Story:** As a 系统管理员, I want to NovaLink-Go 支持完整的频道功能, so that 它可以替代 Java 版本。

#### Acceptance Criteria

1. THE NovaLink-Go SHALL 实现 GLOBAL、SERVER、PRIVATE 三种频道作用域
2. THE NovaLink-Go SHALL 实现消息路由引擎
3. THE NovaLink-Go SHALL 实现世界过滤器功能
4. THE NovaLink-Go SHALL 实现私有频道创建和管理
5. THE NovaLink-Go SHALL 实现频道模板继承

### Requirement 15: NovaLink-Go 认证与权限

**User Story:** As a 系统管理员, I want to NovaLink-Go 实现完整的认证系统, so that 只有授权客户端可以连接。

#### Acceptance Criteria

1. THE NovaLink-Go SHALL 实现 SHA-256 密码哈希验证
2. THE NovaLink-Go SHALL 实现四级权限体系
3. THE NovaLink-Go SHALL 实现 IP 封禁机制
4. THE NovaLink-Go SHALL 实现超级管理员认证
5. THE NovaLink-Go SHALL 使用 JWT 进行 Web 面板认证

### Requirement 16: NovaLink-Go 数据持久化

**User Story:** As a 系统管理员, I want to NovaLink-Go 支持多种数据库, so that 我可以选择适合的存储方案。

#### Acceptance Criteria

1. THE NovaLink-Go SHALL 支持 MySQL 数据库
2. THE NovaLink-Go SHALL 支持 Redis 缓存
3. THE NovaLink-Go SHALL 支持内存存储模式
4. THE NovaLink-Go SHALL 实现玩家状态持久化
5. THE NovaLink-Go SHALL 使用连接池管理数据库连接

### Requirement 17: NovaLink-Go 管理功能

**User Story:** As a 系统管理员, I want to NovaLink-Go 支持完整的管理功能, so that 我可以管理聊天网络。

#### Acceptance Criteria

1. THE NovaLink-Go SHALL 实现禁言系统
2. THE NovaLink-Go SHALL 实现公告系统
3. THE NovaLink-Go SHALL 实现 Title 发送功能
4. THE NovaLink-Go SHALL 实现踢出成员功能
5. THE NovaLink-Go SHALL 实现敏感词过滤
6. THE NovaLink-Go SHALL 实现邀请码系统

### Requirement 18: NovaLink-Go WebSocket 网关

**User Story:** As a 系统管理员, I want to NovaLink-Go 支持 Web 管理面板, so that 我可以通过浏览器管理系统。

#### Acceptance Criteria

1. THE NovaLink-Go SHALL 实现 WebSocket 服务器
2. THE NovaLink-Go SHALL 支持与现有 Vue.js 面板对接
3. THE NovaLink-Go SHALL 实现实时消息推送
4. THE NovaLink-Go SHALL 实现 REST API 端点
5. THE NovaLink-Go SHALL 支持 Webhook 回调

### Requirement 19: NovaLink-Go 与 Java 版本互操作

**User Story:** As a 系统管理员, I want to Go 和 Java 后端可以互换使用, so that 我可以根据需求选择。

#### Acceptance Criteria

1. THE NovaLink-Go SHALL 使用与 Java 版本相同的配置文件格式
2. THE NovaLink-Go SHALL 使用与 Java 版本相同的数据库 schema
3. THE NovaLink-Go SHALL 与所有 NovaChat 客户端兼容
4. WHEN 从 Java 版本迁移到 Go 版本 THEN 数据 SHALL 无缝迁移
5. THE NovaLink-Go SHALL 通过相同的协议测试套件

---

## 第四部分：测试覆盖增强

### Requirement 20: NovaLink 核心模块单元测试

**User Story:** As a 开发者, I want to 为 NovaLink 核心模块编写更多单元测试, so that 代码质量得到保证。

#### Acceptance Criteria

1. THE ChannelManager SHALL 拥有至少 80% 的代码覆盖率
2. THE AuthManager SHALL 拥有至少 80% 的代码覆盖率
3. THE MuteManager SHALL 拥有至少 80% 的代码覆盖率
4. THE AnnouncementManager SHALL 拥有至少 80% 的代码覆盖率
5. THE 单元测试 SHALL 覆盖所有公共方法的正常路径和异常路径

### Requirement 21: NovaChat-Common 协议测试

**User Story:** As a 开发者, I want to 为协议层编写更多测试, so that 跨平台通信的正确性得到验证。

#### Acceptance Criteria

1. THE PacketBuffer SHALL 拥有完整的读写测试（所有数据类型）
2. THE 每种 Packet 类型 SHALL 拥有序列化/反序列化测试
3. THE VarInt SHALL 拥有边界值测试（0、127、128、16383、16384、最大值）
4. THE 协议测试 SHALL 验证字节序正确性

### Requirement 22: 属性测试扩展

**User Story:** As a 开发者, I want to 为更多模块编写属性测试, so that 系统的正确性属性得到形式化验证。

#### Acceptance Criteria

1. THE CronSchedule SHALL 拥有属性测试验证调度正确性
2. THE WebhookManager SHALL 拥有属性测试验证事件分发
3. THE JwtService SHALL 拥有属性测试验证令牌生成和验证的一致性
4. THE 属性测试 SHALL 使用 jqwik 框架，每个属性至少运行 100 次

### Requirement 23: 插件与后端对接测试

**User Story:** As a 开发者, I want to 编写完整的插件与后端对接测试, so that 端到端通信的正确性得到验证。

#### Acceptance Criteria

1. THE 对接测试 SHALL 验证 NovaChat-Bukkit 与 NovaLink 的完整通信流程
2. THE 对接测试 SHALL 验证 NovaChat-Velocity 与 NovaLink 的完整通信流程
3. THE 对接测试 SHALL 验证 NovaChat-BungeeCord 与 NovaLink 的完整通信流程
4. THE 对接测试 SHALL 验证 NovaChat-Nukkit 与 NovaLink 的完整通信流程
5. THE 对接测试 SHALL 验证 NovaChat-PNX 与 NovaLink 的完整通信流程
6. THE 对接测试 SHALL 验证握手认证流程
7. THE 对接测试 SHALL 验证消息发送和接收流程
8. THE 对接测试 SHALL 验证频道加入和离开流程
9. THE 对接测试 SHALL 验证管理命令执行流程

### Requirement 24: 集成测试框架

**User Story:** As a 开发者, I want to 建立集成测试框架, so that 端到端功能可以被自动化测试。

#### Acceptance Criteria

1. THE 集成测试 SHALL 能够启动嵌入式 NovaLink 服务器
2. THE 集成测试 SHALL 能够模拟多个客户端连接
3. THE 集成测试 SHALL 验证消息路由的端到端正确性
4. THE 集成测试 SHALL 验证认证流程的完整性
5. THE 集成测试 SHALL 使用 Testcontainers 管理 MySQL/Redis 依赖
6. THE 集成测试 SHALL 验证 Go 版本和 Java 版本后端的行为一致性

### Requirement 25: 现有功能完整性检查

**User Story:** As a 开发者, I want to 检查并完成所有未完成的功能, so that 系统功能完整可用。

#### Acceptance Criteria

1. THE 检查 SHALL 验证所有 tasks.md 中标记为完成的任务确实已实现
2. THE 检查 SHALL 识别任何缺失的功能实现
3. THE 检查 SHALL 验证所有命令都已正确实现
4. THE 检查 SHALL 验证所有事件处理器都已正确注册
5. THE 检查 SHALL 验证配置文件解析的完整性

---

## 第五部分：构建与发布

### Requirement 26: 统一构建系统

**User Story:** As a 开发者, I want to 使用统一的构建系统管理所有模块, so that 构建和发布流程简化。

#### Acceptance Criteria

1. THE 所有 Java 项目 SHALL 使用 Gradle 构建（novalink-core、novachat-common、novachat-bukkit、novachat-velocity、novachat-bungee、novachat-nukkit、novachat-mod、novachat-pnx）
2. THE Gradle 项目 SHALL 使用统一的依赖管理和版本控制
3. THE NovaLink-Go SHALL 使用 Go Modules 管理依赖
4. THE PocketMine-MP 插件 SHALL 使用 Composer 管理依赖
5. THE Endstone 插件 SHALL 使用 Poetry 管理依赖
6. THE 构建脚本 SHALL 支持一键构建所有平台的发布包（支持 Gradle、Go、Composer、Poetry）

### Requirement 27: 版本兼容性

**User Story:** As a 服务器管理员, I want to 了解各平台的版本兼容性, so that 我可以选择正确的版本。

#### Acceptance Criteria

1. THE README SHALL 包含完整的平台兼容性矩阵
2. THE 每个平台模块 SHALL 在其配置文件中声明支持的 Minecraft 版本
3. WHEN 版本不兼容 THEN 插件/Mod SHALL 在启动时输出清晰的错误信息
4. THE NovaProtocol 版本 SHALL 在握手时验证，不兼容时拒绝连接
5. THE Go 版本和 Java 版本后端 SHALL 使用相同的协议版本号

