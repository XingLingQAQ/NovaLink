# NovaChat Real-Server E2E Harness (committed)

This directory holds the **committed, portable** E2E harness that runs in GitHub
Actions on manual dispatch. It is a trimmed, CI-friendly port of the richer
local harness under `.e2e/` (gitignored, Windows-machine-specific).

The 2026-11-01 scenario and environment integration has not had real-server
acceptance. Historical logs, syntax checks and fixtures are separate evidence.

## Why a separate `test/` source tree?

The `.e2e/` directory is gitignored because it accumulates ~100MB server jars,
world data, and machine-absolute paths. CI needs a portable, reproducible
subset: pinned server versions, SHA-256-verified downloads, and scripts that
take the repo root as a parameter instead of hardcoding `D:\Project\NovaLink`.

## Server binary download — `bin/fetch-server.ps1`

`fetch-server.ps1` downloads + verifies the Minecraft server binary for every
platform the E2E matrix covers. It has **two modes** (design: `docs/REAL-SERVER-E2E.md`
§4.2, extended 2026-08-09):

### PINNED mode (default) — for functional + joint E2E

```
powershell -File test/bin/fetch-server.ps1 -Name <platform> -DistDir .e2e-artifacts/dist
```

Uses the `Url` + `Sha256` pinned in `versions.lock.ps1`. Downloads to a
PID-unique `.download.tmp`, computes SHA-256, and strictly compares it to a
valid lock hash. Missing, malformed or placeholder hashes fail before cache
access, directory creation or download. A matching sidecar cannot authorize a
PINNED artifact. A mismatch discards the tmp and retries up to three
verification attempts before failing. Nukkit, Sponge and PocketMine-MP now
have fixed SHA-256 pins corroborated against official metadata; provenance,
byte counts and artifact identity checks are recorded in `versions.lock.ps1`.

### AUTO mode (`-Auto`) — for runnable-smoke on the plugin's declared api-version

```
powershell -File test/bin/fetch-server.ps1 -Name <platform> -Auto -DistDir .e2e-artifacts/dist
```

Calls the platform's API at runtime to discover the **latest** build + URL,
downloads it, computes SHA-256 on the fly, and logs it for traceability when no
expected hash is available. When the API supplies a
content hash — PaperMC `fill-data.papermc.io` URLs embed the SHA-256 in the path
— Auto mode still verifies it. Used for the smoke matrix where each plugin is
tested against its declared api-version's latest supported server. A failed
resolver falls back to the fixed entry and enforces the full PINNED contract;
it cannot turn a missing hash or unavailable artifact into a successful skip.

### Cache

When an expected hash exists, reuse `dist/<File>` only after it matches.
The `.sha256` sidecar path is allowed only after successful AUTO resolution
with no upstream hash. It cannot substitute for a PINNED or API-supplied hash.
`Kind = 'zip'` entries are extracted to `dist/<Name>-<MC>/`; this keeps
`bds-1.26.52/` and `bds-levilamina-1.26.51/` separate. Existing extracted
directories are reused. `Kind = 'archive'` leaves extraction to the runner.

### Concurrency

The `.download.tmp` name embeds the current PID, so two concurrent
`fetch-server.ps1` invocations for the **same** platform (e.g. two agents
verifying purpur at once) do not collide on the same tmp file handle. Orphaned
tmps from a prior crashed run are cleaned up best-effort (only if not locked by
a live process).

### Proxy

Set `$env:HTTP_PROXY` / `$env:HTTPS_PROXY` if a proxy is needed, e.g.:

```powershell
$env:HTTP_PROXY="http://127.0.0.1:7890"
$env:HTTPS_PROXY="http://127.0.0.1:7890"
```

## Platform coverage

`versions.lock.ps1` describes servers and supporting runtimes for the 10-platform
E2E matrix. `Kind` is `jar` | `zip` | `phar` | `archive`; `Auto = $true` marks
entries with a latest-version resolver. The LeviLamina set and `pmmp-php` are
pin-only. The table describes the checked-in lock and resolver, not a new run.

| Lock name | Engine | Source for `-Auto` | Kind | Lock SHA-256 |
|-----------|--------|--------------------|------|-------------|
| `purpur` | Purpur 1.21.11-2568, JDK 21 | `api.purpurmc.org/v2/purpur/<MC>` → latest build → download | jar | fixed |
| `folia` | Folia 26.1.2-8, JDK 25 | `papermc.io/downloads/folia` → `fill-data.papermc.io` (SHA in path) | jar | fixed |
| `velocity` | Velocity 4.1.0-SNAPSHOT-16, JDK 25 | `papermc.io/downloads/velocity` → `fill-data.papermc.io` | jar | fixed |
| `waterfall` | Waterfall 1.21-615, JDK 21 | `papermc.io/downloads/waterfall` → `fill-data.papermc.io` | jar | fixed |
| `nukkit` | Cloudburst 1.0-SNAPSHOT build 1242, JDK 21 | `repo.opencollab.dev` Maven snapshot metadata | jar | fixed |
| `powernukkitx` | Native PowerNukkitX 3.0.6, JDK 21, Bedrock 1.26.60 | pin-only | jar | fixed |
| `sponge` | SpongeVanilla 1.21.10-17.0.0, JDK 21 | `spongepowered.org/downloads/spongevanilla` → universal jar | jar | fixed |
| `bds` | BDS 1.26.52.3 for Endstone | Minecraft services links API | zip | fixed |
| `bds-levilamina` | BDS 1.26.51.1 for LeviLamina | pin-only | zip | fixed |
| `pocketmine` | PocketMine-MP 5.44.3, PHP | `update.pmmp.io/api?channel=stable` → `download_url` | phar | fixed |

> Java declared versions come from each plugin's `plugin.yml` / `build.gradle`
> (`NovaChat/Plugin/{bukkit,folia}/`, `NovaChat/Proxy/{velocity,bungee}/`,
> `NovaChat/Bedrock/{nukkit,endstone,levilamina,pmmp}/`, `NovaChat/Sponge/sponge/`).
> `bds` serves Endstone; `bds-levilamina` is a separate, incompatible version pin.

### Bedrock Dedicated Server (BDS)

BDS is a native ZIP (no JDK). `Resolve-BdsLatest` calls
`https://net-secondary.web.minecraft-services.net/api/v1.0/download/links`
and selects `result.links[]` with `downloadType = serverBedrockWindows`.
The Windows download URL uses
`https://www.minecraft.net/bedrockdedicatedserver/bin-win/bedrock-server-<version>.zip`.
The former `pocketbedlinks` / `bedrockdedicatedserverbinaries` description is obsolete.

`-Name bds -Auto` resolves and downloads the current BDS. It verifies Endstone's
`bedrock-server-data` SHA-256 only when that metadata names the exact same URL;
otherwise it records the computed hash after successful AUTO resolution. An
unreachable links API or missing Windows link falls back to the verified pin.
A missing/invalid pin or failed download/hash check fails closed; fetch does
not return exit 0 without an artifact. The bounded whole-download retry for a
truncated BDS ZIP remains in place. A verified download proves artifact
integrity only; it is not an E2E acceptance result.

The Endstone runner installs `EndstoneVersion` (0.11.14) from the `bds` lock,
checks its Minecraft version against the pin, and fetches BDS 1.26.52.3 without
`-Auto`. The package and BDS must be upgraded together; floating Endstone can
otherwise prompt for a different BDS release and stall the run.
LeviLamina 26.51.7 requires
`bds-levilamina` 1.26.51.1 (`RequiresBds = '1.26.51'`), so its runner fetches
the separate pin without `-Auto`. The latest BDS must not replace that pin.

### LeviLamina installation and SDK

`run-levilamina-e2e.ps1` fetches five SHA-256-pinned packages:

| Lock name | Version | Installation |
|-----------|---------|--------------|
| `bds-levilamina` | 1.26.51.1 | BDS run directory |
| `levilamina` | 26.51.7 | `plugins/LeviLamina/` (DLL + manifest) |
| `levilamina-preloader` | 1.16.5 | `PreLoader.dll` in the BDS root |
| `levilamina-peeditor` | 3.9.0 | `PeEditor.exe -mb` patches the BDS image |
| `levilamina-runtime` | 26.51.1-server.11 | `bedrock_runtime_data` in the BDS root |

The runner starts `bedrock_server_mod.exe` and installs the NovaChat DLL with
its manifest. Copying only the loader ZIP is insufficient. The SDK in
`NovaChat/Bedrock/levilamina/xmake.lua` must match the runtime loader (26.51.7).
For standalone targets, `xmake f --levilamina_sdk=n` disables the SDK dependency;
`--sdk=n` is not this switch (`sdk` is the toolchain SDK path). This does not
build or validate the real BDS plugin.

## Platform E2E scripts

| Platform | Status | Script |
|----------|--------|--------|
| bukkit | L1 runner wired | `bin/run-bukkit-e2e.ps1` |
| folia | Backend config + strict chat gate implemented | orchestrator → multiplatform runner |
| velocity | `SKIPPED-BY-DESIGN`; no full L1 | orchestrator → multiplatform runner |
| bungee (BungeeCord via Waterfall) | `SKIPPED-BY-DESIGN`; no full L1 | orchestrator → multiplatform runner |
| nukkit | Runner wired | orchestrator → multiplatform runner |
| pnx | Native PowerNukkitX 3.0.6; API 3.x | orchestrator → multiplatform runner |
| sponge | Runner wired | orchestrator → multiplatform runner |
| endstone (BDS) | Runner wired | `bin/run-endstone-e2e.ps1` |
| levilamina (BDS) | Runner wired | `bin/run-levilamina-e2e.ps1` |
| pmmp (PocketMine) | Strict chat gate implemented | `bin/run-pmmp-e2e.ps1` |

These are 10 matrix entries, not 10 verified passes. The committed harness skips
bungee/velocity before running assertions because it lacks their downstream
Purpur topology. Earlier local `.e2e/` records do not establish current CI L1.

PMMP and Folia's final L1 contract requires authenticated plugin/backend
connectivity, plugin command evidence, and an inbound
plugin-formatted `[channel] <bot>: <original phrase>` message after the bot
sends chat. Sent rows, vanilla echoes, command-only success, and `PARTIAL`
do not satisfy it. PMMP now sets `replace_vanilla: true`, keeps forwarding
enabled before the first chat, and includes the main `chatOk` in `pass`.
Its JOIN/LEAVE packets carry `playerId`, `playerName`, and `platform`.
Folia now receives backend config at `plugins/NovaChat-Folia/config.yml`.
The four configured platforms (folia/nukkit/pnx/sponge) fail when plugin
evidence is present but the chat round-trip is missing. All L1 runners also
require `Get-BotRunFailure` to accept the run: exit code 0, a matching `runId`,
`sequenceComplete: true`, a spawn, no timeout/error/kick, and readable server
logs without a fatal marker. A previous result file is removed before launch.
`PARTIAL` and `UNKNOWN` are failures. Normal client shutdown after the sequence
is allowed; a connection lost before the bot requests shutdown is a failure.
Verify all runnable platforms in one unfiltered workflow before claiming full-matrix coverage.

PNX uses the official [3.0.6 release](https://github.com/PowerNukkitX/PowerNukkitX/releases/tag/3.0.6),
with its SHA-256 pinned separately from Nukkit. The plugin targets the
`org.powernukkitx` API on Java 21; the bot uses Bedrock 1.26.60 (protocol 2225).
Earlier Nukkit fallback results do not validate this native PNX version.
The launcher opens `java.base/java.lang` for PNX's event executor and seeds
`chunk-settings.spawnThreshold: 16` for the test's view distance of 4.
The bot connects through PNX's built-in HTTP NetherNet signalling endpoint;
LAN discovery is not used for this platform.
The PNX adapter also snapshots entity metadata before asynchronous encoding,
preventing the 3.0.6 live-map race while preserving packet-event cancellation.

## Joint manual E2E coverage

The 2026-11-01 integration has manual acceptance in progress. These entries
describe selected paths and required evidence; recorded results are in
[the coverage manifest](FEATURE-COVERAGE.md#recorded-results).
All E2E workflows are manual (`workflow_dispatch`);
the daily E2E schedule has been removed.

`e2e-joint.yml` selects `proxies` (`velocity`, `bungee`, or both), `modes`
(`relay`, `lite`, or both), and relay `bedrock` (`nukkit`, `pnx`, or both).
Velocity Lite and Bungee Lite have real proxy + Bukkit execution paths;
Lite starts no standalone backend. Relay uses a proxy + Bukkit + Bedrock.
PNX means native PowerNukkitX 3.0.6, not the former Nukkit fallback.

`-ScenarioGroups` / workflow input `scenario_groups` is a comma-separated
selection. It defaults to `core`, which is always included. Extra groups
require explicit manual selection. Do not combine incompatible modes in one
dispatch; topology validation must reject them before servers start.

| Group | Topology and prerequisites | Required evidence; real acceptance pending |
|---|---|---|
| `core` | Velocity or Bungee; relay with Bedrock, or Lite | Receiver-recorded chat, channel isolation with positive controls, Java reconnect followed by fresh membership and bidirectional chat |
| `business` | Either mode; Java downstream, three ordinary bots, `global`/`local`, real operator callback | DM and `/nc r` delivery, sender confirmation, third-party exclusion, offline refusal, channel-scoped mute and unmute restoration |
| `lite-moderation` | Lite only; Velocity or Bungee console stdin, ordinary target/observer bots | Permission denial, mute/unmute, channel kick without Minecraft disconnect, ban/rejoin refusal, unban and channel scope |
| `recovery` | Relay only; real Java + Bedrock clients and backend stop/start callbacks | New backend PID, authenticated links, fresh membership and bidirectional delivery after restart, one bounded Bedrock reconnect |
| `proxy-switch` | Relay only; primary Bukkit plus `-SecondaryJavaPlatform bukkit` (`bukkit2`) | Real outbound/return switch, destination-only probes, fresh membership, exactly-once delivery and SERVER-channel cross-link refusal |

`-SecondaryJavaPlatform` / `secondary_java_platform` defaults to `none`.
`bukkit` creates a second Bukkit process with a distinct port, RCON port and
backend link identity. It requires exactly one Bukkit primary and no Mod
leg; the secondary uses `ModPort` / `ModRconPort`. A proxy + two Bukkit
servers + Bedrock needs four concurrent server slots.

Bungee switching uses the console-only
[native API fixture](joint/fixtures/bungee-switch/README.md), because the
locked Waterfall distribution's command-module download returns 404. This
verifies real player movement and destination-side evidence; it does not
validate the upstream `/server` command.

Examples of the interface being integrated (these commands were not run):

```powershell
# Bungee Lite, including the explicit management and business groups.
gh workflow run e2e-joint.yml -f proxies=bungee -f modes=lite `
  -f scenario_groups="core,business,lite-moderation"

# Relay with a real second Bukkit downstream.
powershell -File test/joint/run-joint.ps1 -Platforms "velocity,bukkit,nukkit" `
  -Rounds 1 -MaxConcurrentServers 4 -ProxyMode relay `
  -ScenarioGroups "core,proxy-switch" -SecondaryJavaPlatform bukkit -KeepArtifacts

# Backend restart and Bedrock reconnect extend core's Java reconnect.
gh workflow run e2e-joint.yml -f proxies=velocity -f modes=relay -f bedrock=nukkit `
  -f scenario_groups="core,recovery"
```

`joint/scenarios/registry.ps1` defines required IDs for the selected groups.
`assert-joint-ci.ps1` must receive the same groups and secondary Java selection
as the runner. Missing, diagnostic-only or failed required rows do not
establish acceptance. PNX also requires the locked native version/API 3.x
and the NovaChat-PNX enable marker in the actual server log.

Retain selected inputs, exact bot plans, receiver transcripts, process logs
and `joint-summary.json`. An unselected job or a green workflow alone is not
evidence. See [joint scenario coverage](joint/JOINT-SCENARIO-COVERAGE.md) for
pending results and [the coverage plan](joint/COVERAGE-PLAN.md) for remaining gaps.

## Independent environment E2E (manual only)

The separate manual workflow is
[`.github/workflows/e2e-environment.yml`](../.github/workflows/e2e-environment.yml).
It is not a joint scenario group and has no daily schedule. Its runner is
`environment/run-environment.ps1` (PowerShell 7.4+); definitions and required
IDs are in `environment/cases.psd1`. Trusted TLS, untrusted-CA rejection and
PostgreSQL have real evidence in the coverage manifest; MySQL still needs
successful acceptance after its migration fix.

Manual dispatch example (not executed this round):

```powershell
gh workflow run e2e-environment.yml -f cases="tls-trusted,tls-untrusted,mysql,postgresql"
```

| Case | Real topology and required observations |
|---|---|
| `tls-trusted` | Actual Bukkit + Fabric plugins authenticate to the TLS backend and exchange game messages in both directions |
| `tls-untrusted` | Establish trusted bidirectional control; restart Fabric with an unrelated CA; require an explicit certificate-chain error, no new authentication and no receiver delivery during an observed window |
| `mysql` | Actual Bukkit/Fabric messages stored in MySQL, unchanged row read from a fresh backend process, then fresh bidirectional game delivery |
| `postgresql` | The same persistence/restart checks against PostgreSQL |

Prerequisites include JDK 21, built backend/Bukkit/Fabric artifacts, the locked
server/Fabric runtime, bot dependencies, OpenSSL and distinct listener ports.
SQL cases need a dedicated database or explicit `-ProvisionDatabases` with
Docker available. A prerequisite failure is a failure/blocker, not a skipped
success. Only filtered evidence under
`.e2e-artifacts/environment/<runId>-<case>/public/` is intended for upload;
`environment-summary.json` must be backed by game recordings and restart
evidence. `-PlanOnly`, fixtures and connection-only probes do not establish
TLS or SQL game-path acceptance.

## Exit codes (SKIP is not PASS)

| Code | Meaning | Orchestrator status |
|------|---------|---------------------|
| 0 | L1 pass | `PASS` |
| 1 | L1 fail | `FAIL` |
| 2 | prereq error (build/download failed) | `FAIL` |
| 3 | Environment/infrastructure blocked; no test ran | `SKIPPED` |
| 4 | Structural limit (bungee/velocity); no test ran | `SKIPPED-BY-DESIGN` |

A platform that runs zero assertions is never reported as `PASS`.
`run-e2e-orchestrator.ps1 -RequireAllPlatforms` fails on exit 3, but permits
exit 4 while retaining `SKIPPED-BY-DESIGN`. CI enables this with
`-Pe2eRequireAllPlatforms=true`; only bungee/velocity use `continue-on-error`.
In a targeted dispatch, the Run E2E step is skipped for jobs outside
`platforms`; those jobs can still appear green. A green job or workflow alone does not prove full L1.
Check the selection, actual Run E2E step, bot transcript, and per-platform
terminal state written to
`.e2e-artifacts/runs/e2e-orchestrator-summary.json`
(`status`, `exitCode`, `skipped`, `skippedByDesign`, `skippedReason`). The
multiplatform details, including `chatRoundTrip`, are in `runs/e2e-summary.json`.

## EULA policy (CI)

CI **silently accepts the Mojang EULA** by writing `eula=true` into the
throwaway per-run server directory at start time. This overrides the
"do-not-silently-accept" guidance in `docs/REAL-SERVER-E2E.md §4.3` per the
explicit CI policy: CI is a throwaway environment and the EULA file never
enters the repository. The harness does NOT commit a `eula.txt`; it generates
one on the fly and it dies with the run directory. (Engines with `Eula = $true`
in `versions.lock.ps1`: purpur, folia, bds, bds-levilamina.)

## Layout

```
test/
  versions.lock.ps1           # pinned server URLs + SHA-256 + Kind/Auto fields
  README.md                   # this file
  bin/
    fetch-server.ps1          # fail-closed PINNED; explicit AUTO smoke contract
    verify-fetch-server.ps1   # offline fixtures; not real-server evidence
    write-classpath.init.gradle  # export StarLink:core runtime classpath
    run-bukkit-e2e.ps1        # full bukkit/Purpur L1 orchestrator
    run-e2e-orchestrator.ps1  # multi-platform dispatcher
    run-endstone-e2e.ps1      # Bedrock endstone (BDS) E2E
    run-levilamina-e2e.ps1    # Bedrock levilamina (BDS) E2E
    run-pmmp-e2e.ps1          # Bedrock PocketMine-MP E2E
  joint/
    run-joint.ps1             # selected real-server scenario groups
    scenarios/registry.ps1    # required IDs and group/mode constraints
  environment/
    run-environment.ps1       # independent manual TLS/SQL game paths
    cases.psd1                # cases and required assertion IDs
  conf/
    novalink.template.yml     # backend config template (ports/keys templated)
  bot/
    package.json              # mineflayer + bedrock-protocol deps
    run-e2e.js                # Java-platform L1 bot: /nc commands + chat round-trip
    run-e2e-bedrock.js        # Bedrock-platform bot (bedrock-protocol)
```

Runtime artifacts (server jars, logs, world data) land under
`.e2e-artifacts/` at the repo root, which is gitignored.
