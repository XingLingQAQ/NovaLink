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
