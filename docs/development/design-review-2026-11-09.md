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
