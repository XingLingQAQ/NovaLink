# E2E 共享层

Java、Bedrock 和 5 个 L1 编排入口已使用共享生命周期与进程控制。Joint runner、离线验收器和自检共用判定函数，L1 与 Joint 共用普通 Bedrock 客户端创建逻辑。

实现日期：2026-11-08。拆分前基线：`97509183755e81784f4a8a2320afd6009ab4820b`。调用入口、环境变量和结果字段保持原有含义。

## 1. 模块与职责

| 模块 | 当前调用方 | 负责范围 |
| --- | --- | --- |
| [l1-lifecycle.js](../../test/bot/l1-lifecycle.js) | [Java bot](../../test/bot/run-e2e.js)、[Bedrock bot](../../test/bot/run-e2e-bedrock.js) | 初始化、场景执行、完成、失败、断线、超时、结果写入和清理。 |
| [e2e-process.ps1](../../test/bin/e2e-process.ps1) | Bukkit、PMMP、Endstone、LeviLamina 和 multiplatform 的 L1 入口 | 进程启动、句柄保存、等待、超时终止、退出码和日志路径。 |
| [evidence-checks.ps1](../../test/joint/evidence-checks.ps1) | Joint runner、原始证据验收器和场景自检 | 7 个共享判定函数，接收录制对象或已读取的日志文本。 |
| [bedrock-client.js](../../test/bot/bedrock-client.js) | L1 与 Joint Bedrock bot | legacy skin、PMMP ClientData、普通 Bedrock 身份链修补与 PNX 分流。 |

[l1-route.js](../../test/bot/l1-route.js)继续负责独立后端 peer、第二个游戏客户端和路由交换。[assert-l1-route.js](../../test/bot/assert-l1-route.js)继续解码原始协议，核对身份与唯一收包。共享生命周期统一清理路由协作者。

## 2. L1 生命周期有可等待的完成信号

`createL1Lifecycle()` 接收协议适配器的结果快照、写入、关闭、清理和退出函数。调用 `run(initialize, sequence)` 后，`initialized` 在初始化成功或失败时完成，`completed` 在最终结果写入后完成。测试可以注入时钟，无须猜测异步初始化何时注册计时器。

场景完成与主动退出分开判断。场景完成后、主动退出前的断线仍为失败。提前断线、初始化失败、kick、error 和超时都会留下失败结果；重复结束事件不会覆盖先前结论。

协议入口保留 Java 的 `end`、`kicked` 和 Bedrock 的 `close`、`kick` 映射。PMMP 的 toggle 逻辑仍在 Bedrock 场景内，平台字段、`received` 行种类和原始包含义不变。

## 3. 进程模块只报告执行事实

`Start-E2EProcess` 启动进程后立即保存句柄，供 Windows PowerShell 5.1 正确读取退出码。它返回本次拥有的 Process、PID 和标准输出／错误路径。

`Wait-E2EProcess` 接收启动结果与超时预算，超时后终止本次拥有的进程并等待退出。返回 PID、`ExitCode`、`TimedOut`、`Stdout` 和 `Stderr`。未知退出码使用 -1，继续按失败处理。启动与等待分开，允许先启动多个 bot。

模块内部不退出父脚本。调用方继续核对本次 runId、服务端日志、路由收包和平台断言，进程退出 0 本身不构成 E2E 通过。

[环境测试](../../test/environment/scenarios.ps1)仍使用 PowerShell 7.4 的正常关闭与日志排空流程；本次未迁移该运行器。

## 4. Joint 验收器可随证据独立分发

共享判定包括 `Assert-BotExpectations`、`Assert-Delivered`、`Assert-ProxyPluginLoaded`、`Assert-LogContains`、`Get-BackendAuthIdentities`、`Assert-RelayDistinctIdentities` 和 `Assert-RelayLinksCreated`。

[assert-joint-raw-evidence.ps1](../../test/joint/assert-joint-raw-evidence.ps1)直接加载判定模块，已移除从 runner 语法树提取这 7 个函数的逻辑。文件存在性、非空、runId、角色、进程记录与要求的唯一断言 ID，仍由原始证据读取和验收入口核对。summary 的 `pass=true` 不能代替原始录制；render coordination 和 render effects 的重算也保留。

[export-verifier.ps1](../../test/joint/export-verifier.ps1)将验收入口、判定模块、场景依赖和版本锁复制到独立目录。Joint workflow 把这份源码放进证据产物的 `verifier/` 中，无须分发实服 runner。

```powershell
./test/joint/export-verifier.ps1 -Destination .e2e-artifacts/verifier

# 下载的产物解压后，在证据目录执行，参数须与本次选择一致。
./verifier/test/joint/assert-joint-ci.ps1 -JointRoot . `
  -ProxyMode relay -ProxyPlatform velocity -BedrockPlatform pnx `
  -ScenarioGroups core,custom-effects -RequireRenderCoordination -RequireRenderEffects
```

## 5. Bedrock 登录修补发生在身份链生成后

锁定的 `bedrock-protocol` 3.61.0 在 `sendLogin()` 中调用 `createClientChain()`，再序列化 JWT。Joint 先前在 `sendLogin()` 外层提前写入 token，随后会被库重新生成的身份链覆盖。

共享工厂在普通 Bedrock 的 OIDC 离线身份链生成后补齐 PMMP 接受的字段，再交给库发送。保留 L1 的 `BOT_LOGIN_PATCH=0` 禁用开关；旧协议保持库原有的身份链。

PNX 继续使用 [createPnxClient()](../../test/bot/pnx-client.js)的唯一 XUID 派生、NetherNet 信令与资源包握手。普通 Bedrock 的固定 XUID 修补不会作用于 PNX。Transfer 入口继续使用 PNX 专用能力，不套用 L1 场景生命周期。

## 6. 验证状态

| 范围 | 当前证据 |
| --- | --- |
| 生命周期 | 正常退出、提前断线、完成后退出前断线、初始化失败、超时、重复结束和清理失败回归。 |
| 进程控制 | Windows PowerShell 5.1 的正常／非零退出、并发启动、超时终止、未知退出码和日志路径检查。 |
| Joint 判定与分发 | 场景自检通过；独立目录导出后能加载判定，并拒绝缺原始文件、缺身份和缺连接。 |
| Bedrock 工厂 | 实际签名 JWT 的发送时机、字段、禁用开关、旧协议与 legacy skin 检查。PNX 专用测试继续通过。 |
| Java 实服 | 本次 Bukkit/Purpur L1 PASS，独立接收者与后端 peer 证据通过。 |
| Bedrock 实服 | 本次 PocketMine L1 PASS，登录及独立路由往返证据通过。 |

新增模块已加入 Gradle harness 文件清单、路径检查和相应 CI。路由的 [离线夹具](../../test/bot/l1-route-fixtures.test.js)在普通 push/PR 执行拒绝条件；真实录制回放仍独立保留。

历史 Joint 录制未通过当前完整验收，原因是缺少新要求的 reconnect 或后端归属原始证据。本次没有重跑完整 Joint、环境或平台矩阵。各场景仍分别要求 L1 独立收包、Joint 拓扑与身份、环境重启与数据库读回、Transfer 目标 spawn 与源释放证据。

具体命令和日志位置见[项目审查的修复记录](design-review-2026-11-08.md#7-修复实现与验证结果)。
