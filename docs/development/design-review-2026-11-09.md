# 架构复核与后续修复

日期：2026-11-09。起始代码基线：`764ffe2f`。本轮继续检查实际依赖、状态归属、资源生命周期和可复用实现；每完成一项单独提交。

## 1. 已校正的架构说明

`937446bc` 更新了[架构参考](../reference/architecture.md)：补齐独立存储模块、代理公共层、代理消息协议和 Bukkit 后端宿主；区分独立进程入口与核心生命周期。E2E 路径和面板实时状态来源已按源码纠正。

核对依据包括 `settings.gradle`、相关模块 `build.gradle`、`NovaLinkCore`、`NovaLinkBukkitPlugin`、`BackendRouter` 和面板状态 hooks。文档链接均可解析，此步骤未运行业务测试。

## 2. R18：停止超时后丢弃剩余清理动作

位置：[NovaLinkCore.drainStarted()](../../StarLink/core/src/main/java/com/nova/link/NovaLinkCore.java)。

原实现执行一个清理回调后检查截止时间。超时就清空整个登记队列，后续管理器既不关闭，也不会留到下一次回收。正常停止随后仍会关闭数据库；启动失败回滚也复用这段逻辑。

回归用已过期的截止时间登记三个关闭动作。原实现只执行 `last-started`，缺少后面的失败回调和调度器关闭动作，断言失败。该输入无需等待 15 秒，也不依赖线程调度速度。

修复后按逆序尝试全部清理动作，单个异常不会中止回收。[ShutdownBudget](../../StarLink/core/src/main/java/com/nova/link/lifecycle/ShutdownBudget.java) 统一计算剩余等待时间，并在预算用尽或调用线程中断时强制取消执行器。REST、网络双线程池、配置监听、公告、活动、治理清理和 WebSocket 定时推送复用该规则；Webhook 的投递取消和消息日志的结果报告保留各自业务处理。

各组件保留原无参数停止入口。核心停止和启动回滚通过带预算的入口协调等待，数据库仍最后关闭。已超时的组件仍执行取消，避免把等待上限变成资源遗留。

验证：`NovaLinkCoreLifecycleTest`、`ExecutorSplitTest`、`ConfigManagerWatcherTest`、`WebhookPersistenceTest`、`CampaignRecoveryTest` 和 `MessageLogServiceTest` 共 34 项通过，0 失败、0 跳过。新增回归还运行实际执行器，确认预算耗尽后阻塞任务收到中断。命令与结果存于 `.e2e-artifacts/review3-shutdown-regression-before.log` 和 `review3-shutdown-final.log`。

## 3. R19：认证请求跨登录复用

位置：[AuthService.refreshAccessToken() / logout()](../../Panel/web/src/services/auth.js)。

上一轮已禁止旧业务请求使用新账号重试，但认证服务本身仍无条件复用单个 `_refreshPromise` 和 `_logoutPromise`。Alice 的刷新未完成时，Bob 新登录后的刷新拿到 Alice 的同一个 Promise，不会发起自己的请求。Alice 的退出未完成时，Bob 的退出也复用旧 Promise；Bob 虽已清除本地凭据，后端却没有收到 Bob 的撤销请求。

两个新增回归均在原实现失败：不同登录的刷新和退出返回相同 Promise。修复将刷新复用范围绑定到认证代际、后端地址和连接代际，将退出复用范围绑定到登录会话。完成回调按 Promise 身份清理，只能释放自己的记录，不能清空新账号仍进行中的任务。

同一会话仍只发送一次刷新；同一次本地退出的重复调用仍共用撤销请求。退出先登记进行中请求，再同步清除本地会话；网络失败继续返回撤销结果，不阻止本地退出。

验证：`auth.test.js`、`api.session.test.js` 和 `refreshLogoutRace.test.js` 共 22 项通过，0 失败、0 跳过。覆盖新登录独立刷新与撤销、旧请求不能清除新请求、401 重试、刷新失败与 WebSocket 重新鉴权。日志为 `.e2e-artifacts/review3-auth-before.log` 和 `review3-auth-final.log`。仅执行认证相关测试，未重复整套面板测试。

## 4. R20：撤销邀请覆写使用记录或复活已删除记录

位置：[InvitationManager.revokeInvitation() / forceRevokeInvitation()](../../StarLink/core/src/main/java/com/nova/link/channel/InvitationManager.java)。

原实现读取邀请码快照，再修改 `revokedAt` 并整体保存。SQLite 反例在读取后插入一次成功领取，随后撤销将 `usedCount` 从 1 写回 0、清除使用人。另一个反例在读取后删除邀请码，撤销通过 upsert 将记录重新创建。两个测试在原实现均失败。

修复新增 `DatabaseProvider.markInvitationRevoked()`，只修改现存记录的撤销时间，保留首次撤销时间和使用记录。JDBC 三种提供者共享一条字段级条件更新；内存在同一键的更新中设置撤销时间；Redis 使用 WATCH / MULTI / EXEC，根据最新数据提交，并保持原绝对过期时间。记录不存在时返回 false，不执行插入。邀请人的身份检查仍由业务入口负责。

验证：`InvitationManagerTest` 与 `PrivateChannelInvitationPersistenceTest` 共 12 项通过，覆盖原有多次使用、身份约束和新增交错反例。另用独立临时 Redis 执行 `RedisInvitationRevocationTest`，实际并发领取和撤销，验证使用次数、首次撤销时间、TTL 与删除后不重建；1 项通过，临时进程已关闭。MySQL / PostgreSQL 复用 JDBC SQL，本轮未运行外部数据库矩阵。日志为 `.e2e-artifacts/review4-revocation-before.log`、`review4-revocation-final.log` 和 `review4-redis-final.log`。

## 5. R21：入群失败仍消耗邀请次数

位置：[InvitationManager.acceptInvitation()](../../StarLink/core/src/main/java/com/nova/link/channel/InvitationManager.java) 与 [ChannelManager](../../StarLink/core/src/main/java/com/nova/link/channel/ChannelManager.java)。

原实现先检查容量，再扣除邀请次数，最后加入成员。已有成员重复领取时，扣额成功但加入返回 false；最后一个名额也可能在检查和加入之间被普通加入占用。已有成员反例已在原实现失败，持久化 `usedCount` 从 0 变为 1，邀请码无法再供其他玩家使用。

修复在同一频道锁内检查频道身份、已有成员和容量，再完成持久化扣额与加入。普通加入、容量修改、频道创建、更新和删除使用同一同步边界；等候中的操作重新确认频道仍是当前登记对象。删除先持久化成功再移除登记与索引，失败时保留原频道。已有成员和频道满员返回 `NC-409`，不会扣除次数。

同时修复频道更新的相邻缺陷：原实现先修改名称，再验证容量；无效容量会留下未持久化的部分改名。现在所有参数校验在字段修改之前完成。

验证：首轮频道管理、邀请与跨客户端访问测试 50 项通过。后续受影响测试 23 项通过，包含删除持久化失败时保留登记与索引，以及 SQLite 邀请持久化路径。新增回归覆盖已有成员不扣额、普通加入与邀请争用最后一个名额、无效容量不留下改名。结果见 `.e2e-artifacts/review4-admission-before.log`、`review4-admission-final.log` 和 `review4-admission-boundaries.log`。

## 6. R22：代理慢速模式清理仍有效的长窗口

位置：[代理 SlowModeTracker](../../NovaChat/Proxy/common/src/main/java/com/nova/chat/proxy/chat/SlowModeTracker.java)。

lite 频道的 `slow-mode` 接受非负整数秒，但原清理固定删除一小时前的发送记录。两小时窗口在一小时后触发第 256 次检查时就被清理；原实现的确定性反例返回 0 秒、允许发送，预期仍需等待 3599 秒。

修复将最后发送时间与保留截止时间一起保存，清理不得早于实际窗口结束。较短窗口继续保留至少一小时的历史，以维持原有间隔调整语义；拒绝请求不移动最后发送时间。若请求使用更长间隔，保留截止时间随之延长。断线仍可通过 `forgetPlayer()` 清除记录，过期长窗口仍会被回收。

验证：注入毫秒时钟，不使用等待；覆盖长窗口跨清理、过期回收、间隔调整和断线清理，并运行 `LocalAuthorityTest` 检查真实 lite 聊天入口，共 28 项通过。结果见 `.e2e-artifacts/review5-slowmode-before.log` 与 `review5-slowmode-final.log`。

## 7. R23：两端心跳防护重复实现

后端与 `client-core` 各维护一份 `KeepAliveBudget` 和 `RecentKeepAlives`，算法相同；客户端额外需要在连接代际变化时清空 ID。两端独立维护会让限速、TTL 或回声识别修复容易遗漏另一端。

抽取到 `NovaChat/common` 的 `protocol/keepalive` 包，统一滑动窗口预算、容量淘汰、UUID 值匹配和 TTL 回收。原四个公开类保留构造器、常量与方法并委托共享实现；后端公开返回类型不变，客户端 `clear()` 入口不变。共享实现只使用 JDK，依赖沿用已有的 `core → common`、`client-core → common`，没有增加后端到客户端运行时的依赖。

验证沿用已有 `KeepAliveHandlerTest`、`RecentKeepAlivesTest` 和 `CoreNetworkClientKeepAliveTest`，检查两端真实心跳处理、回声丢弃、发送上限、窗口恢复、容量与过期以及重连清理，共 28 项通过。未复制新测试集，结果见 `.e2e-artifacts/review5-heartbeat-shared.log`。

## 8. R24：REST 慢速模式未保存，启动恢复又清零

位置：[REST 频道更新](../../StarLink/core/src/main/java/com/nova/link/api/RestApiHandler.java)、[ChannelManager](../../StarLink/core/src/main/java/com/nova/link/channel/ChannelManager.java) 和 [核心启动恢复](../../StarLink/core/src/main/java/com/nova/link/NovaLinkCore.java)。

REST 原实现先调用频道更新并持久化，再单独设置 `slowModeSeconds`。接口与后续 GET 返回新值，存储仍是旧值。启动恢复构建频道时也漏传该字段，已经存好的值变为 0，并被初始化写入再次覆盖。另一个反例确认更新持久化失败被错误映射为 `400 Invalid request body`。

修复把慢速模式纳入同一频道锁内的字段验证、应用、revision、持久化和失败回滚；原四参数、五参数更新入口保留。REST 直接调用这一入口，存储失败返回 `500` 并记录失败审计。启动恢复完整传递慢速值，避免重启时关闭限速。

验证：三个反例在原实现均失败，分别观察到持久化值 0、恢复值 0 和错误的 HTTP 400。修复后运行相关频道 REST 更新、持久化及启动恢复测试；恢复反例使用单连接 SQLite，调用实际启动恢复入口，并检查运行时与数据库中的值。日志为 `.e2e-artifacts/review6-channel-before.log` 和 `review6-channel-final.log`。

## 9. R25：旧重载快照误删同名新频道

位置：[核心重载清理](../../StarLink/core/src/main/java/com/nova/link/NovaLinkCore.java) 与 [ChannelManager](../../StarLink/core/src/main/java/com/nova/link/channel/ChannelManager.java)。

原清理先收集不再配置的 CONFIG 频道 ID，再按 ID 删除。收集后若旧频道已被删除、同一 ID 被用于新运行时频道，后续清理会删除这个新频道，违反重载保留动态频道的约定。确定性反例在返回旧快照前完成替换，原实现将新的 SERVER 频道及其持久化记录一起删除。

新增 `deleteChannelIfCurrent()`，在频道锁内比较对象身份，再执行持久化删除、索引更新与移除登记。核心保留频道对象而非只有 ID；对象已被替换时跳过。原按 ID 删除入口保留并复用同一实现。旧版 `GlobalChannelLoader` 与 `ServerChannelLoader` 的快照清理也采用此入口。

验证：原反例失败，修复后核心重载、频道管理与持久化共 32 项通过；随后只补跑重载的 4 个场景，覆盖核心保留替换频道、正常清理 CONFIG，以及两个旧版加载器保留同名替换对象。日志为 `.e2e-artifacts/review6-reload-before.log`、`review6-reload-final.log` 和 `review6-reload-entrypoints.log`。

## 10. R26：Redis 邀请写入改变过期时间

位置：[RedisProvider 的邀请写入](../../StarLink/storage/src/main/java/com/nova/link/database/RedisProvider.java)。

保存先把剩余毫秒除以 1000，再分两条命令 SET / EXPIRE。有效期不足一秒时直接丢弃，其他情况下丢失精度。领取和旧版标记使用接口读取整秒 TTL，再重写 SET；TTL 为 0 时不再设置过期时间，键会永久保留。

修复保存使用单条 `SET ... PXAT`，绝对截止时间与内容一起提交。领取、旧版标记在原 WATCH / MULTI / EXEC 内设置 `PEXPIREAT`，与撤销接口一致，保留记录自身的毫秒截止时间。

验证：独立临时 Redis 中，保存、领取、旧标记三个原实现反例均改变了截止时间；修复后三种写入的 `PEXPIRETIME` 与邀请的 `expireTime` 完全相同。连同已有并发领取/撤销回归共 4 项通过，无跳过；临时 Redis 已关闭。日志为 `.e2e-artifacts/review6-expiry-before.log` 与 `review6-expiry-final.log`。
