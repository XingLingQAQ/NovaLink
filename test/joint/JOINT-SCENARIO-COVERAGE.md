# NovaChat JOINT Cross-Platform E2E — Scenario Coverage

**Last reviewed**: 2026-10-18
**Previous review**: 2026-08-08 (the matrix below was rebuilt from source + the
2026-08-08 evidence; anything that could not be re-confirmed is marked UNKNOWN
rather than carried forward as PASS/FAIL).

## How to read this document

The 2026-08-08 run produced evidence for a **two-platform, proxy-less**
topology (Purpur + Nukkit). The harness has since been rewritten:

* paths come from `test/versions.lock.ps1` instead of hardcoded `D:\...` paths,
* platform combinations are parameterised (`-Platforms`),
* rounds rotate one leg at a time (proxy + java + mod + bedrock, then swap the
  bedrock leg, then swap the java leg),
* **a Velocity proxy is part of the default topology**,
* cross-server assertions are proven from the **receiver's** recording.

Two columns follow from that:

| Column | Meaning |
|---|---|
| **Status (source)** | What the code says today, re-read on 2026-10-18. This is a code-level verdict, not a test result. |
| **Status (harness)** | Whether the rewritten harness exercises it, and in which topology. |

`UNKNOWN` means exactly that: the 2026-08-08 evidence predates the current code
and no run has been made since. Do not read UNKNOWN as PASS.

## What the harness now asserts (the primary path)

Every round proves two cross-process deliveries, each in the **receiver's**
results file:

1. `java-to-bedrock` — the Java-leg bot sends a message carrying a per-round
   token; the Bedrock-leg bot's recording must contain that token.
2. `bedrock-to-java` — the mirror direction.

A round PASSes only when both directions are observed. A bot's own belief that
it sent something is never sufficient evidence.

## Scenario matrix

| # | Scenario | Status (source) | Status (harness) | Notes |
|---|----------|-----------------|------------------|-------|
| E12 | REPLACE-mode cross-server chat (baseline) | Implemented (`ChatInterceptor` cancels the event and forwards) | **ASSERTED** every round | The core assertion. Both directions. |
| D9 | @mention cross-server delivery | Implemented (`MessagePipeline` emits `MentionPacket` when a `MentionResolver` is wired; default enabled) | NOT ASSERTED | The bot records raw traffic, so a mention *could* be asserted; no plan step does it yet. |
| D10 | Mention tab-complete | **NOT-IMPL (bot limit)** | NOT ASSERTED | mineflayer / bedrock-protocol expose no tab-complete API. Unchanged from the 2026-08-08 finding. |
| A1 | Create private channel + join | Create implemented; join requires the generated `NC-XXXX` id (`PrivateChannelManager.generateUniqueId`) — joining by display name was never supported | NOT ASSERTED | The old matrix recorded "join by name fails" as a bug; the code shows this is by design: the channel id IS the generated id. The harness would have to parse the id out of the create response (a `handoffSet` step is built for exactly this) — not wired yet. |
| A2 | Invite across servers | **FIXED in source**: `InviteCommand` no longer returns early when `parsePlayer()` misses; it sends `targetName` so the backend resolves cross-server (`resolveTargetId` name fallback) | NOT ASSERTED | The 2026-08-08 `BUG-J1` finding is stale. Two remaining gaps prevent a clean assertion: (a) the invite is a private-channel feature and A1's id plumbing is not wired; (b) `handleInvite` requires `permissionManager.isChannelAdmin`, so the inviter must own the channel. |
| A3 | Channel deletion propagates | **NOT-IMPL (client)**: the backend handles `ChannelAction.DELETE`, but bukkit registers no `delete` subcommand | NOT ASSERTED | Unchanged: `NovaChatCommand` has no `delete` entry. |
| B4 | Cross-server mute enforcement | Backend `handleMute` → `MuteManager.mutePlayer`; `MessagePipeline` drops with `SENDER_MUTED`. `MuteCommand` sends `targetName`, and console uses `CONSOLE_SENTINEL_UUID` which `requireModerationPermission` bypasses | NOT ASSERTED | The 2026-08-08 `BUG-J2` had two layers; both look addressed in source (name fallback + console sentinel). **Not re-verified by a run** → the *behaviour* is UNKNOWN even though the code path exists. |
| B5 | Cross-server kick | Same shape as B4 (`handleKick` + console sentinel + name fallback) | NOT ASSERTED | Same caveat as B4: `BUG-J3` looks addressed in source, behaviour UNKNOWN without a run. |
| B6 | Mute on a private channel | Depends on B4 + A1 | NOT ASSERTED | Blocked on A1 id plumbing. |
| C7 | Announce cross-server | **Gated on a super-admin SESSION** (`AdminActionHandler` checks `hasSuperAdminSession(playerId)`), and console has no session | NOT ASSERTED | The harness now **generates a super-admin entry** in `novalink.yml` (the old harness left `super-admins: []`, which is what made `BUG-J4` "by design"). A player running `/nc auth <password>` can now reach it; RCON/console cannot. `BUG-J4` is therefore **partially addressed**: the config gap is a harness problem (fixed), the console gap is a product decision. |
| C8 | Title cross-server | Same gating as C7 | NOT ASSERTED | Same as C7. |
| E11 | HYBRID mode keeps vanilla chat local | Implemented (both `ChatInterceptor` and Velocity's `ChatListener` return early in HYBRID) | NOT ASSERTED | The harness configures every leg with `replace_vanilla: true`, so HYBRID is not exercised. |
| F13 | Permission denied (NC-403) | Implemented | NOT ASSERTED | Needs a plan step that runs an admin command as a non-admin. |
| G14 | Bot reconnect mid-session | Plugin reconnect logic exists (`reconnect-delay`) | NOT ASSERTED | The joint bot has no reconnect step; the old finding covered a bot-side reconnect only. |
| G15 | ConfigSync mid-session | Implemented (backend pushes `ConfigSyncPacket`) | NOT ASSERTED | Would need a channel created mid-run, which needs A1's id plumbing. |
| H16 | Channel isolation (different channels) | Implemented (`MessagePipeline.calculateRecipients` selects by membership) | NOT ASSERTED | Blocked on A1: both bots need to be on different, joinable channels. |
| H17 | Cross-server `/nc who` | **Implemented**: `WhoCommandService.isMemberListingSupported()` returns `true` and `handleWho` returns `members` / `memberCount` / `displayName` | NOT ASSERTED | The 2026-08-08 "degraded / needs backend support" note is stale. |
| N1 | **Proxy forwards Java↔Java** | Implemented (Velocity plugin is a first-class client) | **TOPOLOGY-ONLY** | The default topology puts a Velocity proxy in front of the Java leg, but the round asserts Java↔Bedrock, not proxy↔proxy. A same-proxy Java↔Java assertion needs two Java legs, which the round builder does not produce (it allows at most one Java + one Mod leg). |
| N2 | **Mod (Fabric) leg loads and connects** | Mod builds; the loader shim needs a self-contained jar (`test/bin/fabric-fatjar.init.gradle`) and the `server` entrypoint in `fabric.mod.json` is broken | **ASSERTED (load only)** | Verified 2026-10-18: with the E2E jar the server reaches `Done (…)` and logs `Server started, initializing NovaChat connection…`. Whether messages then route is covered by the round assertion. |

## Product defects confirmed against source on 2026-10-18

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

## Harness limitations (current)

1. **Two bots per round.** The plan is a Java bot + a Bedrock bot. Scenarios
   needing a third non-joining observer cannot be expressed.
2. **One Java + one Mod leg per round.** The round builder caps each kind at
   one, which is what keeps the process ceiling at 4. That also rules out a
   same-proxy Java↔Java assertion (N1).
3. **Tab-complete (D10)** stays untestable: neither bot library exposes it.
4. **No mid-run backend restart.** The backend is started once per round and
   torn down at the end.
5. **Proxy downstreams are Java+Mod only.** Velocity does not front Bedrock
   servers; the Bedrock bot always joins its server directly.

## Evidence layout (rewritten harness)

```
.e2e-artifacts/joint/<runId>/
  joint-<runId>.log               orchestrator log
  round-<n>/
    backend/{novalink.yml,stdout.log,stderr.log}
    <platform>/{server.stdout.log,server.stderr.log,<properties>}
    <proxy>/proxy.stdout.log
    bots/
      plan-java.json / plan-bedrock.json    exact steps handed to each bot
      java.results.json / bedrock.results.json
      java.stdout.log / bedrock.stdout.log
  joint-summary.json              per-round status + every assertion with its token
```

## Summary of this review

| Bucket | Count | Items |
|---|---|---|
| Asserted by the rewritten harness | 2 | E12, N2 (load) |
| Implemented in source, not asserted | 9 | A2, B4, B5, C7, C8, E11, F13, G15, H17 |
| Blocked on private-channel id plumbing | 4 | A1, B6, H16, D9 (mention is independent but unwired) |
| Not implemented (client) | 1 | A3 |
| Not testable with the current bots | 1 | D10 |
| Topology-only / needs a second Java leg | 1 | N1 |
| Behaviour explicitly UNKNOWN (source exists, no run) | — | B4, B5, and every "not asserted" row above |

The honest headline: the harness now **proves** the cross-server chat path
end-to-end and can be pointed at any platform combination, but most of the
moderation/admin/channel-lifecycle scenarios are only *reachable* — they are not
yet asserted, and the 2026-08-08 verdicts for them must not be quoted as
current results.
