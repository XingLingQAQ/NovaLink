. (Join-Path $PSScriptRoot 'helpers.ps1')

function Get-CompletionRequiredAssertionIds {
  param([int]$RoundIndex)
  foreach ($id in @('drive', 'protocol-capability', 'requester-completed', 'target-completed',
    'baseline-peer', 'inline-peer', 'channel-excluded', 'channel-restored', 'ignore-excluded',
    'ignore-restored', 'permission-excluded', 'permission-restored', 'permission-changes-recorded')) {
    "round$RoundIndex/completion/$id"
  }
}

function Get-JointCompletionWhoSteps {
  param([string]$Phase, [string]$Channel, [string]$DisplayName, [string]$Name, [int]$IntervalMs)
  @(
    @{ action = 'wait'; ms = $IntervalMs },
    @{ action = 'checkpoint'; key = "completion-who-$Phase" },
    @{ action = 'cmd'; text = "/nc who $Channel" },
    @{ id = "who-$Phase-header"; action = 'expectText'; sinceCheckpoint = "completion-who-$Phase";
      pattern = ('(?:^|\s)Channel ' + [regex]::Escape($DisplayName) + ' online members \([0-9]+\):$'); timeoutMs = 20000; required = $true },
    @{ id = "who-$Phase-member"; action = 'expectText'; sinceCheckpoint = "completion-who-$Phase";
      pattern = ('(?:^|[\s,])' + [regex]::Escape($Name) + '(?:,|$)'); timeoutMs = 20000; required = $true },
    @{ action = 'checkpoint'; key = "completion-who-$Phase-done" }
  )
}

function Get-JointCompletionPlans {
  param([Parameter(Mandatory = $true)][string]$RunId,
    [ValidateSet('velocity', 'bungee')][string]$Proxy = 'velocity',
    [ValidateSet('native-chat', 'request-client')][string]$CoverageScope = 'native-chat',
    [string]$GlobalChannel = 'global', [string]$OtherChannel = 'local',
    [string]$GlobalDisplayName = 'Global', [string]$OtherDisplayName = 'Local',
    [ValidateRange(1500, 30000)][int]$CommandIntervalMs = 1500)

  if ($GlobalChannel -ceq $OtherChannel -or $GlobalChannel -notmatch '^[A-Za-z0-9_-]+$' -or
      $OtherChannel -notmatch '^[A-Za-z0-9_-]+$') { throw 'Completion needs two distinct configured channel IDs.' }
  $sha = [Security.Cryptography.SHA256]::Create()
  try { $suffix = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($RunId)))).Replace('-', '').Substring(0, 6) }
  finally { $sha.Dispose() }
  $mode = if ($CoverageScope -eq 'native-chat') { 'chat_suggestions' } else { 'tab_complete' }
  $p = @{ Names = @{ Requester = "E2ECmpR$suffix"; Target = "E2ECmpT$suffix" }; ProtocolMode = $mode;
    CoverageScope = $CoverageScope; CommandIntervalMs = $CommandIntervalMs; Requester = @(); Target = @() }
  $p.Phases = @('baseline', 'channel-away', 'channel-restored', 'ignored', 'unignored', 'permission-denied', 'permission-restored')
  foreach ($role in @('Requester', 'Target')) {
    $p[$role] = @(
      @{ action = 'wait'; ms = $CommandIntervalMs },
      @{ action = 'checkpoint'; key = 'completion-join-global' },
      @{ action = 'cmd'; text = "/nc join $GlobalChannel" },
      @{ id = 'joined-global'; action = 'expectText'; sinceCheckpoint = 'completion-join-global';
        pattern = ('(?:^|\s)Joined channel ' + [regex]::Escape($GlobalChannel) + '$'); timeoutMs = 20000; required = $true }
    )
  }
  $p.Target += @{ action = 'signal'; key = 'completion-target-ready' }
  $p.Requester += @{ action = 'handoffGet'; key = 'completion-target-ready'; timeoutMs = 45000 }
  foreach ($phase in $p.Phases) {
    $p.Requester += @{ action = 'checkpoint'; key = "completion-$phase" }
    if ($phase -in @('ignored', 'unignored')) {
      $verb = if ($phase -eq 'ignored') { 'ignore' } else { 'unignore' }
      $receipt = if ($phase -eq 'ignored') { 'Ignored' } else { 'Unignored' }
      $p.Requester += @(
        @{ action = 'wait'; ms = $CommandIntervalMs },
        @{ action = 'cmd'; text = "/nc $verb $($p.Names.Target)" },
        @{ id = "receipt-$phase"; action = 'expectText'; sinceCheckpoint = "completion-$phase";
          pattern = ('(?:^|\s)' + $receipt + ' player ' + $p.Names.Target + '$'); timeoutMs = 20000; required = $true }
      )
    }
    if ($phase -in @('permission-denied', 'permission-restored')) {
      $p.Requester += @(
        @{ action = 'signal'; key = "completion-$phase-request" },
        @{ action = 'handoffGet'; key = "completion-$phase-applied"; timeoutMs = 45000 }
      )
    }
    $p.Requester += @(
      @{ action = 'signal'; key = "completion-$phase-ready" },
      @{ action = 'handoffGet'; key = "completion-$phase-live"; timeoutMs = 45000 }
    )
    $p.Requester += Get-JointCompletionWhoSteps $phase $GlobalChannel $GlobalDisplayName $p.Names.Requester $CommandIntervalMs
    $negative = $phase -in @('channel-away', 'ignored', 'permission-denied')
    $capture = @{ id = "completion-$phase"; action = 'complete'; protocolMode = $mode;
      text = ('@' + $p.Names.Target.Substring(0, 10)); mustExclude = @('@' + $p.Names.Requester);
      mustInclude = @(); minCandidates = 0; timeoutMs = 12000; settleMs = 1000; required = $true }
    if ($negative) {
      $capture.mustExclude += '@' + $p.Names.Target
      if ($mode -eq 'chat_suggestions') { $capture.sinceCheckpoint = "completion-$phase" }
    } else { $capture.mustInclude = @('@' + $p.Names.Target); $capture.minCandidates = 1 }
    $p.Requester += @(@{ action = 'wait'; ms = $CommandIntervalMs }, $capture)
    if ($phase -eq 'baseline') {
      $inline = $capture.Clone(); $inline.id = 'completion-inline'; $inline.text = 'hello @' + $p.Names.Target.Substring(0, 10).ToLowerInvariant()
      $p.Requester += @(@{ action = 'wait'; ms = $CommandIntervalMs }, $inline)
    }
    $p.Requester += @{ action = 'signal'; key = "completion-$phase-done" }

    $p.Target += @(
      @{ action = 'handoffGet'; key = "completion-$phase-ready"; timeoutMs = 60000 },
      @{ action = 'checkpoint'; key = "completion-$phase" }
    )
    if ($phase -in @('channel-away', 'channel-restored')) {
      $channel = if ($phase -eq 'channel-away') { $OtherChannel } else { $GlobalChannel }
      $p.Target += @(
        @{ action = 'wait'; ms = $CommandIntervalMs },
        @{ action = 'cmd'; text = "/nc join $channel" },
        @{ id = "joined-$phase"; action = 'expectText'; sinceCheckpoint = "completion-$phase";
          pattern = ('(?:^|\s)Joined channel ' + [regex]::Escape($channel) + '$'); timeoutMs = 20000; required = $true }
      )
    }
    $channel = if ($phase -eq 'channel-away') { $OtherChannel } else { $GlobalChannel }
    $display = if ($phase -eq 'channel-away') { $OtherDisplayName } else { $GlobalDisplayName }
    $p.Target += Get-JointCompletionWhoSteps $phase $channel $display $p.Names.Target $CommandIntervalMs
    $p.Target += @(
      @{ action = 'signal'; key = "completion-$phase-live" },
      @{ action = 'handoffGet'; key = "completion-$phase-done"; timeoutMs = 45000 }
    )
  }
  $p.Requester += @{ action = 'signal'; key = 'completion-release'; value = 'go' }
  $p.Target += @{ action = 'handoffGet'; key = 'completion-release'; timeoutMs = 30000 }
  foreach ($role in @('Requester', 'Target')) {
    $p[$role] += @(@{ action = 'checkpoint'; key = 'completion-complete' }, @{ action = 'quit' })
  }
  return $p
}

function Get-CompletionCheckpointIndex {
  param($Result, [string]$Key)
  $indices = @(); $rows = @($Result.received)
  for ($i = 0; $i -lt $rows.Count; $i++) {
    if ($rows[$i].kind -ceq 'checkpoint' -and $rows[$i].key -ceq $Key) { $indices += $i }
  }
  if ($indices.Count -eq 1) { return $indices[0] }
  return -1
}

function Get-CompletionWireCapture {
  param($Result, $Step)
  $rows = @($Result.received)
  $captures = @($rows | Where-Object { $_.kind -ceq 'completion' -and $_.stepId -ceq $Step.id })
  $inputs = @($rows | Where-Object { $_.kind -ceq 'completionInput' -and $_.stepId -ceq $Step.id })
  $bad = @{ Valid = $false; Candidates = @(); Evidence = 'Missing, stale or inconsistent protocol capture.' }
  if ($captures.Count -ne 1 -or $inputs.Count -ne 1) { return $bad }
  $row = $captures[0]; $input = $inputs[0]
  $captureIndex = [Array]::IndexOf($rows, $row)
  $prefix = $Step.text.Substring($Step.text.LastIndexOf(' ') + 1)
  $at = Get-JointBotRowAtMs $row; $start = Get-JointBotRowAtMs $input
  if ($row.synthetic -ne $false -or $null -eq $at -or $null -eq $start -or $at -lt $start -or
      -not $row.localTime -or -not $input.localTime -or $row.inputAtMs -ne $start -or
      $row.inputText -cne $Step.text -or $input.text -cne $Step.text -or $row.prefix -cne $prefix -or
      $row.protocolMode -cne $Step.protocolMode -or $input.protocolMode -cne $Step.protocolMode) { return $bad }
  $candidates = @(); $last = -1
  $custom = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
  foreach ($index in @($row.packetIndices)) {
    if ($index -isnot [int] -and $index -isnot [long]) { return $bad }
    if ($index -le $last -or $index -lt 0 -or $index -ge $captureIndex) { return $bad }
    $packetRow = $rows[$index]; $packet = $packetRow.packet; $packetAt = Get-JointBotRowAtMs $packetRow
    if ($packetRow.kind -cne 'completionPacket' -or $packetRow.packetName -cne $Step.protocolMode -or
        $packetRow.direction -cne 'clientbound' -or $packetRow.synthetic -ne $false -or
        -not $packetRow.localTime -or $null -eq $packetAt -or $packetAt -gt $at) { return $bad }
    if ($Step.protocolMode -ceq 'tab_complete') {
      if (@($row.packetIndices).Count -ne 1 -or $packet.transactionId -ne $row.transactionId -or $packetAt -lt $start) { return $bad }
      $requests = @($Result.sent | Where-Object { $_.action -ceq 'complete' -and $_.stepId -ceq $Step.id -and
        $_.packet.transactionId -eq $packet.transactionId -and $_.text -ceq $Step.text -and $_.packetName -ceq 'tab_complete' })
      if ($requests.Count -ne 1 -or $requests[0].atMs -lt $start -or $requests[0].atMs -gt $packetAt) { return $bad }
      if ($packet.start -ne $Step.text.LastIndexOf(' ') + 1 -or $packet.length -ne $prefix.Length -or
          $row.replacementStart -ne $packet.start -or $row.replacementLength -ne $packet.length) { return $bad }
      $candidates = @($packet.matches | ForEach-Object { if ($_ -is [string]) { $_ } else { $_.match } })
    } else {
      if ($packet.action -notin @(0, 1, 2) -or $null -eq $packet.entries) { return $bad }
      if ($packet.action -eq 2) { $custom.Clear() }
      foreach ($entry in @($packet.entries)) {
        if ($entry -isnot [string]) { return $bad }
        if ($packet.action -eq 1) { $null = $custom.Remove($entry) } else { $null = $custom.Add($entry) }
      }
    }
    $last = $index
  }
  if ($last -lt 0) { return $bad }
  if ($Step.protocolMode -ceq 'chat_suggestions') {
    $candidates = @($custom | Where-Object { $_.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) })
    if ($Step.sinceCheckpoint) {
      $checkpoint = Get-CompletionCheckpointIndex $Result $Step.sinceCheckpoint
      if ($checkpoint -lt 0 -or $last -le $checkpoint) { return $bad }
    }
    $allIndices = @()
    for ($i = 0; $i -lt $captureIndex; $i++) {
      if ($rows[$i].kind -ceq 'completionPacket' -and $rows[$i].packetName -ceq 'chat_suggestions') { $allIndices += $i }
    }
    if (($allIndices -join ',') -cne ($row.packetIndices -join ',')) { return $bad }
  }
  $actual = @($candidates | Sort-Object -CaseSensitive); $reported = @($row.candidates | Sort-Object -CaseSensitive)
  if ($actual.Count -ne $reported.Count) { return $bad }
  for ($i = 0; $i -lt $actual.Count; $i++) { if ($actual[$i] -cne $reported[$i]) { return $bad } }
  return @{ Valid = $true; Candidates = @($candidates);
    Evidence = "step=$($Step.id); mode=$($Step.protocolMode); input=$($input.localTime); captured=$($row.localTime); prefix=$prefix; packets=[$($row.packetIndices -join ',')]; candidates=[$($candidates -join ',')]" }
}

function Test-CompletionLiveReceipt {
  param($Result, [string]$Phase, [string]$Name)
  $first = Get-CompletionCheckpointIndex $Result "completion-who-$Phase"
  $last = Get-CompletionCheckpointIndex $Result "completion-who-$Phase-done"
  if ($first -lt 0 -or $last -le $first) { return $false }
  $rows = @($Result.received)
  $lines = @($rows[($first + 1)..($last - 1)] | Where-Object { $_.kind -ceq 'messagestr' } | ForEach-Object {
    [regex]::Replace([string]$_.text, '(?i)(?:\u00a7|&)[0-9a-fk-orx]', '')
  })
  $header = @($lines | Where-Object { $_ -cmatch 'Channel .+ online members \([0-9]+\):' })
  $member = @($lines | Where-Object { $_ -cmatch ('(?:^|[\s,])' + [regex]::Escape($Name) + '(?:,|$)') })
  return $header.Count -gt 0 -and $member.Count -gt 0
}

function Assert-JointCompletionResults {
  param([int]$RoundIndex, [string]$RunId, [string]$Proxy, $Plans, $RequesterRes, $TargetRes, [object[]]$PermissionChanges = @())
  $prefix = "round$RoundIndex/completion"
  $capable = $Plans.CoverageScope -ceq 'request-client' -or ($Proxy -cin @('velocity', 'bungee') -and $Plans.ProtocolMode -ceq 'chat_suggestions')
  New-JointAssertion "$prefix/protocol-capability" $Plans.ProtocolMode $capable `
    "scope=$($Plans.CoverageScope); modern ordinary chat uses clientbound chat_suggestions; Velocity uses its public API; Waterfall 615 uses the protocol 774 / packet 0x17 PacketWrapper transport. Actual push packets and peer candidates are required separately." 'completion'
  $healthy = @{}
  foreach ($role in @('Requester', 'Target')) {
    $res = if ($role -ceq 'Requester') { $RequesterRes } else { $TargetRes }
    $bad = @(@($res.assertions) + @($res.absentChecks) | Where-Object { $_ -and $_.pass -ne $true })
    $expected = @($Plans[$role] | Where-Object { $_.action -ceq 'cmd' -or ($_.action -ceq 'complete' -and $_.protocolMode -ceq 'tab_complete') })
    $sent = @($res.sent); $paced = $sent.Count -eq $expected.Count
    for ($i = 0; $paced -and $i -lt $sent.Count; $i++) {
      if ($sent[$i].text -cne $expected[$i].text -or $null -eq $sent[$i].atMs -or
          ($i -gt 0 -and $sent[$i].atMs - $sent[$i - 1].atMs -lt $Plans.CommandIntervalMs)) { $paced = $false }
    }
    $healthy[$role] = $null -ne $res -and $res.runId -ceq $RunId -and $res.username -ceq $Plans.Names[$role] -and
      -not $res.error -and -not $res.timedOut -and $bad.Count -eq 0 -and $paced -and
      (Get-CompletionCheckpointIndex $res 'completion-complete') -ge 0 -and
      @(Get-JointBotRows $res -Kind 'spawn').Count -eq 1 -and $res.handoff.'completion-release' -ceq 'go'
    New-JointAssertion "$prefix/$($role.ToLowerInvariant())-completed" $RunId $healthy[$role] `
      "username=$($res.username); error=$($res.error); timedOut=$($res.timedOut); failed=[$($bad.id -join ',')]; requests=$($sent.Count)/$($expected.Count); pacing=$paced ($($Plans.CommandIntervalMs)ms minimum)" 'completion'
  }
  $captures = @{}; $live = @{}
  foreach ($phase in $Plans.Phases) {
    $step = @($Plans.Requester | Where-Object { $_.id -ceq "completion-$phase" })[0]
    $captures[$phase] = Get-CompletionWireCapture $RequesterRes $step
    $live[$phase] = (Test-CompletionLiveReceipt $RequesterRes $phase $Plans.Names.Requester) -and
      (Test-CompletionLiveReceipt $TargetRes $phase $Plans.Names.Target)
  }
  $peer = '@' + $Plans.Names.Target; $self = '@' + $Plans.Names.Requester
  $both = $healthy.Requester -and $healthy.Target
  foreach ($pair in @(@('baseline', 'baseline-peer'), @('channel-restored', 'channel-restored'),
      @('unignored', 'ignore-restored'), @('permission-restored', 'permission-restored'))) {
    $capture = $captures[$pair[0]]
    $pass = $both -and $live[$pair[0]] -and $capture.Valid -and $capture.Candidates.Count -gt 0 -and
      $capture.Candidates -ccontains $peer -and $capture.Candidates -cnotcontains $self
    New-JointAssertion "$prefix/$($pair[1])" $peer $pass $capture.Evidence 'completion'
  }
  $inlineStep = @($Plans.Requester | Where-Object { $_.id -ceq 'completion-inline' })[0]
  $inline = Get-CompletionWireCapture $RequesterRes $inlineStep
  New-JointAssertion "$prefix/inline-peer" $peer ($both -and $live.baseline -and $inline.Valid -and
    $inline.Candidates.Count -gt 0 -and $inline.Candidates -ccontains $peer -and $inline.Candidates -cnotcontains $self) $inline.Evidence 'completion'
  foreach ($case in @(@('channel-away', 'channel-excluded', 'baseline', 'channel-restored'),
      @('ignored', 'ignore-excluded', 'channel-restored', 'unignored'), @('permission-denied', 'permission-excluded', 'unignored', 'permission-restored'))) {
    $capture = $captures[$case[0]]; $before = $captures[$case[2]]; $after = $captures[$case[3]]
    $pass = $both -and $live[$case[0]] -and $live[$case[2]] -and $live[$case[3]] -and
      $capture.Valid -and $before.Valid -and $after.Valid -and $before.Candidates -ccontains $peer -and
      $after.Candidates -ccontains $peer -and $capture.Candidates -cnotcontains $peer
    New-JointAssertion "$prefix/$($case[1])" $peer $pass `
      "$($capture.Evidence); live receipts=$($live[$case[0]]); before=[$($before.Candidates -join ',')]; after=[$($after.Candidates -join ',')]" 'completion'
  }
  $changes = @($PermissionChanges | Where-Object { $_.username -ceq $Plans.Names.Requester -and $_.permission -ceq 'novachat.feature.mention' })
  $audit = $changes.Count -eq 3
  for ($i = 0; $audit -and $i -lt 3; $i++) {
    if ($changes[$i].allowed -ne ($i -ne 1) -or -not $changes[$i].evidence -or -not $changes[$i].localTime) { $audit = $false }
  }
  New-JointAssertion "$prefix/permission-changes-recorded" 'live permission provider: grant/deny/restore' ($both -and $audit) `
    "Changes=$($changes | ConvertTo-Json -Depth 5 -Compress); wire exclusion and restoration are checked separately." 'completion'
}

function Wait-CompletionHandoff {
  param([string]$Directory, [string]$RunId, [string]$Key, [object[]]$Bots)
  $deadline = (Get-Date).AddSeconds(180)
  do {
    $files = @(Get-ChildItem -LiteralPath $Directory -Filter "$RunId-*-$Key.json")
    if ($files.Count -gt 1) { throw "Duplicate completion handoff: $Key" }
    if ($files.Count -eq 1) {
      $payload = [IO.File]::ReadAllText($files[0].FullName, [Text.Encoding]::UTF8) | ConvertFrom-Json
      if ($payload.runId -cne $RunId -or $payload.role -cne 'completion-requester' -or $payload.key -cne $Key -or $payload.value -cne 'ready') {
        throw "Invalid completion handoff: $Key"
      }
      return
    }
    foreach ($bot in $Bots) { if ($bot.Process.HasExited) { throw "Completion $($bot.Role) exited before $Key." } }
    Start-Sleep -Milliseconds 100
  } while ((Get-Date) -lt $deadline)
  throw "Missing completion handoff: $Key"
}

function Invoke-JointCompletionScenarios {
  param($Round, [string]$RoundDir, [Parameter(Mandatory = $true)][string]$RunId, [string]$McVersion = '1.21.11',
    [Parameter(Mandatory = $true)][int]$Port, [scriptblock]$SetMentionPermission,
    [ValidateSet('native-chat', 'request-client')][string]$CoverageScope = 'native-chat',
    [string]$GlobalChannel = 'global', [string]$OtherChannel = 'local',
    [string]$GlobalDisplayName = 'Global', [string]$OtherDisplayName = 'Local',
    [ValidateRange(1500, 30000)][int]$CommandIntervalMs = 1500)

  $prefix = "round$($Round.Index)/completion"
  $plans = Get-JointCompletionPlans -RunId $RunId -Proxy $Round.Proxy -CoverageScope $CoverageScope `
    -GlobalChannel $GlobalChannel -OtherChannel $OtherChannel -GlobalDisplayName $GlobalDisplayName `
    -OtherDisplayName $OtherDisplayName -CommandIntervalMs $CommandIntervalMs
  $work = Join-Path $RoundDir "bots/completion/$RunId"; $handoffs = Join-Path $work 'handoff'
  $bots = @(); $out = @(); $changes = @(); $fresh = $false; $restoreNeeded = $false
  $permission = {
    param([bool]$Allowed)
    $receipt = & $SetMentionPermission $plans.Names.Requester 'novachat.feature.mention' $Allowed
    if (-not $receipt -or -not $receipt.Evidence) { throw 'Permission setter must return Evidence from the live proxy permission provider.' }
    $change = @{ username = $plans.Names.Requester; permission = 'novachat.feature.mention'; allowed = $Allowed;
      evidence = $receipt.Evidence; providerReceipt = $receipt.Receipt;
      atMs = [DateTimeOffset]::Now.ToUnixTimeMilliseconds(); localTime = (Get-Date).ToString('o') }
    $change
  }
  try {
    if ($Round.Proxy -notin @('velocity', 'bungee') -or -not $Round.Java -or $Port -lt 1 -or $Port -gt 65535 -or -not $SetMentionPermission) {
      throw 'Requires Velocity/Bungee with Java downstream, en_US, two configured channels and SetMentionPermission { param($Username, $Permission, [bool]$Allowed) ...; @{ Evidence = <live provider command receipt> } }. No permission shim or server is started here.'
    }
    if ($McVersion -ne '1.21.11') { throw 'Completion protocol contract is pinned to 1.21.11; other versions need explicit verification.' }
    if (Test-Path -LiteralPath $work) { throw 'Completion output already exists; use a fresh run ID.' }
    New-Item -ItemType Directory -Path $handoffs -Force | Out-Null
    $fresh = $true; $restoreNeeded = $true
    $changes += & $permission $true
    [IO.File]::WriteAllText((Join-Path $work 'permission-changes.json'), ($changes | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding $false))
    foreach ($role in @('Target', 'Requester')) {
      $lower = $role.ToLowerInvariant(); $planPath = Join-Path $work "plan-$lower.json"
      $json = New-CrossServerPlan -role "completion-$lower" -kind 'java' -username $plans.Names[$role] -port $Port `
        -mcVersion $McVersion -resultsFile (Join-Path $work "$lower.results.json") -handoffDir $handoffs `
        -timeoutMs 360000 -runId $RunId -steps $plans[$role]
      [IO.File]::WriteAllText($planPath, $json, (New-Object Text.UTF8Encoding $false))
      $process = Start-Bot "completion-$lower" $planPath $work (Join-Path $work "$lower.stdout.log") (Join-Path $work "$lower.stderr.log")
      if (-not $process) { throw "Completion $lower did not start." }
      $bots += [PSCustomObject]@{ Role = $lower; Process = $process }
    }
    if (@($bots.Process.Id | Select-Object -Unique).Count -ne 2) { throw 'Completion needs two independent bot processes.' }
    foreach ($phase in @('permission-denied', 'permission-restored')) {
      Wait-CompletionHandoff $handoffs $RunId "completion-$phase-request" $bots
      $request = @{ phase = $phase; username = $plans.Names.Requester; permission = 'novachat.feature.mention';
        allowed = $phase -eq 'permission-restored'; requestedAt = (Get-Date).ToString('o') }
      [IO.File]::WriteAllText((Join-Path $work 'permission-request.json'), ($request | ConvertTo-Json -Depth 4), (New-Object Text.UTF8Encoding $false))
      $changes += & $permission ($phase -eq 'permission-restored')
      [IO.File]::WriteAllText((Join-Path $work 'permission-changes.json'), ($changes | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding $false))
      Set-Handoff $handoffs $RunId "completion-$phase-applied" 'ready'
    }
    $restoreNeeded = $false
    Wait-Bots $bots 60
    foreach ($bot in $bots) {
      if (-not $bot.Process.HasExited -or $bot.Process.ExitCode -ne 0) { throw "Completion $($bot.Role) exited unsuccessfully." }
    }
    $out += New-JointAssertion "$prefix/drive" $RunId $true 'Both independent Java bot processes completed.' 'completion'
  } catch {
    $out += New-JointAssertion "$prefix/drive" $RunId $false $_.Exception.Message 'completion'
  } finally {
    if ($restoreNeeded -and $SetMentionPermission) {
      try { $null = & $permission $true } catch { $out += New-JointAssertion "$prefix/permission-cleanup" $RunId $false $_.Exception.Message 'completion' }
    }
    foreach ($bot in $bots) { if (-not $bot.Process.HasExited) { Stop-Process -Id $bot.Process.Id -Force -ErrorAction SilentlyContinue } }
  }
  $requester = $null; $target = $null
  if ($fresh) {
    $requester = Read-BotResult (Join-Path $work 'requester.results.json')
    $target = Read-BotResult (Join-Path $work 'target.results.json')
  }
  $out += Assert-JointCompletionResults -RoundIndex $Round.Index -RunId $RunId -Proxy $Round.Proxy -Plans $plans `
    -RequesterRes $requester -TargetRes $target -PermissionChanges $changes
  return $out
}
