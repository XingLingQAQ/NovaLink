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

REST 原实现先调用频道更新，再单独设置 `slowModeSeconds`。频道管理器接入持久化时，接口与后续 GET 返回新值，存储仍是旧值。启动恢复构建频道时也漏传该字段，运行时值变为 0；已接持久化的管理器还会把 0 再次写入存储。另一个反例确认更新持久化失败被错误映射为 `400 Invalid request body`。核心装配未接入频道持久化的独立遗漏见 R27。

修复把慢速模式纳入同一频道锁内的字段验证、应用、revision、持久化和失败回滚；原四参数、五参数更新入口保留。REST 直接调用这一入口，存储失败返回 `500` 并记录失败审计。启动恢复完整传递慢速值，避免重启时关闭限速。

验证：三个反例在原实现均失败，分别观察到持久化值 0、恢复值 0 和错误的 HTTP 400。修复后相关频道 REST 更新、持久化及启动恢复测试共 20 项通过；恢复反例使用单连接 SQLite，调用实际启动恢复入口，并检查运行时与数据库中的值。日志为 `.e2e-artifacts/review6-channel-before.log` 和 `review6-channel-final.log`。

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

## 11. R27：核心未接入频道持久化，TCP 又重复保存

位置：[NovaLinkCore 装配](../../StarLink/core/src/main/java/com/nova/link/NovaLinkCore.java) 与 [ChannelActionHandler](../../StarLink/core/src/main/java/com/nova/link/network/ChannelActionHandler.java)。

频道管理器虽支持写入数据库，实际核心却一直使用无提供者的实例。真实 SQLite 核心启动反例在创建和更新频道后读取不到记录。此前直接构造带提供者管理器的测试只证明入口支持持久化，没有覆盖生产装配。

修复在配置频道与持久化频道加载结束后绑定同一个数据库提供者。这样启动加载不重写原记录，后续 REST、控制台和 TCP 经频道管理器统一持久化。TCP 原本另有一次显式保存和删除，带提供者时会各写两次；两个计数反例确认此问题。现在管理器已接持久化时只由管理器写入，独立内存模式的 TCP 调用仍保留原有显式保存行为。

验证：实际启动核心、创建与更新 SQLite 频道、停止并重启、读取慢速值与名称、删除并检查数据库。另用 TCP 创建/删除验证带提供者与内存模式都只写一次，并检查原治理入口，共 13 项通过。日志为 `.e2e-artifacts/review6-wiring-before.log`、`review6-protocol-persistence-before.log` 和 `review6-wiring-final.log`。

## 12. R28：频道删除失败仍清空成员，创建失败误报参数错误

位置：[REST 频道管理](../../StarLink/core/src/main/java/com/nova/link/api/RestApiHandler.java) 与 [控制台删除](../../StarLink/core/src/main/java/com/nova/link/console/ConsoleCommandHandler.java)。

原 REST 和控制台先移除频道成员、清除玩家已加入及当前频道，再调用持久化删除。数据库失败时频道登记仍在，但成员状态已丢失。REST 创建的持久化异常也被外围捕获，当作无效请求体返回 `400`。

修复在原频道锁内先按对象身份删除，存储成功后才清理原对象的成员与玩家状态。失败不触发这些清理；对象已被替换时也不删除或清理新对象。REST 创建与删除的持久化失败返回 `500` 并记录失败审计；参数解析仍沿用原 `400` 路径。

验证：原 REST 删除反例观察到成员丢失，创建反例观察到 HTTP 400。修复回归覆盖 REST 与控制台失败时保留成员、已加入频道与当前频道，并检查成功创建、正常删除和未知频道路径，共 11 项通过。日志为 `.e2e-artifacts/review6-delete-before.log` 与 `review6-delete-final.log`。

## 13. R29：频道删除遗留玩家状态与管理员权限

位置：[TCP 删除](../../StarLink/core/src/main/java/com/nova/link/network/ChannelActionHandler.java)、[核心装配](../../StarLink/core/src/main/java/com/nova/link/NovaLinkCore.java) 和 [频道生命周期](../../StarLink/core/src/main/java/com/nova/link/channel/ChannelManager.java)。

TCP 删除未清理缓存玩家的已加入频道和当前频道；直接调用频道管理器也没有清理关联权限、监控会话与私有 ID。原频道管理员记录以频道 ID 索引，同名频道重建后仍会给予旧管理员权限。带存储和独立内存两种 TCP 反例，以及实际核心的删除反例均失败。

修复在数据库删除成功后、移除频道登记前执行同步清理。核心统一清理玩家状态、管理员、监控双向索引和私有 ID；所有删除入口及重载共享这一边界。未装配核心的独立调用可提供本地清理，只有一个清理入口会执行。失败的持久化不触发清理，旧对象身份不匹配也不会清理替换对象。

抽取 `ChannelStateCleanup`，由核心、REST、控制台和 TCP 复用。它清除缓存中引用该频道的玩家状态和原频道成员，不加载离线数据库记录。清理在频道锁内、ID 仍被原对象占用时完成，避免事后清理影响同名新频道。

验证覆盖实际核心删除后的玩家、权限、监控会话、私有 ID，以及同名重建后的权限等级；TCP 覆盖两种管理器模式，并复核 REST 与控制台的成功、失败和未知频道路径。日志为 `.e2e-artifacts/review7-removal-before.log` 与 `review7-removal-final.log`。

## 14. R30：重载写入的 CONFIG 频道在停机删除后复活

位置：[ChannelManager 的持久化边界](../../StarLink/core/src/main/java/com/nova/link/channel/ChannelManager.java)。

核心接入频道持久化后，重载新建的 CONFIG 频道也被写入数据库。存储记录不保留 CONFIG 来源，启动恢复统一标记为 DATABASE。实际 SQLite 反例先在运行中通过配置新增频道，再停止核心、从 YAML 删除频道、重新启动；该频道仍从数据库复活为动态频道。

修复把来源归属落实到管理器：CONFIG 频道由配置文件负责，创建、更新和删除均不修改数据库中的动态记录。运行时和恢复的 DATABASE 频道仍按原约定持久化。配置临时覆盖同名动态记录时，移除配置对象不会改写或删除底层动态记录。

验证覆盖上述实际核心重启过程、动态频道的 SQLite 重启与删除、频道持久化失败路径、配置覆盖下保留动态记录，以及重载对象身份检查。日志为 `.e2e-artifacts/review7-provenance-before.log` 与 `review7-provenance-final.log`。

历史误写记录没有保存来源，无法与真实动态频道自动区分；本次保留这些现存记录，防止后续 CONFIG 写入。历史数据需要按原配置和实际频道用途确认后清理。

## 15. R31：重载未更新频道路由边界，重复 ID 混用定义

位置：[NovaLinkCore 的配置频道应用](../../StarLink/core/src/main/java/com/nova/link/NovaLinkCore.java)。

原重载只改名称、权限和容量等字段，无法修改固定的 scope / clientId。GLOBAL 改为 SERVER 后仍按 GLOBAL 路由；客户端迁移则被直接忽略。重复频道 ID 在启动时保留第一个，但重载会用后一个声明修改前一个频道的属性，造成边界与名称来自不同定义。三个原实现反例分别观察到 GLOBAL、旧客户端及后一个声明的名称。

修复在频道锁内确认当前对象与 CONFIG 来源。作用域或客户端绑定变化时，先按对象身份删除并执行清理，再建立新的 CONFIG 频道与索引，revision 高于旧对象；普通字段更新保留成员。每次配置应用只处理同一 ID 的第一个定义，与现有启动顺序一致，后续重复项记录警告。动态频道继续保留。

验证：重载与实际核心配置增删重启共 9 项通过，覆盖边界变化、客户端迁移、重复定义、普通更新保留成员、跳过动态频道及已有替换对象保护；随后只补跑边界替换回归，确认 revision 继续递增。日志为 `.e2e-artifacts/review7-reload-boundaries-before.log`、`review7-reload-boundaries-final.log` 和 `review7-reload-revision.log`。

## 16. R32：过期频道限制遮蔽全局限制，淘汰误删续期记录

位置：[禁言与封禁共享缓存](../../StarLink/core/src/main/java/com/nova/link/moderation/AbstractModerationManager.java)。

原查询遇到过期频道记录立即返回 false，没有继续检查仍有效的全局禁言或封禁，因此首次查询可能放行。读取与定时清理还按键直接删除；读取旧记录后若新限制刚被写入，会删除新缓存值，读取路径还可能无条件删除新的持久化记录。

修复统一布尔查询与详情查询，过期频道记录清除后继续检查全局限制。缓存淘汰使用键和值的条件删除；读取时若旧值已被替换，重新读取新值。定时扫描同样只删除读取到的过期值。消息查询不再执行单条数据库删除；过期数据库记录交给已有每分钟清理任务，避免旧快照删除续期记录，也减少查询中的数据库写入。

验证：实际 MuteManager 与 BanManager 的全局回退反例，以及查询、周期扫描中的续期交错反例共 4 项在原实现失败。修复后复核禁言权限、重启恢复、封禁恢复和实际 JOIN 封禁路径。交错由过期检查入口触发，不使用等待；日志为 `.e2e-artifacts/review7-expiry-before.log` 与 `review7-expiry-final.log`。

## 17. R33：治理存储失败仍报成功，并发提交导致缓存与存储分叉

位置：[禁言与封禁共享层](../../StarLink/core/src/main/java/com/nova/link/moderation/AbstractModerationManager.java)、MuteManager、BanManager 和 REST 错误映射。

原应用和解除操作先改缓存，再尽力写入存储；异常只记录日志，继续返回成功。失败的解除会在本进程先放行，重启后又恢复限制；失败的封禁还会移除成员并发送成功通知。并发应用与解除可以按不同顺序写入缓存和数据库，造成缓存已解除、数据库仍有记录。

修复将持久化成功作为已配置数据库时的提交条件，在共享层以玩家为单位串行提交：先写存储，再改变缓存，封禁移除成员也在同一提交边界内。失败保留旧限制，返回现有 `NC-510`，不触发成功通知或目标推送；无数据库管理器仍保留原内存模式。这个条件取代此前的尽力写入约定。REST 四种单目标操作复用现有错误状态映射，统一返回 HTTP 500；批量与 TCP 调用沿用各自结果通道接收失败码。

验证：四种操作的存储故障反例、一次受控的应用/解除交错在原实现均失败。修复后共 49 项定向测试通过，包含四个实际 REST 500 场景、原权限校验、内存模式、启动恢复、过期回退和 TCP 治理入口。日志为 `.e2e-artifacts/review8-commit-before.log` 与 `review8-commit-final.log`。

## 18. R34：非法治理时长被当作永久限制

位置：[共享时长处理](../../StarLink/core/src/main/java/com/nova/link/moderation/DurationUtils.java)、MuteManager、BanManager，以及 REST、TCP 和控制台入口。

原管理器将负数当作永久时长；大正数与当前时间相加溢出后，到期时间变为负数，也被模型视作永久。TCP 遇到格式错误的值会退回永久；控制台把负数裸值截为 0，单位换算又会饱和到最大整数。REST 的非精确整数转换还会截断小数、回绕超范围数字。

修复由共享函数校验非负时长并精确计算到期时间，禁言和封禁都在存储、缓存及成功副作用之前执行。REST 使用精确整数转换，批量预览也校验到期时间。TCP 和控制台换算使用精确乘法；TCP 已提供的非法毫秒值不会改用秒数字段，也不会被当作省略。非法输入返回现有 `NC-400` 或控制台错误信息。

现有 `0` 和省略时长的永久语义保留，正常的正数时长、TCP 字段别名及控制台单位继续可用。本项未改变各权限等级对显式永久时长的既有约定。

验证：管理器、REST、TCP、控制台四个入口的反例在原实现均失败。修复后相关测试 98 项通过，涵盖旧限制保留、成员保留、无成功通知、批量预览、原权限和成功路径；随后只补跑 TCP 两项，验证空白及非法首选字段拒绝、显式 0 和省略值仍为永久。日志为 `.e2e-artifacts/review9-duration-before.log`、`review9-duration-final.log` 和 `review9-duration-protocol.log`。

## 19. R35：存储过期清理误删刚提交的续期

位置：[MemoryProvider](../../StarLink/storage/src/main/java/com/nova/link/database/MemoryProvider.java) 与 [RedisProvider](../../StarLink/storage/src/main/java/com/nova/link/database/RedisProvider.java) 的禁言、封禁清理。

R32 已修复管理器缓存的旧值删除，但提供者的定时清理仍读取旧记录后按键删除。内存迭代器删除和 Redis 的 `HGETALL` / `HDEL` 都会删除扫描之后刚写入的续期，导致本进程缓存仍有限制、存储却已丢失。四个受控交错反例在原实现均丢失 `renewed` 记录。

内存清理改为按键和值条件删除。Redis 禁言与封禁共用同一条件删除函数，在单键 Lua 脚本内比对扫描时的原 JSON，再删除未变化的条目；写入已经变化时返回 0，不误计为已清理。Redis 命令权限需允许 `EVAL`、`HGET` 和 `HDEL`。JDBC 原本使用带过期时间条件的单条 DELETE，本项不改动。

验证：内存、真实 Redis 的禁言与封禁共 4 项回归通过，同时验证普通过期记录仍删除、永久及未来记录保留、返回数量只计实际删除。交错由读取入口触发，无等待；Redis 仅替换测试连接池，仍执行真实网络读写与 Lua。另复核 R32 / R33 的 9 项治理回归，共 13 项通过，无跳过。临时 Redis 已关闭。日志为 `.e2e-artifacts/review9-storage-expiry-before.log` 和 `review9-storage-expiry-final.log`。

## 20. R36：旧邀请码在频道删除后授权同名新频道

位置：[频道删除](../../StarLink/core/src/main/java/com/nova/link/channel/ChannelManager.java)、[邀请创建](../../StarLink/core/src/main/java/com/nova/link/channel/InvitationManager.java) 和存储提供者。

原删除只清除频道定义，不清理邀请表或 Redis 邀请键。原邀请码仅绑定频道 ID，同名重建后仍能入群；未接频道持久化和 CONFIG 移除也有同一问题。邀请创建在检查频道存在之后独立生成并保存，删除可在保存之前完成，随后留下旧频道的新邀请码。

修复把邀请清理放在释放频道 ID 之前。动态频道与邀请由同一提供者删除；CONFIG 只清理邀请，保留同名动态存储记录。未启用频道持久化时，邀请管理器仍绑定其存储用于清理。邀请创建也在频道锁内重新检查对象身份，与接受、删除共享同一边界。

JDBC 将三个重复删除实现合并到公共提供者，在单连接事务内删除邀请和频道，任一失败都回滚；原审核事务复用同一提交函数。Redis 先扫描当前邀请键，再以 Lua 核对归属并移除目标邀请、频道定义及索引；需允许 `SCAN`、`GET`、`EVAL`、`DEL` 和 `SREM`。内存只移除属于目标频道的记录。

验证：原实现中，内存、SQLite、CONFIG、独立频道登记表的旧码入群，以及创建/删除交错均失败；邀请删除故障也未被执行。修复后 47 项定向检查通过，无跳过，包括真实 Redis、单连接 SQLite、两个 DELETE 故障回滚、其他频道邀请保留、新邀请码入群，以及 REST、控制台、TCP 和审核事务回归。MySQL / PostgreSQL 复用相同 SQL，本轮未运行外部数据库。临时 Redis 已关闭。日志为 `.e2e-artifacts/review10-invitation-before.log` 和 `review10-invitation-final.log`。

## 21. R37：Redis 历史分页先传输整个索引

位置：[RedisProvider 的通知、审计和消息历史查询](../../StarLink/storage/src/main/java/com/nova/link/database/RedisProvider.java)。

原分页先把整个候选索引加载为 Java 列表，再逐条读取、筛选和取页。真实 Redis 的四个反例都确认：索引有 385 个 ID 时，首屏仅取 3 条仍传输了全部 385 个 ID。通知未读数、按用户计数、筛选审计数和消息总数也会先加载整个候选 ID 列表。

修复抽取提供者内的有序索引遍历，分页与筛选计数共用每批 128 个 ID 的入口。页内偏移在筛选之后计算；得到所需数量即停止；消息保留原时间范围，通知保留可见范围与用户已读筛选。计数逐批遍历，索引 ID 的临时内存不再随历史总量增长。首批固定读取上界，后续根据上一条 ID 的当前排名续读，并跳过已越过的分数/ID，处理批间追加和删除导致的位移。

验证：4 项真实 Redis 回归通过，无跳过。每项使用 384 条真实记录及缺失文档的索引项，核对首屏传输量、跨批筛选偏移、同时间戳顺序和精确总数；定向通知另覆盖收件人可见范围及个人已读状态，消息覆盖授权频道集合与时间边界。读取回调触发索引追加和删除，核对续读结果。日志为 `.e2e-artifacts/review10-pagination-before.log` 与 `review10-pagination-final.log`。临时 Redis 已关闭。

Redis 命令白名单需允许 `ZREVRANGEBYSCORE`（含分数）、`ZREVRANK` 和 `ZCOUNT`。稀疏筛选和精确计数仍需遍历候选范围；本项降低单批传输和临时内存，不改变这些筛选的线性时间成本。
