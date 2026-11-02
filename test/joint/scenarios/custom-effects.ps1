if (-not (Get-Command New-JointAssertion -ErrorAction SilentlyContinue)) {
  . (Join-Path $PSScriptRoot 'helpers.ps1')
}

function Get-CustomEffectsRequiredAssertionIds {
  param([int]$RoundIndex)
  foreach ($id in @('drive', 'sender-authenticated', 'sender-completed', 'target-completed', 'observer-completed',
    'target-title', 'target-subtitle', 'target-timings', 'target-absent-observer', 'target-absent-sender',
    'control-title', 'control-subtitle', 'control-timings', 'control-absent-target')) {
    "round$RoundIndex/custom-effects/$id"
  }
}

function Get-CustomEffectsPrerequisites {
  @{
    Mode = 'relay'; JavaPlatform = 'bukkit'; Locale = 'en_US'
    SenderName = 'E2EJointJava'; TargetName = 'E2ECustomTarget'; ObserverName = 'E2ECustomWatch'
    Channels = @('global', 'local'); ChannelScope = 'GLOBAL'
    SenderPermissions = @('novachat.auth', 'novachat.title', 'novachat.channel.global')
    SecretEnvironment = 'NOVALINK_JOINT_CUSTOM_EFFECTS_ADMIN_PASSWORD'
    SecretEnvironmentProcess = 'Primary Bukkit server at launch; not bots or handoff files'
    Authentication = 'Existing backend super-admin UUID for E2EJointJava; password = BackendState.AdminPassword'
    TitleApi = 'NovaChatBukkit.getNetworkClient().sendPacket(AdminActionPacket): STATUS, type=TITLE'
    TimingExtras = @{ fadeIn = '3'; stay = '37'; fadeOut = '9' }
    ControlTimingExtras = @{ fadeIn = '7'; stay = '43'; fadeOut = '11' }
    RequiredCallbacks = @('GrantSenderPermissions(username)', 'AuthenticateSender(username)', 'SendTitle(request)')
    OptionalCallbacks = @('ReleaseSender(username)')
    HarnessFunctions = @('New-CrossServerPlan', 'Start-Bot', 'Wait-Bots', 'Read-BotResult', 'Set-Handoff', 'Wait-Handoff')
  }
}

function Get-CustomEffectsPhases {
  param([Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]*$')][string]$RunId,
    [string]$Channel = 'global', [string]$ControlChannel = 'local')
  if ($Channel -eq $ControlChannel) { throw 'Custom effects requires two distinct channels.' }
  @{
    Id = 'target'; ChannelId = $Channel; Title = "$RunId-ce-target-title"; Subtitle = "$RunId-ce-target-subtitle"
    FadeIn = 3; Stay = 37; FadeOut = 9; Recipient = 'Target'
  }
  @{
    Id = 'control'; ChannelId = $ControlChannel; Title = "$RunId-ce-control-title"; Subtitle = "$RunId-ce-control-subtitle"
    FadeIn = 7; Stay = 43; FadeOut = 11; Recipient = 'Observer'
  }
}

function Get-CustomEffectsPlans {
  param([string]$RunId, [string]$Channel = 'global', [string]$ControlChannel = 'local',
    [ValidateRange(1500, 10000)][int]$CommandIntervalMs = 1500,
    [ValidateRange(2000, 10000)][int]$ObservationMs = 2000)
  $phases = @(Get-CustomEffectsPhases $RunId $Channel $ControlChannel)
  $plans = @{}
  $slot = 0
  foreach ($role in @('Sender', 'Target', 'Observer')) {
    $lower = $role.ToLowerInvariant()
    $ownChannel = if ($role -eq 'Target') { $Channel } else { $ControlChannel }
    $steps = @(@{ action = 'wait'; ms = (2500 + $slot * $CommandIntervalMs) })
    if ($role -eq 'Sender') {
      $steps += @(
        @{ action = 'checkpoint'; key = 'ce-auth-start' },
        @{ action = 'signal'; key = 'ce-sender-online' },
        @{ id = 'ce-auth-success'; action = 'expectText'; pattern = 'Super admin authentication successful';
          sinceCheckpoint = 'ce-auth-start'; timeoutMs = 30000; required = $true },
        @{ action = 'checkpoint'; key = 'ce-auth-end' },
        @{ action = 'signal'; key = 'ce-sender-authenticated' }
      )
    }
    $steps += @(
      @{ action = 'wait'; ms = $CommandIntervalMs },
      @{ action = 'checkpoint'; key = 'ce-join' },
      @{ action = 'cmd'; text = "/nc join $ownChannel" },
      @{ id = 'ce-joined'; action = 'expectText'; pattern = ('Joined channel ' + [regex]::Escape($ownChannel) + '(?:\s|$)');
        sinceCheckpoint = 'ce-join'; timeoutMs = 20000; required = $true }
    )
    foreach ($phase in $phases) {
      $id = $phase.Id
      $before = "$RunId-ce-$id-$lower-before"
      $after = "$RunId-ce-$id-$lower-after"
      $steps += @(
        @{ action = 'wait'; ms = $CommandIntervalMs },
        @{ action = 'checkpoint'; key = "ce-$id-start" },
        @{ action = 'cmd'; text = "/nc $ownChannel $before" },
        @{ id = "ce-$id-before"; action = 'expectText'; pattern = ([regex]::Escape($before) + '$');
          sinceCheckpoint = "ce-$id-start"; timeoutMs = 15000; required = $true },
        @{ action = 'signal'; key = "ce-$id-$lower-ready" },
        @{ action = 'handoffGet'; key = "ce-$id-issued"; timeoutMs = 45000 }
      )
      if ($role -eq $phase.Recipient) {
        $steps += @(
          @{ id = "ce-$id-title"; action = 'expectEvent'; kind = 'title'; match = "title: $($phase.Title)";
            sinceCheckpoint = "ce-$id-start"; timeoutMs = 10000 },
          @{ id = "ce-$id-subtitle"; action = 'expectEvent'; kind = 'title'; match = "subtitle: $($phase.Subtitle)";
            sinceCheckpoint = "ce-$id-start"; timeoutMs = 10000 },
          @{ id = "ce-$id-timings"; action = 'expectEvent'; kind = 'titleTimes';
            match = "fadeIn=$($phase.FadeIn) stay=$($phase.Stay) fadeOut=$($phase.FadeOut)";
            sinceCheckpoint = "ce-$id-start"; timeoutMs = 10000 },
          @{ action = 'signal'; key = "ce-$id-positive-recorded" }
        )
      }
      $steps += @(
        @{ action = 'handoffGet'; key = "ce-$id-observe"; timeoutMs = 45000 },
        @{ action = 'wait'; ms = ([math]::Max($CommandIntervalMs, $ObservationMs)) },
        @{ action = 'cmd'; text = "/nc $ownChannel $after" },
        @{ id = "ce-$id-after"; action = 'expectText'; pattern = ([regex]::Escape($after) + '$');
          sinceCheckpoint = "ce-$id-start"; timeoutMs = 15000; required = $true },
        @{ action = 'checkpoint'; key = "ce-$id-end" },
        @{ action = 'signal'; key = "ce-$id-$lower-done" }
      )
    }
    $steps += @(
      @{ action = 'handoffGet'; key = 'ce-release'; timeoutMs = 45000 },
      @{ action = 'checkpoint'; key = 'ce-completed' },
      @{ action = 'quit' }
    )
    $plans[$role] = $steps
    $slot++
  }
  return $plans
}

function Get-CustomEffectsWindow {
  param($Result, [string]$RunId, [string]$Role, [string]$Phase, [int]$ObservationMs = 2000)
  $rows = @($Result.received)
  $indices = @{}
  foreach ($key in @('start', 'before', 'after', 'end')) {
    $hits = @()
    for ($i = 0; $i -lt $rows.Count; $i++) {
      $row = $rows[$i]
      if ($key -in @('start', 'end')) {
        if ($row.kind -eq 'checkpoint' -and $row.key -ceq "ce-$Phase-$key") { $hits += $i }
      } elseif ($row.kind -eq 'messagestr' -and
        [string]$row.text -cmatch ([regex]::Escape("$RunId-ce-$Phase-$Role-$key") + '$')) { $hits += $i }
    }
    if ($hits.Count -ne 1) { return @{ Valid = $false; Rows = @(); Evidence = "$key anchor count=$($hits.Count)" } }
    $indices[$key] = $hits[0]
  }
  $valid = $indices.start -lt $indices.before -and $indices.before -lt $indices.after -and $indices.after -lt $indices.end
  $beforeMs = Get-JointBotRowAtMs $rows[$indices.before]
  $afterMs = Get-JointBotRowAtMs $rows[$indices.after]
  $elapsed = if ($null -ne $beforeMs -and $null -ne $afterMs) { $afterMs - $beforeMs } else { -1 }
  $valid = $valid -and $elapsed -ge $ObservationMs -and $elapsed -le 90000
  $window = @()
  if ($valid) {
    for ($i = $indices.before + 1; $i -lt $indices.after; $i++) {
      $row = $rows[$i]
      if ($row.kind -notin @('title', 'titleTimes', 'titleClear')) { continue }
      $at = Get-JointBotRowAtMs $row
      if ($null -eq $at -or $at -lt $beforeMs -or $at -gt $afterMs) { $valid = $false }
      $window += $row
    }
  }
  @{ Valid = [bool]$valid; Rows = $window; Evidence = "local markers ordered=$valid; elapsedMs=$elapsed; effects=$($window.Count)" }
}

function Test-CustomEffectsTitleTransaction {
  param($Window, $Phase)
  $titles = @($Window.Rows | Where-Object { $_.kind -eq 'title' -and $_.data.type -ceq 'title' -and $_.data.text -ceq $Phase.Title })
  $subtitles = @($Window.Rows | Where-Object { $_.kind -eq 'title' -and $_.data.type -ceq 'subtitle' -and $_.data.text -ceq $Phase.Subtitle })
  $times = @($Window.Rows | Where-Object { $_.kind -eq 'titleTimes' })
  $titleOk = $Window.Valid -and $titles.Count -eq 1
  $subtitleOk = $Window.Valid -and $subtitles.Count -eq 1
  $timingsOk = $titleOk -and $subtitleOk -and $times.Count -eq 1 -and $Window.Rows.Count -eq 3
  if ($timingsOk) {
    foreach ($name in @('fadeIn', 'stay', 'fadeOut')) {
      $value = $times[0].data.$name
      $timingsOk = $timingsOk -and ($value -is [int] -or $value -is [long] -or $value -is [double]) -and $value -eq $Phase[$name]
    }
  }
  @{ Title = [bool]$titleOk; Subtitle = [bool]$subtitleOk; Timings = [bool]$timingsOk
    Evidence = "$($Window.Evidence); title=$($titles.Count); subtitle=$($subtitles.Count); titleTimes=$($times.Count); expected ticks=$($Phase.FadeIn)/$($Phase.Stay)/$($Phase.FadeOut)" }
}

function Assert-CustomEffectsResults {
  param([int]$RoundIndex, [string]$RunId, $SenderRes, $TargetRes, $ObserverRes,
    [string]$Channel = 'global', [string]$ControlChannel = 'local', [int]$ObservationMs = 2000)
  $prefix = "round$RoundIndex/custom-effects"
  $results = @{ Sender = $SenderRes; Target = $TargetRes; Observer = $ObserverRes }
  $names = @{ Sender = 'E2EJointJava'; Target = 'E2ECustomTarget'; Observer = 'E2ECustomWatch' }
  $usable = @{}
  foreach ($role in @('Sender', 'Target', 'Observer')) {
    $res = $results[$role]
    $ownChannel = if ($role -eq 'Target') { $Channel } else { $ControlChannel }
    $ok = $null -ne $res -and $res.runId -ceq $RunId -and $res.username -ceq $names[$role] -and $res.kind -eq 'java'
    if ($ok) {
      $joined = Find-JointBotVerdict $res 'ce-joined'
      $ok = -not $res.error -and $res.timedOut -eq $false -and -not $res.state.kicked -and
        $res.handoff.'ce-release' -ceq 'go' -and $joined.pass -eq $true -and
        @($res.sent | Where-Object { $_.text -ceq "/nc join $ownChannel" }).Count -eq 1 -and
        @($res.received | Where-Object { $_.kind -eq 'checkpoint' -and $_.key -ceq 'ce-completed' }).Count -eq 1 -and
        @($res.assertions | Where-Object { $_.pass -ne $true }).Count -eq 0
      $joinCheckpoint = @($res.received | Where-Object { $_.kind -eq 'checkpoint' -and $_.key -ceq 'ce-join' })
      $phaseStart = @($res.received | Where-Object { $_.kind -eq 'checkpoint' -and $_.key -ceq 'ce-target-start' })
      $freshJoin = $false
      if ($joinCheckpoint.Count -eq 1 -and $phaseStart.Count -eq 1) {
        $rows = @($res.received)
        $start = [array]::IndexOf($rows, $joinCheckpoint[0]); $end = [array]::IndexOf($rows, $phaseStart[0])
        for ($i = $start + 1; $i -lt $end; $i++) {
          if ($rows[$i].kind -eq 'messagestr' -and [string]$rows[$i].text -cmatch
            ('Joined channel ' + [regex]::Escape($ownChannel) + '(?:\s|$)')) { $freshJoin = $true }
        }
      }
      $ok = $ok -and $freshJoin
    }
    $usable[$role] = [bool]$ok
    New-JointAssertion -Id "$prefix/$($role.ToLowerInvariant())-completed" -Token "$RunId/$role" -Pass $ok `
      -Evidence "same-run named Java recording, fresh join, final release and all bot verdicts=$ok" -Kind 'custom-effects'
  }
  $complete = $usable.Sender -and $usable.Target -and $usable.Observer
  $authRows = @($SenderRes.received)
  $authStart = @($authRows | Where-Object { $_.kind -eq 'checkpoint' -and $_.key -ceq 'ce-auth-start' })
  $authEnd = @($authRows | Where-Object { $_.kind -eq 'checkpoint' -and $_.key -ceq 'ce-auth-end' })
  $auth = $false
  if ($usable.Sender -and $authStart.Count -eq 1 -and $authEnd.Count -eq 1) {
    $start = [array]::IndexOf($authRows, $authStart[0]); $end = [array]::IndexOf($authRows, $authEnd[0])
    for ($i = $start + 1; $i -lt $end; $i++) {
      if ($authRows[$i].kind -eq 'messagestr' -and [string]$authRows[$i].text -cmatch 'Super admin authentication successful') { $auth = $true }
    }
    $auth = $auth -and (Find-JointBotVerdict $SenderRes 'ce-auth-success').pass -eq $true
  }
  New-JointAssertion -Id "$prefix/sender-authenticated" -Token 'production AUTH response' -Pass $auth `
    -Evidence "E2EJointJava received backend AUTH success between its local authentication checkpoints=$auth" -Kind 'custom-effects'
  $windows = @{}; $transactions = @{}
  $phases = @(Get-CustomEffectsPhases $RunId $Channel $ControlChannel)
  foreach ($phase in $phases) {
    foreach ($role in @('Sender', 'Target', 'Observer')) {
      $key = "$($phase.Id)/$role"
      $windows[$key] = Get-CustomEffectsWindow $results[$role] $RunId $role.ToLowerInvariant() $phase.Id $ObservationMs
      $transactions[$key] = Test-CustomEffectsTitleTransaction $windows[$key] $phase
    }
    $recipient = $phase.Recipient
    $transaction = $transactions["$($phase.Id)/$recipient"]
    foreach ($field in @('Title', 'Subtitle', 'Timings')) {
      $pass = $auth -and $usable[$recipient] -and $transaction[$field]
      New-JointAssertion -Id "$prefix/$($phase.Id)-$($field.ToLowerInvariant())" -Token $phase.Title -Pass $pass `
        -Evidence "$recipient receiver: $($transaction.Evidence); same-run completed=$($usable[$recipient]); authenticated=$auth" -Kind 'custom-effects'
    }
  }
  foreach ($negative in @(
    @{ Phase = 'target'; Role = 'Observer'; Control = 'control/Observer'; Positive = 'target/Target' },
    @{ Phase = 'target'; Role = 'Sender'; Control = 'control/Sender'; Positive = 'target/Target' },
    @{ Phase = 'control'; Role = 'Target'; Control = 'target/Target'; Positive = 'control/Observer' }
  )) {
    $key = "$($negative.Phase)/$($negative.Role)"
    $window = $windows[$key]
    $phase = @($phases | Where-Object { $_.Id -ceq $negative.Phase })[0]
    $leaks = @($results[$negative.Role].received | Where-Object {
      $_.kind -eq 'title' -and ($_.data.text -ceq $phase.Title -or $_.data.text -ceq $phase.Subtitle)
    })
    $pass = $auth -and $complete -and $window.Valid -and $window.Rows.Count -eq 0 -and $leaks.Count -eq 0 -and
      $transactions[$negative.Control].Timings -and $transactions[$negative.Positive].Timings
    New-JointAssertion -Id "$prefix/$($negative.Phase)-absent-$($negative.Role.ToLowerInvariant())" -Token $phase.Title -Pass $pass `
      -Evidence "$($negative.Role): $($window.Evidence); run-token leaks=$($leaks.Count); own positive control=$($transactions[$negative.Control].Timings); intended receiver=$($transactions[$negative.Positive].Timings)" -Kind 'custom-effects'
  }
}

function Invoke-CustomEffectsScenarios {
  param([int]$RoundIndex, [string]$RunId, [string]$WorkDir, [int]$JavaPort, [int]$ProxyPort,
    [string]$McVersion, [scriptblock]$GrantSenderPermissions, [scriptblock]$AuthenticateSender,
    [scriptblock]$SendTitle, [scriptblock]$ReleaseSender,
    [string]$Channel = 'global', [string]$ControlChannel = 'local',
    [ValidateRange(1500, 10000)][int]$CommandIntervalMs = 1500,
    [ValidateRange(2000, 10000)][int]$ObservationMs = 2000)
  $prefix = "round$RoundIndex/custom-effects"
  $bots = @(); $driveOk = $false; $evidence = ''; $work = $null; $senderPrepared = $false
  try {
    if (-not $GrantSenderPermissions -or -not $AuthenticateSender -or -not $SendTitle -or
      -not $WorkDir -or $JavaPort -le 0 -or $ProxyPort -le 0 -or -not $McVersion) {
      throw 'Requires relay, direct Bukkit JavaPort, ProxyPort, MC version, and GrantSenderPermissions/AuthenticateSender/SendTitle callbacks.'
    }
    $plans = Get-CustomEffectsPlans $RunId $Channel $ControlChannel $CommandIntervalMs $ObservationMs
    $work = Join-Path $WorkDir ([guid]::NewGuid().ToString('N'))
    $handoffs = Join-Path $work 'handoff'
    $null = New-Item -ItemType Directory -Path $handoffs -Force
    foreach ($role in @('Sender', 'Target', 'Observer')) {
      $lower = $role.ToLowerInvariant()
      $name = switch ($role) { 'Sender' { 'E2EJointJava' }; 'Target' { 'E2ECustomTarget' }; 'Observer' { 'E2ECustomWatch' } }
      $port = if ($role -eq 'Sender') { $JavaPort } else { $ProxyPort }
      $planPath = Join-Path $work "plan-$lower.json"
      $json = New-CrossServerPlan -role "custom-effects-$lower" -kind 'java' -username $name -port $port -mcVersion $McVersion `
        -resultsFile (Join-Path $work "$lower.results.json") -handoffDir $handoffs -timeoutMs 240000 -runId $RunId -steps $plans[$role]
      [IO.File]::WriteAllText($planPath, $json, (New-Object Text.UTF8Encoding $false))
      $bots += [PSCustomObject]@{ Role = $lower; Process = (Start-Bot "custom-effects-$lower" $planPath $work `
        (Join-Path $work "$lower.stdout.log") (Join-Path $work "$lower.stderr.log")) }
    }
    if (-not (Wait-Handoff $handoffs $RunId 'ce-sender-online' 35)) { throw 'Sender did not open its local authentication window.' }
    $senderPrepared = $true
    $null = & $GrantSenderPermissions 'E2EJointJava'
    $null = & $AuthenticateSender 'E2EJointJava'
    if (-not (Wait-Handoff $handoffs $RunId 'ce-sender-authenticated' 35)) { throw 'Sender did not receive production backend AUTH success.' }
    foreach ($phase in @(Get-CustomEffectsPhases $RunId $Channel $ControlChannel)) {
      $id = $phase.Id
      foreach ($role in @('sender', 'target', 'observer')) {
        if (-not (Wait-Handoff $handoffs $RunId "ce-$id-$role-ready" 35)) { throw "$id/${role}: no receiver-local before marker." }
      }
      $request = [PSCustomObject]@{
        RunId = $RunId; Phase = $id; RequestId = [guid]::NewGuid().ToString(); SenderName = 'E2EJointJava'
        ChannelId = $phase.ChannelId; Title = $phase.Title; Subtitle = $phase.Subtitle
        FadeIn = $phase.FadeIn; Stay = $phase.Stay; FadeOut = $phase.FadeOut
      }
      [IO.File]::WriteAllText((Join-Path $work "request-$id.json"), ($request | ConvertTo-Json), (New-Object Text.UTF8Encoding $false))
      $null = & $SendTitle $request
      Set-Handoff $handoffs $RunId "ce-$id-issued" 'go'
      if (-not (Wait-Handoff $handoffs $RunId "ce-$id-positive-recorded" 35)) { throw "${id}: intended receiver did not finish effect observations." }
      Set-Handoff $handoffs $RunId "ce-$id-observe" 'go'
      foreach ($role in @('sender', 'target', 'observer')) {
        if (-not (Wait-Handoff $handoffs $RunId "ce-$id-$role-done" 25)) { throw "$id/${role}: no receiver-local after marker." }
      }
    }
    if ($ReleaseSender) {
      $null = & $ReleaseSender 'E2EJointJava'
      $senderPrepared = $false
    }
    Set-Handoff $handoffs $RunId 'ce-release' 'go'
    Wait-Bots $bots 15
    foreach ($bot in $bots) {
      if (-not $bot.Process.HasExited -or $bot.Process.ExitCode -ne 0) { throw "$($bot.Role) bot process did not complete successfully." }
    }
    $driveOk = $true
    $evidence = "Three bot processes completed two production title API requests; receiver assertions below determine delivery. Artifacts: $work"
  } catch {
    $evidence = $_.Exception.Message
  } finally {
    if ($ReleaseSender -and $senderPrepared) {
      try { $null = & $ReleaseSender 'E2EJointJava' } catch { $driveOk = $false; $evidence += '; sender release failed' }
    }
    foreach ($bot in $bots) {
      if (-not $bot.Process.HasExited) { Stop-Process -Id $bot.Process.Id -Force -ErrorAction SilentlyContinue }
    }
  }
  New-JointAssertion -Id "$prefix/drive" -Token $RunId -Pass $driveOk -Evidence $evidence -Kind 'custom-effects'
  $recordings = @{ Sender = $null; Target = $null; Observer = $null }
  if ($work) {
    foreach ($role in @('Sender', 'Target', 'Observer')) {
      $recordings[$role] = Read-BotResult (Join-Path $work "$($role.ToLowerInvariant()).results.json")
    }
  }
  Assert-CustomEffectsResults -RoundIndex $RoundIndex -RunId $RunId -SenderRes $recordings.Sender `
    -TargetRes $recordings.Target -ObserverRes $recordings.Observer -Channel $Channel -ControlChannel $ControlChannel -ObservationMs $ObservationMs
}

function Get-CustomEffectsBukkitBridge {
  $fixture = Join-Path $PSScriptRoot '../fixtures/custom-effects'
  @{
    SourceName = 'JointCustomEffectsBridge.java'
    Source = [IO.File]::ReadAllText((Join-Path $fixture 'src/com/nova/joint/fixture/JointCustomEffectsBridge.java'))
    PluginYml = [IO.File]::ReadAllText((Join-Path $fixture 'plugin.yml'))
  }
}

function New-CustomEffectsRconCallbacks {
  param([int]$RconPort, [string]$RconPassword)
  if ($RconPort -le 0 -or -not $RconPassword) { throw 'Custom effects requires primary Bukkit RCON credentials.' }
  $send = {
    param([string]$Operation, [string]$Arguments)
    $response = Send-Rcon -Port $RconPort -Password $RconPassword -Command "jointcustomeffects $Operation $Arguments"
    if (-not $response -or -not $response.Contains("JointCustomEffectsBridge queued $Operation")) {
      throw "Custom effects bridge rejected $Operation; check primary Bukkit bridge, permissions and launch environment."
    }
  }.GetNewClosure()
  @{
    GrantSenderPermissions = { param([string]$Username) & $send 'grant' $Username }.GetNewClosure()
    AuthenticateSender = { param([string]$Username) & $send 'auth' $Username }.GetNewClosure()
    SendTitle = {
      param($Request)
      $parts = @($Request.SenderName, $Request.RequestId, $Request.ChannelId, $Request.Title, $Request.Subtitle,
        $Request.FadeIn, $Request.Stay, $Request.FadeOut)
      foreach ($part in $parts) {
        if ([string]$part -cnotmatch '^[A-Za-z0-9_.-]+$') { throw 'Custom effects bridge arguments must be single safe tokens.' }
      }
      & $send 'title' ($parts -join ' ')
    }.GetNewClosure()
    ReleaseSender = { param([string]$Username) & $send 'release' $Username }.GetNewClosure()
  }
}
