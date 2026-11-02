. (Join-Path $PSScriptRoot 'helpers.ps1')

function Get-ChannelLifecycleRequiredAssertionIds {
  param([int]$RoundIndex)
  foreach ($name in @(
    'distinct-server-ports', 'owner-completed', 'invitee-completed', 'observer-completed',
    'before-positive-control', 'created-unique-id', 'owner-initial-membership', 'invite-code-exported',
    'accepted-on-secondary', 'owner-membership', 'invitee-membership', 'owner-to-invitee',
    'invitee-to-owner', 'outsider-refused', 'outsider-isolated', 'owner-left', 'invitee-left',
    'membership-cleared', 'after-positive-control', 'channel-deleted'
  )) { "round$RoundIndex/channel-lifecycle/$name" }
}

function Get-JointChannelLifecycleContext {
  param([Parameter(Mandatory = $true)][string]$RunId)
  $sha = [Security.Cryptography.SHA256]::Create()
  try { $digest = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($RunId))).Replace('-', '') }
  finally { $sha.Dispose() }
  @{
    Name = 'CL' + $digest.Substring(0, 12)
    Password = $digest.Substring(12, 12)
    Owner = 'E2EChOwner'; Invitee = 'E2EChInvitee'; Observer = 'E2EChWatch'
    CreatePattern = '(?:^|\s)Private channel id: (NC-[0-9A-F]{4}), password: ' + $digest.Substring(12, 12) + '$'
    InvitePattern = '(?:^|\s)Invite code: ([A-Z0-9]{6}) \(have them run /nc accept \1\)$'
  }
}

function Get-JointChannelLifecycleCommandSteps {
  param([string]$Phase, [string]$Command, [string]$Pattern)
  @(
    @{ action = 'wait'; ms = 2000 },
    @{ action = 'checkpoint'; key = $Phase },
    @{ action = 'cmd'; text = $Command },
    @{ id = $Phase; action = 'expectText'; pattern = $Pattern; sinceCheckpoint = $Phase; timeoutMs = 20000; required = $true },
    @{ action = 'checkpoint'; key = "${Phase}_done" }
  )
}

function Get-JointChannelLifecycleWhoSteps {
  param([string]$Phase, [string]$Name, [string[]]$Members)
  $body = if ($Members.Count) { [regex]::Escape(($Members | Sort-Object) -join ', ') } else { 'No members online' }
  @(
    @{ action = 'wait'; ms = 2000 },
    @{ action = 'checkpoint'; key = $Phase },
    @{ action = 'cmd'; text = '/nc who {{channel_id}}' },
    @{ id = "$Phase-header"; action = 'expectText'; pattern = ('(?:^|\s)Channel ' + [regex]::Escape($Name) + ' online members \(' + $Members.Count + '\):$'); sinceCheckpoint = $Phase; timeoutMs = 20000; required = $true },
    @{ id = "$Phase-body"; action = 'expectText'; pattern = ('^(?:\[NovaChat\]\s*)?' + $body + '$'); sinceCheckpoint = $Phase; timeoutMs = 20000; required = $true },
    @{ action = 'checkpoint'; key = "${Phase}_done" }
  )
}

function Get-JointChannelLifecyclePlans {
  param([Parameter(Mandatory = $true)][string]$RunId)
  $c = Get-JointChannelLifecycleContext $RunId
  $plans = @{}
  foreach ($role in @('Owner', 'Invitee', 'Observer')) {
    $plans[$role] = @(Get-JointChannelLifecycleCommandSteps 'join_global' '/nc join global' '(?:^|\s)Joined channel global$') + @(
      @{ action = 'arm'; token = "$RunId-before" },
      @{ action = 'arm'; token = "$RunId-after" },
      @{ action = 'arm'; token = "$RunId-owner-private" },
      @{ action = 'arm'; token = "$RunId-invitee-private" },
      @{ action = 'checkpoint'; key = 'before' },
      @{ action = 'signal'; key = ($role.ToLowerInvariant() + '_ready') }
    )
    if ($role -eq 'Observer') {
      $plans[$role] += @(
        @{ id = 'no-owner-private'; action = 'expectAbsent'; token = "$RunId-owner-private" },
        @{ id = 'no-invitee-private'; action = 'expectAbsent'; token = "$RunId-invitee-private" },
        @{ action = 'handoffGet'; key = 'owner_ready'; timeoutMs = 60000 },
        @{ action = 'handoffGet'; key = 'invitee_ready'; timeoutMs = 60000 },
        @{ action = 'chat'; text = "$RunId-before" }
      )
    }
    $plans[$role] += @(
      @{ id = 'before-control'; action = 'expect'; token = "$RunId-before"; timeoutMs = 30000; required = $true },
      @{ action = 'checkpoint'; key = 'before_done' }
    )
  }
  $plans.Owner += @(Get-JointChannelLifecycleCommandSteps 'create' "/nc create $($c.Name) $($c.Password)" $c.CreatePattern) + @(
    @{ action = 'handoffSet'; key = 'channel_id'; pattern = ('Private channel id: (?:\u00a7[0-9a-fk-or])*(NC-[0-9A-F]{4})(?:\u00a7[0-9a-fk-or])*, password: (?:\u00a7[0-9a-fk-or])*' + $c.Password) }
  ) + @(Get-JointChannelLifecycleWhoSteps 'initial_membership' $c.Name @($c.Owner)) + @(
    @{ action = 'checkpoint'; key = 'private_receive' }
  ) + @(Get-JointChannelLifecycleCommandSteps 'invite' "/nc invite $($c.Invitee) {{channel_id}}" $c.InvitePattern) + @(
    @{ action = 'handoffSet'; key = 'invite_code'; pattern = 'Invite code: (?:\u00a7[0-9a-fk-or])*([A-Z0-9]{6})(?:\u00a7[0-9a-fk-or])* \(have them run /nc accept [A-Z0-9]{6}\)' },
    @{ action = 'handoffGet'; key = 'invitee_accepted'; timeoutMs = 45000 },
    @{ action = 'handoffGet'; key = 'observer_window'; timeoutMs = 45000 }
  ) + @(Get-JointChannelLifecycleWhoSteps 'membership' $c.Name @($c.Owner, $c.Invitee)) + @(
    @{ action = 'chat'; text = "$RunId-owner-private" },
    @{ id = 'from-invitee'; action = 'expect'; token = "$RunId-invitee-private"; timeoutMs = 30000; required = $true },
    @{ action = 'checkpoint'; key = 'private_receive_done' },
    @{ action = 'signal'; key = 'private_done' }
  ) + @(Get-JointChannelLifecycleCommandSteps 'leave' '/nc leave {{channel_id}}' '(?:^|\s)Left channel {{channel_id}}, switched to default channel: global$') + @(
    @{ action = 'handoffGet'; key = 'invitee_left'; timeoutMs = 45000 }
  ) + @(Get-JointChannelLifecycleWhoSteps 'empty_membership' $c.Name @()) +
    @(Get-JointChannelLifecycleCommandSteps 'restore_global' '/nc join global' '(?:^|\s)Joined channel global$') + @(
    @{ action = 'checkpoint'; key = 'after' },
    @{ action = 'signal'; key = 'owner_left' },
    @{ id = 'after-control'; action = 'expect'; token = "$RunId-after"; timeoutMs = 30000; required = $true },
    @{ action = 'checkpoint'; key = 'after_done' },
    @{ action = 'signal'; key = 'cleanup_ready' },
    @{ action = 'handoffGet'; key = 'channel_deleted'; timeoutMs = 45000 }
  ) + @(Get-JointChannelLifecycleCommandSteps 'deleted_join' '/nc join {{channel_id}}' '(?:^|[\s\[])NC-404(?:\]|\b)') + @(
    @{ action = 'signal'; key = 'owner_done' }
  )
  $plans.Invitee += @(
    @{ action = 'handoffGet'; key = 'channel_id'; timeoutMs = 60000 },
    @{ action = 'handoffGet'; key = 'invite_code'; timeoutMs = 60000 },
    @{ action = 'checkpoint'; key = 'private_receive' }
  ) + @(Get-JointChannelLifecycleCommandSteps 'accept' '/nc accept {{invite_code}}' '(?:^|\s)Joined channel {{channel_id}} via invite$') + @(
    @{ action = 'signal'; key = 'invitee_accepted' }
  ) + @(Get-JointChannelLifecycleWhoSteps 'membership' $c.Name @($c.Owner, $c.Invitee)) + @(
    @{ id = 'from-owner'; action = 'expect'; token = "$RunId-owner-private"; timeoutMs = 30000; required = $true },
    @{ action = 'chat'; text = "$RunId-invitee-private" },
    @{ action = 'handoffGet'; key = 'private_done'; timeoutMs = 30000 },
    @{ action = 'checkpoint'; key = 'private_receive_done' }
  ) + @(Get-JointChannelLifecycleCommandSteps 'leave' '/nc leave {{channel_id}}' '(?:^|\s)Left channel {{channel_id}}, switched to default channel: global$') +
    @(Get-JointChannelLifecycleCommandSteps 'restore_global' '/nc join global' '(?:^|\s)Joined channel global$') + @(
    @{ action = 'checkpoint'; key = 'after' },
    @{ action = 'signal'; key = 'invitee_left' },
    @{ id = 'after-control'; action = 'expect'; token = "$RunId-after"; timeoutMs = 30000; required = $true },
    @{ action = 'checkpoint'; key = 'after_done' },
    @{ action = 'signal'; key = 'invitee_done' }
  )
  $plans.Observer += @(
    @{ action = 'handoffGet'; key = 'channel_id'; timeoutMs = 60000 }
  ) + @(Get-JointChannelLifecycleCommandSteps 'refused_join' '/nc join {{channel_id}}' '(?:^|[\s\[])NC-434(?:\]|\b)') + @(
    @{ action = 'checkpoint'; key = 'private_receive' },
    @{ action = 'signal'; key = 'observer_window' },
    @{ action = 'handoffGet'; key = 'private_done'; timeoutMs = 60000 },
    @{ action = 'wait'; ms = 1500 },
    @{ action = 'checkpoint'; key = 'private_receive_done' },
    @{ action = 'handoffGet'; key = 'owner_left'; timeoutMs = 45000 },
    @{ action = 'handoffGet'; key = 'invitee_left'; timeoutMs = 45000 },
    @{ action = 'checkpoint'; key = 'after' },
    @{ action = 'chat'; text = "$RunId-after" },
    @{ id = 'after-control'; action = 'expect'; token = "$RunId-after"; timeoutMs = 30000; required = $true },
    @{ action = 'checkpoint'; key = 'after_done' },
    @{ action = 'signal'; key = 'observer_done' }
  )
  foreach ($role in @('Owner', 'Invitee', 'Observer')) {
    $plans[$role] += @(
      @{ action = 'handoffGet'; key = 'lifecycle_release'; timeoutMs = 60000 },
      @{ action = 'checkpoint'; key = 'completed' },
      @{ action = 'quit' }
    )
    $paced = @()
    foreach ($step in $plans[$role]) {
      if ($step.action -eq 'chat') { $paced += @{ action = 'wait'; ms = 2000 } }
      $paced += $step
    }
    $plans[$role] = $paced
  }
  $plans
}

function ConvertTo-JointChannelLifecycleText {
  param([string]$Text)
  ([regex]::Replace($Text, '(?i)(?:\u00a7|&)[0-9a-fk-orx]', '') -replace '^\s*\[NovaChat\]\s*', '').Trim()
}

function Get-JointChannelLifecycleChatRows {
  param($Result)
  @(Get-JointBotRows $Result -Kind 'messagestr')
}

function Get-JointChannelLifecyclePhase {
  param($Result, [string]$Phase)
  $rows = @($Result.received)
  if ($rows.Count -lt 3) { return $null }
  $starts = @(0..($rows.Count - 1) | Where-Object { $rows[$_].kind -ceq 'checkpoint' -and $rows[$_].key -ceq $Phase })
  $ends = @(0..($rows.Count - 1) | Where-Object { $rows[$_].kind -ceq 'checkpoint' -and $rows[$_].key -ceq "${Phase}_done" })
  if ($starts.Count -ne 1 -or $ends.Count -ne 1 -or $ends[0] -le $starts[0]) { return $null }
  $startAt = Get-JointBotRowAtMs $rows[$starts[0]]
  $endAt = Get-JointBotRowAtMs $rows[$ends[0]]
  if ($null -eq $startAt -or $null -eq $endAt -or $endAt -lt $startAt) { return $null }
  $chatRows = @()
  if ($ends[0] -gt ($starts[0] + 1)) {
    $chatRows = @($rows[($starts[0] + 1)..($ends[0] - 1)] | Where-Object { $_.kind -ceq 'messagestr' })
  }
  @{ Rows = $chatRows; Start = $startAt; End = $endAt }
}

function Get-JointChannelLifecycleReceipt {
  param($Result, [string]$Phase, [string]$Command, [string]$Pattern)
  $window = Get-JointChannelLifecyclePhase $Result $Phase
  if (-not $window) { return $null }
  $sent = @($Result.sent | Where-Object { $_.text -ceq $Command -and $null -ne $_.atMs -and $_.atMs -ge $window.Start -and $_.atMs -le $window.End })
  if ($sent.Count -ne 1) { return $null }
  $hits = @($window.Rows | Where-Object {
    $at = Get-JointBotRowAtMs $_
    $null -ne $at -and $at -ge $sent[0].atMs -and $at -le $window.End -and
      [regex]::IsMatch((ConvertTo-JointChannelLifecycleText $_.text), $Pattern)
  })
  if ($hits.Count -eq 1) { return $hits[0] }
  return $null
}

function Test-JointChannelLifecycleMembership {
  param($Result, [string]$Phase, [string]$ChannelId, [string]$Name, [string[]]$Members)
  if ($ChannelId -cnotmatch '^NC-[0-9A-F]{4}$') { return $false }
  $header = '^Channel ' + [regex]::Escape($Name) + ' online members \(' + $Members.Count + '\):$'
  $body = if ($Members.Count) { '^' + [regex]::Escape(($Members | Sort-Object) -join ', ') + '$' } else { '^No members online$' }
  $h = Get-JointChannelLifecycleReceipt $Result $Phase "/nc who $ChannelId" $header
  $b = Get-JointChannelLifecycleReceipt $Result $Phase "/nc who $ChannelId" $body
  if (-not $h -or -not $b) { return $false }
  foreach ($pattern in @($header, $body)) {
    if (@(Get-JointChannelLifecycleChatRows $Result | Where-Object {
      [regex]::IsMatch((ConvertTo-JointChannelLifecycleText $_.text), $pattern)
    }).Count -ne 1) { return $false }
  }
  $window = Get-JointChannelLifecyclePhase $Result $Phase
  $lines = @($window.Rows)
  $headerIndex = [array]::IndexOf($lines, $h)
  $bodyIndex = [array]::IndexOf($lines, $b)
  return $bodyIndex -eq ($headerIndex + 1)
}

function Test-JointChannelLifecycleDelivery {
  param($Sender, $Receiver, [string]$SenderName, [string]$Token, [string]$Phase)
  $window = Get-JointChannelLifecyclePhase $Receiver $Phase
  $senderWindow = Get-JointChannelLifecyclePhase $Sender $Phase
  if (-not $window -or -not $senderWindow) { return $false }
  $sent = @($Sender.sent | Where-Object { $_.text -ceq $Token -and $null -ne $_.atMs -and $_.atMs -ge $senderWindow.Start -and $_.atMs -le $senderWindow.End })
  $pattern = '(?<![A-Za-z0-9_])' + [regex]::Escape($SenderName) + '(?![A-Za-z0-9_]).*' + [regex]::Escape($Token) + '$'
  $hits = @($window.Rows | Where-Object {
    $at = Get-JointBotRowAtMs $_
    $null -ne $at -and $at -ge $window.Start -and $at -le $window.End -and
      [regex]::IsMatch((ConvertTo-JointChannelLifecycleText $_.text), $pattern)
  })
  $allHits = @(Get-JointChannelLifecycleChatRows $Receiver | Where-Object { [string]$_.text -clike "*$Token*" })
  return $sent.Count -eq 1 -and $hits.Count -eq 1 -and $allHits.Count -eq 1
}

function Test-JointChannelLifecycleCommands {
  param($Result, [object[]]$Steps)
  $patterns = @{}
  $command = ''
  foreach ($step in $Steps) {
    if ($step.action -eq 'cmd') {
      $command = $step.text.Replace('{{channel_id}}', [string]$Result.handoff.channel_id).Replace('{{invite_code}}', [string]$Result.handoff.invite_code)
    }
    if ($step.action -eq 'expectText') {
      $pattern = $step.pattern.Replace('{{channel_id}}', [string]$Result.handoff.channel_id).Replace('{{invite_code}}', [string]$Result.handoff.invite_code)
      if (-not (Get-JointChannelLifecycleReceipt $Result $step.sinceCheckpoint $command $pattern)) { return $false }
      if (-not $patterns.ContainsKey($pattern)) { $patterns[$pattern] = 0 }
      $patterns[$pattern]++
    }
  }
  foreach ($pattern in $patterns.Keys) {
    if (@(Get-JointChannelLifecycleChatRows $Result | Where-Object {
      [regex]::IsMatch((ConvertTo-JointChannelLifecycleText $_.text), $pattern)
    }).Count -ne $patterns[$pattern]) { return $false }
  }
  return $true
}

function Assert-JointChannelLifecycleResults {
  param([int]$RoundIndex, [string]$RunId, [int]$PrimaryPort, [int]$SecondaryPort, $OwnerRes, $InviteeRes, $ObserverRes)
  $c = Get-JointChannelLifecycleContext $RunId
  $plans = Get-JointChannelLifecyclePlans $RunId
  $verdicts = [ordered]@{}
  $identities = @{}
  $verdicts['distinct-server-ports'] = $PrimaryPort -gt 0 -and $SecondaryPort -gt 0 -and $PrimaryPort -ne $SecondaryPort -and
    $OwnerRes.port -eq $PrimaryPort -and $InviteeRes.port -eq $SecondaryPort -and $ObserverRes.port -eq $PrimaryPort
  foreach ($pair in @(@{ Role = 'Owner'; Res = $OwnerRes }, @{ Role = 'Invitee'; Res = $InviteeRes }, @{ Role = 'Observer'; Res = $ObserverRes })) {
    $r = $pair.Res
    $role = $pair.Role.ToLowerInvariant()
    $all = @(@($r.assertions) + @($r.absentChecks) | Where-Object { $null -ne $_ })
    $expected = @($plans[$pair.Role] | Where-Object { $_.action -in @('expect', 'expectText', 'expectAbsent') })
    $identities[$pair.Role] = $null -ne $r -and $r.runId -ceq $RunId -and $r.role -ceq "channel-lifecycle-$role" -and
      $r.username -ceq $c[$pair.Role] -and $r.kind -ceq 'java' -and $r.host -ceq '127.0.0.1' -and
      @(Get-JointBotRows $r -Kind 'spawn').Count -eq 1
    $complete = $identities[$pair.Role] -and -not $r.error -and -not $r.timedOut -and $r.handoff.lifecycle_release -ceq 'go' -and
      @(Get-JointBotRows $r -Kind 'checkpoint' | Where-Object { $_.key -ceq 'completed' }).Count -eq 1 -and
      @($all | Where-Object { $_.pass -ne $true -or $_.diagnostic }).Count -eq 0 -and
      @($r.received | Where-Object { $_.kind -in @('kicked', 'error', 'planError') }).Count -eq 0
    foreach ($step in $expected) {
      if (@($all | Where-Object { $_.id -ceq $step.id -and $_.pass -eq $true -and -not $_.diagnostic }).Count -ne 1) { $complete = $false }
    }
    if ($complete -and -not (Test-JointChannelLifecycleCommands $r $plans[$pair.Role])) { $complete = $false }
    $verdicts["$role-completed"] = $complete
  }
  $create = Get-JointChannelLifecycleReceipt $OwnerRes 'create' "/nc create $($c.Name) $($c.Password)" $c.CreatePattern
  $channelId = ''
  if ($create -and (ConvertTo-JointChannelLifecycleText $create.text) -cmatch $c.CreatePattern) { $channelId = $Matches[1] }
  $createLines = @(Get-JointChannelLifecycleChatRows $OwnerRes | Where-Object { (ConvertTo-JointChannelLifecycleText $_.text) -cmatch '(?:^|\s)Private channel id:' })
  $verdicts['created-unique-id'] = $identities.Owner -and [bool]$create -and $createLines.Count -eq 1 -and $OwnerRes.handoff.channel_id -ceq $channelId
  $verdicts['owner-initial-membership'] = $verdicts['created-unique-id'] -and (Test-JointChannelLifecycleMembership $OwnerRes 'initial_membership' $channelId $c.Name @($c.Owner))
  $invite = Get-JointChannelLifecycleReceipt $OwnerRes 'invite' "/nc invite $($c.Invitee) $channelId" $c.InvitePattern
  $code = ''
  if ($invite -and (ConvertTo-JointChannelLifecycleText $invite.text) -cmatch $c.InvitePattern) { $code = $Matches[1] }
  $inviteLines = @(Get-JointChannelLifecycleChatRows $OwnerRes | Where-Object { (ConvertTo-JointChannelLifecycleText $_.text) -cmatch '(?:^|\s)Invite code:' })
  $verdicts['invite-code-exported'] = $verdicts['created-unique-id'] -and $identities.Invitee -and [bool]$invite -and $inviteLines.Count -eq 1 -and
    $OwnerRes.handoff.invite_code -ceq $code -and $InviteeRes.handoff.invite_code -ceq $code -and $InviteeRes.handoff.channel_id -ceq $channelId
  $acceptPattern = '^Joined channel ' + [regex]::Escape($channelId) + ' via invite$'
  $accept = Get-JointChannelLifecycleReceipt $InviteeRes 'accept' "/nc accept $code" $acceptPattern
  $acceptedLines = @(Get-JointChannelLifecycleChatRows $InviteeRes | Where-Object { (ConvertTo-JointChannelLifecycleText $_.text) -cmatch $acceptPattern })
  $verdicts['accepted-on-secondary'] = $verdicts['distinct-server-ports'] -and $verdicts['invite-code-exported'] -and [bool]$accept -and $acceptedLines.Count -eq 1
  $verdicts['owner-membership'] = $verdicts['accepted-on-secondary'] -and (Test-JointChannelLifecycleMembership $OwnerRes 'membership' $channelId $c.Name @($c.Owner, $c.Invitee))
  $verdicts['invitee-membership'] = $verdicts['accepted-on-secondary'] -and (Test-JointChannelLifecycleMembership $InviteeRes 'membership' $channelId $c.Name @($c.Owner, $c.Invitee))
  $verdicts['owner-to-invitee'] = $verdicts['accepted-on-secondary'] -and (Test-JointChannelLifecycleDelivery $OwnerRes $InviteeRes $c.Owner "$RunId-owner-private" 'private_receive')
  $verdicts['invitee-to-owner'] = $verdicts['accepted-on-secondary'] -and (Test-JointChannelLifecycleDelivery $InviteeRes $OwnerRes $c.Invitee "$RunId-invitee-private" 'private_receive')
  foreach ($phase in @('before', 'after')) {
    $verdicts["$phase-positive-control"] = $identities.Owner -and $identities.Invitee -and $identities.Observer -and
      (Test-JointChannelLifecycleDelivery $ObserverRes $OwnerRes $c.Observer "$RunId-$phase" $phase) -and
      (Test-JointChannelLifecycleDelivery $ObserverRes $InviteeRes $c.Observer "$RunId-$phase" $phase) -and
      (Test-JointChannelLifecycleDelivery $ObserverRes $ObserverRes $c.Observer "$RunId-$phase" $phase)
  }
  $refused = Get-JointChannelLifecycleReceipt $ObserverRes 'refused_join' "/nc join $channelId" '(?:^|[\s\[])NC-434(?:\]|\b)'
  $unexpectedJoin = @(Get-JointChannelLifecycleChatRows $ObserverRes | Where-Object { (ConvertTo-JointChannelLifecycleText $_.text) -cmatch ('^Joined channel ' + [regex]::Escape($channelId) + '(?: via invite)?$') })
  $verdicts['outsider-refused'] = $verdicts['created-unique-id'] -and $identities.Observer -and $ObserverRes.handoff.channel_id -ceq $channelId -and [bool]$refused -and $unexpectedJoin.Count -eq 0
  $isolated = $verdicts['observer-completed'] -and $verdicts['before-positive-control'] -and $verdicts['after-positive-control'] -and
    $verdicts['owner-to-invitee'] -and $verdicts['invitee-to-owner'] -and $verdicts['outsider-refused'] -and
    [bool](Get-JointChannelLifecyclePhase $ObserverRes 'private_receive')
  foreach ($token in @("$RunId-owner-private", "$RunId-invitee-private")) {
    if (@(Get-JointChannelLifecycleChatRows $ObserverRes | Where-Object { ([string]$_.text).Contains($token) }).Count) { $isolated = $false }
  }
  $verdicts['outsider-isolated'] = $isolated
  $leavePattern = '^Left channel ' + [regex]::Escape($channelId) + ', switched to default channel: global$'
  $verdicts['owner-left'] = $verdicts['created-unique-id'] -and [bool](Get-JointChannelLifecycleReceipt $OwnerRes 'leave' "/nc leave $channelId" $leavePattern)
  $verdicts['invitee-left'] = $verdicts['accepted-on-secondary'] -and [bool](Get-JointChannelLifecycleReceipt $InviteeRes 'leave' "/nc leave $channelId" $leavePattern)
  $verdicts['membership-cleared'] = $verdicts['owner-left'] -and $verdicts['invitee-left'] -and (Test-JointChannelLifecycleMembership $OwnerRes 'empty_membership' $channelId $c.Name @())
  $verdicts['channel-deleted'] = $verdicts['created-unique-id'] -and $OwnerRes.handoff.channel_deleted -ceq $channelId -and
    [bool](Get-JointChannelLifecycleReceipt $OwnerRes 'deleted_join' "/nc join $channelId" '(?:^|[\s\[])NC-404(?:\]|\b)')
  foreach ($id in @(Get-ChannelLifecycleRequiredAssertionIds $RoundIndex)) {
    $key = $id.Substring($id.LastIndexOf('/') + 1)
    $evidence = "channel=$channelId; invite=$code; primary=$PrimaryPort; secondary=$SecondaryPort"
    if ($key -match '^(owner|invitee|observer)-completed$') {
      $r = switch ($Matches[1]) { 'owner' { $OwnerRes }; 'invitee' { $InviteeRes }; 'observer' { $ObserverRes } }
      $failed = @(@($r.assertions) + @($r.absentChecks) | Where-Object { $null -ne $_ -and $_.pass -ne $true } | ForEach-Object { $_.id })
      $evidence += "; error=$($r.error); timedOut=$($r.timedOut); failed=[$($failed -join ',')]"
    }
    New-JointAssertion -Id $id -Token $key -Pass ([bool]$verdicts[$key]) -Evidence $evidence -Kind 'channel-lifecycle'
  }
}

function Wait-JointChannelLifecycleHandoff {
  param([string]$Directory, [string]$RunId, [string]$Key, [string]$Publisher, [object[]]$Bots, [int]$TimeoutSec = 90)
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  do {
    $files = @(Get-ChildItem -LiteralPath $Directory -Filter "$RunId-*-$Key.json")
    if ($files.Count -gt 1) { throw "Duplicate channel lifecycle handoff: $Key" }
    if ($files.Count -eq 1) {
      $payload = $null
      try { $payload = [IO.File]::ReadAllText($files[0].FullName, [Text.Encoding]::UTF8) | ConvertFrom-Json } catch { }
      if ($payload) {
        if ($payload.runId -cne $RunId -or $payload.key -cne $Key -or $payload.role -cne $Publisher -or -not $payload.value) {
          throw "Invalid channel lifecycle handoff: $Key"
        }
        return [string]$payload.value
      }
    }
    foreach ($bot in $Bots) {
      if ($bot.Process.HasExited) { throw "channel lifecycle $($bot.Role) exited before $Key (code=$($bot.Process.ExitCode))" }
    }
    Start-Sleep -Milliseconds 100
  } while ((Get-Date) -lt $deadline)
  throw "Missing channel lifecycle handoff: $Key"
}

function Invoke-JointChannelLifecycleScenarios {
  param(
    $Round, [string]$RoundDir, [Parameter(Mandatory = $true)][string]$RunId, [string]$McVersion,
    [int]$PrimaryPort, [int]$SecondaryPort, [scriptblock]$BackendCommand
  )
  $prefix = "round$($Round.Index)/channel-lifecycle"
  $work = Join-Path $RoundDir 'bots/channel-lifecycle'
  $handoffs = Join-Path $work 'handoff'
  $bots = @()
  $out = @()
  $channelId = ''
  $deleted = $false
  $fresh = $false
  $delete = {
    $request = @{ command = "delete $channelId"; requestedAt = (Get-Date).ToUniversalTime().ToString('o') }
    [IO.File]::WriteAllText((Join-Path $work 'cleanup-request.json'), ($request | ConvertTo-Json), (New-Object Text.UTF8Encoding $false))
    $null = & $BackendCommand "delete $channelId"
  }
  try {
    if ($Round.Java -cne 'bukkit' -or $Round.SecondaryJava -cne 'bukkit2' -or $PrimaryPort -lt 1 -or
        $SecondaryPort -lt 1 -or $PrimaryPort -gt 65535 -or $SecondaryPort -gt 65535 -or $PrimaryPort -eq $SecondaryPort -or -not $BackendCommand) {
      throw 'Requires relay, primary Bukkit + bukkit2, distinct direct Java ports, en_US/global, ordinary create/invite/accept/join/leave permissions, and BackendCommand { param([string]$Command) ... } for real backend delete.'
    }
    if (Test-Path -LiteralPath $work) {
      if (@(Get-ChildItem -LiteralPath $work -Force).Count) { throw 'channel lifecycle output contains earlier evidence; use a fresh RoundDir' }
    }
    New-Item -ItemType Directory -Force -Path $handoffs | Out-Null
    $fresh = $true
    $plans = Get-JointChannelLifecyclePlans $RunId
    $c = Get-JointChannelLifecycleContext $RunId
    foreach ($role in @('Observer', 'Invitee', 'Owner')) {
      $name = $role.ToLowerInvariant()
      $port = if ($role -eq 'Invitee') { $SecondaryPort } else { $PrimaryPort }
      $planPath = Join-Path $work "plan-$name.json"
      $json = New-CrossServerPlan -role "channel-lifecycle-$name" -kind 'java' -username $c[$role] -port $port `
        -mcVersion $McVersion -resultsFile (Join-Path $work "$name.results.json") -handoffDir $handoffs `
        -timeoutMs 300000 -runId $RunId -steps $plans[$role]
      [IO.File]::WriteAllText($planPath, $json, (New-Object Text.UTF8Encoding $false))
      $bots += [PSCustomObject]@{ Role = $name; Process = (Start-Bot "channel-lifecycle-$name" $planPath $work `
        (Join-Path $work "$name.stdout.log") (Join-Path $work "$name.stderr.log")) }
    }
    if (@($bots | ForEach-Object { $_.Process.Id } | Select-Object -Unique).Count -ne 3) { throw 'Three independent bot processes are required.' }
    $channelId = Wait-JointChannelLifecycleHandoff $handoffs $RunId 'channel_id' 'channel-lifecycle-owner' $bots
    if ($channelId -cnotmatch '^NC-[0-9A-F]{4}$') { $channelId = ''; throw 'Created channel handoff has no valid NC-XXXX ID.' }
    $null = Wait-JointChannelLifecycleHandoff $handoffs $RunId 'cleanup_ready' 'channel-lifecycle-owner' $bots 120
    $null = Wait-JointChannelLifecycleHandoff $handoffs $RunId 'invitee_done' 'channel-lifecycle-invitee' $bots 30
    $null = Wait-JointChannelLifecycleHandoff $handoffs $RunId 'observer_done' 'channel-lifecycle-observer' $bots 30
    & $delete
    $deleted = $true
    Set-Handoff $handoffs $RunId 'channel_deleted' $channelId
    $null = Wait-JointChannelLifecycleHandoff $handoffs $RunId 'owner_done' 'channel-lifecycle-owner' $bots 30
    Set-Handoff $handoffs $RunId 'lifecycle_release' 'go'
    Wait-Bots $bots 20
    foreach ($bot in $bots) {
      if (-not $bot.Process.HasExited -or $bot.Process.ExitCode -ne 0) { throw "channel lifecycle $($bot.Role) exited unsuccessfully" }
    }
  } catch {
    $out += New-JointAssertion "$prefix/drive" 'create/invite/accept + receipts' $false $_.Exception.Message 'channel-lifecycle'
  } finally {
    if ($channelId -and -not $deleted -and $BackendCommand) {
      try { & $delete } catch { $out += New-JointAssertion "$prefix/cleanup" $channelId $false $_.Exception.Message 'channel-lifecycle' }
    }
    foreach ($bot in $bots) {
      if (-not $bot.Process.HasExited) { Stop-Process -Id $bot.Process.Id -Force -ErrorAction SilentlyContinue }
    }
  }
  $owner = $null; $invitee = $null; $observer = $null
  if ($fresh) {
    $owner = Read-BotResult (Join-Path $work 'owner.results.json')
    $invitee = Read-BotResult (Join-Path $work 'invitee.results.json')
    $observer = Read-BotResult (Join-Path $work 'observer.results.json')
  }
  $out += Assert-JointChannelLifecycleResults -RoundIndex $Round.Index -RunId $RunId -PrimaryPort $PrimaryPort `
    -SecondaryPort $SecondaryPort -OwnerRes $owner -InviteeRes $invitee -ObserverRes $observer
  $out
}
