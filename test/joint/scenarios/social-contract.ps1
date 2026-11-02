. (Join-Path $PSScriptRoot 'helpers.ps1')

function Get-SocialContractRequiredAssertionIds {
  param([int]$RoundIndex)
  foreach ($id in @(
    'drive', 'sender-completed', 'target-completed', 'observer-completed',
    'ignore-before-target', 'ignore-before-observer', 'ignore-receipt',
    'ignore-suppressed', 'ignore-observer-same-message', 'ignore-live-window',
    'unignore-receipt', 'unignore-target', 'unignore-observer',
    'who-joined-exact-set', 'who-joined-count', 'who-left-exact-set', 'who-left-count',
    'who-rejoined-exact-set', 'who-rejoined-count',
    'list-joined-exact-ids', 'list-left-exact-ids', 'list-rejoined-exact-ids', 'list-configured-scope'
  )) { "round$RoundIndex/social-contract/$id" }
}

function ConvertTo-SocialContractText {
  param([string]$Text)
  ([regex]::Replace($Text, '(?i)(?:\u00a7|&)[0-9a-fk-orx]', '') -replace '(?m)^\[NovaChat\]\s*', '').Trim()
}

function Get-SocialContractQuerySteps {
  param([string]$Phase)
  @(
    @{ action = 'checkpoint'; key = "social-who-$Phase" },
    @{ action = 'cmd'; text = '/nc who local' },
    @{ id = "social-who-$Phase-header"; action = 'expectText'; pattern = '(?:^|\s)Channel Local online members \([0-9]+\):(?:\n|$)'; sinceCheckpoint = "social-who-$Phase"; timeoutMs = 20000; required = $true },
    @{ id = "social-who-$Phase-body"; action = 'expectText'; pattern = '(?:^|\n)(?:\[NovaChat\]\s*)?[A-Za-z0-9_]+(?:,\s*[A-Za-z0-9_]+)+\s*$'; sinceCheckpoint = "social-who-$Phase"; timeoutMs = 20000; required = $true },
    @{ action = 'checkpoint'; key = "social-who-$Phase-done" },
    @{ action = 'checkpoint'; key = "social-list-$Phase" },
    @{ action = 'cmd'; text = '/nc list' },
    @{ id = "social-list-$Phase-tail"; action = 'expectText'; pattern = '^(?:\[NovaChat\]\s*)?={27}$'; sinceCheckpoint = "social-list-$Phase"; timeoutMs = 20000; required = $true },
    @{ action = 'checkpoint'; key = "social-list-$Phase-done" }
  )
}

function Get-JointSocialContractPlans {
  param([Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$RunId)

  $sha = [Security.Cryptography.SHA256]::Create()
  try { $suffix = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($RunId)))).Replace('-', '').Substring(0, 6) }
  finally { $sha.Dispose() }
  $names = @{ Sender = "E2ESocS$suffix"; Target = "E2ESocT$suffix"; Observer = "E2ESocW$suffix" }
  $tokens = @{ Before = "$RunId-before"; Blocked = "$RunId-blocked"; Live = "$RunId-live"; After = "$RunId-after" }
  $plans = @{ Names = $names; Tokens = $tokens; ObservationMs = 3000; CommandIntervalMs = 1200 }
  foreach ($role in @('Sender', 'Target', 'Observer')) {
    $lower = $role.ToLowerInvariant()
    $plans[$role] = @()
    foreach ($token in $tokens.Values) { $plans[$role] += @{ action = 'arm'; token = $token } }
    $plans[$role] += @(
      @{ action = 'checkpoint'; key = 'social-join-global' },
      @{ action = 'cmd'; text = '/nc join global' },
      @{ id = 'social-joined-global'; action = 'expectText'; pattern = '(?:^|\s)Joined channel global$'; sinceCheckpoint = 'social-join-global'; timeoutMs = 20000; required = $true },
      @{ action = 'signal'; key = "social-$lower-ready" }
    )
  }
  $plans.Sender += @(
    @{ action = 'handoffGet'; key = 'social-target-ready'; timeoutMs = 45000 },
    @{ action = 'handoffGet'; key = 'social-observer-ready'; timeoutMs = 45000 },
    @{ action = 'chat'; text = $tokens.Before },
    @{ action = 'handoffGet'; key = 'social-target-before'; timeoutMs = 30000 },
    @{ action = 'handoffGet'; key = 'social-observer-before'; timeoutMs = 30000 },
    @{ action = 'handoffGet'; key = 'social-target-block-armed'; timeoutMs = 30000 },
    @{ action = 'handoffGet'; key = 'social-observer-block-armed'; timeoutMs = 30000 },
    @{ action = 'chat'; text = $tokens.Blocked },
    @{ action = 'handoffGet'; key = 'social-observer-block-received'; timeoutMs = 30000 },
    @{ action = 'handoffGet'; key = 'social-target-window-complete'; timeoutMs = 30000 },
    @{ action = 'handoffGet'; key = 'social-target-unignored'; timeoutMs = 30000 },
    @{ action = 'handoffGet'; key = 'social-observer-after-armed'; timeoutMs = 30000 },
    @{ action = 'chat'; text = $tokens.After },
    @{ action = 'handoffGet'; key = 'social-target-after'; timeoutMs = 30000 },
    @{ action = 'handoffGet'; key = 'social-observer-after'; timeoutMs = 30000 },
    @{ action = 'signal'; key = 'social-chat-finished' }
  )
  $plans.Target += @(
    @{ id = 'social-before'; action = 'expect'; token = $tokens.Before; timeoutMs = 45000; required = $true },
    @{ action = 'signal'; key = 'social-target-before' },
    @{ action = 'checkpoint'; key = 'social-ignore' },
    @{ action = 'cmd'; text = "/nc ignore $($names.Sender)" },
    @{ id = 'social-ignore-receipt'; action = 'expectText'; pattern = ('(?:^|\s)Ignored player ' + $names.Sender + '$'); sinceCheckpoint = 'social-ignore'; timeoutMs = 20000; required = $true },
    @{ id = 'social-blocked-absent'; action = 'expectAbsent'; token = $tokens.Blocked },
    @{ action = 'checkpoint'; key = 'social-block-start' },
    @{ action = 'signal'; key = 'social-target-block-armed' },
    @{ action = 'handoffGet'; key = 'social-observer-block-received'; timeoutMs = 30000 },
    @{ id = 'social-live'; action = 'expect'; token = $tokens.Live; timeoutMs = 30000; required = $true },
    @{ action = 'wait'; ms = $plans.ObservationMs },
    @{ action = 'checkpoint'; key = 'social-block-end' },
    @{ action = 'signal'; key = 'social-target-window-complete' },
    @{ action = 'checkpoint'; key = 'social-unignore' },
    @{ action = 'cmd'; text = "/nc unignore $($names.Sender)" },
    @{ id = 'social-unignore-receipt'; action = 'expectText'; pattern = ('(?:^|\s)Unignored player ' + $names.Sender + '$'); sinceCheckpoint = 'social-unignore'; timeoutMs = 20000; required = $true },
    @{ action = 'signal'; key = 'social-target-unignored' },
    @{ id = 'social-after'; action = 'expect'; token = $tokens.After; timeoutMs = 30000; required = $true },
    @{ action = 'signal'; key = 'social-target-after' }
  )
  $plans.Observer += @(
    @{ id = 'social-before'; action = 'expect'; token = $tokens.Before; timeoutMs = 45000; required = $true },
    @{ action = 'signal'; key = 'social-observer-before' },
    @{ action = 'signal'; key = 'social-observer-block-armed' },
    @{ id = 'social-blocked'; action = 'expect'; token = $tokens.Blocked; timeoutMs = 30000; required = $true },
    @{ action = 'signal'; key = 'social-observer-block-received' },
    @{ action = 'chat'; text = $tokens.Live },
    @{ action = 'signal'; key = 'social-observer-after-armed' },
    @{ id = 'social-after'; action = 'expect'; token = $tokens.After; timeoutMs = 45000; required = $true },
    @{ action = 'signal'; key = 'social-observer-after' }
  )
  foreach ($role in @('Sender', 'Target', 'Observer')) {
    $lower = $role.ToLowerInvariant()
    $plans[$role] += @(
      @{ action = 'handoffGet'; key = 'social-chat-finished'; timeoutMs = 45000 },
      @{ action = 'checkpoint'; key = 'social-join-local' },
      @{ action = 'cmd'; text = '/nc join local' },
      @{ id = 'social-joined-local'; action = 'expectText'; pattern = '(?:^|\s)Joined channel local$'; sinceCheckpoint = 'social-join-local'; timeoutMs = 20000; required = $true },
      @{ action = 'signal'; key = "social-$lower-local-ready" }
    )
  }
  $plans.Target += @(
    @{ action = 'handoffGet'; key = 'social-sender-local-ready'; timeoutMs = 30000 },
    @{ action = 'handoffGet'; key = 'social-observer-local-ready'; timeoutMs = 30000 }
  )
  $plans.Target += Get-SocialContractQuerySteps -Phase 'joined'
  $plans.Target += @(
    @{ action = 'checkpoint'; key = 'social-leave' },
    @{ action = 'cmd'; text = '/nc leave local' },
    @{ id = 'social-left'; action = 'expectText'; pattern = '(?:^|\s)Left channel local, switched to default channel: global$'; sinceCheckpoint = 'social-leave'; timeoutMs = 20000; required = $true }
  )
  $plans.Target += Get-SocialContractQuerySteps -Phase 'left'
  $plans.Target += @(
    @{ action = 'checkpoint'; key = 'social-rejoin' },
    @{ action = 'cmd'; text = '/nc join local' },
    @{ id = 'social-rejoined'; action = 'expectText'; pattern = '(?:^|\s)Joined channel local$'; sinceCheckpoint = 'social-rejoin'; timeoutMs = 20000; required = $true }
  )
  $plans.Target += Get-SocialContractQuerySteps -Phase 'rejoined'
  $plans.Target += @(
    @{ action = 'signal'; key = 'social-target-finished' },
    @{ action = 'handoffGet'; key = 'social-sender-done'; timeoutMs = 30000 },
    @{ action = 'handoffGet'; key = 'social-observer-done'; timeoutMs = 30000 },
    @{ action = 'signal'; key = 'social-release'; value = 'go' }
  )
  foreach ($role in @('Sender', 'Observer')) {
    $plans[$role] += @(
      @{ action = 'handoffGet'; key = 'social-target-finished'; timeoutMs = 180000 },
      @{ action = 'signal'; key = "social-$($role.ToLowerInvariant())-done" }
    )
  }
  foreach ($role in @('Sender', 'Target', 'Observer')) {
    $plans[$role] += @(
      @{ action = 'handoffGet'; key = 'social-release'; timeoutMs = 30000 },
      @{ action = 'checkpoint'; key = 'social-complete' },
      @{ action = 'quit' }
    )
    $steps = @($plans[$role]); $paced = @(); $request = 0
    for ($i = 0; $i -lt $steps.Count; $i++) {
      $step = $steps[$i]
      if (($step.action -eq 'checkpoint' -and $i + 1 -lt $steps.Count -and $steps[$i + 1].action -eq 'cmd') -or $step.action -eq 'chat') {
        $paced += @{ action = 'wait'; ms = $plans.CommandIntervalMs }
      }
      if ($step.action -in @('cmd', 'chat')) {
        $request++
        $paced += @{ action = 'checkpoint'; key = "social-request-$request" }
      }
      $paced += $step
    }
    $plans[$role] = $paced
  }
  return $plans
}

function Get-SocialContractCheckpointMs {
  param($Result, [string]$Key)
  $rows = @(Get-JointBotRows -BotResult $Result -Kind 'checkpoint' | Where-Object { $_.key -ceq $Key })
  if ($rows.Count -eq 1) { return Get-JointBotRowAtMs $rows[0] }
  return $null
}

function Get-SocialContractLines {
  param($Result, [string]$StartKey, [string]$EndKey)
  $start = Get-SocialContractCheckpointMs $Result $StartKey
  $end = Get-SocialContractCheckpointMs $Result $EndKey
  if ($null -eq $start -or $null -eq $end -or $end -lt $start) { return @() }
  foreach ($row in @(Get-JointBotRows -BotResult $Result -Kind 'messagestr' -SinceMs $start -UntilMs $end)) {
    foreach ($line in ((ConvertTo-SocialContractText $row.text) -split '\r?\n')) {
      ConvertTo-SocialContractText $line
    }
  }
}

function Get-SocialContractChatRows {
  param($Result, [string]$Name, [string]$Token)
  $pattern = '^\[Global\] ' + [regex]::Escape($Name) + ': ' + [regex]::Escape($Token) + '$'
  @(Get-JointBotRows -BotResult $Result -Kind 'messagestr' -TextContains $Token | Where-Object {
    (ConvertTo-SocialContractText $_.text) -cmatch $pattern
  })
}

function Assert-JointSocialContractResults {
  param([int]$RoundIndex, [string]$RunId, $SenderRes, $TargetRes, $ObserverRes,
    [hashtable]$ChannelScopes = @{ global = 'GLOBAL'; local = 'GLOBAL' }, [string[]]$ForbiddenChannelIds = @())

  $plans = Get-JointSocialContractPlans -RunId $RunId
  $prefix = "round$RoundIndex/social-contract"
  $results = @{ Sender = $SenderRes; Target = $TargetRes; Observer = $ObserverRes }
  $healthy = @{}
  foreach ($role in @('Sender', 'Target', 'Observer')) {
    $res = $results[$role]
    $done = Get-SocialContractCheckpointMs $res 'social-complete'
    $failed = @(@($res.assertions) + @($res.absentChecks) | Where-Object { $_ -and $_.pass -ne $true })
    $sent = @($res.sent | Where-Object { $null -ne $_ })
    $expectedRequests = @($plans[$role] | Where-Object { $_.action -in @('cmd', 'chat') }).Count
    $paced = $sent.Count -eq $expectedRequests
    $previous = $null
    foreach ($request in $sent) {
      $at = Get-JointBotRowAtMs $request
      if ($null -eq $at -or ($null -ne $previous -and $at -lt ($previous + $plans.CommandIntervalMs))) { $paced = $false }
      $previous = $at
    }
    $healthy[$role] = (Test-JointBotRecording $res) -and $res.runId -ceq $RunId -and
      $res.username -ceq $plans.Names[$role] -and -not $res.error -and -not $res.timedOut -and
      $null -ne $done -and $res.handoff.'social-release' -ceq 'go' -and $failed.Count -eq 0 -and $paced
    New-JointAssertion -Id "$prefix/$($role.ToLowerInvariant())-completed" -Token $RunId -Pass $healthy[$role] `
      -Evidence "username=$($res.username); runId=$($res.runId); error=$($res.error); timedOut=$($res.timedOut); complete=$done; requests=$($sent.Count)/$expectedRequests; minimum interval=$($plans.CommandIntervalMs)ms; pacing=$paced; failed=[$($failed.id -join ', ')]" -Kind 'social-contract'
  }
  $beforeTarget = @(Get-SocialContractChatRows $TargetRes $plans.Names.Sender $plans.Tokens.Before)
  $beforeObserver = @(Get-SocialContractChatRows $ObserverRes $plans.Names.Sender $plans.Tokens.Before)
  $blockedObserver = @(Get-SocialContractChatRows $ObserverRes $plans.Names.Sender $plans.Tokens.Blocked)
  $afterTarget = @(Get-SocialContractChatRows $TargetRes $plans.Names.Sender $plans.Tokens.After)
  $afterObserver = @(Get-SocialContractChatRows $ObserverRes $plans.Names.Sender $plans.Tokens.After)
  foreach ($check in @(
    @{ Id = 'ignore-before-target'; Rows = $beforeTarget; Role = 'Target'; Token = $plans.Tokens.Before },
    @{ Id = 'ignore-before-observer'; Rows = $beforeObserver; Role = 'Observer'; Token = $plans.Tokens.Before },
    @{ Id = 'ignore-observer-same-message'; Rows = $blockedObserver; Role = 'Observer'; Token = $plans.Tokens.Blocked },
    @{ Id = 'unignore-target'; Rows = $afterTarget; Role = 'Target'; Token = $plans.Tokens.After },
    @{ Id = 'unignore-observer'; Rows = $afterObserver; Role = 'Observer'; Token = $plans.Tokens.After }
  )) {
    New-JointAssertion -Id "$prefix/$($check.Id)" -Token $check.Token -Pass ($healthy[$check.Role] -and $check.Rows.Count -gt 0) `
      -Evidence "receiver=$($check.Role); matching rendered chat rows=$($check.Rows.Count)" -Kind 'social-contract'
  }
  $ignoreStart = Get-SocialContractCheckpointMs $TargetRes 'social-ignore'
  $unignoreStart = Get-SocialContractCheckpointMs $TargetRes 'social-unignore'
  $start = Get-SocialContractCheckpointMs $TargetRes 'social-block-start'
  $end = Get-SocialContractCheckpointMs $TargetRes 'social-block-end'
  $ignore = @(Get-SocialContractLines $TargetRes 'social-ignore' 'social-block-start' | Where-Object { $_ -ceq "Ignored player $($plans.Names.Sender)" })
  $unignore = @(Get-SocialContractLines $TargetRes 'social-unignore' 'social-complete' | Where-Object { $_ -ceq "Unignored player $($plans.Names.Sender)" })
  foreach ($pair in @(@{ Id = 'ignore-receipt'; Rows = $ignore }, @{ Id = 'unignore-receipt'; Rows = $unignore })) {
    New-JointAssertion -Id "$prefix/$($pair.Id)" -Token $plans.Names.Sender -Pass ($healthy.Target -and $pair.Rows.Count -eq 1) `
      -Evidence "fresh exact receipt rows=$($pair.Rows.Count)" -Kind 'social-contract'
  }
  $liveRows = @(Get-SocialContractChatRows $TargetRes $plans.Names.Observer $plans.Tokens.Live)
  $live = @($liveRows | Where-Object { $at = Get-JointBotRowAtMs $_; $null -ne $at -and $null -ne $start -and $null -ne $end -and $at -ge $start -and $at -le ($end - $plans.ObservationMs) })
  $before = @($beforeTarget | Where-Object { $at = Get-JointBotRowAtMs $_; $null -ne $at -and $null -ne $ignoreStart -and $at -le $ignoreStart })
  $after = @($afterTarget | Where-Object { $at = Get-JointBotRowAtMs $_; $null -ne $at -and $null -ne $unignoreStart -and $at -ge $unignoreStart })
  $window = $healthy.Target -and $healthy.Observer -and $null -ne $start -and $null -ne $end -and
    $end -ge ($start + $plans.ObservationMs) -and $live.Count -gt 0 -and $before.Count -gt 0 -and
    $after.Count -gt 0 -and $ignore.Count -eq 1 -and $unignore.Count -eq 1 -and $unignoreStart -ge $end
  New-JointAssertion -Id "$prefix/ignore-live-window" -Token $plans.Tokens.Live -Pass $window `
    -Evidence "target window=$start..$end; minimum post-live observation=$($plans.ObservationMs)ms; live=$($live.Count); before=$($before.Count); after=$($after.Count)" -Kind 'social-contract'
  $absent = Find-JointBotVerdict $TargetRes 'social-blocked-absent'
  $leaked = @(Get-JointBotRows -BotResult $TargetRes -Kind 'messagestr' -TextContains $plans.Tokens.Blocked)
  New-JointAssertion -Id "$prefix/ignore-suppressed" -Token $plans.Tokens.Blocked `
    -Pass ($window -and $healthy.Sender -and $blockedObserver.Count -gt 0 -and $null -ne $absent -and $absent.pass -eq $true -and $absent.token -ceq $plans.Tokens.Blocked -and $leaked.Count -eq 0) `
    -Evidence "armed absence verdict=$($absent.pass); target matches=$($leaked.Count); same-message observer matches=$($blockedObserver.Count); live window=$window" -Kind 'social-contract'

  $scopePass = $ChannelScopes.ContainsKey('global') -and $ChannelScopes.ContainsKey('local') -and
    $ChannelScopes.global -ceq 'GLOBAL' -and $ChannelScopes.local -ceq 'GLOBAL'
  foreach ($id in $ChannelScopes.Keys) { if ($ChannelScopes[$id] -cnotin @('GLOBAL', 'SERVER')) { $scopePass = $false } }
  foreach ($id in $ForbiddenChannelIds) { if ($ChannelScopes.ContainsKey($id)) { $scopePass = $false } }
  foreach ($phase in @('joined', 'left', 'rejoined')) {
    $expected = @($plans.Names.Sender, $plans.Names.Observer)
    if ($phase -ne 'left') { $expected += $plans.Names.Target }
    $who = @(Get-SocialContractLines $TargetRes "social-who-$phase" "social-who-$phase-done")
    $headers = @($who | Where-Object { $_ -cmatch '^Channel Local online members \(([0-9]+)\):$' })
    $members = @(); $count = -1; $validBody = $false
    if ($headers.Count -eq 1) {
      $null = $headers[0] -cmatch '^Channel Local online members \(([0-9]+)\):$'
      $count = [int]$Matches[1]
      $headerIndex = [Array]::IndexOf($who, $headers[0])
      if ($headerIndex + 1 -lt $who.Count -and $who[$headerIndex + 1] -cmatch '^[A-Za-z0-9_]+(?:,\s*[A-Za-z0-9_]+)*$') {
        $members = @($who[$headerIndex + 1] -split ',\s*')
        $validBody = $true
      }
    }
    $exact = $healthy.Target -and $validBody -and $members.Count -eq $expected.Count
    foreach ($name in $expected) { if ($members -cnotcontains $name) { $exact = $false } }
    New-JointAssertion -Id "$prefix/who-$phase-exact-set" -Token '/nc who local' -Pass $exact `
      -Evidence "expected=[$($expected -join ', ')]; actual=[$($members -join ', ')]; headers=$($headers.Count)" -Kind 'social-contract'
    New-JointAssertion -Id "$prefix/who-$phase-count" -Token '/nc who local' `
      -Pass ($healthy.Target -and $validBody -and $headers.Count -eq 1 -and $count -eq $members.Count -and $count -eq $expected.Count) `
      -Evidence "reported=$count; parsed=$($members.Count); expected=$($expected.Count)" -Kind 'social-contract'

    $lines = @(Get-SocialContractLines $TargetRes "social-list-$phase" "social-list-$phase-done")
    $titles = @($lines | Where-Object { $_ -ceq '=== NovaChat Channels ===' })
    $tails = @($lines | Where-Object { $_ -cmatch '^={27}$' })
    $listed = @(); $markers = @{}; $parsed = $false
    if ($titles.Count -eq 1 -and $tails.Count -eq 1) {
      $first = [Array]::IndexOf($lines, $titles[0]); $last = [Array]::IndexOf($lines, $tails[0])
      $parsed = $last -gt $first
      for ($i = $first + 1; $i -lt $last; $i++) {
        if ($lines[$i] -cmatch '^([\u2713\u25cb])\s+(\S+)$') {
          $id = $Matches[2]; $listed += $id; $markers[$id] = $Matches[1] -ceq ([string][char]0x2713)
        } else { $parsed = $false }
      }
    }
    $listExact = $healthy.Target -and $parsed -and $listed.Count -eq $ChannelScopes.Count
    foreach ($id in $ChannelScopes.Keys) { if ($listed -cnotcontains $id) { $listExact = $false } }
    $joinedMarkers = $markers.ContainsKey('global') -and $markers.global -and $markers.ContainsKey('local') -and
      $markers.local -eq ($phase -ne 'left')
    New-JointAssertion -Id "$prefix/list-$phase-exact-ids" -Token '/nc list' -Pass ($listExact -and $joinedMarkers) `
      -Evidence "configured IDs=[$(($ChannelScopes.Keys | Sort-Object) -join ', ')]; actual=[$($listed -join ', ')]; global joined=$($markers.global); local joined=$($markers.local); phase=$phase" -Kind 'social-contract'
    $scopePass = $scopePass -and $listExact
    foreach ($id in $ForbiddenChannelIds) { if ($listed -ccontains $id) { $scopePass = $false } }
  }
  $scopes = @($ChannelScopes.Keys | Sort-Object | ForEach-Object { "$_=$($ChannelScopes[$_])" })
  New-JointAssertion -Id "$prefix/list-configured-scope" -Token 'configured visibility by full channel ID' -Pass $scopePass `
    -Evidence "visible configuration=[$($scopes -join ', ')]; forbidden IDs=[$($ForbiddenChannelIds -join ', ')]; list exposes IDs and join markers, no wire scope field" -Kind 'social-contract'
}

function Invoke-JointSocialContractScenarios {
  param($Round, [string]$RoundDir, [string]$McVersion, [int]$Port = 0,
    [hashtable]$ChannelScopes = @{ global = 'GLOBAL'; local = 'GLOBAL' }, [string[]]$ForbiddenChannelIds = @())

  $prefix = "round$($Round.Index)/social-contract"
  if ($Port -le 0) { $Port = $ProxyPort }
  if ($Round.Proxy -notin @('velocity', 'bungee') -or -not $Round.Java -or $Port -lt 1 -or $Port -gt 65535) {
    New-JointAssertion -Id "$prefix/setup" -Token 'proxy with Java downstream and valid proxy port' -Pass $false `
      -Evidence 'NOT EXECUTED: requires Velocity/Bungee, Java downstream, en_US and global/local channels; run after earlier scenario bots exit.' -Kind 'social-contract'
    return
  }
  $runId = "$($script:RunId)-r$($Round.Index)-social-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
  $work = Join-Path $RoundDir "bots/social-contract/$runId"
  $handoffs = Join-Path $work 'handoff'
  $plans = Get-JointSocialContractPlans -RunId $runId
  $bots = @(); $out = @(); $fresh = $false
  try {
    if (Test-Path -LiteralPath $work) { throw 'Social contract directory already exists; refusing earlier evidence.' }
    $fresh = $true
    New-Item -ItemType Directory -Path $handoffs -Force | Out-Null
    foreach ($role in @('Observer', 'Target', 'Sender')) {
      $lower = $role.ToLowerInvariant()
      $planPath = Join-Path $work "plan-$lower.json"
      $json = New-CrossServerPlan -role "social-$lower" -kind 'java' -username $plans.Names[$role] -port $Port `
        -mcVersion $McVersion -resultsFile (Join-Path $work "$lower.results.json") -handoffDir $handoffs `
        -timeoutMs 240000 -runId $runId -steps $plans[$role]
      [IO.File]::WriteAllText($planPath, $json, (New-Object Text.UTF8Encoding $false))
      $process = Start-Bot "social-$lower" $planPath $work (Join-Path $work "$lower.stdout.log") (Join-Path $work "$lower.stderr.log")
      if (-not $process) { throw "Social contract $lower process did not start." }
      $bots += [PSCustomObject]@{ Role = $lower; Process = $process }
    }
    Wait-Bots $bots 240
    foreach ($bot in $bots) {
      if (-not $bot.Process.HasExited -or $bot.Process.ExitCode -ne 0) { throw "Social contract $($bot.Role) exited unsuccessfully." }
    }
    $out += New-JointAssertion -Id "$prefix/drive" -Token $runId -Pass $true -Evidence 'All three bot processes exited successfully.' -Kind 'social-contract'
  } catch {
    $out += New-JointAssertion -Id "$prefix/drive" -Token $runId -Pass $false -Evidence $_.Exception.Message -Kind 'social-contract'
  } finally {
    foreach ($bot in $bots) {
      if (-not $bot.Process.HasExited) { Stop-Process -Id $bot.Process.Id -Force -ErrorAction SilentlyContinue }
    }
  }
  $results = @{}
  foreach ($role in @('Sender', 'Target', 'Observer')) {
    $results[$role] = $null
    if ($fresh) { $results[$role] = Read-BotResult (Join-Path $work "$($role.ToLowerInvariant()).results.json") }
  }
  $out += Assert-JointSocialContractResults -RoundIndex $Round.Index -RunId $runId -SenderRes $results.Sender `
    -TargetRes $results.Target -ObserverRes $results.Observer -ChannelScopes $ChannelScopes -ForbiddenChannelIds $ForbiddenChannelIds
  return $out
}
