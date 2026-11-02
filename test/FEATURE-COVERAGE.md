# NovaChat Real-Server E2E Feature Coverage Manifest

**Integration review**: 2026-11-02. The selected joint and environment cases have real passing evidence.
**Historical evidence date**: 2026-08-08. The original seven-platform matrix
and `.e2e/` evidence below are retained as a dated snapshot. `TESTED` there
means the old script exercised a path, sometimes only its refusal response;
it does not validate the present binaries or the new scenario groups.

## Current manual acceptance scope

All E2E workflows require manual dispatch. The daily E2E schedule is removed.
The default joint selection is `core`; other groups require explicit
`ScenarioGroups` / `scenario_groups` selection. Required evidence and the
latest observed results are recorded separately below.

| Entry | Selected coverage | Required evidence |
|---|---|---|
| `core` | Receiver chat, isolation controls and Java reconnect with non-default `local` selected | Fresh current-channel query and bidirectional delivery without a repair JOIN, plus every required registry ID |
| `business` | Three-bot DM/reply privacy, offline refusal and channel-scoped mute/unmute | Receiver delivery and exclusion with live positive controls; sender confirmation alone is insufficient |
| `lite-moderation` | Real Velocity/Bungee Lite console commands; ordinary player permission denial, mute/kick/ban and inverse actions | Both bot recordings, channel scope, kick keeps Minecraft connected and ban blocks rejoin |
| `recovery` | Relay backend process restart and bounded Bedrock reconnect with `local` selected | Changed PID, fresh membership/active channel and bidirectional delivery, without another player JOIN |
| `proxy-switch` | Relay with real Bukkit + `bukkit2`, selected by `SecondaryJavaPlatform bukkit` | Player `/server`, destination probes, switch/return, exactly-once chat, membership and SERVER-channel isolation |
| `channel-lifecycle` | Relay; direct Bukkit + `bukkit2` clients | Actual created channel ID, cross-server invite/accept, exact membership, delivery, outsider exclusion and cleanup |
| `social-contract` | Relay or Lite; three ordinary proxy players | Ignore/unignore controls, exact who members/counts and list IDs/scopes across leave/rejoin |
| `custom-effects` | Relay; Bukkit admin action and receiver/outsider controls | Actual title/subtitle and exact configured timings, with non-target exclusion |
| `completion` | Relay or Lite; native Java chat candidates, two proxy players and live permission provider | Recorded protocol candidate updates, channel/ignore/permission controls and restoration |
| Native PNX | Official PowerNukkitX 3.0.6, `org.powernukkitx` API 3.x, Bedrock 1.26.60 | Actual native server/plugin enable and receiver-recorded game traffic; old Nukkit-hosted runs do not qualify |
| Native Bedrock transfer | Two native PNX 3.0.6 servers, no proxy | Exact TransferPacket before every new connection, destination spawn/probe, stable player identity, source removal, backend ownership migration and bidirectional delivery |
| Independent `e2e-environment` | Manual TLS trusted/untrusted and MySQL/PostgreSQL/Redis cases using actual Bukkit + Fabric | Certificate-chain rejection controls, game messages, stored row identity across backend restart and fresh post-restart delivery |

### Recorded results

Local native Bedrock transfer passed **22/22** required IDs on 2026-11-02,
including the independent verifier. Evidence is under
`.e2e-artifacts/bedrock-transfer/acceptance-final-baaa0b02/` (`summary.json`,
raw events, bot recordings and both PNX logs). This does not cover a remote
workflow run or other native Bedrock implementations.

The current standard Fabric production package also passed local TLS game
acceptance on 2026-11-02: trusted CA **3/3**, unrelated CA **4/4**, with actual
Bukkit/Fabric clients. Evidence: `nl-env-tls-final-20261102/` under the task's
system temporary directory, runs `20261102-202056-42b240ad-tls-trusted` and
`20261102-203435-4fcc604b-tls-untrusted`, each with a public
`environment-summary.json`. The second case reused the first case's runtime
and world cache; its configuration, plans and verdicts were fresh. These TLS
cases use memory storage and do not validate SQL/Redis game persistence.

Bungee Lite `core,completion` passed **81/81** local gating assertions in run
`20261102-205413`, including all 31 required registry IDs. This proves automatic
non-default Java reconnect and all 13 native chat completion controls on the
pinned Java protocol. The earlier `20261102-204320` run remains FAIL; its early
channel query race was fixed with bounded read-only polling, without a repair
JOIN. Evidence: `novalink-joint-acceptance-20261102/20261102-205413/` under the
task's system temporary directory.

PNX backend recovery passed a separate complete `core,recovery` selection,
**98/98** local gating assertions and all 39 required IDs, in run
`20261102-215039`. The PNX client now waits for restored membership responses
before selecting the active channel, rather than relying on JOIN reply order.
Both backend restart and native Bedrock reconnect retained `local` and delivered
plain chat in both directions. The earlier failed `20261102-211749` run is
retained; its reversed JOIN responses are the regression evidence.

Bungee Relay `core,custom-effects` then passed **93/93** gating assertions and
all 34 required IDs in run `20261102-220041`. Real authenticated `/nc title`
commands produced exactly one title, subtitle and timing packet per intended
receiver; non-targets received none. Bukkit now shares proxy ownership and
per-recipient deduplication across both incoming transports. The real RCON
boundary check returned complete replies in 0.7-1.1 seconds.

Run `20261102-210038` separately recorded all required `business` (8/8),
`completion` (13/13), `social-contract` (23/23), `channel-lifecycle` (20/20)
and real player-command `proxy-switch` (26/26) IDs as passing. That combined
run remains **FAIL** because of its title and colored-banner failures; the
later targeted passes do not rewrite it. A fresh combined remote workflow,
Velocity's new completion game path, and schema-16 SQL/Redis game/TLS cases
remain unverified. Push/remote CI is currently blocked by the GitHub connection.

The external game runs below predate these changes. Their results do not
extend automatically to new packages or scenario selections.

Directed invitations now persist their recipient UUID and authorize that
recipient's PRIVATE membership across client links. SQL backends add schema
16; migrations 1-15 retain their original contents and checksums. Targeted
checks include SQLite reopen and the 41 local real-provider assertions recorded
below. The SQL probes verify v15-to-v16 upgrade, retained migration metadata
and data, and recipient binding; Redis verifies its DTO and atomic claims
without SQL migrations. The earlier external MySQL/PostgreSQL results do not
validate schema 16 or the new Redis game case.

| Run / commit | Selection | Observed result |
|---|---|---|
| [41272492807](https://github.com/XingLingQAQ/NovaLink/actions/runs/41272492807) / `787ad13` | Bukkit + Fabric, trusted TLS | PASS, 3/3 required assertions |
| Same run | Untrusted CA after trusted game control | PASS, 4/4 required assertions |
| Same run | PostgreSQL game messages across backend restart | PASS, 5/5 required assertions |
| Same run | MySQL | FAIL: migration 10 uses unsupported `CREATE INDEX IF NOT EXISTS`; game cases did not execute |
| [41271524062](https://github.com/XingLingQAQ/NovaLink/actions/runs/41271524062) / `e23cb70` | Both proxies, PNX, `business,recovery,proxy-switch` | FAIL; follow-up fixes and fresh acceptance required |
| [41271531739](https://github.com/XingLingQAQ/NovaLink/actions/runs/41271531739) / `e23cb70` | Velocity mention sound | Required sound assertion PASS: `minecraft:entity.experience_orb.pickup` received as an entity sound |
| Same run | Velocity and Bungee Lite moderation | All 18 required moderation assertions passed on each proxy; the combined workflow failed on extended-business timing/console input |
| [41274531514](https://github.com/XingLingQAQ/NovaLink/actions/runs/41274531514) / `6e5a2c7` | Both Lite proxies, `core,business,lite-moderation` | PASS: Velocity 148/148, Bungee 147/147 gating assertions, including all required group IDs |
| [41274536675](https://github.com/XingLingQAQ/NovaLink/actions/runs/41274536675) / `6e5a2c7` | MySQL after migration repair | PASS, 5/5; the same game-message row survived a graceful backend restart, then bidirectional game delivery resumed |
| [41274540721](https://github.com/XingLingQAQ/NovaLink/actions/runs/41274540721) / `6e5a2c7` | Both Relay proxies, native PNX + two Bukkit servers, `core,business,recovery,proxy-switch` | PASS: 174/174 gating assertions per proxy, including 13 recovery and 23 switch assertions each; fresh backend logs and JWT refresh recorded |
| [41275523518](https://github.com/XingLingQAQ/NovaLink/actions/runs/41275523518) / `2016984` | Normal Build & Test | PASS: all-platform Gradle build/tests, orchestration contract and Panel lint/production build |
| [Local 2026-11-02, original native summary](../.e2e-artifacts/environment-native/19bcd51611aa48d1bed944bb6156d3bc/public/native-storage-summary.json) | MariaDB 12.3.3 via MySQLProvider, PostgreSQL 18.6 and Redis 8.10.1 | PASS: 11 + 11 + 8 = 30 real-provider assertions, including SQL v15-to-v16 upgrade, recipient binding and Redis DTO compatibility; preserved unchanged after the separate Oracle run |
| [Local 2026-11-02, Oracle native summary](../.e2e-artifacts/environment-native/8c7ef98ec0224683bba9d5e7a94cd878/public/native-storage-summary.json) | Oracle MySQL 8.4.13 LTS via MySQLProvider | PASS: 11/11 real-provider assertions, including v15-to-v16 upgrade, old checksums/data retention, recipient persistence, wrong-target rejection, single-winner concurrent claim, reconnect and unbound compatibility; [official source, checksums and execution command](../.e2e-artifacts/environment-native/8c7ef98ec0224683bba9d5e7a94cd878/public/oracle-source-manifest.json) |

Local native database acceptance totals **41/41 passing assertions**:
Oracle MySQL 11 + MariaDB 11 + PostgreSQL 11 + Redis 8. These runs use real
database servers and production providers. They do not exercise TLS or actual
game traffic. Schema 16 and the new Redis game case still need current TLS
and game-path acceptance; the historical game runs above do not cover them.
Per-engine artifacts and validation boundaries are listed in
[the storage probe record](environment/STORAGE.md#recorded-native-results-2026-11-02).

Lite management has a real console-driven entry in
`joint/scenarios/lite-moderation.ps1`, including Bungee Lite; it is no longer
an area with no execution path. The selected registry IDs must be present as
non-diagnostic assertions. An unselected job, a fixture or a process starting
is not a real acceptance result.

Nukkit, Sponge and PocketMine-MP have fixed SHA-256 pins in
`versions.lock.ps1`. PINNED downloads reject missing/invalid/placeholder hashes
before cache access or download. A sidecar cannot replace the expected hash.
Successful AUTO resolution may retain its no-upstream-hash smoke contract;
API-supplied hashes and fixed-entry fallback remain strict.

See [the harness README](README.md#joint-manual-e2e-coverage) for selection
and prerequisites, and [joint coverage](joint/JOINT-SCENARIO-COVERAGE.md) for
the current acceptance record, retained failures and remaining coverage limits.

## Historical evidence legend (2026-08-08)

**Evidence**: Each historical TESTED cell cites its old results file and observation.
**Legend**:
- TESTED = Feature exercised with real evidence captured
- NOT-IMPL = Feature not implemented on this platform (verified via command class source)
- NOT-RUN = Script ready but not run this session
- N/A = Not applicable to this platform type

## Historical Feature x Platform Matrix (2026-08-08)

| Feature | bukkit | velocity | bungee | sponge | nukkit | pnx (legacy Nukkit run) | folia |
|---|---|---|---|---|---|---|---|
| help | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED |
| join | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED |
| leave | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED |
| list | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED |
| who | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED |
| toggle (REPLACE/HYBRID) | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED |
| REPLACE-mode chat | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED |
| HYBRID-mode chat (/nc <ch> <msg>) | TESTED | TESTED | TESTED | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL |
| create | TESTED | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL |
| invite | TESTED | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL |
| accept | TESTED | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL |
| reload (admin) | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED |
| mute (admin) | TESTED | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL |
| kick (admin) | DEFERRED | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL |
| announce (admin) | TESTED | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL |
| title (admin) | TESTED | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL | NOT-IMPL |
| debug (admin) | NOT-RUN | NOT-RUN | NOT-RUN | TESTED | NOT-RUN | NOT-RUN | NOT-RUN |
| @mention sender-side render | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED |
| NC-403 permission denied | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED |
| NC-433 not-in-channel error | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED |
| NC-404 channel/player not found | TESTED | NOT-RUN | NOT-RUN | NOT-RUN | NOT-RUN | NOT-RUN | NOT-RUN |
| reconnect re-sync | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED | TESTED |

## Historical Evidence Index

### bukkit (Purpur 1.21.8, mineflayer)
- results: `.e2e/bot/results.json` (21711 bytes)
- stdout: `.e2e/bot/run-extended.stdout.log` (171 lines)
- rcon: `.e2e/bot/rcon-admin.stdout.log`
- help shows create/invite/accept: L17-19
- toggle REPLACE->HYBRID: L34, L41
- HYBRID chat: L69-70
- create with password: L81-82
- NC-404 (join non-existent channel): L90
- invite non-existent player NC-404: L107
- accept bogus code: L112
- reload NC-403 (player): L123
- mute/announce/title NC-403 (player): L128, L138, L143
- NC-433 (leave when not joined): L157
- reconnect re-spawn: L165
- RCON admin reload: rcon-admin.stdout.log L2-5 (success)
- RCON admin announce/title: rcon-admin.stdout.log L7-13 (player-only restriction)

### velocity (Velocity 4.1.0 proxy, mineflayer)
- results: `.e2e/velocity/bot/results.json` (15130 bytes)
- HYBRID chat round-trip: results.json "hello via hybrid from velocity"
- toggle: results.json "混合模式" / "频道模式"
- NC-403: results.json "[NC-403] 权限不足"
- NC-433: results.json "[NC-433] 不在该频道中"
- reconnect re-spawn: results.json "re-spawned"

### bungee (Waterfall 1.21 proxy, mineflayer)
- results: `.e2e/bungee/bot/results.json` (14331 bytes)
- stdout: bungee bot run output (112 lines)
- HYBRID chat: L66 "hello via hybrid from bungee"
- toggle: L28 "混合模式", L36 "频道模式"
- NC-403: L84 "[NC-403] 权限不足"
- NC-433: L99 "[NC-433] 不在该频道中"
- reconnect re-spawn: L106

### sponge (SpongeAPI 8, MC 1.16.5, mineflayer)
- results: `.e2e/sponge/bot/results.json` (17306 bytes)
- stdout: sponge bot run output (126 lines)
- help: L9-22 (shows help/join/leave/list/who/toggle only)
- join: L30 "已加入频道 global"
- toggle: L35 "频道模式", L43 "混合模式"
- HYBRID /nc <ch> <msg>: L75 "Incorrect argument" (Brigadier pruning)
- create: L84 "Incorrect argument" (Brigadier pruning)
- reload: L93 "Incorrect argument" (pruned, admin perm)
- mute: L97 "Incorrect argument" (pruned)
- NC-433: L108 "[NC-433] 不在该频道中"
- reconnect re-spawn: L116

### nukkit (Cloudburst Nukkit, Bedrock 1.26.30, bedrock-protocol)
- results: `.e2e/nukkit/bot/results.json` (12115 bytes)
- stdout: `.e2e/nukkit/bot/run-extended.stdout.log`
- toggle: L26 "混合模式", L31 "频道模式"
- HYBRID /nc <ch> <msg>: L55 "未知命令: global"
- create: L64 "未知命令: create"
- reload NC-403: L73
- mute: L77 "未知命令: mute"
- reconnect re-spawn: L91

### pnx-labelled historical Nukkit run (not native PowerNukkitX 3)

This old label refers to the Nukkit-hosted fallback. Current `pnx` selects
native PowerNukkitX 3.0.6; none of the observations below validates that runtime.

- results: `.e2e/pnx/bot/results.json` (11669 bytes)
- stdout: `.e2e/pnx/bot/run-stdout.log`
- help: shows help/join/leave/list/who/toggle/channel
- join: "已加入频道 global"
- toggle: "聊天已关闭" / "聊天已开启"
- REPLACE chat: "hello from pnx bot (replace mode)" round-trip
- HYBRID /nc <ch> <msg>: "未知命令: global"
- create: "未知命令: create"
- reload NC-403: "你没有权限执行此命令 (NC-403)"
- mute: "未知命令: mute"
- reconnect re-spawn: "re-spawned" + /nc list verified

### folia (Folia, mineflayer)
- results: `.e2e/folia/bot/results.json` (15031 bytes)
- stdout: folia bot run output (118 lines)
- help: L7-17
- toggle: L29 "频道模式", L35 "混合模式"
- HYBRID /nc <ch> <msg>: L63 "未知命令: global"
- create: L75 "未知命令: create"
- reload NC-403: L87
- mute: L92 "未知命令: mute"
- NC-433: L106
- reconnect re-spawn: L113

## Historical Platform Implementation Summary (2026-08-08)

| Platform | Commands implemented | Admin commands | Chat modes |
|---|---|---|---|
| bukkit | help,join,leave,list,who,create,invite,accept,toggle | mute,kick,announce,title,reload,debug | REPLACE + HYBRID |
| velocity | help,join,leave,list,who,toggle + handleChannelMessage | reload | REPLACE + HYBRID (/nc <ch> <msg>) |
| bungee | help,join,leave,list,who,toggle + handleChannelMessage | reload | REPLACE + HYBRID (/nc <ch> <msg>) |
| sponge | help,join,leave,list,who,toggle | reload,debug (permission-gated) | REPLACE + HYBRID (vanilla preserved) |
| nukkit | help,join,leave,list,who,toggle,channel,reload,debug | reload,debug | REPLACE + HYBRID (toggle = on/off) |
| pnx (legacy Nukkit run) | help,join,leave,list,who,toggle,channel,reload,debug | reload,debug | REPLACE + HYBRID (toggle = on/off) |
| folia | help,join,leave,list,who,toggle | reload,debug | REPLACE + HYBRID (vanilla preserved) |

## Historical Bugs Found (2026-08-08 evidence; not revalidated this round)

### BUG-1: announce/title player-only restriction blocks RCON console
- **Platform**: bukkit
- **Evidence**: rcon-admin.stdout.log L9, L13 show "此命令只能由玩家执行"
- **Impact**: Admin cannot run `/nc announce` or `/nc title` from server console/RCON.
  AnnounceCommand.isPlayerOnly()=true and TitleCommand.isPlayerOnly()=true require a player UUID.
- **Severity**: Medium — limits admin automation via RCON.

### BUG-2: Sponge Brigadier tree prunes admin/unimplemented commands for offline players
- **Platform**: sponge (SpongeAPI 8, MC 1.16.5)
- **Evidence**: sponge stdout L75, L84, L93, L97 — all return "Incorrect argument for command at position 3: nc <--[HERE]" instead of clean NC-403 or unknown-command messages
- **Impact**: /nc reload, /nc create, /nc mute, /nc <ch> <msg> all silently pruned from the Brigadier command tree because the player lacks novachat.admin.* permissions or the subcommand is not registered. Users see a raw Brigadier parse error instead of a NovaChat error code.
- **Severity**: Low-Medium — UX issue, not a crash. Known SpongeAPI 8 behavior for offline-player permission resolution.

### BUG-3: PNX/nukkit toggle semantics differ from Java platforms
- **Platform**: nukkit, pnx
- **Evidence**: nukkit L26 "混合模式"/L31 "频道模式"; pnx "聊天已关闭"/"聊天已开启"
- **Impact**: On Bedrock platforms, toggle cycles between "chat on" and "chat off" (or between REPLACE and HYBRID), but the semantics differ from bukkit where toggle cycles REPLACE<->HYBRID with explicit mode names. The Bedrock toggle does not clearly distinguish the two modes.
- **Severity**: Low — cosmetic/UX inconsistency, not a functional bug.

## Historical gaps (2026-08-08; current acceptance queue above)

1. **kick**: Script sends /nc kick on bukkit only (DEFERRED — the kick test was not fully exercised because the target player does not exist, so it would return NC-404 like invite). The kick DELIVERY test (target receives kick notification) is deferred to the joint 2-platform E2E agent.

2. **mute delivery**: The mute command is tested for permission denial (NC-403) and unknown-command on platforms that don't implement it. The actual mute ENFORCEMENT test (muted player's messages are blocked) is deferred to the joint E2E agent.

3. **@mention delivery**: The @mention test only verifies the SENDER-side render (the bot sends a message containing @NonExistentTarget and observes its own message echoed). The TARGET-side delivery (highlight + sound/title) requires a second bot on a second platform — deferred to the joint E2E agent.

4. **debug command**: Only sponge tested the debug command path (it was pruned by Brigadier). Other platforms have debug in their command class but it was not explicitly tested with admin permissions.

5. **HYBRID /nc <ch> <msg> on nukkit/pnx/folia**: These platforms return "未知命令: global" when sent `/nc global <msg>`, meaning the HYBRID routing path (`handleChannelMessage`) is not implemented on these platforms. This is NOT-IMPL, not a bug — the command class does not have a default branch for channel message routing.

## Historical files changed (2026-08-08, under .e2e/)

- `.e2e/bot/run-e2e.js` — extended bukkit bot script (full feature sequence)
- `.e2e/bot/package.json` — added "start" script
- `.e2e/bot/results.json` — bukkit E2E results (real evidence)
- `.e2e/bot/run-extended.stdout.log` — bukkit E2E stdout
- `.e2e/bot/rcon-admin.stdout.log` — bukkit RCON admin command log
- `.e2e/bin/start-purpur-bot.ps1` — created (bukkit bot runner)
- `.e2e/bin/start-purpur-rcon.ps1` — created (RCON admin command sender)
- `.e2e/velocity/bot/run-e2e.js` — extended velocity bot script
- `.e2e/velocity/bot/results.json` — velocity E2E results
- `.e2e/bungee/bot/run-e2e.js` — extended bungee bot script (rewritten for syntax safety)
- `.e2e/bungee/bot/results.json` — bungee E2E results
- `.e2e/sponge/bot/run-e2e.js` — extended sponge bot script
- `.e2e/sponge/bot/results.json` — sponge E2E results
- `.e2e/nukkit/bot/run-e2e.js` — extended nukkit bot script
- `.e2e/nukkit/bot/results.json` — nukkit E2E results
- `.e2e/pnx/bot/run-e2e.js` — extended pnx bot script
- `.e2e/pnx/bot/results.json` — pnx E2E results
- `.e2e/pnx/bot/run-stdout.log` — pnx E2E stdout
- `.e2e/pnx/backend/novalink.yml` — fixed websocket-port (34582 -> 34583, was conflicting with sponge backend)
- `.e2e/folia/bot/run-e2e.js` — extended folia bot script
- `.e2e/folia/bot/results.json` — folia E2E results
- `.e2e/FEATURE-COVERAGE.md` — this manifest
