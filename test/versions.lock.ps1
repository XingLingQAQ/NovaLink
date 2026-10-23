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
#   - net-secondary.web.minecraft-services.net (BDS) ... 200 JSON. The links
#     API is the canonical source:
#       /api/v1.0/download/links -> result.links[] { downloadType, downloadUrl }
#     downloadType "serverBedrockWindows" is the Windows BDS zip. NOTE the
#     download host changed shape: the old
#       minecraft.net/bedrockdedicatedserverbinaries/<os>/<file>
#     form now 404s for EVERY version; links point at
#       minecraft.net/bedrockdedicatedserver/bin-<os>/<file>.
#     (The earlier "pocketbedlinks is blocked" finding was a false negative: a
#     stale HTTPS_PROXY=http://127.0.0.1:7890 in the shell, honoured by curl
#     but ignored by .NET/Node, made the download host look unreachable.)
# ============================================================================
$LockedServers = @(
    # =========================================================================
    # Java platforms
    # =========================================================================

    # Bukkit platform: Purpur 1.21.11 build 2568. JDK 21.
    # Plugin declares api-version "1.21" (NovaChat/Plugin/bukkit/.../plugin.yml).
    #
    # Deliberately kept on 1.21.11 even though mineflayer 4.39.0 now accepts
    # 26.1+: bukkit coverage is meant to stay on the long-lived 1.21 line, and
    # folia covers the 26.x line. The old ceiling came from mineflayer 4.37.1,
    # whose loader.js hard-codes latestSupportedVersion as the last entry of its
    # testedVersions list (that list stopped at 1.21.11); 4.39.0 lists 26.1, so
    # venturing past 1.21.11 here no longer needs a library bump.
    #
    # Auto-detect: api.purpurmc.org/v2/purpur/<MC> -> builds.latest, then
    #   download = https://api.purpurmc.org/v2/purpur/<MC>/<build>/download
    #   (NOTE: the OLD /builds/<build>/downloads/<file>.jar path 404s as of
    #   2026-08-09; the current pattern is /<MC>/<build>/download, singular,
    #   no filename. The website purpurmc.org/downloads links to this pattern.)
    # SHA-256 computed from the downloaded jar (the Purpur API only exposes
    # MD5); the MD5 matched the API value b8d5402ef8e38bf60cabc6eeddb3fa18.
    @{
        Name    = 'purpur'
        MC      = '1.21.11'
        Build   = '2568'
        Engine  = 'purpur'
        Url     = 'https://api.purpurmc.org/v2/purpur/1.21.11/2568/download'
        Sha256  = 'EE199511BF0A970EE8459FE3A351703FEC37293F00BC2DE1C5CB746C7E4ACC34'
        Eula    = $true
        Jdk     = 21
        File    = 'purpur-1.21.11-2568.jar'
        # Protocol version the bot asks for. Separate from MC so a server that
        # outruns mineflayer can still be driven by a bot speaking the nearest
        # supported protocol (see the folia entry).
        BotMcVersion = '1.21.11'
        Kind    = 'jar'
        Auto    = $true
    }

    # Folia: 1.21.11 is not a published Purpur-style MC; PaperMC's latest stable
    # folia build is 26.1.2-8 (MC 26.1.2). Plugin declares api-version "1.21" +
    # folia-supported: true (NovaChat/Plugin/folia/.../plugin.yml).
    # Auto-detect: scrape papermc.io/downloads/folia for the first
    #   https://fill-data.papermc.io/v1/objects/<sha>/folia-<MC>-<build>.jar
    # URL -- the <sha> in the path IS the content hash, so Auto mode can verify.
    # JDK 25, not 21: the jar's own version.json declares "java_version": 25,
    # and booting it on 21 exits immediately with "Minecraft 26.1 and newer
    # requires running the server with Java 25 or above". Verified 2026-10-18
    # (JDK 21 -> that error; JDK 25 -> "Done (39.332s)!").
    @{
        Name    = 'folia'
        MC      = '26.1.2'
        Build   = '8'
        Engine  = 'folia'
        Url     = 'https://fill-data.papermc.io/v1/objects/607afd1c3320008e1ffd2eaee6780ace4419d5f8c527b75e79f259be79ebf57b/folia-26.1.2-8.jar'
        Sha256  = '607AFD1C3320008E1FFD2EAEE6780ACE4419D5F8C527B75E79F259BE79EBF57B'
        Eula    = $true
        Jdk     = 25
        File    = 'folia-26.1.2-8.jar'
        # The bot speaks the server's own version. mineflayer 4.39.0 lists 26.1
        # as its latest tested version, so a 26.1.2 server is accepted (26.1.2
        # is the same protocol 775 as 26.1; prismarine-registry normalises the
        # patch number away). The old 1.21.11 downgrade existed only because
        # mineflayer 4.37.1 stopped there and rejected 26.x outright -- it is
        # no longer needed. Keep this in step with MC above: if MC moves to a
        # version newer than mineflayer's testedVersions, the loader throws
        # "Server version '<v>' is not supported" before a socket is opened.
        BotMcVersion = '26.1.2'
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
        # MC above is the proxy's own version, not a protocol the bot can speak.
        BotMcVersion = '1.21.11'
        Kind    = 'jar'
        Auto    = $true
    }

    # BungeeCord proxy: Waterfall 1.21 build 615. JDK 21.
    # (Waterfall EOL 2026-06 but archived builds remain on fill-data CDN.)
    # 21 is sufficient: the highest non-multi-release class in the jar is
    # class-file 66.0, but those are jline's Java-22 FFM console helpers which
    # the proxy never loads on Windows. Verified booting to "Listening" on 21
    # (2026-10-18).
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
        # MC above is the proxy's own version, not a protocol the bot can speak.
        BotMcVersion = '1.21.11'
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

    # Sponge: SpongeVanilla 1.21.10-17.0.0 (SpongeAPI 17.0.0 / MC 1.21.10). JDK 21.
    # The runtime is 21, not 17: the universal jar's installer/bootstrap classes
    # are class-file 65.0, so JDK 17 fails at launch with
    # UnsupportedClassVersionError. Verified 2026-10-18 (JDK 17 -> UCVE;
    # JDK 21 -> "Done (29.131s)!").
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
        Jdk     = 21
        File    = 'spongevanilla-1.21.10-17.0.0-universal.jar'
        # MC above carries the Sponge build suffix ("1.21.10-17.0.0"), which is
        # not a protocol version; the bot needs the bare Minecraft version.
        BotMcVersion = '1.21.10'
        Kind    = 'jar'
        Auto    = $true
    }

    # =========================================================================
    # Bedrock platforms
    # =========================================================================

    # BDS (Bedrock Dedicated Server) for endstone + levilamina. One BDS download
    # serves both: endstone patches BDS (python plugin host), levilamina injects
    # a preloader dll into BDS (C++ mod loader). BDS is a ZIP, not a jar --
    # fetch-server.ps1 extracts it to dist/bds-<MC>/ after download.
    #
    # Auto-detect: the links API (see the reachability notes) is queried at
    # runtime for the serverBedrockWindows URL; the version is parsed out of the
    # file name, and the SHA-256 is taken from Endstone's bedrock-server-data
    # metadata when that names the same URL. The pin below is only the fallback
    # for a non-Auto run or a failed API call.
    #
    # Version: 1.26.52 is what endstone 0.11.13+ hosts. endstone.__minecraft_version__
    # reads '26.52' (0.11.14 wheel), and the CLI resolves its BDS metadata from
    # 1.<that version> -- so a 1.26.40 BDS is not merely unsupported, the CLI would
    # fetch a different artifact than the one we extract. The producer's own
    # metadata supplies the pinned SHA-256.
    @{
        Name    = 'bds'
        MC      = '1.26.52'
        Build   = '3'
        Engine  = 'bds'
        Url     = 'https://www.minecraft.net/bedrockdedicatedserver/bin-win/bedrock-server-1.26.52.3.zip'
        # From EndstoneMC/bedrock-server-data v2/release/1.26.52/metadata.json;
        # independently reproduced by hashing the downloaded zip (85,766,941 bytes).
        Sha256  = '2C9B98D07D2504786996F2335980E88BD969B4A77514925E75471A1349995825'
        Eula    = $true   # Mojang EULA required for BDS.
        Jdk     = 0       # BDS is native (no JDK); bedrock_server.exe runs directly.
        File    = 'bedrock-server-1.26.52.3.zip'
        # bedrock-protocol must be handed a version STRING its table knows.
        # 1.26.52 is a hotfix: it did not bump the wire protocol, so it speaks
        # protocol 2193 -- the number the table maps to 1.26.51 (minecraft-data
        # 3.117.0 has no 1.26.52 entry at all). Asking for "1.26.52" throws
        # "Unsupported version"; asking for "1.26.51" negotiates 2193, which the
        # server advertises and accepts (verified by logging a bot in).
        # Requires bedrock-protocol 3.60.1: 3.59.0 has no NetherNet transport,
        # and BDS 1.26.52 refuses RakNet logins ("NetherNet is the only supported
        # transport type"). 3.60.0 added NetherNet + 1.26.51; 3.60.1 picks the
        # transport from the server's pong.
        BotMcVersion = '1.26.51'
        Kind    = 'zip'   # fetch-server.ps1 extracts Kind=zip to dist/bds-<MC>/.
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

    # PocketMine-MP's PHP runtime (pmmp/PHP-Binaries). This is NOT optional:
    # PocketMine requires ten extensions, five of which -- chunkutils2, crypto,
    # encoding, morton, pmmpthread -- are PocketMine-private C extensions that
    # ship in no standard PHP distribution and in no PECL package. Stock PHP
    # (setup-php, scoop, winget) therefore cannot run the server at all:
    #   [ERROR] Selected PHP binary does not satisfy some requirements.
    # pmmp publishes a prebuilt zip per PHP branch+arch that bundles all ten.
    # run-pmmp-e2e.ps1 extracts it to dist/php-runtime/ and uses its php.exe.
    #
    # Pinned to the immutable php-build-524 tag rather than the moving
    # `pm5-php-8.4-latest` tag: both currently serve the identical asset
    # (SHA-256 verified 2026-10-18), but only the build tag cannot change
    # underneath a fixed hash. PHP 8.4.16 ZTS satisfies PocketMine 5.44.3's
    # composer requirement `php: ^8.1`.
    #
    # Windows x64 only -- the E2E matrix runs on windows-2022, and the zip is
    # a Windows build (bin/php/php.exe + php8ts.dll). There is a Linux tarball
    # in the same release if the harness ever moves off Windows.
    @{
        Name    = 'pmmp-php'
        MC      = '8.4.16'
        Build   = '524'
        Engine  = 'php'
        Url     = 'https://github.com/pmmp/PHP-Binaries/releases/download/php-build-524/PHP-8.4-Windows-x64-PM5.zip'
        Sha256  = 'CF4023B8549C99EEC47CFD403805AC7D78100FA366394DAB4E45BB544DCC9BE4'
        Eula    = $false
        Jdk     = 0
        File    = 'PHP-8.4-Windows-x64-PM5.zip'
        # Deliberately NOT 'zip': fetch-server.ps1's Kind=zip path exists for
        # BDS and extracts to dist/<name>-<MC>/, which is not where the runner
        # looks for this runtime (dist/php-runtime/). Leaving it as a plain
        # archive keeps extraction in one place -- run-pmmp-e2e.ps1.
        Kind    = 'archive'
        Auto    = $false  # No auto-detect resolver; the pin is the contract.
    }
)

function Get-LockedServer([string]$Name) {
    return $LockedServers | Where-Object { $_.Name -eq $Name } | Select-Object -First 1
}

# When dot-sourced (as fetch-server.ps1 does), the function + variable are
# injected into the caller's scope. Export-ModuleMember is only valid inside a
# .psm1 module, so it is intentionally omitted here.
