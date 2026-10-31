# NovaLink 真实服务器 E2E 自动化测试设计

> 设计基线：2026-08-01 当前工作树。本文档只给出**可执行的设计**（脚本结构、版本固定、断言、回收、CI 编排）；实际下载/启动/接受 EULA 等执行动作由后续实施阶段按本文档落地，**不在本文档产出时执行**。
>
> 当前实现以 `test/` 为准，矩阵含 bukkit、folia、velocity、bungee、nukkit、pnx、sponge、endstone、levilamina 和 pmmp。下文原设计及早期实跑记录保留作历史参考；当前契约见 §1.5.1、§4 和 [test/README.md](../test/README.md)。完整矩阵的通过情况以未过滤的 CI 运行产物为准。

Endstone 运行器按 `bds.EndstoneVersion` 安装固定版本，并校验其要求的 Minecraft 版本。Endstone 包和 BDS 必须同时升级，L1 运行不使用 `-Auto` 替换配套 BDS。

---

## 1. 目标与边界

### 1.1 要验证什么

现有测试体系（`docs/TEST-MATRIX-CODE.md`）覆盖 unit / property / embedded integration（进程内起 Netty，`MultiClientSimulator` 模拟多个协议客户端）。它的盲区是：

1. **真实服务端进程**：Paper/Folia/Velocity/BungeeCord/Nukkit/PNX/Sponge 的启动、插件装载（plugin.yml 声明、api-version、folia-supported）、配置读取、插件生命周期回调，只有真实进程能验证。
2. **真实 Minecraft 客户端协议**：插件端把玩家的登录/进服/聊天/世界切换事件映射为 NovaProtocol 包；嵌入式测试绕过了 Bukkit/Nukkit API 层，覆盖不到这部分适配。
3. **后端独立进程**：`NovaLinkMain` 以 `java -jar` 方式运行时的配置解析（首个参数为配置文件路径）、端口绑定、优雅关闭、崩溃行为。
4. **代理拓扑**：Velocity/BungeeCord 的 `backend` 配置注入与连线。
5. **跨平台互操作**：Java 与基岩端同时连线同一 backend。

### 1.2 不验证什么（诚实标注，见 §9）

- 不验证 Minecraft **玩家客户端的登录握手**（除非引入真实客户端自动登录，见 §7.4 的 P2 可行性——该路径依赖微软账号/Mojang 账号，自动化不可行或受反滥用限制）。
- 不验证 Folia 的**多线程调度正确性**（真实服务器进程内的调度行为可冒烟，但线程交错断言不可靠）。
- 不验证 BungeeCord 旧协议栈与高版本 Paper 下游的完整兼容（Bungee 仅做 `login`/`server` 级冒烟，见 §5.4）。
- LeviLamina、PocketMine-MP 和 Endstone 已纳入 `test/` 执行矩阵；Fabric / NeoForge / Quilt / Forge 不在这套 10 平台矩阵内。
- 不验证 **Nukkit/PNX 的基岩客户端协议**（真实基岩客户端登录）；只验证 Nukkit/PNX 服务端进程 + 插件装载 + 到 backend 的 NovaProtocol 连线。
- 不验证 **MySQL/Redis 持久化**：`database.type=memory`. MySQL/Redis/PostgreSQL 的真实持久化另由 `MySQLProvider*`/`RedisProvider*`/`PostgreSQLRealInstanceTest` 系列集成测试覆盖（直接连真实数据库实例，不依赖 Testcontainers）；`TestContainersConfig` 工具类此前是孤儿代码（无任何测试调用）已删除。

### 1.3 冻结平台红线

10 个矩阵条目中，bungee 和 velocity 因缺少下游 Purpur 拓扑而按设计跳过，不执行完整 L1；其余条目已接入 runner。接入状态不代表本轮实跑通过。

---

## 1.5 实施现状与历史记录

> §1.5.1 按当前锁文件和 runner 更新；§1.5.2～§1.5.10 保留 2026-08 的本地 `.e2e/` 历史记录，不作为当前 `test/` 或 CI 的通过证明。

### 1.5.1 当前 10 平台矩阵与 L1 契约

完整 L1 要求真实协议机器人（Java 用 mineflayer，Bedrock 用 bedrock-protocol）连入服务端，验证 `/nc` 命令和聊天往返：机器人发测试内容 → 插件转发 → backend 路由 → 插件收包 → 机器人收到含频道、机器人身份和原始内容的格式化消息。自身发送记录、原版回显、仅命令成功或 `PARTIAL` 均不足以证明聊天回环。当前产物在 `.e2e-artifacts/runs/`。

| 平台 | 当前锁定服务端 | 实现状态 |
|---|---|---|
| bukkit | Purpur 1.21.11-2568，JDK 21 | 独立 runner 已接入 |
| folia | Folia 26.1.2-8，JDK 25 | 配置注入和严格聊天 gate 已完成 |
| velocity | Velocity 4.1.0-SNAPSHOT-16，JDK 25 | `SKIPPED-BY-DESIGN`，非 Full L1 |
| bungee | Waterfall 1.21-615，JDK 21 | `SKIPPED-BY-DESIGN`，非 Full L1 |
| nukkit | Cloudburst Nukkit 1.0-SNAPSHOT build 1242 | 共享 runner 已接入 |
| pnx | 原生 PowerNukkitX 3.0.6，JDK 21，Bedrock 1.26.60 | 独立版本锁，插件使用 3.x API |
| sponge | SpongeVanilla 1.21.10-17.0.0，JDK 21 | 共享 runner 已接入 |
| endstone | `bds` 1.26.52.3 | 独立 runner 已接入 |
| levilamina | `bds-levilamina` 1.26.51.1 + LeviLamina 26.51.7 | 独立 runner 已接入 |
| pmmp | PocketMine-MP 5.44.3 + `pmmp-php` | 严格聊天 gate 已完成 |

PMMP 已设 `replace_vanilla: true`，首条聊天前保持转发开启，主 `chatOk` 纳入 `pass`；JOIN/LEAVE 包带 `playerId`、`playerName` 和 `platform`。Folia 配置写入 `plugins/NovaChat-Folia/config.yml`。全部 L1 runner 统一检查运行 ID、完成标志、退出码、超时和服务器致命日志；缺少真实聊天回环、`PARTIAL` 和 `UNKNOWN` 均不能判成功。

PNX 已改用[官方 3.0.6 发布包](https://github.com/PowerNukkitX/PowerNukkitX/releases/tag/3.0.6)，锁定 SHA-256 和协议 2225。插件同步迁移到 `org.powernukkitx` API。此前“PNX 通过”的 Nukkit 回退记录只证明兼容路径，不能作为这次原生 PNX 验证的证据。

平台 exit 3 为环境或基础设施阻塞（`SKIPPED`），`-RequireAllPlatforms` 将其视为失败；exit 4 为结构性限制（`SKIPPED-BY-DESIGN`），严格模式允许跳过，仍不记为 PASS。下载或哈希校验失败属于前置错误（平台 exit 2）。定向 CI 中，未选中的 job 可直接 exit 0 显绿；须核对选择范围、实际执行步骤、机器人记录及 `e2e-orchestrator-summary.json` 的逐平台状态。10 个条目和绿色工作流均不等于全矩阵真实通过。

### 1.5.2 与设计的关键分叉

| # | 设计（§2–§8） | 实际落地 | 影响 |
|---|---|---|---|
| a | §3.1 目录 `e2e/`（小写），含 `conf/`、`tests/`、`report/` | 实际工作区为 `.e2e/`（gitignore），结构：`.e2e/bin/`（平台专属启动脚本）、`.e2e/artifacts/`（`dist/` 下载物 + `runs/` 配置/端口/classpath）、`.e2e/<platform>/`（服务端运行目录 + `bot/results.json`）、`.e2e/bot/`（共享 node 机器人源 + `run-e2e.js`）、`.e2e/backup/` | 布局重命名，产物路径迁移；§3.1 的 `e2e/` 骨架仍是有效设计参考 |
| b | §4.1 velocity 锁 3.4.0-SNAPSHOT，JDK21 | **velocity 适配到 4.1.0-SNAPSHOT**，velocity 模块 `sourceCompatibility/targetCompatibility = VERSION_25`，需 JDK25（temurin25-jdk / openjdk 25.0.4）；实际下载物 `velocity-4.1.0-SNAPSHOT-13.jar` | §4.1 velocity 行已过时，见 §1.5.3 更新；也触发了 Finding A（§1.5.4） |
| c | §3.3 bukkit 平台用 Paper 1.21.x | 实际用 **Purpur 1.21.8**（build 2497）作为 bukkit 平台服务端，因为 PaperMC API v2 已 sunset、v3 在本机 IP 经 Cloudflare 403，而 Purpur API 可正常下载 | 就绪标志行为一致；仅下载源不同 |
| d | §5.3 backend 以 `java -jar artifacts/dist/novalink-core.jar` 启动 | **`novalink-core` 无 shadow 插件**（`novalink-core/build.gradle` 只有 `id 'java'`），`java -jar` 会 NoClassDefFoundError。实际经 init-script 导出 `runtimeClasspath` 到 `.e2e/artifacts/runs/novalink-core.classpath.txt`，用 `java -cp <classpath> com.nova.link.NovaLinkMain <config>` 启动 | §5.3 已更正，见 §1.5.5 |
| e | §7 机器人选型 A（内嵌协议客户端）为 MVP 主选 | 实际 L1 全部用**真实 MC 协议机器人**（方案 C：mineflayer / bedrock-protocol），因为它能驱动完整玩家事件 + `/nc` 命令 UX，覆盖面优于纯协议客户端 | 设计的 A/B 方案仍有效；现实走了 C 路线，已验证可行 |
| f | §2.1 P0 仅 backend+Paper，P1 才加 proxy，P2 才加 bedrock | 实际一次性推进到 7/7 Java 平台 L1（含 proxy + bedrock + sponge），未严格按 P0→P1→P2 分阶段 | 进度超前于设计阶段划分 |

### 1.5.3 §4.1 锁文件 velocity 行更正

设计 §4.1 的 velocity 锁行（`3.4.0-SNAPSHOT`）已过时。实际使用：

```powershell
@{ Name='velocity';   MC='4.1.0';  Engine='velocity';
   Url='https://api.papermc.io/v2/projects/velocity/versions/4.1.0-SNAPSHOT/builds/13/downloads/velocity-4.1.0-SNAPSHOT-13.jar';
   Sha256='<首次下载后用 Get-FileHash 实测回填>'; Eula=$false; Jdk=25 }
```

> velocity 模块（`novachat-velocity/build.gradle`）固定 `JavaVersion.VERSION_25`；Lombok 1.18.x 在 JDK25 的 annotation-processor SPI 下崩溃，故 velocity 源码不使用 Lombok。bungee/bukkit/folia/nukkit/pnx/sponge 仍按 JDK21 构建。bukkit 平台服务端用 Purpur（见 §1.5.2 c）。

### 1.5.4 E2E 发现的真实产品 bug（已修复）

> 这些 bug 均被代码级测试矩阵（`docs/TEST-MATRIX-CODE.md`）漏掉，由真实服务器 E2E 暴露——印证了本套件存在的价值。Finding A/B2 见下；Finding C（sponge `novachat.use` default-deny）见 §1.5.6。

**Finding A — Velocity 4.1.0 命令节点剪枝（NC-VELOCITY）**

- 文件：`novachat-velocity/.../command/NovaChatCommand.java:339-356`
- 现象：Velocity 4.1.0 改变了 Brigadier 分发——当 `SimpleCommand.hasPermission` 对某 sender 返回 **false** 时，4.1.0 会把该命令节点的子命令节点从该 sender 的分发树中**剪枝**（3.4.0 不会）。离线模式玩家无任何授权 → `hasPermission("novachat.use")` 返回 false → `nc` 节点的子命令（help/join/leave/...）被剪 → 每个 `/nc <sub>` 报 "Incorrect argument at position 3: nc <--[HERE]"（权限拒绝伪装成语法错误）。
- 为何 bungee E2E 没暴露：BungeeCord 默认权限提供者授予 ALL 权限；Velocity 4.1.0 default-deny + 节点剪枝才触发。
- 修复：`hasPermission` 改为无条件 `return true;`（带注释说明）；admin 闸口 `/nc reload` 在 `handleReload`（`NovaChatCommand.java:272`）内独立检查 `novachat.admin`，admin 闸口保留。
- 回归测试：`NovaChatCommandPermissionTest.java`（源码级断言 hasPermission body 为 `return true`，不引用 `novachat.use`）。velocity 模块现 4 个测试类（原 3）。

**Finding B2 — Nukkit + PNX `registerCommands` 静默失败**

- 文件：`NovaChatNukkit.java:264-277`、`NovaChatPNX.java:179-192`
- 现象：Nukkit 用 `nukkit.yml`（jar 根，非 `plugin.yml`）、PNX 用 `plugin.yml` 声明 `commands: novachat: aliases: [nc]`，`PluginManager.parseYamlCommands` 据此预注册一个无 executor 的 `PluginCommand` 占住 `nc`/`novachat` 别名槽。原 `registerCommands()` 调 `getCommandMap().register("novachat", commandHandler)` **静默返回 false（无日志、无异常）**，因为别名槽已被占。预注册的 `PluginCommand` 无 executor → `execute` 返回 false → 回 "usage"。
- 修复：先经 `getCommandMap().getCommand("novachat")` 查描述符预注册的 `PluginCommand`，调 `setExecutor(commandHandler)`；`register()` 保留为 fallback。`NovaChatCommand`（nukkit 与 pnx）现 `implements CommandExecutor` + `onCommand(...)` 桥接。
- 跨平台审计：**仅 nukkit+pnx** 有此 bug；bukkit/folia 已用安全的 `setExecutor` 模式；bungee/velocity 用不同且正常的 API。

### 1.5.5 §5.3 backend 启动更正

设计 §5.3 的 `java -jar artifacts/dist/novalink-core.jar` **不可行**——`novalink-core/build.gradle` 无 shadow 插件，`java -jar` 会 NoClassDefFoundError。实际启动（见 `.e2e/bin/start-backend.ps1`）：

```powershell
# init-script 先把 runtimeClasspath 导出到 classpath 文件
$cp = (Get-Content $ClassPathFile -Raw).Trim()
java -cp $cp com.nova.link.NovaLinkMain $Config
```

配置实例（`.e2e/artifacts/runs/novalink.yml`）：`database.type: memory`、`bind-address: 127.0.0.1`、随机 `secret-key`（每次运行生成）、客户端 `E2E_Client` / `e2e-secret-password`、`debug: true`、`filter.enabled: false`、`announcements` 全空——与 §5.2 设计要点一致，仅启动方式不同。

### 1.5.6 sponge L1 达成（2026-08-08）

sponge L1 **已达成**（2026-08-08）。完整 3-way 日志证据：机器人 `join` + `REPLACE` toggle + `ChatMessagePacket{...content='hello from sponge bot'...}` 往返；真实 `/nc help` 文案已断言；权限 default-deny 修复已落地。落地细节：
- 服务端：**SpongeVanilla 1.16.5-8.2.0**（SpongeAPI 8.2.0 / MC 1.16.5），JDK 17（`novachat-sponge/build.gradle:6-8` 固定 `VERSION_17`，`:34` 依赖 `spongeapi:8.2.0`）。
- 构建：sponge 模块用 shadow 8.1.1 打 fat jar（`NovaChat-Sponge`），受 §1.5.7 影响需绕过 shadow；Mixin 0.8.5、Gson 2.10.1 一并 bundle 进 fat jar。
- **Finding C — Sponge `novachat.use` default-deny（已修复）**：命令注册用 `.permission("novachat.use")`（`NovaChatCommand.java:60/114/128/195`），**无 `permissionDefaults` 声明**——与 velocity（Finding A）同类 default-deny。离线模式玩家无授权 → 子命令不可用。修复：补 `permissionDefaults` 对 `novachat.use` 默认授予（true），或按 velocity 路径让权限闸口放行、admin 子命令独立检查；L1 修复后 `/nc` 子命令对离线模式玩家可用。
- HOCON 配置修复：`config/sponge/global.conf` 注入点对齐 `plugins/novachat/config.yml`，确保插件读到 backend 连接参数。

### 1.5.7 构建工具问题（pre-existing，非 E2E 引入）

`com.github.johnrengelman.shadow` 8.1.1 与 Gradle 9.x 不兼容——`shadowJar` 在 pnx/folia/sponge 上失败，报 `No such property: mode for StubbedFileCopyDetails`。这 3 个模块的 E2E fat jar 是经手动 `jar` 合并或模块专属 init-script 绕过 shadow 产出的。bukkit/velocity/bungee/nukkit 不用 shadow，不受影响。后续：为这 3 个模块 pin Gradle 8.x 或升级 shadow 插件。

### 1.5.8 已知后续

1. **shadow 插件 Gradle 9 修复**：pnx/folia/sponge 的官方 fat-jar 构建路径 broken（§1.5.7），需 pin Gradle 8.x 或换 shadow 插件版本。
2. **`/nc who` 降级**：全部已测平台 `/nc who` 返回 "频道成员查询暂不可用（需后端支持）"——pre-existing 后端缺口，`WhoCommand` 需后端 who-query 支持。
3. **后端 `ServerNetworkHandler` 缺收包日志**：`ChannelActionPacket`/`ChatMessagePacket` 收包 handler 未打 INFO/DEBUG；补一个 `log.debug` 可为 3-way 证据（机器人 results.json + 服务端日志 + 后端日志）提供第 3 个独立旁证锚点。
4. ~~**sponge L1 完成**~~：已于 2026-08-08 达成，见 §1.5.6。
5. **全流程编排自动化**：把整个 run 收敛为单个可 CI 的 `run-e2e.ps1` orchestrator（当前各平台为独立 `start-<platform>-*.ps1` 脚本）。

### 1.5.9 跨平台 `novachat.use` 权限默认值审计（修复 agent 产出）

| 平台 | `/nc` 权限闸口 | 默认行为 | 结论 |
|---|---|---|---|
| velocity | `hasPermission`（现已 `return true`） | 修复后全允许 | ✅ 已修（Finding A） |
| bungee | 闸 `novachat.use` | BungeeCord 默认授予 ALL | 被默认授权掩盖，非 bug |
| bukkit / folia | 不用 `novachat.use`；用粒度 per-subcommand 权限（`novachat.join`/`leave`/`help`），plugin.yml 均 `default: true` | 正确 | 设计正确 |
| nukkit / pnx | `setPermission("novachat.use")`（`NovaChatCommand.java:31`），plugin.yml 无 `default: true` | 但描述符预注册的 `PluginCommand` `permission=null` → `testPermission()` 返回 true → executor 总跑；`setPermission` 是死代码 | 非真实 bug |
| sponge | `.permission("novachat.use")`（`NovaChatCommand.java:60/114/128/195`），无 `permissionDefaults` | 原潜在 default-deny | ✅ 已修（Finding C，§1.5.6） |

### 1.5.10 功能覆盖现状（2026-08-08）

L1 E2E 当前只覆盖 `/nc` 命令集与聊天往返的**冒烟子集**，并非全功能验证。为诚实标注，下方列出已测与未测。

**已测（L1 冒烟）**：`/nc help`（真实帮助文案断言）、`/nc join`、`/nc leave`、`/nc toggle`（REPLACE 模式）、`/nc list`、`/nc who`（降级回执，见 §1.5.8 #2）、REPLACE 聊天往返（机器人发 `hello from X bot` → 插件拦截 → backend 路由 → 插件收包 → 机器人渲染含真实内容的格式化消息，3-way 日志对齐）。

**尚未被 L1 E2E 覆盖的功能**（均已有 unit/property 或嵌入式 integration 覆盖，但未在真实服务器进程上驱动）：
- HYBRID 模式聊天路由（原版聊天 + 频道消息并存语义）
- `/nc create`、`/nc invite`、`/nc accept`（私有频道 + 密码 + 邀请码闭环）
- `/nc mute`、`/nc unmute`、`/nc kick`（管理动作 + 目标通知，见 UX §5）
- `/nc announce`、`/nc title`（公告 / Title 推送）
- `@mention` 高亮 + sound + title（UX §4 接线，真实客户端渲染未断言）
- 权限拒绝路径（`novachat.admin` 子命令对普通玩家拒绝；`novachat.create` 等粒度权限）
- `/nc reload`、`/nc debug`（运维命令）
- `WhoCommand` 后端 who-query（pre-existing 后端缺口，见 §1.5.8 #2）

**联合测试单独验收**：`test/joint/run-joint.ps1` 已接入 `e2e-joint.yml`。relay 用例分别使用 Velocity 或 Bungee + Purpur + Nukkit，lite 用例使用 Velocity + Purpur。门禁要求接收端双向投递、频道隔离的正反对照、Java bot 重连和实际成员列表；诊断行不能代替必需断言。该工作流与单平台 L1 分别统计。未执行的私聊隐私、后端重启、TLS／真实数据库和原生 PNX 场景仍未验收。

## 2. 总览：MVP 与分阶段扩展

### 2.1 阶段总表

| 阶段 | 内容 | 平台 | 机器人客户端 | 出口 |
|---|---|---|---|---|
| **P0（MVP）** | backend（独立进程）+ Paper 1.21.x + 协议机器人 | NovaLink core、Paper | 内嵌协议客户端（`novachat-common` codec） | 本地可跑通核心链路：认证→频道→路由→REST/WS |
| **P1** | 加入 Folia、Velocity、BungeeCord；`/nc` 命令断言 | +Folia/Velocity/Bungee | 内嵌协议客户端 +（可选）BotClient 插件 | 覆盖代理拓扑与全部 Java 端 |
| **P2** | Nukkit、PNX、Sponge；WebSocket 面板会话；可选 MC 协议机器人 | +Nukkit/PNX/Sponge | 内嵌 + BotClient +（可选）MC 协议机器人 | 全平台矩阵；基岩端到 NovaProtocol 连通 |
| **P3** | 全矩阵 CI 固化、缓存优化、报告产出 | 全部 | 同 P2 | 每个 PR 自动跑 |

**本阶段只交付 P0 的可执行设计**（§3–§4 + §6–§8），P1/P2 给出与 P0 相同的骨架与差异点。

### 2.2 拓扑图

```
                          ┌────────────────────────────┐
                          │   NovaLink backend (java)   │
                          │  tcp :P1  ws/rest :P2       │
                          └───▲──────────────▲─────────┘
                    NovaProtocol (TCP)        │ REST/JWT
        ┌───────────────┬─────┴─────┬─────────┴───────┐
   ┌────▼─────┐   ┌─────▼─────┐ ┌───▼─────┐      ┌────▼─────┐
   │ Paper     │   │ Velocity  │ │ Nukkit  │ ...  │ 机器人   │
   │ (plugins) │   │ /Bungee   │ │ /PNX    │      │ (内嵌/   │
   └───────────┘   └───────────┘ └─────────┘      │  BotClient)│
                                                  └──────────┘
   （真实玩家侧由机器人客户端模拟，连 backend 的 TCP 端口）
```

---

## 3. 目录布局与产物

### 3.1 仓库内目录（建议）

```
e2e/                                  # 新增，不进 gradle 主构建
  README.md                           # 快速上手 + 安全检查表
  bin/
    fetch-server.ps1                  # 下载 + SHA-256 校验 + 解包
    run-backend.ps1                   # 启动 backend（动态端口）
    run-minecraft.ps1                 # 启动 MC 服务端（动态端口、EULA 门）
    wait-health.ps1                   # 健康探针轮询
    stop-process-tree.ps1             # 进程树回收（含 job 对象）
    gen-config.ps1                    # 模板 → 实例化配置（端口/密钥注入）
  conf/
    novalink.template.yml             # backend 配置模板（占位符）
    paper/   server.template.properties, plugins/novachat-config.template.yml
    folia/   (同 paper 结构)
    velocity/ velocity.template.toml, plugins/novachat-config.template.yml
    bungee/  config.template.yml, plugins/novachat-config.template.yml
    nukkit/  server.template.properties, plugins/novachat-config.template.yml
    pnx/     (同 nukkit 结构)
    sponge/  spongix.conf 注入说明 + plugins/novachat-config.template.yml
  versions.lock.ps1                  # 版本固定清单（§4.1）+ SHA-256 表
  bot/                                # 机器人客户端（§7）
    bot-core/                         # 内嵌协议客户端（复用 novachat-common codec）
    bot-plugin/                       # BotClient 插件（可选，P1+）
  tests/
    p0_backend_paper.ps1              # MVP 脚本
    p1_proxy_matrix.ps1
    p2_bedrock_matrix.ps1
    lib/                              # 断言/日志/回收公共库
      assert.ps1
      log-grep.ps1
      port-picker.ps1
      process-tree.ps1
  artifacts/                          # 运行时产物（gitignore）
    dist/                             # 下载的服务端 JAR/zip
    runs/<run-id>/                    # 每次执行的日志、配置、report
  report/                             # 生成的汇总报告（gitignore）
```

### 3.2 产物来源

| 产物 | 来源 | 实际产物名（见 `.e2e/`） |
|---|---|---|
| `novalink-core.jar` | 本仓库 `novalink-core` 构建（`Main-Class: com.nova.link.NovaLinkMain`）；**无 shadow，不能 `java -jar`**，需 `java -cp`（见 §5.3 / §1.5.5） | 非 fat jar，classpath 经 init-script 导出 |
| `novachat-paper.jar` | `novachat-bukkit` 构建产物（Purpur/Paper 兼容 Bukkit 插件；`api-version: "1.21"`） | `novachat-bukkit-1.0.0-SNAPSHOT-fat.jar` |
| `novachat-folia.jar` | `novachat-folia` 构建（`folia-supported: true`） | `NovaChat-Folia.jar`（`.e2e/folia/plugins/`） |
| `novachat-velocity.jar` | `novachat-velocity` 构建（velocity 4.1.0，JDK25） | `novachat-velocity-1.0.0-SNAPSHOT-fat.jar` |
| `novachat-bungee.jar` | `novachat-bungee` 构建 | `NovaChat-Bungee.jar`（`.e2e/bungee/plugins/`） |
| `novachat-nukkit.jar` | `novachat-nukkit` 构建 | `NovaChat-Nukkit-E2E.jar`（`.e2e/nukkit/plugins/`） |
| `novachat-pnx.jar` | `novachat-pnx` 构建 | `NovaChat-PNX-1.0.0-SNAPSHOT.jar`（`.e2e/pnx/plugins/`） |
| `novachat-sponge.jar` | `novachat-sponge` 构建（shadow fat jar `NovaChat-Sponge`） | `NovaChat-Sponge.jar`（`.e2e/sponge/plugins/`）；sponge L1 已达成，见 §1.5.6 |

> 实际 bukkit 平台服务端用 **Purpur 1.21.8**（非 Paper），因 PaperMC API v2 sunset / v3 在本机 IP 经 Cloudflare 403（§1.5.2 c）。MC 服务端本体不入库，由平台专属 `start-*.ps1` 下载到 `.e2e/artifacts/dist/`（gitignore）。

### 3.3 平台-版本-流程矩阵

| 平台 | 服务端产物 | 启动方式 | 插件目录 | 配置注入点 | 就绪标志 |
|---|---|---|---|---|---|
| ~~Paper 1.21.x~~ Purpur 1.21.8（实际） | purpur jar（`purpur-1.21.8-2497.jar`，见 §1.5.2 c） | `java -jar purpur.jar nogui` | `plugins/` | `server.properties`、`plugins/NovaChat/config.yml` | 日志 `Done (…)` + 端口可连 |
| Folia 1.21.11 | folia jar（`folia-1.21.11-14.jar`, build 14, JDK21） | 同上 | `plugins/` | 同 Purpur | 日志 `Done (…)` |
| Velocity **4.1.0**（非 3.x） | velocity jar（`velocity-4.1.0-SNAPSHOT-13.jar`, JDK25） | `java -jar velocity.jar` | `plugins/` | `velocity.toml`、`plugins/novachat/config.yml` | 日志 `Done` / `Listening on …` |
| BungeeCord（waterfall） | waterfall jar（`waterfall-1.21-615.jar`） | `java -jar waterfall.jar` | `plugins/` | `config.yml`（listen host、forced hosts 可留默认） | 日志 `Listening on …` |
| Nukkit | Cloudburst Nukkit jar（`nukkit-cloudburst-1.0-20260616.184029-1239.jar`） | `java -jar nukkit.jar` | `plugins/` | `server.properties`（`port=`、`motd=`）、`plugins/novachat/config.yml` | 日志 `Done` / `Default game mode` 行 + 端口 |
| PNX | pnx jar（**实际用 Cloudburst Nukkit 1239 作 fallback**，PNX 插件用 Nukkit API，见 §1.5.1） | `java -jar nukkit.jar` | `plugins/` | 同 Nukkit | 同 Nukkit |
| Sponge | spongevanilla jar（`spongevanilla-1.16.5-8.2.0.jar`，SpongeAPI 8.2.0 / MC 1.16.5, JDK17） | `java -jar spongevanilla.jar` | `mods/plugins/`（`config/sponge/global.conf`） | `global.conf` 或 `--config` 注入；`plugins/novachat/config.yml` | 日志 `Sponge server started` / `Done`（sponge L1 已达成，见 §1.5.6） |

> 就绪标志统一以「日志特征串 + TCP 端口可连接」双条件为准（§6.2），不依赖固定等待时间。

---

## 4. 版本固定与 SHA-256 校验

### 4.1 固定原则

当前实际锁为 `test/versions.lock.ps1`。BDS 已通过 Minecraft links API（`https://net-secondary.web.minecraft-services.net/api/v1.0/download/links`）解析 `result.links[]` 的 `serverBedrockWindows`，下载路径为 `bedrockdedicatedserver/bin-win/`。`bds` 为 Endstone 独立 pin 1.26.52.3；`bds-levilamina` 为 LeviLamina 独立 pin 1.26.51.1。后者禁用 Auto，不能用 Mojang 当前最新版替换。

LeviLamina 安装五件套为 `bds-levilamina`、`levilamina`（26.51.7）、`levilamina-preloader`（1.16.5）、`levilamina-peeditor`（3.9.0）和 `levilamina-runtime`（26.51.1-server.11），均有固定 SHA-256。loader 放 `plugins/LeviLamina/`；`PreLoader.dll` 和 `bedrock_runtime_data` 放 BDS 根目录，执行 `PeEditor.exe -mb` 后启动 `bedrock_server_mod.exe`。SDK 须与 loader 同版；独立测试目标使用 `--levilamina_sdk=n`，不能写成工具链 SDK 路径参数 `--sdk=n`。安装细节见 [test/README.md](../test/README.md#levilamina-installation-and-sdk)。

`bds -Auto` 仅在 Endstone 元数据指向同一下载 URL 时采用其 SHA-256，否则只记录计算值。links API 不可用或缺 Windows 链接时，fetch 可 exit 0 且不产出文件；runner 无可用 BDS 时写明原因并 exit 3。实际下载或哈希校验失败为平台 exit 2，均不证明 Bedrock 实跑通过。以下锁示例保留为早期设计，当前版本和哈希以实际锁文件为准。

- **一切外部下载物进入 `versions.lock.ps1`**：`name / url / sha256 / eula-required / mcv / engine`。
- URL 指向固定版本构建产物（PaperMC API 的 `https://api.papermc.io/v2/projects/paper/versions/<MC>/builds/<build>/downloads/paper-<MC>-<build>.jar` 或项目官方下载直链），**不用 `latest`**；升级 = 改 lock 文件 + 更新 SHA，不允许“自动跟随最新”。
- 锁文件内容示例（字段含义即文档）：

```powershell
# versions.lock.ps1（示意；SHA 为占位，落地时由 fetch 校验生成）
# 版本基线：2026-08-08 检索的最新发布（MC Java 26.2、Bedrock 26.42）。
# 下载前务必重新核对最新构建号 —— PaperMC 站点每日出新 build，下方 build 号可能已被超越。
$LockedServers = @(
    @{ Name='paper';      MC='26.2'; Engine='purpur';
       Url='https://api.purpurmc.org/v2/purpur/26.2/builds/2619/downloads/purpur-26.2-2619.jar';
       Sha256='<下载后实测的64位hex>'; Eula=$true; Jdk=21 },
    @{ Name='folia';      MC='26.1.2'; Engine='folia';
       Url='https://api.papermc.io/v2/projects/folia/versions/26.1.2/builds/8/downloads/folia-26.1.2-8.jar';
       Sha256='<hex>'; Eula=$true; Jdk=21 },
    @{ Name='velocity';   MC='4.1.0';  Engine='velocity';
       Url='https://api.papermc.io/v2/projects/velocity/versions/4.1.0-SNAPSHOT/builds/16/downloads/velocity-4.1.0-SNAPSHOT-16.jar';
       Sha256='<hex>'; Eula=$false; Jdk=25 },
    @{ Name='bungeecord'; MC='1.21'; Engine='waterfall';
       Url='https://api.papermc.io/v2/projects/waterfall/versions/1.21/builds/615/downloads/waterfall-1.21-615.jar';
       Sha256='<hex>'; Eula=$false; Jdk=21 },
    @{ Name='nukkit';     MC='26.40-bedrock'; Engine='nukkit';
       Url='https://ci.opencollab.dev/job/Nukkit/job/master/lastSuccessfulBuild/artifact/target/nukkit-cloudburst-1.0-SNAPSHOT.jar';
       Sha256='<hex>'; Eula=$false; Jdk=21 },
    @{ Name='pnx';        MC='26.40-bedrock'; Engine='pnx';
       Url='<PNX jar 在本环境不可下载，实际用 Nukkit 作 fallback 服务端，见 §1.5.1>';
       Sha256='<hex>'; Eula=$false; Jdk=21 },
    @{ Name='sponge';     MC='1.21.10-26.2'; Engine='spongevanilla';
       Url='https://repo.spongepowered.org/content/groups/maven/org/spongepowered/spongevanilla/<ver>/spongevanilla-<ver>.jar';
       Sha256='<hex>'; Eula=$false; Jdk=21 }
)
```

> 上表为 2026-08-08 检索的最新版本基线（Purpur 26.2 build 2619、Folia 26.1.2 build 8、Velocity 4.1.0-SNAPSHOT build 16、Waterfall 1.21 build 615、Nukkit/PNX 对应 Bedrock 26.40、Sponge API 17.x 稳定 / API 20.x 实验支持 MC 26.2）。**下载前必须重新核对最新构建号** —— PaperMC/Purpur 站点每日出新 build，上方 build 号可能已被超越；按 §4.2 流程在首次下载后用 `Get-FileHash -Algorithm SHA256` 实测回填，**禁止留空运行**。velocity 模块需 **JDK25**（`novachat-velocity/build.gradle` 固定 `VERSION_25`，Lombok 1.18.x 在 JDK25 下崩溃故 velocity 源码不用 Lombok）；其余平台 JDK21。Waterfall 已于 2026-06 EOL（仅存档构建），新部署建议迁移到 Velocity。

### 4.2 校验流程（`fetch-server.ps1`）

1. `Test-Path artifacts/dist/<name>-<ver>.jar` 且已有 `*.sha256` 记录 → 直接复用（CI 命中缓存，§8.2）。
2. 否则下载到 `artifacts/dist/.download.tmp`，`Get-FileHash -Algorithm SHA256` 与锁文件比对；**不一致 → 删除临时文件并抛错**（绝不落盘/运行）。
3. 通过后原子重命名为最终名，并把哈希写入 `artifacts/dist/<name>-<ver>.sha256`（与 `versions.lock.ps1` 交叉核对）。
4. 每次运行前对已缓存文件**再校验一次哈希**（防缓存污染/截断），不过则重新下载。

### 4.3 EULA 人工同意边界（不静默接受）

- Paper/Folia 首次启动要求 `eula.txt` 含 `eula=true`。**本套件不生成、不注入该文件**——那是用户对 Mojang EULA 的声明，自动化**不得代为同意**。
- 设计：`run-minecraft.ps1` 启动前检查：
  - `artifacts/eula/paper-eula.txt` 存在且内容为 `eula=true` → 复制进运行目录；
  - 不存在 → **打印醒目提示**（下载 URL、EULA 地址 https://aka.ms/MinecraftEULA、验证命令 `Get-FileHash`），并**中止该平台场景**（exit 3，标记 `EULA_NOT_ACCEPTED`）。
- 仓库内**不提交任何 `eula.txt`**；CI 里该人工产物放在 CI 机密/受保护变量或 artifacts 持久区（§8.4 安全边界），CI 任务无该文件时**跳过 Paper/Folia 场景**并在报告中标黄，而不是伪造同意。
- BungeeCord/Velocity/Nukkit/PNX/Sponge 无 EULA 门，不受影响。

---

## 5. 动态端口与配置注入

### 5.1 端口策略

- 所有端口由 `port-picker.ps1` 在**测试进程内**分配：先 `Get-Random -Minimum 20000 -Maximum 60000`，再 `Test-NetConnection -Port`（或 `System.Net.Sockets.TcpListener` 试绑定）确认空闲；记录到 `artifacts/runs/<run-id>/ports.json` 供所有子进程与断言读取。
- 固定约定（相对运行目录）：
  - `$env:NOVALINK_TCP_PORT`（backend TCP）
  - `$env:NOVALINK_WS_PORT`（backend WebSocket/REST，同一端口服务 `/api/*` 与 `/ws`）
  - `$env:MC_SERVER_PORT`（Minecraft 服务端，25665 起跳，避开 25565 常见占用）
  - Velocity/Bungee 场景：`$env:MC_SERVER_PORT` 即代理监听口，下游 Paper 用 266xx 段。
- 后端 `bind-address` 一律 `127.0.0.1`（本机/CI 单机，不开外网；见 §8.4）。

### 5.2 配置注入（模板 → 实例化）

- 所有模板放 `conf/`，含占位符（`{{TCP_PORT}}`、`{{WS_PORT}}`、`{{SECRET_KEY}}`、`{{CLIENT_PASSWORD_HASH}}` 等）；`gen-config.ps1` 按 `ports.json` 渲染到 `runs/<run-id>/<platform>/`。
- **backend 模板要点**（对照 `examples/novalink.yml` 与 `NovaLinkMain` 实测行为）：
  - `database.type: memory`（不依赖 MySQL/Redis）；
  - `server.port` / `server.websocket-port` ← 动态端口；
  - `server.secret-key` ← 每次运行随机生成（`[System.Security.Cryptography.RandomNumberGenerator]`），**不再使用默认值**（`NovaLinkMain` 会对默认值打 warning）；
  - `clients[].password` 可直接写 64 位 hex（`NovaLinkMain.registerClients` 识别 64-hex 视为已是 SHA-256，不二次哈希；也可写明文让它自己哈希）；
  - `security.allowed-ips` 只留 `127.0.0.1`；
  - `filter.enabled: false`（避免敏感词干扰消息断言；敏感词过滤已有 unit 覆盖）；
  - `announcements.scheduled` 全部 `enabled: false`（防定时公告污染日志/消息流断言）。
- **插件配置**：`backend.host=127.0.0.1`、`backend.port=<TCP_PORT>`、`backend.username/password` 与 backend `clients[]` 对齐（注意插件侧 `replace_vanilla` 与格式模板按平台模板覆盖）。
- **Paper/Folia 的 `server.properties`**：`online-mode=false`（本地回环，无外网认证）、`server-port=<MC_SERVER_PORT>`、`spawn-protection=0`、`view-distance` 调小、`level-type=flat`（可选，加快生成）、`motd` 设为唯一标记串便于日志断言。
- **Nukkit/PNX 的 `server.properties`**：`port=<MC_SERVER_PORT>`、`motd` 唯一标记、`xbox-auth=false`（本地回环）。

### 5.3 后端启动参数

> **设计与现实分叉（见 §1.5.5）**：本节原写 `java -jar ...`，但 `novalink-core` 无 shadow 插件，`java -jar` 会 NoClassDefFoundError。实际启动用 `java -cp` 经 init-script 导出 `runtimeClasspath`。下方同时保留原设计（划掉）与实际命令。

~~原设计（不可行，novalink-core 无 shadow）：~~

```powershell
# ❌ 不可行 — novalink-core/build.gradle 无 shadow 插件，jar 不含依赖
java -Xmx512m -jar artifacts/dist/novalink-core.jar <runs>/<run-id>/novalink/novalink.yml
```

实际落地（见 `.e2e/bin/start-backend.ps1`）：

```powershell
# init-script 先把 runtimeClasspath 导出到 classpath 文件
$cp = (Get-Content <runs>/novalink-core.classpath.txt -Raw).Trim()
java -Xmx512m -cp $cp com.nova.link.NovaLinkMain <runs>/novalink/novalink.yml
```

（`NovaLinkMain` 首个参数即配置文件路径；若缺省会找当前目录 `novalink.yml`，务必显式传参。配置实例：`database.type: memory`、随机 `secret-key`、客户端 `E2E_Client`/`e2e-secret-password`、`debug: true`，见 `.e2e/artifacts/runs/novalink.yml`。）

### 5.4 代理场景差异（P1）

- **Velocity**：`velocity.toml` 设 `bind = "127.0.0.1:<MC_SERVER_PORT>"`、`online-mode = false`；下游 Paper 的 `server.properties` 里 `server-port` 用 266xx、`online-mode=false`、`server-ip=127.0.0.1`；`velocity.toml` 的 `secret` 与 Paper `spigot.yml` 的 `velocity.secret` 对齐（或用 `paper-global.yml` 的 `proxies.velocity.enabled=true`）。插件配置指向 backend，与代理无关。
- **BungeeCord**：`config.yml` 设 `listeners[0].host = 127.0.0.1:<MC_SERVER_PORT>`；`online_mode: false`；`ip_forward: false`（P1 冒烟不强制 IP forwarding，减少 Paper 端配置面）。
- 上述为历史拓扑设计。当前 `test/` 共享 runner 缺少下游 Purpur，bungee/velocity 在断言前即 `SKIPPED-BY-DESIGN`，不能记为 Full L1。

---

## 6. 健康探针与就绪门

### 6.1 后端探针

- **首选：REST**。backend 的 WS 端口同时服务 HTTP `/api/*`。但 `/api/*` 除 `/api/auth/*` 外都要求 JWT（`RestApiHandler.channelRead0` 实测：非 auth 路径无有效 `Authorization` 直接 401）。因此：
  - **就绪探针用 TCP 连接成功**（`TcpClient.ConnectAsync(127.0.0.1, WS_PORT)` 或 `TCP_PORT`，任一即可），不依赖 HTTP 状态码；
  - **语义断言用 JWT 登录**：`POST /api/auth/login`（`HttpAuthHandler` 提供）拿 token，再 `GET /api/status` 断言 200 与 JSON 字段（`RestApiHandler` 有 `/api/status`）。
- 日志探针：`log-grep.ps1` 等待 `NovaLink Backend Server started successfully on tcp …`（`NovaLinkMain` 实际日志行）。
- 双条件都满足才放行后续步骤；超时（默认 60s）→ 转储日志尾部（§6.4）并失败。

### 6.2 Minecraft 服务端探针

- 日志特征串 + TCP 端口可连，双条件（§3.3 表格的“就绪标志”列）。
- 等待期间持续 tail 日志到 `runs/<run-id>/<platform>/server.live.log`；每 2s 轮询一次。
- 超时默认：Paper/Folia 180s（首次启动要生成世界），Velocity/Bungee/Nukkit/PNX/Sponge 90s。

### 6.3 机器人客户端就绪

- 机器人连 backend TCP 后先握手认证（`HandshakePacket` → 期待 `HandshakeResponsePacket` success）；认证成功即视为就绪（`NovaLinkMain` 握手 handler 实测：版本不匹配回 `NC-420`、认证失败回 `NC-401`）。

### 6.4 失败转储

任何就绪/断言失败 → `collect-failure.ps1` 汇总：所有子进程日志尾部 200 行、`ports.json`、进程树快照、断言现场（收到的包/行），打包为 `artifacts/runs/<run-id>/failure.zip`，日志路径写入退出摘要。CI 里作为 artifact 上传。

---

## 7. 机器人客户端选型对比

### 7.1 候选

| 方案 | 实现成本 | 覆盖面 | 稳定性 | 与仓库集成度 | 判定 |
|---|---|---|---|---|---|
| **A. 内嵌协议客户端（复用 `novachat-common` codec）** | 低 | backend 全协议面（握手/认证/频道/路由/keepalive/管理动作/ConfigSync），**不覆盖**插件事件适配 | 高（与生产代码同源） | 高：直接以 `novachat-common` 为依赖 | ✅ **MVP 主选** |
| **B. BotClient 插件（跑在 MC 服务端进程内）** | 中 | 插件事件适配层（玩家进服/聊天/世界切换 → NovaProtocol） | 高 | 中：随各平台插件一起装载 | ✅ **P1 主选（补 A 的盲区）** |
| **C. 真实 MC 协议机器人（protocol lib：Mineflayer (JS) / PrismarineJS / mcprotocollib (Java)）** | 高 | 玩家级登录握手、游戏内聊天、命令执行（`/nc join` 等） | 中（依赖 MC 协议版本演进） | 低 | ⚠️ P2 可选，用于验证命令 UX 与玩家事件映射 |
| **D. 手动/半自动** | - | - | - | - | ❌ 不在自动化范围内 |

### 7.2 推荐组合

- **MVP（P0）**：方案 A（`e2e/bot/bot-core/`，Java，依赖 `novachat-common`，用 `PacketDecoder`/`PacketEncoder`/`Varint21FrameDecoder`/`Varint21LengthFieldPrepender`——与 `IntegrationTestHelper`/`MultiClientSimulator` 相同的编解码栈，但作为**独立进程**运行，走真实 socket 而非嵌入式）。它驱动 backend 的完整协议面，是全部 P0 断言的唯一数据源。
- **P1**：方案 B（BotClient 插件）在 Paper/Folia 进程内注册 `PlayerJoinEvent`/`AsyncPlayerChatEvent` 监听，把自动生成的 UUID 玩家加入频道并发消息——专门覆盖「插件事件 → 协议包 → backend 路由 → 其他端收到」这条嵌入式测试没走过的链路。B 与 A 可并行使用：A 做 backend 协议断言，B 做插件适配断言。
- **P2（可选，不阻塞）**：方案 C（mcprotocollib，Java，可复用 repo 的 Java 工具链）跑真实玩家登录 + 游戏内 `/nc` 命令，验证命令 UX 与提示文案（`/nc join`/`/nc leave` 的确认回执，参考 `UX-AUDIT-*.md` 文案基线）。此方案**默认关闭**，只在本机显式 `-IncludeMinecraftBot` 时启用——因为在线模式关闭下的自动登录与协议版本耦合，CI 稳定性差（§9）。

### 7.3 机器人行为规范（A/B 通用）

- 每个机器人会话有唯一 run-id 前缀（`bot-<runid>-<n>`），日志里所有消息/频道/玩家 ID 带该前缀，避免与断言标记冲突。
- 握手密码：`SHA-256(明文)` 小写 hex（backend 实测按此比对）；机器人端用与 `novachat-client-core` 相同的 `PasswordHasher` 约定。
- 所有机器人等待响应均有超时（默认 10s），超时按失败上报并 dump 现场。
- 机器人关闭：先发 `KeepAlivePacket` 优雅退出（可选），再关 socket；进程回收由 §7.4 兜底。

### 7.4 进程树回收（Windows + CI 双保险）

- **Job Object 方案（Windows 本机）**：`process-tree.ps1` 用 `CreateJobObject`/`AssignProcessToJobObject`/`TerminateJobObject`（P/Invoke）把 backend/服务端/机器人进程加入 job，**脚本退出（含异常）时整个 job 被终止**，杜绝孤儿 JVM。
- **进程树遍历（兜底，跨平台）**：记录每个启动进程的 PID + 父 PID（`Get-CimInstance Win32_Process`），回收时按树遍历 `Stop-Process`；Java 启动器与子 JVM 都入表。
- **超时强制回收**：`run-id` 全局最长执行时间（P0 单场景 15min，全矩阵 40min），到点先发 `CTRL_C`/`SIGTERM`（验证 `NovaLinkMain` 的 shutdown hook 走 `safeShutdown`），再 15s 无果即 `TerminateJobObject`。
- **端口遗嘱检查**：回收后断言 `ports.json` 里所有端口均无监听；有残留 → 报告失败并列出 PID（防 CI 并发撞端口）。
- **启动保护**：每个端口在分配前试绑定，绑定失败立即换端口重试（最多 5 次）。

---

## 8. 日志/协议断言、CI 编排、安全边界

### 8.1 断言层次

| 层次 | 手段 | 断言内容 | 示例 |
|---|---|---|---|
| L1 日志特征 | `log-grep.ps1` 轮询/`Select-String` | 就绪、崩溃、特定事件 | backend `Client authenticated: …`；Paper `Done (…)` |
| L2 协议收包 | 机器人 A 的收包回调 | 期待 `ChatMessagePacket`/`ChannelActionResponsePacket` 精确送达 | 频道 `global` 广播到两个不同 client；SERVER 频道不外泄到其他 client（对照 `MessageRouterBoundaryPipelineTest` 的嵌入式断言，在真实进程上复验） |
| L3 语义 API | REST + JWT | 状态/成员/消息 | `GET /api/status` 200；`GET /api/channels` 含 `global` |
| L4 平台状态 | BotClient B 查询插件状态（P1） | 玩家 state 与 backend 一致 | join 后插件侧 joined channels 更新 |
| L5 文案（P2，可选） | 真实客户端控制台抓取 | `/nc` 命令回显文案 | 对照 `UX-AUDIT` 基线关键词 |

### 8.2 CI（GitHub Actions 设计）与缓存、并行

- **runner**：`windows-2025`（与本机 Windows Server 2025 对齐）+ `ubuntu-24.04` 各一份（脚本用 PowerShell Core 保证跨平台；`process-tree.ps1` 在 Linux 用 `kill -- -pgid` 兜底）。
- **JDK**：`actions/setup-java@v4`，`temurin` 21（记忆库：`novachat-build-jdk-setup` 记录 JDK21 才满足 folia 全量构建）。
- **缓存**：
  - 服务端下载：`actions/cache@v4` key `e2e-dist-<versions.lock.ps1 哈希>`，path `e2e/artifacts/dist`；锁文件变更 → 缓存失效。
  - Gradle：`~/.gradle/caches` + `~/.gradle/wrapper/dists`（现有构建已开 `org.gradle.caching=true`）。
- **并行**：job 按平台矩阵分片——`e2e-backend-paper`（P0，必跑）、`e2e-folia`、`e2e-velocity-bungee`、`e2e-nukkit-pnx`、`e2e-sponge`（P1/P2 标 `continue-on-error: true` 或手动触发，避免外部下载抖动拖红 PR）；每个 job 内多场景**串行**执行（共享 `ports.json` 与缓存目录），job 之间天然并行。
- **并发安全**：每个 job 独立 `artifacts/runs/<job>-<sha>-<n>`，端口随机段按 job 名做盐（`port-picker.ps1` 用 job 名派生随机种子），防 runner 内撞端口。
- **超时**：workflow 级 `timeout-minutes: 45`；每 job 再设更严上限（§7.4）。
- **测试报告**：JUnit XML 风格汇总（`report/e2e-results.xml`）或 Markdown 表格，随 PR 评论/artifact 上传；`EULA_NOT_ACCEPTED` 场景标 **SKIPPED-YELLOW** 而非失败。

### 8.3 安全边界

- 所有服务绑定 `127.0.0.1`，端口随机；CI 无公网暴露面。
- 配置注入的 `secret-key`、客户端密码由 `gen-config.ps1` 随机生成，**写入 gitignore 的运行目录**，不入库、不进日志（日志里只出现 hash）。
- 下载物 SHA-256 强制校验（§4.2）；**禁止** `Invoke-WebRequest` 后未校验直接运行。
- EULA 不静默接受（§4.3）；仓库内无 eula.txt；CI 中 EULA 人工产物放 secrets/持久区，缺失即跳过。
- 服务器日志可能含玩家名/IP → 断言与失败转储仅提取特征串，全文日志只留在 artifacts（PR artifact 默认 7 天过期）。
- 本机运行前 `bin/check-env.ps1` 检查：JDK21、PowerShell 7+、`Get-Command` 可用、防火墙规则（若有）放行 127.0.0.1 段——并打印「本机执行前需确认已阅读并同意各服务端 EULA」提示。

---

## 9. 诚实标注：无法/不建议自动化的部分

| # | 项目 | 原因 | 状态 |
|---|---|---|---|
| 1 | 真实 Minecraft 玩家客户端登录（微软/Mojang 账号） | 账号认证受反滥用限制，无法无人值守 | **不可自动化**（仅 P2 可选用 offline-mode 机器人，非 CI） |
| 2 | Folia 多线程调度正确性断言 | 线程交错不可重复，断言不可靠 | **不建议自动化**（仅冒烟） |
| 3 | LeviLamina / PocketMine-MP / Endstone | 使用各自独立工具链和 runner | 已纳入当前矩阵；本轮未实跑，全矩阵验证待统一执行 |
| 4 | Fabric / NeoForge / Quilt / Forge | 需要 Gradle 9.5+（当前 wrapper 8.8，loader 子模块未纳入构建），且为 mod 加载器（需额外 bootstrap） | **不可执行**（构建前置未满足） |
| 5 | MySQL/Redis 持久化路径 | 已有 Testcontainers 覆盖；引入 DB 依赖会放大外部服务抖动 | 不在本套件（归现有测试） |
| 6 | 真实基岩客户端登录 Nukkit/PNX | 基岩登录协议 + 设备模拟复杂 | **不可自动化**（仅服务端进程 + NovaProtocol 连线） |
| 7 | BungeeCord / Velocity 代理拓扑 | 当前 runner 缺少下游 Purpur | `SKIPPED-BY-DESIGN`，非 Full L1 |
| 8 | 公网网络环境下的下载与防火墙行为 | 本套件全回环；真实部署网络策略超出自动化范围 | 不覆盖 |

---

## 10. 落地检查清单（实施阶段按此执行）

> 当前实施状态见 §1.5.1：10 个条目已接入编排，其中两个代理按设计跳过；PMMP 和 Folia 的严格聊天 gate 已完成，全矩阵实跑待统一验证。下方原设计清单保留作历史参照，不作为通过证明。

- [ ] `e2e/` 目录与 §3.1 结构落地，`artifacts/`、`report/` 入 `.gitignore`
- [ ] `versions.lock.ps1` 填实（URL + 实测 SHA-256），`fetch-server.ps1` 校验闭环
- [ ] 本机人工确认各服务端 EULA 后，将 `eula.txt` 放入 gitignore 的 `artifacts/eula/`
- [ ] `run-backend.ps1` + `gen-config.ps1` + `wait-health.ps1` 打通 P0：backend 起来，机器人 A 握手/认证/路由/keepalive 全绿
- [ ] `process-tree.ps1`（Job Object + 树遍历）验证：异常路径下无残留 JVM、端口全部释放
- [ ] P1：Folia、Velocity、Bungee 场景脚本；BotClient 插件事件链路断言
- [ ] P2：Nukkit、PNX、Sponge；WS 面板会话（JWT 登录 → `/api/status`）；可选 MC 协议机器人
- [ ] CI workflow：分片 job、缓存、并行、失败转储 artifact、EULA 缺失跳过逻辑
- [ ] 每次服务端版本升级走 lock 文件 + SHA 更新 + 一次本机全量回归
