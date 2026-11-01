. (Join-Path $PSScriptRoot 'recovery.ps1')

function Get-ProxySwitchRequiredAssertionIds {
  param([int]$RoundIndex)
  $prefix = "round$RoundIndex/proxy-switch"
  foreach ($name in @('mover', 'left', 'right')) {
    "$prefix/$name-completed"
    "$prefix/leg-$name"
  }
  foreach ($phase in @('pre', 'moved', 'return')) {
    foreach ($name in @('left', 'right', 'mover')) { "$prefix/$phase-to-$name" }
  }
  foreach ($phase in @('moved', 'return')) {
    "$prefix/leg-$phase"
    "$prefix/$phase-membership"
  }
  foreach ($name in @('owned-channel-bidirectional', 'wrong-link-denied', 'no-cross-link-secret', 'no-cross-link-reply')) {
    "$prefix/$name"
  }
}

function Get-JointProxySwitchFixture {
  param([string]$PrimaryServer, [string]$SecondaryServer, [string]$PrimaryIdentity, [string]$SecondaryIdentity)
  if (-not $PrimaryServer -or -not $SecondaryServer -or $PrimaryServer -eq $SecondaryServer -or
      -not $PrimaryIdentity -or -not $SecondaryIdentity -or $PrimaryIdentity -eq $SecondaryIdentity) {
    throw 'Proxy switch requires two different Java downstream names and two different backend identities.'
  }
  @{
    PrimaryServer = $PrimaryServer; SecondaryServer = $SecondaryServer
    Identities = @{ $PrimaryServer = $PrimaryIdentity; $SecondaryServer = $SecondaryIdentity }
    BackendClientChannels = @{ $SecondaryIdentity = @{ 'joint-switch-owned' = @{ display_name = 'Joint owned'; scope = 'SERVER'; permission = ''; max_capacity = 20 } } }
    Channels = @(
      @{ Id = 'global'; Scope = 'GLOBAL' },
      @{ Id = 'joint-switch-owned'; Scope = 'SERVER'; ClientId = $SecondaryIdentity }
    )
    Usernames = @{ Mover = 'E2ESwitchMover'; Left = 'E2ESwitchLeft'; Right = 'E2ESwitchRight' }
    Locale = 'en_US'; PlayerPermissions = @('novachat.use', 'novachat.channel.global')
    OptionalPlayerSwitchPermission = @{ velocity = 'velocity.command.server'; bungee = 'bungeecord.command.server' }
    RequiredCallbacks = @('SendLegProbe(server, username, token)')
    CallbackSwitchMode = 'SwitchPlayer(username, server); optional when bots use /server'
  }
}

function Get-JointProxyPlayerSwitchSteps {
  param([string]$Phase, [string]$Server)
  @(
    @{ action = 'handoffGet'; key = "switch-$Phase-command-go"; timeoutMs = 90000 },
    @{ action = 'checkpoint'; key = "switch-$Phase-command" },
    @{ action = 'cmd'; text = "/server $Server" },
    @{ id = "switch-$Phase-respawn"; action = 'expectEvent'; kind = 'respawn'; sinceCheckpoint = "switch-$Phase-command"; timeoutMs = 30000 },
    @{ action = 'signal'; key = "switch-$Phase-command-done" }
  )
}

function Invoke-JointProxyPlayerSwitch {
  param([string]$Mode, [string]$Phase, [string]$Username, [string]$Server,
    [string]$HandoffDir, [string]$RunId, [scriptblock]$Callback)
  if ($Mode -eq 'callback') {
    if (-not $Callback) { throw 'SwitchMode=callback requires SwitchPlayer(username, server).' }
    $null = & $Callback $Username $Server
  } else {
    Set-Handoff $HandoffDir $RunId "switch-$Phase-command-go" 'go'
    if (-not (Wait-Handoff $HandoffDir $RunId "switch-$Phase-command-done" 45)) { throw "Player switch $Phase did not finish." }
  }
}

function Get-JointProxySwitchPlans {
  param([string]$RunId, [string]$Channel = 'global', [string]$ScopedChannel = 'joint-switch-owned',
    [string]$PrimaryServer = 'primary', [string]$SecondaryServer = 'secondary',
    [ValidateSet('player-command', 'callback')][string]$SwitchMode = 'player-command')
  $plans = @{}
  foreach ($role in @('Mover', 'Left', 'Right')) {
    $lower = $role.ToLowerInvariant()
    $plans[$role] = @(
      @{ action = 'arm'; token = "$RunId-placed-$lower" },
      @{ action = 'arm'; token = "$RunId-pre" },
      @{ action = 'arm'; token = "$RunId-pre-reply" },
      @{ action = 'arm'; token = "$RunId-moved" },
      @{ action = 'arm'; token = "$RunId-moved-reply" },
      @{ action = 'arm'; token = "$RunId-return" },
      @{ action = 'arm'; token = "$RunId-return-reply" },
      @{ action = 'arm'; token = 'Joined channel' },
      @{ action = 'cmd'; text = "/nc join $Channel" },
      @{ id = 'switch-join'; action = 'expect'; token = 'Joined channel'; timeoutMs = 20000 },
      @{ action = 'signal'; key = "switch-$lower-ready" }
    )
    if ($role -eq 'Right' -and $SwitchMode -eq 'player-command') {
      $plans[$role] += Get-JointProxyPlayerSwitchSteps 'right' $SecondaryServer
    }
    $plans[$role] += @(
      @{ action = 'handoffGet'; key = 'switch-topology-ready'; timeoutMs = 90000 },
      @{ id = "switch-placed-$lower"; action = 'expect'; token = "$RunId-placed-$lower"; timeoutMs = 30000 },
      @{ action = 'signal'; key = "switch-$lower-placed" },
      @{ action = 'handoffGet'; key = 'switch-pre-go'; timeoutMs = 60000 }
    )
  }
  $plans.Mover += @(
    @{ action = 'arm'; token = "$RunId-leg-moved" },
    @{ action = 'arm'; token = "$RunId-leg-return" },
    @{ action = 'chat'; text = "$RunId-pre" },
    @{ id = 'switch-pre-reverse'; action = 'expect'; token = "$RunId-pre-reply"; timeoutMs = 30000 },
    @{ action = 'signal'; key = 'switch-pre-done' }
  )
  if ($SwitchMode -eq 'player-command') { $plans.Mover += Get-JointProxyPlayerSwitchSteps 'move' $SecondaryServer }
  $plans.Mover += @(
    @{ action = 'handoffGet'; key = 'switch-moved-go'; timeoutMs = 60000 },
    @{ action = 'checkpoint'; key = 'switch-moved' },
    @{ id = 'switch-leg-moved'; action = 'expect'; token = "$RunId-leg-moved"; timeoutMs = 30000 }
  )
  $plans.Mover += Get-JointRecoveryWhoSteps -Phase 'switch-moved-membership' -Channel $Channel
  $plans.Mover += @(
    @{ action = 'chat'; text = "$RunId-moved" },
    @{ id = 'switch-moved-reverse'; action = 'expect'; token = "$RunId-moved-reply"; timeoutMs = 30000 },
    @{ action = 'signal'; key = 'switch-moved-done' }
  )
  $plans.Left += @(
    @{ id = 'switch-pre-left'; action = 'expect'; token = "$RunId-pre"; timeoutMs = 30000 },
    @{ action = 'signal'; key = 'switch-left-pre-done' },
    @{ id = 'switch-moved-left'; action = 'expect'; token = "$RunId-moved"; timeoutMs = 90000 },
    @{ action = 'chat'; text = "$RunId-moved-reply" },
    @{ action = 'signal'; key = 'switch-left-moved-done' }
  )
  $plans.Right += @(
    @{ id = 'switch-pre-right'; action = 'expect'; token = "$RunId-pre"; timeoutMs = 30000 },
    @{ action = 'chat'; text = "$RunId-pre-reply" },
    @{ id = 'switch-moved-right'; action = 'expect'; token = "$RunId-moved"; timeoutMs = 90000 },
    @{ action = 'signal'; key = 'switch-right-moved-done' }
  )
  foreach ($role in @('Mover', 'Left', 'Right')) {
    $plans[$role] += @(
      @{ action = 'handoffGet'; key = 'switch-isolation-go'; timeoutMs = 60000 },
      @{ action = 'checkpoint'; key = 'switch-isolation' },
      @{ action = 'arm'; token = 'Joined channel'; reset = $true },
      @{ action = 'arm'; token = 'NC-403'; reset = $true },
      @{ action = 'arm'; token = "$RunId-scoped-secret" },
      @{ action = 'arm'; token = "$RunId-scoped-reply" },
      @{ action = 'cmd'; text = "/nc join $ScopedChannel" }
    )
    if ($role -eq 'Left') {
      $plans[$role] += @(
        @{ id = 'switch-wrong-link-denied'; action = 'expect'; token = 'NC-403'; timeoutMs = 20000 },
        @{ action = 'cmd'; text = "/nc join $Channel" },
        @{ id = 'switch-left-restored'; action = 'expect'; token = 'Joined channel'; timeoutMs = 20000 },
        @{ id = 'switch-secret-absent'; action = 'expectAbsent'; token = "$RunId-scoped-secret" },
        @{ id = 'switch-reply-absent'; action = 'expectAbsent'; token = "$RunId-scoped-reply" }
      )
    } else {
      $plans[$role] += @{ id = 'switch-scoped-joined'; action = 'expect'; token = 'Joined channel'; timeoutMs = 20000 }
    }
    $plans[$role] += @(
      @{ action = 'signal'; key = "switch-$($role.ToLowerInvariant())-isolated" },
      @{ action = 'handoffGet'; key = 'switch-scoped-go'; timeoutMs = 60000 }
    )
  }
  $plans.Right += @(
    @{ action = 'chat'; text = "$RunId-scoped-secret" },
    @{ id = 'switch-scoped-reply'; action = 'expect'; token = "$RunId-scoped-reply"; timeoutMs = 30000 },
    @{ action = 'signal'; key = 'switch-scoped-done' }
  )
  $plans.Mover += @(
    @{ id = 'switch-scoped-secret'; action = 'expect'; token = "$RunId-scoped-secret"; timeoutMs = 30000 },
    @{ action = 'chat'; text = "$RunId-scoped-reply" }
  )
  if ($SwitchMode -eq 'player-command') { $plans.Mover += Get-JointProxyPlayerSwitchSteps 'return' $PrimaryServer }
  foreach ($role in @('Mover', 'Left', 'Right')) {
    $plans[$role] += @(
      @{ action = 'handoffGet'; key = 'switch-return-go'; timeoutMs = 90000 },
      @{ action = 'checkpoint'; key = 'switch-return' },
      @{ action = 'arm'; token = 'Joined channel'; reset = $true }
    )
    if ($role -eq 'Mover') {
      $plans[$role] += @{ id = 'switch-leg-return'; action = 'expect'; token = "$RunId-leg-return"; timeoutMs = 30000 }
    }
    $plans[$role] += @(
      @{ action = 'cmd'; text = "/nc join $Channel" },
      @{ id = 'switch-return-joined'; action = 'expect'; token = 'Joined channel'; timeoutMs = 20000 },
      @{ action = 'signal'; key = "switch-$($role.ToLowerInvariant())-return-ready" },
      @{ action = 'handoffGet'; key = 'switch-return-chat-go'; timeoutMs = 60000 }
    )
  }
  $plans.Mover += Get-JointRecoveryWhoSteps -Phase 'switch-return-membership' -Channel $Channel
  $plans.Mover += @(
    @{ action = 'chat'; text = "$RunId-return" },
    @{ id = 'switch-return-reverse'; action = 'expect'; token = "$RunId-return-reply"; timeoutMs = 30000 },
    @{ action = 'signal'; key = 'switch-return-done' }
  )
  $plans.Left += @(
    @{ id = 'switch-return-left'; action = 'expect'; token = "$RunId-return"; timeoutMs = 30000 },
    @{ action = 'chat'; text = "$RunId-return-reply" }
  )
  $plans.Right += @(
    @{ id = 'switch-return-right'; action = 'expect'; token = "$RunId-return"; timeoutMs = 30000 },
    @{ action = 'signal'; key = 'switch-right-return-done' }
  )
  foreach ($role in @('Mover', 'Left', 'Right')) {
    $plans[$role] += @(
      @{ action = 'handoffGet'; key = 'switch-finish'; timeoutMs = 60000 },
      @{ action = 'quit' }
    )
  }
  return $plans
}

function Assert-JointProxySwitchResults {
  param([int]$RoundIndex, [string]$RunId, $MoverRes, $LeftRes, $RightRes, [string]$Channel = 'global', [string]$ScopedChannel = 'joint-switch-owned')
  $prefix = "round$RoundIndex/proxy-switch"
  foreach ($pair in @(@{ Name = 'mover'; Result = $MoverRes }, @{ Name = 'left'; Result = $LeftRes }, @{ Name = 'right'; Result = $RightRes })) {
    $res = $pair.Result
    $ok = $null -ne $res -and $res.runId -ceq $RunId -and $res.kind -ceq 'java' -and -not $res.error -and -not $res.timedOut -and (Test-JointBotRecording $res) -and @($res.assertions | Where-Object { -not $_.pass -and -not $_.diagnostic }).Count -eq 0
    New-JointAssertion "$prefix/$($pair.Name)-completed" 'recording' $ok "error=$($res.error); timedOut=$($res.timedOut)" 'proxy-switch'
    $ok = Test-JointRecoveryReceipt $res "$RunId-placed-$($pair.Name)"
    New-JointAssertion "$prefix/leg-$($pair.Name)" 'targeted downstream probe' $ok 'Destination bot must receive the tellraw issued only on its assigned Java leg.' 'proxy-switch'
  }
  foreach ($phase in @('pre', 'moved', 'return')) {
    foreach ($peer in @(@{ Name = 'left'; Result = $LeftRes }, @{ Name = 'right'; Result = $RightRes })) {
      $ok = Test-JointRecoveryReceipt $peer.Result "$RunId-$phase" -ExactlyOnce
      New-JointAssertion "$prefix/$phase-to-$($peer.Name)" "$RunId-$phase" $ok 'Exactly one receiver-side chat delivery on the anchored peer.' 'proxy-switch'
    }
    $boundary = if ($phase -eq 'pre') { '' } elseif ($phase -eq 'moved') { 'switch-moved' } else { 'switch-return' }
    $ok = Test-JointRecoveryReceipt $MoverRes "$RunId-$phase-reply" $boundary -ExactlyOnce
    New-JointAssertion "$prefix/$phase-to-mover" "$RunId-$phase-reply" $ok 'Exactly one reverse delivery on the mover after its local phase boundary.' 'proxy-switch'
  }
  foreach ($phase in @('moved', 'return')) {
    $ok = Test-JointRecoveryReceipt $MoverRes "$RunId-leg-$phase"
    New-JointAssertion "$prefix/leg-$phase" 'targeted downstream probe' $ok 'Probe must originate on the new Java leg; console dispatch is not delivery evidence.' 'proxy-switch'
    $ok = Test-JointRecoveryMembership $MoverRes "switch-$phase-membership" @('E2ESwitchMover', 'E2ESwitchLeft', 'E2ESwitchRight') $Channel
    New-JointAssertion "$prefix/$phase-membership" 'fresh /nc who' $ok 'All three members must appear in a fresh member list received on the mover.' 'proxy-switch'
  }
  $secret = Test-JointRecoveryReceipt $MoverRes "$RunId-scoped-secret" 'switch-isolation'
  $reply = Test-JointRecoveryReceipt $RightRes "$RunId-scoped-reply" 'switch-isolation'
  New-JointAssertion "$prefix/owned-channel-bidirectional" 'scoped chat' ($secret -and $reply) 'Two actual receivers on the owning backend identity.' 'proxy-switch'
  $denial = @()
  $isolation = Get-JointRecoveryCheckpoint $LeftRes 'switch-isolation'
  if ($null -ne $isolation) {
    $query = @($LeftRes.sent | Where-Object { $_.text -ceq "/nc join $ScopedChannel" -and $_.atMs -ge $isolation }) | Select-Object -First 1
    if ($query) {
      $denial = @(Get-JointRecoveryChatRows $LeftRes -SinceMs $query.atMs -UntilMs ($query.atMs + 20000) | Where-Object { ([string]$_.text).Contains('NC-403') })
    }
  }
  New-JointAssertion "$prefix/wrong-link-denied" 'NC-403' ($denial.Count -gt 0) 'The player on the other Java leg must receive a refusal for the SERVER channel.' 'proxy-switch'
  foreach ($name in @('secret', 'reply')) {
    $leak = Test-JointRecoveryReceipt $LeftRes "$RunId-scoped-$name"
    $control = Test-JointRecoveryReceipt $LeftRes "$RunId-return" 'switch-return'
    $ok = $secret -and $reply -and $control -and -not $leak -and $denial.Count -gt 0
    New-JointAssertion "$prefix/no-cross-link-$name" "$RunId-scoped-$name" $ok 'Absence requires real scoped deliveries, explicit cross-client denial, and a later positive delivery on the excluded receiver.' 'proxy-switch'
  }
}

function Invoke-JointProxySwitchScenarios {
  param([int]$RoundIndex, [string]$RunId, [string]$WorkDir, [int]$ProxyPort, [string]$McVersion,
    [string]$PrimaryServer, [string]$SecondaryServer, [string]$PrimaryIdentity, [string]$SecondaryIdentity,
    [scriptblock]$SwitchPlayer,
    [ValidateSet('player-command', 'callback')][string]$SwitchMode = 'player-command',
    [Parameter(Mandatory = $true)][scriptblock]$SendLegProbe,
    [string]$Channel = 'global', [string]$ScopedChannel = 'joint-switch-owned')
  $bots = @()
  try {
    $fixture = Get-JointProxySwitchFixture $PrimaryServer $SecondaryServer $PrimaryIdentity $SecondaryIdentity
    $clients = @{}
    foreach ($role in @('Mover', 'Left', 'Right')) { $clients[$role] = @{ Kind = 'java'; Username = $fixture.Usernames[$role]; Port = $ProxyPort; McVersion = $McVersion } }
    $plans = Get-JointProxySwitchPlans $RunId $Channel $ScopedChannel $PrimaryServer $SecondaryServer $SwitchMode
    $bots = @(Start-JointRecoveryBots $plans $clients $WorkDir $RunId 480000)
    $handoffs = Join-Path $WorkDir 'handoff'
    foreach ($role in @('mover', 'left', 'right')) {
      if (-not (Wait-Handoff $handoffs $RunId "switch-$role-ready" 60)) { throw "$role was not ready" }
    }
    Invoke-JointProxyPlayerSwitch $SwitchMode 'right' $fixture.Usernames.Right $SecondaryServer $handoffs $RunId $SwitchPlayer
    $null = & $SendLegProbe $PrimaryServer $fixture.Usernames.Mover "$RunId-placed-mover"
    $null = & $SendLegProbe $PrimaryServer $fixture.Usernames.Left "$RunId-placed-left"
    $null = & $SendLegProbe $SecondaryServer $fixture.Usernames.Right "$RunId-placed-right"
    Set-Handoff $handoffs $RunId 'switch-topology-ready' 'ready'
    foreach ($role in @('mover', 'left', 'right')) {
      if (-not (Wait-Handoff $handoffs $RunId "switch-$role-placed" 40)) { throw "$role did not receive its assigned Java leg probe" }
    }
    Set-Handoff $handoffs $RunId 'switch-pre-go' 'go'
    foreach ($key in @('switch-pre-done', 'switch-left-pre-done')) {
      if (-not (Wait-Handoff $handoffs $RunId $key 40)) { throw "Missing $key" }
    }
    Invoke-JointProxyPlayerSwitch $SwitchMode 'move' $fixture.Usernames.Mover $SecondaryServer $handoffs $RunId $SwitchPlayer
    $null = & $SendLegProbe $SecondaryServer $fixture.Usernames.Mover "$RunId-leg-moved"
    Set-Handoff $handoffs $RunId 'switch-moved-go' 'go'
    foreach ($key in @('switch-moved-done', 'switch-left-moved-done', 'switch-right-moved-done')) {
      if (-not (Wait-Handoff $handoffs $RunId $key 50)) { throw "Missing $key" }
    }
    Set-Handoff $handoffs $RunId 'switch-isolation-go' 'go'
    foreach ($role in @('mover', 'left', 'right')) {
      if (-not (Wait-Handoff $handoffs $RunId "switch-$role-isolated" 50)) { throw "$role did not enter the isolation phase" }
    }
    Set-Handoff $handoffs $RunId 'switch-scoped-go' 'go'
    if (-not (Wait-Handoff $handoffs $RunId 'switch-scoped-done' 40)) { throw 'Scoped bidirectional control did not finish.' }
    Invoke-JointProxyPlayerSwitch $SwitchMode 'return' $fixture.Usernames.Mover $PrimaryServer $handoffs $RunId $SwitchPlayer
    $null = & $SendLegProbe $PrimaryServer $fixture.Usernames.Mover "$RunId-leg-return"
    Set-Handoff $handoffs $RunId 'switch-return-go' 'go'
    foreach ($role in @('mover', 'left', 'right')) {
      if (-not (Wait-Handoff $handoffs $RunId "switch-$role-return-ready" 50)) { throw "$role did not return to the shared channel" }
    }
    Set-Handoff $handoffs $RunId 'switch-return-chat-go' 'go'
    foreach ($key in @('switch-return-done', 'switch-right-return-done')) {
      if (-not (Wait-Handoff $handoffs $RunId $key 50)) { throw "Missing $key" }
    }
    Set-Handoff $handoffs $RunId 'switch-finish' 'go'
    Wait-Bots $bots 30
    foreach ($bot in $bots) {
      if (-not $bot.Process.HasExited -or $bot.Process.ExitCode -ne 0) { throw "Switch bot $($bot.Role) did not finish successfully." }
    }
  } catch {
    New-JointAssertion "round$RoundIndex/proxy-switch/drive" 'two Java legs behind a real proxy' $false $_.Exception.Message 'proxy-switch'
  } finally { Stop-JointRecoveryBots $bots }
  $mover = Read-BotResult (Join-Path $WorkDir 'Mover.results.json')
  $left = Read-BotResult (Join-Path $WorkDir 'Left.results.json')
  $right = Read-BotResult (Join-Path $WorkDir 'Right.results.json')
  Assert-JointProxySwitchResults $RoundIndex $RunId $mover $left $right $Channel $ScopedChannel
}
