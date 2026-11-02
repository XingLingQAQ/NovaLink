[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
foreach ($name in @('channel-lifecycle.ps1', 'verify-channel-lifecycle.ps1')) {
  $tokens = $null; $errors = $null
  $null = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name), [ref]$tokens, [ref]$errors)
  if ($errors.Count) { throw "$name parse failed: $($errors.Message -join '; ')" }
}
. (Join-Path $PSScriptRoot 'channel-lifecycle.ps1')

function Add-ChannelContractLine($Engine, $State, [string]$Text) {
  $State.Result.received += [PSCustomObject]@{ kind = 'messagestr'; text = $Text; raw = $Text; atMs = $Engine.At }
  foreach ($token in @($State.Armed.Keys)) {
    if ($Text.Contains($token) -and -not $State.Seen.ContainsKey($token)) { $State.Seen[$token] = $Text }
  }
}

function Expand-ChannelContractText($Engine, [string]$Text) {
  [regex]::Replace($Text, '\{\{([A-Za-z0-9_]+)\}\}', {
    param($match)
    if (-not $Engine.Handoffs.ContainsKey($match.Groups[1].Value)) { throw "Unresolved handoff template: $Text" }
    [string]$Engine.Handoffs[$match.Groups[1].Value]
  })
}

function New-ChannelContractRecording {
  $runId = 'synthetic-channel-contract'
  $c = Get-JointChannelLifecycleContext $runId
  $plans = Get-JointChannelLifecyclePlans $runId
  $engine = @{ At = 10; Handoffs = @{}; ChannelId = 'NC-A1B2'; Code = 'Q2W3E4'; Exists = $false; Members = @{} }
  $states = @{}
  foreach ($role in @('Owner', 'Invitee', 'Observer')) {
    $states[$role] = @{
      Index = 0; Name = $c[$role]; Channel = 'global'; Armed = @{}; Seen = @{}; Done = $false
      Result = [PSCustomObject]@{
        role = 'channel-lifecycle-' + $role.ToLowerInvariant(); runId = $runId; kind = 'java'; username = $c[$role]
        host = '127.0.0.1'; port = $(if ($role -eq 'Invitee') { 25566 } else { 25565 })
        received = @([PSCustomObject]@{ kind = 'spawn'; text = 'spawned'; raw = 'spawned'; atMs = 1 })
        sent = @(); assertions = @(); absentChecks = @(); handoff = @{}; error = $null; timedOut = $false
      }
    }
  }
  for ($cycle = 0; $cycle -lt 600; $cycle++) {
    if ($engine.Handoffs.ContainsKey('cleanup_ready')) { $engine.Exists = $false; $engine.Handoffs.channel_deleted = $engine.ChannelId }
    if ($engine.Handoffs.ContainsKey('owner_done')) { $engine.Handoffs.lifecycle_release = 'go' }
    $progress = $false
    foreach ($role in @('Owner', 'Invitee', 'Observer')) {
      $state = $states[$role]
      if ($state.Done) { continue }
      $step = $plans[$role][$state.Index]
      if (-not $step) { throw "Contract plan exhausted before quit: $role" }
      $advance = $true
      switch ($step.action) {
        'wait' { $engine.At += $step.ms }
        'checkpoint' { $state.Result.received += [PSCustomObject]@{ kind = 'checkpoint'; key = $step.key; text = $step.key; atMs = $engine.At } }
        'arm' { $state.Armed[$step.token] = $true }
        'expectAbsent' {
          $state.Armed[$step.token] = $true
          $state.Result.absentChecks += [PSCustomObject]@{ id = $step.id; token = $step.token; pass = $true }
        }
        'signal' { $engine.Handoffs[$step.key] = 'ready'; $state.Result.handoff[$step.key] = 'ready' }
        'handoffGet' {
          if (-not $engine.Handoffs.ContainsKey($step.key)) { $advance = $false }
          else { $state.Result.handoff[$step.key] = $engine.Handoffs[$step.key] }
        }
        'handoffSet' {
          $hit = @($state.Result.received | Where-Object { $_.raw -cmatch $step.pattern }) | Select-Object -Last 1
          $match = [regex]::Match([string]$hit.raw, $step.pattern)
          if (-not $hit -or -not $match.Success) { throw "Contract handoff lacks inbound support: $role/$($step.key)" }
          $engine.Handoffs[$step.key] = $match.Groups[1].Value
          $state.Result.handoff[$step.key] = $match.Groups[1].Value
        }
        'cmd' {
          $command = Expand-ChannelContractText $engine $step.text
          $state.Result.sent += [PSCustomObject]@{ text = $command; atMs = $engine.At }
          if ($command -ceq '/nc join global') {
            $state.Channel = 'global'
            Add-ChannelContractLine $engine $state '[NovaChat] Joined channel global'
          } elseif ($command -cmatch '^/nc create (\S+) (\S+)$') {
            $engine.Exists = $true; $engine.Members[$state.Name] = $true; $state.Channel = $engine.ChannelId
            Add-ChannelContractLine $engine $state "[NovaChat] Private channel id: $($engine.ChannelId), password: $($c.Password)"
          } elseif ($command -ceq "/nc invite $($c.Invitee) $($engine.ChannelId)") {
            Add-ChannelContractLine $engine $state "[NovaChat] Invite code: $($engine.Code) (have them run /nc accept $($engine.Code))"
          } elseif ($command -ceq "/nc accept $($engine.Code)") {
            $engine.Members[$state.Name] = $true; $state.Channel = $engine.ChannelId
            Add-ChannelContractLine $engine $state "[NovaChat] Joined channel $($engine.ChannelId) via invite"
          } elseif ($command -ceq "/nc who $($engine.ChannelId)") {
            $members = @($engine.Members.Keys | Sort-Object)
            Add-ChannelContractLine $engine $state "[NovaChat] Channel $($c.Name) online members ($($members.Count)):"
            $body = if ($members.Count) { $members -join ', ' } else { 'No members online' }
            Add-ChannelContractLine $engine $state "[NovaChat] $body"
          } elseif ($command -ceq "/nc leave $($engine.ChannelId)") {
            $engine.Members.Remove($state.Name); $state.Channel = 'global'
            Add-ChannelContractLine $engine $state "[NovaChat] Left channel $($engine.ChannelId), switched to default channel: global"
          } elseif ($command -ceq "/nc join $($engine.ChannelId)") {
            $errorCode = if ($engine.Exists) { 'NC-434' } else { 'NC-404' }
            Add-ChannelContractLine $engine $state "[NovaChat] [$errorCode] refused"
          } else { throw "Unsupported contract command: $command" }
        }
        'chat' {
          $state.Result.sent += [PSCustomObject]@{ text = $step.text; atMs = $engine.At }
          foreach ($recipient in $states.Values) {
            if ($recipient.Channel -ceq $state.Channel) { Add-ChannelContractLine $engine $recipient "[$($state.Channel)] $($state.Name): $($step.text)" }
          }
        }
        'expect' {
          if (-not $state.Seen.ContainsKey($step.token)) { $advance = $false }
          else { $state.Result.assertions += [PSCustomObject]@{ id = $step.id; token = $step.token; pass = $true; evidence = $state.Seen[$step.token] } }
        }
        'expectText' {
          $pattern = Expand-ChannelContractText $engine $step.pattern
          $boundary = @($state.Result.received | Where-Object { $_.kind -ceq 'checkpoint' -and $_.key -ceq $step.sinceCheckpoint }) | Select-Object -Last 1
          if (-not $boundary) { throw "Missing contract checkpoint: $($step.sinceCheckpoint)" }
          $hit = @(Get-JointChannelLifecycleChatRows $state.Result | Where-Object {
            $_.atMs -ge $boundary.atMs -and (ConvertTo-JointChannelLifecycleText $_.text) -cmatch $pattern
          }) | Select-Object -First 1
          if (-not $hit) { $advance = $false }
          else { $state.Result.assertions += [PSCustomObject]@{ id = $step.id; token = $pattern; pass = $true; evidence = $hit.text } }
        }
        'quit' { $state.Done = $true }
        default { throw "Unsupported contract action: $($step.action)" }
      }
      if ($advance) { $state.Index++; $engine.At++; $progress = $true }
    }
    if (@($states.Values | Where-Object { -not $_.Done }).Count -eq 0) { break }
    if (-not $progress) {
      $blocked = @($states.Keys | ForEach-Object { "$($_):$($plans[$_][$states[$_].Index].action)" }) -join ', '
      throw "Channel contract deadlock: $blocked"
    }
  }
  foreach ($state in $states.Values) {
    if (-not $state.Done) { throw 'Channel contract exceeded step budget' }
    foreach ($check in $state.Result.absentChecks) { $check.pass = -not $state.Seen.ContainsKey($check.token) }
  }
  @{ Owner = $states.Owner.Result; Invitee = $states.Invitee.Result; Observer = $states.Observer.Result }
}

function Get-ChannelContractVerdicts($Results) {
  @(Assert-JointChannelLifecycleResults -RoundIndex 1 -RunId 'synthetic-channel-contract' -PrimaryPort 25565 -SecondaryPort 25566 `
    -OwnerRes $Results.Owner -InviteeRes $Results.Invitee -ObserverRes $Results.Observer)
}

function Assert-ChannelContractFailure($Results, [string]$Name) {
  $verdict = @(Get-ChannelContractVerdicts $Results | Where-Object { $_.id -ceq "round1/channel-lifecycle/$Name" })
  if ($verdict.Count -ne 1 -or $verdict[0].pass -or $verdict[0].kind -eq 'diagnostic') { throw "Channel contract accepted invalid evidence: $Name" }
}

$baseline = New-ChannelContractRecording
$verdicts = Get-ChannelContractVerdicts $baseline
$failed = @($verdicts | Where-Object { -not $_.pass -or $_.kind -eq 'diagnostic' })
if ($failed.Count) { throw "Synthetic channel contract baseline rejected: $($failed | ConvertTo-Json -Compress)" }
foreach ($id in @(Get-ChannelLifecycleRequiredAssertionIds 1)) {
  if (@($verdicts | Where-Object { $_.id -ceq $id }).Count -ne 1) { throw "Missing/duplicate required assertion: $id" }
}
$plans = Get-JointChannelLifecyclePlans 'synthetic-channel-contract'
foreach ($role in @('Owner', 'Invitee', 'Observer')) {
  $quietMs = 0
  foreach ($step in $plans[$role]) {
    if ($step.action -eq 'wait') { $quietMs += $step.ms }
    if ($step.action -in @('cmd', 'chat')) {
      if ($quietMs -lt 2000) { throw "Unpaced channel contract request: $role/$($step.text)" }
      $quietMs = 0
    }
  }
}
$json = $baseline | ConvertTo-Json -Depth 30
$r = $json | ConvertFrom-Json
$r.Owner = $null
Assert-ChannelContractFailure $r 'owner-completed'
Assert-ChannelContractFailure $r 'created-unique-id'

$r = $json | ConvertFrom-Json
$r.Observer.received = @([PSCustomObject]@{ kind = 'spawn'; text = 'spawned'; atMs = 1 })
Assert-ChannelContractFailure $r 'outsider-isolated'

$r = $json | ConvertFrom-Json
$r.Owner.received += @($r.Owner.received | Where-Object { $_.text -cmatch 'Private channel id:' })[0]
Assert-ChannelContractFailure $r 'created-unique-id'

$r = $json | ConvertFrom-Json
($r.Owner.received | Where-Object { $_.text -cmatch 'Private channel id:' }).text = 'unrelated text; raw still has an id'
Assert-ChannelContractFailure $r 'created-unique-id'

$r = $json | ConvertFrom-Json
($r.Owner.received | Where-Object { $_.text -cmatch 'Invite code:' }).atMs = 0
Assert-ChannelContractFailure $r 'invite-code-exported'

$r = $json | ConvertFrom-Json
($r.Invitee.received | Where-Object { $_.text -cmatch ' via invite$' }).text = '[NovaChat] [NC-403] Cannot join private channel from different client'
Assert-ChannelContractFailure $r 'accepted-on-secondary'
Assert-ChannelContractFailure $r 'owner-to-invitee'

$r = $json | ConvertFrom-Json
$r.Owner.sent = @($r.Owner.sent | Where-Object { $_.text -cne 'synthetic-channel-contract-owner-private' })
Assert-ChannelContractFailure $r 'owner-to-invitee'
Assert-ChannelContractFailure $r 'outsider-isolated'

$r = $json | ConvertFrom-Json
$r.Invitee.received = @($r.Invitee.received | Where-Object { $_.text -cne '[NovaChat] E2EChInvitee, E2EChOwner' })
Assert-ChannelContractFailure $r 'invitee-membership'

$r = $json | ConvertFrom-Json
$r.Observer.received += [PSCustomObject]@{ kind = 'messagestr'; text = '[private] E2EChOwner: synthetic-channel-contract-owner-private'; atMs = 999999 }
Assert-ChannelContractFailure $r 'outsider-isolated'

$r = $json | ConvertFrom-Json
$r.Observer.timedOut = $true
Assert-ChannelContractFailure $r 'observer-completed'
Assert-ChannelContractFailure $r 'outsider-isolated'

$r = $json | ConvertFrom-Json
$r.Owner.runId = 'earlier-run'
Assert-ChannelContractFailure $r 'created-unique-id'

$r = $json | ConvertFrom-Json
$r.Invitee.port = 25565
Assert-ChannelContractFailure $r 'accepted-on-secondary'

$r = $json | ConvertFrom-Json
($r.Owner.received | Where-Object { $_.text -cmatch '\[NC-404\]' }).atMs = 0
Assert-ChannelContractFailure $r 'channel-deleted'

Write-Host '[SYNTHETIC CONTRACT] channel lifecycle plans, pacing, 20 mandatory assertions and invalid evidence rejection passed. No servers or sockets; cross-client accept remains unverified.'
