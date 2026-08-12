# NovaChat 第二轮 UX 实现状态（对账表）

> 对照 `docs/UX-DESIGN-2.md` §9-§15 设计，记录 2026-07-30 ~ 2026-08-01 实际落地情况。
> 生成日期：2026-08-01。测试基线：JDK21 全量矩阵 1465/1465 通过（213 份 Java 测试报告，0 failures / 0 errors / 0 skipped）。
>
> **2026-08-08 更新**：`novachat-multipaper` 与 `novalink-go` 已从仓库删除（产品决策，不再需要）。下文保留的 multipaper 对账行/§14 是**历史记录**（删除前的冻结状态），不再代表当前支持平台。当前活平台为 7 个 Java 端：bukkit/folia/nukkit/pnx/sponge/velocity/bungee。`com.nova.chat.multipaper` 相关代码引用已同步清除（含 `NovaLinkMain`/`NovaProtocol`/`client-core` 共享层）。

## 逐项状态

### §9 离开频道反馈统一

- **设计要点**：全部 7 平台补"正在离开频道 X…"即时回执；`ChannelResponseDispatcher` 的 LEAVE 成功分支新增 chat 确认（`PlayerMessages.left(channel, default)`），与 action bar 并行，velocity/bungee 因 action bar no-op 以 chat 确认作为唯一离开反馈。
- **实际落地**：⚠️ 部分落地（核心已落地，bukkit/multipaper 接缝为已知后续）
- **落地提交**：`2cd572c`（dispatcher sendLeaveSuccess + 6 平台接线）+ `08936b7`（proxy 修复双确认）+ `0b494c7`（nukkit/pnx 修复双确认）
- **共享层改动**：`ChannelResponseDispatcher` LEAVE 成功分支调 `adapter.sendLeaveSuccess(playerId, leftChannel)`（`ChannelResponseDispatcher.java:96`）；接口声明 `ChannelResponseDispatcher.java:215`；文案由各平台 adapter 内部渲染 `PlayerMessages.left(channel, default)`。
- **平台接线表**：

  | 平台 | 即时回执（leaving） | 后端确认（left via dispatcher） | 备注 |
  |------|--------------------|--------------------------------|------|
  | bukkit | 手拼 `LeaveCommand.java:85` "正在离开频道 &e…&7..." | **未接 dispatcher**，走 legacy `NetworkClient.formatChannelActionSuccess` (`NetworkClient.java:708`) 发"已离开频道 &eX"（无默认频道句） | UX-COPY-SERVER.md P1/P2 已知后续；已有 chat 确认但措辞未收口 |
  | folia | 手拼 `LeaveCommand.java:80` "正在离开频道 &e…&7..." | ✅ `AsyncChatInterceptor.java:172` sendLeaveSuccess → `:180` `PlayerMessages.left(...)` | UX-COPY-SERVER.md P1 已知后续（即时回执未收口到 PlayerMessages） |
  | nukkit | ✅ `LeaveCommand.java:83` `PlayerMessages.leaving(...)` | ✅ `ChatInterceptor.java:145` sendLeaveSuccess → `:152` `PlayerMessages.left(...)` | 已完全收口 |
  | pnx | ✅ `LeaveCommand.java:71` `PlayerMessages.leaving(...)` | ✅ `NetworkClient.java:295` sendLeaveSuccess → `:302` `PlayerMessages.left(...)` | 已完全收口；"不可离开默认频道"守卫保留 |
  | sponge | ✅ `NovaChatCommand.java:286` `PlayerMessages.leaving(...)` | ✅ `ChatListener.java:147` sendLeaveSuccess → `:154` `PlayerMessages.left(...)` | 已完全收口 |
  | velocity | ✅ `NovaChatCommand.java:193` `PlayerMessages.leaving(...)` | ✅ `ChatListener.java:143` sendLeaveSuccess → `:146` `PlayerMessages.left(...)` | 已完全收口；chat 确认是降级唯一反馈 |
  | bungee | ✅ `NovaChatCommand.java:195` `PlayerMessages.leaving(...)` | ✅ `ChatListener.java:141` sendLeaveSuccess → `:147` `PlayerMessages.left(...)` | 已完全收口；chat 确认是降级唯一反馈 |
  | multipaper | 手拼 `LeaveCommand.java:71`（**冻结，未触碰**） | **无 dispatcher** | §14 有意跳过 |

- **遗留接缝（已知后续，非本轮 bug）**：
  - UX-COPY-SERVER.md #4 `bukkit/LeaveCommand.java:85` 即时回执未收口到 `PlayerMessages.leaving`（P1）
  - UX-COPY-SERVER.md #13 `folia/LeaveCommand.java:80` 即时回执未收口（P1）
  - UX-COPY-SERVER.md #8 `bukkit/NetworkClient.java:708` LEAVE 确认未用 `PlayerMessages.left` 且缺默认频道句（P2）
  - multipaper 无后端确认（§14 冻结）
- **注**：`08936b7`/`0b494c7` 修复了 proxy 与 nukkit/pnx 上"即时回执已用 left() 导致后端确认落地时双确认"的回归——即时回执统一改用 `leaving()`，后端确认路径唯一发 `left()`。

### §10 @提及防刷屏正式接线

- **设计要点**：每平台持有单例 `MentionNotifier`，handler 收到 `MentionPacket` 后先调 `notifyOrSkip(mentioned, mentioner, onNotify)`，通过才 `playSound`+`sendTitle`；`shouldNotify` 内淘汰超过 `DEDUP_INTERVAL_MS` 的旧条目（修 BUG-M2）。
- **实际落地**：✅ 已落地（7 平台全部接线）
- **落地提交**：`2e13f1b`（6 平台 dedup 接线）+ `b008671`（pnx 集中转发状态 + dedup）
- **共享层改动**：`MentionNotifier.notifyOrSkip(...)` 便捷方法（`MentionNotifier.java:206`，包内重载 `:211`）；`shouldNotify` 内 `synchronized (lastNotifiedAt)` 块中 `removeIf` 过期淘汰（`MentionNotifier.java:186-195`）——过期淘汰在 synchronized 块内，符合设计。
- **平台接线表**：

  | 平台 | 接线点 | 形式 |
  |------|--------|------|
  | bukkit | `NetworkClient.java:264` | `mentionNotifier.notifyOrSkip(...)` |
  | folia | `AsyncChatInterceptor.java:301` | `mentionNotifier.notifyOrSkip(...)` |
  | nukkit | `ChatInterceptor.java:299` | `mentionNotifier.notifyOrSkip(...)` |
  | pnx | `ChatInterceptor.java:225-227` `shouldNotifyMention` → `mentionNotifier.shouldNotify(...)`（`NetworkClient.java:221` 调用） | 走 `shouldNotify` 而非 `notifyOrSkip`，等价去重；PNX 无 `playSound` API，`onNotify` 形式不适用 |
  | sponge | `ChatListener.java:317` | `mentionNotifier.notifyOrSkip(...)` |
  | velocity | `ChatListener.java:250` | `mentionNotifier.notifyOrSkip(...)` |
  | bungee | `ChatListener.java:242` | `mentionNotifier.notifyOrSkip(...)` |
  | multipaper | **未触碰**（冻结） | §14 跳过 |

- **遗留接缝**：无（7 平台全部接线，BUG-M2 已修）。

### §11 PNX forwardingEnabled 下沉

- **设计要点**：共享 `PlayerChannelState` 新增 `boolean forwardingEnabled`（默认 true，volatile）；PNX `chatEnabled` 委托共享 getter/setter，删除本地私有布尔；PNX `/nc toggle` 行为不变（仍翻 forwardingEnabled，文案仍是"聊天已开启/已关闭"）。
- **实际落地**：✅ 已落地
- **落地提交**：`b008671`
- **共享层改动**：
  - 字段 `PlayerChannelState.java:30` `private volatile boolean forwardingEnabled = true;`
  - getter `:138` `isForwardingEnabled()` / setter `:142` `setForwardingEnabled(boolean)`
  - `copy()` 复制 `:187` `copy.forwardingEnabled = this.forwardingEnabled;`
  - `toString()` 含字段 `:200`
- **PNX 委托**：`ChatInterceptor.java:264` `isChatEnabled()` → `channelState.isForwardingEnabled()`；`:268` `setChatEnabled(boolean)` → `channelState.setForwardingEnabled(...)`；本地私有布尔字段已删除（`PlayerChatState` 仅持有 `PlayerChannelState channelState`）。注释 `:245-250` 明确"distinct from shared ChatMode，/nc toggle 仍翻 forwarding flag"——尊重 memory 约束，行为未改。
- **测试**：`PlayerChannelStateTest.forwardingEnabled`（`:221`）覆盖。
- **遗留接缝**：无。

### §12 跨平台文案统一（modeName 等）

- **设计要点**：共享 `ChatModeDescriptions` 增加 `modeName(ChatMode)`（"频道模式"/"混合模式"），平台 toggle 路径引用而非自造；sponge 的"替换模式"对齐为"频道模式"。
- **实际落地**：⚠️ 部分落地（共享层已就位，3 平台 toggle 路径收口；bukkit/folia/nukkit/multipaper 的 toggle 仍手拼，已知 P1/P2）
- **落地提交**：`6f0fc87`（proxy 收敛 toggle copy）+ `fef1893`（共享 modeName 方法）
- **共享层改动**：`ChatModeDescriptions.modeName(ChatMode)`（`:74`），常量 `HYBRID_MODE_NAME="混合模式"`（`:35`）、`REPLACE_MODE_NAME="频道模式"`（`:43`，注释明确"对齐 §12，历史称'替换模式'"）。
- **平台接线表**：

  | 平台 | toggle 路径是否引用 modeName | 备注 |
  |------|------------------------------|------|
  | sponge | ✅ `NovaChatCommand.java:346` `ChatModeDescriptions.modeName(newMode)` | "替换模式"已对齐为"频道模式"；UX-COPY-PROXY.md S6 P1 已修 |
  | velocity | ✅ `NovaChatCommand.java:258` | UX-COPY-PROXY.md V6 P2 已修 |
  | bungee | ✅ `NovaChatCommand.java:259` | UX-COPY-PROXY.md B6 P2 已修 |
  | bukkit | ❌ `ToggleCommand.java:82,84` 手拼"频道模式/混合模式" | UX-COPY-SERVER.md #5 P1 已知后续 |
  | folia | ❌ `ToggleCommand.java:66` 三元手拼 | UX-COPY-SERVER.md #14 P1 已知后续 |
  | nukkit | ❌ `ToggleCommand.java:65` 三元手拼 | UX-COPY-SERVER.md #24 P1 已知后续 |
  | pnx | N/A | PNX 是 chat on/off，不走 ChatMode（§11 约束） |
  | multipaper | ❌ `ToggleCommand.java:47` 手拼（冻结） | §14 跳过 |

- **遗留接缝（已知后续，非本轮 bug）**：UX-COPY-SERVER.md #5/#14/#24（bukkit/folia/nukkit toggle 模式名手拼，均 P1）；multipaper（冻结）。
- **注**：proxy 侧前缀/无权限/不在频道/help 列表的接缝在 UX-COPY-PROXY.md 标 P1/P2，本轮 `6f0fc87` 收敛了部分 toggle/leave copy，其余为已知后续。

### §13 PlayerMessages 文案表

- **设计要点**：新增 `PlayerMessages`（client-core，纯静态），集中 `joining/joined/leaving/left/currentChannelBar/chatOn/chatOff` 6 方法；各平台引用替换硬编码。
- **实际落地**：⚠️ 部分落地（共享类已就位 + leave/join 即时回执在 proxy+nukkit+pnx 收口；bukkit/folia/multipaper 仍手拼，已知 P1）
- **落地提交**：`fef1893`（PlayerMessages 类）+ `2cd572c`/`08936b7`/`0b494c7`/`6f0fc87`（leave/join 收口）
- **共享层改动**：`PlayerMessages.java` 全量 112 行，6 方法齐全：
  - `joining(channel)` `:33` → "正在加入频道 &e{ch}&7..."
  - `joined(channel)` `:44` → "已加入频道 &e{ch}"
  - `leaving(channel)` `:55` → "正在离开频道 &e{ch}&7..."
  - `left(channel, default)` `:68` → "已离开频道 &e{ch}&7，已切换到默认频道: &e{def}"
  - `currentChannelBar(channel, mode)` `:84` → "&7当前频道：&b{ch} &7（{modeName}）"
  - `chatOn()` `:94` / `chatOff()` `:103`
- **调用点覆盖**：
  - `joining`：bungee `:150`、velocity `:148`、sponge `:243`（已收口）；bukkit `JoinCommand:77`、folia `JoinCommand:75`、nukkit `JoinCommand:71`、pnx `JoinCommand:75`、multipaper `JoinCommand:68` 仍手拼（UX-COPY-SERVER.md P1 已知后续，pnx/nukkit 是无 `&` 色码变体）。
  - `joined`：暂无生产调用点（后端 JOIN 确认各平台仍手拼"已加入频道 X"，UX-COPY-SERVER.md #7/#15/#25/#36 与 UX-COPY-PROXY.md V8/B8/S7 均标 P2 已知后续）。
  - `leaving`：nukkit `LeaveCommand:83`、pnx `LeaveCommand:71`、sponge `NovaChatCommand:286`、velocity `:193`、bungee `:195`（已收口）；bukkit `LeaveCommand:85`、folia `LeaveCommand:80`、multipaper `LeaveCommand:71` 仍手拼（P1 已知后续）。
  - `left`：folia `AsyncChatInterceptor:180`、nukkit `ChatInterceptor:152`、pnx `NetworkClient:302`、sponge `ChatListener:154`、velocity `ChatListener:146`、bungee `ChatListener:147`（dispatcher 后端确认路径已收口）；bukkit 走 legacy `NetworkClient:708` 未收口（P2 已知后续）。
  - `currentChannelBar`：暂无生产调用点（action bar 各平台仍手拼，UX-COPY-SERVER.md #6/#16/#26 与 UX-COPY-PROXY.md B9 标 P1/P2 已知后续）。
  - `chatOn`/`chatOff`：暂无生产调用点（PNX toggle 与表单仍手拼"聊天已开启/已关闭"，UX-COPY-SERVER.md #35/#38/#39 标 P1/P2/P3 已知后续；§11 约束保留 on/off 行为）。
- **遗留接缝（已知后续，非本轮 bug）**：bukkit/folia/multipaper 的 join/leave 即时回执未收口（P1）；`joined`/`currentChannelBar`/`chatOn`/`chatOff` 生产调用点为 0（P2/P3 后续）；均已在 UX-COPY-SERVER.md / UX-COPY-PROXY.md 登记。
- **结论**：`PlayerMessages` 作为基础设施已落地并承载了 leave 后端确认（§9）与 proxy/nukkit/pnx 的即时回执；剩余接缝已登记为后续 P1/P2/P3，非本轮 bug。

### §14 multipaper 体验对齐（冻结，有意跳过）

- **设计要点**：multipaper 接 `ChannelResponseDispatcher` + `/nc list` + DUP-7 迁移 + action bar。
- **实际落地**：❌ 遗漏（**有意跳过，非 bug**——memory `multipaper-command-layer-archb-gap` 记录其命令层未接 ChannelCommandService/KnownChannelRegistry/ListCommandService，在冻结期不触碰）
- **核对**：`git show --stat` 逐提交核对，本轮 12 个 commit **无任何 `novachat-multipaper/` 改动**（multipaper `LeaveCommand.java:71`/`JoinCommand.java:68`/`ToggleCommand.java:47` 仍为冻结期旧代码）。
- **结论**：设计 §14 标注的"multipaper 需先接 dispatcher"是前置依赖，本轮在冻结约束下有意跳过，符合任务约束。

### §15 TODO 项（确认未偷偷实现）

- **设计要点**：`/nc who` 成员列表、`ItemDisplayPacket` handler、welcome 全网首次、nukkit/pnx/folia kick/mute 命令——仅标 TODO，不实现。
- **实际落地**：✅ 全部保持 TODO 状态（未偷偷实现）
- **核对**：
  - `/nc who`：`WhoCommandService.isMemberListingSupported()`（`WhoCommandService.java:50`）仍恒 false；bukkit `WhoCommand.java:49` 仍走降级分支；其余平台 WhoCommand 类注释仍写明降级。✅ 未偷偷实现成员列表。
  - `ItemDisplayPacket`：client handler 仍 0 命中（仅 `novachat-common` 协议层 `NovaProtocol.java:54` 注册 + 测试）。✅ 未偷偷加 handler。
  - welcome 全网首次：本轮提交链无 welcome 相关改动。✅ 未偷偷实现。
  - nukkit/pnx/folia kick/mute 命令：`Glob novachat-{nukkit,pnx,folia}/src/main/java/**/{Kick,Mute,Unmute}Command.java` 0 文件。✅ 未偷偷实现。
- **结论**：§15 四项 TODO 全部保持未实现状态，无违规。

## 本轮提交链

| hash | 说明 |
|------|------|
| `e25a507` | docs(test): fix matrix count nits (55 entries, 108 test classes) |
| `0b494c7` | fix(ux): nukkit/pnx leave immediate receipt uses leaving() — backend confirm sends left() |
| `854bd8e` | docs(e2e): design executable real-server E2E automation |
| `43b8afc` | docs(test): add code-level test matrix audit |
| `13af549` | docs(ux): audit server copy seams |
| `08936b7` | fix(ux): leave immediate receipt uses leaving() — backend confirm sends left() |
| `2cd572c` | feat(ux): confirm successful channel leaves |
| `6f0fc87` | refactor(proxy): converge command UX copy |
| `fbdf6a0` | docs(ux): audit proxy copy seams |
| `2e13f1b` | feat(mention): deduplicate platform notifications |
| `b008671` | refactor(pnx): centralize forwarding state and dedup mentions |
| `fef1893` | feat(client-core): centralize player messages |

## 测试基线

- **JDK21 clean test**：1465 tests, 0 failures, 0 errors, 0 skipped（213 份 Java `TEST-*.xml` 报告汇总，`./gradlew --no-daemon test` BUILD SUCCESSFUL）。
- **已知脆弱项**：已在前序会话解决（见 memory `novalink-test-flakiness.md`），本轮 clean run 无 flaky。
- **编译告警（非缺陷）**：以下为既有的 `@SuppressWarnings("unchecked")` 标注点，属泛型擦除侧的预期告警抑制，非本轮引入：
  - `novachat-folia/src/main/java/com/nova/chat/folia/network/AsyncNetworkClient.java:131`
  - `NovaChat/client-core/src/main/java/com/nova/chat/client/network/CoreNetworkClient.java:246`
  - `novachat-common/src/main/java/com/nova/chat/common/extension/DefaultExtensionLoader.java:105`

## 下一轮建议（非本轮范围）

- **multipaper 解冻后的对齐工作清单（§14）**：接 `ChannelResponseDispatcher`（注入 sendMessage/renderActionBar）获得 join/leave 确认 + 错误码反馈；新增 `/nc list`（复用 `ListCommandService`）；`ToggleCommand` 改引用 `ChatModeDescriptions`；命令路径迁共享 `PlayerChannelState`（DUP-7）；action bar 降级。前置依赖见 memory `multipaper-command-layer-archb-gap`。
- **UX-COPY-SERVER.md P1 接缝收口**：bukkit/folia/nukkit toggle 模式名引用 `ChatModeDescriptions.modeName`；bukkit/folia join/leave 即时回执引用 `PlayerMessages.joining/leaving`；`joined`/`currentChannelBar`/`chatOn`/`chatOff` 生产调用点接线。
- **UX-COPY-PROXY.md P2 接缝收口**：proxy 侧 join/leave/joined 文案引用 `PlayerMessages`。
- **REAL-SERVER-E2E.md 的实施**：`854bd8e` 设计的执行化 E2E 自动化**已落地**——截至 2026-08-08，7/7 Java 平台达成 L1（bukkit/bungee/velocity/nukkit/folia/pnx/sponge），并对账记录于 `docs/REAL-SERVER-E2E.md` §1.5。E2E 另发现 3 个产品 bug（velocity 4.1.0 命令节点剪枝、nukkit/pnx `registerCommands` 静默失败、sponge `novachat.use` default-deny）并已修复。
