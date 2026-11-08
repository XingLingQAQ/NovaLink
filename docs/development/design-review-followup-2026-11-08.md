# 第二轮设计、功能与测试审查

本轮确认的 R10—R17 共 8 项问题均已实现修复，并加入正式回归测试。[上一轮 R01—R09](design-review-2026-11-08.md)的修复已推送。第 1—8 节保留修复前的证据和验收条件，当前实现与验证记录见第 9 节。

审查日期：2026-11-08。业务源码基线：`128ddd796da92bcf24cc36aa2cf1b69702f133ee`，已推送到 `origin/master`。随后提交 `ab5796efb1d7f448684f20774f64e5564c35d428` 修复了英文 CI 环境下的测试语言问题，业务源码未变。

## 1. 结论与处理顺序

| 编号 | 优先级 | 确认的问题 | 直接观察 |
| --- | --- | --- | --- |
| R10 | P1 | 已申诉案件可以改派，从而绕过自审限制。 | Alice 最初收到 `NC-403`，改派给 Bob 后，Alice 审核成功。 |
| R11 | P1 | 旧会话请求会使用新账号凭据重试。 | Alice 的 DELETE 晚到返回 401，随后使用 Bob 刷新的 token 再次发送。 |
| R12 | P2 | 已完成申诉仍能覆写审核结论，案件也未结束申诉状态。 | `APPROVED` 被另一人改为 `DENIED`，案件仍为 `APPEALED`。 |
| R13 | P2 | 新建申诉与案件状态更新分开写入，部分失败会产生重复申诉。 | 第二笔写入失败后首次仍成功返回；再次提交产生 2 条待审申诉。 |
| R14 | P2 | 活动投递受限或抛错时，接口仍报告激活成功。 | 2 次激活均成功，实际只投递 1 次；未投递活动进入 `ACTIVE`，无法再次激活。 |
| R15 | P2 | 活动启动恢复遗漏结束时间与到期任务。 | 过期计划恢复后发出消息；过期活动仍为 `ACTIVE`，任务数为 0。 |
| R16 | P2 | 设置以外的面板数据仍有异步覆盖。 | 晚到 REST 覆盖较新 WebSocket 频道；旧申诉列表响应覆盖当前列表。 |
| R17 | P2 | 线程池关停测试依赖固定等待，已造成远端 CI 失败。 | 首轮期望执行 1 个任务，实际执行 6 个；相同测试源码后续运行通过。 |

修复按审核与会话隔离、申诉事务、活动投递与恢复、面板快照及关停测试同步完成，明细见第 9 节。

## 2. 复现方法与适用范围

本轮使用 `code-refactor-review` 检查调用关系与状态所有权；使用 `frontend-api-integration-patterns`、`frontend-state-and-interaction-design` 检查会话、请求交错及恢复路径；按 `zh-tech-writing` 记录结论。

后端探针重新编译当前 `ModerationManager`、`CampaignManager` 和 `MemoryProvider`。Java 业务对象、实际调度器与内存存储执行了 7 个观察场景。写入失败由存储子类定点注入，活动恢复由预置记录触发真实恢复方法。这里没有使用外部数据库或重启实服进程。

面板探针通过 jsdom 挂载实际 hook 与组件，运行了 3 个观察场景。REST、WebSocket 连接和传输响应由替身控制，业务状态逻辑与认证服务执行当前源码。跨账号反例使用虚构凭据，没有访问真实账号或远程接口。

这些探针断言的是缺陷确实出现。它们执行成功不表示业务已修复，也不替代修复后的回归测试。

## 3. 审核与会话隔离

### R10：改派已申诉案件可以绕过自审限制

位置：[ModerationManager.assignCase()](../../StarLink/core/src/main/java/com/nova/link/moderation/ModerationManager.java)、[reviewAppeal()](../../StarLink/core/src/main/java/com/nova/link/moderation/ModerationManager.java)和 [REST 指派入口](../../StarLink/core/src/main/java/com/nova/link/api/RestApiHandler.java)。

`assignCase()` 只拒绝 `CLOSED`，允许把 `RESOLVED`、`APPEALED` 改为 `UNDER_REVIEW`，同时改写 `assignedModerator`。`reviewAppeal()` 又只将审核人与案件当前的 `assignedModerator` 比较。REST 的指派和审核都允许 ADMIN 操作。

复现顺序：

1. Alice 接手案件、完成处理，案件收到一条申诉。
2. Alice 尝试审核，得到 `NC-403`。
3. Alice 调用指派入口，将该案件改派给 Bob。
4. Alice 再次审核同一申诉，结果为 `APPROVED`；案件变为 `UNDER_REVIEW`。

原本应受隔离约束的操作人可以通过合法 API 组合避开检查。最小修复是限制指派的合法前置状态，保留处理完成时的责任身份，审核时依据该身份判断。状态判断与写入需要同一存储条件，避免并发改派再次绕过。

验收：`RESOLVED`、`APPEALED`、`CLOSED` 不能通过普通指派退回处理中；原处理人仍被拒绝；不同审核人的正常操作保留。

### R11：旧请求可能以新账号身份重新执行

位置：[api.js 的刷新重试](../../Panel/web/src/services/api.js)和[动态构造认证头](../../Panel/web/src/services/api.js)。

请求发出时没有绑定会话身份。首次返回 401 后，`withTokenRefresh()` 调用当前全局认证服务；重试的 `runner()` 再读取当前 token。认证服务虽然保护了刷新请求自身的会话代际，却不知道这个刷新源于另一个会话的旧业务请求。

复现中，Alice 发出 `DELETE /api/channels/old-session-target`。在它返回前，执行真实的本地退出并登录 Bob。旧 DELETE 返回 401 后，刷新使用的是 Bob 的 refresh token，重发使用 Bob 的新 access token，旧调用最终返回成功。

这使旧账号的操作意图归到新账号，可能在切换账号后执行删除或治理操作。后端仍按 Bob 的权限鉴权，问题发生在客户端重放了旧会话的操作。

修复应为业务请求捕获稳定的登录会话标识。收到响应、开始刷新和实际重试前都检查标识；退出、换账号或换后端后，旧请求结束为取消。正常 token 轮换仍属于同一登录会话，应保持可用。`getMetrics()` 共用这段刷新逻辑，调整时需一并检查。

验收：同会话的 token 过期仍可刷新一次；跨账号、退出或换后端后，旧 GET 与修改请求均不再刷新或重发。

## 4. 申诉状态与存储

### R12：完成审核后仍能直接覆写决定

位置：[ModerationManager.reviewAppeal()](../../StarLink/core/src/main/java/com/nova/link/moderation/ModerationManager.java)、[MemoryProvider.updateAppealReview()](../../StarLink/storage/src/main/java/com/nova/link/database/MemoryProvider.java)和 [JDBC 更新](../../StarLink/storage/src/main/java/com/nova/link/database/AbstractJdbcProvider.java)。

业务入口仅校验目标状态不是 `PENDING`，没有检查申诉当前状态。存储更新也只使用申诉 ID 作为条件。Bob 先批准申诉后，Charlie 仍可再次提交拒绝，覆盖最终决定、审核人和备注。两次调用均成功。

审核完成后，关联案件仍为 `APPEALED`。这与 [CaseStatus](../../StarLink/storage/src/main/java/com/nova/link/moderation/CaseStatus.java) 对待审申诉和结案的含义不一致。面板也[只为 PENDING 提供审核按钮](../../Panel/web/src/components/dashboard/AppealQueue.jsx)，无法从现有交互解释后续改判。

应明确允许审核的源状态，以及批准、拒绝、升级分别对案件状态的影响。存储采用带源状态或版本条件的更新；已完成决定的纠正需要明确操作，不能沿用无条件审核写入。已有审计事件继续保留。

验收：两个竞争审核只有一个生效；重复审核不会覆盖原决定；案件按最终结果离开待审状态；升级后的继续处理规则有独立断言。

### R13：申诉和案件状态更新缺少共同事务

位置：[ModerationManager.createAppeal()](../../StarLink/core/src/main/java/com/nova/link/moderation/ModerationManager.java)。

代码先保存申诉，再另行保存 `APPEALED` 案件。第二笔写入抛错时只记日志，仍记录成功并返回申诉。探针让这笔案件写入失败后，观察到申诉已存在、案件仍为 `RESOLVED`。再次提交通过了前置状态检查，最终留下 2 条 `PENDING` 申诉。

应在现有 `DatabaseProvider` 边界内提供申诉创建与条件状态转换的一次操作。JDBC 层已有[事务处理模式](../../StarLink/storage/src/main/java/com/nova/link/database/AbstractJdbcProvider.java)，可沿用连接、回滚和 auto-commit 恢复约定。内存实现也须原子完成两者，不能仅在 HTTP 层加锁。

验收：任一写入失败时，两项都不生效；同一案件并发或重复申请不会产生多条待审申诉；成功后案件与申诉状态对应。

## 5. 活动投递与时间窗口

### R14：未投递的活动也被报告为成功

位置：[CampaignManager.doActivate()](../../StarLink/core/src/main/java/com/nova/link/announcement/CampaignManager.java)和 [deliverOnce()](../../StarLink/core/src/main/java/com/nova/link/announcement/CampaignManager.java)。

`doActivate()` 先进入 `ACTIVE`，再执行投递，最后无论投递结果如何都返回成功。限流时 `deliverOnce()` 返回的 `NC-429` 只用于日志。发送回调抛出异常时，`deliverOnce()` 自身又吞掉错误并返回 `Delivered`。

将频道限额设为每小时 1 次，连续激活两个活动，结果是两次成功、一次实际发送。第二个活动已为 `ACTIVE`，再次激活被状态机拒绝。另一个注入发送异常的场景也返回成功并停在 `ACTIVE`。

应保留“状态提交”和“实际投递”的结果，向 API 与面板提供可判断的失败或部分完成信息。未投递活动需要明确恢复入口，不能因成功状态失去再次发送的途径。发送异常不应变成 `Delivered`。

验收：达到限额时调用方可识别 `NC-429`；发送失败有实际错误结果；恢复操作不会重复发送已成功投递的活动。

### R15：恢复活动没有落实结束时间

位置：[CampaignManager.loadPersistedCampaigns()](../../StarLink/core/src/main/java/com/nova/link/announcement/CampaignManager.java)、[armActivation()](../../StarLink/core/src/main/java/com/nova/link/announcement/CampaignManager.java)和 [doActivate()](../../StarLink/core/src/main/java/com/nova/link/announcement/CampaignManager.java)。

恢复时仅按状态排除 `EXPIRED`、`REVOKED`，不按 `endAt` 判断时间窗口。已有 `ACTIVE` 记录不会重建到期任务。`SCHEDULED` 的开始时间已经过去时，恢复路径立即激活；若结束时间也已过去，激活既发送消息，也不安排到期任务。

两个观察场景分别得到：

- `endAt` 已过的 `ACTIVE` 记录恢复后仍为 `ACTIVE`，任务数为 0。
- 开始与结束时间都已过的 `SCHEDULED` 记录恢复后发出消息，状态为 `ACTIVE`。

源码注释将漏掉到期任务称为可接受的行为，但这与[活动时间窗口和 EXPIRED 状态](../../StarLink/storage/src/main/java/com/nova/link/announcement/CampaignStatus.java)的含义冲突。应修正恢复行为，并同步该注释。

验收：已过期记录直接进入 `EXPIRED`，不补发；未过期的 `ACTIVE` 只恢复到期任务，不再次投递；尚未开始的计划保留激活与结束时间。

## 6. 面板状态仍有两个覆盖入口

### R16：REST 与 WebSocket、旧列表请求分别直接写状态

位置：[useDashboardData.fetchAllData()](../../Panel/web/src/hooks/useDashboardData.js)、[useWsOrchestration 的频道处理](../../Panel/web/src/hooks/useWsOrchestration.js)和 [AppealQueue.fetchPage()](../../Panel/web/src/components/dashboard/AppealQueue.jsx)。

频道和玩家状态由两个 hook 分别调用同一 setter。WebSocket 服务的 revision 检查只覆盖 WebSocket 消息，不能约束晚到的 REST。实际 hook 挂载后，先采用 revision 100 的 WebSocket 频道 `new-channel`，再完成初始 REST，最终列表变成旧的 `old-channel`。

申诉列表则没有请求代际或清理保护。在 Strict Mode 的两次初始请求中，第二次先返回 `current-appeal`，第一次随后返回 `old-appeal`，页面最终只展示旧条目。上一轮 `MessageHistory` 已处理同类生命周期，但该规则没有覆盖申诉组件。

修复应让每个实体域统一决定是否采用快照，覆盖初始加载、手动刷新、操作后刷新和实时更新。现有 [settingsStore](../../Panel/web/src/hooks/settingsStore.js) 提供了集中管理的项目内实例；频道与玩家没有相同版本契约，不能直接照搬设置的 revision 比较。列表读取则沿用 [MessageHistory 的请求代际处理](../../Panel/web/src/components/dashboard/MessageHistory.jsx)，保留当前错误和加载状态的归属。

验收：较新 WebSocket 更新不会被先前 REST 读覆盖；旧请求的成功、失败和 finally 均不能更改当前列表状态；实际挂载测试覆盖 Strict Mode、切换筛选与卸载。

## 7. 远端 CI 的失败与恢复

### 已修复：英文主机导致中文断言失败

首次推送触发的 [42481904507](https://github.com/XingLingQAQ/NovaLink/actions/runs/42481904507) 中，面板测试为 136 项通过、41 项失败。静态组件断言使用中文文案，测试入口却让 Node.js 根据主机语言自动检测；英文 runner 因而采用英文翻译。

在本机强制 `navigator.language = en-US`，原样复现了相同的 41 项失败。`ab5796ef` 在测试入口固定中文默认语言后，相同输入下 177 项全部通过，0 项跳过。修改只位于 `Panel/web/_test/register.mjs`。

后续 [42482614387](https://github.com/XingLingQAQ/NovaLink/actions/runs/42482614387) 的 4 个 job 均已通过：面板 lint、测试和生产构建，Windows 离线检查，编排路径检查，以及全平台 Gradle 构建与测试。

### R17：关停测试没有同步到关停开始

位置：[ExecutorSplitTest.shutdownCancelsPendingTasks()](../../StarLink/core/src/test/java/com/nova/link/network/ExecutorSplitTest.java)和 [ServerNetworkHandler.shutdown()](../../StarLink/core/src/main/java/com/nova/link/network/ServerNetworkHandler.java)。

首轮远端核心测试运行 1,418 项，只有这项失败：期望处理计数为 1，实际为 6。测试启动关停线程后固定等待 200 ms，随后释放阻塞任务。它没有确认连接已退休，或关停线程已进入执行器收尾阶段。源码未改变的下一次远端运行通过。

从源码推断，如果 CI 上关停线程在 200 ms 后仍未获得执行机会，释放 blocker 会让其后的 5 个队列任务先执行，恰好产生计数 6。这个推断能解释现有失败，但本轮没有录制 CI 的逐线程调度。

测试还将关停描述为立即 `shutdownNow()`；实际实现先退休连接，再调用 `shutdown()` 等待，超过 5 s 才强制结束。应按真实契约拆开断言：使用同步条件验证退休后排队包不再进入处理器；单独保持 blocker 直到中断，验证强制结束及无遗留任务。不要延长固定 sleep 来掩盖时序。

验收：关停线程延迟启动、正常退出和强制结束三种场景的断言都由事件驱动，且每条路径完成线程及连接清理。

## 8. 本地证据与下一轮边界

证据目录：`.e2e-artifacts/design-review-followup-20261108/`。它受 Git 忽略；本报告保留了重建反例所需的输入、调用顺序与结果。

| 文件 | 内容 |
| --- | --- |
| `WorkflowReviewProbe.java`、`backend-results.json`、`backend.log` | 审核隔离、重复审核、申诉部分写入、投递限流／异常和过期恢复。 |
| `panel-probes.mjs`、`panel-results.json`、`panel-probes.log` | 实际 hook／组件的两类旧响应覆盖、真实认证服务下的跨账号重试。 |
| `english-navigator.mjs`、`panel-english-before.log`、`panel-english-after.log` | 相同英文主机输入下，41 项失败到 177 项通过的对照。 |

首次远端失败日志另存于 `.e2e-artifacts/review-followup-ci-failed.log`，其中包含 R17 的失败用例、断言结果及源码行号。

初次审查未启动浏览器或 Minecraft 实服，也未执行外部数据库矩阵。原探针用于确认缺陷；修复后的正式验证见下节。

## 9. 修复结果与正式验证

| 编号 | 当前实现 | 正式回归入口 |
| --- | --- | --- |
| R10 | 仅 `OPEN`、`UNDER_REVIEW` 可指派；处理完成后保留责任人，未指派案件由处理人补齐；状态更新使用条件写入。 | `ModerationWorkflowTest`、`ModerationTransactionTest` |
| R11 | 业务请求捕获登录会话和连接代际；响应、刷新、重试及读取响应体后检查身份。正常 token 轮换保持同一会话，旧刷新失败也不能清除新账号。 | `api.session.test.js` |
| R12 | 已完成决定返回 `NC-409`；最终审核与案件 `CLOSED` 同步提交。`ESCALATED` 保持 `APPEALED`，仅高级审核可继续；原处理人仍被拒绝。面板提供对应入口并显示实际拒绝原因。 | `ModerationWorkflowTest`、`ModerationTransactionTest`、`RestApiHandlerTest`、`dashboard.interaction.test.js` |
| R13 | 在 `DatabaseProvider` 内提供申诉与案件的原子操作；内存同步提交，JDBC 使用同一连接事务。 | `ModerationTransactionTest`：内存／SQLite 并发申请与审核，触发器注入插入／审核失败并验证回滚。 |
| R14 | 回调或路由失败返回错误，限流返回 `NC-429`；成功后才进入 `ACTIVE` 并消耗配额。保留原活动供重试，并发激活只发送一次。 | `CampaignManagerTest`、`CampaignRecoveryTest`、`RestApiHandlerCampaignTest` |
| R15 | 恢复时先执行结束时间判断；过期直接 `EXPIRED`，活跃活动只重建到期任务，计划活动恢复激活任务。 | `CampaignRecoveryTest`：实际调度器、到期事件及无重复投递断言。 |
| R16 | 频道／玩家使用共享快照采用规则，覆盖初始、手动及操作后刷新；WebSocket 更新使先前读取失效。申诉列表的成功、失败及 finally 均受请求代际约束，清理时使旧请求失效。 | `dashboardSnapshots.test.js`、`dashboard.interaction.test.js`：实际挂载 hooks／组件、筛选切换及 Strict Mode 清理路径。 |
| R17 | 使用连接退休事件同步关停开始；分别验证正常释放和超时强制中断，清理线程及连接。 | `ExecutorSplitTest`：延迟启动、优雅退出与强制结束，移除固定等待。 |

升级审核通过 REST 根据有效 `SUPER_ADMIN` 角色授权；原五参数业务方法仍可审核 `PENDING`，需要继续处理 `ESCALATED` 的可信调用方使用带高级审核标志的重载。新错误码与活动重试方法见 [Admin REST API](../reference/admin-api.md)。

全量后端回归还暴露了两个原有测试输入问题，已同步修正：配置草稿审计断言按目标操作筛选事件，避免依赖同毫秒记录的顺序；userinfo 安全用例改用确定的公网／回环 IP 字面量，双向验证实际 host，不再根据外部域名解析猜测 guard 的判定。

本地 core／storage 全量测试通过，补充的竞争审核、定时投递失败重试和 HTTP 流程测试通过；核心最新运行共 1,430 项，0 失败、0 跳过。面板 197 项测试全部通过，lint 为 0 错误、1 个既有警告，生产构建通过。日志位于 `.e2e-artifacts/review2-backend-final.log`、`review2-acceptance-final.log`、`review2-panel-complete.log`、`review2-panel-lint-complete.log` 和 `review2-panel-build-complete.log`。

存储事务在内存和 SQLite 上验证；MySQL、PostgreSQL 复用 JDBC 实现，本轮未连接外部数据库执行矩阵。活动测试验证真实调度与可控发送回调，核心接线检查路由结果；没有 Minecraft 玩家端送达确认协议。未启动浏览器或实服。
