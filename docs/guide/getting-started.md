# 快速开始：让第一个服务器接入 NovaLink

本指南的目标不是一次性配置所有能力，而是建立一条可验证的最小链路：**NovaLink 后端启动成功，一个 NovaChat 接入端完成认证，且管理员能够确认客户端在线。** 后端是独立 Java 进程；平台插件、代理或模组作为 NovaChat 客户端接入同一 NovaProtocol TCP 服务。[1] [2]

> **适用范围。** 以下步骤使用仓库当前的构建任务和示例配置。不同平台模块的安装目录、发布物和上游运行时要求并不完全一致；请在选定模块的构建文件、元数据文件和目标平台环境中完成最终验证。[3]

## 1. 准备条件

| 项目 | 最低要求 | 用途 |
| --- | --- | --- |
| Git | 可用 | 获取源码。 |
| JDK | 17 或更高 | 构建并运行 Java 后端与大多数 Java 平台模块。 |
| Node.js | 仅管理面板需要 | 构建 `Panel/web`。 |
| 目标平台运行时 | 按模块而定 | 加载 Bukkit、代理、Bedrock、Sponge 或模组接入端。 |
| 可持久化存储 | 生产建议 | 保存运营与状态数据；本地首次验证可先使用 SQLite。 |

不要把示例中的数据库密码、JWT 密钥、客户端密码或允许网段直接用于生产环境。它们只描述字段位置，并不构成安全默认值。[4]

## 2. 构建 NovaLink 后端

在仓库根目录执行：

```bash
git clone https://github.com/XingLingQAQ/NovaLink.git
cd NovaLink

# Linux / macOS
./gradlew :StarLink:core:shadowJar

# Windows PowerShell
.\gradlew.bat :StarLink:core:shadowJar
```

该任务产出携带依赖的后端 JAR，默认位置为：

```text
StarLink/core/build/libs/*-all.jar
```

如需先确认整个仓库的基础构建状态，可使用 `./gradlew build`；如只关心后端行为，使用 `./gradlew :StarLink:core:test`。真实 Minecraft 服务端 E2E 并不会随普通构建默认运行。[5]

## 3. 创建最小后端配置

从示例复制配置文件：

```bash
cp examples/novalink.yml novalink.yml
```

对于第一次本地验证，可以将数据库改为 SQLite，并只配置一个客户端。下面的片段保留了后端当前实际读取的字段：

```yaml
server:
  bind-address: 127.0.0.1
  port: 8888
  websocket-port: 8889
  secret-key: "replace-with-a-long-random-secret"

database:
  type: sqlite
  sqlite:
    file-path: data/novalink.db

security:
  allowed-ips:
    - 127.0.0.1
  ip-ban-duration: 300

clients:
  - username: "survival"
    password: "replace-with-a-strong-client-password"
    display_name: "Survival"
```

后端使用 `server.port` 接收 NovaProtocol TCP 连接，并在 `server.websocket-port` 上提供管理控制面。`clients[].username` 与 `clients[].password` 必须和接入端配置相匹配。后端会将非 64 位 SHA-256 十六进制形式的客户端密码处理为 SHA-256 哈希；接入端在握手前也会计算密码哈希。因此，首次验证中可使用相同的强随机明文，生产环境则应采用受控的密钥管理方式。[6]

## 4. 启动后端

默认情况下，后端从当前工作目录读取 `novalink.yml`：

```bash
java -jar StarLink/core/build/libs/*-all.jar
```

也可以显式传入配置文件路径：

```bash
java -jar StarLink/core/build/libs/*-all.jar /opt/novalink/novalink.yml
```

启动成功后，日志会报告 TCP 与 WebSocket 的绑定地址和端口。启动失败时，优先检查配置文件路径、数据库连接、端口占用和 JDK 版本；不要在未确认问题原因前反复重启生产实例。[6]

## 5. 配置第一个 NovaChat 接入端

将目标平台对应的 NovaChat 构件放入该平台的插件、代理或模组目录，再按接入端配置格式填写后端地址与凭据。对支持示例格式的平台，核心连接字段如下：

```yaml
backend:
  host: "127.0.0.1"
  port: 8888
  username: "survival"
  password: "replace-with-a-strong-client-password"
  reconnect-delay: 5
  timeout: 30
```

`host` 与 `port` 指向 NovaLink 的 TCP 服务；`username` 与 `password` 必须与后端 `clients` 条目对应。客户端在 TCP 连接成功后仍会等待握手响应；仅 TCP 可达不等于认证完成。插件侧会处理 KeepAlive 和非预期断线重连，显式停用时不应继续重连。[7] [8]

## 6. 完成最小验收

按以下顺序检查，而不是只看进程是否还在运行：

| 检查点 | 预期结果 | 排查方向 |
| --- | --- | --- |
| 后端启动 | 日志显示 TCP 与 WS 服务已启动 | 端口占用、YAML、数据库、JDK。 |
| 客户端握手 | 接入端日志显示认证成功 | 用户名、密码、协议版本、允许 IP。 |
| 后端状态 | 控制台 `clients` 可看到已认证客户端 | 客户端网络、凭据、IP 限制。 |
| 频道可见性 | `channels` 显示配置中的全局或客户端频道 | YAML 缩进、频道 ID、配置重载。 |
| 消息路径 | 在预期频道中发送一条测试消息 | 客户端聊天拦截设置、频道成员、权限或功能开关。 |

后端控制台支持 `status`、`clients`、`players`、`channels` 等只读命令，可作为首次接入时的无侵入检查。完整操作语义见[运行手册](../operations/operations-runbook.md)。[9]

## 7. 下一步

完成最小链路后，建议不要直接扩大网络范围。先阅读[配置指南](configuration.md)，明确频道作用域、客户端权限和功能开关；随后阅读[部署指南](deployment.md)与[安全基线](../operations/security.md)，再把服务暴露给真实网络。若需要管理面板或自动化集成，请继续阅读[管理 API](../reference/admin-api.md)和[实时网关](../reference/realtime-gateway.md)。

## 参考资料

[1]: ../../README.md "NovaLink 项目概览与构建入口"
[2]: ../../StarLink/core/src/main/java/com/nova/link/NovaLinkMain.java "后端启动、TCP 与 WebSocket 装配"
[3]: ../../settings.gradle "仓库当前 Gradle 模块声明"
[4]: ../../examples/novalink.yml "后端示例配置与安全占位符"
[5]: ../../build.gradle "构建与可选真实 E2E 任务"
[6]: ../../StarLink/core/src/main/java/com/nova/link/NovaLinkMain.java "配置路径、客户端凭据与数据库提供者"
[7]: ../../examples/novachat-config.yml "NovaChat 接入端示例配置"
[8]: ../../NovaChat/client-core/DESIGN.md "客户端握手、KeepAlive 与重连生命周期"
[9]: ../../StarLink/core/src/main/java/com/nova/link/console/ConsoleCommandHandler.java "后端控制台命令"
