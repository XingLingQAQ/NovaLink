# Requirements Document

## Introduction

本需求文档描述 NovaChat 平台扩展计划的第二阶段，包括：
1. **新平台支持**: MultiPaper、Folia、Sponge 服务端插件
2. **Mod 多版本支持**: 完善 Fabric/NeoForge/Quilt/Forge 的多 Minecraft 版本兼容
3. **自定义插件系统**: 为 NovaChat 开发可扩展的插件加载机制
4. **高级聊天功能**: @提及、物品展示、背包展示、末影箱展示、图片展示
5. **权限系统完善**: 为所有功能、频道和聊天格式设置细粒度权限节点

## Glossary

- **NovaChat**: 前端 Minecraft 插件/Mod，负责拦截聊天事件、渲染消息、与后端通信
- **NovaLink**: 独立后端服务，负责消息路由、权限校验、数据持久化
- **NovaProtocol**: 基于 TCP 的自定义二进制通信协议
- **MultiPaper**: 支持多服务器实例共享世界的 Paper 分支
- **Folia**: Paper 的区域化多线程分支，使用区域调度器
- **Sponge**: 基于 Mixin 的 Minecraft 服务端 API
- **NovaChat Extension**: NovaChat 的自定义扩展插件，可添加新功能
- **Item Display**: 在聊天中展示物品信息的功能
- **Mention**: @提及功能，可以在聊天中提及其他玩家

### 平台支持矩阵（扩展）

| 平台 | 语言 | Minecraft 版本 | 状态 |
|-----|------|---------------|------|
| Bukkit/Spigot/Paper | Java | Java 1.8+ | ✅ 已实现 |
| Velocity | Java | 代理端 | ✅ 已实现 |
| BungeeCord | Java | 代理端 | ✅ 已实现 |
| Nukkit | Java | Bedrock | ✅ 已实现 |
| PowerNukkitX | Java | Bedrock | ✅ 已实现 |
| LeviLamina (BDS) | C++ | Bedrock | ✅ 已实现 |
| PocketMine-MP | PHP | Bedrock | ✅ 已实现 |
| Endstone | Python | Bedrock | ✅ 已实现 |
| Fabric | Java | Java 1.14+ | ✅ 已实现 |
| NeoForge | Java | Java 1.20.2+ | ✅ 已实现 |
| Quilt | Java | Java 1.14+ | ✅ 已实现 |
| Forge | Java | Java 1.7+ | ✅ 已实现 |
| **MultiPaper** | Java | Java 1.19+ | 🆕 待实现 |
| **Folia** | Java | Java 1.19+ | 🆕 待实现 |
| **Sponge** | Java | Java 1.16+ | 🆕 待实现 |

## Requirements

---

## 第一部分：新平台支持

### Requirement 1: MultiPaper 插件支持

**User Story:** As a MultiPaper 服务器管理员, I want to 在 MultiPaper 服务端使用 NovaChat, so that 我可以在多实例共享世界的环境中实现跨服聊天。

#### Acceptance Criteria

1. THE NovaChat-MultiPaper SHALL 兼容 MultiPaper 2.x API
2. WHEN 插件加载 THEN NovaChat-MultiPaper SHALL 检测 MultiPaper 环境并启用跨实例同步
3. WHEN 玩家在不同实例间移动 THEN NovaChat-MultiPaper SHALL 保持聊天状态一致
4. THE NovaChat-MultiPaper SHALL 使用 Paper API 的 AsyncPlayerChatEvent 处理聊天
5. THE NovaChat-MultiPaper SHALL 复用 novachat-common 模块的协议实现

### Requirement 2: Folia 插件支持

**User Story:** As a Folia 服务器管理员, I want to 在 Folia 服务端使用 NovaChat, so that 我可以在区域化多线程环境中实现跨服聊天。

#### Acceptance Criteria

1. THE NovaChat-Folia SHALL 兼容 Folia 的区域调度器 API
2. WHEN 执行网络操作 THEN NovaChat-Folia SHALL 使用 Folia 的异步任务调度器
3. WHEN 访问玩家数据 THEN NovaChat-Folia SHALL 在正确的区域线程上执行
4. THE NovaChat-Folia SHALL 使用 AsyncPlayerChatEvent 处理聊天（线程安全）
5. THE NovaChat-Folia SHALL 复用 novachat-common 模块的协议实现

### Requirement 3: Sponge 插件支持

**User Story:** As a Sponge 服务器管理员, I want to 在 Sponge 服务端使用 NovaChat, so that 我可以在 Sponge 生态中实现跨服聊天。

#### Acceptance Criteria

1. THE NovaChat-Sponge SHALL 兼容 Sponge API 8.x 及以上版本
2. WHEN 插件加载 THEN NovaChat-Sponge SHALL 使用 Sponge 事件系统注册监听器
3. WHEN 玩家发送聊天消息 THEN NovaChat-Sponge SHALL 通过 PlayerChatEvent 拦截消息
4. THE NovaChat-Sponge SHALL 使用 Sponge Text API 渲染富文本消息
5. THE NovaChat-Sponge SHALL 复用 novachat-common 模块的协议实现

---

## 第二部分：Mod 多版本支持

### Requirement 4: Fabric Mod 多版本支持

**User Story:** As a Fabric 服务器管理员, I want to 在不同 Minecraft 版本上使用 NovaChat Fabric Mod, so that 我可以在旧版本服务器上也能使用跨服聊天。

#### Acceptance Criteria

1. THE NovaChat-Fabric SHALL 支持 Minecraft 1.14.x 至 1.21.x 版本
2. THE 构建系统 SHALL 为每个主要版本生成独立的 JAR 文件
3. WHEN API 在不同版本间有差异 THEN NovaChat-Fabric SHALL 使用版本适配器处理
4. THE 版本检测器 SHALL 在运行时检测 Minecraft 版本并加载正确的适配器
5. WHEN 版本不兼容 THEN NovaChat-Fabric SHALL 在启动时输出清晰的错误信息

### Requirement 5: NeoForge Mod 多版本支持

**User Story:** As a NeoForge 服务器管理员, I want to 在不同 Minecraft 版本上使用 NovaChat NeoForge Mod, so that 我可以在各版本服务器上使用跨服聊天。

#### Acceptance Criteria

1. THE NovaChat-NeoForge SHALL 支持 Minecraft 1.20.2 至 1.21.x 版本
2. THE 构建系统 SHALL 为每个主要版本生成独立的 JAR 文件
3. WHEN NeoForge API 在不同版本间有差异 THEN NovaChat-NeoForge SHALL 使用版本适配器处理
4. THE 版本检测器 SHALL 在运行时检测 Minecraft 版本并加载正确的适配器
5. WHEN 版本不兼容 THEN NovaChat-NeoForge SHALL 在启动时输出清晰的错误信息

### Requirement 6: Quilt Mod 多版本支持

**User Story:** As a Quilt 服务器管理员, I want to 在不同 Minecraft 版本上使用 NovaChat Quilt Mod, so that 我可以在各版本服务器上使用跨服聊天。

#### Acceptance Criteria

1. THE NovaChat-Quilt SHALL 支持 Minecraft 1.14.x 至 1.21.x 版本
2. THE 构建系统 SHALL 为每个主要版本生成独立的 JAR 文件
3. WHEN Quilted Fabric API 在不同版本间有差异 THEN NovaChat-Quilt SHALL 使用版本适配器处理
4. THE 版本检测器 SHALL 在运行时检测 Minecraft 版本并加载正确的适配器
5. WHEN 版本不兼容 THEN NovaChat-Quilt SHALL 在启动时输出清晰的错误信息

### Requirement 7: Forge Mod 多版本支持

**User Story:** As a Forge 服务器管理员, I want to 在不同 Minecraft 版本上使用 NovaChat Forge Mod, so that 我可以在旧版本服务器上也能使用跨服聊天。

#### Acceptance Criteria

1. THE NovaChat-Forge SHALL 支持 Minecraft 1.7.10 至 1.21.x 版本
2. THE 构建系统 SHALL 为每个主要版本生成独立的 JAR 文件
3. WHEN Forge API 在不同版本间有差异 THEN NovaChat-Forge SHALL 使用版本适配器处理
4. THE 版本检测器 SHALL 在运行时检测 Minecraft 版本并加载正确的适配器
5. WHEN 版本不兼容 THEN NovaChat-Forge SHALL 在启动时输出清晰的错误信息

---

## 第三部分：自定义插件系统

### Requirement 8: NovaChat 扩展插件架构

**User Story:** As a 开发者, I want to 为 NovaChat 开发自定义扩展插件, so that 我可以添加自定义功能而无需修改核心代码。

#### Acceptance Criteria

1. THE NovaChat 核心 SHALL 提供 Extension API 供第三方开发者使用
2. THE Extension API SHALL 包含事件监听、命令注册、消息处理等接口
3. WHEN NovaChat 启动 THEN 扩展加载器 SHALL 扫描 extensions 目录并加载所有有效扩展
4. THE 扩展 SHALL 使用 YAML 或 JSON 格式的描述文件声明元数据
5. WHEN 扩展加载失败 THEN NovaChat SHALL 记录错误并继续加载其他扩展

### Requirement 9: Java 平台扩展支持

**User Story:** As a Java 开发者, I want to 使用 Java 开发 NovaChat 扩展, so that 我可以在 Bukkit/Velocity/BungeeCord/Mod 平台上添加自定义功能。

#### Acceptance Criteria

1. THE Java 扩展 SHALL 打包为 JAR 文件并放置在 extensions 目录
2. THE Java 扩展 SHALL 实现 NovaChatExtension 接口
3. WHEN 扩展加载 THEN 扩展加载器 SHALL 调用 onEnable() 方法
4. WHEN 扩展卸载 THEN 扩展加载器 SHALL 调用 onDisable() 方法
5. THE Java 扩展 SHALL 能够访问 NovaChat 的核心 API

### Requirement 10: 基岩版平台扩展支持

**User Story:** As a 基岩版服务器开发者, I want to 为基岩版 NovaChat 开发扩展, so that 我可以在 PMMP/Endstone/Nukkit 平台上添加自定义功能。

#### Acceptance Criteria

1. THE PMMP 扩展 SHALL 使用 PHP 编写并放置在 extensions 目录
2. THE Endstone 扩展 SHALL 使用 Python 编写并放置在 extensions 目录
3. THE Nukkit/PNX 扩展 SHALL 使用 Java 编写并放置在 extensions 目录
4. WHEN 扩展加载 THEN 各平台扩展加载器 SHALL 调用对应的初始化方法
5. THE 扩展 API SHALL 在各平台间保持一致的接口设计

---

## 第四部分：高级聊天功能

### Requirement 11: @提及功能

**User Story:** As a 玩家, I want to 在聊天中 @提及其他玩家, so that 被提及的玩家可以收到通知。

#### Acceptance Criteria

1. WHEN 玩家在消息中输入 @玩家名 THEN 系统 SHALL 识别并高亮显示提及
2. WHEN 玩家被提及 THEN 系统 SHALL 向被提及玩家发送声音/标题通知
3. THE 提及功能 SHALL 支持 Tab 补全玩家名称
4. THE 提及功能 SHALL 支持 @all 提及频道内所有玩家（需要权限）
5. WHEN 玩家没有提及权限 THEN 系统 SHALL 将 @ 符号作为普通文本处理

### Requirement 12: 物品展示功能

**User Story:** As a 玩家, I want to 在聊天中展示我手中的物品, so that 其他玩家可以查看物品详情。

#### Acceptance Criteria

1. WHEN 玩家在消息中输入 [item] 或 [i] THEN 系统 SHALL 将其替换为手持物品的可悬停展示
2. THE 物品展示 SHALL 包含物品名称、附魔、Lore 等完整信息
3. WHEN 其他玩家悬停在物品展示上 THEN 系统 SHALL 显示物品的完整 Tooltip
4. THE 物品展示 SHALL 支持 Java 版的 HoverEvent 和基岩版的替代方案
5. WHEN 玩家没有物品展示权限 THEN 系统 SHALL 将 [item] 作为普通文本处理

### Requirement 13: 背包展示功能

**User Story:** As a 玩家, I want to 在聊天中展示我的背包, so that 其他玩家可以查看我的物品。

#### Acceptance Criteria

1. WHEN 玩家在消息中输入 [inv] 或 [inventory] THEN 系统 SHALL 生成背包预览链接
2. WHEN 其他玩家点击背包链接 THEN 系统 SHALL 打开一个只读的背包预览界面
3. THE 背包展示 SHALL 显示玩家背包的快照（发送时的状态）
4. THE 背包预览 SHALL 支持查看物品详情但不能取出物品
5. WHEN 玩家没有背包展示权限 THEN 系统 SHALL 将 [inv] 作为普通文本处理

### Requirement 14: 末影箱展示功能

**User Story:** As a 玩家, I want to 在聊天中展示我的末影箱, so that 其他玩家可以查看我的末影箱内容。

#### Acceptance Criteria

1. WHEN 玩家在消息中输入 [ec] 或 [enderchest] THEN 系统 SHALL 生成末影箱预览链接
2. WHEN 其他玩家点击末影箱链接 THEN 系统 SHALL 打开一个只读的末影箱预览界面
3. THE 末影箱展示 SHALL 显示玩家末影箱的快照（发送时的状态）
4. THE 末影箱预览 SHALL 支持查看物品详情但不能取出物品
5. WHEN 玩家没有末影箱展示权限 THEN 系统 SHALL 将 [ec] 作为普通文本处理

### Requirement 15: 图片展示功能

**User Story:** As a 玩家, I want to 在聊天中分享图片, so that 其他玩家可以查看我分享的图片。

#### Acceptance Criteria

1. WHEN 玩家在消息中输入图片 URL THEN 系统 SHALL 将其转换为可点击的图片链接
2. THE 图片展示 SHALL 支持常见图片格式（PNG、JPG、GIF、WebP）
3. WHEN 其他玩家点击图片链接 THEN 系统 SHALL 在游戏内显示图片预览（Java 版使用地图/告示牌，基岩版使用表单）
4. THE 图片功能 SHALL 支持图床白名单以防止恶意链接
5. WHEN 玩家没有图片分享权限 THEN 系统 SHALL 将图片 URL 作为普通文本处理

---

## 第五部分：权限系统完善

### Requirement 16: 功能权限节点

**User Story:** As a 服务器管理员, I want to 为每个功能设置独立的权限节点, so that 我可以精细控制玩家可以使用的功能。

#### Acceptance Criteria

1. THE 权限系统 SHALL 为每个命令提供独立的权限节点（novachat.command.<命令名>）
2. THE 权限系统 SHALL 为每个高级功能提供独立的权限节点（novachat.feature.<功能名>）
3. WHEN 玩家没有对应权限 THEN 系统 SHALL 拒绝执行并显示权限不足提示
4. THE 权限系统 SHALL 支持通配符权限（novachat.* 授予所有权限）
5. THE 权限系统 SHALL 与各平台的权限插件兼容（LuckPerms、PermissionsEx 等）

### Requirement 17: 频道权限节点

**User Story:** As a 服务器管理员, I want to 为每个频道设置独立的权限节点, so that 我可以控制玩家可以加入的频道。

#### Acceptance Criteria

1. THE 权限系统 SHALL 为每个频道提供加入权限（novachat.channel.<频道ID>.join）
2. THE 权限系统 SHALL 为每个频道提供发言权限（novachat.channel.<频道ID>.speak）
3. THE 权限系统 SHALL 为每个频道提供管理权限（novachat.channel.<频道ID>.manage）
4. WHEN 玩家没有加入权限 THEN 系统 SHALL 拒绝加入并显示权限不足提示
5. WHEN 玩家没有发言权限 THEN 系统 SHALL 拒绝发送消息并显示权限不足提示

### Requirement 18: 聊天格式权限节点

**User Story:** As a 服务器管理员, I want to 为不同聊天格式设置权限, so that 我可以为不同等级的玩家提供不同的聊天样式。

#### Acceptance Criteria

1. THE 权限系统 SHALL 支持多组聊天格式配置
2. THE 权限系统 SHALL 为每组格式提供独立的权限节点（novachat.format.<格式组名>）
3. WHEN 玩家拥有多个格式权限 THEN 系统 SHALL 使用优先级最高的格式
4. THE 格式配置 SHALL 支持颜色代码、PlaceholderAPI 变量和自定义前缀/后缀
5. WHEN 玩家没有任何格式权限 THEN 系统 SHALL 使用默认格式

---

## 第六部分：协议扩展

### Requirement 19: 展示数据包协议

**User Story:** As a 开发者, I want to NovaProtocol 支持展示功能的数据包, so that 物品/背包/末影箱/图片展示可以跨服传输。

#### Acceptance Criteria

1. THE NovaProtocol SHALL 定义 ItemDisplayPacket 用于传输物品展示数据
2. THE NovaProtocol SHALL 定义 InventorySnapshotPacket 用于传输背包快照
3. THE NovaProtocol SHALL 定义 ImageDisplayPacket 用于传输图片展示数据
4. THE 数据包 SHALL 使用高效的序列化格式（NBT 压缩或 JSON）
5. THE 数据包 SHALL 包含发送者信息和时间戳以防止重放攻击

### Requirement 20: 提及数据包协议

**User Story:** As a 开发者, I want to NovaProtocol 支持提及功能的数据包, so that @提及可以跨服通知。

#### Acceptance Criteria

1. THE NovaProtocol SHALL 定义 MentionPacket 用于传输提及通知
2. THE MentionPacket SHALL 包含提及者、被提及者、频道和消息内容
3. WHEN 收到 MentionPacket THEN 客户端 SHALL 向被提及玩家发送通知
4. THE 提及通知 SHALL 支持自定义声音和标题显示
5. THE 提及功能 SHALL 支持跨服提及（通过后端路由）

---

## 第七部分：跨平台一致性

### Requirement 21: 跨平台协议一致性

**User Story:** As a 开发者, I want to 所有平台使用一致的协议实现, so that 不同平台的客户端可以无缝通信。

#### Acceptance Criteria

1. THE 所有平台 SHALL 使用相同版本的 NovaProtocol
2. THE 所有平台 SHALL 使用相同的字节序（大端序）
3. THE 所有平台 SHALL 使用相同的 VarInt 编码
4. WHEN 协议版本不匹配 THEN 后端 SHALL 拒绝连接并返回版本错误
5. THE 协议测试 SHALL 验证所有平台的序列化/反序列化一致性

### Requirement 22: 文档与兼容性

**User Story:** As a 服务器管理员, I want to 了解各平台的兼容性和功能支持情况, so that 我可以选择合适的平台。

#### Acceptance Criteria

1. THE README SHALL 包含完整的平台兼容性矩阵
2. THE README SHALL 包含各平台的功能支持情况（哪些功能在哪些平台可用）
3. THE 文档 SHALL 包含各平台的安装指南
4. WHEN 功能在某平台不可用 THEN 文档 SHALL 明确说明原因和替代方案
5. THE 文档 SHALL 包含扩展开发指南

