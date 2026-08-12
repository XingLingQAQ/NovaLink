# NovaChat & NovaLink

<div align="center">

![NovaChat Logo](https://img.shields.io/badge/NovaChat-v1.0.0-blue?style=for-the-badge)
![NovaLink Logo](https://img.shields.io/badge/NovaLink-v1.0.0-green?style=for-the-badge)
![Java](https://img.shields.io/badge/Java-17+-orange?style=for-the-badge)
![License](https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge)

**A distributed cross-platform Minecraft chat infrastructure system**

**分布式跨平台 Minecraft 聊天基础设施系统**

[English](#english) | [中文](#中文)

</div>

---

# English

## Overview

NovaChat & NovaLink is a distributed cross-platform Minecraft chat infrastructure system using a star topology architecture. NovaChat serves as the frontend plugin deployed on various Minecraft servers (Bukkit/Spigot, Velocity/BungeeCord, Nukkit, LeviLamina), while NovaLink operates as an independent Java backend responsible for message routing, permission management, and data persistence.

### Key Features

- 🌐 **Cross-Platform Support**: Bukkit, Velocity, BungeeCord, Nukkit, LeviLamina, Fabric, NeoForge, Quilt, Forge, PocketMine-MP, Endstone, PowerNukkitX
- 🔗 **Unified Protocol**: Custom NovaProtocol v1 for efficient communication
- 📢 **Flexible Channels**: Global, Server, World, and Private channels
- 🔐 **Permission System**: Four-tier hierarchy (SuperAdmin > ClientAdmin > ChannelAdmin > Player)
- 💾 **Data Persistence**: MySQL, Redis, or in-memory storage
- 🌍 **World Filtering**: Auto-routing based on player world
- 🎨 **Rich Formatting**: PlaceholderAPI and color code support
- 🖥️ **Web Panel**: Real-time monitoring and management

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      NovaLink Backend                        │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────────────┐   │
│  │ Channel │ │  Auth   │ │  Mute   │ │    Database     │   │
│  │ Manager │ │ Manager │ │ Manager │ │ (MySQL/Redis)   │   │
│  └─────────┘ └─────────┘ └─────────┘ └─────────────────┘   │
└─────────────────────────┬───────────────────────────────────┘
                          │ NovaProtocol (TCP)
        ┌─────────────────┼─────────────────┐
        │                 │                 │
┌───────▼───────┐ ┌───────▼───────┐ ┌───────▼───────┐
│ NovaChat      │ │ NovaChat      │ │ NovaChat      │
│ Bukkit/Spigot │ │ Velocity      │ │ Nukkit        │
└───────────────┘ └───────────────┘ └───────────────┘
```

### Core modules (three layers)

| Module | Layer | Purpose |
|--------|-------|---------|
| **`novachat-common`** | Shared protocol | NovaProtocol packets, codecs, mentions, extensions — used by **backend and all clients** |
| **`novachat-client-core`** | Plugin runtime | Connection lifecycle helpers, reconnect policy, client state — **plugins/mods only; not used by `novalink-core`** |
| **`novalink-core`** | Production backend | Canonical Java NovaLink server (routing, auth, persistence, REST/WS) |

Dependency direction: `novalink-core` → `novachat-common` only; platform plugins → `novachat-client-core` → `novachat-common`. See [`novachat-client-core/DESIGN.md`](NovaChat/client-core/DESIGN.md).

## Platform Compatibility Matrix

### Backend Servers

| Backend | Language | Protocol Version | Status |
|---------|----------|------------------|--------|
| NovaLink-Java | Java 17+ | v1 | ✅ **Production (canonical)** |

### Java Edition Clients

| Platform | Language | Minecraft Version | Protocol | Status |
|----------|----------|-------------------|----------|--------|
| Bukkit/Spigot/Paper | Java 17+ | 1.8 – 1.21.11 / 26.2 | v1 | ✅ Stable |
| Folia | Java 17+ | 1.19 – 1.21.11 / 26.2 (stable 26.1.2; 26.2 experimental) | v1 | ✅ Stable |
| Velocity | Java 25+ | Proxy (API 4.1.0+) | v1 | ✅ Stable |
| BungeeCord | Java 8+ | Proxy (API 1.21-R0.4; Waterfall EOL) | v1 | ✅ Stable |
| Fabric | Java 21+ | 1.21.x / 26.x (default 1.21.11; loader 0.19.3) | v1 | ⛔ Not built (needs Gradle 9.5+) |
| NeoForge | Java 25+ | 1.20.2 – 26.1 (NeoForge 26.1.0.x; MC 26.2 pending) | v1 | ⛔ Not built (needs Gradle 9.5+) |
| Quilt | Java 21+ | 1.21.x / 26.x (default 1.21.11; loader 0.30.0) | v1 | ⛔ Not built (needs Gradle 9.5+) |
| Forge | Java 8+/17+/21+ | 1.12.2 – 26.2 (Forge 65.1.0) | v1 | ⛔ Not built (needs Gradle 9.5+) |
| Sponge | Java 17+ | 1.16.5 (SpongeAPI 8.2.0) — upstream has SpongeAPI 17.x (MC 1.21.10) / 20.x RC (MC 26.2); project not yet upgraded | v1 | ✅ Stable |

### Bedrock Edition Clients

| Platform | Language | Minecraft Version | Protocol | Status |
|----------|----------|-------------------|----------|--------|
| Nukkit | Java 8+ | Bedrock 1.20+ – 26.40 (Cloudburst Nukkit snapshot) | v1 | ✅ Stable |
| PowerNukkitX | Java 17+ | Bedrock 1.20+ – 26.40 (PNX 3.0.2, protocol 2168) | v1 | ✅ Stable |
| LeviLamina (BDS) | C++ | Bedrock 1.20+ – 26.40 (LeviLamina 26.20.x) | v1 | ✅ Stable |
| PocketMine-MP | PHP 8.1+ | Bedrock 1.20+ – 26.30 (protocol ≤1001; PMMP 5.44.3 archived Jul 2026) | v1 | ✅ Stable |
| Endstone | Python 3.10+ | Bedrock 1.20+ – 26.40 (Endstone 0.11.8, BDS 1.26.40) | v1 | ✅ Stable |

### Protocol Version Compatibility

All clients and backends must use the same protocol version to communicate. The current protocol version is **v1**.

| Protocol Version | Supported Backends | Supported Clients |
|------------------|-------------------|-------------------|
| v1 | NovaLink-Java | All platforms listed above |

## Installation

### Requirements

- Java 17+ (for **production** NovaLink backend and modern plugins)
- Java 21+ (for Fabric/Quilt/Forge/NeoForge targeting 1.20.5+; also the floor for Paper/Folia server builds on MC 1.21.x)
- Java 25+ (for the **Velocity** proxy module — `novachat-velocity` pins `VERSION_25` and Lombok is disabled under JDK 25; also the floor for Minecraft 26.1+ server platforms)
- Java 8+ (for legacy Minecraft plugins / BungeeCord)
- PHP 8.1+ (for PocketMine-MP plugin)
- Python 3.10+ (for Endstone plugin)
- MySQL 5.7+ (optional)
- Redis 6+ (optional)

> Platform versions above reflect the latest releases as of 2026-08-08 (Minecraft Java 26.2, Bedrock 26.42). Mod-loader platforms (Fabric/NeoForge/Quilt/Forge) are **not currently built** — their `novachat-mod:<loader>` subprojects are commented out in `settings.gradle` pending a Gradle wrapper upgrade to 9.5+. See the per-row status in the matrices.

### NovaLink Backend Setup

1. Download `novalink-core.jar` from releases
2. Create a directory and place the JAR file
3. Run once to generate configuration:
   ```bash
   java -jar novalink-core.jar
   ```
4. Edit `novalink.yml` (see [Configuration](#configuration))
5. Start the backend:
   ```bash
   java -jar novalink-core.jar
   ```

### NovaChat Plugin Setup

1. Download the appropriate plugin for your server:

   **Java Edition:**
   - `novachat-bukkit.jar` - Bukkit/Spigot/Paper
   - `novachat-velocity.jar` - Velocity proxy
   - `novachat-bungee.jar` - BungeeCord proxy
   - `novachat-mod-fabric.jar` - Fabric 1.20.x+
   - `novachat-mod-neoforge.jar` - NeoForge 1.20.2+
   - `novachat-mod-quilt.jar` - Quilt 1.20.x+
   - `novachat-mod-forge.jar` - Forge 1.20.x

   **Bedrock Edition:**
   - `novachat-nukkit.jar` - Nukkit
   - `novachat-pnx.jar` - PowerNukkitX
   - `novachat-levilamina.dll` - LeviLamina (BDS)
   - `novachat-pmmp.phar` - PocketMine-MP
   - `novachat-endstone/` - Endstone (Python package)

2. Place in your server's plugins/mods folder
3. Start the server to generate configuration
4. Edit the configuration file (location varies by platform)
5. Restart the server

## Configuration

### NovaLink Backend (novalink.yml)

See [examples/novalink.yml](examples/novalink.yml) for a complete example.

```yaml
# Server settings
server:
  bind-address: 0.0.0.0
  port: 8888
  websocket-port: 8889

# Database settings
database:
  type: mysql  # mysql, redis, memory
  mysql:
    host: 127.0.0.1
    port: 3306
    database: novalink
    username: root
    password: password

# Global channels (cross-server)
global_channels:
  global:
    display_name: "Global"
    permission: "novachat.channel.global"

# Client configurations
clients:
  - username: "Survival_Server"
    password: "your-password-hash"
    channels:
      local:
        display_name: "Local"
        scope: SERVER
```

### NovaChat Plugin (config.yml)

See [examples/novachat-config.yml](examples/novachat-config.yml) for a complete example.

```yaml
# Backend connection
backend:
  host: "127.0.0.1"
  port: 8888
  username: "Survival_Server"
  password: "your-password"

# Chat settings
chat:
  replace_vanilla: false
  default_channel: "local"

# Message formats
format:
  channels:
    global: "&c[Global] &7{player}&f: {message}"
    local: "&e[Local] &7{player}&f: {message}"
```

## Commands

### Player Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/nc help` | Show available commands | - |
| `/nc join <channel>` | Join a channel | - |
| `/nc leave` | Leave current channel | - |
| `/nc create <name> [password]` | Create private channel | `novachat.create` |
| `/nc invite <player>` | Invite player to channel | Channel owner |
| `/nc accept <code>` | Accept invitation | - |
| `/nc toggle` | Toggle chat mode | - |

### Admin Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/nc mute <player> <time>` | Mute a player | `novachat.admin` |
| `/nc kick <player>` | Kick from channel | `novachat.admin` |
| `/nc announce <channel> <msg>` | Send announcement | `novachat.admin` |
| `/nc title <channel> <title>` | Send title message | `novachat.admin` |
| `/nc reload` | Reload configuration | `novachat.admin` |
| `/nc debug [on\|off]` | Toggle debug mode | `novachat.admin` |

### Super Admin Commands

| Command | Description |
|---------|-------------|
| `/nc auth <password>` | Authenticate as super admin |
| `/nc admin spy <server> <channel>` | Monitor remote channel |

## Channel Types

| Type | Scope | Description |
|------|-------|-------------|
| **Global** | GLOBAL | Cross-server, all connected clients |
| **Server** | SERVER | Single server only |
| **World** | SERVER + `allowed_worlds` | Specific worlds within a server |
| **Private** | PRIVATE | Player-created, password protected |

## API

### Plugin API (Bukkit)

```java
// Get API instance
NovaChatAPI api = NovaChatAPI.getInstance();

// Send message to channel
api.sendToChannel("global", "Hello from API!");

// Listen to events
@EventHandler
public void onChannelMessage(ChannelMessageEvent event) {
    String channel = event.getChannelId();
    String message = event.getMessage();
}

@EventHandler
public void onChannelSwitch(PlayerChannelSwitchEvent event) {
    String from = event.getFromChannel();
    String to = event.getToChannel();
}
```

### REST API (NovaLink)

```bash
# Get channel list
GET /api/channels

# Send message
POST /api/channels/{id}/messages
Content-Type: application/json
{"content": "Hello World"}

# Get online players
GET /api/players
```

### Webhook

Configure webhooks in `novalink.yml`:

```yaml
webhooks:
  - url: "https://your-server.com/webhook"
    events: ["message", "join", "leave"]
    secret: "your-webhook-secret"
```

## Troubleshooting

### Connection Issues

- Verify backend is running and accessible
- Check firewall settings for port 8888
- Ensure credentials match between plugin and backend

### Permission Issues

- Verify permission nodes are correctly configured
- Check player has required permissions
- Use `/nc debug on` to see detailed logs

### Error Codes

| Code | Description | Solution |
|------|-------------|----------|
| NC-401 | Authentication failed | Check username/password |
| NC-403 | Permission denied | Verify permissions |
| NC-404 | Channel not found | Check channel ID |
| NC-410 | Invitation expired | Request new invitation |

---

# 中文

## 概述

NovaChat & NovaLink 是一个分布式跨平台 Minecraft 聊天基础设施系统，采用星型拓扑架构。NovaChat 作为前端插件部署在各类 Minecraft 服务端（Bukkit/Spigot、Velocity/BungeeCord、Nukkit、LeviLamina），NovaLink 作为独立 Java 后端负责消息路由、权限管理、数据持久化等核心逻辑。

### 主要特性

- 🌐 **跨平台支持**: Bukkit、Velocity、BungeeCord、Nukkit、LeviLamina、Fabric、NeoForge、Quilt、Forge、PocketMine-MP、Endstone、PowerNukkitX
- 🔗 **统一协议**: 自定义 NovaProtocol v1 高效通信
- 📢 **灵活频道**: 全网、服务器、世界、私有频道
- 🔐 **权限系统**: 四级权限层级（超级管理员 > 客户端管理员 > 频道管理员 > 玩家）
- 💾 **数据持久化**: MySQL、Redis 或内存存储
- 🌍 **世界过滤**: 基于玩家世界的自动路由
- 🎨 **丰富格式**: PlaceholderAPI 和颜色代码支持
- 🖥️ **Web 面板**: 实时监控和管理

## 架构

```
┌─────────────────────────────────────────────────────────────┐
│                      NovaLink 后端                           │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────────────┐   │
│  │ 频道    │ │  认证   │ │  禁言   │ │    数据库       │   │
│  │ 管理器  │ │ 管理器  │ │ 管理器  │ │ (MySQL/Redis)   │   │
│  └─────────┘ └─────────┘ └─────────┘ └─────────────────┘   │
└─────────────────────────┬───────────────────────────────────┘
                          │ NovaProtocol (TCP)
        ┌─────────────────┼─────────────────┐
        │                 │                 │
┌───────▼───────┐ ┌───────▼───────┐ ┌───────▼───────┐
│ NovaChat      │ │ NovaChat      │ │ NovaChat      │
│ Bukkit/Spigot │ │ Velocity      │ │ Nukkit        │
└───────────────┘ └───────────────┘ └───────────────┘
```

### 核心模块（三层）

| 模块 | 层级 | 职责 |
|------|------|------|
| **`novachat-common`** | 共享协议层 | NovaProtocol 数据包、编解码、提及、扩展 —— **后端与全部客户端共用** |
| **`novachat-client-core`** | 插件运行时 | 连接生命周期辅助、重连策略、客户端状态 —— **仅插件/模组使用；`novalink-core` 不依赖** |
| **`novalink-core`** | 生产后端 | 规范 Java NovaLink 服务端（路由、认证、持久化、REST/WS） |

依赖方向：`novalink-core` → 仅 `novachat-common`；各平台插件 → `novachat-client-core` → `novachat-common`。详见 [`novachat-client-core/DESIGN.md`](NovaChat/client-core/DESIGN.md)。

## 平台兼容性矩阵

### 后端服务器

| 后端 | 语言 | 协议版本 | 状态 |
|------|------|----------|------|
| NovaLink-Java | Java 17+ | v1 | ✅ **生产（规范实现）** |

### Java 版客户端

| 平台 | 语言 | Minecraft 版本 | 协议 | 状态 |
|------|------|----------------|------|------|
| Bukkit/Spigot/Paper | Java 17+ | 1.8 – 1.21.11 / 26.2 | v1 | ✅ 稳定 |
| Folia | Java 17+ | 1.19 – 1.21.11 / 26.2（稳定版 26.1.2；26.2 实验中） | v1 | ✅ 稳定 |
| Velocity | Java 25+ | 代理端 (API 4.1.0+) | v1 | ✅ 稳定 |
| BungeeCord | Java 8+ | 代理端 (API 1.21-R0.4；Waterfall 已 EOL) | v1 | ✅ 稳定 |
| Fabric | Java 21+ | 1.21.x / 26.x（默认 1.21.11；loader 0.19.3） | v1 | ⛔ 未构建（需 Gradle 9.5+） |
| NeoForge | Java 25+ | 1.20.2 – 26.1（NeoForge 26.1.0.x；MC 26.2 待发布） | v1 | ⛔ 未构建（需 Gradle 9.5+） |
| Quilt | Java 21+ | 1.21.x / 26.x（默认 1.21.11；loader 0.30.0） | v1 | ⛔ 未构建（需 Gradle 9.5+） |
| Forge | Java 8+/17+/21+ | 1.12.2 – 26.2（Forge 65.1.0） | v1 | ⛔ 未构建（需 Gradle 9.5+） |
| Sponge | Java 17+ | 1.16.5（SpongeAPI 8.2.0）— 上游已有 SpongeAPI 17.x（MC 1.21.10）/ 20.x RC（MC 26.2），本项目尚未升级 | v1 | ✅ 稳定 |

### 基岩版客户端

| 平台 | 语言 | Minecraft 版本 | 协议 | 状态 |
|------|------|----------------|------|------|
| Nukkit | Java 8+ | 基岩版 1.20+ – 26.40（Cloudburst Nukkit snapshot） | v1 | ✅ 稳定 |
| PowerNukkitX | Java 17+ | 基岩版 1.20+ – 26.40（PNX 3.0.2，协议 2168） | v1 | ✅ 稳定 |
| LeviLamina (BDS) | C++ | 基岩版 1.20+ – 26.40（LeviLamina 26.20.x） | v1 | ✅ 稳定 |
| PocketMine-MP | PHP 8.1+ | 基岩版 1.20+ – 26.30（协议 ≤1001；PMMP 5.44.3 于 2026-07 归档） | v1 | ✅ 稳定 |
| Endstone | Python 3.10+ | 基岩版 1.20+ – 26.40（Endstone 0.11.8，BDS 1.26.40） | v1 | ✅ 稳定 |

### 协议版本兼容性

所有客户端和后端必须使用相同的协议版本才能通信。当前协议版本为 **v1**。

| 协议版本 | 支持的后端 | 支持的客户端 |
|----------|-----------|-------------|
| v1 | NovaLink-Java | 上述所有平台 |

## 安装

### 环境要求

- Java 17+（**生产** NovaLink 后端和现代插件）
- Java 21+（Fabric/Quilt/Forge/NeoForge 针对 1.20.5+；也是 MC 1.21.x 下 Paper/Folia 服务端构建的下限）
- Java 25+（**Velocity** 代理模块 —— `novachat-velocity` 固定 `VERSION_25`，JDK25 下禁用 Lombok；也是 MC 26.1+ 服务端平台的下限）
- Java 8+（旧版 Minecraft 插件）
- PHP 8.1+（PocketMine-MP 插件）
- Python 3.10+（Endstone 插件）
- MySQL 5.7+（可选）
- Redis 6+（可选）

> 上述平台版本反映 2026-08-08 的最新发布（Minecraft Java 26.2、Bedrock 26.42）。模组加载器平台（Fabric/NeoForge/Quilt/Forge）**当前未构建** —— 其 `novachat-mod:<loader>` 子工程在 `settings.gradle` 中被注释，待 Gradle wrapper 升级到 9.5+ 后启用。见矩阵中各行状态。

### NovaLink 后端安装

1. 从发布页下载 `novalink-core.jar`
2. 创建目录并放置 JAR 文件
3. 首次运行生成配置：
   ```bash
   java -jar novalink-core.jar
   ```
4. 编辑 `novalink.yml`（参见[配置](#配置)）
5. 启动后端：
   ```bash
   java -jar novalink-core.jar
   ```

### NovaChat 插件安装

1. 下载对应服务端的插件：

   **Java 版：**
   - `novachat-bukkit.jar` - Bukkit/Spigot/Paper
   - `novachat-velocity.jar` - Velocity 代理
   - `novachat-bungee.jar` - BungeeCord 代理
   - `novachat-mod-fabric.jar` - Fabric 1.20.x+
   - `novachat-mod-neoforge.jar` - NeoForge 1.20.2+
   - `novachat-mod-quilt.jar` - Quilt 1.20.x+
   - `novachat-mod-forge.jar` - Forge 1.20.x

   **基岩版：**
   - `novachat-nukkit.jar` - Nukkit
   - `novachat-pnx.jar` - PowerNukkitX
   - `novachat-levilamina.dll` - LeviLamina (BDS)
   - `novachat-pmmp.phar` - PocketMine-MP
   - `novachat-endstone/` - Endstone（Python 包）

2. 放入服务器的 plugins/mods 文件夹
3. 启动服务器生成配置
4. 编辑配置文件（位置因平台而异）
5. 重启服务器

## 配置

### NovaLink 后端 (novalink.yml)

完整示例请参见 [examples/novalink.yml](examples/novalink.yml)。

```yaml
# 服务器设置
server:
  bind-address: 0.0.0.0
  port: 8888
  websocket-port: 8889

# 数据库设置
database:
  type: mysql  # mysql, redis, memory
  mysql:
    host: 127.0.0.1
    port: 3306
    database: novalink
    username: root
    password: password

# 全网频道（跨服）
global_channels:
  global:
    display_name: "全服"
    permission: "novachat.channel.global"

# 客户端配置
clients:
  - username: "Survival_Server"
    password: "your-password-hash"
    channels:
      local:
        display_name: "本地"
        scope: SERVER
```

### NovaChat 插件 (config.yml)

完整示例请参见 [examples/novachat-config.yml](examples/novachat-config.yml)。

```yaml
# 后端连接
backend:
  host: "127.0.0.1"
  port: 8888
  username: "Survival_Server"
  password: "your-password"

# 聊天设置
chat:
  replace_vanilla: false
  default_channel: "local"

# 消息格式
format:
  channels:
    global: "&c[全服] &7{player}&f: {message}"
    local: "&e[本地] &7{player}&f: {message}"
```

## 命令

### 玩家命令

| 命令 | 描述 | 权限 |
|------|------|------|
| `/nc help` | 显示可用命令 | - |
| `/nc join <频道>` | 加入频道 | - |
| `/nc leave` | 离开当前频道 | - |
| `/nc create <名称> [密码]` | 创建私有频道 | `novachat.create` |
| `/nc invite <玩家>` | 邀请玩家加入频道 | 频道所有者 |
| `/nc accept <邀请码>` | 接受邀请 | - |
| `/nc toggle` | 切换聊天模式 | - |

### 管理员命令

| 命令 | 描述 | 权限 |
|------|------|------|
| `/nc mute <玩家> <时间>` | 禁言玩家 | `novachat.admin` |
| `/nc kick <玩家>` | 踢出频道 | `novachat.admin` |
| `/nc announce <频道> <消息>` | 发送公告 | `novachat.admin` |
| `/nc title <频道> <标题>` | 发送 Title 消息 | `novachat.admin` |
| `/nc reload` | 重载配置 | `novachat.admin` |
| `/nc debug [on\|off]` | 切换调试模式 | `novachat.admin` |

### 超级管理员命令

| 命令 | 描述 |
|------|------|
| `/nc auth <密码>` | 超级管理员认证 |
| `/nc admin spy <服务器> <频道>` | 监听远程频道 |

## 频道类型

| 类型 | 作用域 | 描述 |
|------|--------|------|
| **全网频道** | GLOBAL | 跨服互通，所有连接的客户端 |
| **服务器频道** | SERVER | 仅限单个服务器 |
| **世界频道** | SERVER + `allowed_worlds` | 服务器内指定世界 |
| **私有频道** | PRIVATE | 玩家创建，密码保护 |

## API

### 插件 API (Bukkit)

```java
// 获取 API 实例
NovaChatAPI api = NovaChatAPI.getInstance();

// 发送消息到频道
api.sendToChannel("global", "来自 API 的消息！");

// 监听事件
@EventHandler
public void onChannelMessage(ChannelMessageEvent event) {
    String channel = event.getChannelId();
    String message = event.getMessage();
}

@EventHandler
public void onChannelSwitch(PlayerChannelSwitchEvent event) {
    String from = event.getFromChannel();
    String to = event.getToChannel();
}
```

### REST API (NovaLink)

```bash
# 获取频道列表
GET /api/channels

# 发送消息
POST /api/channels/{id}/messages
Content-Type: application/json
{"content": "Hello World"}

# 获取在线玩家
GET /api/players
```

### Webhook

在 `novalink.yml` 中配置 Webhook：

```yaml
webhooks:
  - url: "https://your-server.com/webhook"
    events: ["message", "join", "leave"]
    secret: "your-webhook-secret"
```

## 故障排除

### 连接问题

- 确认后端正在运行且可访问
- 检查防火墙设置（端口 8888）
- 确保插件和后端的凭据匹配

### 权限问题

- 确认权限节点配置正确
- 检查玩家是否拥有所需权限
- 使用 `/nc debug on` 查看详细日志

### 错误代码

| 代码 | 描述 | 解决方案 |
|------|------|----------|
| NC-401 | 认证失败 | 检查用户名/密码 |
| NC-403 | 权限不足 | 验证权限配置 |
| NC-404 | 频道不存在 | 检查频道 ID |
| NC-410 | 邀请码过期 | 请求新的邀请码 |

---

## License

MIT License - see [LICENSE](LICENSE) for details.

## Contributing

Contributions are welcome! Please read our contributing guidelines before submitting PRs.

## Support

- GitHub Issues: [Report bugs](https://github.com/your-repo/novachat/issues)
- Discord: [Join our community](https://discord.gg/your-invite)
