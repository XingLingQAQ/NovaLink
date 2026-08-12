# 开发与测试

NovaLink 是一个包含 Java 后端、多个平台接入端、非 Java Bedrock 模块和 React 管理面板的多模块仓库。有效的验证策略应贴近改动边界：修改后端时先运行后端测试；修改面板时构建面板；修改平台适配时在目标平台上做 smoke 或真实链路验证。不要为了改一处文案而启动完整 Minecraft 集群，也不要因为全仓编译成功就宣布跨平台行为已验证。[1] [2]

## 1. 本地环境

| 组件 | 主要用途 | 说明 |
| --- | --- | --- |
| JDK 17+ | 编译 Java 后端与多数 Java 模块 | 根构建对 Java 子项目设置 Java 17 源/目标兼容性；特定真实 E2E 平台可能需要不同 JDK。 |
| Gradle Wrapper | 统一构建入口 | 使用仓库的 `./gradlew` 或 Windows 的 `gradlew.bat`。 |
| Node.js | Admin Console | 仅在构建或开发 `Panel/web` 时需要。 |
| 各平台运行时 | 平台适配测试 | Bukkit、代理、Bedrock、Sponge、Loader 的要求按模块与 E2E 锁定配置变化。 |
| PowerShell 与测试资源 | 真实 E2E | `realE2E` 是可选路径，需要实际服务端和机器人条件。 |

非 Java Bedrock 模块不会被根项目当作普通 Java 子项目应用 Java 插件；它们经原生/PHP/Python 工具链参与构建或验证。修改这些模块前应先阅读其目录中的构建脚本与平台说明。[1] [3]

## 2. 日常构建命令

在仓库根目录运行：

| 目标 | 命令 | 适用情况 |
| --- | --- | --- |
| 构建全部默认模块 | `./gradlew build` | 提交前的基础编译与测试门槛。 |
| 执行常规校验 | `./gradlew check` | 需要运行项目已接入的检查任务时。 |
| 只验证后端行为 | `./gradlew :StarLink:core:test` | 修改认证、路由、配置、数据库或 API 时优先执行。 |
| 构建后端 fat JAR | `./gradlew :StarLink:core:shadowJar` | 准备本地/部署运行包时。 |
| 查看任务 | `./gradlew tasks` | 不确定模块是否存在特定任务时。 |

全仓构建是重要信号，但它无法模拟所有真实服务端、玩家事件、代理链路、线程模型或 Bedrock 运行时差异。对影响协议或平台适配的改动，应在相应平台补充验证。[1] [2]

## 3. 后端修改的推荐验证顺序

`StarLink/core` 负责认证、频道、路由、持久化、REST、WebSocket 与控制台。后端修改应从最小测试圈开始，逐步扩大。

| 改动类别 | 最小验证 | 扩展验证 |
| --- | --- | --- |
| 配置模型或解析 | 对应配置/单元测试；启动测试配置 | 手动重载、检查 ConfigSync 与运行时开关。 |
| 认证或客户端权限 | 后端测试 | 一次真实接入端握手、失败凭据、允许 IP 场景。 |
| 频道与消息路由 | 后端测试 | 受控的多客户端/多频道消息路径。 |
| REST 或 WebSocket | 后端测试与 API 负载对照 | 面板构建、登录、订阅、状态推送。 |
| 数据库提供者 | 后端测试 | 在目标数据库上验证迁移、读写、备份与恢复。 |
| 治理/通知 | 后端测试 | 受控 UUID、频道范围、期限和面板通知检查。 |

后端启动会装配配置监听器、TCP 服务和管理网关。对这些路径的改动应通过实际启动日志和失败模式检查，而不仅是通过静态阅读确认。[4]

## 4. Admin Console

`Panel/web` 是 React + Vite 管理面板。修改前端或其 API 契约后，运行：

```bash
cd Panel/web
npm ci
npm run build
```

面板默认通过同源 `/api` 访问 REST，并用当前主机的 `8889` 端口构建 WebSocket URL；高级设置和环境变量可覆盖地址。对前后端契约改动，至少验证登录、token 刷新、一个受保护 REST 调用、WebSocket 鉴权和频道订阅。[5] [6]

## 5. 真实服务端 E2E

真实 E2E 位于 `e2e/`，由根 Gradle 项目注册为**显式选择**的 `realE2E` 任务。普通 `build`、`test` 与 `check` 不会自动启动真实服务端；只有在传入 `-PenableRealE2E=true` 时，`check` 才会依赖该任务。[1]

```bash
# 运行默认平台集合的真实 E2E（需要准备完整前置条件）
./gradlew realE2E

# 限制平台集合
./gradlew realE2E -Pe2ePlatforms=bukkit,velocity

# 显式把真实 E2E 纳入 check
./gradlew check -PenableRealE2E=true
```

真实 E2E 使用 PowerShell 编排真实服务端、机器人和 NovaLink 后端，需要匹配的服务端二进制、Node.js、相关 JDK 与平台运行环境。`e2e/README.md` 还区分了严格哈希校验的 pinned 下载模式与自动发现最新构建的 Auto 模式；不要把 Auto 模式当作固定可复现版本的替代。[1] [2]

| 何时考虑真实 E2E | 典型原因 |
| --- | --- |
| 修改协议帧、握手或包注册 | 编译无法发现真实客户端/服务端编码互操作问题。 |
| 修改跨服路由、频道或命令 | 需要验证真实玩家与机器人消息回环。 |
| 修改平台代理生命周期/线程调度 | 需要发现平台事件线程或启停时序错误。 |
| 更新上游 API/Minecraft/JDK | 需要确认声明版本与运行环境的可用性。 |
| 改动 Bedrock 接入 | 需要使用对应 BDS/PMMP/原生环境检验。 |

## 6. 文档与接口变更

当修改任何用户可见或集成可见契约时，测试不应只包含代码：

| 变更 | 必须同步审阅 |
| --- | --- |
| YAML 字段、默认值或重载语义 | [配置指南](../guide/configuration.md)、示例配置、部署手册。 |
| REST 路由、字段或状态码 | [管理 API](../reference/admin-api.md)、面板 API 客户端、集成示例。 |
| WebSocket 消息类型/字段 | [实时网关](../reference/realtime-gateway.md)、前端 WS 客户端与上下文。 |
| 模块依赖、协议或客户端生命周期 | [架构参考](../reference/architecture.md)、[协议与客户端](../reference/protocol-and-clients.md)。 |
| 管理命令和治理语义 | [运行手册](../operations/operations-runbook.md)。 |

## 7. 提交前检查表

| 检查项 | 通过标准 |
| --- | --- |
| 影响范围 | 已列出后端、面板、平台适配、配置与文档影响。 |
| 最小测试 | 已运行最接近改动的 Gradle/Node 测试或构建。 |
| 平台验证 | 若改动跨平台，已说明实际验证的平台与未验证平台。 |
| 配置安全 | 未提交真实密码、token、私有 IP、Webhook URL 或生产数据。 |
| 文档链接 | 新增或更新的 Markdown 链接可解析。 |
| 真实 E2E | 只有在前置条件满足时运行；未运行时如实说明原因。 |

验证报告应记录命令、环境、结果、已知限制和失败信息。不可复现的“本地应该没问题”不能替代可审阅的证据。

## 参考资料

[1]: ../../build.gradle "根构建、Java 版本与可选真实 E2E 任务"
[2]: ../../e2e/README.md "真实服务端 E2E 环境、平台矩阵与下载模式"
[3]: ../../settings.gradle "非 Java Bedrock 模块声明"
[4]: ../../StarLink/core/src/main/java/com/nova/link/NovaLinkMain.java "后端启动路径"
[5]: ../../Panel/web/package.json "Admin Console 构建脚本"
[6]: ../../Panel/web/src/services/api.js "面板 API 与 WebSocket 地址解析"
