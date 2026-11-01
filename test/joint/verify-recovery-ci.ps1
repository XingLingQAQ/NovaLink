[CmdletBinding()]
param([string[]]$ArtifactRoots = @())
$ErrorActionPreference = 'Stop'

foreach ($relative in @('scenarios/recovery.ps1', 'scenarios/proxy-switch.ps1', 'verify-recovery.ps1', 'verify-recovery-ci.ps1')) {
  $parseTokens = $null
  $parseErrors = $null
  $null = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $relative), [ref]$parseTokens, [ref]$parseErrors)
  if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
}
. (Join-Path $PSScriptRoot 'scenarios/proxy-switch.ps1')

function Assert-CiRecoveryCheck {
  param([bool]$Pass, [string]$Label)
  if (-not $Pass) { throw "Offline recovery regression failed: $Label" }
}
function Read-CiRecoveryJson {
  param([string]$Path)
  [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8) | ConvertFrom-Json
}

$header = [regex]::Unescape('\u00a76\u9891\u9053 \u00a7eGlobal \u00a76\u5728\u7ebf\u6210\u5458 \u00a77\uff082 \u4eba\uff09\uff1a')
$fixture = [PSCustomObject]@{
  received = @(
    [PSCustomObject]@{ kind = 'checkpoint'; key = 'phase'; atMs = 100 },
    [PSCustomObject]@{ kind = 'text:raw'; text = $header; atMs = 102 },
    [PSCustomObject]@{ kind = 'text:raw'; text = ([string][char]0xA7 + '7Alice, Bob'); atMs = 103 }
  )
  sent = @([PSCustomObject]@{ text = '/nc who global'; atMs = 101 })
}
Assert-CiRecoveryCheck (Test-JointRecoveryMembership $fixture 'phase' @('Alice', 'Bob')) 'localized, colored member list'
$fixture.received[2].text = 'Alice'
Assert-CiRecoveryCheck (-not (Test-JointRecoveryMembership $fixture 'phase' @('Alice', 'Bob'))) 'member count and complete names remain required'
$fixture.received[2].text = 'Alice, Bob'
$fixture.received[1].atMs = 99
Assert-CiRecoveryCheck (-not (Test-JointRecoveryMembership $fixture 'phase' @('Alice', 'Bob'))) 'stale localized header rejected'

$plans = Get-JointProxySwitchPlans 'offline' -PrimaryServer 'bukkit' -SecondaryServer 'bukkit2'
foreach ($phase in @('right', 'move', 'return')) {
  $steps = if ($phase -eq 'right') { $plans.Right } else { $plans.Mover }
  $confirmation = @($steps | Where-Object { $_.id -eq "switch-$phase-server-confirmed" })
  Assert-CiRecoveryCheck ($confirmation.Count -eq 1 -and $confirmation[0].required -and $confirmation[0].sinceCheckpoint) "required destination receipt: $phase"
  $issued = @($steps | Where-Object { $_.action -eq 'signal' -and $_.key -eq "switch-$phase-command-done" })[0]
  Assert-CiRecoveryCheck ([array]::IndexOf($steps, $issued) -lt [array]::IndexOf($steps, $confirmation[0])) "command issuance cannot depend on later probe: $phase"
}
Assert-CiRecoveryCheck (@($plans.Mover | Where-Object { $_.kind -eq 'respawn' }).Count -eq 0) 'switch completion requires target evidence without a respawn assumption'
Assert-CiRecoveryCheck (@($plans.Mover | Where-Object { $_.id -eq 'switch-scoped-joined' -and $_.required }).Count -eq 1) 'rejected scoped join cannot continue sending traffic'

$script:probeAttempts = 0
function Wait-Handoff {
  param([string]$Dir, [string]$RunId, [string]$Key, [int]$Seconds)
  if ($Key -eq 'switch-move-target-confirmed' -and $script:probeAttempts -eq 2) { return 'received' }
  return $null
}
$probe = { param($Server, $Username, $Token) $script:probeAttempts++ }
Invoke-JointProxyLegProbe 'player-command' 'move' 'bukkit2' 'Mover' 'token' 'unused' 'offline' $probe
Assert-CiRecoveryCheck ($script:probeAttempts -eq 2) 'probe retries until the receiver confirms, without a server'

foreach ($artifactRoot in $ArtifactRoots) {
  $summaryFiles = @(Get-ChildItem -LiteralPath $artifactRoot -Filter 'joint-summary.json' -Recurse)
  Assert-CiRecoveryCheck ($summaryFiles.Count -eq 1) "one summary in $artifactRoot"
  $roundDir = Join-Path $summaryFiles[0].DirectoryName 'round-1'
  $recoveryDir = Join-Path $roundDir 'bots/recovery'
  $java = Read-CiRecoveryJson (Join-Path $recoveryDir 'Java.results.json')
  $bedrock = Read-CiRecoveryJson (Join-Path $recoveryDir 'Bedrock.results.json')
  foreach ($phase in @('backend-restored', 'bedrock-restored')) {
    Assert-CiRecoveryCheck (Test-JointRecoveryMembership $bedrock $phase @('E2ERecoverJava', 'E2ERecoverBed')) "recorded Bedrock member list: $artifactRoot/$phase"
  }
  $joinQuery = @($bedrock.sent | Where-Object { $_.text -ceq '/nc join global' })[0]
  $ack = @(Get-JointRecoveryChatRows $bedrock -SinceMs $joinQuery.atMs | Where-Object {
    (ConvertTo-JointRecoveryText $_.text) -match (Get-JointRecoveryJoinPattern 'global')
  })
  Assert-CiRecoveryCheck ($ack.Count -gt 0) "recorded localized JOIN acknowledgement: $artifactRoot"
  $recovery = @(Assert-JointRecoveryResults 1 $bedrock.runId $java $bedrock)
  Assert-CiRecoveryCheck (@($recovery | Where-Object { $_.id -eq 'round1/recovery/bedrock-completed' -and -not $_.pass }).Count -eq 1) 'historical failed bot assertions still fail completion'
  Assert-CiRecoveryCheck (@($recovery | Where-Object { $_.id -match '/(?:pre|restart|reconnect)-(?:java-to-bedrock|bedrock-to-java)$' -and $_.pass }).Count -eq 6) 'six historical messages have actual receiver evidence'

  $switchDir = Join-Path $roundDir 'bots/proxy-switch'
  $mover = Read-CiRecoveryJson (Join-Path $switchDir 'Mover.results.json')
  $left = Read-CiRecoveryJson (Join-Path $switchDir 'Left.results.json')
  $right = Read-CiRecoveryJson (Join-Path $switchDir 'Right.results.json')
  $switch = @(Assert-JointProxySwitchResults 1 $mover.runId $mover $left $right)
  foreach ($role in @('mover', 'right')) {
    $completed = @($switch | Where-Object { $_.id -eq "round1/proxy-switch/$role-completed" })[0]
    Assert-CiRecoveryCheck (-not $completed.pass -and $completed.evidence.Contains('failed assertions=[')) "historical $role failures stay gating and explain their IDs"
  }
  $owned = @($switch | Where-Object { $_.id -eq 'round1/proxy-switch/owned-channel-bidirectional' })[0]
  $summary = Read-CiRecoveryJson $summaryFiles[0].FullName
  if ($summary.rounds[0].proxy -eq 'bungee') {
    Assert-CiRecoveryCheck (-not $owned.pass) 'Bungee global fallback traffic cannot prove owned-channel delivery'
    Assert-CiRecoveryCheck (@($switch | Where-Object { $_.id -in @('round1/proxy-switch/leg-right', 'round1/proxy-switch/leg-moved') -and -not $_.pass }).Count -eq 2) 'Bungee missing destination probes remain failures'
  } else {
    Assert-CiRecoveryCheck $owned.pass 'Velocity has actual scoped joins and destination probes'
  }
  Write-Host "[ARTIFACT REPLAY] ${artifactRoot}: recorded receiver evidence inspected; historical failed gating preserved."
}

& node (Join-Path $PSScriptRoot 'bots/joint-bot.js') --selftest-text | Select-Object -Last 4
if ($LASTEXITCODE -ne 0) { throw "Localized bot synthetic checks exited $LASTEXITCODE" }
Write-Host 'Offline regression checks only; no new real server or CI validation.'
