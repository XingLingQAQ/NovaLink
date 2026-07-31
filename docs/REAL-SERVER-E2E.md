# NovaLink 真实服务器 E2E 自动化测试设计

> 设计基线：2026-08-01 当前工作树。本文档只给出**可执行的设计**（脚本结构、版本固定、断言、回收、CI 编排）；实际下载/启动/接受 EULA 等执行动作由后续实施阶段按本文档落地，**不在本文档产出时执行**。
>
> 范围：Windows 本机 + CI 的 NovaLink backend + Paper / Folia / Velocity / BungeeCord / Nukkit / PowerNukkitX（PNX）/ Sponge 可行性。
> **MultiPaper 明确冻结（见 `novachat-multipaper` 与记忆库 `multipaper-command-layer-archb-gap`），不纳入任何执行矩阵。**
> `novalink-go` 同为冻结/实验状态，仅保留一致性观测位，不纳入执行。

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
- 不验证 **LeviLamina（C++ BDS）/ PocketMine-MP / Endstone / Fabric / NeoForge / Quilt / Forge** —— 模组加载器类需要 Gradle 9.5+（当前 wrapper 8.8，`novachat-mod` loader 子模块在 `settings.gradle` 中被注释）；PHP/Python/C++ 端有独立工具链，归为 P2 且不做 CI 默认执行。
- 不验证 **Nukkit/PNX 的基岩客户端协议**（真实基岩客户端登录）；只验证 Nukkit/PNX 服务端进程 + 插件装载 + 到 backend 的 NovaProtocol 连线。
- 不验证 **MySQL/Redis 持久化**（Testcontainers 已有独立覆盖：`TestContainersConfig`、`MySQLProvider` 测试；本套件统一 `database.type=memory`）。

### 1.3 冻结平台红线

| 平台 | 状态 | 本套件 |
|---|---|---|
| MultiPaper | ❄️ 冻结（Arch B 命令层未接线） | **排除**，文档保留占位说明 |
| novalink-go | ❄️ 冻结/实验 | 不执行；仅 CI 里保留一个 skip-able 的一致性观测任务（§8.3） |

---

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

| 产物 | 来源 |
|---|---|
| `novalink-core.jar` | 本仓库 `novalink-core` 构建（`Main-Class: com.nova.link.NovaLinkMain`） |
| `novachat-paper.jar` | `novachat-bukkit` 构建产物（Paper 兼容 Bukkit 插件；`api-version: "1.21"`） |
| `novachat-folia.jar` | `novachat-folia` 构建（`folia-supported: true`） |
| `novachat-velocity.jar` / `novachat-bungee.jar` / `novachat-nukkit.jar` / `novachat-pnx.jar` / `novachat-sponge.jar` | 对应模块构建产物 |

MC 服务端本体不进仓库，由 `fetch-server.ps1` 从固定 URL 下载到 `artifacts/dist/`（gitignore），见 §4。

### 3.3 平台-版本-流程矩阵

| 平台 | 服务端产物 | 启动方式 | 插件目录 | 配置注入点 | 就绪标志 |
|---|---|---|---|---|---|
| Paper 1.21.x | paperclip jar | `java -jar paper.jar nogui` | `plugins/` | `server.properties`、`plugins/novachat/config.yml` | 日志 `Done (…)` + 端口可连 |
| Folia 1.21.x | folia jar（同 paperclip 风格） | 同上 | `plugins/` | 同 Paper | 日志 `Done (…)` |
| Velocity 3.x | velocity jar | `java -jar velocity.jar` | `plugins/` | `velocity.toml`、`plugins/novachat/config.yml` | 日志 `Done` / `Listening on …` |
| BungeeCord | bungeecord jar | `java -jar bungee.jar` | `plugins/` | `config.yml`（listen host、forced hosts 可留默认） | 日志 `Listening on …` |
| Nukkit | nukkit jar | `java -jar nukkit.jar` | `plugins/` | `server.properties`（`port=`、`motd=`）、`plugins/novachat/config.yml` | 日志 `Done` / `Default game mode` 行 + 端口 |
| PNX | pnx jar | `java -jar pnx.jar` | `plugins/` | 同 Nukkit | 同 Nukkit |
| Sponge | sponge jar + spongevanilla | `java -jar spongevanilla.jar` | `mods/plugins/`（`config/sponge/global.conf`） | `global.conf` 或 `--config` 注入；`plugins/novachat/config.yml` | 日志 `Sponge server started` / `Done` |

> 就绪标志统一以「日志特征串 + TCP 端口可连接」双条件为准（§6.2），不依赖固定等待时间。

---

## 4. 版本固定与 SHA-256 校验

### 4.1 固定原则

- **一切外部下载物进入 `versions.lock.ps1`**：`name / url / sha256 / eula-required / mcv / engine`。
- URL 指向固定版本构建产物（PaperMC API 的 `https://api.papermc.io/v2/projects/paper/versions/<MC>/builds/<build>/downloads/paper-<MC>-<build>.jar` 或项目官方下载直链），**不用 `latest`**；升级 = 改 lock 文件 + 更新 SHA，不允许“自动跟随最新”。
- 锁文件内容示例（字段含义即文档）：

```powershell
# versions.lock.ps1（示意；SHA 为占位，落地时由 fetch 校验生成）
$LockedServers = @(
    @{ Name='paper';      MC='1.21.4'; Engine='paperclip';
       Url='https://api.papermc.io/v2/projects/paper/versions/1.21.4/builds/290/downloads/paper-1.21.4-290.jar';
       Sha256='<下载后实测的64位hex>'; Eula=$true },
    @{ Name='folia';      MC='1.21.4'; Engine='folia';
       Url='https://api.papermc.io/v2/projects/folia/versions/1.21.4/builds/<b>/downloads/folia-1.21.4-<b>.jar';
       Sha256='<hex>'; Eula=$true },
    @{ Name='velocity';   MC='3.4.0';  Engine='velocity';
       Url='https://api.papermc.io/v2/projects/velocity/versions/3.4.0-SNAPSHOT/builds/<b>/downloads/velocity-3.4.0-SNAPSHOT-<b>.jar';
       Sha256='<hex>'; Eula=$false },
    @{ Name='bungeecord'; MC='latest'; Engine='bungee';
       Url='https://ci.md-5.net/job/BungeeCord/lastSuccessfulBuild/artifact/bootstrap/target/BungeeCord.jar';
       Sha256='<hex>'; Eula=$false },
    @{ Name='nukkit';     MC='1.20.x'; Engine='nukkit';
       Url='https://ci.opencollab.dev/job/Nukkit/job/master/lastSuccessfulBuild/artifact/target/nukkit-1.0-SNAPSHOT.jar';
       Sha256='<hex>'; Eula=$false },
    @{ Name='pnx';        MC='1.20.x'; Engine='pnx';
       Url='https://github.com/PowerNukkitX/PowerNukkitX/releases/download/<tag>/powernukkitx-<ver>-shaded.jar';
       Sha256='<hex>'; Eula=$false },
    @{ Name='sponge';     MC='1.21.x'; Engine='spongevanilla';
       Url='https://repo.spongepowered.org/content/groups/maven/org/spongepowered/spongevanilla/<ver>/spongevanilla-<ver>.jar';
       Sha256='<hex>'; Eula=$false }
)
```

> 上表 URL 为**示例定位**（BungeeCord 的 md-5 CI 直链、Nukkit 的 OpenCollab CI 直链均非稳定归档；落地时优先选带版本号的发布页）。`Sha256` 一律在首次下载后用 `Get-FileHash -Algorithm SHA256` 实测并回填，**禁止留空运行**。

### 4.2 校验流程（`fetch-server.ps1`）

1. `Test-Path artifacts/dist/<name>-<ver>.jar` 且已有 `*.sha256` 记录 → 直接复用（CI 命中缓存，§8.2）。
2. 否则下载到 `artifacts/dist/.download.tmp`，`Get-FileHash -Algorithm SHA256` 与锁文件比对；**不一致 → 删除临时文件并抛错**（绝不落盘/运行）。
3. 通过后原子重命名为最终名，并把哈希写入 `artifacts/dist/<name>-<ver>.sha256`（与 `versions.lock.ps1` 交叉核对）。
4. 每次运行前对已缓存文件**再校验一次哈希**（防缓存污染/截断），不过则重新下载。

### 4.3 EULA 人工同意边界（不静默接受）

- Paper/Folia 首次启动要求 `eula.txt` 含 `eula=true`。**本套件不生成、不注入该文件**——那是用户对 Mojang EULA 的声明，自动化**不得代为同意**。
- 设计：`run-minecraft.ps1` 启动前检查：
  - `artifacts/eula/paper-1.21.4-eula.txt` 存在且内容为 `eula=true` → 复制进运行目录；
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

```powershell
java -Xmx512m -jar artifacts/dist/novalink-core.jar <runs>/<run-id>/novalink/novalink.yml
```

（`NovaLinkMain` 首个参数即配置文件路径；若缺省会找当前目录 `novalink.yml`，务必显式传参。）

### 5.4 代理场景差异（P1）

- **Velocity**：`velocity.toml` 设 `bind = "127.0.0.1:<MC_SERVER_PORT>"`、`online-mode = false`；下游 Paper 的 `server.properties` 里 `server-port` 用 266xx、`online-mode=false`、`server-ip=127.0.0.1`；`velocity.toml` 的 `secret` 与 Paper `spigot.yml` 的 `velocity.secret` 对齐（或用 `paper-global.yml` 的 `proxies.velocity.enabled=true`）。插件配置指向 backend，与代理无关。
- **BungeeCord**：`config.yml` 设 `listeners[0].host = 127.0.0.1:<MC_SERVER_PORT>`；`online_mode: false`；`ip_forward: false`（P1 冒烟不强制 IP forwarding，减少 Paper 端配置面）。
- 断言只做「代理进程起来 → 插件连上 backend → 消息路由可达」，不断言真实玩家通过代理进服（无玩家客户端，见 §9）。

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
- **JDK**：`actions/setup-java@v4`，`temurin` 21（记忆库：`novachat-build-jdk-setup` 记录 JDK21 才满足 folia/multipaper 全量构建）。
- **缓存**：
  - 服务端下载：`actions/cache@v4` key `e2e-dist-<versions.lock.ps1 哈希>`，path `e2e/artifacts/dist`；锁文件变更 → 缓存失效。
  - Gradle：`~/.gradle/caches` + `~/.gradle/wrapper/dists`（现有构建已开 `org.gradle.caching=true`）。
- **并行**：job 按平台矩阵分片——`e2e-backend-paper`（P0，必跑）、`e2e-folia`、`e2e-velocity-bungee`、`e2e-nukkit-pnx`、`e2e-sponge`（P1/P2 标 `continue-on-error: true` 或手动触发，避免外部下载抖动拖红 PR）；每个 job 内多场景**串行**执行（共享 `ports.json` 与缓存目录），job 之间天然并行。
- **并发安全**：每个 job 独立 `artifacts/runs/<job>-<sha>-<n>`，端口随机段按 job 名做盐（`port-picker.ps1` 用 job 名派生随机种子），防 runner 内撞端口。
- **超时**：workflow 级 `timeout-minutes: 45`；每 job 再设更严上限（§7.4）。
- **测试报告**：JUnit XML 风格汇总（`report/e2e-results.xml`）或 Markdown 表格，随 PR 评论/artifact 上传；`EULA_NOT_ACCEPTED` 场景标 **SKIPPED-YELLOW** 而非失败。

### 8.3 Go 一致性观测位

- CI 中一个独立、可手动触发的 job：在 `novalink-go` 冻结声明不变的前提下，用同一 `versions.lock` 启动 Go backend，跑同一批 A 机器人断言，输出差异报告（**不参与 PR 门禁**）。Go 端现状（`novalink-go/FROZEN.md`）与 Java 未完全对齐，此 job 仅用于跟踪漂移。

### 8.4 安全边界

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
| 3 | LeviLamina / PocketMine-MP / Endstone | C++/PHP/Python 独立工具链 + 依赖下载面过大 | **P2 外、不在执行矩阵** |
| 4 | Fabric / NeoForge / Quilt / Forge | 需要 Gradle 9.5+（当前 wrapper 8.8，loader 子模块未纳入构建），且为 mod 加载器（需额外 bootstrap） | **不可执行**（构建前置未满足） |
| 5 | MultiPaper | 明确冻结（命令层未接 ChannelCommandService/KnownChannelRegistry/ListCommandService） | **排除** |
| 6 | novalink-go | 冻结/实验，功能未对齐 | 不执行；仅观测位 |
| 7 | MySQL/Redis 持久化路径 | 已有 Testcontainers 覆盖；引入 DB 依赖会放大外部服务抖动 | 不在本套件（归现有测试） |
| 8 | 真实基岩客户端登录 Nukkit/PNX | 基岩登录协议 + 设备模拟复杂 | **不可自动化**（仅服务端进程 + NovaProtocol 连线） |
| 9 | BungeeCord 与高版本下游完整兼容 | Bungee 维护周期慢、下游协议组合爆炸 | 仅 login/server 级冒烟 |
| 10 | 公网网络环境下的下载与防火墙行为 | 本套件全回环；真实部署网络策略超出自动化范围 | 不覆盖 |

---

## 10. 落地检查清单（实施阶段按此执行）

- [ ] `e2e/` 目录与 §3.1 结构落地，`artifacts/`、`report/` 入 `.gitignore`
- [ ] `versions.lock.ps1` 填实（URL + 实测 SHA-256），`fetch-server.ps1` 校验闭环
- [ ] 本机人工确认各服务端 EULA 后，将 `eula.txt` 放入 gitignore 的 `artifacts/eula/`
- [ ] `run-backend.ps1` + `gen-config.ps1` + `wait-health.ps1` 打通 P0：backend 起来，机器人 A 握手/认证/路由/keepalive 全绿
- [ ] `process-tree.ps1`（Job Object + 树遍历）验证：异常路径下无残留 JVM、端口全部释放
- [ ] P1：Folia、Velocity、Bungee 场景脚本；BotClient 插件事件链路断言
- [ ] P2：Nukkit、PNX、Sponge；WS 面板会话（JWT 登录 → `/api/status`）；可选 MC 协议机器人
- [ ] CI workflow：分片 job、缓存、并行、失败转储 artifact、EULA 缺失跳过逻辑
- [ ] 每次服务端版本升级走 lock 文件 + SHA 更新 + 一次本机全量回归
