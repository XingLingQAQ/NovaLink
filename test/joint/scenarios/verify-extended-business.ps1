[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
foreach ($name in @('pm.ps1', 'social.ps1', 'extended-business.ps1')) {
  $tokens = $null; $errors = $null
  $null = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name), [ref]$tokens, [ref]$errors)
  if ($errors.Count) { throw "$name parse failed: $($errors.Message -join '; ')" }
}
. (Join-Path $PSScriptRoot 'extended-business.ps1')

function Assert-BotExpectations($Role, $Result) {
  foreach ($verdict in @($Result.assertions) + @($Result.absentChecks)) {
    New-JointAssertion -Id "$Role/$($verdict.id)" -Token $verdict.token -Pass $verdict.pass `
      -Evidence $verdict.evidence -Kind 'synthetic-bot-verdict'
  }
}

function Add-SyntheticLine($Engine, $State, [string]$Text) {
  $State.Result.received += [PSCustomObject]@{ kind = 'messagestr'; text = $Text; raw = $Text; atMs = $Engine.At }
  foreach ($token in @($State.Armed.Keys)) {
    if ($Text.Contains($token) -and -not $State.Seen.ContainsKey($token)) { $State.Seen[$token] = $Text }
  }
}

function New-SyntheticResults {
  $plans = Get-ExtendedBusinessPlans -RunId 'synthetic'
  $engine = @{ At = 1; Handoffs = @{}; Muted = $false }
  $states = @{}
  foreach ($pair in @(
    @{ Role = 'Sender'; Name = 'E2EExtSender' },
    @{ Role = 'Target'; Name = 'E2EExtTarget' },
    @{ Role = 'Observer'; Name = 'E2EExtWatch' }
  )) {
    $states[$pair.Role] = @{
      Index = 0; Name = $pair.Name; Channel = 'global'; Partner = ''; Armed = @{}; Seen = @{}; Done = $false
      Result = [PSCustomObject]@{
        received = @([PSCustomObject]@{ kind = 'spawn'; raw = 'spawned'; text = 'spawned'; atMs = 1 })
        sent = @(); assertions = @(); absentChecks = @(); handoff = @{}; error = $null; timedOut = $false
      }
    }
  }
  for ($cycle = 0; $cycle -lt 1000; $cycle++) {
    if ($engine.Handoffs.ContainsKey('mute-ready')) { $engine.Muted = $true; $engine.Handoffs['mute-applied'] = 'go' }
    if ($engine.Handoffs.ContainsKey('mute-scope-complete')) { $engine.Muted = $false; $engine.Handoffs['mute-unmuted'] = 'go' }
    if ($engine.Handoffs.ContainsKey('extended-finished')) { $engine.Handoffs['extended-release'] = 'go' }
    $progress = $false
    foreach ($role in @('Sender', 'Target', 'Observer')) {
      $state = $states[$role]
      if ($state.Done) { continue }
      $step = $plans[$role][$state.Index]
      if (-not $step) { throw "synthetic plan exhausted without quit: $role" }
      $advance = $true
      switch ($step.action) {
        'arm' { $state.Armed[$step.token] = $true }
        'expectAbsent' {
          $state.Armed[$step.token] = $true
          $state.Result.absentChecks += [PSCustomObject]@{ id = $step.id; token = $step.token; pass = $true; evidence = '' }
        }
        'wait' { $engine.At += $step.ms }
        'cmd' {
          $state.Result.sent += [PSCustomObject]@{ text = $step.text; atMs = $engine.At }
          if ($step.text -match '^/nc join (\S+)$') {
            $state.Channel = $Matches[1]
            Add-SyntheticLine $engine $state "Joined channel $($state.Channel)"
          } elseif ($step.text -match '^/nc mute ') {
            Add-SyntheticLine $engine $state '[NC-403] Permission denied'
          } else {
            $parts = $step.text -split ' ', 4
            $targetName = if ($parts[1] -eq 'r') { $state.Partner } else { $parts[2] }
            $content = if ($parts[1] -eq 'r') { $parts[2] } else { $parts[3] }
            $recipient = @($states.Values | Where-Object { $_.Name -ceq $targetName }) | Select-Object -First 1
            if ($recipient) {
              Add-SyntheticLine $engine $state "You whisper to ${targetName}: $content"
              Add-SyntheticLine $engine $recipient "$($state.Name) whispers to you: $content"
              $state.Partner = $recipient.Name
              $recipient.Partner = $state.Name
            } else { Add-SyntheticLine $engine $state "Player $targetName is not online" }
          }
        }
        'chat' {
          $state.Result.sent += [PSCustomObject]@{ text = $step.text; atMs = $engine.At }
          if (-not ($engine.Muted -and $role -eq 'Sender' -and $state.Channel -eq 'global')) {
            foreach ($recipient in $states.Values) {
              if ($recipient.Channel -eq $state.Channel) { Add-SyntheticLine $engine $recipient "[$($state.Channel)] $($state.Name): $($step.text)" }
            }
          }
        }
        'expect' {
          if (-not $state.Seen.ContainsKey($step.token)) { $advance = $false }
          else {
            $id = if ($step.id) { $step.id } else { $step.token }
            $state.Result.assertions += [PSCustomObject]@{ id = $id; token = $step.token; pass = $true; evidence = $state.Seen[$step.token] }
          }
        }
        'handoffSet' {
          $hit = @($state.Result.received | Where-Object { $_.raw -match $step.pattern }) | Select-Object -Last 1
          if (-not $hit) { throw "synthetic handoff lacks inbound support: $role/$($step.key)" }
          $engine.Handoffs[$step.key] = $hit.raw
          $state.Result.handoff[$step.key] = $hit.raw
        }
        'handoffGet' {
          if (-not $engine.Handoffs.ContainsKey($step.key)) { $advance = $false }
          else { $state.Result.handoff[$step.key] = $engine.Handoffs[$step.key] }
        }
        'quit' { $state.Done = $true }
        default { throw "unsupported synthetic action: $($step.action)" }
      }
      if ($advance) { $state.Index++; $engine.At++; $progress = $true }
    }
    if (@($states.Values | Where-Object { -not $_.Done }).Count -eq 0) { break }
    if (-not $progress) {
      $blocked = @($states.Keys | ForEach-Object { "$($_):$($plans[$_][$states[$_].Index] | ConvertTo-Json -Compress)" })
      throw "synthetic plan deadlocked: $($blocked -join '; ')"
    }
  }
  foreach ($state in $states.Values) {
    if (-not $state.Done) { throw 'synthetic plan exceeded step budget' }
    foreach ($check in $state.Result.absentChecks) {
      $check.pass = -not $state.Seen.ContainsKey($check.token)
      if (-not $check.pass) { $check.evidence = $state.Seen[$check.token] }
    }
  }
  return @{ Sender = $states.Sender.Result; Target = $states.Target.Result; Observer = $states.Observer.Result }
}

function Get-SyntheticVerdicts($Results) {
  @(Assert-ExtendedBusinessResults -RoundIndex 1 -RunId 'synthetic' `
    -SenderRes $Results.Sender -TargetRes $Results.Target -ObserverRes $Results.Observer)
}

function Copy-SyntheticResults {
  $script:Baseline | ConvertTo-Json -Depth 20 | ConvertFrom-Json
}

function Assert-SyntheticFailure($Results, [string]$Id) {
  $verdict = @(Get-SyntheticVerdicts $Results | Where-Object { $_.id -ceq "round1/$Id" })
  if ($verdict.Count -ne 1 -or $verdict[0].pass -or $verdict[0].kind -eq 'diagnostic') {
    throw "synthetic regression accepted: $Id"
  }
  $script:Checks++
}

$script:Checks = 0
$script:Baseline = New-SyntheticResults
$verdicts = Get-SyntheticVerdicts $script:Baseline
$failed = @($verdicts | Where-Object { -not $_.pass -and $_.kind -ne 'diagnostic' })
if ($failed.Count) { throw "synthetic baseline rejected: $($failed | ConvertTo-Json -Depth 6 -Compress)" }
foreach ($id in @(Get-ExtendedBusinessRequiredAssertionIds -RoundIndex 1)) {
  if (@($verdicts | Where-Object { $_.id -ceq $id -and $_.kind -ne 'diagnostic' }).Count -ne 1) {
    throw "synthetic baseline missing or duplicated mandatory assertion: $id"
  }
}
$script:Checks++

$r = Copy-SyntheticResults
$r.Observer.received = @($r.Observer.received | Where-Object { -not $_.text.Contains('synthetic-pm-control') })
Assert-SyntheticFailure $r 'pm/observer-positive-control'
Assert-SyntheticFailure $r 'pm/dm-not-leaked-to-observer'

$r = Copy-SyntheticResults
$r.Observer.received += [PSCustomObject]@{ kind = 'messagestr'; text = 'E2EExtSender whispers to you: synthetic-pm-dm'; atMs = 9999 }
Assert-SyntheticFailure $r 'pm/dm-not-leaked-to-observer'

$r = Copy-SyntheticResults
($r.Sender.received | Where-Object { $_.text.Contains('synthetic-pm-reply') }).text = 'E2EExtWatch whispers to you: synthetic-pm-reply'
Assert-SyntheticFailure $r 'pm/reply-delivered-to-sender'

$r = Copy-SyntheticResults
$r.Sender.sent = @($r.Sender.sent | Where-Object { $_.text -ne '/nc r synthetic-pm-retarget' })
Assert-SyntheticFailure $r 'pm/sender-reply-delivered-to-target'

$r = Copy-SyntheticResults
($r.Sender.received | Where-Object { $_.text.Contains('is not online') }).atMs = 0
Assert-SyntheticFailure $r 'pm/offline-refused-to-sender'
Assert-SyntheticFailure $r 'pm/offline-not-delivered-anywhere'

$r = Copy-SyntheticResults
$r.Observer.absentChecks = @($r.Observer.absentChecks | Where-Object { $_.id -ne 'pm-absent-dm' })
Assert-SyntheticFailure $r 'pm/dm-not-leaked-to-observer'

$r = Copy-SyntheticResults
$r.Observer = $null
Assert-SyntheticFailure $r 'extended-business/observer-completed'
Assert-SyntheticFailure $r 'pm/dm-not-leaked-to-observer'
Assert-SyntheticFailure $r 'channel-mute/muted-absent-from-observer'

$r = Copy-SyntheticResults
$r.Observer.timedOut = $true
Assert-SyntheticFailure $r 'extended-business/observer-completed'
Assert-SyntheticFailure $r 'pm/reply-not-leaked-to-observer'

$r = Copy-SyntheticResults
$r.Target.received += [PSCustomObject]@{ kind = 'messagestr'; text = '[global] E2EExtSender: synthetic-mute-suppressed'; atMs = 9999 }
Assert-SyntheticFailure $r 'channel-mute/muted-absent-from-target'

$r = Copy-SyntheticResults
$r.Sender.sent = @($r.Sender.sent | Where-Object { $_.text -ne 'synthetic-mute-suppressed' })
Assert-SyntheticFailure $r 'channel-mute/muted-absent-from-target'

$r = Copy-SyntheticResults
$r.Target.received = @($r.Target.received | Where-Object { -not $_.text.Contains('synthetic-mute-other') })
Assert-SyntheticFailure $r 'channel-mute/other-channel-delivered'
Assert-SyntheticFailure $r 'channel-mute/mute-persists-after-channel-switch'

$r = Copy-SyntheticResults
$r.Target.sent = @($r.Target.sent | Where-Object { $_.text -ne '/nc join local' })
Assert-SyntheticFailure $r 'channel-mute/other-channel-delivered'

$r = Copy-SyntheticResults
$lastAck = @($r.Target.received | Where-Object { $_.text -eq 'Joined channel global' }) | Select-Object -Last 1
$r.Target.received = @($r.Target.received | Where-Object { $_.atMs -ne $lastAck.atMs })
Assert-SyntheticFailure $r 'channel-mute/mute-persists-after-channel-switch'

$r = Copy-SyntheticResults
($r.Observer.received | Where-Object { $_.text.Contains('NC-403') }).atMs = 0
Assert-SyntheticFailure $r 'channel-mute/permission-refused'

$r = Copy-SyntheticResults
$r.Observer.received = @($r.Observer.received | Where-Object { -not $_.text.Contains('synthetic-mute-restored') })
Assert-SyntheticFailure $r 'channel-mute/unmute-restores-observer'
Assert-SyntheticFailure $r 'channel-mute/muted-absent-from-observer'

$r = Copy-SyntheticResults
$r.Target.sent = @($r.Target.sent | Where-Object { $_.text -ne 'synthetic-mute-alive' })
Assert-SyntheticFailure $r 'channel-mute/muted-channel-still-delivers'
Assert-SyntheticFailure $r 'channel-mute/muted-absent-from-target'

$r = Copy-SyntheticResults
$r.Sender.sent = @($r.Sender.sent | Where-Object { $_.text -ne 'synthetic-mute-stillmuted' })
Assert-SyntheticFailure $r 'channel-mute/mute-persists-after-channel-switch'

$setup = @(Invoke-ExtendedBusinessScenarios -Round ([PSCustomObject]@{ Index = 1; Proxy = 'velocity'; Java = 'paper' }))
if ($setup.Count -ne 1 -or $setup[0].pass -or $setup[0].kind -eq 'diagnostic') { throw 'missing admin callback did not fail setup' }
$script:Checks++
Write-Host "[SYNTHETIC CHECK] $($script:Checks) checks accepted; no server, console or network was used."
