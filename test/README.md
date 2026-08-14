# NovaChat Real-Server E2E Harness (committed)

This directory holds the **committed, portable** E2E harness that runs in GitHub
Actions. It is a trimmed, CI-friendly port of the richer local harness that
lives under `.e2e/` (gitignored, Windows-machine-specific).

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
PID-unique `.download.tmp`, computes `Get-FileHash -Algorithm SHA256`, and
**strictly compares** to the lock hash. Mismatch → delete tmp, error out (never
run an unverified binary). This is how functional + joint E2E pin a known-good,
reproducible version.

### AUTO mode (`-Auto`) — for runnable-smoke on the plugin's declared api-version

```
powershell -File test/bin/fetch-server.ps1 -Name <platform> -Auto -DistDir .e2e-artifacts/dist
```

Calls the platform's API at runtime to discover the **latest** build + URL,
downloads it, computes SHA-256 on the fly, and **logs the hash for
traceability but does NOT fail on a mismatch** (the latest can change daily;
pinned mode is the one that enforces the hash). When the API itself supplies a
content hash — PaperMC `fill-data.papermc.io` URLs embed the SHA-256 in the path
— Auto mode still verifies it. Used for the smoke matrix where each plugin is
tested against its declared api-version's latest supported server.

### Cache

If `dist/<File>` exists AND the hash matches (pinned) OR the `.sha256` sidecar
matches the cached file (Auto), reuse without re-downloading. BDS zips
(`Kind = 'zip'`) are extracted to `dist/bds-<version>/` and the extracted dir is
cached too.

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

`versions.lock.ps1` has an entry (with `Kind` + `Auto` fields) for all 8
platforms. `Kind` is `jar` | `zip` | `phar` (controls whether `fetch-server.ps1`
extracts); `Auto = $true` marks platforms that support auto-detect-latest (all
of them).

| Platform | Engine (declared) | Source API for `-Auto` | Kind | Pinned? | Auto verified locally? |
|----------|-------------------|------------------------|------|---------|-------------------------|
| `purpur` | Purpur 26.2 (build 2620), JDK 21 | `api.purpurmc.org/v2/purpur/<MC>` → `builds.latest` → `/<MC>/<build>/download` | jar | YES (real SHA-256) | pinned verified 2026-08-12 |
| `folia` | Folia 26.1.2-8, JDK 21 | scrape `papermc.io/downloads/folia` → `fill-data.papermc.io/.../folia-<MC>-<b>.jar` (SHA in path) | jar | content-addressed (SHA in URL) | resolver implemented |
| `velocity` | Velocity 4.1.0-SNAPSHOT-16, JDK 25 | scrape `papermc.io/downloads/velocity` → `fill-data.papermc.io/.../velocity-4.1.0-SNAPSHOT-<b>.jar` | jar | content-addressed | resolver implemented |
| `waterfall` | Waterfall 1.21-615, JDK 21 (BungeeCord API-compatible, EOL 2026-06) | scrape `papermc.io/downloads/waterfall` → `fill-data.papermc.io/.../waterfall-<MC>-<b>.jar` | jar | content-addressed | resolver implemented |
| `nukkit` | Cloudburst 1.0-SNAPSHOT build 1242, JDK 21 | `repo.opencollab.dev/maven-snapshots/.../maven-metadata.xml` → timestamp+buildNumber | jar | hash auto-computed | resolver implemented |
| `sponge` | SpongeVanilla 1.21.10-17.0.0, JDK 17 | scrape `spongepowered.org/downloads/spongevanilla` → `.../spongevanilla-<ver>-universal.jar` | jar | hash auto-computed | resolver implemented |
| `bds` | Bedrock Dedicated Server (serves endstone + levilamina) | minecraft.net pocketbedlinks JSON (see "BDS" below) | zip | documented placeholder | **documented-only** (minecraft.net blocks this env) |
| `pocketmine` | PocketMine-MP 5.44.3 (api 5.0.0+), PHP | `update.pmmp.io/api?channel=stable` → `download_url` (GitHub release phar) | phar | hash auto-computed | **auto verified 2026-08-09** |

> Java declared versions come from each plugin's `plugin.yml` / `build.gradle`
> (`NovaChat/Plugin/{bukkit,folia}/`, `NovaChat/Proxy/{velocity,bungee}/`,
> `NovaChat/Bedrock/{nukkit,endstone,levilamina,pmmp}/`, `NovaChat/Sponge/sponge/`).
> Bedrock `bds` serves BOTH endstone + levilamina (both run on top of Mojang BDS).

### Bedrock Dedicated Server (BDS)

BDS is a **ZIP** (not a jar), native binary (no JDK). `fetch-server.ps1`
extracts it to `dist/bds-<version>/` after download. The real BDS URL is served
by minecraft.net's `pocketbedlinks` JSON, which the download page
(`https://www.minecraft.net/en-us/download/server/bedrock`) fetches at runtime
via JS. That endpoint is **blocked from this environment** (TLS dropped even with
a browser UA; the page is JS-rendered with no static URL).

`-Auto` therefore logs the detection approach + URL pattern and exits 0 without
downloading — it is **documented-only**, not a script failure. The levilamina /
endstone E2E agents supply a locally-cached BDS zip via their own scripts.

URL pattern (documented):
```
https://www.minecraft.net/bedrockdedicatedserverbinaries/<platform>/bedrock-server-<MC>.<patch>.zip
```
where `<platform>` is `win` or `linux`. Detection approach: fetch the download
page, extract the `pocketbedlinks` JSON URL from the page's
`data-mc-config-strings` attribute, call that JSON for the
`serverBedrockWindows` / `serverBedrockLinux` download URL.

## Platform E2E scripts

| Platform | Status | Script |
|----------|--------|--------|
| bukkit | Full (L1) | `bin/run-bukkit-e2e.ps1` |
| folia | Full (L1) | orchestrator |
| velocity | Full (L1) | orchestrator |
| bungee (BungeeCord via Waterfall) | Full (L1) | orchestrator |
| nukkit | Full (L1) | orchestrator |
| sponge | Full (L1) | orchestrator |
| endstone (BDS) | Bedrock E2E | `bin/run-endstone-e2e.ps1` |
| levilamina (BDS) | Bedrock E2E | `bin/run-levilamina-e2e.ps1` |
| pmmp (PocketMine) | Bedrock E2E | `bin/run-pmmp-e2e.ps1` |

The Java platforms (bukkit/folia/velocity/bungee/nukkit/sponge) reached L1 in
the local `.e2e/` harness (see `docs/REAL-SERVER-E2E.md` §1.5.1). The Bedrock
platforms (endstone/levilamina/pmmp) are wired through the orchestrator +
their dedicated scripts.

## EULA policy (CI)

CI **silently accepts the Mojang EULA** by writing `eula=true` into the
throwaway per-run server directory at start time. This overrides the
"do-not-silently-accept" guidance in `docs/REAL-SERVER-E2E.md §4.3` per the
explicit CI policy: CI is a throwaway environment and the EULA file never
enters the repository. The harness does NOT commit a `eula.txt`; it generates
one on the fly and it dies with the run directory. (Engines with `Eula = $true`
in `versions.lock.ps1`: purpur, folia, bds.)

## Layout

```
test/
  versions.lock.ps1           # pinned server URLs + SHA-256 + Kind/Auto fields
  README.md                   # this file
  bin/
    fetch-server.ps1          # two-mode download: PINNED (strict hash) | -Auto (latest)
    write-classpath.init.gradle  # export StarLink:core runtime classpath
    run-bukkit-e2e.ps1        # full bukkit/Purpur L1 orchestrator
    run-e2e-orchestrator.ps1  # multi-platform dispatcher
    run-endstone-e2e.ps1      # Bedrock endstone (BDS) E2E
    run-levilamina-e2e.ps1    # Bedrock levilamina (BDS) E2E
    run-pmmp-e2e.ps1          # Bedrock PocketMine-MP E2E
  conf/
    novalink.template.yml     # backend config template (ports/keys templated)
  bot/
    package.json              # mineflayer + bedrock-protocol deps
    run-e2e.js                # Java-platform L1 bot: /nc commands + chat round-trip
    run-e2e-bedrock.js        # Bedrock-platform bot (bedrock-protocol)
```

Runtime artifacts (server jars, logs, world data) land under
`.e2e-artifacts/` at the repo root, which is gitignored.
