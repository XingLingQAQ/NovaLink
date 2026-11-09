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

## 22. R38：批量治理缓存跨账号、跨请求复用，并发重复执行

位置：[RestApiHandler 的批量治理](../../StarLink/core/src/main/java/com/nova/link/api/RestApiHandler.java)。

原缓存只用调用方提供的 key。第二个管理员使用相同 key 时拿到前一个账号的目标结果，自己的操作没有执行；同一操作人更换动作、目标或原因，也会返回旧结果。缓存查询在预览分支之前，预览可能被替换为已执行结果。缓存只在完成后登记，并发重试可同时进入治理操作。

修复以操作人与 key 定位批次，用不可变的有效请求记录核对动作、目标顺序、频道、时长、原因和案件 ID。预览独立返回。执行前在缓存锁内登记，执行与响应发送在锁外完成；进行中的相同请求返回 `409`，完成后的相同请求复用原序列化响应，内容变化返回 `409` 并提示更换 key。容量检查、过期清理及登记使用同一锁，只淘汰已完成记录，保留原 10 分钟、1,024 项的单进程范围。

首次响应、缓存响应和其他 JSON 响应复用同一发送入口，统一请求 ID、CORS 与连接处理。定向测试的请求、响应和嵌入式通道也由测试调用方释放，参与并发的线程在退出前回收。

验证：四个原实现反例均失败。修复后 12 项定向测试通过，包含跨账号 key、六种有效内容变化、token 刷新后的相同请求、预览前后复用 key、进行中重试与完成重放、逐项审计、原批量角色检查、时长拒绝及排队请求 ID。并发反例由存储闸门控制，不使用休眠。日志为 `.e2e-artifacts/review11-batch-before.log` 和 `review11-batch-final.log`。

## 23. R39：玩家治理与限制列表绕过频道资源权限

位置：[RestApiHandler 的玩家治理与批量入口](../../StarLink/core/src/main/java/com/nova/link/api/RestApiHandler.java)、[PanelResourcePolicy](../../StarLink/core/src/main/java/com/nova/link/auth/PanelResourcePolicy.java)。

共享策略明确规定 `PRIVATE` 仅供 SUPER_ADMIN 访问，但单人治理只校验 ADMIN 角色，再以受信任操作人调用管理器；ADMIN 能禁言、封禁、解除限制和踢出私有频道成员。限制列表还向 VIEWER、ADMIN 暴露私有及已删除频道的记录。批量预览、执行与缓存重放同样缺少频道资源检查。

原攻击矩阵有一项把 ADMIN 对私有频道禁言成功视为预期，与共享策略及既有生产设计说明冲突。本项按共享策略修正该预期，同时保留真正需要的全局治理与超级管理员恢复入口。

修复在 `PanelResourcePolicy` 增加限制读取与管理判断，REST 五种单人操作和批量入口共用资源校验。限制列表先过滤再解析名称、投影和计数，没有可见封禁的玩家不会出现在封禁列表。批量资源校验位于幂等缓存读取前，使用账号当前角色，降权后无法重放原私有频道结果。不可见与缺失频道统一返回 `404 Channel not found`。

SUPER_ADMIN 保留私有频道治理和已删除频道限制的单人解除入口；新增限制与批量操作仍检查频道存在。ADMIN 省略频道时的全局操作保持原有契约。测试调用方释放请求、响应及嵌入式通道。

验证：新增四项 HTTP 回归在原实现中三项失败，分别暴露写入越权、列表泄漏和批量越权；超级管理员正向与恢复用例原本通过。修复后首轮 34 项定向检查通过，覆盖五种操作的存储、成员、当前频道及通知状态不变，三种角色的列表与数量，批量预览、执行及降权重放，以及原有批量、资源策略和单人治理行为。最终完整攻击矩阵、单人治理、资源策略及原有 ADMIN 写入、VIEWER 读取兼容性检查共 45 项通过，0 失败、0 跳过。日志为 `.e2e-artifacts/review11-moderation-scope-before.log`、`review11-moderation-scope-final.log` 和 `review11-moderation-scope-compatibility.log`。

## 24. R40：并发入群越过已完成的封禁

位置：[ChannelActionHandler 的 JOIN、ACCEPT 与 CREATE](../../StarLink/core/src/main/java/com/nova/link/network/ChannelActionHandler.java)、[BanManager](../../StarLink/core/src/main/java/com/nova/link/ban/BanManager.java)。

JOIN 与 ACCEPT 在修改成员前检查封禁，但检查和入群之间没有协调。请求检查通过后停住，另一线程完成封禁并移除成员，请求再继续加入，最终留下已封禁成员。CREATE 还会自动加入创建者，原实现没有检查全局封禁。

修复由 `BanManager.withAdmission` 复用封禁应用、解除所用的逐玩家缓存更新边界。三种入群路径共享玩家标识解析和执行入口，覆盖检查、邀请扣额、成员写入及玩家状态更新。入群先执行时，封禁随后移除成员；封禁先执行时，入群沿用 `NC-403` 拒绝，邀请不扣额。全局封禁拒绝 CREATE，不创建频道。未装配封禁管理器的既有调用仍沿用原流程。

该入口不引入额外玩家锁表；回调不能再写封禁。频道容量与邀请扣额继续由既有频道锁负责，存储层不依赖协议响应类型。

原实现四个有效并发反例失败，覆盖私有频道密码入群、邀请接受与频道/全局封禁。首次运行另两项普通 JOIN 夹具错误地给 GLOBAL 频道设置 clientId，未进入并发路径；已修正。日志为 `.e2e-artifacts/review12-admission-before.log`。

修正夹具后，29 项定向回归通过，0 失败、0 跳过。七项受控并发用例覆盖普通 JOIN、私有 JOIN、ACCEPT 与频道/全局封禁，以及 CREATE 与全局封禁；其余检查覆盖既有封禁拒绝与到期、解封、邀请不扣额、跨客户端定向邀请、未装配封禁管理器时的创建及持久化。并发测试等到封禁完成或确认封禁线程正在等待入群线程持有的锁，再释放闸门，不使用休眠。同一生产实现下的五项治理提交及存储失败回归也已通过。日志为 `.e2e-artifacts/review12-admission-corrected.log` 与 `review12-admission-final.log`。

## 25. R41：封禁清理成员后仍保留在线玩家的旧频道状态

位置：[BanManager 的成功副作用](../../StarLink/core/src/main/java/com/nova/link/ban/BanManager.java)、[NovaLinkCore 的管理器装配](../../StarLink/core/src/main/java/com/nova/link/NovaLinkCore.java)。

原封禁只删除频道成员，不更新 `PlayerState.joinedChannels` 和 `activeChannel`。REST 玩家列表直接读取这些字段，封禁后仍可显示旧频道；后续保存还会把旧引用写回存储。全局封禁也遗留已删除频道及仅存在于当前频道字段中的引用。

核心现在向共享 `BanManager` 注入 `PlayerStateManager`。封禁持久化成功后，在同一逐玩家串行边界内清理在线状态；频道封禁只移除指定频道，全局封禁清空全部频道引用。清理复用 `leaveChannel`，标记待保存，沿原有玩家状态保存流程落库。REST、TCP 和控制台无需分别实现状态同步；三参数构造仍支持独立装配。

验证：两个真实后端启动反例在原实现中均失败，频道封禁后当前频道仍是 `first`，全局封禁后仍是已删除的 `retired-active`。修复后 13 项定向回归通过，0 失败、0 跳过：两项启动回归同时核对其他频道和其他玩家不受影响、脏状态保存后的持久化结果；七项受控并发回归补查成员及玩家状态都被清理；四项治理存储失败回归确认旧状态保留。日志为 `.e2e-artifacts/review12-ban-state-before.log` 与 `review12-ban-state-final.log`。

## 26. R42：邀请有效期截断、回绕及溢出后仍返回创建成功

位置：[InvitationManager.createInvitation](../../StarLink/core/src/main/java/com/nova/link/channel/InvitationManager.java)、[REST 邀请入口](../../StarLink/core/src/main/java/com/nova/link/api/RestApiHandler.java)。

原 REST 使用 `getAsLong` 解析有效期，小数会截断，超出 long 的整数会回绕。管理器直接把当前时间与有效期相加，负数、零和溢出均可生成邀请码并返回成功。Redis 会跳过已过期记录，导致成功响应中的码实际不存在；其他提供者会保存立即失效的记录。

修复在 REST 使用精确整数转换，在共享邀请管理器拒绝非正有效期，并用精确加法计算到期时间。校验发生在邀请码生成和存储访问之前；到期时间在获得频道锁后计算。`InvalidTtlException` 保留参数异常继承关系，供 REST 将无效有效期映射为 `400`，不改变频道不存在的 `404`。省略或 `null` 仍使用 24 小时，不套用治理时长中“0 表示永久”的约定。

两个新增反例在原实现均失败。首轮 20 项邀请、REST 创建与撤销检查通过。将到期时间计算移到频道锁内、区分数值错误与无效 JSON 后，最终 5 项相关检查通过，0 失败、0 跳过。非法输入覆盖零、负数、long 两端、小数及可回绕为正常短时长的超大整数，并核对未访问邀请存储；正常自定义有效期、默认 24 小时及频道缺失也已验证。日志为 `.e2e-artifacts/review13-ttl-before.log`、`review13-ttl-final.log` 和 `review13-ttl-exact-final.log`。

## 27. R43：旧频道 JOIN 授权同名新频道，删除后仍追加成员与状态

位置：[ChannelActionHandler.handleJoin](../../StarLink/core/src/main/java/com/nova/link/network/ChannelActionHandler.java)、[PrivateChannelManager.joinPrivateChannel](../../StarLink/core/src/main/java/com/nova/link/channel/PrivateChannelManager.java)。

普通 JOIN 对观察到的频道对象校验作用域，再按频道 ID 加入当前对象。GLOBAL 频道在中间删除并同名重建为 PRIVATE 时，旧检查能授权加入新私有频道。私有管理器则在检查后直接写旧对象，删除完成后仍可向旧成员表追加玩家；协议层随后还可写回已删除频道的玩家状态。

修复将协议 JOIN 的对象身份复核、访问检查、成员加入及玩家状态更新放入同一个频道锁。普通与私有路径共用这一提交入口，通知和 webhook 放在频道锁外。私有管理器的公开入群入口也独立遵守同一锁与对象身份约束，覆盖直接调用和已有成员的快速返回。

删除或替换先完成时，旧对象返回 `NC-404`；JOIN 先完成时，删除清理随后移除其成员和玩家状态。既有跨客户端私有成员恢复、新成员客户端及密码校验沿用原规则。本项覆盖 JOIN；CREATE 和 ACCEPT 的后续复核见 R44。

原实现的旧 GLOBAL 授权新 PRIVATE、直接私有加入已删除对象两项反例失败。普通 JOIN 并发用例首次被线程内 Mockito 初始化延迟影响；将连接初始化移到主线程后，确认普通 JOIN 同样可越过删除。首轮修复后的 31 项定向检查通过，覆盖上述三项回归、入群与封禁并发、原封禁行为、私有邀请及 webhook。保留私有入口原有空白客户端判定后，最终三项生命周期回归、密码直入拒绝和既有成员跨客户端恢复共 5 项通过，0 失败、0 跳过。日志为 `.e2e-artifacts/review13-channel-generation-before.log`、`review13-channel-generation-before-corrected.log`、`review13-channel-generation-final.log` 与 `review13-channel-generation-exact-final.log`。

## 28. R44：CREATE、ACCEPT 在频道删除后补写权限、存储及玩家状态

位置：[PrivateChannelManager.createPrivateChannel](../../StarLink/core/src/main/java/com/nova/link/channel/PrivateChannelManager.java)、[InvitationManager.acceptInvitation](../../StarLink/core/src/main/java/com/nova/link/channel/InvitationManager.java)、[ChannelActionHandler 的 CREATE 与 ACCEPT](../../StarLink/core/src/main/java/com/nova/link/network/ChannelActionHandler.java)。

频道创建发布后，私有管理器无锁加入创建者并登记生成 ID；协议处理器再独立补写权限、兼容存储和玩家状态。频道在这些交接点被删除并同名重建时，旧创建结果仍返回成功，可把旧创建者登记为新频道管理员，并用旧定义覆盖新存储。CREATE、ACCEPT 的玩家状态更新也在成员处理返回之后，删除清理完成后仍能写回旧频道引用。

修复让私有创建者及 ID 登记在频道锁内检查对象身份；协议 CREATE 在同一对象锁内再次复核，随后提交权限、兼容存储和玩家状态。旧对象已被删除时返回 `NC-404`，不会污染替代频道。ACCEPT 增加成功回调，在扣额、入群成功后且频道锁释放前发布调用方状态。原三参数接受接口委托到同一实现，存储和邀请层不依赖协议包或网络处理器。

验证：四个受控反例在原实现全部失败，覆盖频道创建返回前、私有创建返回后的同名替换，以及 CREATE、ACCEPT 在玩家状态发布前与删除交错。修复后 37 项定向检查通过，0 失败、0 跳过，包括旧结果拒绝、旧成员/权限/生成 ID/玩家状态不残留、替代定义不被覆盖、原持久化路径、邀请使用和封禁并发。日志为 `.e2e-artifacts/review14-admission-before.log` 与 `review14-admission-final.log`。

## 29. R45：协议兼容装配吞掉频道存储失败，删除提前清理邀请

位置：[ChannelManager 的创建和删除存储边界](../../StarLink/core/src/main/java/com/nova/link/channel/ChannelManager.java)、[PrivateChannelManager 的创建入口](../../StarLink/core/src/main/java/com/nova/link/channel/PrivateChannelManager.java)、[ChannelActionHandler 的 CREATE、DELETE](../../StarLink/core/src/main/java/com/nova/link/network/ChannelActionHandler.java)。

频道管理器未绑定数据库时，协议 CREATE 先登记频道、成员与权限，再独立保存定义；DELETE 先删除运行时登记、邀请、权限和玩家状态，再删除数据库定义。两处存储异常均只记日志，响应仍成功。管理器已绑定数据库时虽然能回滚或保留状态，协议仍将其存储异常归为普通 `NC-500`。

修复在既有频道创建和删除入口增加按次持久化参数，由同一频道锁与回滚逻辑处理。已绑定提供者优先，按次参数不重新绑定管理器。私有频道定义保存成功后才登记创建者；协议删除的存储先提交，再清理运行时。定义与邀请使用同一提供者时共用提供者删除入口，避免先单独清掉邀请再遇到定义删除失败。网络处理器删除重复保存/删除分支，并用类型化存储异常返回 `NC-510`；既有无参数及仅内存入口保留原契约。

验证：四项故障反例在原实现全部失败：兼容装配仍返回成功，绑定装配返回错误的 `NC-500`。修复后 29 项定向检查通过，0 失败、0 跳过，覆盖两种装配下创建回滚、删除保留邀请/成员/权限/玩家状态、成功仅持久化一次、管理器配置不被改写、既有频道持久化与 CONFIG 规则，以及 R40、R44 并发回归。R44 的闸门移至新重载实际调用的同一创建交接点，仍验证旧结果不得污染替代频道。日志为 `.e2e-artifacts/review14-persistence-before.log` 与 `review14-persistence-final.log`。

## 30. R46：面板玩家禁言标记读取旧快照，并暴露不可见限制

位置：[REST 玩家投影](../../StarLink/core/src/main/java/com/nova/link/api/RestApiHandler.java)、[WebSocket 玩家投影](../../StarLink/core/src/main/java/com/nova/link/websocket/WebSocketMessageHandler.java)、[共享资源策略](../../StarLink/core/src/main/java/com/nova/link/auth/PanelResourcePolicy.java)。

原两种传输都用 `PlayerState.mutes` 是否非空判断玩家被禁言，实际治理由 `MuteManager` 独立维护。玩家上线后禁言不会改变旧快照，标记仍为正常；解除或到期后，旧记录又可能让标记持续为禁言。任何非空记录还会让无权查看其频道的账号得到禁言标记，泄漏不可见限制的存在。

修复从当前管理器记录派生状态，由 `PanelResourcePolicy.hasVisibleMute` 共享有效期与资源过滤。REST 列表、详情和 WebSocket 按需请求、广播使用同一规则，网关装配同一个 `MuteManager`。保留原五参数 WebSocket 构造及未接禁言管理器的独立 REST 调用：快照仅作为这些调用的来源，同样过滤过期和不可见限制。面板适配器的旧注释同步修正，不增加第二份禁言缓存。

验证：两个原实现反例均失败，分别观察到实际禁言后的 `false` 与 VIEWER 对私有/过期快照的 `true`。修复后首轮资源治理、WebSocket 授权和共享策略共 13 项检查通过。扩充全局禁言及旧构造入口后，最终 3 项定向回归通过，0 失败、0 跳过；断言同时读取 REST 列表/详情、WebSocket 请求/广播，覆盖禁言、解除后旧快照、全局限制、私有范围、过期记录与旧构造入口。新增旧构造用例曾引用局部测试变量导致编译失败，已修正为独立网络替身后通过。日志为 `.e2e-artifacts/review15-player-mutes-before.log`、`review15-player-mutes-final.log` 和 `review15-player-mutes-exact-final.log`。

## 31. R47：保存清除较新脏标记，并发写入可能倒序完成

位置：[PlayerStateManager.saveState() / markDirty()](../../StarLink/storage/src/main/java/com/nova/link/database/PlayerStateManager.java)。

原实现用 UUID 集合记录脏状态。保存途中若再次修改玩家状态，标脏仍是同一个集合元素。旧保存返回后无条件移除 UUID，新修改便不会在下一次刷新时落库。同一玩家的两次保存也没有顺序约束：旧快照写入较晚时，会覆盖已经保存的新值。

修复让每次标脏产生新版本。保存先读取版本，再复制玩家状态并写入提供者，成功后只清除原版本。期间发生的新标脏或保存失败都会保留待保存记录。同一 UUID 的保存复用缓存原子更新边界，快照和数据库写入在其中串行执行，无需另建玩家锁表。核心与代理 lite 共用存储模块；直接保存未缓存对象、显式加载和布尔保存结果保持原契约。

验证：[PlayerStateSaveRaceTest](../../StarLink/storage/src/test/java/com/nova/link/database/PlayerStateSaveRaceTest.java) 的两项反例在原实现失败，分别得到丢失的脏标记和倒序覆盖的旧值。修复后 3 项回归及现有 `playerStateManagerRoundTrip` 属性检查通过，0 失败、0 跳过；属性检查使用 100 组输入。覆盖保存中新修改的后续刷新、并发保存顺序、失败后保留并重试，以及未缓存对象的直接保存和重新加载。并发回归使用写入闸门与实际线程等待状态，无固定延时。仅执行这些定向检查，日志为 `.e2e-artifacts/review15-player-save-before.log`、`review15-player-save-order-before.log` 和 `review15-player-save-final.log`。

## 32. R48：退出或清缓存时丢弃保存失败的数据

位置：[PlayerStateManager 的缓存与保存生命周期](../../StarLink/storage/src/main/java/com/nova/link/database/PlayerStateManager.java)。调用方包括协议退出和代理 lite 存储。

`removeFromCache(true)` 忽略保存结果，随后删除缓存及脏标记；`clearCache(true)` 也在刷新失败后清空两者。玩家重新登录便读回数据库中的旧值，后续刷新无从找回丢失的状态。直接把失败状态留在在线缓存又会使面板和私信查找继续认为玩家在线。

修复将失败写入保存在独立的内存待重试区，在线缓存照常移除。刷新在逐 UUID 原子更新边界内选择当前状态，持久化成功后按脏版本清理，返回原在线缓存归属。退出、显式加载和删除复用同一边界，避免刷新重新发布离线状态。原保存实现提取为内部 `persistState`，直接保存仍保留成功后缓存传入对象的契约。

重新登录优先取回待重试状态，避免恢复旧频道或旧偏好。显式数据库加载先提交待重试离线写入，失败返回空值并保留数据。带保存的清缓存保留失败记录；不带保存的清缓存仍显式丢弃数据。删除成功会清除待重试记录，失败保留原状态。待重试记录供当前进程中的后续刷新或重连恢复。

验证：[PlayerStateRecoveryTest](../../StarLink/storage/src/test/java/com/nova/link/database/PlayerStateRecoveryTest.java) 的 3 项存储反例在原实现失败。修复后与 R47 保存回归、现有管理器往返属性检查、[协议退出](../../StarLink/core/src/test/java/com/nova/link/network/ChannelActionHandlerLogoutTest.java)及私信离线路由共 25 项定向检查通过，0 失败、0 跳过；属性检查包含 100 组输入。新增协议回归实际注入退出保存失败，确认偏好可重试落库，重试前后玩家均未回到在线名单。日志为 `.e2e-artifacts/review16-player-recovery-before.log` 和 `review16-player-recovery-final.log`。

另执行代理 lite 的 SQLite 重启恢复和退群持久化两项既有检查，均通过。该调用方继续使用原加载与保存入口；日志为 `.e2e-artifacts/review16-lite-recovery-compatibility.log`。

## 33. R49：读取异常被当作无记录，并在频道变更后才加载玩家

位置：[PlayerStateManager.getOrCreateState()](../../StarLink/storage/src/main/java/com/nova/link/database/PlayerStateManager.java)、[ChannelActionHandler 的 JOIN、CREATE、ACCEPT 和普通 LEAVE](../../StarLink/core/src/main/java/com/nova/link/network/ChannelActionHandler.java)。

原管理器捕获读取异常后继续创建默认状态并标脏。数据库中已有的频道和私信偏好可能在恢复后的保存中被默认值覆盖。协议又在加入成员、保存新频道与授权、扣除邀请码次数后才加载玩家；普通退群也先移除成员。仅让管理器抛错会使失败响应伴随已发生的副作用。

修复将读取失败与正常无记录分开：前者抛出有原始原因的 `StateLoadException`，后者保留原创建行为。共享存储层提供 `prepareState` 和 `cachePreparedState`，让调用方先读候选而不发布在线记录，成功提交时再采用当前缓存或待重试实例。准备阶段不标脏，不引入核心模块依赖。已有缓存或待重试状态继续直接可用。

JOIN、CREATE、ACCEPT 在副作用前准备状态，原提交位置统一使用准备结果，不再于扣额或授权后读取数据库。普通 LEAVE 将加载前移至成员移除之前。协议返回既有 `NC-510` 并提示重试。原频道身份检查和锁内状态提交保留；R44 回归的闸门随实际发布入口调整，仍验证删除必须等待成员及玩家状态提交完成。代理 lite 的写入已有运行时异常处理，读取失败现在进入该失败路径，不再保存默认玩家记录。

验证：原实现的 4 项协议故障反例均错误返回成功，存储反例未抛出异常，5 项全部失败。修复后 35 项定向检查通过，0 失败、0 跳过。覆盖四种操作失败无副作用及恢复后重试、原私信偏好与频道保留、候选不提前上线及不覆盖新缓存、R47/R48 保存恢复、退出，以及频道创建失败回滚和删除并发边界。未运行全量套件。日志为 `.e2e-artifacts/review17-player-read-before.log`、`review17-player-read-final.log` 和 `review17-player-read-admission.log`。

## 34. R50：生产核心缺少运行中的玩家状态刷新

位置：[NovaLinkCore 的玩家管理器装配](../../StarLink/core/src/main/java/com/nova/link/NovaLinkCore.java)、[PlayerStateFlushService](../../StarLink/core/src/main/java/com/nova/link/database/PlayerStateFlushService.java)。

原核心仅在停止时调用 `saveAllDirty()`，退出路径另尝试单人保存。在线入群、退群或封禁清理的脏状态在运行中没有定期保存；退出保存失败后，R48 保留的数据也缺少自动重试消费者。仅靠内存待重试区不足以恢复运行中的持久化。

修复为核心装配独立刷新服务，首次及每轮结束后等待 30 秒，再调用共享管理器的刷新入口。单线程顺序执行，避免慢存储下堆积周期任务；运行时异常记录后继续后续轮次。存储操作及离线归属仍由共享层负责，调度不引入存储到核心的依赖，也不改变代理 lite 的逐次保存入口。

刷新服务接入核心回收队列，启动失败时同样停止。正常停止取消后续轮次，并按共享预算等待当前任务，最多 5 秒；预算耗尽后中断工作。核心随后保留原最终玩家保存和数据库最后关闭的顺序。

验证：新增核心回归在原实现等待 35 秒后仍未观察到运行中保存，断言失败。修复后 4 项定向检查通过，0 失败、0 跳过。真实核心按生产间隔保存在线状态，正常停止后刷新线程退出；[刷新服务回归](../../StarLink/core/src/test/java/com/nova/link/database/PlayerStateFlushServiceTest.java)通过事件闸门验证离线失败写入自动重试、重试不重新上线、异常后继续下一轮，以及过期关闭预算中断工作。另运行既有首次启动失败回滚检查，确认后台线程回到基线。日志为 `.e2e-artifacts/review18-player-flush-before.log` 和 `review18-player-flush-final.log`。

## 35. R51：逗号编码破坏频道 ID 和世界过滤边界

位置：三种 JDBC 提供者的玩家 `joined_channels` 和频道 `allowed_worlds` 读写，以及共享编码器 [JdbcStringCollections](../../StarLink/storage/src/main/java/com/nova/link/database/JdbcStringCollections.java)。

原实现把集合用逗号拼接，再按逗号拆分。频道 ID 与世界名允许包含逗号，存储恢复却会将一个元素拆成多个。玩家的当前频道仍保留完整 ID，与已加入频道集合分叉；允许世界 `lobby,staff` 恢复成 `lobby` 和 `staff`，扩大可访问范围。末尾空字符串也会丢失。三种 JDBC 提供者复制了相同逻辑。

修复在共享存储层统一 JSON 字符串数组编码，三种 JDBC 提供者均使用同一实现。schema v17 追加 `joined_channels_format` 和 `allowed_worlds_format`，旧记录默认 `0`，新保存原子写入内容及格式 `1`。读取按格式选择旧规则或 JSON，不按内容猜测。旧行保持原意，下一次保存升级格式；损坏的新格式及未知格式返回存储异常，不静默改成无过滤。Redis 和内存格式不变。

新迁移保留旧版本 SQL。MySQL 延用信息表检查和存储过程，分别检查两列，支持部分完成后的重试；PostgreSQL 使用已有的条件加列方式，SQLite 随迁移事务提交。旧数据中已经丢失的逗号边界无法推断，需按原配置重新保存。

验证：3 项 SQLite 存储反例在原实现均失败，分别观察到频道 ID 拆分、世界过滤扩大与升级后再次拆分；新增测试初版有同名变量编译错误，修正后才执行反例。修复后 12 项定向检查通过，0 失败、0 跳过。覆盖实际文件关闭重开、单条及批量加载、v16 数据升级、v1 迁移至 v17 及重复启动、损坏或未知新格式拒绝、三种方言版本及 MySQL 加列检查，以及代理 lite 的 SQLite 重启恢复。旧值恰为合法 JSON 文本的用例加强后单独通过。未运行外部 MySQL、PostgreSQL 实例。

日志为 `.e2e-artifacts/review19-string-collection-before.log`、`review19-string-collection-final.log`、`review19-string-collection-invalid.log`、`review19-string-collection-compatibility.log` 和 `review19-string-collection-legacy-final.log`。

## 36. R52：客户端异常断开遗留在线玩家与频道成员

位置：[核心断开监听](../../StarLink/core/src/main/java/com/nova/link/NovaLinkCore.java)、[ChannelActionHandler 的退出与断开清理](../../StarLink/core/src/main/java/com/nova/link/network/ChannelActionHandler.java)、[PlayerStateManager.detachState()](../../StarLink/storage/src/main/java/com/nova/link/database/PlayerStateManager.java)。

原断开监听只清理客户端权限、限流状态并发送通知，没有处理玩家缓存与频道成员。接入端异常掉线、来不及发送逐玩家退出包时，其全部玩家持续出现在在线名单，频道容量仍被占用；同名客户端重新连接后，旧玩家还可能被当作该连接上的在线目标。显式退出的成员与状态清理仅存在于协议 LEAVE 路径。

修复在当前客户端代际结束时调用统一清理入口，按 `clientId` 选择玩家，复用显式退出的成员、已加入频道及当前频道清理。私信偏好和其他持久字段保留。显式退出与异常断开均复用现有入群、封禁的逐玩家边界；批量清理在边界内再次检查缓存实例及归属，避免删除已迁往其他客户端的状态。原网络代际保护保留，旧连接迟到的断开不触发客户端清理。

共享存储层新增按实例移出缓存的 `detachState`，只登记待保存记录，无玩家数据库写入。异常断开因此不在网络回调中逐个保存；R50 刷新任务及原停机刷新消费待保存数据，失败仍可重试或重连恢复。清理成功后统一推送一次玩家列表更新。嵌入式集成服务器也装配同一清理入口；显式退出继续使用原立即保存方式。

验证：新增真实核心装配反例在原实现失败，当前代际断开后仍能读到旧玩家缓存。修复后 21 项定向检查通过，0 失败、0 跳过。覆盖同名连接接管及旧断开无副作用、当前连接的多玩家清理、其他客户端保留、退场状态落库、原退出与私信离线路由。事件闸门另确认批量快照后的跨客户端迁移仍保留成员；存储回归确认按实例移出不写数据库、失败刷新可重试且保持离线。未运行全量套件。

日志为 `.e2e-artifacts/review20-player-disconnect-before.log`、`review20-player-disconnect-final.log` 和 `review20-player-disconnect-races.log`。

## 37. R53：普通退群可修改其他客户端的玩家状态

位置：[ChannelActionHandler.handleLeave()](../../StarLink/core/src/main/java/com/nova/link/network/ChannelActionHandler.java)。

原普通 LEAVE 只检查频道作用域和成员资格，没有检查玩家当前属于哪个客户端。玩家迁移后，旧客户端仍可移除其全局频道或既有私有频道成员，随后把玩家的 `clientId` 改回自己。该路径也没有进入入群与显式退出共用的逐玩家边界。

修复将两种 LEAVE 的归属检查移入同一逐玩家边界，先检查当前在线记录，再执行退群或退出。记录属于其他客户端时返回 `NC-403`，成员、当前频道、平台及归属保持原值，不标脏，也不发送玩家更新或离开 Webhook。复用现有 `BanManager.withAdmission()`，不增加锁表。成功 JOIN 的归属迁移、受邀成员跨客户端退群，以及无缓存时先读取再清理的原入口继续可用。

验证：全局和私有频道的两个反例在原实现均失败，旧客户端退群返回成功。修复后 33 项定向检查通过，0 失败、0 跳过。新增事件闸门确认迁移先于 LEAVE 进入边界时，旧请求重新读取归属并拒绝；原退出、私有邀请、Webhook 和读取失败重试检查也通过。未运行全量套件。

日志为 `.e2e-artifacts/review21-player-departure-before.log` 和 `review21-player-departure-final.log`。

## 38. R54：跨客户端 WHO 可冒用私有成员读取名单

位置：[ChannelActionHandler.handleWho()](../../StarLink/core/src/main/java/com/nova/link/network/ChannelActionHandler.java)。

原 WHO 仅凭请求中的成员 UUID，就允许跨客户端查询私有频道。无关客户端填写已有成员的 UUID 后，可以读取完整成员名单；玩家迁移后，旧客户端也继续拥有查询能力。

修复在跨客户端的成员授权中检查当前在线玩家记录，要求成员的 `clientId` 与请求连接一致。失败返回 `NC-403`，响应不包含成员名单或人数，也不修改玩家归属。全局频道查询、本客户端绑定频道查询，以及受邀成员在当前客户端上的查询入口保留。

验证：冒用成员身份和迁移后旧客户端查询的两个反例在原实现均失败，均收到成功响应。修复后，私有邀请与 WHO 共 19 项定向检查通过，0 失败、0 跳过。迁移后的新客户端仍能读取两名成员；跨客户端加入、退群及原公开查询检查也通过。未运行全量套件。

日志为 `.e2e-artifacts/review22-private-who-before.log` 和 `review22-private-who-final.log`。

## 39. R55：WHO 按名字去重导致成员人数少计

位置：[ChannelActionHandler.handleWho()](../../StarLink/core/src/main/java/com/nova/link/network/ChannelActionHandler.java)。

原实现用忽略大小写的 `TreeSet` 保存显示名，再用集合大小作为成员数。三个不同 UUID 使用 `Alice`、`Alice` 和 `ALICE` 时，名单和人数均缩成一项。无缓存名字的 UUID 也混入同一排序集合，可能排在玩家名字前，与原接口说明的顺序不符。

修复改用列表，为每个成员保留一项。名字与无名称 UUID 分别排序，再把 UUID 接在名字后。人数取本次生成的完整列表大小，与同次响应的名单一致；协议字段和客户端格式化入口保留。

验证：同名计数的新回归在原实现得到 1，预期为 3。既有名单测试改用固定 UUID 检查完整顺序，原实现同样失败。修复后 WHO 的 8 项定向检查通过，0 失败、0 跳过。未运行全量套件。

日志为 `.e2e-artifacts/review23-who-members-before.log` 和 `review23-who-members-final.log`。

## 40. R56：INVITE 可冒用其他客户端的频道管理员

位置：[ChannelActionHandler.handleInvite()](../../StarLink/core/src/main/java/com/nova/link/network/ChannelActionHandler.java)。

原 INVITE 从请求读取邀请人 UUID 后，只检查其频道管理员权限。无关客户端填写拥有者的 UUID，即可创建邀请码；邀请人迁移或退出后，旧客户端也能继续使用保留的频道权限创建邀请。

修复在现有逐玩家边界内读取邀请人在线状态，要求 `clientId` 与请求连接一致，再执行原频道权限、接收人校验和邀请码保存。失败返回 `NC-403`，不加载离线玩家，也不写入邀请。复用 `BanManager.withAdmission()`，与迁移、退出的锁顺序一致。合法管理员迁移到其他客户端后仍可创建邀请；REST 与邀请管理器的直接入口不增加在线前提。

验证：新增反例在原实现收到成功响应，预期为 `NC-403`。修复后 16 项定向检查通过，0 失败、0 跳过。覆盖冒用、迁移后旧请求、退出后的邀请拒绝、合法新客户端邀请，以及真实 TCP 邀请与领取流程。事件闸门确认检查在逐玩家边界内执行，迁移先完成时不会创建邀请码。未运行全量套件。

日志为 `.e2e-artifacts/review24-inviter-ownership-before.log` 和 `review24-inviter-ownership-final.log`。

## 41. R57：标题只发往私有频道绑定客户端，漏掉受邀成员

位置：[MessageRouter](../../StarLink/core/src/main/java/com/nova/link/channel/MessageRouter.java)、[AdminActionHandler.handleTitle()](../../StarLink/core/src/main/java/com/nova/link/network/AdminActionHandler.java) 和[控制台标题入口](../../StarLink/core/src/main/java/com/nova/link/console/ConsoleCommandHandler.java)。

TCP 管理动作和控制台各自复制标题分发逻辑。私有频道只发往创建时的 `clientId`，跨客户端受邀成员收不到标题；创建者迁移后，原绑定客户端即使已无成员，仍会收到包。全局标题直接广播，绕过消息管线已有的客户端频道权限筛选。

修复增加共享 `MessageRouter.routeTitle()`，复用原收件人计算，并在投递前检查连接认证与存活状态。私有频道按当前有效成员所在客户端分发，同一客户端只发一次；全局频道按原频道权限筛选。两个入口保留各自身份授权和错误呈现，不再复制作用域分发。控制台的私有标题成功提示报告实际客户端数；标题内容、时长、发送人和请求 ID 沿用原数据包。

非全局标题无可投递连接时继续返回未连接错误，全局标题保留零收件人成功的原行为。独立 AdminActionHandler 仍可只装配频道管理器和网络：临时路由在缺少玩家状态装配时保留私有频道绑定客户端的原规则。投递集合表示调用了客户端发送入口，协议没有玩家显示确认。

验证：私有标题的 TCP、控制台两个反例在原实现均失败，两个成员客户端都未收到包。修复后 9 项定向检查通过，0 失败、0 跳过。覆盖跨客户端成员、同客户端去重、无成员绑定客户端不接收、全局权限与失活连接筛选、服务端离线错误、独立装配和标题字段传递。原控制台全局及服务端标题检查也通过。未运行全量套件。

日志为 `.e2e-artifacts/review25-title-routing-before.log` 和 `review25-title-routing-final.log`。

## 42. R58：六种管理动作可借用已迁移玩家的权限

位置：[ChannelActionHandler.handleOperatorAction()](../../StarLink/core/src/main/java/com/nova/link/network/ChannelActionHandler.java)。

KICK、MUTE、UNMUTE、BAN、UNBAN、DELETE 各自读取请求中的操作者 UUID，然后直接查询权限。管理员迁移后，旧客户端仍能借用其权限执行全部六种动作；离线后保留的超级管理员会话或频道管理员权限也能被使用。全局 BAN、UNBAN 没有频道检查，不能只在频道权限入口补校验。

修复合并六种动作的操作者解析与身份校验。玩家操作者必须在授权时在线，且当前 `clientId` 与请求连接一致；否则返回 `NC-403`，不进入目标解析、存储修改或通知。角色、频道范围和时长限制仍由原处理器及管理器负责。接入端控制台沿用原 `ConsoleSentinel` 协议；REST、本地控制台和直接管理器调用不增加玩家在线前提。

该入口只执行一次身份授权检查，随后按原业务边界提交。不持有操作者的封禁锁再调用目标玩家的封禁写入，避免自封重入或两个管理员互相封禁形成嵌套锁。JOIN、INVITE、LEAVE 的原逐玩家串行规则保留。

验证：六种旧客户端反例在原实现全部返回成功。修复后共 29 个不同定向检查通过，覆盖迁移及离线拒绝、当前客户端操作、全局 BAN/UNBAN、接入端控制台、自封、原权限与持久化失败处理，以及真实 TCP 删除。首轮 28 项通过，另一个旧删除夹具因使用未初始化的独立存储失败；改为复用已初始化的共同夹具后，单独重跑该项通过。未运行全量套件。

日志为 `.e2e-artifacts/review26-moderation-identity-before.log`、`review26-moderation-identity-final.log` 和 `review26-moderation-delete-final.log`。

## 43. R59：AdminAction 未验证玩家所属客户端

位置：[AdminActionHandler.handleFromClient()](../../StarLink/core/src/main/java/com/nova/link/network/AdminActionHandler.java)、[核心 TCP 注册入口](../../StarLink/core/src/main/java/com/nova/link/NovaLinkCore.java) 和 [PlayerStateManager.isOnlineOnClient()](../../StarLink/storage/src/main/java/com/nova/link/database/PlayerStateManager.java)。

原 TCP 注册入口只检查连接已经认证，随后按请求里的玩家 UUID 操作超级管理员会话，没有验证玩家当前归属。无关客户端可使用另一客户端玩家的有效会话；LOGOUT 甚至无条件撤销包内 UUID 的会话。玩家迁移或退出后，旧客户端仍可继续发送管理动作。

修复增加独立网络入口，校验连接认证、玩家 UUID 及当前在线归属后，再进入原业务处理。归属查询只读在线缓存，频道治理动作也使用这一共享入口，不把存储读取或默认玩家创建混入授权。核心与两套嵌入式服务器改为装配同一网络入口，删除重复连接检查。原单参数构造和 `handle()` 重载保持调用方管理身份的用法。

超级管理员会话继续按 UUID 维护，不绑定首次认证客户端。玩家成功 JOIN 迁移后，新客户端可继续使用已有会话，旧客户端返回 `NC-403`；离线记录也不能凭未过期会话通过网络入口。AUTH 同样先验证玩家归属，再检查原密码和 IP 限流。控制台哨兵跳过在线玩家检查，仍执行原管理员会话授权。

验证：新增真实 TCP 反例在原实现允许无关客户端为另一客户端的玩家执行 AUTH。最终 21 个不同定向检查通过，0 失败、0 跳过。覆盖全部 AdminAction 的跨客户端拒绝及请求 ID、当前客户端认证与注销、迁移后会话可用、退出后拒绝、原治理操作和标题直接调用。另以真实核心装配确认跨客户端注销被拒绝且会话保留；独立入口检查确认 IP 封禁及恢复、控制台与直接调用契约。未运行全量套件。

日志为 `.e2e-artifacts/review27-admin-client-before.log`、`review27-admin-client-final.log` 和 `review27-admin-core-final.log`。

## 44. R60：管理员会话失效后仍能监控和发言

位置：[SpyManager](../../StarLink/core/src/main/java/com/nova/link/spy/SpyManager.java) 和 [AdminActionHandler.handleLogout()](../../StarLink/core/src/main/java/com/nova/link/network/AdminActionHandler.java)。

原实现只在启动监控时检查超级管理员会话。已启动的监控继续按索引收取消息，监控发言只检查监控记录与发送开关；会话撤销或过期后，两种操作仍成功。显式 LOGOUT 也只撤销管理员会话，留下全部监控记录。

新增 5 个反例在原实现全部失败，覆盖撤销、过期后的接收与发送，以及注销后的残留记录。修复复用原会话检查与批量停止入口，在启动、转发和监控发言前检查授权。检测到失效时清除全部监控及反向索引，发言返回 `NC-403`。显式 LOGOUT 同步清理；空闲会话到期后，下次监控操作触发清理，重新认证不会恢复已清理的记录。

验证：13 个定向检查通过，0 失败、0 跳过。覆盖上述反例、有效会话收发、清理后重新认证、控制台监控，以及真实 TCP 的启动、注销与跨客户端拒绝。未运行全量套件。

日志为 `.e2e-artifacts/review28-spy-authority-before.log` 和 `review28-spy-authority-final.log`。

## 45. R61：监控副本修改原消息占位符，并中断不可变消息的转发

位置：[SpyManager.createSpyMessage()](../../StarLink/core/src/main/java/com/nova/link/spy/SpyManager.java)。

原实现把原消息的占位符 Map 直接赋给监控副本，再写入 `_spy` 和 `_spy_source_client`。可修改的 Map 因此混入监控标记，两个消息后续也会互相改写占位符。传入 `Map.of()` 时，添加标记抛出 `UnsupportedOperationException`，中断消息管线，后续 WebSocket 转发无法执行。

修复只在创建监控副本时复制 Map，再添加监控标记，保留共享消息类型原有 setter 的引用赋值语义。新增两个反例使用实际消息管线和 `SpyManager`，分别复现原消息污染与不可变 Map 异常；原实现均失败。修复后普通投递、监控投递和面板转发均完成，原消息与监控副本的占位符也可独立修改。

验证：消息管线副通道的 10 项定向检查通过，0 失败、0 跳过。覆盖两种 Map、标记及来源保留、双向修改隔离，以及既有禁言、全局和服务端频道的监控与面板调用规则。未运行全量套件。

日志为 `.e2e-artifacts/review29-spy-message-before.log` 和 `review29-spy-message-final.log`。

## 46. R62：监控来源选择未生效，未认证连接仍可接收

位置：[SpyManager.forwardToSpies()](../../StarLink/core/src/main/java/com/nova/link/spy/SpyManager.java)。

启动监控虽保存 `targetClientId`，转发却只读取频道反向索引。同一全局频道上选择 alpha、beta 的管理员都会收到所有来源的消息，包括未知来源。接收连接只需存活，即使尚未认证，也会收到消息。两个新增反例均在原实现失败。

修复在转发时读取管理员的当前监控记录，跳过已停止的记录，并比较所选来源与消息 `clientId`。指定来源时，未知来源也跳过；空值和空字符串继续表示全部来源。投递前检查连接认证及存活状态，沿用原管理员会话检查与失效清理。

验证：6 项定向检查通过，0 失败、0 跳过。覆盖三名管理员的独立来源选择、未知来源、空来源选择、连接认证恢复、正常收发和原消息占位符隔离。测试使用实际管理器和消息管线，网络连接由夹具提供；未运行全量套件。

日志为 `.e2e-artifacts/review30-spy-forwarding-before.log` 和 `review30-spy-forwarding-final.log`。

## 47. R63：生产监控无法定位管理员，接入端缺少定向显示

位置：[SpyManager](../../StarLink/core/src/main/java/com/nova/link/spy/SpyManager.java)、[核心装配](../../StarLink/core/src/main/java/com/nova/link/NovaLinkCore.java) 和各接入端的消息处理入口。

原转发只扫描 `ClientConnection.superAdminUuid`，生产 AUTH 却没有写入该字段，正常管理员无法收到监控消息。这个连接级单值也无法表示同一客户端的多名管理员。即使补上字段，原普通聊天包没有目标 UUID，接入端只按当前频道显示，无法保证仅向监控管理员展示。

修复由核心及嵌入式服务器向 `SpyManager` 注入在线玩家管理器。每次转发按管理员当前归属查询客户端，离线跳过；迁移后继续使用同一管理员会话和监控记录，从新客户端接收。原三参数构造继续支持独立装配的连接字段定位，不给这些调用增加玩家缓存前提。

新增独立服务端包 `SpyMessagePacket`（`0x18`），载荷为目标管理员 UUID 与原聊天载荷，使用原字符串和 Map 限制。后端不依赖 `client-core`；Java 适配在 `client-core` 共用显示格式，各自保留主线程或区域线程调度。PHP、Python、C++ 同步注册和解码，只向指定在线玩家显示，不触发频道广播或私信回复记录。

代理 relay 注册新包并保留后端范围，Bukkit 的直连与代理下发共用目标筛选。监控包沿用原消息请求 ID，代理与 Bukkit 的显示协调支持新类型；监控内容仅向目标玩家提供，不向其他玩家发显示协调副本。旧接入端忽略未知类型，使用监控需同步更新后端和接入端。

验证：22 个不同定向检查通过，0 失败、0 跳过，未运行全量套件。覆盖真实核心装配、两名管理员、迁移和离线，以及实际 Bukkit/代理显示入口。共享字节样本在 Java、PHP、Python、C++ 间往返一致；PHP/Python 还验证目标筛选，Bukkit 验证频道及回复对象保持不变，并覆盖代理双副本与仅后端副本的显示去重。Folia、Nukkit、PNX、Sponge 和模组公共层均编译通过。

C++ 协议测试与网络文件编译通过。首次手工编译聊天文件时漏传服务器 SDK 的 `LL_PLAT_S` 等定义，导致 `MolangScriptArgData` 复制赋值错误；MSVC 的事件名称静态断言也不代表项目指定的 clang-cl 构建失败。补齐服务器头文件路径和编译定义后，`ChatInterceptor.cpp` 使用 clang-cl 编译通过，见 `.e2e-artifacts/msvctest/review32-chat-compile.log`。完整插件链接及真实游戏环境尚未验证，发布前仍需完成这两项检查。本轮没有修改外部 SDK。

证据见 `.e2e-artifacts/review31-spy-delivery-java.log`、`review31-spy-coordination-final.log`、`review31-spy-core-final.log`、`review31-spy-adapters-compile.log`、`review31-spy-delivery-python.log`、`review31-spy-delivery-php.log` 和 `msvctest/review31-*.log`。补跑核心检查时，长期运行的 Gradle 守护进程曾因 javac 推断耗尽堆；已调整新增断言，并使用新进程复核。

## 48. R64：原生测试编译失败后仍运行旧程序，并报告成功

位置：[LeviLamina Gradle 测试任务](../../NovaChat/Bedrock/levilamina/build.gradle)。

`test` 任务设置 `ignoreExitValue = true`。显式编译测试目标失败后，`doLast` 仍调用 `xmake run`；旧测试程序返回成功即可让 Gradle 报告成功。缺少 xmake 时已有 `onlyIf` 跳过任务，无需靠忽略错误处理。

修复移除该设置，让编译失败直接终止任务，并清理相邻的过期说明。测试运行失败仍沿用原异常报告。

隔离复现复制真实构建脚本，用替身 xmake 记录调用。原实现收到编译退出码 23 后仍调用 `run`，最终显示 `BUILD SUCCESSFUL`；修复后 Gradle 返回失败，且未调用 `run`。正常编译路径仍执行测试并返回成功。两项修复后流程通过，未运行全量套件，也未用替身结果代替插件构建验证。

证据见 `.e2e-artifacts/review32-wrapper-before.log`、`review32-wrapper-fixed-failure.log` 和 `review32-wrapper-fixed-success.log`，命令记录位于 `review32-gradle-wrapper/*.trace`。

## 49. R65：原生工具探测和测试运行会因输出管道阻塞

位置：[LeviLamina](../../NovaChat/Bedrock/levilamina/build.gradle)、[PMMP](../../NovaChat/Bedrock/pmmp/build.gradle)、[Endstone](../../NovaChat/Bedrock/endstone/build.gradle) 的 Gradle 构建入口。

LeviLamina、PMMP 的工具探测按顺序读取标准输出和错误输出，Endstone 则直接等待进程结束。子进程先写满错误管道时，父进程仍等待标准输出关闭或子进程退出，双方互相等待。LeviLamina 的测试运行也有同样问题。

修复沿用 PMMP 安装流程已有的 `ProcessBuilder.redirectErrorStream(true)`，合并两条输出管道，再持续读取。工具探测仍按退出码判定可用性，测试运行继续输出日志并报告失败退出码。

隔离复现使用真实构建脚本与替身工具，向错误输出写入 256 KiB。原实现的三个探测入口和测试运行均阻塞，直到替身的 8 秒看门狗结束子进程才返回。修复后三个探测均成功；测试运行读完全部输出并成功结束，返回错误码 29 的路径也正确使 Gradle 失败。共 5 项定向检查通过，未运行全量套件。

证据见 `.e2e-artifacts/review33-probes-before.log`、`review33-run-before.log`、`review33-probes-fixed.log`、`review33-run-fixed.log` 和 `review33-run-failure-fixed.log`，命令与输出完成记录位于 `review33-process-pipes/*.trace`。

## 50. R66：重复启动监控后仍使用旧来源

位置：[SpyManager.startSpying](../../StarLink/core/src/main/java/com/nova/link/spy/SpyManager.java)、[SpySession](../../StarLink/core/src/main/java/com/nova/link/spy/SpySession.java)。

会话按管理员 UUID 和频道 ID 判等。重复启动时，新会话无法加入集合，接口却仍返回成功；从来源 alpha 改为 beta，或取消来源筛选，都继续使用旧选择。

修复更新已有会话的来源，保留起始时间和发言开关。同一管理员的启动更新通过已有映射的 `compute` 串行执行；来源使用 `volatile`，转发线程可读取更新。会话身份和频道反向索引不变。

两个新增反例在原实现均失败。修复后 6 项定向检查通过，覆盖切换来源、恢复全部来源、禁止发言状态保留、其他管理员独立筛选、认证要求和正常收发。未运行全量套件。

证据见 `.e2e-artifacts/review34-spy-retarget-before.log` 和 `review34-spy-retarget-final.log`。

## 51. R67：控制面路由掩盖策略拒绝，REST 错报频道不存在

位置：[MessageRouter](../../StarLink/core/src/main/java/com/nova/link/channel/MessageRouter.java)、[RoutingResult](../../StarLink/core/src/main/java/com/nova/link/channel/RoutingResult.java)、[RestApiHandler](../../StarLink/core/src/main/java/com/nova/link/api/RestApiHandler.java)。

按频道 ID 发送的辅助入口先把流水线结果转为接收集合，再无条件构造成功结果。禁言、封禁和慢速模式的拒绝原因因此丢失，内部调用方误判成功；REST 则因接收数为 0 统一返回 `404`，提示错误的故障方向。

修复直接读取流水线结果，通过 `RoutingResult` 返回错误代码及原因。禁言、封禁对应 `NC-403`，慢速模式对应 `NC-429`，并包含剩余等待时间。有效消息无人在线时，业务辅助入口仍返回成功和接收数 0；REST 继续按原约定对此返回 `404`。

治理、活动和消息发送共用同一 HTTP 错误码映射，移除活动接口重复的转换分支。旧路由失败工厂入口保留 `NC-404` 语义，其他路由入口的签名和处理规则不变。[API 参考](../reference/admin-api.md) 已同步更新。

三个 HTTP 反例在原实现均错误返回 `404`。修复后 8 项定向检查通过，覆盖三种拒绝、缺失频道、无人在线的业务及 HTTP 约定、正常服务器频道投递，以及复用转换后的活动失败、限额响应。未运行全量套件。

证据见 `.e2e-artifacts/review35-routing-result-before.log`、`review35-routing-result-final.log` 和 `review35-routing-result-shared-status.log`。

## 52. R68：可信发送入口绕过协议内容上限

位置：[MessagePipeline.processForChannel](../../StarLink/core/src/main/java/com/nova/link/channel/MessagePipeline.java)、[MessageRouter.routeMessage](../../StarLink/core/src/main/java/com/nova/link/channel/MessageRouter.java)。

TCP 入口会检查空内容和 2048 UTF-8 字节上限，但已解析频道的 REST、控制台和活动入口直接进入后半段流水线。它们可以绕过长度限制；过滤器把敏感词替换成更长文本时，原先也不会重新检查，最终可能发送超过协议字段上限的消息。

修复提取共享内容校验，在两条流水线入口执行；敏感词过滤后再次检查编码长度。可信入口仍跳过游戏玩家的成员和客户端归属校验，保持管理消息语义。超限通过 `RoutingResult` 返回 `NC-400`，REST 返回 `400`，不会触发接收端发送、审计或活动成功回调。

原实现的 2049 字节管理消息可发送，过滤后变长的消息也可继续发送。修复后 6 项定向检查通过，覆盖可信入口的空/超限校验、普通 TCP 入口、过滤后超限和 REST `400` 响应。API 文档已补充限制和错误码；未运行全量套件。

证据见 `.e2e-artifacts/review36-trusted-payload-final2.log`。

## 53. R69：过滤规则热更新会改变正在匹配的列表

位置：[SensitiveWordFilter](../../StarLink/core/src/main/java/com/nova/link/filter/SensitiveWordFilter.java)。

正则规则使用同步列表，但过滤和检测通过未加锁的迭代器遍历。管理端清空并重填列表时，正在匹配的消息可能漏过旧规则或抛出并发修改异常。单词规则的空值检查和实际匹配还会分别读取同一字段；热更新清空字段后，后一次读取可能出现空指针。

修复把正则规则改为不可变列表，通过 `volatile` 引用整批发布。增删规则、批量替换、重置和清空共用写入边界，读取无需持有更新锁；非法正则在发布前编译失败，保留旧规则。单词匹配使用一次本地快照，词表写入及编译也串行执行，避免较旧编译结果覆盖较新更新。

4 项定向检查通过，覆盖过滤与检测各自和热更新重叠时完成旧规则匹配、后续使用新规则、非法批次保持原规则，以及增删与重置的既有行为。并发检查用门闩控制匹配顺序，不依赖反复执行或延时碰撞。未运行全量套件。

证据见 `.e2e-artifacts/review38-filter-reload-final2.log`。
