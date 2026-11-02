# NovaChat JOINT Cross-Platform E2E — Scenario Coverage

**Integration review**: 2026-11-01. Main-thread wiring is implemented.
**Real acceptance this round**: the corrected Lite and native-PNX Relay
selections passed on both proxies. Historical runs, unit tests, syntax
checks, fixtures and diagnostics do not substitute for current game evidence.
**Historical snapshots retained below**: 2026-08-08 and 2026-10-18/21.

## Current selection and evidence contract

All E2E workflows are manual (`workflow_dispatch`). The daily E2E schedule
has been removed. `ScenarioGroups` / `scenario_groups` defaults to `core` and
always includes it. The other groups require explicit selection. Required
IDs come from `scenarios/registry.ps1`; the runner and `assert-joint-ci.ps1`
must receive matching selections. Unknown groups, incompatible topologies,
missing required IDs and diagnostic-only evidence cannot satisfy acceptance.

| Group | Required topology | Required ID families and observations |
|---|---|---|
| `core` | Velocity/Bungee relay with Bedrock, or Velocity/Bungee Lite + Bukkit | `round<N>/iso/*`, non-default selection/current-channel/no-rejoin `round<N>/business/reconnect-*`, plus mode-specific delivery, link and attribution IDs |
| `business` | Relay or Lite; three ordinary Java bots, two channels, real operator callback | `round<N>/extended-business/*`, `round<N>/pm/*`, `round<N>/channel-mute/*`: DM/reply privacy, offline refusal, mute scope and restoration |
| `lite-moderation` | Lite only; actual Velocity or Bungee proxy console stdin; target + observer bots | `round<N>/lite-moderation/*`: permission denial, mute/unmute, kick/rejoin, ban/unban, channel scope, Minecraft connection retained after channel kick |
| `recovery` | Relay only; real Java + Bedrock clients, selected `local` channel and backend restart callbacks | `round<N>/recovery/*`: changed PID, fresh membership and active-channel delivery after restart/reconnect, with no new player JOIN |
| `proxy-switch` | Relay only; Bukkit primary + real `bukkit2`, `SecondaryJavaPlatform bukkit` | `round<N>/proxy-switch/*`: player commands, destination probes, switch/return, fresh who, exactly-once deliveries and SERVER-channel link isolation |
| `channel-lifecycle` | Relay only; Bukkit primary + `bukkit2`, distinct direct client ports | `round<N>/channel-lifecycle/*`: generated ID, invite/accept, exact membership, cross-server delivery, outsider refusal and cleanup |
| `social-contract` | Relay or Lite; sender, target and observer through the proxy | `round<N>/social-contract/*`: ignore/unignore, live exclusion controls, exact who sets/counts and list IDs/scopes |
| `custom-effects` | Relay only; Bukkit operator plus real target/outsider recordings | `round<N>/custom-effects/*`: non-default title timings, title/subtitle delivery and target isolation |
| `completion` | Relay or Lite; two proxy players and scoped real permission fixture | `round<N>/completion/*`: real protocol candidates, prefix matching and channel/ignore/permission exclusion/restoration |

The `business` group extends the basic `business/reconnect-*` assertions that
are already required by `core`; the similar names do not mean the extended
group ran by default. A console command being written is only an action, not
proof that moderation worked. Negative observations need a completed receiver
recording, an armed observation window and positive delivery controls.

Bungee Lite has its own real proxy + Bukkit entry. Lite moderation is driven
through the proxy's real console; the old claim that Lite management has no
execution entry is obsolete. Relay `proxy-switch` explicitly starts two
Bukkit processes with separate ports/RCON/link identities. This option
requires exactly one Bukkit primary and no Mod leg; the secondary uses
`ModPort` / `ModRconPort`. The default single-Java topology does not imply
a switch occurred.

Current `pnx` means native PowerNukkitX 3.0.6, API 3.x, Bedrock 1.26.60.
Nukkit is a separate artifact. Old Nukkit-hosted PNX compatibility evidence
does not validate the native runtime. Nukkit, Sponge and PMMP now have fixed
SHA-256 pins; PINNED and AUTO fallback fail before cache/download on an
invalid pin. AUTO without an upstream hash remains smoke evidence only.

The separate manual workflow is
[`.github/workflows/e2e-environment.yml`](../../.github/workflows/e2e-environment.yml).
It is outside the joint group list and runs actual Bukkit + Fabric against
a TLS backend. Required
case IDs are in `test/environment/cases.psd1`: `ENV-TLS-TRUSTED-*`,
`ENV-TLS-UNTRUSTED-*`, `ENV-MYSQL-*`, `ENV-POSTGRES-*`. Trusted bidirectional
control, explicit untrusted-CA rejection and SQL row identity across a real
backend restart must be recorded. Run
[41272492807](https://github.com/XingLingQAQ/NovaLink/actions/runs/41272492807)
at `787ad13` passed trusted TLS (3/3), untrusted-CA rejection (4/4) and
PostgreSQL (5/5). MySQL subsequently passed 5/5 after migration repair in run
[41274536675](https://github.com/XingLingQAQ/NovaLink/actions/runs/41274536675)
at `6e5a2c7`.

See [manual commands and prerequisites](../README.md#joint-manual-e2e-coverage).

## Historical review context

The 2026-08-08 run used a proxy-less Purpur + Nukkit topology. The old matrix
below also includes findings from 2026-10-18/21. Its source and harness status
columns describe those snapshots; NOT ASSERTED and topology limits there
must not override the current group table. Product findings need fresh
source review and real results after the present integration.

## Relay core delivery contract

Relay `core` requires two cross-process deliveries, each in the **receiver's**
results file:

1. `java-to-bedrock` — the Java-leg bot sends a message carrying a per-round
   token; the Bedrock-leg bot's recording must contain that token.
2. `bedrock-to-java` — the mirror direction.

Both directions and every other required assertion for the selected groups
must be observed. A bot's own belief that it sent something is insufficient
evidence. Run 41274540721 established both directions for the recorded
native-PNX Relay selections.

## Historical scenario matrix (2026-10-18/21)

| # | Scenario | Status (source) | Status (harness) | Notes |
|---|----------|-----------------|------------------|-------|
| E12 | REPLACE-mode cross-server chat (baseline) | Implemented (`ChatInterceptor` cancels the event and forwards) | **ASSERTED** every round | The core assertion. Both directions. |
| D9 | @mention cross-server delivery | Implemented (`MessagePipeline` emits `MentionPacket` when a `MentionResolver` is wired; default enabled) | NOT ASSERTED | The bot records raw traffic, so a mention *could* be asserted; no plan step does it yet. |
| D10 | Mention tab-complete | **NOT-IMPL (bot limit)** | NOT ASSERTED | mineflayer / bedrock-protocol expose no tab-complete API. Unchanged from the 2026-08-08 finding. |
| A1 | Create private channel + join | Create implemented; join requires the generated `NC-XXXX` id (`PrivateChannelManager.generateUniqueId`) — joining by display name was never supported | NOT ASSERTED | The old matrix recorded "join by name fails" as a bug; the code shows this is by design: the channel id IS the generated id. The harness would have to parse the id out of the create response (a `handoffSet` step is built for exactly this) — not wired yet. |
| A2 | Invite across servers | **FIXED in source**: `InviteCommand` no longer returns early when `parsePlayer()` misses; it sends `targetName` so the backend resolves cross-server (`resolveTargetId` name fallback) | NOT ASSERTED | The 2026-08-08 `BUG-J1` finding is stale. Two remaining gaps prevent a clean assertion: (a) the invite is a private-channel feature and A1's id plumbing is not wired; (b) `handleInvite` requires `permissionManager.isChannelAdmin`, so the inviter must own the channel. |
| A3 | Channel deletion propagates | **NOT-IMPL (client)**: the backend handles `ChannelAction.DELETE`, but bukkit registers no `delete` subcommand | NOT ASSERTED | Unchanged: `NovaChatCommand` has no `delete` entry. |
| B4 | Cross-server mute enforcement | Backend `handleMute` → `MuteManager.mutePlayer`; `MessagePipeline` drops with `SENDER_MUTED`. `MuteCommand` sends `targetName`, and console uses `CONSOLE_SENTINEL_UUID` which `requireModerationPermission` bypasses | **ASSERTED on relay** (run 20261021-033336) | Console-issued `/nc mute`, driven over the bukkit server's RCON. The muted target's line appears in NEITHER observer's recording and `MuteManager` logs the apply; a pre-mute control line from the same sender was delivered, so "only the mute suppressed it" is proven. Negative half is gating. |
| B5 | Cross-server kick | `handleKick` removes channel membership only; `MessagePipeline` has NO membership check on the send path | **ASSERTED — FAILS** (run 20261021-033336) | The kick DOES remove the membership (`/nc who global` after the kick lists neither the target nor, on the relay link, the target is gone) but the kicked player's next line still reaches BOTH observers: `MessagePipeline.processForChannel` gates on mute/ban only. A kick therefore has no effect on speech. Tracked as a product finding, not a harness gap. |
| B6 | Mute on a private channel | Depends on B4 + A1 | NOT ASSERTED | Blocked on A1 id plumbing. |
| C7 | Announce cross-server | **Gated on a super-admin SESSION** (`AdminActionHandler` checks `hasSuperAdminSession(playerId)`), and console has no session | NOT ASSERTED | The harness now **generates a super-admin entry** in `novalink.yml` (the old harness left `super-admins: []`, which is what made `BUG-J4` "by design"). A player running `/nc auth <password>` can now reach it; RCON/console cannot. `BUG-J4` is therefore **partially addressed**: the config gap is a harness problem (fixed), the console gap is a product decision. |
| C8 | Title cross-server | Same gating as C7 | NOT ASSERTED | Same as C7. |
| E11 | HYBRID mode keeps vanilla chat local | Implemented (both `ChatInterceptor` and Velocity's `ChatListener` return early in HYBRID) | NOT ASSERTED | The harness configures every leg with `replace_vanilla: true`, so HYBRID is not exercised. |
| F13 | Permission denied (NC-403) | Implemented in `requireModerationPermission`, but a PLAYER can never be authorized (see "Moderation authority" below) | **ASSERTED (effect half) + DIAGNOSTIC (refusal is invisible)** | An op bot with no backend session issues `/nc mute`; the authority applies nothing and the target is still heard (both halves gated). The refusal itself never reaches the issuing player: `MuteCommand` sets no `playerId` extra, which `ChannelResponseTracker.track` keys on, so `ChannelResponseDispatcher.handle` returns before rendering. |
| G14 | Bot reconnect mid-session | Plugin reconnect logic exists (`reconnect-delay`) | NOT ASSERTED | The joint bot has no reconnect step; the old finding covered a bot-side reconnect only. |
| G15 | ConfigSync mid-session | Implemented (backend pushes `ConfigSyncPacket`) | NOT ASSERTED | Would need a channel created mid-run, which needs A1's id plumbing. |
| H16 | Channel isolation (different channels) | Implemented (`MessagePipeline.calculateRecipients` selects by membership) | NOT ASSERTED | Blocked on A1: both bots need to be on different, joinable channels. |
| H17 | Cross-server `/nc who` | **Implemented**: `WhoCommandService.isMemberListingSupported()` returns `true` and `handleWho` returns `members` / `memberCount` / `displayName` | NOT ASSERTED | The 2026-08-08 "degraded / needs backend support" note is stale. |
| N1 | **Proxy forwards Java↔Java** | Implemented (Velocity plugin is a first-class client) | **TOPOLOGY-ONLY** | The default topology puts a Velocity proxy in front of the Java leg, but the round asserts Java↔Bedrock, not proxy↔proxy. A same-proxy Java↔Java assertion needs two Java legs, which the round builder does not produce (it allows at most one Java + one Mod leg). |
| N2 | **Mod (Fabric) leg loads and connects** | Mod builds; the loader shim needs a self-contained jar (`test/bin/fabric-fatjar.init.gradle`) and the `server` entrypoint in `fabric.mod.json` is broken | **ASSERTED (load only)** | Verified 2026-10-18: with the E2E jar the server reaches `Done (…)` and logs `Server started, initializing NovaChat connection…`. Whether messages then route is covered by the round assertion. |

## Historical moderation authority on relay (2026-10-21 source review)

`ChannelActionHandler.requireModerationPermission` admits four levels, and on a
relay topology only one of them is reachable from this harness:

| Level | How it is obtained | Reachable from a player here? |
|---|---|---|
| SUPER_ADMIN | `AdminActionHandler.handleAuth` → `/nc auth <password>` | **No.** `AuthCommand` gates on `novachat.auth`, whose `plugin.yml` default is `false`, and Bukkit's `PermissionDefault.FALSE` is false even for ops. No permissions plugin exists in the harness (or in the shipped server configs), so the node can never be granted. |
| CLIENT_ADMIN | `PermissionManager.registerClientAdmin` | **No.** It has no production call site (only its own definition and tests). |
| CHANNEL_ADMIN | `handleCreate` / `bootstrapPrivateChannelAdmins` | Only for a PRIVATE channel the player created, and the `CHANNEL_ADMIN` branch refuses a GLOBAL channel. |
| PLAYER | — | Always NC-403. |

The console is the one authorized operator: `ConsoleSentinel.isConsole(...)`
returns before any permission lookup, `AbstractModerationManager
.resolveOperatorLevel` mirrors the bypass, and the moderation commands are
`isPlayerOnly() == false` with an explicit console branch
(`CONSOLE_SENTINEL_UUID` + `console=true`). The relay harness therefore drives
mute/kick/ban/unban through the bukkit server's own RCON, and uses a player bot
only for the permission-denied case.

The historical relay run reported three moderation defects below. Their
current status remains subject to source review and real revalidation.

### 2026-10-21 — moderation defects the relay run proved

* **Kick does not stop speech (run 20261021-033336).** `handleKick` removes the
  membership (`channelManager.removeMember`), but
  `MessagePipeline.processForChannel` gates on mute (`SENDER_MUTED`) and ban
  (`SENDER_BANNED`) only — there is no membership check on the send path. The
  kicked player's next line reached BOTH observers. `/nc who global` after the
  kick confirmed the membership really was gone, so this is not "the kick was
  never applied".
* **A ban does not block a rejoin (run 20261021-033336).** `handleJoin` never
  consults `BanManager`; the only `isBanned` call sites are `MessagePipeline`
  and `ItemDisplayHandler`. With the ban in force (03:40:22–03:41:14 in the
  backend log), the target's `/nc join global` answered
  `Success: Joined channel global` at 03:40:56 and it was never refused. The ban
  still silences them afterwards at stage 4b, which is why `ban-silences`
  passes while `ban-blocks-rejoin` fails.
* **A moderation refusal is invisible to the player who issued it.**
  `MuteCommand`/`KickCommand`/`BanCommand` add `operatorId`, `targetId`,
  `operatorName` and `duration` extras but never `playerId`, while
  `ChannelResponseTracker.track` keys its pending entry on `extra[playerId]`.
  The tracked entry therefore has a null player id and
  `ChannelResponseDispatcher.handle` returns at
  `pending == null || pending.getPlayerId() == null` before any adapter
  callback. A player who runs a moderation command cannot tell an accepted
  command from a refused one.

## Historical product findings from the 2026-10-18 source review

### BUG-J1 — cross-server invite: **STALE, appears fixed**
`InviteCommand` now sends the packet with `targetName` when the local
`parsePlayer()` lookup misses, and the backend's `resolveTargetId` resolves by
name across all connected clients. The 2026-08-08 rejection (`NC-404 | 玩家 … 不存在`)
came from the old early-return. **Not re-tested by a run.**

### BUG-J2 / BUG-J3 — cross-server mute / kick: **source path present, behaviour UNKNOWN**
Both commands send `targetName`; `requireModerationPermission` lets the console
sentinel through; `resolveTargetId` falls back to a name scan. Every link the
2026-08-08 report identified as broken now exists. There is **no run evidence**
after those changes, so the enforcement behaviour is UNKNOWN, not PASS.

### BUG-J4 — announce/title require super-admin auth: **partially addressed**
Two separate issues were conflated in the old report:
* the **harness** never configured `super-admins`, so no session could ever be
  created — fixed: the orchestrator now writes a super-admin entry whose UUID is
  the offline-mode UUID of the Java bot.
* the **backend** requires an authenticated *session*, and the console sentinel
  has none, so RCON-issued announce/title still fail. That is a product
  decision (console is not a player), not a regression.

### NEW (2026-10-18) — Fabric `server` entrypoint is invalid
`NovaChat/MOD/fabric/src/main/resources/fabric.mod.json` lists
`com.nova.chat.mod.fabric.NovaChatFabric` under both `main` and `server`.
Fabric requires the `server` entrypoint to implement
`net.fabricmc.api.DedicatedServerModInitializer`; the class implements only
`ModInitializer`, so a dedicated server dies at boot with
`LanguageAdapterException: … cannot be cast to …DedicatedServerModInitializer!`.
`test/bin/fabric-fatjar.init.gradle` drops the broken entrypoint **only when the
class genuinely lacks the interface**, so the workaround disappears as soon as
the descriptor is fixed. Reported here rather than fixed in `src/**` (out of
this change's scope).

## Remaining coverage boundaries

1. Default `core` does not imply the optional groups ran. Extended business
   uses three bot roles; switch uses mover + two anchored observers.
2. A second Java server requires explicit `SecondaryJavaPlatform bukkit`.
   The normal single-Java round cannot establish destination switching.
3. Mention completion requires the explicit `completion` group and actual
   native suggestion packets; a synthetic or request-only client is separate evidence.
4. Backend restart is explicit `recovery`, distinct from core Java reconnect.
5. Proxy downstreams are Java servers; Bedrock connects directly. The separate
   `test/bedrock-transfer` entry tests native PNX transfer; the Java switch
   group does not establish Bedrock transfer results.
6. Lifecycle, ignore, exact list semantics and custom title timings require
   their separate explicit groups and fresh recordings; `business` alone
   does not establish them.
7. Fixtures, `PlanOnly` output and diagnostic assertions are separate from
   real acceptance evidence, including fetch integrity diagnostics.

## Evidence layout (rewritten harness)

```
.e2e-artifacts/joint/<runId>/
  joint-<runId>.log               orchestrator log
  round-<n>/
    backend/{novalink.yml,stdout.log,stderr.log}
    <platform>/{server.stdout.log,server.stderr.log,<properties>}
    bukkit2/                    optional real secondary Bukkit process
    <proxy>/proxy.stdout.log
    bots/
      plan-java.json / plan-bedrock.json    exact steps handed to each bot
      java.results.json / bedrock.results.json
      java.stdout.log / bedrock.stdout.log
      <selected-group>/         plans, per-role results and driver evidence
  joint-summary.json              per-round status + every assertion with its token
```

## Recorded results and coverage limits

Local Bungee Lite run `20261102-205413` on 2026-11-02 passed the complete
`core,completion` selection: **81/81** gating assertions, all 31 registry IDs,
non-default Java reconnect and all 13 native completion controls. Evidence is
under the task's temporary `novalink-joint-acceptance-20261102/20261102-205413/`.
Earlier failed local attempts remain failures. The reconnect query now polls
read-only `/nc who` within a fixed deadline; it never repairs membership.

Runs 41274531514 and 41274540721 at `6e5a2c7` passed the complete selected
groups. Earlier failed runs remain failures; passing rows inside them do not
turn those workflows into passes.

| Selection | Recorded evidence and limits |
|---|---|
| Native-PNX Relay with two Bukkit servers | Run 41274540721: 174/174 gating rows per proxy; native PNX marker and every selected required group ID verified. Nukkit was not selected in this run |
| Velocity Lite and Bungee Lite `core` | Run 41274531514 at `6e5a2c7`: complete `core,business,lite-moderation` selections passed, Velocity 148/148 and Bungee 147/147 |
| `business` in both modes/proxies | Runs 41274531514 and 41274540721 passed three-recorder DM/reply controls, permission denial and channel-scoped mute/unmute |
| Velocity/Bungee `lite-moderation` | Run 41274531514 at `6e5a2c7`: the complete `core,business,lite-moderation` selections passed on both proxies, including all 18 required moderation rows each |
| `recovery` | Run 41274540721: 13/13 required rows per proxy; new backend PID, fresh authentication/JWT, member lists and post-restart/reconnect bidirectional delivery |
| Dual-Bukkit `proxy-switch` | Same run: 23/23 required rows per proxy, including destination probes, movement/return, exact delivery and SERVER-channel link isolation |
| Independent TLS/SQL environment suite | Run 41272492807 recorded 3/3 trusted TLS, 4/4 untrusted-CA rejection and 5/5 PostgreSQL; run 41274536675 recorded 5/5 MySQL after migration repair |

The recorded Bungee run used `fixtureNativeAPI`, through the console-only
fixture and the proxy's real connect API; it did not validate `/server`.
The current driver is `player-command` on both proxies. Bungee now installs
the matching pinned upstream `cmd_server` module. Its three command receipt
IDs are required in addition to destination probes and link isolation.

The new lifecycle, social and custom-effects groups, non-default recovery
and Bungee command path have not yet had real-server acceptance in this
revision. Fabric also now ships its runtime dependencies in the standard
production jar, with the invalid duplicate entrypoint removed. The E2E task
copies that unchanged jar instead of rewriting it; previous Fabric game
evidence used the older patched test package.

Local TLS acceptance on 2026-11-02 used the unchanged standard Fabric package:
trusted CA 3/3 and unrelated CA 4/4 passed with real Bukkit/Fabric clients.
Evidence is in the task's temporary `nl-env-tls-final-20261102` directory,
runs `20261102-202056-42b240ad-tls-trusted` and
`20261102-203435-4fcc604b-tls-untrusted`. This replaces the package-validation
gap above; it does not extend the older SQL game results to schema 16 or Redis.

For joint runs retain `.e2e-artifacts/joint/<runId>/` (or the explicit
`JointRoot`), `joint-summary.json`, the selected group list, assertion IDs,
exact plans and relevant process logs. Environment public evidence is under
`.e2e-artifacts/environment/<runId>-<case>/public/`; keep credentials, private
keys and unfiltered logs outside uploaded artifacts. Record failures and
prerequisite blockers explicitly; neither an unavailable environment nor a
missing artifact is a successful acceptance result.
