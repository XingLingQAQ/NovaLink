# ============================================================================
# versions.lock.ps1 -- pinned server versions + SHA-256 for E2E downloads.
#
# Every external server jar downloaded by the E2E harness is pinned here with a
# fixed URL + SHA-256. `fetch-server.ps1` verifies the hash before the jar is
# allowed to run. Bump the build number + SHA together when upgrading; never
# run with an empty/placeholder SHA.
#
# CI uses these pins via `actions/cache` keyed on this file's hash, so a lock
# change invalidates the server-jar cache and forces a fresh verified download.
# ============================================================================
$LockedServers = @(
    # Bukkit platform: Purpur 1.21.8 build 2497 (PaperMC API v2 sunset on this
    # network, Purpur is the reliable Paper-compatible downstream). JDK 21.
    # SHA-256 verified locally 2026-08-09.
    @{
        Name    = 'purpur'
        MC      = '1.21.8'
        Build   = '2497'
        Engine  = 'purpur'
        Url     = 'https://api.purpurmc.org/v2/purpur/1.21.8/builds/2497/downloads/purpur-1.21.8-2497.jar'
        Sha256  = 'DD7B227F2555601C39CEC091DF678275F0A81FA7ED68CE954EC4924BB4575C46'
        Eula    = $true
        Jdk     = 21
        # Server jar filename on disk under e2e/artifacts/dist/
        File    = 'purpur-1.21.8-2497.jar'
    }
    # Other platforms (folia, velocity, bungee/waterfall, nukkit, pnx, sponge)
    # are TODO -- add their pins here once their E2E scripts are ported from
    # the local .e2e/ harness. See e2e/README.md "Platform coverage".
)

function Get-LockedServer([string]$Name) {
    return $LockedServers | Where-Object { $_.Name -eq $Name } | Select-Object -First 1
}

# When dot-sourced (as fetch-server.ps1 does), the function + variable are
# injected into the caller's scope. Export-ModuleMember is only valid inside a
# .psm1 module, so it is intentionally omitted here.
