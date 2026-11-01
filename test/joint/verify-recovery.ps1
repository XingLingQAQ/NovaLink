[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'

foreach ($relative in @('bots/joint-bot.js', 'scenarios/recovery.ps1', 'scenarios/proxy-switch.ps1', 'verify-recovery.ps1')) {
  if (-not $relative.EndsWith('.ps1')) { continue }
  $parseTokens = $null
  $parseErrors = $null
  $null = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $relative), [ref]$parseTokens, [ref]$parseErrors)
  if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
}
. (Join-Path $PSScriptRoot 'scenarios/proxy-switch.ps1')

function Assert-RecoveryCheck {
  param([bool]$Pass, [string]$Name)
  if (-not $Pass) { throw "Synthetic recovery check failed: $Name" }
  Write-Host "[SYNTHETIC PASS] $Name"
}
function New-RecoveryRow {
  param([string]$Text, [double]$AtMs, [string]$Kind = 'messagestr')
  [PSCustomObject]@{ kind = $Kind; text = $Text; raw = ''; atMs = $AtMs }
}
function Add-RecoveryPhase {
  param($Result, [string]$Name, [double]$AtMs, [string]$Members)
  $Result.received += [PSCustomObject]@{ kind = 'checkpoint'; key = $Name; atMs = $AtMs }
  $Result.sent += [PSCustomObject]@{ text = '/nc who global'; atMs = ($AtMs + 1) }
  $Result.received += New-RecoveryRow 'Channel Global online members (2):' ($AtMs + 2) -Kind $(if ($Result.kind -eq 'bedrock') { 'text:system' } else { 'messagestr' })
  $Result.received += New-RecoveryRow $Members ($AtMs + 3) -Kind $(if ($Result.kind -eq 'bedrock') { 'text:system' } else { 'messagestr' })
}
function New-RecoveryResult {
  param([string]$Kind = 'java')
  [PSCustomObject]@{ runId = 'pure'; kind = $Kind; received = @(); sent = @(); assertions = @(); absentChecks = @(); error = $null; timedOut = $false }
}

$plans = Get-JointRecoveryPlans 'pure'
Assert-RecoveryCheck (@($plans.Bedrock | Where-Object { $_.action -eq 'reconnect' }).Count -eq 1) 'one bounded Bedrock reconnect in plan'
foreach ($role in @('Java', 'Bedrock')) {
  Assert-RecoveryCheck (@($plans[$role] | Where-Object { $_.action -eq 'cmd' -and $_.text.StartsWith('/nc join') }).Count -eq 1) "$role does not rejoin after backend restart"
}

$java = New-RecoveryResult
$bedrock = New-RecoveryResult 'bedrock'
Add-RecoveryPhase $java 'backend-restored' 100 'E2ERecoverJava, E2ERecoverBed'
Add-RecoveryPhase $bedrock 'backend-restored' 100 'E2ERecoverJava, E2ERecoverBed'
$java.received += [PSCustomObject]@{ kind = 'checkpoint'; key = 'bedrock-peer-restored'; atMs = 300 }
Add-RecoveryPhase $bedrock 'bedrock-restored' 300 'E2ERecoverJava, E2ERecoverBed'
$bedrock.received += [PSCustomObject]@{ kind = 'reconnect'; phase = 'spawned'; protocol = 'bedrock'; attempts = 1; atMs = 299 }
$bedrock.assertions += [PSCustomObject]@{ id = 'bedrock-reconnect'; pass = $true; evidence = 'synthetic transport'; kind = 'reconnect' }
foreach ($phase in @('pre', 'restart', 'reconnect')) {
  $at = if ($phase -eq 'pre') { 50 } elseif ($phase -eq 'restart') { 150 } else { 350 }
  $java.received += New-RecoveryRow "pure-$phase-bedrock" $at
  $bedrock.received += New-RecoveryRow "pure-$phase-java" $at 'text:chat'
}
$verdicts = @(Assert-JointRecoveryResults 1 'pure' $java $bedrock)
Assert-RecoveryCheck (@($verdicts | Where-Object { -not $_.pass }).Count -eq 0) 'receiver-side synthetic recovery fixture accepted'
$restart = New-JointAssertion 'round1/recovery/backend-process-restarted' 'synthetic process transition' $true 'pure fixture only' 'recovery'
foreach ($id in @(Get-RecoveryRequiredAssertionIds -RoundIndex 1)) {
  Assert-RecoveryCheck (@(@($verdicts) + @($restart) | Where-Object { $_.id -ceq $id -and $_.pass -and $_.kind -ne 'diagnostic' }).Count -eq 1) "mandatory recovery contract: $id"
}
Assert-RecoveryCheck (-not (Test-JointRecoveryMembership $null 'backend-restored' @('E2ERecoverJava'))) 'missing receiver cannot prove membership'
$membership = New-RecoveryResult
Add-RecoveryPhase $membership 'backend-restored' 100 'unrelated player chat E2ERecoverJava'
Assert-RecoveryCheck (-not (Test-JointRecoveryMembership $membership 'backend-restored' @('E2ERecoverJava'))) 'unrelated player chat cannot satisfy member list'
$java.received = @($java.received | Where-Object { $_.text -ne 'pure-reconnect-bedrock' })
$java.received += [PSCustomObject]@{ kind = 'messagestr'; text = 'unrelated'; raw = 'pure-reconnect-bedrock'; atMs = 350 }
Assert-RecoveryCheck (-not (Test-JointRecoveryReceipt $java 'pure-reconnect-bedrock' 'bedrock-peer-restored')) 'raw payload cannot prove delivery'
$java.received += New-RecoveryRow 'pure-reconnect-bedrock' 1
Assert-RecoveryCheck (-not (Test-JointRecoveryReceipt $java 'pure-reconnect-bedrock' 'bedrock-peer-restored')) 'pre-phase replay cannot prove recovery'
$bedrock.received[-1].text = 'pure-reconnect-java-reply'
Assert-RecoveryCheck (-not (Test-JointRecoveryReceipt $bedrock 'pure-reconnect-java' 'bedrock-restored')) 'token prefix cannot satisfy another message'

$fixture = Get-JointProxySwitchFixture 'bukkit' 'folia' 'backend-a' 'backend-b'
Assert-RecoveryCheck ($fixture.Identities.Count -eq 2 -and $fixture.Channels[1].ClientId -eq 'backend-b') 'two distinct backend identities and owned SERVER channel'
$rejected = $false
try { $null = Get-JointProxySwitchFixture 'bukkit' 'bukkit' 'backend-a' 'backend-b' } catch { $rejected = $true }
Assert-RecoveryCheck $rejected 'same Java leg cannot masquerade as two servers'
$switchPlans = Get-JointProxySwitchPlans 'pure'
Assert-RecoveryCheck ($switchPlans.Count -eq 3) 'switch plan has mover plus two anchored receivers'
Assert-RecoveryCheck (@($switchPlans.Mover | Where-Object { $_.action -eq 'cmd' -and $_.text.StartsWith('/server ') }).Count -eq 2) 'mover requests both real server switches'
Assert-RecoveryCheck (@($switchPlans.Right | Where-Object { $_.action -eq 'expectEvent' -and $_.sinceCheckpoint }).Count -eq 1) 'secondary observer waits for a fresh transport respawn'
$callbackPlans = Get-JointProxySwitchPlans 'pure' -SwitchMode 'callback'
Assert-RecoveryCheck (@($callbackPlans.Mover | Where-Object { $_.action -eq 'cmd' -and $_.text.StartsWith('/server ') }).Count -eq 0) 'callback switch mode delegates movement to parent'

$mover = New-RecoveryResult
$left = New-RecoveryResult
$right = New-RecoveryResult
$mover.received += New-RecoveryRow 'pure-placed-mover' 1
$left.received += New-RecoveryRow 'pure-placed-left' 1
$right.received += New-RecoveryRow 'pure-placed-right' 1
foreach ($phase in @('pre', 'moved', 'return')) {
  $at = if ($phase -eq 'pre') { 50 } elseif ($phase -eq 'moved') { 150 } else { 450 }
  $left.received += New-RecoveryRow "pure-$phase" $at
  $right.received += New-RecoveryRow "pure-$phase" $at
  $mover.received += New-RecoveryRow "pure-$phase-reply" $at
}
$mover.received += [PSCustomObject]@{ kind = 'checkpoint'; key = 'switch-moved'; atMs = 100 }
$mover.received += New-RecoveryRow 'pure-leg-moved' 101
Add-RecoveryPhase $mover 'switch-moved-membership' 102 'E2ESwitchMover, E2ESwitchLeft, E2ESwitchRight'
$mover.received += [PSCustomObject]@{ kind = 'checkpoint'; key = 'switch-isolation'; atMs = 200 }
$right.received += [PSCustomObject]@{ kind = 'checkpoint'; key = 'switch-isolation'; atMs = 200 }
$left.received += [PSCustomObject]@{ kind = 'checkpoint'; key = 'switch-isolation'; atMs = 200 }
$left.sent += [PSCustomObject]@{ text = '/nc join joint-switch-owned'; atMs = 201 }
$left.received += New-RecoveryRow '[NC-403] Forbidden' 202
$mover.received += New-RecoveryRow 'pure-scoped-secret' 250
$right.received += New-RecoveryRow 'pure-scoped-reply' 251
$mover.received += [PSCustomObject]@{ kind = 'checkpoint'; key = 'switch-return'; atMs = 300 }
$left.received += [PSCustomObject]@{ kind = 'checkpoint'; key = 'switch-return'; atMs = 300 }
$mover.received += New-RecoveryRow 'pure-leg-return' 301
Add-RecoveryPhase $mover 'switch-return-membership' 302 'E2ESwitchMover, E2ESwitchLeft, E2ESwitchRight'
foreach ($row in $mover.received) { if ($row.text -eq 'Channel Global online members (2):') { $row.text = 'Channel Global online members (3):' } }
$mover.received = @($mover.received | Sort-Object atMs)
$left.received = @($left.received | Sort-Object atMs)
$right.received = @($right.received | Sort-Object atMs)
$verdicts = @(Assert-JointProxySwitchResults 1 'pure' $mover $left $right)
Assert-RecoveryCheck (@($verdicts | Where-Object { -not $_.pass }).Count -eq 0) 'synthetic switch and isolation fixture accepted'
foreach ($id in @(Get-ProxySwitchRequiredAssertionIds -RoundIndex 1)) {
  Assert-RecoveryCheck (@($verdicts | Where-Object { $_.id -ceq $id -and $_.pass -and $_.kind -ne 'diagnostic' }).Count -eq 1) "mandatory proxy switch contract: $id"
}
$left.received += New-RecoveryRow 'pure-scoped-secret' 260
$verdicts = @(Assert-JointProxySwitchResults 1 'pure' $mover $left $right)
Assert-RecoveryCheck (@($verdicts | Where-Object { $_.id -eq 'round1/proxy-switch/no-cross-link-secret' -and -not $_.pass }).Count -eq 1) 'cross-link leak rejected'
$verdicts = @(Assert-JointProxySwitchResults 1 'pure' $mover $null $right)
Assert-RecoveryCheck (@($verdicts | Where-Object { $_.id -eq 'round1/proxy-switch/no-cross-link-secret' -and -not $_.pass }).Count -eq 1) 'missing excluded receiver cannot pass isolation'
$mover.received += New-RecoveryRow 'pure-moved-reply' 151
Assert-RecoveryCheck (-not (Test-JointRecoveryReceipt $mover 'pure-moved-reply' 'switch-moved' -ExactlyOnce)) 'duplicate rendering rejected'

& node (Join-Path $PSScriptRoot 'bots/joint-bot.js') --selftest-recovery
if ($LASTEXITCODE -ne 0) { throw "Bot lifecycle synthetic check exited $LASTEXITCODE" }
Write-Host 'Recovery pure checks finished; no real server, Bedrock login, proxy switch, or restart was exercised.'
