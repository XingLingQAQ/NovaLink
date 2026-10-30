# ============================================================================
# e2e-assertions.ps1 -- shared result assertions for the L1 E2E harnesses.
#
# Dot-source this file next to versions.lock.ps1:
#     . (Join-Path $binDir 'e2e-assertions.ps1')
#
# It defines functions only; it starts nothing and touches no files.
#
# Each function here exists because the assertion it replaces could not fail:
#
#   Get-BotReceivedRows -- run-e2e.js / run-e2e-bedrock.js record every
#     outgoing command and chat line into results.json's `received[]` with
#     kind='sent'. Assertions built from the raw list were therefore satisfied
#     by the bot's own input: "$msgs -match 'join'" matched the bot's own
#     "/nc join global", and "hello from e2e bot" matched the bot's own chat
#     line. Those checks passed whether or not a plugin was installed.
#
#   Test-ServerLogFatal -- plugin exceptions are written to the SERVER's
#     console (JVM stack traces / Python tracebacks / PHP fatals), never into
#     the bot's chat transcript. Scanning received text for "Exception" named
#     the server log and then read something else, so it was a constant true.
#
#   Test-BackendEvidence -- the "/nc who" prompt is the one line every
#     platform prints only AFTER its backend gate accepts (see the comment on
#     the function). It separates "the plugin loaded and answers commands"
#     from "the plugin is really talking to the backend".
# ============================================================================

# Rows the bot received, with its own outgoing traffic removed.
#
# results.json holds a single object rather than an array when the bot only
# recorded one row, so both shapes are normalised to an array.
function Get-BotReceivedRows {
    param($Results)
    if ($null -eq $Results -or $null -eq $Results.received) { return @() }
    return @($Results.received | Where-Object { $null -ne $_ -and $_.kind -ne 'sent' })
}

# The text of every received row, in order. Convenience for -match assertions.
function Get-BotReceivedTexts {
    param($Rows)
    return @($Rows | ForEach-Object { [string]$_.text })
}

# Received text with colour codes stripped.
#
# The two bots record what the server put on the wire, and that differs by
# platform: mineflayer hands back chat already rendered without formatting
# ("[Global] Name: text"), while bedrock-protocol preserves the section-sign
# codes ("§c[全服] §7Name§f: text"). Matching a channel-format envelope against
# the raw text therefore has to tolerate §x sequences, or it silently fails on
# every Bedrock platform. Both the § (U+00A7) and the plugin's internal & form
# are removed, with the single character that follows them.
function Get-BotReceivedPlainTexts {
    param($Rows)
    $pattern = [string][char]0x00A7 + '.|&[0-9a-fk-orA-FK-OR]'
    return @($Rows | ForEach-Object { [regex]::Replace([string]$_.text, $pattern, '') })
}

# Rows whose plain text matches $Pattern, for kind-aware assertions.
function Select-BotRowsByPlainText {
    param($Rows, [string]$Pattern)
    $result = @()
    foreach ($row in $Rows) {
        $plain = [regex]::Replace([string]$row.text,
            [string][char]0x00A7 + '.|&[0-9a-fk-orA-FK-OR]', '')
        if ($plain -match $Pattern) { $result += $row }
    }
    return $result
}

# Plugin-liveness evidence: a message that only NovaChat can emit.
#
# "NovaChat" is the brand token carried by every platform's message prefix
# (MessagePrefixes.PREFIX on the JVM side; "[NovaChat]" in the bedrock and
# PMMP configs; the help header on all of them). No vanilla server, and no
# other plugin in these run directories, produces that token, so a hit proves
# NovaChat itself wrote a line to this bot -- which is what "the plugin is
# installed, enabled, and serving this player" means.
function Test-PluginEvidence {
    param($Rows)
    foreach ($row in $Rows) {
        if ([string]$row.text -match 'NovaChat') { return $true }
    }
    return $false
}

# Backend-link evidence: the "/nc who" progress prompt, which every platform
# prints only after its backend gate accepts:
#   bukkit      AbstractSubCommand::checkConnection -> isConnected + isAuthenticated,
#               then WhoCommandService.getFetchingPrompt ("Fetching online members of channel")
#   endstone    NetworkClient.is_connected, then chat.who.fetching
#   levilamina  NetworkClient::isConnected(), then chat.who.fetching
#   pmmp        NetworkClient::isAuthenticated(), then "Fetching online members for"
# When the backend is unreachable each of them prints a DIFFERENT message
# (not-connected / unavailable / "query is unavailable"), so this line is the
# discriminator between "plugin loaded" and "plugin really reaches the backend".
#
# Both locales are accepted because the harnesses pin the plugin locale
# differently (bukkit writes locale: en_US; endstone/levilamina default to
# zh_CN; PMMP prints English unconditionally).
function Test-BackendEvidence {
    param($Rows)
    foreach ($row in $Rows) {
        if ([string]$row.text -match 'Fetching online members|正在获取频道') { return $true }
    }
    return $false
}

# First fatal line in the SERVER's own log, or $null when clean.
#
# Patterns are deliberately narrow so a healthy run cannot trip them:
#   \bFATAL\b                          -- BDS fatal banner
#   Traceback (most recent call last)  -- uncaught Python exception (endstone)
#   at com.nova.                       -- a JVM stack frame inside the plugin
#   Fatal error                        -- PHP fatal
#   Unhandled exception                -- PocketMine's uncaught-exception banner
# A routine command error (e.g. "[NC-433] Not in this channel") is logged as a
# warning by every platform and does not match any of these.
#
# These patterns were checked against the healthy logs already on disk under
# .e2e-artifacts/joint/*/round-1 (bukkit, nukkit, backend stdout+stderr): zero
# hits, so a passing run is not tripped by them.
#
# A missing log file yields $null (no fatal): the harness only calls this after
# the server reached its ready marker, and treating an unreadable file as
# failure would turn a healthy platform red on an I/O hiccup.
function Test-ServerLogFatal {
    param([string[]]$Paths)
    # FTL is LeviLamina's fatal level (it never prints "FATAL"), so a mod that
    # refuses to load -- e.g. "Failed to load mod ..." -- was invisible here and
    # the run only reddened via the much less direct bot assertions.
    $pattern = '\bFATAL\b|\bFTL\b|Failed to load mod|Traceback \(most recent call last\)|\bat com\.nova\.|Fatal error|Unhandled exception'
    foreach ($p in $Paths) {
        if (-not $p) { continue }
        if (-not (Test-Path -LiteralPath $p)) { continue }
        $hit = Select-String -LiteralPath $p -Pattern $pattern -List -ErrorAction Stop
        if ($hit) { return $hit }
    }
    return $null
}

function Initialize-BotRun {
    param([string]$ResultsFile)
    if (Test-Path -LiteralPath $ResultsFile) {
        if (-not (Test-Path -LiteralPath $ResultsFile -PathType Leaf)) { throw "Bot result path is not a file: $ResultsFile" }
        Remove-Item -LiteralPath $ResultsFile -Force
    }
    $env:E2E_RUN_ID = [guid]::NewGuid().ToString('N')
    return $env:E2E_RUN_ID
}

function Get-BotRunFailure {
    param(
        $Results,
        $ExitCode,
        [bool]$TimedOut = $false,
        [string]$ExpectedRunId,
        [string[]]$ServerLogPaths = @()
    )
    if ($TimedOut) { return 'bot exceeded the runner deadline' }
    if ($null -eq $ExitCode) { return 'bot exit code is unavailable' }
    if ($ExitCode -ne 0) { return "bot exited with code $ExitCode" }
    if ($null -eq $Results) { return 'bot results are missing' }
    if (-not $ExpectedRunId -or -not $Results.PSObject.Properties['runId'] -or $Results.runId -ne $ExpectedRunId) { return 'bot results belong to another run' }
    if ($Results.PSObject.Properties['timedOut'] -and $Results.timedOut) { return 'bot reported a timeout' }
    if ($Results.PSObject.Properties['error'] -and $Results.error) { return "bot reported an error: $($Results.error)" }
    if (-not $Results.PSObject.Properties['sequenceComplete'] -or $Results.sequenceComplete -isnot [bool] -or -not $Results.sequenceComplete) { return 'bot sequence did not finish' }
    if (-not $Results.PSObject.Properties['received']) { return 'bot traffic is missing' }
    $rows = @(Get-BotReceivedRows $Results)
    if (-not @($rows | Where-Object { $_.kind -eq 'spawn' }).Count) { return 'bot never spawned' }
    $failure = $rows | Where-Object { $_.kind -in @('error', 'kicked') } | Select-Object -First 1
    if ($failure) { return "bot $($failure.kind): $($failure.text)" }
    foreach ($logPath in $ServerLogPaths) {
        if (-not (Test-Path -LiteralPath $logPath -PathType Leaf)) { return "server log is missing: $logPath" }
    }
    try { $fatal = Test-ServerLogFatal $ServerLogPaths } catch { return "server log could not be read: $($_.Exception.Message)" }
    if ($fatal) { return "fatal server log at $($fatal.Path):$($fatal.LineNumber): $($fatal.Line.Trim())" }
    return $null
}

# Whether the bot's npm dependencies are present AND match the lock file.
#
# The existence-only guard (`Test-Path node_modules/bedrock-protocol`) that the
# platform runners used to carry is not enough: it is satisfied by ANY installed
# copy, including one left behind by an earlier checkout. That is not a
# hypothetical -- measured on this host, test/bot/node_modules held
# bedrock-protocol 3.58.1 / minecraft-data 3.113.1 while package.json declares
# 3.60.1 / 3.117.0. A 3.58.x install has no NetherNet transport at all, so the
# Bedrock bots cannot reach a 1.26.52 BDS, and because the guard never ran
# `npm ci` the runner silently used the stale tree. The same shape would hide a
# downgrade after any future bump.
#
# `npm ls --depth=0` reports the INSTALLED versions and exits non-zero when they
# disagree with package.json/lock, so it is the cheapest honest check. It exits
# 0 both when everything matches and (with a warning) when a peer is merely
# optional, which is the behaviour the runners want.
#
# A missing node_modules, a missing npm, or a non-zero `npm ls` all mean "run
# npm ci". Erring toward reinstalling is deliberate: a needless `npm ci` costs
# seconds, a stale tree costs a red platform that looks like a product bug.
function Test-BotDepsCurrent {
    param([string]$BotDir)
    if (-not (Test-Path -LiteralPath $BotDir)) { return $false }
    if (-not (Test-Path -LiteralPath (Join-Path $BotDir 'node_modules'))) { return $false }
    if (-not (Test-Path -LiteralPath (Join-Path $BotDir 'package.json'))) { return $false }
    $npm = Get-Command npm -ErrorAction SilentlyContinue
    if (-not $npm) { return $false }
    Push-Location $BotDir
    try {
        & $npm.Source ls --depth=0 --silent 2>&1 | Out-Null
        return ($LASTEXITCODE -eq 0)
    } catch {
        return $false
    } finally {
        Pop-Location
    }
}
