# ============================================================================
# run-e2e-orchestrator.ps1 -- multi-platform E2E dispatcher.
#
# This is the CI entry point. It dispatches to per-platform scripts.
#
# CURRENT COVERAGE (honest status):
#   bukkit      -- FULLY IMPLEMENTED (run-bukkit-e2e.ps1, L1 flow complete).
#   folia       -- IMPLEMENTED via run-multiplatform-e2e.ps1 (shared primitive).
#   velocity    -- IMPLEMENTED via run-multiplatform-e2e.ps1 (proxy-only; the
#                  downstream purpur it fronts is not started by this harness).
#   bungee      -- IMPLEMENTED via run-multiplatform-e2e.ps1 (proxy-only; same
#                  downstream-purpur caveat as velocity).
#   nukkit      -- IMPLEMENTED via run-multiplatform-e2e.ps1 (Bedrock bot).
#   pnx         -- IMPLEMENTED via run-multiplatform-e2e.ps1 (Bedrock bot).
#   sponge      -- IMPLEMENTED via run-multiplatform-e2e.ps1 (JDK 17).
#   endstone    -- IMPLEMENTED (run-endstone-e2e.ps1, BDS+Endstone+python plugin).
#   levilamina  -- IMPLEMENTED (run-levilamina-e2e.ps1, BDS+LeviLamina+C++ dll).
#   pmmp        -- IMPLEMENTED (run-pmmp-e2e.ps1, PocketMine-MP phar+PHP).
#
# The seven Java platforms (bukkit/folia/velocity/bungee/nukkit/pnx/sponge) are
# driven by the shared run-multiplatform-e2e.ps1 orchestrator under
# test/bin/multiplatform/, which calls the parameterized start-backend.ps1 +
# start-server.ps1 primitives (one backend JVM + one server process per
# platform) and then runs the test/bot/run-e2e.js or run-e2e-bedrock.js bot.
# bukkit keeps its self-contained run-bukkit-e2e.ps1 because that script also
# builds the bukkit plugin fat jar and installs it into the server's plugins/
# dir -- a step the shared primitive does not do (the other Java platforms are
# tested against the server alone, without their platform plugin installed;
# wiring the per-platform plugin build + install is tracked as a follow-up).
#
# Exit codes:
#   0 = every requested platform PASSED (a by-design SKIPPED does not block)
#   1 = a platform FAILED, or was SKIPPED (environment-blocked) while
#       -RequireAllPlatforms is set
#   2 = orchestrator/prereq failure before any platform result
#
# CHILD exit codes consumed here -- two distinct classes of SKIP, never merged:
#   0 = PASS
#   1 = FAIL
#   2 = prereq error
#   3 = SKIPPED              -- the platform could not run for an ENVIRONMENT /
#                              infrastructure reason (e.g. the BDS zip is
#                              unavailable). This is a problem to fix, so strict
#                              mode (-RequireAllPlatforms) counts it as a failure.
#   4 = SKIPPED-BY-DESIGN    -- the platform structurally cannot run its L1 flow
#                              (velocity/bungee: proxy-only, the downstream
#                              purpur they front is not started by this harness).
#                              Permanent by design, so strict mode must NOT fail
#                              it, and the summary says so explicitly.
#
# run-multiplatform-e2e.ps1 emits 4 for the by-design skip and 3 when nothing
# ran for environmental reasons. This script accepts BOTH codes because a
# producer that has not been migrated yet still emits only 3; treating an
# unknown code as FAIL is the honest default in that case (see the function).
# A SKIPPED of either class is never reported as PASS: the per-platform statuses
# are written to .e2e-artifacts/runs/e2e-orchestrator-summary.json.
# ============================================================================
[CmdletBinding()]
param(
    [string]$Platforms = "bukkit,pmmp",
    # Strict CI mode: an environment-blocked SKIPPED platform (exit 3) counts as
    # a failure. A SKIPPED-BY-DESIGN platform (exit 4 -- velocity/bungee are
    # proxy-only) never does: it is a permanent structural limit, not a defect.
    # This is OFF by default because even exit 3 is a normal local outcome (a
    # BDS-blocked dev box) and defaulting to fatal would make every offline/local
    # full run red. CI opts in explicitly, so a platform that ran ZERO assertions
    # for an infrastructure reason can never look green there.
    [switch]$RequireAllPlatforms,
    # Resolved in the body below, NOT as a parameter default: under
    # [CmdletBinding()] PowerShell binds parameters as an advanced function, and
    # every automatic variable ($MyInvocation / $PSScriptRoot / $PSCommandPath)
    # is still empty at bind time. A default here therefore evaluated to $null
    # and the Split-Path chain threw "Cannot bind argument to parameter 'Path'
    # because it is null", failing the whole E2E job before any platform ran.
    [string]$RepoRoot,
    [int]$TimeoutSec = 420,
    [int]$BotWaitSec = 150
)

$ErrorActionPreference = "Stop"

# Derive the repo root from this script's own location when the caller did not
# supply one (test/bin/ -> repo root is three levels up). $PSScriptRoot is
# populated once the script body runs, unlike in the param block above.
if (-not $RepoRoot) {
    $RepoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath))
}
$binDir = Join-Path $RepoRoot "test/bin"
$multiplatformDir = Join-Path $binDir "multiplatform"
$RequestedPlatforms = $Platforms -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }

function Log([string]$m) { Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m) }

$pathContract = Join-Path $binDir "verify-orchestration-paths.ps1"
if (-not (Test-Path -LiteralPath $pathContract -PathType Leaf)) {
    Log "ERROR: required harness contract is missing: $pathContract"
    exit 2
}
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $pathContract -RepoRoot $RepoRoot
if ($LASTEXITCODE -ne 0) {
    Log "ERROR: committed E2E harness preflight failed."
    exit 2
}

# --- generate the backend classpath the Java platforms need ------------------
# start-backend.ps1 launches the NovaLink backend with
# `java -cp <classpath> com.nova.link.NovaLinkMain`, reading that classpath from
# .e2e-artifacts/novalink-core.classpath.txt. Nothing produced that file on this
# path: the self-contained platform scripts (run-bukkit/endstone/levilamina/
# pmmp-e2e.ps1) each build it, but the shared multiplatform orchestrator
# deliberately does not ("It does NOT build the backend jars itself"), and
# run-e2e-orchestrator.ps1 -- which is what the Gradle realE2E task invokes --
# never did either. Every Java platform therefore failed prereq with
# "classpath file missing" once the earlier launcher bug was fixed.
#
# Produce it here, once, for the same reason bukkit does it inline: the
# multiplatform path has no other opportunity to run the build. Skipped when
# the file already exists so a re-run (or the bukkit script, which builds it
# itself) does not pay for it twice.
$classPathFile = Join-Path $RepoRoot ".e2e-artifacts/novalink-core.classpath.txt"
# The Java platforms are exactly those the multiplatform dispatcher handles:
# every known platform that is NOT served by a self-contained script. Checked
# against a literal list rather than $PlatformDefs because that table is defined
# further down this file; keep the two in sync when adding a platform. An
# unknown name is deliberately not treated as a Java platform -- it has no
# dispatch, so building the classpath for it would be pointless work.
$selfContained = @('bukkit', 'endstone', 'levilamina', 'pmmp')
$knownPlatforms = @('bukkit', 'endstone', 'levilamina', 'pmmp',
                    'folia', 'velocity', 'bungee', 'nukkit', 'pnx', 'sponge')
$javaPlatforms = $RequestedPlatforms | Where-Object {
    ($knownPlatforms -contains $_) -and ($selfContained -notcontains $_)
}
if ($javaPlatforms.Count -gt 0 -and -not (Test-Path $classPathFile)) {
    Log "=== building backend classpath (required by: $($javaPlatforms -join ', ')) ==="
    $gradlew = if ($env:OS -eq 'Windows_NT') { 'gradlew.bat' } else { './gradlew' }
    $buildLog = Join-Path $RepoRoot ".e2e-artifacts/classpath-build.log"
    New-Item -ItemType Directory -Force -Path (Split-Path $buildLog -Parent) | Out-Null
    $initScript = Join-Path $binDir "write-classpath.init.gradle"
    # Gradle writes "Picked up JAVA_TOOL_OPTIONS: ..." to stderr on every run.
    # Under $ErrorActionPreference = 'Stop' PowerShell turns a native command's
    # stderr into a terminating NativeCommandError, so the classpath build died
    # before gradle ever compiled anything (folia/velocity/bungee/nukkit/pnx/
    # sponge all failed here, 53s in). Relax it for the duration of the call and
    # judge the outcome by the exit code, which is what actually reports failure.
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & (Join-Path $RepoRoot $gradlew) ":StarLink:core:jar" ":StarLink:core:writeRuntimeClasspath" `
            "--init-script" $initScript `
            "-Porg.gradle.java.installations.paths=" "-Dorg.gradle.java.installations.paths=" `
            "--console=plain" 1>$buildLog 2>&1
    } finally {
        $ErrorActionPreference = $prevEap
    }
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $classPathFile)) {
        Log "ERROR: backend classpath build failed (exit $LASTEXITCODE); see $buildLog"
        Get-Content $buildLog -ErrorAction SilentlyContinue |
            Select-Object -Last 20 | ForEach-Object { Log "  $_" }
    } else {
        Log "  classpath written: $classPathFile"
    }
}

# Platform definitions table. Each entry maps a platform name to its run script
# and the contract used by the assertion layer.
#
# Two dispatch paths:
#   * Self-contained scripts (run-<platform>-e2e.ps1) for platforms whose
#     orchestrator also builds + installs the platform plugin (bukkit) or that
#     need non-JVM server setup (endstone/levilamina/pmmp: BDS zip / PHP phar).
#     Invoked directly with -RepoRoot.
#   * The shared multiplatform orchestrator (run-multiplatform-e2e.ps1) for the
#     seven Java platforms (bukkit/folia/velocity/bungee/nukkit/pnx/sponge).
#     It calls start-backend.ps1 + start-server.ps1 + the bot, enforces a
#     per-platform timeout, and emits a combined summary JSON. Invoked with
#     -RepoRoot + -Platforms <the subset to run>.
#     bukkit is in BOTH tables: it has its self-contained script (preferred for
#     the full plugin-build+install flow) AND a multiplatform entry (so a
#     -Platforms bukkit,folia,... run can bundle it with the others).
#
# Fields:
#   Dispatch          - "self" (run-<platform>-e2e.ps1) | "multiplatform"
#   BackendScript     - the per-platform orchestrator script (for "self" only).
#   BotScript         - the bot script the orchestrator runs.
#   ServerReadyFile   - the log file whose readiness marker is grepped.
#   ServerReadyPattern - the regex that marks server readiness. NOTE: this key
#                       (and ServerReadyFile) is currently UNUSED -- the dispatch
#                       below only reads .Dispatch and .BackendScript, and every
#                       per-platform script owns its own readiness loop. Kept in
#                       step with those loops so a reader grepping the pattern
#                       here is not misled; do not rely on it to gate anything.
#   ResultsFile       - where the bot writes results.json.
#   ChatPhrase        - the chat phrase the bot sends for the round-trip.
#   Jdk               - the JDK the platform's server/backend runs on. Doc-only
#                       metadata: the field is not consumed by the dispatch
#                       below (run-multiplatform-e2e.ps1 owns the real runtime
#                       JDK selection). Kept in step with versions.lock.ps1 and
#                       that script's $PlatformDefs so the table cannot mislead.
$PlatformDefs = @{
    bukkit = @{
        Dispatch           = "self"
        BackendScript      = "run-bukkit-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name purpur")
        BotScript          = "run-e2e.js"
        ServerReadyFile    = "paper/purpur.stdout.log"
        ServerReadyPattern = 'Done \('
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
    folia = @{
        Dispatch           = "multiplatform"
        BackendScript      = "run-multiplatform-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name folia")
        BotScript          = "run-e2e.js"
        ServerReadyFile    = "folia/folia.stdout.log"
        ServerReadyPattern = 'Done \('
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 25
    }
    velocity = @{
        Dispatch           = "multiplatform"
        BackendScript      = "run-multiplatform-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name velocity")
        BotScript          = "run-e2e.js"
        ServerReadyFile    = "velocity/velocity.stdout.log"
        ServerReadyPattern = 'Done|Listening on'
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 25
    }
    bungee = @{
        Dispatch           = "multiplatform"
        BackendScript      = "run-multiplatform-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name waterfall")
        BotScript          = "run-e2e.js"
        ServerReadyFile    = "bungee/waterfall.stdout.log"
        ServerReadyPattern = 'Listening on'
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
    nukkit = @{
        Dispatch           = "multiplatform"
        BackendScript      = "run-multiplatform-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name nukkit")
        BotScript          = "run-e2e-bedrock.js"
        ServerReadyFile    = "nukkit/nukkit.stdout.log"
        ServerReadyPattern = 'Done|Default game mode'
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
    pnx = @{
        Dispatch           = "multiplatform"
        BackendScript      = "run-multiplatform-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name nukkit")
        BotScript          = "run-e2e-bedrock.js"
        ServerReadyFile    = "pnx/nukkit.stdout.log"
        ServerReadyPattern = 'Done|Default game mode'
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
    sponge = @{
        Dispatch           = "multiplatform"
        BackendScript      = "run-multiplatform-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name sponge")
        BotScript          = "run-e2e.js"
        ServerReadyFile    = "sponge/sponge.stdout.log"
        ServerReadyPattern = 'Sponge server started|Done \('
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
    # ---- Three non-Java Bedrock platforms (added 2026-08-09) ----
    # endstone: BDS server + Endstone python host + novachat_endstone pip plugin.
    # Server is native (BDS); backend uses JDK21. Bot is bedrock-protocol.
    endstone = @{
        Dispatch           = "self"
        BackendScript      = "run-endstone-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name bds")
        BotScript          = "run-e2e-bedrock.js"
        ServerReadyFile    = "bds/bds.stdout.log"
        ServerReadyPattern = 'Server started\.|Done \('
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
    # levilamina: BDS server + LeviLamina C++ mod loader + novachat-levilamina.dll.
    # Server is native (BDS); backend uses JDK21. Bot is bedrock-protocol.
    levilamina = @{
        Dispatch           = "self"
        BackendScript      = "run-levilamina-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name bds-levilamina")
        BotScript          = "run-e2e-bedrock.js"
        ServerReadyFile    = "bds/bds.stdout.log"
        # BDS prints "Server started in (1.8s)!" -- no period after "started",
        # so 'Server started\.' can never match. Matches run-levilamina-e2e.ps1.
        ServerReadyPattern = 'Server started|Done \('
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
    # pmmp: PocketMine-MP phar + PHP runtime + NovaChat PHP source plugin.
    # Server is PHP (phar); backend uses JDK21. Bot is bedrock-protocol.
    pmmp = @{
        Dispatch           = "self"
        BackendScript      = "run-pmmp-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name pocketmine -Auto")
        BotScript          = "run-e2e-bedrock.js"
        ServerReadyFile    = "pocketmine/pmmp.stdout.log"
        ServerReadyPattern = 'Done \('
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
}

# The default platforms list includes every platform with an IMPLEMENTED
# dispatch. The seven Java platforms route through run-multiplatform-e2e.ps1;
# the three Bedrock platforms (endstone/levilamina/pmmp) + bukkit use their
# self-contained scripts. Pass -Platforms folia,velocity,... to run a subset.
$Implemented = @('bukkit','endstone','levilamina','pmmp')

# Partition the requested platforms into "self" (one script per platform) and
# "multiplatform" (one shared orchestrator run for the whole Java subset). The
# shared orchestrator takes the Java platforms as a single -Platforms arg so it
# can reuse one backend-JVM + bot-deps install across the batch when desired.
$SelfPlatforms   = @()
$MultiPlatforms = @()
foreach ($p in $RequestedPlatforms) {
    $def = $PlatformDefs[$p]
    if (-not $def) {
        Log "ERROR: unknown platform '$p'. Known: $($PlatformDefs.Keys -join ', ')"
        $SelfPlatforms += $p   # surface the error in the loop below
        continue
    }
    if ($def.Dispatch -eq 'multiplatform') {
        $MultiPlatforms += $p
    } else {
        $SelfPlatforms += $p
    }
}

$results = @()

# Exit code contract shared with the per-platform scripts: 0 PASS, 1 FAIL,
# 2 prereq error, 3 SKIPPED (environment-blocked, no test ran), 4
# SKIPPED-BY-DESIGN (structurally cannot run, no test ran).
#
# 3 and 4 are separate terminal states on purpose. Both mean "zero assertions
# executed", so neither is ever collapsed into PASS -- but only 3 is a problem:
# 3 is an infrastructure block someone must fix (BDS download, missing runtime),
# while 4 is a permanent design limit (proxy-only platforms). Merging them made
# strict mode red the proxy jobs every night for a condition no commit can fix.
function Convert-ExitCodeToStatus([int]$exitCode) {
    switch ($exitCode) {
        0 { return 'PASS' }
        3 { return 'SKIPPED' }
        4 { return 'SKIPPED-BY-DESIGN' }
        default { return 'FAIL' }
    }
}

# A SKIPPED leaf script records why in <runsDir>/skip-reason.txt so the summary
# can say "SKIPPED (BDS unavailable)" instead of a bare SKIPPED. Both skip
# classes use the same file: the reason is what tells the reader which one it is
# when the exit code alone is not enough.
function Read-SkipReason([string]$platform) {
    $reasonFile = Join-Path $RepoRoot ".e2e-artifacts/runs/$platform/skip-reason.txt"
    if (Test-Path -LiteralPath $reasonFile -PathType Leaf) {
        return (Get-Content -LiteralPath $reasonFile -Raw -ErrorAction SilentlyContinue).Trim()
    }
    return ""
}

# True for both skip classes (3 and 4). Use this for "did any assertion run?"
# checks; use an explicit -eq against one status when the classes differ in
# consequence (only 'SKIPPED' is a strict-mode failure).
function Test-SkipStatus([string]$status) {
    return ($status -eq 'SKIPPED' -or $status -eq 'SKIPPED-BY-DESIGN')
}

# --- dispatch the self-contained platforms (one script per platform) ---------
foreach ($p in $SelfPlatforms) {
    Log "=== platform: $p ==="
    $def = $PlatformDefs[$p]
    if (-not $def) {
        $results += [pscustomobject]@{ platform = $p; exitCode = 2; status = 'UNKNOWN'; skippedReason = '' }
        continue
    }
    $script = Join-Path $binDir $def.BackendScript
    if (-not (Test-Path $script)) {
        Log "ERROR: required platform script is missing: $script"
        $results += [pscustomobject]@{ platform = $p; exitCode = 2; status = 'MISSING-HARNESS'; skippedReason = '' }
        continue
    }
    # A skip-reason file is per-run; remove any stale one so a previous run's
    # reason cannot be attributed to this one.
    $reasonFile = Join-Path $RepoRoot ".e2e-artifacts/runs/$p/skip-reason.txt"
    Remove-Item -LiteralPath $reasonFile -Force -ErrorAction SilentlyContinue
    # Invoke the child script with the call operator rather than Start-Process.
    # Start-Process builds a fresh environment block and throws
    # "Item has already been added. Key in dictionary: 'NO_PROXY' Key being
    # added: 'no_proxy'" on any host whose environment holds two variables that
    # differ only by case -- which a Windows box configured with proxy settings
    # commonly does. CI runners do not have that shape, so this is portability
    # rather than a CI fix; it also keeps the harness debuggable on a dev
    # machine instead of failing before any platform runs.
    #
    # The call operator inherits the environment as-is and propagates the
    # child's exit code through $LASTEXITCODE. Verified that a child writing to
    # stderr does not abort this script even with $ErrorActionPreference = "Stop"
    # set above (native-command stderr becomes a non-terminating error record).
    # The multiplatform orchestrator cannot take this route for its own children
    # because it needs file-redirected output -- see the note on
    # Invoke-StartScript there.
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script `
        -RepoRoot $RepoRoot -BotTimeoutSec $BotWaitSec
    $selfExit = $LASTEXITCODE
    $selfStatus = Convert-ExitCodeToStatus $selfExit
    $selfReason = if (Test-SkipStatus $selfStatus) { Read-SkipReason $p } else { '' }
    $results += [pscustomobject]@{ platform = $p; exitCode = $selfExit; status = $selfStatus; skippedReason = $selfReason }
}

# --- dispatch the Java platforms via the shared multiplatform orchestrator ----
if ($MultiPlatforms.Count -gt 0) {
    $mpScript = Join-Path $multiplatformDir "run-multiplatform-e2e.ps1"
    if (-not (Test-Path $mpScript)) {
        Log "ERROR: run-multiplatform-e2e.ps1 not found at $mpScript"
        foreach ($p in $MultiPlatforms) {
            $results += [pscustomobject]@{ platform = $p; exitCode = 2; status = 'MISSING-HARNESS'; skippedReason = '' }
        }
    } else {
        $mpPlatforms = $MultiPlatforms -join ','
        Log "=== multiplatform batch: $mpPlatforms ==="
        # The batch's summary is per-run; drop a stale one so a crashed batch
        # cannot leave the previous run's PASS rows behind to be read below.
        $mpSummaryFile = Join-Path $RepoRoot ".e2e-artifacts/runs/e2e-summary.json"
        Remove-Item -LiteralPath $mpSummaryFile -Force -ErrorAction SilentlyContinue
        # Call operator, not Start-Process -- see the note above.
        # Build the array element-by-element. `$a = if (...) { @('-flag') } else { @() }`
        # does NOT produce an array: an if-statement unrolls its output into the
        # pipeline, so the one-element array collapses to a bare string. Splatting
        # that string passes individual characters as arguments ("it is not
        # possible to bind argument ... name" / the switch never reaches the
        # child), which silently made strict mode unreachable. Verified with a
        # minimal repro on PowerShell 5.1.
        $mpStrictArgs = @()
        if ($RequireAllPlatforms) { $mpStrictArgs += "-RequireAllPlatforms" }
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $mpScript `
            -Platforms $mpPlatforms -RepoRoot $RepoRoot `
            -TimeoutSec $TimeoutSec -BotWaitSec $BotWaitSec @mpStrictArgs
        $mpExit = $LASTEXITCODE
        # The batch reports one exit code for N platforms, but the platforms do
        # not share a fate: velocity/bungee SKIP by design while a sibling FAILs.
        # Read the batch's per-platform summary JSON so each row keeps its own
        # status instead of inheriting a blanket PASS/FAIL.
        $mpRows = @()
        if (Test-Path -LiteralPath $mpSummaryFile -PathType Leaf) {
            try {
                # Windows PowerShell 5.1's ConvertFrom-Json returns a JSON array
                # as ONE Object[] value; wrapping that in @() yields a nested
                # array, and member enumeration then makes $row.status an array
                # ("System.Object[]" in the summary). Enumerate explicitly.
                $parsed = Get-Content -LiteralPath $mpSummaryFile -Raw | ConvertFrom-Json
                $parsed | ForEach-Object { $mpRows += $_ }
            } catch {
                Log "WARN: could not parse multiplatform summary JSON: $($_.Exception.Message)"
            }
        }
        foreach ($p in $MultiPlatforms) {
            $row = $mpRows | Where-Object { $_.platform -eq $p } | Select-Object -First 1
            if ($row) {
                # The batch writes its own status strings. Accept BOTH skip
                # classes here: 'SKIPPED-BY-DESIGN' for the proxy-only entries
                # (velocity/bungee, from a $PlatformDefs Note) and 'SKIPPED' for
                # an environment block. A batch that has not been migrated yet
                # still writes the bare 'SKIPPED' for both -- that is why this
                # switch keeps a SKIPPED arm instead of rejecting it.
                $mpStatus = $row.status
                $mpReason = if (Test-SkipStatus $mpStatus) { $row.notes } else { '' }
                # PARTIAL maps to 0 like the batch does: only FAIL reds the run.
                # The exit code is re-derived from the status so the strict-mode
                # computation below sees the same code the status implies.
                $mpRowExit = switch ($mpStatus) {
                    'FAIL'              { 1 }
                    'SKIPPED'           { 3 }
                    'SKIPPED-BY-DESIGN' { 4 }
                    default             { 0 }
                }
                $results += [pscustomobject]@{ platform = $p; exitCode = $mpRowExit; status = $mpStatus; skippedReason = $mpReason }
            } else {
                # No per-platform row: fall back to the batch exit code.
                $mpStatus = Convert-ExitCodeToStatus $mpExit
                $results += [pscustomobject]@{ platform = $p; exitCode = $mpExit; status = $mpStatus; skippedReason = '' }
            }
        }
    }
}

Log "=== E2E summary ==="
# Print the honest terminal state. A skipped platform of EITHER class executed
# zero assertions, so it is never rendered as PASS -- the reason is printed next
# to it, and the status string itself says which kind of skip it was.
$results | ForEach-Object {
    if ((Test-SkipStatus $_.status) -and $_.skippedReason) {
        Log ("  {0,-12} {1} ({2})" -f $_.platform, $_.status, $_.skippedReason)
    } else {
        Log ("  {0,-12} {1}" -f $_.platform, $_.status)
    }
}
# @() is required: with exactly ONE failing platform, Where-Object returns a
# scalar PSCustomObject rather than an array, and .Count on a scalar is $null --
# so `$null -gt 0` is False and the orchestrator would exit 0 (a silently green
# E2E run) for the single-failure case. Wrapping in @() guarantees an array.
$failures = @($results | Where-Object { $_.status -eq 'FAIL' -or $_.status -eq 'UNKNOWN' -or $_.status -eq 'MISSING-HARNESS' })
# The two skip classes are counted separately. Only $skipped (environment) can
# fail the run under strict mode; $skippedByDesign is reported but never fatal.
$skipped         = @($results | Where-Object { $_.status -eq 'SKIPPED' })
$skippedByDesign = @($results | Where-Object { $_.status -eq 'SKIPPED-BY-DESIGN' })
Log ("=== {0} platform(s) requested, {1} passed, {2} skipped, {3} skipped-by-design, {4} failed ===" -f `
    $results.Count, ($results.Count - $skipped.Count - $skippedByDesign.Count - $failures.Count), `
    $skipped.Count, $skippedByDesign.Count, $failures.Count)
if ($skippedByDesign.Count -gt 0) {
    Log "SKIPPED-BY-DESIGN (structural limit, not a failure): $(($skippedByDesign | ForEach-Object { $_.platform }) -join ', ')"
}
if ($RequireAllPlatforms -and $skipped.Count -gt 0) {
    Log "STRICT MODE (-RequireAllPlatforms): environment-blocked SKIPPED platform(s) count as failures: $(($skipped | ForEach-Object { $_.platform }) -join ', ')"
}

# Machine-readable summary. 'status' is the explicit terminal state
# (PASS/FAIL/SKIPPED/SKIPPED-BY-DESIGN/...); 'skipped' makes the SKIP outcome
# unambiguous for consumers that only look at booleans, and 'skippedReason'
# explains why. 'skippedByDesign' separates a permanent structural limit from an
# environment block so a consumer can tell them apart without parsing 'status'.
# Both flags are true for their respective class only -- a SKIPPED row has
# skipped=true / skippedByDesign=false, and vice versa.
$summaryFile = Join-Path $RepoRoot ".e2e-artifacts/runs/e2e-orchestrator-summary.json"
$summaryDir = Split-Path -Parent $summaryFile
if (-not (Test-Path $summaryDir)) { New-Item -ItemType Directory -Force -Path $summaryDir | Out-Null }
$summaryRows = $results | ForEach-Object {
    [pscustomobject]@{
        platform        = $_.platform
        status          = $_.status
        exitCode        = $_.exitCode
        skipped         = ($_.status -eq 'SKIPPED')
        skippedByDesign = ($_.status -eq 'SKIPPED-BY-DESIGN')
        skippedReason   = $_.skippedReason
    }
}
# Write UTF-8 WITHOUT a BOM: Windows PowerShell 5.1's -Encoding utf8 emits one,
# and Groovy's JsonSlurper (build.gradle reads this file) rejects a BOM.
$summaryJson = $summaryRows | ConvertTo-Json -Depth 5
[System.IO.File]::WriteAllText($summaryFile, $summaryJson, (New-Object System.Text.UTF8Encoding($false)))
Log "Summary written to: $summaryFile"

# Strict mode fails ONLY the environment-blocked class. A SKIPPED-BY-DESIGN
# platform is a permanent structural limit (velocity/bungee are proxy-only), so
# counting it here would red the nightly forever for a condition no commit can
# fix -- the exact noise that made the old signal unreadable.
$strictFailure = $RequireAllPlatforms -and $skipped.Count -gt 0
if ($failures.Count -gt 0 -or $strictFailure) { exit 1 } else { exit 0 }
