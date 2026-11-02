[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
foreach ($name in @('social-contract.ps1', 'verify-social-contract.ps1')) {
  $tokens = $null; $errors = $null
  $null = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name), [ref]$tokens, [ref]$errors)
  if ($errors.Count) { throw "$name parse failed: $($errors.Message -join '; ')" }
}
. (Join-Path $PSScriptRoot 'social-contract.ps1')

$listSourcePath = Join-Path $PSScriptRoot '../../../NovaChat/client-core/src/main/java/com/nova/chat/client/command/ListCommandService.java'
$listSource = [IO.File]::ReadAllText($listSourcePath, [Text.Encoding]::UTF8)
foreach ($marker in @(@{ Name = 'JOINED_MARKER'; Value = "&a$([char]0x2713)&r" }, @{ Name = 'NOT_JOINED_MARKER'; Value = "&7$([char]0x25cb)&r" })) {
  $match = [regex]::Match($listSource, ('\b' + $marker.Name + '\s*=\s*"([^"]+)"'))
  if (-not $match.Success -or $match.Groups[1].Value -cne $marker.Value) { throw "production list marker contract changed: $($marker.Name)" }
}

function Add-SocialContractFixtureLine {
  param($Engine, $State, [string]$Text)
  $State.Result.received += [PSCustomObject]@{ kind = 'messagestr'; text = $Text; raw = $Text; atMs = $Engine.At }
  foreach ($token in @($State.Armed.Keys)) {
    if ($Text.Contains($token)) { $State.Seen[$token] = $Text }
  }
}

function New-SocialContractFixture {
  param([string]$RunId)
  $plans = Get-JointSocialContractPlans -RunId $RunId
  $engine = @{ At = 10000; Handoffs = @{} }
  $states = @{}
  foreach ($role in @('Sender', 'Target', 'Observer')) {
    $states[$role] = @{
      Index = 0; Armed = @{}; Seen = @{}; Active = 'global'; Joined = @('global'); Ignored = $false; Done = $false; Queries = 0
      Result = [PSCustomObject]@{
        runId = $RunId; username = $plans.Names[$role]; received = @(); sent = @(); assertions = @(); absentChecks = @()
        handoff = @{}; error = $null; timedOut = $false
      }
    }
  }
  for ($cycle = 0; $cycle -lt 300; $cycle++) {
    $progress = $false
    foreach ($role in @('Sender', 'Target', 'Observer')) {
      $state = $states[$role]
      if ($state.Done) { continue }
      $step = $plans[$role][$state.Index]
      if (-not $step) { throw "fixture plan exhausted: $role" }
      $advance = $true
      switch ($step.action) {
        'arm' { $state.Armed[$step.token] = $true }
        'checkpoint' {
          $state.Result.received += [PSCustomObject]@{ kind = 'checkpoint'; key = $step.key; text = $step.key; atMs = $engine.At }
        }
        'signal' {
          $value = if ($step.ContainsKey('value')) { $step.value } else { 'ready' }
          $engine.Handoffs[$step.key] = $value; $state.Result.handoff[$step.key] = $value
        }
        'handoffGet' {
          if (-not $engine.Handoffs.ContainsKey($step.key)) { $advance = $false }
          else { $state.Result.handoff[$step.key] = $engine.Handoffs[$step.key] }
        }
        'wait' { $engine.At += $step.ms }
        'expectAbsent' {
          $state.Armed[$step.token] = $true
          $state.Result.absentChecks += [PSCustomObject]@{ id = $step.id; token = $step.token; pass = $true }
        }
        'expect' {
          if (-not $state.Seen.ContainsKey($step.token)) { $advance = $false }
          else { $state.Result.assertions += [PSCustomObject]@{ id = $step.id; token = $step.token; pass = $true } }
        }
        'expectText' {
          $rows = @($state.Result.received)
          $checkpoint = -1
          for ($i = 0; $i -lt $rows.Count; $i++) {
            if ($rows[$i].kind -eq 'checkpoint' -and $rows[$i].key -ceq $step.sinceCheckpoint) { $checkpoint = $i }
          }
          if ($checkpoint -lt 0) { throw "fixture checkpoint missing: $($step.sinceCheckpoint)" }
          $hits = @($rows | Select-Object -Skip ($checkpoint + 1) | Where-Object {
            $_.kind -eq 'messagestr' -and (ConvertTo-SocialContractText $_.text) -cmatch $step.pattern
          })
          if (-not $hits.Count) { $advance = $false }
          else { $state.Result.assertions += [PSCustomObject]@{ id = $step.id; pass = $true } }
        }
        'cmd' {
          $state.Result.sent += [PSCustomObject]@{ text = $step.text; atMs = $engine.At }
          if ($step.text -match '^/nc join (global|local)$') {
            $state.Active = $Matches[1]
            if ($state.Joined -cnotcontains $state.Active) { $state.Joined += $state.Active }
            Add-SocialContractFixtureLine $engine $state "[NovaChat] Joined channel $($state.Active)"
          } elseif ($step.text -ceq '/nc leave local') {
            $state.Joined = @($state.Joined | Where-Object { $_ -cne 'local' }); $state.Active = 'global'
            Add-SocialContractFixtureLine $engine $state '[NovaChat] Left channel local, switched to default channel: global'
          } elseif ($step.text -ceq '/nc who local') {
            $members = @($states.Keys | Where-Object { $states[$_].Joined -ccontains 'local' } | ForEach-Object { $plans.Names[$_] } | Sort-Object)
            $header = "[NovaChat] Channel Local online members ($($members.Count)):"
            if ($state.Queries -eq 1) {
              Add-SocialContractFixtureLine $engine $state $header
              Add-SocialContractFixtureLine $engine $state ($members -join ', ')
            } else { Add-SocialContractFixtureLine $engine $state "$header`n$($members -join ', ')" }
            $state.Queries++
          } elseif ($step.text -ceq '/nc list') {
            Add-SocialContractFixtureLine $engine $state '=== NovaChat Channels ==='
            foreach ($id in @('global', 'local')) {
              $marker = if ($state.Joined -ccontains $id) { [char]0x2713 } else { [char]0x25cb }
              Add-SocialContractFixtureLine $engine $state "[NovaChat] $marker $id"
            }
            Add-SocialContractFixtureLine $engine $state '==========================='
          } elseif ($step.text -ceq "/nc ignore $($plans.Names.Sender)") {
            $state.Ignored = $true
            Add-SocialContractFixtureLine $engine $state "[NovaChat] Ignored player $($plans.Names.Sender)"
          } elseif ($step.text -ceq "/nc unignore $($plans.Names.Sender)") {
            $state.Ignored = $false
            Add-SocialContractFixtureLine $engine $state "[NovaChat] Unignored player $($plans.Names.Sender)"
          } else { throw "unsupported fixture command: $($step.text)" }
        }
        'chat' {
          $state.Result.sent += [PSCustomObject]@{ text = $step.text; atMs = $engine.At }
          foreach ($receiver in $states.Values) {
            if ($receiver.Active -ceq $state.Active -and -not ($receiver.Ignored -and $role -ceq 'Sender')) {
              Add-SocialContractFixtureLine $engine $receiver "[Global] $($plans.Names[$role]): $($step.text)"
            }
          }
        }
        'quit' { $state.Done = $true }
        default { throw "unsupported fixture action: $($step.action)" }
      }
      if ($advance) { $state.Index++; $engine.At++; $progress = $true }
    }
    if (@($states.Values | Where-Object { -not $_.Done }).Count -eq 0) { break }
    if (-not $progress) {
      $blocked = @($states.Keys | ForEach-Object { "${_}:$($plans[$_][$states[$_].Index] | ConvertTo-Json -Compress)" })
      throw "fixture plan deadlocked: $($blocked -join '; ')"
    }
  }
  $results = @{}
  foreach ($role in $states.Keys) {
    $state = $states[$role]
    if (-not $state.Done) { throw "fixture plan exceeded step budget: $role" }
    foreach ($check in $state.Result.absentChecks) { $check.pass = -not $state.Seen.ContainsKey($check.token) }
    $results[$role] = $state.Result
  }
  return $results
}

function Get-SocialContractFixtureVerdicts {
  param($Results)
  @(Assert-JointSocialContractResults -RoundIndex 1 -RunId 'social-fixture' -SenderRes $Results.Sender `
    -TargetRes $Results.Target -ObserverRes $Results.Observer -ForbiddenChannelIds @('joint-switch-owned'))
}

function Copy-SocialContractFixture {
  $script:SocialBaseline | ConvertTo-Json -Depth 15 | ConvertFrom-Json
}

function Assert-SocialContractFixtureFailure {
  param($Results, [string]$Id)
  $matches = @(Get-SocialContractFixtureVerdicts $Results | Where-Object { $_.id -ceq "round1/social-contract/$Id" })
  if ($matches.Count -ne 1 -or $matches[0].pass -or $matches[0].kind -eq 'diagnostic') { throw "fixture regression accepted: $Id" }
}

$script:SocialBaseline = New-SocialContractFixture -RunId 'social-fixture'
$verdicts = @(Get-SocialContractFixtureVerdicts $script:SocialBaseline)
$required = @(Get-SocialContractRequiredAssertionIds -RoundIndex 1 | Where-Object { $_ -cne 'round1/social-contract/drive' })
foreach ($id in $required) {
  $matches = @($verdicts | Where-Object { $_.id -ceq $id })
  if ($matches.Count -ne 1 -or -not $matches[0].pass -or $matches[0].kind -eq 'diagnostic') {
    throw "fixture baseline failed: $id; $($matches.evidence)"
  }
}
$plans = Get-JointSocialContractPlans -RunId 'social-fixture'
$bad = Copy-SocialContractFixture
$bad.Target.received = @([PSCustomObject]@{ kind = 'spawn'; text = 'spawned'; atMs = 1 })
Assert-SocialContractFixtureFailure $bad 'ignore-suppressed'
$bad = Copy-SocialContractFixture
$bad.Target.received += [PSCustomObject]@{ kind = 'messagestr'; text = "[Global] $($plans.Names.Sender): $($plans.Tokens.Blocked)"; atMs = 10010 }
Assert-SocialContractFixtureFailure $bad 'ignore-suppressed'
$bad = Copy-SocialContractFixture
$bad.Observer.received = @($bad.Observer.received | Where-Object { -not $_.text.Contains($plans.Tokens.Blocked) })
Assert-SocialContractFixtureFailure $bad 'ignore-suppressed'
$bad = Copy-SocialContractFixture
($bad.Target.received | Where-Object { $_.kind -eq 'checkpoint' -and $_.key -ceq 'social-block-end' }).atMs = (Get-SocialContractCheckpointMs $bad.Target 'social-block-start') + 1
Assert-SocialContractFixtureFailure $bad 'ignore-live-window'
$bad = Copy-SocialContractFixture
$bad.Target.runId = 'earlier-run'
Assert-SocialContractFixtureFailure $bad 'target-completed'
$bad = Copy-SocialContractFixture
$header = $bad.Target.received | Where-Object { $_.text -ceq '[NovaChat] Channel Local online members (2):' }
$header.text = '[NovaChat] Channel Local online members (3):'
Assert-SocialContractFixtureFailure $bad 'who-left-count'
$bad = Copy-SocialContractFixture
$body = $bad.Target.received | Where-Object { $_.text -ceq ((@($plans.Names.Sender, $plans.Names.Observer) | Sort-Object) -join ', ') }
$body.text += ', UnexpectedPlayer'
Assert-SocialContractFixtureFailure $bad 'who-left-exact-set'
$bad = Copy-SocialContractFixture
foreach ($line in @($bad.Target.received | Where-Object { $_.text -cmatch '[\u2713\u25cb] local$' })) { $line.text += '-similar' }
Assert-SocialContractFixtureFailure $bad 'list-joined-exact-ids'
$bad = Copy-SocialContractFixture
$listEnd = Get-SocialContractCheckpointMs $bad.Target 'social-list-joined-done'
$tail = $bad.Target.received | Where-Object { $_.text -ceq '===========================' -and $_.atMs -lt $listEnd } | Select-Object -First 1
$tail.text = "$([char]0x25cb) joint-switch-owned`n==========================="
Assert-SocialContractFixtureFailure $bad 'list-configured-scope'
Write-Host "[SYNTHETIC CONTRACT ONLY] Three-role plan completed; $($required.Count) result IDs passed; 9 malformed fixtures rejected. No server, browser or network E2E was run."
