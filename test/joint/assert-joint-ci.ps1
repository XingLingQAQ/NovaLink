[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$JointRoot,
  [Parameter(Mandatory = $true)][ValidateSet('relay', 'lite')][string]$ProxyMode,
  [ValidateSet('velocity', 'bungee')][string]$ProxyPlatform = 'velocity'
)

$ErrorActionPreference = 'Stop'
$files = @(Get-ChildItem -LiteralPath $JointRoot -Recurse -Filter joint-summary.json -File)
if ($files.Count -ne 1) { throw "Expected one joint summary, found $($files.Count)" }
$summary = [System.IO.File]::ReadAllText($files[0].FullName, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
if ($summary.proxyMode -ne $ProxyMode -or -not $summary.overallPass) { throw 'Joint run did not pass in the requested mode' }
if (@($summary.rounds).Count -ne 1) { throw 'Joint CI requires exactly one round' }
$round = $summary.rounds[0]
if ($round.status -ne 'PASS' -or $round.proxy -ne $ProxyPlatform -or $round.java -ne 'bukkit' -or $round.mod) {
  throw "Joint CI requires $ProxyPlatform with Bukkit and no mod leg"
}
if ($ProxyPlatform -eq 'bungee' -and $ProxyMode -ne 'relay') { throw 'Bungee CI supports relay only' }
if ($ProxyMode -eq 'relay' -and $round.bedrock -ne 'nukkit') { throw 'Relay CI requires Nukkit' }

$required = @(
  'round1/iso/phase1-alice-line-accepted',
  'round1/iso/phase1-isolated-from-bob',
  'round1/iso/phase2-bob-line-accepted',
  'round1/iso/phase2-isolated-from-alice',
  'round1/iso/phase3-shared-channel-delivered',
  'round1/iso/join-b-ack-observed',
  'round1/iso/join-a-ack-after-phase2',
  'round1/business/alice-completed',
  'round1/business/bob-completed',
  'round1/business/reconnect-spawned',
  'round1/business/reconnect-membership',
  'round1/business/reconnect-outbound',
  'round1/business/reconnect-inbound'
)
if ($ProxyMode -eq 'relay') {
  $required += @('round1/java-to-bedrock', 'round1/bedrock-to-java', 'round1/proxy-plugin-loaded',
    'round1/relay-links-created', 'round1/relay-distinct-backend-identities',
    'round1/relay-chat-attribution-and-delivery')
} else {
  $required += @('round1/lite-mode-active', 'round1/downstream-upstream-roundtrip', 'round1/proxy-to-downstream-chat')
}
foreach ($id in $required) {
  $matches = @($round.assertions | Where-Object { $_.id -eq $id })
  if ($matches.Count -ne 1 -or $matches[0].kind -eq 'diagnostic' -or -not $matches[0].pass) {
    throw "Required gating assertion missing or failed: $id"
  }
}
$failed = @($round.assertions | Where-Object { $_.kind -ne 'diagnostic' -and -not $_.pass })
if ($failed.Count) { throw "Summary contains $($failed.Count) failed gating assertions" }
$gating = @($round.assertions | Where-Object { $_.kind -ne 'diagnostic' })
Write-Host "Joint $ProxyPlatform/$ProxyMode evidence accepted: $($gating.Count) gating assertions; diagnostics excluded"
