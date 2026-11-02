[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
foreach ($name in @('custom-effects.ps1', 'verify-custom-effects.ps1', '../fixtures/custom-effects/installer.ps1')) {
  $tokens = $null; $errors = $null
  $null = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name), [ref]$tokens, [ref]$errors)
  if ($errors.Count) { throw "$name parse failed: $($errors.Message -join '; ')" }
}
. (Join-Path $PSScriptRoot 'custom-effects.ps1')
Write-Host '[SYNTHETIC] Custom title packet/plan contracts only; no server acceptance or rendering is exercised.'

function Assert-CustomContract([bool]$Pass, [string]$Label) {
  if (-not $Pass) { throw "Custom effects contract failed: $Label" }
}

function New-CustomContractRow([string]$Kind, [double]$At, [string]$Text, [hashtable]$Data = @{}) {
  [PSCustomObject]@{ kind = $Kind; atMs = $At; text = $Text; raw = $Text; data = [PSCustomObject]$Data; key = $Text }
}

function New-CustomContractResults {
  $results = @{}
  $names = @{ Sender = 'E2EJointJava'; Target = 'E2ECustomTarget'; Observer = 'E2ECustomWatch' }
  $clocks = @{ Sender = 100000; Target = 9000000; Observer = 500000 }
  foreach ($role in @('Sender', 'Target', 'Observer')) {
    $at = $clocks[$role]
    $channel = if ($role -eq 'Target') { 'global' } else { 'local' }
    $rows = @((New-CustomContractRow 'spawn' $at 'spawned'))
    $verdicts = @([PSCustomObject]@{ id = 'ce-joined'; pass = $true })
    if ($role -eq 'Sender') {
      $rows += @(
        (New-CustomContractRow 'checkpoint' ($at + 10) 'ce-auth-start'),
        (New-CustomContractRow 'messagestr' ($at + 20) '[NovaChat] Super admin authentication successful'),
        (New-CustomContractRow 'checkpoint' ($at + 30) 'ce-auth-end')
      )
      $verdicts += [PSCustomObject]@{ id = 'ce-auth-success'; pass = $true }
    }
    $rows += @(
      (New-CustomContractRow 'checkpoint' ($at + 100) 'ce-join'),
      (New-CustomContractRow 'messagestr' ($at + 200) "Joined channel $channel")
    )
    $sent = @([PSCustomObject]@{ text = "/nc join $channel"; atMs = $at + 101 })
    foreach ($phase in @(Get-CustomEffectsPhases 'contract')) {
      $at += 5000
      $id = $phase.Id; $lower = $role.ToLowerInvariant()
      $before = "contract-ce-$id-$lower-before"; $after = "contract-ce-$id-$lower-after"
      $rows += @(
        (New-CustomContractRow 'checkpoint' $at "ce-$id-start"),
        (New-CustomContractRow 'messagestr' ($at + 100) "<$($names[$role])> $before")
      )
      $sent += [PSCustomObject]@{ text = "/nc $channel $before"; atMs = $at + 1 }
      if ($role -eq 'Sender') {
        $rows += @(
          (New-CustomContractRow 'checkpoint' ($at + 150) "ce-$id-command-start"),
          (New-CustomContractRow 'messagestr' ($at + 190) '[NovaChat] Title sent'),
          (New-CustomContractRow 'checkpoint' ($at + 195) "ce-$id-command-end")
        )
        $sent += [PSCustomObject]@{ text = (Get-CustomEffectsTitleCommand $phase); atMs = $at + 160 }
        $verdicts += [PSCustomObject]@{ id = "ce-$id-command-success"; pass = $true }
      }
      if ($role -eq $phase.Recipient -or ($role -eq 'Sender' -and $id -eq 'control')) {
        $rows += @(
          (New-CustomContractRow 'titleTimes' ($at + 200) 'durations' @{ fadeIn = $phase.FadeIn; stay = $phase.Stay; fadeOut = $phase.FadeOut }),
          (New-CustomContractRow 'title' ($at + 201) "title: $($phase.Title)" @{ type = 'title'; text = $phase.Title }),
          (New-CustomContractRow 'title' ($at + 202) "subtitle: $($phase.Subtitle)" @{ type = 'subtitle'; text = $phase.Subtitle })
        )
      }
      $rows += @(
        (New-CustomContractRow 'messagestr' ($at + 2500) "<$($names[$role])> $after"),
        (New-CustomContractRow 'checkpoint' ($at + 2501) "ce-$id-end")
      )
      $sent += [PSCustomObject]@{ text = "/nc $channel $after"; atMs = $at + 2400 }
    }
    $rows += New-CustomContractRow 'checkpoint' ($at + 3000) 'ce-completed'
    $results[$role] = [PSCustomObject]@{
      runId = 'contract'; username = $names[$role]; kind = 'java'; received = $rows; sent = $sent
      assertions = $verdicts; absentChecks = @(); handoff = [PSCustomObject]@{ 'ce-release' = 'go' }
      error = $null; timedOut = $false; state = [PSCustomObject]@{ kicked = $null }
    }
  }
  return $results
}

function Get-CustomContractVerdicts($Results) {
  @(Assert-CustomEffectsResults -RoundIndex 4 -RunId 'contract' -SenderRes $Results.Sender `
    -TargetRes $Results.Target -ObserverRes $Results.Observer)
}

function Assert-CustomContractRejected($Results, [string]$Id) {
  $rows = @(Get-CustomContractVerdicts $Results | Where-Object { $_.id -ceq "round4/custom-effects/$Id" })
  Assert-CustomContract ($rows.Count -eq 1 -and $rows[0].pass -eq $false -and $rows[0].kind -ne 'diagnostic') $Id
}

$required = @(Get-CustomEffectsRequiredAssertionIds -RoundIndex 4)
$results = New-CustomContractResults
$verdicts = @(Get-CustomContractVerdicts $results)
Assert-CustomContract ($required.Count -eq 16 -and @($required | Select-Object -Unique).Count -eq 16) '16 distinct required IDs'
Assert-CustomContract ($verdicts.Count -eq 15 -and @($verdicts | Where-Object { -not $_.pass }).Count -eq 0) 'complete synthetic commands and packets with independent receiver clocks'
Assert-CustomContract (@($verdicts | Where-Object { $_.id -notin $required }).Count -eq 0) 'required IDs match producer'

$plans = Get-CustomEffectsPlans -RunId 'contract'
foreach ($role in @('Sender', 'Target', 'Observer')) {
  $waited = 100000; $commands = 0
  foreach ($step in $plans[$role]) {
    if ($step.action -eq 'wait') { $waited += $step.ms }
    if ($step.action -eq 'cmd') {
      Assert-CustomContract ($waited -ge 1500) "$role command cadence"
      Assert-CustomContract ($step.text -notmatch '/nc auth') 'no secret-bearing player command'
      $commands++; $waited = 0
    }
  }
  $expectedCommands = if ($role -eq 'Sender') { 7 } else { 5 }
  Assert-CustomContract ($commands -eq $expectedCommands) "$role command count"
  $titles = @($plans[$role] | Where-Object { $_.action -eq 'cmd' -and $_.text -like '/nc title *' })
  $expectedTitles = if ($role -eq 'Sender') { 2 } else { 0 }
  Assert-CustomContract ($titles.Count -eq $expectedTitles) "$role real title command count"
  if ($role -eq 'Sender') {
    $phases = @(Get-CustomEffectsPhases 'contract')
    for ($i = 0; $i -lt $phases.Count; $i++) {
      Assert-CustomContract ($titles[$i].text -ceq (Get-CustomEffectsTitleCommand $phases[$i])) 'exact command channel, text and named timing flags'
    }
  }
  foreach ($step in @($plans[$role] | Where-Object { $_.action -eq 'expectText' })) {
    Assert-CustomContract ([bool]$step.sinceCheckpoint -and $step.required -eq $true) 'fresh required chat receipts'
  }
}
$bridge = Get-CustomEffectsBukkitBridge
Assert-CustomContract ($bridge.Source.Contains('client.sendPacket(packet)') -and $bridge.Source.Contains('System.getenv(') -and
  $bridge.Source.Contains('createAuthPacket(') -and $bridge.Source.Contains('createLogoutPacket(') -and
  -not $bridge.Source.Contains('case "title"') -and -not $bridge.Source.Contains('"TITLE"') -and
  -not $bridge.Source.Contains('.sendTitle(') -and -not $bridge.Source.Contains('dispatchCommand(')) 'bridge only grants, authenticates and releases; title comes from the bot command'

$results = New-CustomContractResults
$results.Sender.sent = @($results.Sender.sent | Where-Object { $_.text -notlike '/nc title *' })
Assert-CustomContractRejected $results 'target-command'
Assert-CustomContractRejected $results 'control-command'
Assert-CustomContractRejected $results 'target-timings'
Assert-CustomContractRejected $results 'target-absent-observer'

$results = New-CustomContractResults
$results.Sender.sent[2].text = $results.Sender.sent[2].text -replace '--stay 37', '--stay 70'
Assert-CustomContractRejected $results 'target-command'

$results = New-CustomContractResults
$results.Sender.received = @($results.Sender.received | Where-Object { $_.text -ne '[NovaChat] Title sent' })
Assert-CustomContractRejected $results 'target-command'
Assert-CustomContractRejected $results 'target-title'

$results = New-CustomContractResults
$times = @($results.Target.received | Where-Object { $_.kind -eq 'titleTimes' })[0]
$times.data.fadeIn = 10; $times.data.stay = 70; $times.data.fadeOut = 20
Assert-CustomContractRejected $results 'target-timings'
Assert-CustomContractRejected $results 'target-absent-observer'

$results = New-CustomContractResults
$times = @($results.Target.received | Where-Object { $_.kind -eq 'titleTimes' })[0]
$before = @($results.Target.received | Where-Object { $_.text -like '*contract-ce-target-target-before' })[0]
$times.atMs = $before.atMs - 1
Assert-CustomContractRejected $results 'target-timings'

$results = New-CustomContractResults
$results.Target.received = @($results.Target.received | Where-Object { $_.kind -ne 'titleTimes' })
foreach ($row in @($results.Target.received | Where-Object { $_.kind -eq 'title' })) {
  $row.data | Add-Member -MemberType NoteProperty -Name times -Value @{ fadeIn = 3; stay = 37; fadeOut = 9 }
}
Assert-CustomContractRejected $results 'target-timings'

$results = New-CustomContractResults
$results.Observer.received += New-CustomContractRow 'title' 9999999 'late leak' @{ type = 'title'; text = 'contract-ce-target-title' }
Assert-CustomContractRejected $results 'target-absent-observer'

$results = New-CustomContractResults
$before = @($results.Observer.received | Where-Object { $_.text -like '*contract-ce-target-observer-before' })[0]
$index = [array]::IndexOf($results.Observer.received, $before)
$leak = New-CustomContractRow 'titleTimes' ($before.atMs + 1) 'timing-only leak' @{ fadeIn = 3; stay = 37; fadeOut = 9 }
$results.Observer.received = @($results.Observer.received[0..$index]) + @($leak) + @($results.Observer.received[($index + 1)..($results.Observer.received.Count - 1)])
Assert-CustomContractRejected $results 'target-absent-observer'

$results = New-CustomContractResults
$results.Observer.received = @($results.Observer.received | Where-Object { $_.kind -notin @('title', 'titleTimes') })
Assert-CustomContractRejected $results 'control-timings'
Assert-CustomContractRejected $results 'target-absent-observer'

$results = New-CustomContractResults
$results.Target.runId = 'previous-run'
Assert-CustomContractRejected $results 'target-completed'
Assert-CustomContractRejected $results 'target-absent-observer'

$results = New-CustomContractResults
$results.Sender.received = @($results.Sender.received | Where-Object { $_.text -notmatch 'authentication successful' })
Assert-CustomContractRejected $results 'sender-authenticated'
Assert-CustomContractRejected $results 'target-timings'

$results = New-CustomContractResults
$results.Target.received = @($results.Target.received | Where-Object { $_.text -ne 'Joined channel global' })
Assert-CustomContractRejected $results 'target-completed'

$notRun = @(Invoke-CustomEffectsScenarios -RoundIndex 4 -RunId 'contract')
Assert-CustomContract ($notRun.Count -eq $required.Count -and @($notRun | Where-Object { $_.pass -or $_.kind -eq 'diagnostic' }).Count -eq 0) 'missing callbacks and recordings fail every required gate'

$script:CustomEffectsRconCalls = @()
function Send-Rcon([int]$Port, [string]$Password, [string]$Command) {
  $script:CustomEffectsRconCalls += $Command
  "JointCustomEffectsBridge queued $(($Command -split ' ')[1])"
}
function Invoke-CustomContractCallbackConsumer($Callbacks) {
  & $Callbacks.GrantSenderPermissions 'E2EJointJava'
  & $Callbacks.AuthenticateSender 'E2EJointJava'
  & $Callbacks.ReleaseSender 'E2EJointJava'
}
function Invoke-CustomContractCallbackFactory {
  $callbacks = New-CustomEffectsRconCallbacks -RconPort 25590 -RconPassword 'synthetic-rcon'
  Invoke-CustomContractCallbackConsumer $callbacks
}
Invoke-CustomContractCallbackFactory
Assert-CustomContract ($script:CustomEffectsRconCalls.Count -eq 3 -and
  $script:CustomEffectsRconCalls[1] -ceq 'jointcustomeffects auth E2EJointJava' -and
  $script:CustomEffectsRconCalls[2] -ceq 'jointcustomeffects release E2EJointJava') 'RCON adapter carries no auth credential or title operation'
Write-Host '[SYNTHETIC PASS] Plans, 16 gating IDs, real command provenance and backend receipts, local packet windows, exact timings, negative controls and three RCON session closures.'
