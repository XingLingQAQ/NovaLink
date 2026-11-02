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

$localPlans = Get-JointRecoveryPlans 'offline' -Channel local
foreach ($role in @('Java', 'Bedrock')) {
  $steps = $localPlans[$role]
  Assert-CiRecoveryCheck (@($steps | Where-Object { $_.action -eq 'cmd' -and $_.text -match '^/nc join\s' }).Count -eq 1) "$role has one initial JOIN only"
  Assert-CiRecoveryCheck (@($steps | Where-Object { $_.id -eq 'recovery-join' -and $_.required }).Count -eq 1) "$role requires the initial JOIN receipt"
  Assert-CiRecoveryCheck (@($steps | Where-Object { $_.action -eq 'cmd' -and $_.text -ceq '/nc who' }).Count -eq 3) "$role reads its active channel before and after both events"
  Assert-CiRecoveryCheck (@($steps | Where-Object { $_.action -eq 'chat' -and $_.text -match '^offline-(?:pre|restart|reconnect)-(?:java|bedrock)$' }).Count -eq 3) "$role sends plain chat in all three phases"
  Assert-CiRecoveryCheck (@($steps | Where-Object { $_.action -eq 'signal' -and $_.key -eq "recovery-$($role.ToLowerInvariant())-selected" }).Count -eq 1) "$role confirms initial selection before traffic"
}
Assert-CiRecoveryCheck (@($localPlans.Bedrock | Where-Object { $_.action -eq 'reconnect' }).Count -eq 1) 'non-default path still has one bounded Bedrock reconnect'
Assert-CiRecoveryCheck (@(Get-RecoveryRequiredAssertionIds 1 -Channel local).Count -eq (@(Get-RecoveryRequiredAssertionIds 1).Count + 8)) 'global IDs remain compatible; non-default mode adds eight mandatory IDs'

function New-CiRecoveryResult {
  param([string]$Kind)
  $chatKind = if ($Kind -eq 'bedrock') { 'text:raw' } else { 'messagestr' }
  [PSCustomObject]@{
    runId = 'offline'; kind = $Kind; error = $null; timedOut = $false; assertions = @(); absentChecks = @()
    received = @(
      [PSCustomObject]@{ kind = 'checkpoint'; key = 'recovery-joining'; atMs = 10 },
      [PSCustomObject]@{ kind = $chatKind; text = 'Joined channel: local'; atMs = 12 }
    )
    sent = @([PSCustomObject]@{ text = '/nc join local'; atMs = 11 })
  }
}
function Add-CiRecoveryMembers {
  param($Result, [string]$Phase, [double]$AtMs, [switch]$Current)
  $chatKind = if ($Result.kind -eq 'bedrock') { 'text:raw' } else { 'messagestr' }
  $Result.received += [PSCustomObject]@{ kind = 'checkpoint'; key = $Phase; atMs = $AtMs }
  $command = if ($Current) { '/nc who' } else { '/nc who local' }
  $Result.sent += [PSCustomObject]@{ text = $command; atMs = ($AtMs + 1) }
  $Result.received += [PSCustomObject]@{ kind = $chatKind; text = 'Channel Local online members (2):'; atMs = ($AtMs + 2) }
  $Result.received += [PSCustomObject]@{ kind = $chatKind; text = 'E2ERecoverJava, E2ERecoverBed'; atMs = ($AtMs + 3) }
}

$localJava = New-CiRecoveryResult java
$localBedrock = New-CiRecoveryResult bedrock
foreach ($result in @($localJava, $localBedrock)) {
  Add-CiRecoveryMembers $result 'recovery-selected' 20 -Current
  Add-CiRecoveryMembers $result 'backend-restored' 100
  Add-CiRecoveryMembers $result 'backend-active' 110 -Current
}
Add-CiRecoveryMembers $localJava 'bedrock-peer-active' 310 -Current
Add-CiRecoveryMembers $localBedrock 'bedrock-restored' 300
Add-CiRecoveryMembers $localBedrock 'bedrock-active' 310 -Current
$localBedrock.received += [PSCustomObject]@{ kind = 'reconnect'; phase = 'spawned'; protocol = 'bedrock'; attempts = 1; atMs = 299 }
$localBedrock.assertions += [PSCustomObject]@{ id = 'bedrock-reconnect'; pass = $true; evidence = 'synthetic transport' }
foreach ($phase in @('pre', 'restart', 'reconnect')) {
  $at = if ($phase -eq 'pre') { 50 } elseif ($phase -eq 'restart') { 150 } else { 350 }
  $localJava.sent += [PSCustomObject]@{ text = "offline-$phase-java"; atMs = $at }
  $localBedrock.sent += [PSCustomObject]@{ text = "offline-$phase-bedrock"; atMs = $at }
  $localJava.received += [PSCustomObject]@{ kind = 'messagestr'; text = "offline-$phase-bedrock"; atMs = ($at + 1) }
  $localBedrock.received += [PSCustomObject]@{ kind = 'text:chat'; text = "offline-$phase-java"; atMs = ($at + 1) }
}
foreach ($result in @($localJava, $localBedrock)) { $result.received = @($result.received | Sort-Object atMs) }
$localVerdicts = @(Assert-JointRecoveryResults 1 'offline' $localJava $localBedrock -Channel local)
Assert-CiRecoveryCheck (@($localVerdicts | Where-Object { -not $_.pass }).Count -eq 0) 'complete non-default synthetic recording accepted'
foreach ($id in @(Get-RecoveryRequiredAssertionIds 1 -Channel local | Where-Object { $_ -notlike '*/backend-process-restarted' })) {
  Assert-CiRecoveryCheck (@($localVerdicts | Where-Object { $_.id -ceq $id -and $_.pass -and $_.kind -eq 'recovery' }).Count -eq 1) "non-default required assertion emitted: $id"
}
$localBedrock.received | Where-Object { $_.atMs -eq 312 } | ForEach-Object { $_.text = 'Channel Global online members (2):' }
$resetVerdicts = @(Assert-JointRecoveryResults 1 'offline' $localJava $localBedrock -Channel local)
Assert-CiRecoveryCheck (@($resetVerdicts | Where-Object { $_.id -ceq 'round1/recovery/reconnect-active-bedrock' -and -not $_.pass }).Count -eq 1) 'game reconnect resetting to global fails even with valid membership and all receiver tokens'
Assert-CiRecoveryCheck (@($resetVerdicts | Where-Object { $_.id -ceq 'round1/recovery/restart-active-bedrock' -and $_.pass }).Count -eq 1) 'game reconnect reset is distinct from backend reauthentication'
$localBedrock.received | Where-Object { $_.atMs -eq 312 } | ForEach-Object { $_.text = 'Channel Local online members (2):' }
$localBedrock.sent += [PSCustomObject]@{ text = '/novachat join local'; atMs = 305 }
Assert-CiRecoveryCheck (-not (Test-JointRecoveryNoRejoin $localBedrock local)) 'second JOIN through a command alias cannot hide a recovery gap'
$localBedrock.sent = @($localBedrock.sent | Where-Object { $_.atMs -ne 305 })
$localJava.sent | Where-Object { $_.text -ceq 'offline-restart-java' } | ForEach-Object { $_.text = '/nc local offline-restart-java' }
$overrideVerdicts = @(Assert-JointRecoveryResults 1 'offline' $localJava $localBedrock -Channel local)
Assert-CiRecoveryCheck (@($overrideVerdicts | Where-Object { $_.id -ceq 'round1/recovery/restart-java-to-bedrock' -and -not $_.pass }).Count -eq 1) 'explicit channel send cannot prove active-channel delivery'
$missingVerdicts = @(Assert-JointRecoveryResults 1 'offline' $null $null -Channel local)
Assert-CiRecoveryCheck (@($missingVerdicts | Where-Object { $_.pass }).Count -eq 0) 'missing clients fail every non-default recording assertion'
$fixture.received[1].atMs = 102
Assert-CiRecoveryCheck (-not (Test-JointRecoveryMembership $fixture 'phase' @('Alice', 'Bob') local)) 'global query and response cannot prove local membership'
$fixture.sent[0].text = '/nc who local'
Assert-CiRecoveryCheck (-not (Test-JointRecoveryMembership $fixture 'phase' @('Alice', 'Bob') local)) 'wrong-channel header cannot satisfy an explicit local query'
Write-Host '[SYNTHETIC] Non-default recovery contract and rejection cases checked; no game or backend process started.'

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
