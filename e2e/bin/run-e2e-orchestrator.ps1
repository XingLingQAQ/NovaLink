# ============================================================================
# run-e2e-orchestrator.ps1 -- multi-platform E2E dispatcher.
#
# This is the CI entry point. It dispatches to per-platform scripts.
#
# CURRENT COVERAGE (honest status):
#   bukkit   -- FULLY IMPLEMENTED (run-bukkit-e2e.ps1, L1 flow complete).
#   folia    -- TODO stub (exits with a clear "not implemented" message).
#   velocity -- TODO stub
#   bungee   -- TODO stub
#   nukkit   -- TODO stub
#   pnx      -- TODO stub
#   sponge   -- TODO stub
#
# The TODO platforms print a message and exit 0 so a matrix job that includes
# them does not fail the whole workflow; the per-platform job in e2e.yml uses
# continue-on-error for the stubs so the real signal is visible per-platform.
# ============================================================================
[CmdletBinding()]
param(
    [string]$Platforms = "bukkit",
    [string]$RepoRoot  = (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
)

$ErrorActionPreference = "Stop"
$binDir = Join-Path $RepoRoot "e2e/bin"
$platforms = $Platforms -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }

function Log([string]$m) { Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m) }

$results = @()
foreach ($p in $platforms) {
    Log "=== platform: $p ==="
    switch ($p) {
        'bukkit' {
            $script = Join-Path $binDir "run-bukkit-e2e.ps1"
            & powershell -NoProfile -ExecutionPolicy Bypass -File $script -RepoRoot $RepoRoot
            $results += [pscustomobject]@{ platform = $p; exitCode = $LASTEXITCODE; status = if ($LASTEXITCODE -eq 0) { 'PASS' } else { 'FAIL' } }
        }
        default {
            Log "TODO: platform '$p' not yet implemented in the committed e2e/ harness."
            Log "       The local .e2e/ (gitignored) has a working script; port it to e2e/bin/."
            $results += [pscustomobject]@{ platform = $p; exitCode = 0; status = 'TODO-STUB' }
        }
    }
}

Log "=== E2E summary ==="
$results | ForEach-Object { Log ("  {0,-10} {1}" -f $_.platform, $_.status) }
$anyFail = ($results | Where-Object { $_.status -eq 'FAIL' }).Count -gt 0
if ($anyFail) { exit 1 } else { exit 0 }
