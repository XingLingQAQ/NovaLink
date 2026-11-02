[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$JointRoot,
  [Parameter(Mandatory = $true)][ValidateSet('relay', 'lite')][string]$ProxyMode,
  [ValidateSet('velocity', 'bungee')][string]$ProxyPlatform = 'velocity',
  [ValidateSet('nukkit', 'pnx')][string]$BedrockPlatform = 'nukkit',
  [string]$ScenarioGroups = 'core',
  [ValidateSet('none', 'bukkit')][string]$SecondaryJavaPlatform = 'none'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scenarios/registry.ps1')
$requestedGroups = @(Resolve-JointScenarioGroups -Value $ScenarioGroups)
$required = @(Get-JointScenarioAssertionIds -ScenarioGroups $requestedGroups -RoundIndex 1 -Mode $ProxyMode -Proxy $ProxyPlatform)
if ('proxy-switch' -in $requestedGroups -and $SecondaryJavaPlatform -ne 'bukkit') {
  throw "Scenario group 'proxy-switch' requires -SecondaryJavaPlatform bukkit (bukkit2)"
}
if ('channel-lifecycle' -in $requestedGroups -and $SecondaryJavaPlatform -ne 'bukkit') {
  throw "Scenario group 'channel-lifecycle' requires -SecondaryJavaPlatform bukkit (bukkit2)"
}
$files = @(Get-ChildItem -LiteralPath $JointRoot -Recurse -Filter joint-summary.json -File)
if ($files.Count -ne 1) { throw "Expected one joint summary, found $($files.Count)" }
$summary = [System.IO.File]::ReadAllText($files[0].FullName, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
if ($summary.proxyMode -ne $ProxyMode -or $summary.overallPass -isnot [bool] -or -not $summary.overallPass) {
  throw 'Joint run did not pass in the requested mode'
}
$reportedGroups = @($summary.scenarioGroups)
if ($summary.scenarioGroups -is [string] -or $reportedGroups.Count -eq 0) {
  throw 'summary.scenarioGroups must be a non-empty array including core and every requested group'
}
foreach ($group in $reportedGroups) {
  if ($group -isnot [string] -or $group -cnotin @('core', 'business', 'lite-moderation', 'recovery', 'proxy-switch', 'channel-lifecycle', 'social-contract', 'custom-effects', 'completion')) {
    throw "Invalid group in summary.scenarioGroups: '$group'"
  }
}
$reportedSet = @($reportedGroups | Sort-Object -Unique)
if ($reportedSet.Count -ne $reportedGroups.Count -or
    ($reportedSet -join ',') -cne (@($requestedGroups | Sort-Object) -join ',')) {
  throw "summary.scenarioGroups mismatch: requested [$($requestedGroups -join ',')], recorded [$($reportedGroups -join ',')]"
}
if (@($summary.rounds).Count -ne 1) { throw 'Joint CI requires exactly one round' }
$round = $summary.rounds[0]
if ('recovery' -in $requestedGroups -and -not $round.bedrock) {
  throw "Scenario group 'recovery' requires relay mode and a real Bedrock leg"
}
if ('proxy-switch' -in $requestedGroups -and ($round.java -ne 'bukkit' -or $round.secondaryJava -ne 'bukkit2')) {
  throw "Scenario group 'proxy-switch' requires relay mode, Bukkit primary and bukkit2 secondary"
}
if ('proxy-switch' -in $requestedGroups -and $round.proxySwitchDriver -cne 'player-command') {
  throw "Scenario group 'proxy-switch' requires recorded player-command evidence"
}
if ('channel-lifecycle' -in $requestedGroups -and ($round.java -ne 'bukkit' -or $round.secondaryJava -ne 'bukkit2')) {
  throw "Scenario group 'channel-lifecycle' requires relay mode, Bukkit primary and bukkit2 secondary"
}
if ($SecondaryJavaPlatform -eq 'bukkit') {
  if ($round.secondaryJava -ne 'bukkit2' -or $round.mod) { throw 'Requested secondary Bukkit requires round.secondaryJava=bukkit2 and no mod leg' }
} elseif ($round.secondaryJava -and $round.secondaryJava -ne 'none') {
  throw "Unexpected secondary Java leg '$($round.secondaryJava)'; requested none"
}
if ($round.status -ne 'PASS' -or $round.proxy -ne $ProxyPlatform -or $round.java -ne 'bukkit' -or $round.mod) {
  throw "Joint CI requires $ProxyPlatform with Bukkit and no mod leg"
}
if ($ProxyMode -eq 'relay' -and $round.bedrock -ne $BedrockPlatform) { throw "Relay CI requires $BedrockPlatform" }
if ($ProxyMode -eq 'lite' -and $round.bedrock) { throw 'Lite CI must not contain a Bedrock leg' }
if ($ProxyMode -eq 'relay') {
  $required += @('round1/java-to-bedrock', 'round1/bedrock-to-java', 'round1/relay-chat-attribution-and-delivery')
}
if ($ProxyMode -eq 'relay' -and $BedrockPlatform -eq 'pnx') { $required += 'round1/native-pnx-runtime' }
foreach ($id in $required) {
  $matches = @($round.assertions | Where-Object { $_.id -ceq $id })
  if ($matches.Count -ne 1 -or $matches[0].kind -eq 'diagnostic' -or $matches[0].pass -isnot [bool] -or -not $matches[0].pass) {
    throw "Required gating assertion missing, diagnostic, duplicated or failed: $id"
  }
}
$failed = @($round.assertions | Where-Object { $_.kind -ne 'diagnostic' -and ($_.pass -isnot [bool] -or -not $_.pass) })
if ($failed.Count) { throw "Summary contains $($failed.Count) failed gating assertions" }
$gating = @($round.assertions | Where-Object { $_.kind -ne 'diagnostic' })
$topology = if ($ProxyMode -eq 'relay') { "$ProxyPlatform/$ProxyMode/$BedrockPlatform" } else { "$ProxyPlatform/$ProxyMode" }
Write-Host "Joint $topology evidence accepted: $($gating.Count) gating assertions; groups=[$($requestedGroups -join ',')]; secondary=$SecondaryJavaPlatform"
