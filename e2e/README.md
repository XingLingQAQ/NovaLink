# NovaChat Real-Server E2E Harness (committed)

This directory holds the **committed, portable** E2E harness that runs in GitHub
Actions. It is a trimmed, CI-friendly port of the richer local harness that
lives under `.e2e/` (gitignored, Windows-machine-specific).

## Why a separate `e2e/`?

The `.e2e/` directory is gitignored because it accumulates ~100MB server jars,
world data, and machine-absolute paths. CI needs a portable, reproducible
subset: pinned server versions, SHA-256-verified downloads, and scripts that
take the repo root as a parameter instead of hardcoding `D:\Project\NovaLink`.

## Platform coverage (honest status)

| Platform | Status      | Script                  |
|----------|-------------|-------------------------|
| bukkit   | FULL (L1)   | `bin/run-bukkit-e2e.ps1`|
| folia    | TODO stub   | orchestrator            |
| velocity | TODO stub   | orchestrator            |
| bungee   | TODO stub   | orchestrator            |
| nukkit   | TODO stub   | orchestrator            |
| pnx      | TODO stub   | orchestrator            |
| sponge   | TODO stub   | orchestrator            |

Only **bukkit/Purpur** has a complete Layer-1 flow (backend + server + bot +
assertion). The other six platforms are dispatched as TODO stubs by
`run-e2e-orchestrator.ps1` and marked `continue-on-error: true` in
`.github/workflows/e2e.yml` so they never fail the workflow. To enable a
platform, port its script from `.e2e/bin/start-<platform>*.ps1` into
`e2e/bin/run-<platform>-e2e.ps1` (parameterise all absolute paths), add its
pin to `versions.lock.ps1`, and flip the job in `e2e.yml` to required.

## EULA policy (CI)

CI **silently accepts the Mojang EULA** by writing `eula=true` into the
throwaway per-run server directory at start time. This overrides the
"do-not-silently-accept" guidance in `docs/REAL-SERVER-E2E.md §4.3` per the
explicit CI policy: CI is a throwaway environment and the EULA file never
enters the repository. The harness does NOT commit a `eula.txt`; it generates
one on the fly and it dies with the run directory.

## Layout

```
e2e/
  versions.lock.ps1           # pinned server URLs + SHA-256
  README.md                   # this file
  bin/
    fetch-server.ps1          # download + SHA-256 verify (§4.2 of the doc)
    write-classpath.init.gradle  # export StarLink:core runtime classpath
    run-bukkit-e2e.ps1        # full bukkit/Purpur L1 orchestrator
    run-e2e-orchestrator.ps1  # multi-platform dispatcher (bukkit done, rest TODO)
  conf/
    novalink.template.yml     # backend config template (ports/keys templated)
  bot/
    package.json              # mineflayer dep
    run-e2e.js                # L1 bot: /nc commands + chat round-trip
```

Runtime artifacts (server jars, logs, world data) land under
`.e2e-artifacts/` at the repo root, which is gitignored.
