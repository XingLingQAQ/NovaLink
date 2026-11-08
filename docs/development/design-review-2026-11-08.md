# 项目设计、功能与测试审查

本轮确认了 7 项可复现缺陷，以及常规 CI 漏跑测试、组件交互覆盖不足的问题。R01—R09 已完成代码修复与本地回归，[E2E 共享层](e2e-shared-layer.md)的 4 个模块也已实现并接入调用方。

审查与修复日期：2026-11-08。修复前基线：`97509183755e81784f4a8a2320afd6009ab4820b`。第 2—5 节保留修复前的观察与任务依据；当前实现和验证结果见第 7 节。

## 1. 审查范围与证据

本轮检查了后端私信分发、REST 请求处理、配置更新与发布、客户端请求跟踪、Panel 设置状态，以及 E2E 入口与 CI 调用关系。重点追踪输入、状态变化、输出和直接调用方。

| 方法 | 用途 | 本轮结果 |
| --- | --- | --- |
| `code-refactor-review` | 检查重复实现、依赖方向、隐式依赖和模块职责。 | 形成共享层与配置状态所有权建议。 |
| `frontend-api-integration-patterns` | 检查 REST、WebSocket 与异步响应的状态覆盖。 | 复现设置版本号丢失和旧响应覆盖新状态。 |
| `frontend-state-and-interaction-design` | 检查保存、冲突、失败和恢复状态。 | 发现后端保存失败与面板回滚结果不一致。 |
| `zh-tech-writing` | 编写可追溯的中文方案和审查记录。 | 记录触发条件、证据等级和修复验收条件。 |
| `coding-standards` | 参考语言约定与项目现有规范。 | 外部 JavaScript 指南读取失败，本报告未据该指南提出风格缺陷。 |

Panel 现有测试使用 Node.js `v24.19.0` 执行，结果为 **163 项通过，0 项失败或跳过**。命令如下：

```powershell
Set-Location Panel/web
node --import ./_test/register.mjs --test --test-reporter=spec
```

后端反例使用 JDK `25.0.4`、Netty `EmbeddedChannel` 和内存提供者。相关受审类从当前源码重新编译，依赖复用现有 classpath。本轮没有启动 Minecraft、外部数据库或远程 CI。设置处理器反例直接调用实际处理方法；私信与请求 ID 反例经过真实分发入口。

Panel 反例执行当前 `useDashboardData.js` 中的回调，用替身提供 hook 状态和 API 响应。它验证回调逻辑，不代表浏览器交互验收。

本地反例源码与结果保存在 `.e2e-artifacts/design-review-20261108/`。该目录受 Git 忽略；下文保留独立可理解的触发过程和观察结果，正式修复时应将相应断言纳入仓库测试。

## 2. 修复前已复现的功能缺陷

### R01 · P1：私信发送者未绑定到发送节点

位置：[PrivateMessageHandler.java](../../StarLink/core/src/main/java/com/nova/link/network/PrivateMessageHandler.java#L146)，重点为 `findPlayerByName()` 后的发送者处理；入口为 [ServerNetworkHandler.java](../../StarLink/core/src/main/java/com/nova/link/network/ServerNetworkHandler.java#L297)。

处理器按数据包中的 `senderName` 查询全网玩家，随后覆盖 `senderId`。它没有确认查到的玩家属于当前连接的 `clientId`。连接认证和代际校验只能证明节点身份，不能证明节点有权代表另一节点上的玩家。

**复现：**注册 3 个已认证连接。玩家 `Victim` 属于 `server-b`，接收者属于 `server-c`。从 `server-a` 发送声称来自 `Victim` 的私信。经 `handlePacket()` 分发后，`server-c` 收到消息；发送者 UUID 被改成 `Victim` 的真实 UUID，`senderClientId` 则仍为 `server-a`。

影响是已认证但异常或被控制的接入节点可以冒用其他节点的在线玩家发送私信。这不需要绕过节点认证。

修复应在私信处理前验证玩家与当前连接的归属，并在传给后续处理器前形成确定的发送者身份。代理专用后端连接也须沿用实际归属规则。现有[发送者测试](../../StarLink/core/src/test/java/com/nova/link/network/PrivateMessageHandlerTest.java#L333)验证 UUID 覆盖和名字大小写，但未覆盖“真实玩家名字来自另一个节点”。

验收：外节点冒名消息无投递、无成功审计；合法直接连接和代理转发继续可用；玩家切服后的归属按当前会话判断。

### R02 · P1：相同配置版本的并发写入都会成功

位置：[RestApiHandler.java](../../StarLink/core/src/main/java/com/nova/link/api/RestApiHandler.java#L2823) 的 `handleUpdateSettings()`；[ConfigManager.java](../../StarLink/core/src/main/java/com/nova/link/config/ConfigManager.java#L152) 的 `save()`。

更新处理器先读取 revision，再校验、修改共享配置，最后调用 `save()`。锁只覆盖保存阶段。两个工作线程可以读取同一个旧 revision，分别通过校验后保存。

**复现：**用屏障让两个实际处理器调用都读取到 `baseRevision=0`，分别提交 `filterEnabled=false` 与 `true`。两次响应均为 **200**，最终 revision 为 **2**。后提交者未收到预期的冲突响应。

修复应把版本校验、候选配置构造、持久化和提交放在同一个配置更新边界内。仍保留旧调用方不传 revision 时的既有约定，版本保护适用于显式提供 revision 的更新。

验收：两个显式使用同一 revision 的并发写入只能有一个成功，另一个返回 409；失败请求不改变生效配置。现有 [revision 测试](../../StarLink/core/src/test/java/com/nova/link/api/RestApiHandlerAuditTest.java#L577)主要按先后顺序验证旧版本和新版本，未制造校验与保存之间的并发交错。

### R03 · P1：保存失败后，后端生效状态与面板回滚不一致

位置：[设置更新](../../StarLink/core/src/main/java/com/nova/link/api/RestApiHandler.java#L2913)、[过滤规则更新](../../StarLink/core/src/main/java/com/nova/link/api/RestApiHandler.java#L5770)和 [Panel 失败回滚](../../Panel/web/src/hooks/useDashboardData.js#L523)。

后端先修改内存配置和运行时开关，再写磁盘。保存抛错时返回 500，且错误文字明确说明应用已成功、持久化失败。Panel 将此响应当作整体失败，并恢复修改前的开关值，因此画面与实际运行状态相反。

**复现：**初始 `filterEnabled=true`，提交 `false`，让 `ConfigManager.save()` 抛出 `ConfigException`。返回值为 **500**，内存值仍变成 **false**，revision 仍为 **0**。过滤规则路径存在相同的先应用后保存结构，本轮未单独执行该路径。

建议与 R02 一并修复：配置更新先构造候选状态，持久化成功后发布新快照和运行时变更。若继续支持部分成功，必须提供明确的机器可读状态和权威快照，让 Panel 据此恢复显示；单纯返回错误文字不足以协调双方状态。

验收：注入保存失败后，磁盘、内存配置、运行时开关和面板显示保持一致；失败 revision 和成功广播行为符合选定契约。

### R04 · P2：Panel 保存成功后没有采用返回的新 revision

位置：[useDashboardData.js](../../Panel/web/src/hooks/useDashboardData.js#L488) 的 `handleSettingChange()`。

该函数等待 `api.updateSettings()`，但丢弃成功响应。后端响应已经包含新的设置和 revision，前端却继续依赖后续 WebSocket 通知更新本地版本。

**复现：**客户端和服务器起始 revision 为 10。第一次保存成功，服务器返回 11，客户端仍为 10。没有 WebSocket 补偿时，第二次保存继续发送 10，收到 409。观察到两次请求的版本序列为 `[10, 10]`。

修复应在成功响应后统一采用服务器设置快照和 revision。REST 保存成功不应依赖 WebSocket 才能完成本地状态更新。

验收：WebSocket 断开时，连续两次保存分别使用 10 和 11，均成功；并发操作的旧响应不能覆盖较新的保存结果。

### R05 · P2：旧 REST 设置响应会覆盖较新的 WebSocket 状态

位置：[useDashboardData.js](../../Panel/web/src/hooks/useDashboardData.js#L60) 的 `fetchSettings()` 和 [WebSocket 设置回调](../../Panel/web/src/hooks/useDashboardData.js#L553)。

WebSocket 服务会过滤自身收到的旧消息，但 REST 与 WebSocket 各自直接改写同一份设置。`fetchSettings()` 无条件采用返回值，也会降低 `settingsRevisionRef`。

**复现：**REST 请求尚未返回时，先接收 revision 12 的 WebSocket 快照；随后让 REST 返回 revision 11。当前 hook 的设置从 **12 回退到 11**。

修复应让 REST 加载、REST 保存、409 恢复与 WebSocket 通知调用同一个快照采用函数。函数维护当前服务器/登录会话内的 revision 顺序，并在切换会话时显式重置。请求取消和响应版本校验分别解决资源回收与状态新旧问题。

验收：11 的晚到响应不能覆盖 12；切换后端或重新登录后，可接收新会话的初始版本。可参考 React 官方的 [Effect 数据请求清理](https://react.dev/reference/react/useEffect#fetching-data-with-effects)。

### R06 · P2：连接级 request ID 会串到同一连接的其他请求

位置：[RestApiHandler.channelRead0()](../../StarLink/core/src/main/java/com/nova/link/api/RestApiHandler.java#L482)、[异步派发](../../StarLink/core/src/main/java/com/nova/link/api/RestApiHandler.java#L551)和 [currentRequestId()](../../StarLink/core/src/main/java/com/nova/link/api/RestApiHandler.java#L6364)。

处理器把 request ID 存进 Netty channel 属性，再把请求派发到工作线程。一个连接存在两个尚未完成的请求时，第二个请求会覆盖第一个请求的 ID。响应和审计随后读取的都是连接上的最新值。

**复现：**在同一个 `EmbeddedChannel` 输入 `request-a` 和 `request-b`，先排队两个工作任务，再执行。两次 401 响应的 `X-Request-Id` 都为 **request-b**。反例不需要登录凭据，也不打开网络端口。

修复应在接收请求时创建不可变的请求上下文，随工作任务传递到响应和审计。请求身份属于一次请求，不能由连接上的可变字段承担。

验收：同连接请求交错时，各自的响应和审计保留原 ID；同时验证 HTTP/1.1 响应顺序。后者在本轮未测试，不能从 ID 反例推定已出现错序响应。

### R07 · P3：PendingRequestTracker 清理会丢失回调中注册的新 future

位置：[PendingRequestTracker.java](../../NovaChat/client-core/src/main/java/com/nova/chat/client/network/PendingRequestTracker.java#L165) 的 `clearAll()`。

函数先完成旧 future，再执行 `pending.clear()`。`CompletableFuture` 的同步完成回调可以在清空之前注册新请求，新请求随后被删除，却没有完成或失败。

**复现：**给旧 future 注册 `whenComplete()`，回调中使用同一 request ID 再次 `track()`。调用 `clearAll()` 后，`pendingCount=0`，新 future 的 `isDone()` 为 **false**。

本轮搜索仅发现该类及其测试，未发现生产调用方，因此优先级低于在线路径问题。修复需明确清理期间新注册请求的规则，先移除本次要结束的条目，再通知回调，或显式拒绝清理期间注册。无论采用哪种方式，返回给调用方的 future 都不能失去完成途径。

验收：同步重入与并发注册下，新 future 保持可跟踪或明确失败；补充原有[并发 smoke](../../NovaChat/client-core/src/test/java/com/nova/chat/client/network/PendingRequestTrackerTest.java#L321)未覆盖的清理场景。

## 3. 修复前的测试和 CI 缺口

### R08 · P2：已有离线检查没有进入常规 CI

[nova-ci.yml](../../.github/workflows/nova-ci.yml) 的 Panel job 只执行 lint 与生产构建，未运行 [package.json](../../Panel/web/package.json) 已定义的测试命令。本轮 163 项本地通过不代表该工作流曾执行它们。

另有 3 个独立 PowerShell 检查未被现有 workflow 或根 Gradle 构建调用：

- [test-orchestrator-verdicts.ps1](../../test/bin/test-orchestrator-verdicts.ps1)：非法选择、缺行、重复行、未知结果和严格跳过。
- [test-l1-build-inputs.ps1](../../test/bin/test-l1-build-inputs.ps1)：存在旧 classpath 时仍构建，构建失败时停止。
- [test-e2e-artifact-whitelist.ps1](../../test/bin/test-e2e-artifact-whitelist.ps1)：证据文件保留范围。

[l1-route.test.js](../../test/bot/l1-route.test.js)在手动 L1 workflow 中运行。它的大部分删改负例依赖 `L1_ACCEPTANCE` 指向实服留存，未提供时跳过。因此，只向普通 Node 检查命令追加这个文件，仍不能获得稳定的离线回归覆盖。

修复应把 Panel 测试加入现有 job，将依赖 `powershell.exe` 和 `.bat` 的检查放到 Windows job。为路由验收补充明确标识为合成的离线 fixture，专门测试拒绝条件；真实录制回放继续保留为独立验证。

验收：常规 push/PR 运行上述检查；测试自身失败时 job 失败；合成负例通过不会写成实服 E2E 通过。

### R09 · P2：静态组件测试未覆盖实际交互

[ConfigPublishPanel.test.js](../../Panel/web/src/components/dashboard/ConfigPublishPanel.test.js#L12)、[SettingsView.test.js](../../Panel/web/src/components/dashboard/SettingsView.test.js#L14)和 [ModerationManagement.test.js](../../Panel/web/src/components/dashboard/ModerationManagement.test.js#L13)明确采用 `renderToStaticMarkup()`。这些检查适合确认页面结构、权限入口与翻译，效果函数和用户操作不会执行。

例如 moderation 的 [403 检查](../../Panel/web/src/components/dashboard/ModerationManagement.test.js#L137)分别确认 API 抛出状态码和翻译文案存在，未操作 `AppealQueue`，也未证明拒绝后页面实际展示了提示。设置页现有测试未调用 `useDashboardData` 的保存和快照回调，因而 R04、R05 可以与 163 项通过同时存在。

修复应保留现有静态检查，补充有用户操作和异步响应的关键路径测试。优先覆盖连续保存、冲突恢复、旧响应、审批失败后输入保留，以及重复点击时的提交次数。避免用“函数存在”或“文案能翻译”替代流程成功。

验收：测试实际触发处理函数或客户端组件交互，并断言状态变化、请求次数和用户可见结果。浏览器测试仍遵守项目的 jshook MCP + Camoufox 约定。

## 4. 应调整的设计边界

### 配置提交应由一个组件负责

R02、R03 共同暴露了配置状态所有权分散。REST 处理器直接改功能开关，`ConfigManager` 负责保存与 revision，`ConfigPublishService` 和 `ConfigHistoryService` 又分别执行复制、交换、保存与失败恢复。

[发布](../../StarLink/core/src/main/java/com/nova/link/api/ConfigPublishService.java#L275)、[恢复备份](../../StarLink/core/src/main/java/com/nova/link/api/ConfigPublishService.java#L439)和 [历史回滚](../../StarLink/core/src/main/java/com/nova/link/api/ConfigHistoryService.java#L503)都把交换引用与保存拆成多个调用。`save()` 的锁不能自动把调用者之前的交换纳入事务。本轮未对这 3 条路径逐一制造并发反例；它们应由配置修复负责人连同 R02 一起检查。

推荐在现有配置域建立单一提交入口，接收候选修改、可选预期 revision 和操作者。HTTP 层只解析输入、校验权限和映射结果。提交入口统一管理持久化、revision、运行时采用和广播次序，保留旧入口的缺省参数约定。

### Panel 设置需要一个快照采用规则

当前 `useDashboardData` 同时管理多个业务域，并让 REST 与 WebSocket 直接写入设置。先把设置相关状态、版本校验和提交动作集中到一个专用 hook 或 reducer。其他业务域只有在出现共同的状态规则时再拆分，避免一次引入通用状态框架。

同类线索还有 [MessageHistory.fetchPage()](../../Panel/web/src/components/dashboard/MessageHistory.jsx#L61)：请求结果无版本或取消保护；输入框的 Enter 路径也没有 loading 检查。该路径本轮只完成源码检查，尚未加入上文的 7 项复现统计。

### 保持已建立的模块隔离

本轮核对的 Gradle 依赖中，`StarLink/core` 使用 `NovaChat/common` 与 `StarLink/storage`；代理共享层使用存储模块。未在后端生产源码中发现对客户端或平台适配层的反向导入。

`com.nova.link.*` 包名本身不能证明依赖了后端核心，例如代理引用的 `DatabaseProvider` 实际位于存储模块。后续拆分应依据所属模块和运行职责，避免按包名前缀误判耦合。

E2E 的生命周期、进程控制和 Joint 判定拆分按[专门方案](e2e-shared-layer.md)执行。当前文档中的旧 `e2e/` 路径还存在于架构参考和文档索引；本轮已修正直接关联的开发与测试指南，其余历史说明需要按用途区分后更新。

## 5. 修复顺序与责任范围

| 顺序 | 负责范围 | 交付条件 |
| --- | --- | --- |
| 1 | 后端网络：R01。 | 发件人归属校验生效，合法跨服私信保留，外节点冒名负例通过。 |
| 2 | 配置域：R02、R03，以及发布、恢复、回滚的提交边界。 | 同版本并发冲突正确；保存失败后运行状态一致；现有入口契约保持。 |
| 3 | Panel 设置：R04、R05。 | REST 与 WebSocket 采用同一版本规则，连续保存和晚到响应检查通过。 |
| 4 | REST 请求上下文：R06。 | 请求 ID 随任务传递，同连接交错请求各自归属正确。 |
| 5 | 测试维护：R08、R09。 | 常规 CI 运行现有离线检查，关键状态转换有行为断言。 |
| 6 | E2E 维护：共享层方案。 | 完成全部受影响调用方迁移，并保留各场景的独立证据规则。 |
| 7 | 客户端基础库：R07。 | 清理与重入不遗留失去跟踪的 future。 |

## 6. 本地证据索引

| 文件 | 内容 |
| --- | --- |
| `.e2e-artifacts/design-review-20261108/BackendReviewProbe.java` | R01、R02、R03、R07 的内存反例。 |
| `.e2e-artifacts/design-review-20261108/backend-result.json` | 上述 4 项的实际观察值。 |
| `.e2e-artifacts/design-review-20261108/panel-state-probe.cjs` | R04、R05 的当前源码回调反例。 |
| `.e2e-artifacts/design-review-20261108/panel-state-result.json` | revision 丢失与 12 回退到 11 的观察值。 |
| `.e2e-artifacts/design-review-20261108/RequestIdProbe.java` | R06 的同连接双请求反例。 |
| `.e2e-artifacts/design-review-20261108/request-id-result.json` | 两个响应都使用 request-b 的观察值。 |
| `.e2e-artifacts/design-review-20261108/run-backend-probes.ps1` | 在新的独立目录重新编译受审类并执行后端反例；依赖本机已有 classpath。 |

## 7. 修复实现与验证结果

| 编号 | 已完成的修复 | 直接回归证据 |
| --- | --- | --- |
| R01 | 私信规范化身份前，核对玩家所属节点与发送连接。 | `PrivateMessageHandlerTest` 覆盖外节点借名、切服后旧节点、合法直接连接和转发。 |
| R02 | `ConfigManager` 在一个锁内校验版本、复制候选配置、保存并提交。GET 快照与 revision 同锁读取。 | `ConfigManagerCommitTest` 同版本并发只允许一个成功；旧版本返回当前快照。 |
| R03 | 设置、过滤规则、发布、恢复和回滚改用候选提交，保存失败不改变生效状态。 | API 设置和过滤规则的写入失败测试；发布、恢复、回滚失败测试；磁盘、内存、运行时与 revision 一致性测试。 |
| R04 | 设置保存成功后采用响应快照和新 revision。 | 无 WebSocket 时连续保存使用版本 `[10, 11]`；实际设置组件触发保存。 |
| R05 | `useSettings` 与 `settingsStore` 统一处理 GET、PUT、409 和 WebSocket。会话改变时更换 store。 | 晚到的 GET、PUT、409、失败响应不能覆盖较新快照；旧会话响应不能覆盖新会话。 |
| R06 | 请求 ID 保留在随工作任务传递的请求中，响应与审计显式读取该请求。 | 同连接请求和反序工作任务保留各自 ID；HTTP 入口统一排队，验证 REST、认证与健康检查的响应顺序。 |
| R07 | 清理前保留本批条目快照，先按原条目删除，再触发 future 回调。 | `clearAll` 与过期清理的同步回调重新注册同 ID，重试仍可正常完成。 |
| R08 | 常规 CI 加入面板测试、路由合成负例及 Windows 离线检查。 | 本地运行对应命令；Windows job 逐脚本检查独立进程退出码。远端工作流尚未触发。 |
| R09 | 保留静态测试，新增真实组件挂载、事件和异步响应测试。 | 设置连续保存和失败回滚、审批失败保留备注、申诉自审 403、重复点击与举报详情失败显示。 |

局部设置提交只更新功能与过滤运行时。完整配置发布、恢复、回滚和重载才重新应用频道配置。持久化成功后的通知失败写入日志，不把已经提交的配置误报为保存失败。历史回滚在提交锁内记录一个新快照，消除重复 revision 记录及晚到历史写入。

`MessageHistory` 也已加入请求代际保护，并阻止加载期间的 Enter 重复查询。Strict Mode 的两次初始请求交错测试确认，旧响应不会覆盖当前列表。

### 验证命令与结果

| 范围 | 已执行的验证 | 结果 |
| --- | --- | --- |
| 后端配置与 API | `:StarLink:core:test --tests '*Config*' --tests '*Rest*' --tests '*PrivateMessage*' --tests '*I18n*'` | 406 项通过。 |
| HTTP 入口与请求归属 | `:StarLink:core:test --tests '*RestApiHandler*' --tests '*HttpRequestSequencer*' --tests '*WebSocketServer*'` | 182 项通过，与上一行有重叠。 |
| 提交后的通知失败与核心启停 | `:StarLink:core:test --tests '*ConfigManagerCommitTest' --tests '*NovaLinkCoreLifecycleTest'` | 通过，验证持久化成功后的通知异常、局部／完整提交和实际核心启停。 |
| 客户端请求跟踪 | `:NovaChat:client-core:test --tests '*PendingRequestTrackerTest'` | 25 项通过。 |
| 管理面板 | `npm test`、`npm run lint`、`npm run build` | 177 项通过；lint 无错误，保留未改动通知组件的 1 条既有警告；构建通过。 |
| L1 生命周期、登录与路由判定 | 生命周期、Bedrock JWT/皮肤、PNX、声音录制及路由合成负例 | 受影响的 Node 检查通过；合成检查不产生实服结论。 |
| Windows 编排与 Joint | verdict、构建输入、产物白名单、进程模块、场景自检和独立验收器导出 | 离线检查通过，受影响的 PowerShell 脚本可解析。 |
| Java 实服 | Bukkit/Purpur L1，独立端口和本次结果目录 | PASS，后端 peer 与独立游戏接收者证据齐全。 |
| Bedrock 实服 | PocketMine L1，独立端口和本次结果目录 | PASS，登录、命令、聊天往返及独立接收证据通过。 |

本地日志均位于 `.e2e-artifacts/`。后端检查记录在 `review-backend-tests.log`、`review-http-tests.log` 和 `review-config-lifecycle-tests.log`。面板结果在 `review-panel-tests.log`、`review-panel-lint.log` 与 `review-panel-build.log`。实服入口日志为 `review-real-bukkit.log`、`review-real-pmmp.log`；原始结果位于 `review-fix-bukkit/` 和 `review-fix-pmmp/`。

Joint 历史录制重算未通过当前完整验收：旧 lite 录制缺少 reconnect 判定，render-effects 录制缺少 `relay-attribution.results.json`。验收器继续拒绝这些输入。独立目录导出的验收器已通过纯判定测试和缺原始文件负例。

本轮未重跑全部平台、严格 Joint 实服或环境矩阵，也未验证全部数据库提供者。原有矩阵待办继续保留；下一次对应实服执行时，应使用本次导出的验收器和当前证据清单。新 CI 接线的远端执行结果仍需首次 push/PR 确认。
