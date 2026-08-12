# ============================================================================
# versions.lock.ps1 -- pinned + auto server versions for E2E downloads.
#
# Two modes per platform (see fetch-server.ps1):
#   * PINNED (default): Url + Sha256 are fixed here. fetch-server.ps1 enforces
#     the hash before the jar is allowed to run. Bump Build+Sha256 together
#     when upgrading; never run with an empty/placeholder SHA.
#   * Auto=$true: fetch-server.ps1 -Auto calls the platform's API at runtime to
#     discover the latest build + URL, downloads it, and computes SHA-256 on
#     the fly. The lock entry still supplies a fallback Url+Sha256+Build (used
#     when -Auto is NOT passed, or when the auto-detect API is unreachable so
#     the run degrades to the last-known-good pin instead of hard-failing).
#
# CI uses these pins via `actions/cache` keyed on this file's hash, so a lock
# change invalidates the server-jar cache and forces a fresh verified download.
#
# Reachability notes (verified 2026-08-09 via proxy 127.0.0.1:7890):
#   - api.purpurmc.org ............ 200 (JSON, works). Download URL pattern is
#     /v2/purpur/<MC>/<build>/download (singular, no filename; the OLD
#     /builds/<b>/downloads/<file>.jar path 404s as of 2026-08-09).
#   - api.papermc.io v2 ........... 410 GONE (sunset). v3 is 403 (Cloudflare).
#     BUT papermc.io/downloads/<project> HTML embeds content-addressed URLs on
#     https://fill-data.papermc.io/v1/objects/<sha256>/<file>.jar -- the SHA is
#     IN the URL path. fetch-server.ps1 scrapes that page in Auto mode.
#   - ci.opencollab.dev Jenkins ... 502 (down). Cloudburst maven mirror works:
#     repo.opencollab.dev/maven-snapshots/cn/nukkit/nukkit/1.0-SNAPSHOT/
#     maven-metadata.xml -> latest timestamp+buildNumber.
#   - repo.spongepowered.org ...... 200 (maven-releases). The *universal* jar
#     (runnable server) is NOT found by the maven /search REST API (that only
#     returns API/library jars); scrape spongepowered.org/downloads/spongevanilla
#     for the universal-jar URL list, or pin a known-good version.
#   - update.pmmp.io .............. 200 JSON (base_version + download_url).
#     download_url -> github.com/pmmp/PocketMine-MP/releases/download/<ver>/
#     PocketMine-MP.phar (asset CDN reachable; api.github.com itself is 403).
#   - minecraft.net BDS ........... BLOCKED from this env (pocketbedlinks
#     endpoints drop the connection even with a browser UA; the download page
#     is JS-rendered with no static URL). BDS pin is a documented placeholder
#     URL pattern; -Auto logs the detection approach + skips live download.
# ============================================================================
$LockedServers = @(
    # =========================================================================
    # Java platforms
    # =========================================================================

    # Bukkit platform: Purpur 1.21.8 build 2497. JDK 21.
    # Plugin declares api-version "1.21" (NovaChat/Plugin/bukkit/.../plugin.yml).
    # Auto-detect: api.purpurmc.org/v2/purpur/<MC> -> builds.latest, then
    #   download = https://api.purpurmc.org/v2/purpur/<MC>/<build>/download
    #   (NOTE: the OLD /builds/<build>/downloads/<file>.jar path 404s as of
    #   2026-08-09; the current pattern is /<MC>/<build>/download, singular,
    #   no filename. The website purpurmc.org/downloads links to this pattern.)
    # SHA-256 verified locally 2026-08-09 (matches the .e2e cached jar).
    @{
        Name    = 'purpur'
        MC      = '1.21.8'
        Build   = '2497'
        Engine  = 'purpur'
        Url     = 'https://api.purpurmc.org/v2/purpur/1.21.8/2497/download'
        Sha256  = 'DD7B227F2555601C39CEC091DF678275F0A81FA7ED68CE954EC4924BB4575C46'
        Eula    = $true
        Jdk     = 21
        File    = 'purpur-1.21.8-2497.jar'
        Kind    = 'jar'
        Auto    = $true
    }

    # Folia: 1.21.11 is not a published Purpur-style MC; PaperMC's latest stable
    # folia build is 26.1.2-8 (MC 26.1.2). Plugin declares api-version "1.21" +
    # folia-supported: true (NovaChat/Plugin/folia/.../plugin.yml).
    # Auto-detect: scrape papermc.io/downloads/folia for the first
    #   https://fill-data.papermc.io/v1/objects/<sha>/folia-<MC>-<build>.jar
    # URL -- the <sha> in the path IS the content hash, so Auto mode can verify.
    # JDK 21.
    @{
        Name    = 'folia'
        MC      = '26.1.2'
        Build   = '8'
        Engine  = 'folia'
        Url     = 'https://fill-data.papermc.io/v1/objects/607afd1c3320008e1ffd2eaee6780ace4419d5f8c527b75e79f259be79ebf57b/folia-26.1.2-8.jar'
        Sha256  = '607AFD1C3320008E1FFD2EAEE6780ACE4419D5F8C527B75E79F259BE79EBF57B'
        Eula    = $true
        Jdk     = 21
        File    = 'folia-26.1.2-8.jar'
        Kind    = 'jar'
        Auto    = $true
    }

    # Velocity proxy: 4.1.0-SNAPSHOT build 16. JDK 25 (novachat-velocity
    # build.gradle pins VERSION_25; Lombok 1.18.x crashes under JDK25 so the
    # velocity source uses no Lombok).
    # Auto-detect: scrape papermc.io/downloads/velocity for the first
    #   https://fill-data.papermc.io/v1/objects/<sha>/velocity-4.1.0-SNAPSHOT-<b>.jar
    @{
        Name    = 'velocity'
        MC      = '4.1.0'
        Build   = '16'
        Engine  = 'velocity'
        Url     = 'https://fill-data.papermc.io/v1/objects/aebade8be3b15d7c3c61514a50ce857cbf78ee87bd32e8d16d2352c6ca3e472f/velocity-4.1.0-SNAPSHOT-16.jar'
        Sha256  = 'AEBADE8BE3B15D7C3C61514A50CE857CBF78EE87BD32E8D16D2352C6CA3E472F'
        Eula    = $false
        Jdk     = 25
        File    = 'velocity-4.1.0-SNAPSHOT-16.jar'
        Kind    = 'jar'
        Auto    = $true
    }

    # BungeeCord proxy: Waterfall 1.21 build 615. JDK 21.
    # (Waterfall EOL 2026-06 but archived builds remain on fill-data CDN.)
    # Auto-detect: scrape papermc.io/downloads/waterfall for the first
    #   https://fill-data.papermc.io/v1/objects/<sha>/waterfall-<MC>-<b>.jar
    @{
        Name    = 'waterfall'
        MC      = '1.21'
        Build   = '615'
        Engine  = 'waterfall'
        Url     = 'https://fill-data.papermc.io/v1/objects/5eda8bfd0691e5088701f87020c68964299586df0faba289a634122d282d598c/waterfall-1.21-615.jar'
        Sha256  = '5EDA8BFD0691E5088701F87020C68964299586DF0FABA289A634122D282D598C'
        Eula    = $false
        Jdk     = 21
        File    = 'waterfall-1.21-615.jar'
        Kind    = 'jar'
        Auto    = $true
    }

    # Nukkit (Cloudburst): 1.0-SNAPSHOT build 1242 (2026-08-09). JDK 21.
    # Plugin declares api "1.0.0" (NovaChat/Bedrock/nukkit/.../nukkit.yml).
    # Auto-detect: repo.opencollab.dev/maven-snapshots/cn/nukkit/nukkit/
    #   1.0-SNAPSHOT/maven-metadata.xml -> timestamp + buildNumber -> jar URL.
    #   (ci.opencollab.dev Jenkins is 502-down; the maven mirror is canonical.)
    # No pre-pinned hash from the mirror; Auto mode computes SHA-256 on the fly.
    @{
        Name    = 'nukkit'
        MC      = '1.0-SNAPSHOT'
        Build   = '1242'
        Engine  = 'nukkit'
        Url     = 'https://repo.opencollab.dev/maven-snapshots/cn/nukkit/nukkit/1.0-SNAPSHOT/nukkit-1.0-20260809.225630-1242.jar'
        Sha256  = ''  # Auto-computed on first download; pinned mode refuses to run with empty hash.
        Eula    = $false
        Jdk     = 21
        File    = 'nukkit-1.0-20260809.225630-1242.jar'
        Kind    = 'jar'
        Auto    = $true
    }

    # Sponge: SpongeVanilla 1.21.10-17.0.0 (SpongeAPI 17.0.0 / MC 1.21.10). JDK 17.
    # Plugin compiles against spongeapi 8.2.0 but the runtime server is the
    # universal jar from repo.spongepowered.org maven-releases.
    # Auto-detect: scrape spongepowered.org/downloads/spongevanilla for the
    # first .../spongevanilla/<ver>/spongevanilla-<ver>-universal.jar URL.
    # (api.spongepowered.org is unreachable; maven /search returns library jars
    # only, NOT the runnable universal jar -- must scrape the downloads page.)
    @{
        Name    = 'sponge'
        MC      = '1.21.10-17.0.0'
        Build   = '17.0.0'
        Engine  = 'spongevanilla'
        Url     = 'https://repo.spongepowered.org/repository/maven-releases/org/spongepowered/spongevanilla/1.21.10-17.0.0/spongevanilla-1.21.10-17.0.0-universal.jar'
        Sha256  = ''  # Auto-computed on first download.
        Eula    = $false
        Jdk     = 17
        File    = 'spongevanilla-1.21.10-17.0.0-universal.jar'
        Kind    = 'jar'
        Auto    = $true
    }

    # =========================================================================
    # Bedrock platforms
    # =========================================================================

    # BDS (Bedrock Dedicated Server) for endstone + levilamina. One BDS download
    # serves both: endstone patches BDS (python plugin host), levilamina injects
    # a preloader dll into BDS (C++ mod loader). BDS is a ZIP, not a jar --
    # fetch-server.ps1 extracts it to dist/bds-<version>/ after download.
    # Auto-detect: the real BDS URL is served by minecraft.net's pocketbedlinks
    # JSON, which is fetched at runtime by the download page JS. That endpoint is
    # BLOCKED from this env (TLS dropped even with a browser UA), so -Auto logs
    # the detection approach + URL pattern and exits without downloading.
    # Pinned mode below uses a documented placeholder; the levilamina/endstone
    # E2E agents supply a locally-cached BDS zip via their own scripts.
    @{
        Name    = 'bds'
        MC      = '1.21.93'
        Build   = '13'
        Engine  = 'bds'
        # Pattern (documented, not directly reachable here):
        #   https://www.minecraft.net/en-us/download/server/bedrock -> JS fetches
        #   pocketbedlinks JSON -> returns
        #   https://www.minecraft.net/bedrockdedicatedserverbinaries/<platform>/
        #   bedrock-server-<MC>.<patch>.zip  (platform = win | linux)
        Url     = 'https://www.minecraft.net/bedrockdedicatedserverbinaries/win/bedrock-server-1.21.93.13.zip'
        Sha256  = ''  # Auto-computed when a real BDS zip is downloadable.
        Eula    = $true   # Mojang EULA required for BDS.
        Jdk     = 0       # BDS is native (no JDK); bedrock_server.exe runs directly.
        File    = 'bedrock-server-1.21.93.13.zip'
        Kind    = 'zip'   # fetch-server.ps1 extracts Kind=zip to dist/bds-<version>/.
        Auto    = $true
    }

    # PocketMine-MP: 5.44.3 (Bedrock 1.26.30). PHP phar, not a jar.
    # Auto-detect: update.pmmp.io/api?channel=stable -> JSON with base_version +
    # download_url (-> github.com/pmmp/PocketMine-MP/releases/download/<ver>/
    # PocketMine-MP.phar). GitHub release assets are reachable even though
    # api.github.com is 403 from this env.
    # No pre-pinned hash; Auto mode computes SHA-256 on the fly.
    @{
        Name    = 'pocketmine'
        MC      = '5.44.3'
        Build   = '2617'
        Engine  = 'pocketmine'
        Url     = 'https://github.com/pmmp/PocketMine-MP/releases/download/5.44.3/PocketMine-MP.phar'
        Sha256  = ''  # Auto-computed on first download.
        Eula    = $false
        Jdk     = 0    # PocketMine is PHP, not Java.
        File    = 'PocketMine-MP.phar'
        Kind    = 'phar'  # PHP phar; run directly by php executable, not extracted.
        Auto    = $true
    }
)

function Get-LockedServer([string]$Name) {
    return $LockedServers | Where-Object { $_.Name -eq $Name } | Select-Object -First 1
}

# When dot-sourced (as fetch-server.ps1 does), the function + variable are
# injected into the caller's scope. Export-ModuleMember is only valid inside a
# .psm1 module, so it is intentionally omitted here.
