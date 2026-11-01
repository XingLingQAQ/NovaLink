. (Join-Path $PSScriptRoot 'helpers.ps1')
. (Join-Path $PSScriptRoot 'pm.ps1')
. (Join-Path $PSScriptRoot 'social.ps1')

function Get-ExtendedBusinessRequiredAssertionIds {
  param([int]$RoundIndex)

  foreach ($role in @('sender', 'target', 'observer')) { "round$RoundIndex/extended-business/$role-completed" }
  foreach ($id in @(
    'observer-positive-control', 'sender-delivery-confirmation', 'dm-delivered-to-target',
    'dm-not-leaked-to-observer', 'control-not-leaked-to-target', 'reply-delivered-to-sender',
    'reply-not-leaked-to-observer', 'sender-reply-delivered-to-target',
    'sender-reply-not-leaked-to-observer', 'offline-refused-to-sender', 'offline-not-delivered-anywhere'
  )) { "round$RoundIndex/pm/$id" }
  foreach ($id in @(
    'permission-refused', 'permission-denied-keeps-chat', 'premute-delivered-to-target',
    'premute-delivered-to-observer', 'muted-absent-from-target', 'muted-absent-from-observer',
    'muted-channel-still-delivers', 'other-channel-delivered', 'mute-persists-after-channel-switch',
    'unmute-restores-target', 'unmute-restores-observer'
  )) { "round$RoundIndex/channel-mute/$id" }
}

function Get-ExtendedBusinessPlans {
  param([string]$RunId)

  $pm = Get-PrivateMessagePlanSteps -RunId $RunId
  $mute = Get-ChannelMutePlanSteps -RunId $RunId
  $plans = @{}
  foreach ($role in @('Sender', 'Target', 'Observer')) {
    $steps = @($pm.$role) + @($mute.$role)
    # Arm every recorder before the first peer can send.
    $plans[$role] = @($steps | Where-Object { $_.action -in @('arm', 'expectAbsent') }) + @(
      @{ action = 'arm'; token = 'Joined channel global' },
      @{ action = 'wait'; ms = 2000 },
      @{ id = 'extended-join-global'; action = 'cmd'; text = '/nc join global' },
      @{ id = 'extended-joined-global'; action = 'expect'; token = 'Joined channel global'; timeoutMs = 20000 }
    ) + @($steps | Where-Object { $_.action -notin @('arm', 'expectAbsent') }) + @(
      @{ action = 'handoffGet'; key = 'extended-release'; timeoutMs = 45000 },
      @{ action = 'wait'; ms = 3000 },
      @{ id = 'extended-quit'; action = 'quit' }
    )
  }
  return $plans
}

function Assert-ExtendedBusinessResults {
  param([int]$RoundIndex, [string]$RunId, $SenderRes, $TargetRes, $ObserverRes)

  foreach ($pair in @(
    @{ Role = 'sender'; Res = $SenderRes },
    @{ Role = 'target'; Res = $TargetRes },
    @{ Role = 'observer'; Res = $ObserverRes }
  )) {
    $res = $pair.Res
    $completed = [bool]((Test-JointBotRecording $res) -and -not $res.error -and -not $res.timedOut -and
      $res.handoff.'extended-release' -eq 'go')
    New-JointAssertion -Id "round$RoundIndex/extended-business/$($pair.Role)-completed" -Token 'results + final release' `
      -Pass $completed -Evidence "recording=$([bool]$res); error=$($res.error); timedOut=$($res.timedOut); final release=$($res.handoff.'extended-release')" -Kind 'extended-business'
    if ($res) { Assert-BotExpectations "extended-$($pair.Role)" $res }
  }
  Invoke-PrivateMessageScenarios -RoundIndex $RoundIndex -RunId $RunId `
    -SenderRes $SenderRes -TargetRes $TargetRes -ObserverRes $ObserverRes
  Assert-ChannelMuteScenarios -RoundIndex $RoundIndex -RunId $RunId `
    -SenderRes $SenderRes -TargetRes $TargetRes -ObserverRes $ObserverRes
}

function Invoke-ExtendedBusinessScenarios {
  param(
    $Round,
    [string]$RoundDir,
    [string]$McVersion,
    [scriptblock]$AdminCommand,
    [int]$Port = 0
  )

  $prefix = "round$($Round.Index)/extended-business"
  if ($Round.Proxy -notin @('velocity', 'bungee') -or -not $Round.Java -or -not $AdminCommand) {
    New-JointAssertion -Id "$prefix/setup" -Token 'proxy + Java downstream + console callback' -Pass $false `
      -Evidence 'NOT EXECUTED: selected extended business group requires Velocity/Bungee, a Java downstream, en_US, global/local channels, ordinary bot permissions and -AdminCommand { param([string]$Command) ... }' -Kind 'extended-business'
    return
  }
  if ($Port -le 0) { $Port = $ProxyPort }
  $runId = "$($script:RunId)-r$($Round.Index)-extended"
  $work = Join-Path $RoundDir 'bots/extended-business'
  $handoffs = Join-Path $work 'handoff'
  New-Item -ItemType Directory -Force -Path $handoffs | Out-Null
  $plans = Get-ExtendedBusinessPlans -RunId $runId
  $bots = @()
  $out = @()
  $requests = New-Object System.Collections.Generic.List[object]
  $muteAttempted = $false
  $unmuteAttempted = $false
  $sendAdmin = {
    param([string]$command)
    $requests.Add([PSCustomObject]@{ command = $command; requestedAt = (Get-Date).ToUniversalTime().ToString('o') })
    [IO.File]::WriteAllText((Join-Path $work 'admin-requests.json'), ($requests.ToArray() | ConvertTo-Json -Depth 4),
      (New-Object Text.UTF8Encoding $false))
    $null = & $AdminCommand $command
  }
  $wait = {
    param([string]$key, [int]$seconds)
    if (-not (Wait-Handoff $handoffs $runId $key $seconds)) { throw "extended business handoff missing: $key" }
  }
  try {
    if (@(Get-ChildItem -LiteralPath $handoffs -Filter '*.json').Count -gt 0 -or
        @(Get-ChildItem -LiteralPath $work -Filter '*.results.json').Count -gt 0) {
      throw 'extended business output directory contains earlier evidence; use a fresh RoundDir'
    }
    foreach ($role in @('Observer', 'Target', 'Sender')) {
      $name = $role.ToLowerInvariant()
      $username = switch ($role) {
        'Observer' { 'E2EExtWatch' }
        'Target' { 'E2EExtTarget' }
        'Sender' { 'E2EExtSender' }
      }
      $planPath = Join-Path $work "plan-$name.json"
      $json = New-CrossServerPlan -role "extended-$name" -kind 'java' -username $username -port $Port `
        -mcVersion $McVersion -resultsFile (Join-Path $work "$name.results.json") -handoffDir $handoffs `
        -timeoutMs 360000 -runId $runId -steps $plans.$role
      [IO.File]::WriteAllText($planPath, $json, (New-Object Text.UTF8Encoding $false))
      $bots += [PSCustomObject]@{ Role = $name; Process = (Start-Bot "extended-$name" $planPath $work `
        (Join-Path $work "$name.stdout.log") (Join-Path $work "$name.stderr.log")) }
    }
    & $wait 'mute-ready' 150
    $muteAttempted = $true
    & $sendAdmin 'nc mute E2EExtSender 5m global'
    Start-Sleep -Milliseconds 1500
    Set-Handoff $handoffs $runId 'mute-applied' 'go'
    & $wait 'mute-scope-complete' 120
    Start-Sleep -Seconds 3
    & $sendAdmin 'nc unmute E2EExtSender global'
    $unmuteAttempted = $true
    Start-Sleep -Milliseconds 1500
    Set-Handoff $handoffs $runId 'mute-unmuted' 'go'
    & $wait 'extended-finished' 45
    Set-Handoff $handoffs $runId 'extended-release' 'go'
    Wait-Bots $bots 45
    foreach ($bot in $bots) {
      if (-not $bot.Process.HasExited -or $bot.Process.ExitCode -ne 0) { throw "extended $($bot.Role) exited unsuccessfully" }
    }
  } catch {
    $out += New-JointAssertion -Id "$prefix/drive" -Token 'private messaging + channel mute' -Pass $false `
      -Evidence $_.Exception.Message -Kind 'extended-business'
  } finally {
    if ($muteAttempted -and -not $unmuteAttempted) {
      try { & $sendAdmin 'nc unmute E2EExtSender global' } catch {
        $out += New-JointAssertion -Id "$prefix/cleanup" -Token 'unmute' -Pass $false `
          -Evidence $_.Exception.Message -Kind 'extended-business'
      }
    }
    foreach ($bot in $bots) {
      if (-not $bot.Process.HasExited) { Stop-Process -Id $bot.Process.Id -Force -ErrorAction SilentlyContinue }
    }
  }
  $sender = Read-BotResult (Join-Path $work 'sender.results.json')
  $target = Read-BotResult (Join-Path $work 'target.results.json')
  $observer = Read-BotResult (Join-Path $work 'observer.results.json')
  $out += Assert-ExtendedBusinessResults -RoundIndex $Round.Index -RunId $runId `
    -SenderRes $sender -TargetRes $target -ObserverRes $observer
  return $out
}
