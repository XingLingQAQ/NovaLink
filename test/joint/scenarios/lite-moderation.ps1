. (Join-Path $PSScriptRoot 'helpers.ps1')

function Get-JointLiteModerationPrerequisites {
  [PSCustomObject]@{
    Group = 'lite-moderation'
    Mode = 'lite'
    Proxy = @('velocity', 'bungee')
    Channels = @('global', 'local')
    Locale = 'en_US'
    Target = 'E2ELModTarget'
    Observer = 'E2ELModPeer'
    PlayerPermissions = 'Basic commands and both channels only; no novachat.mute/kick/ban/admin or wildcard'
    AdminCommand = 'param([string]$Command); write and flush the real proxy process StandardInput'
    Backend = 'Not required'
  }
}

function Get-JointLiteModerationStages {
  @(
    @{ Id = 'control'; Channel = 'global' },
    @{ Id = 'permission-mute'; Channel = 'global'; DenyRole = 'Target'; Deny = 'mute E2ELModPeer 10m global'; Inverse = $true },
    @{ Id = 'mute-blocks'; Channel = 'global'; Admin = 'mute E2ELModTarget 10m global'; Error = 'NC-436' },
    @{ Id = 'permission-unmute'; Channel = 'global'; DenyRole = 'Observer'; Deny = 'unmute E2ELModTarget global'; Error = 'NC-436' },
    @{ Id = 'mute-channel-scope'; Channel = 'local' },
    @{ Id = 'unmute-restores'; Channel = 'global'; Admin = 'unmute E2ELModTarget global' },
    @{ Id = 'permission-kick'; Channel = 'global'; DenyRole = 'Target'; Deny = 'kick E2ELModPeer global'; Inverse = $true },
    @{ Id = 'kick-removes'; Channel = 'global'; Admin = 'kick E2ELModTarget global'; Error = 'NC-433'; CheckConnected = $true },
    @{ Id = 'kick-rejoin'; Channel = 'global' },
    @{ Id = 'permission-ban'; Channel = 'global'; DenyRole = 'Target'; Deny = 'ban E2ELModPeer 10m global'; Inverse = $true },
    @{ Id = 'ban-blocks'; Channel = 'global'; Admin = 'ban E2ELModTarget 10m global'; Error = 'NC-403'; JoinDenied = $true },
    @{ Id = 'ban-channel-scope'; Channel = 'local' },
    @{ Id = 'permission-unban'; Channel = 'global'; DenyRole = 'Observer'; Deny = 'unban E2ELModTarget global'; Error = 'NC-403'; JoinDenied = $true },
    @{ Id = 'unban-restores'; Channel = 'global'; Admin = 'unban E2ELModTarget global' }
  ) | ForEach-Object {
    $stage = $_
    foreach ($key in @('Admin', 'DenyRole', 'Deny', 'Error', 'Inverse', 'CheckConnected', 'JoinDenied')) {
      if (-not $stage.ContainsKey($key)) { $stage[$key] = $null }
    }
    $stage
  }
}

function Get-JointLiteModerationAssertionIds {
  param([int]$RoundIndex)
  $prefix = "round$RoundIndex/lite-moderation"
  @('drive', 'target-completed', 'observer-completed', 'kick-keeps-minecraft') | ForEach-Object { "$prefix/$_" }
  Get-JointLiteModerationStages | ForEach-Object { "$prefix/$($_.Id)" }
}

function Get-JointLiteModerationPlans {
  param([Parameter(Mandatory = $true)][string]$RunId)
  $plans = @{ Target = @(); Observer = @() }
  foreach ($role in @('Target', 'Observer')) {
    foreach ($channel in @('local', 'global')) {
      $plans[$role] += @(
        @{ id = "setup-arm-$channel"; action = 'arm'; token = "Joined channel $channel"; reset = $true },
        @{ id = "setup-join-$channel"; action = 'cmd'; text = "/nc join $channel" },
        @{ id = "setup-joined-$channel"; action = 'expect'; token = "Joined channel $channel"; timeoutMs = 15000 }
      )
    }
  }

  foreach ($stage in @(Get-JointLiteModerationStages)) {
    $id = $stage.Id
    $token = "$RunId-payload-$id"
    $live = "$RunId-live-$id"
    $receiver = if ($stage.Inverse) { 'Target' } else { 'Observer' }
    foreach ($role in @('Target', 'Observer')) {
      $plans[$role] += @{ id = "$id-prepare"; action = 'handoffGet'; key = "$id-prepare"; timeoutMs = 45000 }
      if ($role -eq 'Observer' -or -not $stage.Error) {
        $plans[$role] += @(
          @{ id = "$id-arm-joined"; action = 'arm'; token = "Joined channel $($stage.Channel)"; reset = $true },
          @{ id = "$id-join"; action = 'cmd'; text = "/nc join $($stage.Channel)" },
          @{ id = "$id-joined"; action = 'expect'; token = "Joined channel $($stage.Channel)"; timeoutMs = 15000 }
        )
      }
      if ($stage.DenyRole -eq $role) {
        $plans[$role] += @(
          @{ id = "$id-arm-denial"; action = 'arm'; token = 'NC-403'; reset = $true },
          @{ id = "$id-deny-command"; action = 'cmd'; text = "/nc $($stage.Deny)" },
          @{ id = "$id-denied"; action = 'expect'; token = 'NC-403'; timeoutMs = 15000 }
        )
      }
      $plans[$role] += @(
        @{ id = "$id-arm-payload"; action = 'arm'; token = $token; reset = $true },
        @{ id = "$id-arm-live"; action = 'arm'; token = $live; reset = $true },
        @{ id = "$id-ready"; action = 'handoffSet'; key = "$id-$($role.ToLowerInvariant())-ready"; pattern = 'Joined channel' },
        @{ id = "$id-go"; action = 'handoffGet'; key = "$id-go"; timeoutMs = 45000 }
      )
    }

    if ($stage.JoinDenied) {
      $plans.Target += @(
        @{ id = "$id-arm-join-denied"; action = 'arm'; token = 'NC-403'; reset = $true },
        @{ id = "$id-rejoin-command"; action = 'cmd'; text = '/nc join global' },
        @{ id = "$id-join-denied"; action = 'expect'; token = 'NC-403'; timeoutMs = 15000 }
      )
    }
    if ($stage.Error) {
      $plans.Target += @(
        @{ id = "$id-arm-error"; action = 'arm'; token = $stage.Error; reset = $true },
        @{ id = "$id-send"; action = 'cmd'; text = "/nc $($stage.Channel) $token" },
        @{ id = "$id-rejected"; action = 'expect'; token = $stage.Error; timeoutMs = 15000 },
        @{ id = "$id-done"; action = 'handoffSet'; key = "$id-target-done"; pattern = [regex]::Escape($stage.Error) }
      )
      $plans.Observer += @{ id = "$id-absent"; action = 'expectAbsent'; token = $token }
    } else {
      $sender = if ($stage.Inverse) { 'Observer' } else { 'Target' }
      $plans[$sender] += @{ id = "$id-send"; action = 'cmd'; text = "/nc $($stage.Channel) $token" }
      $plans[$receiver] += @(
        @{ id = "$id-delivered"; action = 'expect'; token = $token; timeoutMs = 15000 },
        @{ id = "$id-received"; action = 'handoffSet'; key = "$id-received"; pattern = [regex]::Escape($token) }
      )
      $plans[$sender] += @{ id = "$id-await-received"; action = 'handoffGet'; key = "$id-received"; timeoutMs = 20000 }
      $plans.Target += @{ id = "$id-done"; action = 'handoffSet'; key = "$id-target-done"; pattern = 'Joined channel' }
    }
    $plans.Observer += @(
      @{ id = "$id-heartbeat"; action = 'cmd'; text = "/nc $($stage.Channel) $live" },
      @{ id = "$id-live"; action = 'expect'; token = $live; timeoutMs = 15000 }
    )
    if ($stage.Error) { $plans.Observer += @{ id = "$id-observation-window"; action = 'wait'; ms = 2500 } }
    if ($stage.CheckConnected) {
      $plans.Observer += @{ id = 'kick-keeps-minecraft'; action = 'expectState'; player = 'E2ELModTarget'; present = $true; timeoutMs = 5000 }
    }
    $plans.Observer += @{ id = "$id-done"; action = 'handoffSet'; key = "$id-observer-done"; pattern = [regex]::Escape($live) }
  }
  foreach ($role in @('Target', 'Observer')) {
    $plans[$role] += @(
      @{ id = 'finish'; action = 'handoffGet'; key = 'finish'; timeoutMs = 45000 },
      @{ id = 'quit'; action = 'quit' }
    )
  }
  return $plans
}

function Test-JointLiteModerationStep {
  param($Result, [string]$Id)
  $verdict = Find-JointBotVerdict -BotResult $Result -Id $Id
  return ($null -ne $verdict -and $verdict.pass)
}

function Assert-JointLiteModerationResults {
  param([int]$RoundIndex, [string]$RunId, $TargetRes, $ObserverRes)
  $prefix = "round$RoundIndex/lite-moderation"
  $results = @{ Target = $TargetRes; Observer = $ObserverRes }
  $usable = @{}
  foreach ($role in @('Target', 'Observer')) {
    $res = $results[$role]
    $ok = $null -ne $res -and (Test-JointBotRecording $res)
    if ($ok) { $ok = -not $res.error -and -not $res.timedOut -and -not $res.state.kicked }
    foreach ($channel in @('local', 'global')) {
      $ok = $ok -and (Test-JointLiteModerationStep $res "setup-joined-$channel")
    }
    $usable[$role] = $ok
    New-JointAssertion -Id "$prefix/$($role.ToLowerInvariant())-completed" -Token 'real bot recording' -Pass $ok `
      -Evidence "recording=$([bool]$res); completed without timeout, error or Minecraft kick=$ok" -Kind 'moderation'
  }
  $both = $usable.Target -and $usable.Observer
  foreach ($stage in @(Get-JointLiteModerationStages)) {
    $id = $stage.Id
    $token = "$RunId-payload-$id"
    $live = "$RunId-live-$id"
    $receiver = if ($stage.Inverse) { $TargetRes } else { $ObserverRes }
    $sender = if ($stage.Inverse) { $ObserverRes } else { $TargetRes }
    $sent = @()
    if ($sender) { $sent = @($sender.sent | Where-Object { $_.text -eq "/nc $($stage.Channel) $token" }) }
    $liveRows = @(Get-JointBotRows $ObserverRes -Kind 'messagestr' -TextContains $live)
    $pass = $both -and $sent.Count -eq 1 -and $liveRows.Count -gt 0 `
      -and (Test-JointLiteModerationStep $ObserverRes "$id-live")
    if ($stage.Error) {
      $payloadRows = @(Get-JointBotRows $ObserverRes -Kind 'messagestr' -TextContains $token)
      $pass = $pass -and $payloadRows.Count -eq 0 `
        -and (Test-JointLiteModerationStep $TargetRes "$id-rejected") `
        -and (Test-JointLiteModerationStep $ObserverRes "$id-absent")
    } else {
      $payloadRows = @(Get-JointBotRows $receiver -Kind 'messagestr' -TextContains $token)
      $pass = $pass -and $payloadRows.Count -gt 0 `
        -and (Test-JointLiteModerationStep $receiver "$id-delivered")
    }
    if ($stage.DenyRole) {
      $pass = $pass -and (Test-JointLiteModerationStep $results[$stage.DenyRole] "$id-denied")
    }
    if ($stage.JoinDenied) {
      $pass = $pass -and (Test-JointLiteModerationStep $TargetRes "$id-join-denied")
    }
    New-JointAssertion -Id "$prefix/$id" -Token $token -Pass $pass `
      -Evidence "sender commands=$($sent.Count); receiver payload rows=$($payloadRows.Count); observer heartbeat rows=$($liveRows.Count); expected rejection=$($stage.Error); permission denial actor=$($stage.DenyRole)" -Kind 'moderation'
  }
  $connected = $both -and (Test-JointLiteModerationStep $ObserverRes 'kick-keeps-minecraft')
  New-JointAssertion -Id "$prefix/kick-keeps-minecraft" -Token 'E2ELModTarget' -Pass $connected `
    -Evidence "observer tab-list still contains target after channel kick; both recordings complete=$connected" -Kind 'moderation'
}

function Invoke-JointLiteModerationScenarios {
  param(
    $Round,
    [string]$RoundDir,
    [string]$McVersion,
    [string]$Mode,
    [scriptblock]$AdminCommand,
    [int]$Port = $ProxyPort,
    [string]$RunId = "$($script:RunId)-r$($Round.Index)-lite-moderation"
  )
  $prefix = "round$($Round.Index)/lite-moderation"
  if ($Mode -ne 'lite' -or $Round.Proxy -notin @('velocity', 'bungee') -or -not $Round.Java -or -not $AdminCommand) {
    New-JointAssertion -Id "$prefix/preconditions" -Token 'Lite proxy console and two Java clients' -Pass $false `
      -Evidence 'NOT EXECUTED: requires mode=lite, Velocity/Bungee, Java downstream, en_US, global/local, and real proxy AdminCommand callback' -Kind 'diagnostic'
    return
  }

  $work = Join-Path $RoundDir 'bots/lite-moderation'
  $handoffs = Join-Path $work 'handoff'
  New-Item -ItemType Directory -Force -Path $handoffs | Out-Null
  $plans = Get-JointLiteModerationPlans -RunId $RunId
  $bots = @()
  $driveOk = $false
  $driveEvidence = ''
  try {
    foreach ($role in @('Target', 'Observer')) {
      $lower = $role.ToLowerInvariant()
      $username = if ($role -eq 'Target') { 'E2ELModTarget' } else { 'E2ELModPeer' }
      $planPath = Join-Path $work "plan-$lower.json"
      $json = New-CrossServerPlan -role "lite-mod-$lower" -kind 'java' -username $username `
        -port $Port -mcVersion $McVersion -resultsFile (Join-Path $work "$lower.results.json") `
        -handoffDir $handoffs -timeoutMs 360000 -runId $RunId -steps $plans[$role]
      [IO.File]::WriteAllText($planPath, $json, (New-Object Text.UTF8Encoding $false))
      $bots += [PSCustomObject]@{ Role = $lower; Process = (Start-Bot "lite-mod-$lower" $planPath $work `
        (Join-Path $work "$lower.stdout.log") (Join-Path $work "$lower.stderr.log")) }
    }
    foreach ($stage in @(Get-JointLiteModerationStages)) {
      if ($stage.Admin) {
        & $AdminCommand "nc $($stage.Admin)" | Out-Null
        Start-Sleep -Milliseconds 750
      }
      Set-Handoff $handoffs $RunId "$($stage.Id)-prepare" 'go'
      foreach ($role in @('target', 'observer')) {
        if (-not (Wait-Handoff $handoffs $RunId "$($stage.Id)-$role-ready" 40)) { throw "$($stage.Id): $role not ready" }
      }
      Set-Handoff $handoffs $RunId "$($stage.Id)-go" 'go'
      foreach ($role in @('target', 'observer')) {
        if (-not (Wait-Handoff $handoffs $RunId "$($stage.Id)-$role-done" 40)) { throw "$($stage.Id): $role did not finish" }
      }
    }
    Set-Handoff $handoffs $RunId 'finish' 'go'
    Wait-Bots $bots 30
    foreach ($bot in $bots) {
      if (-not $bot.Process.HasExited -or $bot.Process.ExitCode -ne 0) { throw "$($bot.Role) exited unsuccessfully" }
    }
    $driveOk = $true
    $driveEvidence = 'Two bot processes completed all stages; console writes alone do not establish moderation outcomes'
  } catch {
    $driveEvidence = $_.Exception.Message
  } finally {
    foreach ($bot in $bots) {
      if (-not $bot.Process.HasExited) { Stop-Process -Id $bot.Process.Id -Force -ErrorAction SilentlyContinue }
    }
  }
  New-JointAssertion -Id "$prefix/drive" -Token 'Lite moderation driver' -Pass $driveOk -Evidence $driveEvidence -Kind 'moderation'
  $target = Read-BotResult (Join-Path $work 'target.results.json')
  $observer = Read-BotResult (Join-Path $work 'observer.results.json')
  Assert-JointLiteModerationResults -RoundIndex $Round.Index -RunId $RunId -TargetRes $target -ObserverRes $observer
}
