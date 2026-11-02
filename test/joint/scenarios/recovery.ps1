. (Join-Path $PSScriptRoot 'helpers.ps1')

function Get-RecoveryRequiredAssertionIds {
  param([int]$RoundIndex, [string]$Channel = 'global')
  $prefix = "round$RoundIndex/recovery"
  foreach ($name in @(
    'backend-process-restarted', 'java-completed', 'bedrock-completed',
    'restart-membership-java', 'restart-membership-bedrock',
    'pre-java-to-bedrock', 'pre-bedrock-to-java',
    'restart-java-to-bedrock', 'restart-bedrock-to-java',
    'reconnect-java-to-bedrock', 'reconnect-bedrock-to-java',
    'bedrock-single-reconnect', 'bedrock-membership'
  )) { "$prefix/$name" }
  if ($Channel -ne 'global') {
    foreach ($role in @('java', 'bedrock')) {
      foreach ($name in @('pre-selected', 'restart-active', 'reconnect-active', 'no-rejoin')) { "$prefix/$name-$role" }
    }
  }
}

function ConvertTo-JointRecoveryText {
  param([string]$Text)
  [regex]::Replace($Text, '(?i)(?:\u00a7|&)[0-9a-fk-orx]', '').Trim()
}

function Get-JointRecoveryJoinPattern {
  param([string]$Channel)
  '(?:^|\s)(?:Joined channel|\u5df2\u52a0\u5165\u9891\u9053)(?:\s*[:\uff1a]\s*|\s+)' + [regex]::Escape($Channel) + '\s*$'
}

function Get-JointRecoveryWhoPattern {
  param([string]$Channel = '')
  if ($Channel -and $Channel -ne 'global') {
    $displayName = if ($Channel -eq 'local') { 'Local' } else { $Channel }
    $name = [regex]::Escape($displayName)
    return '(?:^|\s)(?:Channel\s+' + $name + '\s+online members\s*\(([1-9][0-9]*)\):|\u9891\u9053\s+' + $name + '\s+\u5728\u7ebf\u6210\u5458\s*\uff08([1-9][0-9]*)\s*\u4eba\uff09\uff1a)\s*$'
  }
  '(?:online members\s*\(([1-9][0-9]*)\):|\u5728\u7ebf\u6210\u5458\s*\uff08([1-9][0-9]*)\s*\u4eba\uff09\uff1a)\s*$'
}

function Get-JointRecoveryWhoSteps {
  param([string]$Phase, [string]$Channel = 'global', [switch]$Current)
  $query = if ($Current) { '/nc who' } else { "/nc who $Channel" }
  $steps = @(
    @{ action = 'checkpoint'; key = $Phase },
    @{ action = 'cmd'; text = $query },
    @{ id = "$Phase-who"; action = 'expectText'; pattern = (Get-JointRecoveryWhoPattern $Channel); sinceCheckpoint = $Phase; timeoutMs = 20000 }
  )
  if ($Channel -ne 'global') {
    $steps += @{ id = "$Phase-who-body"; action = 'expectText'; pattern = '^(?:\[NovaChat\]\s*)?[A-Za-z0-9_ ]+(?:,\s*[A-Za-z0-9_ ]+)*$'; sinceCheckpoint = $Phase; timeoutMs = 5000 }
  }
  $steps
}

function Get-JointRecoveryFailureDetails {
  param($Result)
  $failed = @(@($Result.assertions) + @($Result.absentChecks) | Where-Object { $null -ne $_ -and -not $_.pass -and -not $_.diagnostic } | ForEach-Object { $_.id })
  "error=$($Result.error); timedOut=$($Result.timedOut); failed assertions=[$($failed -join ', ')]"
}

function Get-JointRecoveryPlans {
  param([Parameter(Mandatory = $true)][string]$RunId, [string]$Channel = 'global')
  $java = @(
    @{ action = 'arm'; token = "$RunId-pre-bedrock" },
    @{ action = 'checkpoint'; key = 'recovery-joining' },
    @{ action = 'cmd'; text = "/nc join $Channel" },
    @{ id = 'recovery-join'; action = 'expectText'; pattern = (Get-JointRecoveryJoinPattern $Channel); sinceCheckpoint = 'recovery-joining'; timeoutMs = 20000; required = $true },
    @{ action = 'signal'; key = 'recovery-java-ready' },
    @{ action = 'handoffGet'; key = 'recovery-bedrock-ready'; timeoutMs = 60000 }
  )
  if ($Channel -ne 'global') {
    $java += Get-JointRecoveryWhoSteps -Phase 'recovery-selected' -Channel $Channel -Current
    $java += @(
      @{ action = 'signal'; key = 'recovery-java-selected' },
      @{ action = 'handoffGet'; key = 'recovery-bedrock-selected'; timeoutMs = 30000 }
    )
  }
  $java += @(
    @{ action = 'chat'; text = "$RunId-pre-java" },
    @{ id = 'recovery-pre-bedrock'; action = 'expect'; token = "$RunId-pre-bedrock"; timeoutMs = 30000 },
    @{ action = 'signal'; key = 'recovery-ready' },
    @{ action = 'handoffGet'; key = 'recovery-backend-ready'; timeoutMs = 180000 }
  )
  $java += Get-JointRecoveryWhoSteps -Phase 'backend-restored' -Channel $Channel
  if ($Channel -ne 'global') { $java += Get-JointRecoveryWhoSteps -Phase 'backend-active' -Channel $Channel -Current }
  $java += @(
    @{ action = 'arm'; token = "$RunId-restart-bedrock" },
    @{ action = 'signal'; key = 'recovery-java-restored' },
    @{ action = 'handoffGet'; key = 'recovery-bedrock-restored'; timeoutMs = 30000 },
    @{ action = 'chat'; text = "$RunId-restart-java" },
    @{ id = 'restart-from-bedrock'; action = 'expect'; token = "$RunId-restart-bedrock"; timeoutMs = 30000 },
    @{ action = 'signal'; key = 'recovery-restart-complete' },
    @{ action = 'handoffGet'; key = 'recovery-bedrock-reconnected'; timeoutMs = 90000 },
    @{ action = 'checkpoint'; key = 'bedrock-peer-restored' }
  )
  if ($Channel -ne 'global') { $java += Get-JointRecoveryWhoSteps -Phase 'bedrock-peer-active' -Channel $Channel -Current }
  $java += @(
    @{ action = 'arm'; token = "$RunId-reconnect-bedrock" },
    @{ action = 'chat'; text = "$RunId-reconnect-java" },
    @{ id = 'reconnect-from-bedrock'; action = 'expect'; token = "$RunId-reconnect-bedrock"; timeoutMs = 30000 },
    @{ action = 'signal'; key = 'recovery-done' },
    @{ action = 'handoffGet'; key = 'recovery-bedrock-done'; timeoutMs = 30000 },
    @{ action = 'quit' }
  )
  $bedrock = @(
    @{ action = 'arm'; token = "$RunId-pre-java" },
    @{ action = 'checkpoint'; key = 'recovery-joining' },
    @{ action = 'cmd'; text = "/nc join $Channel" },
    @{ id = 'recovery-join'; action = 'expectText'; pattern = (Get-JointRecoveryJoinPattern $Channel); sinceCheckpoint = 'recovery-joining'; timeoutMs = 20000; required = $true },
    @{ action = 'signal'; key = 'recovery-bedrock-ready' },
    @{ action = 'handoffGet'; key = 'recovery-java-ready'; timeoutMs = 60000 }
  )
  if ($Channel -ne 'global') {
    $bedrock += Get-JointRecoveryWhoSteps -Phase 'recovery-selected' -Channel $Channel -Current
    $bedrock += @(
      @{ action = 'signal'; key = 'recovery-bedrock-selected' },
      @{ action = 'handoffGet'; key = 'recovery-java-selected'; timeoutMs = 30000 }
    )
  }
  $bedrock += @(
    @{ id = 'recovery-pre-java'; action = 'expect'; token = "$RunId-pre-java"; timeoutMs = 30000 },
    @{ action = 'chat'; text = "$RunId-pre-bedrock" },
    @{ action = 'handoffGet'; key = 'recovery-backend-ready'; timeoutMs = 180000 }
  )
  $bedrock += Get-JointRecoveryWhoSteps -Phase 'backend-restored' -Channel $Channel
  if ($Channel -ne 'global') { $bedrock += Get-JointRecoveryWhoSteps -Phase 'backend-active' -Channel $Channel -Current }
  $bedrock += @(
    @{ action = 'arm'; token = "$RunId-restart-java" },
    @{ action = 'signal'; key = 'recovery-bedrock-restored' },
    @{ action = 'handoffGet'; key = 'recovery-java-restored'; timeoutMs = 30000 },
    @{ id = 'restart-from-java'; action = 'expect'; token = "$RunId-restart-java"; timeoutMs = 30000 },
    @{ action = 'chat'; text = "$RunId-restart-bedrock" },
    @{ action = 'handoffGet'; key = 'recovery-restart-complete'; timeoutMs = 30000 },
    @{ id = 'bedrock-reconnect'; action = 'reconnect'; timeoutMs = 45000; delayMs = 1000 }
  )
  $bedrock += Get-JointRecoveryWhoSteps -Phase 'bedrock-restored' -Channel $Channel
  if ($Channel -ne 'global') { $bedrock += Get-JointRecoveryWhoSteps -Phase 'bedrock-active' -Channel $Channel -Current }
  $bedrock += @(
    @{ action = 'arm'; token = "$RunId-reconnect-java" },
    @{ action = 'signal'; key = 'recovery-bedrock-reconnected' },
    @{ id = 'reconnect-from-java'; action = 'expect'; token = "$RunId-reconnect-java"; timeoutMs = 30000 },
    @{ action = 'chat'; text = "$RunId-reconnect-bedrock" },
    @{ action = 'handoffGet'; key = 'recovery-done'; timeoutMs = 30000 },
    @{ action = 'signal'; key = 'recovery-bedrock-done' },
    @{ action = 'quit' }
  )
  @{ Java = $java; Bedrock = $bedrock }
}

function Get-JointRecoveryChatRows {
  param($Result, [double]$SinceMs = [double]::NegativeInfinity, [double]$UntilMs = [double]::PositiveInfinity)
  @(Get-JointBotRows -BotResult $Result -SinceMs $SinceMs -UntilMs $UntilMs | Where-Object {
    $_.kind -eq 'messagestr' -or $_.kind -in @('text:chat', 'text:system', 'text:raw', 'text:json', 'text:translation', 'text:announcement', 'text:whisper')
  })
}

function Get-JointRecoveryCheckpoint {
  param($Result, [string]$Key)
  $rows = @(Get-JointBotRows -BotResult $Result -Kind 'checkpoint' | Where-Object { $_.key -ceq $Key })
  if ($rows.Count -eq 1) { return Get-JointBotRowAtMs $rows[0] }
  return $null
}

function Test-JointRecoveryMembership {
  param($Result, [string]$Phase, [string[]]$Members, [string]$Channel = 'global', [switch]$Current)
  $since = Get-JointRecoveryCheckpoint $Result $Phase
  if ($null -eq $since) { return $false }
  $command = if ($Current) { '/nc who' } else { "/nc who $Channel" }
  $query = @($Result.sent | Where-Object { $_.text -ceq $command -and $_.atMs -ge $since }) | Select-Object -First 1
  if (-not $query) { return $false }
  $until = [double]$query.atMs + 20000
  $nextBoundary = @(Get-JointBotRows -BotResult $Result -Kind 'checkpoint' -SinceMs ($since + 1)) | Select-Object -First 1
  if ($nextBoundary) { $until = [Math]::Min($until, (Get-JointBotRowAtMs $nextBoundary)) }
  $lines = @(Get-JointRecoveryChatRows $Result -SinceMs $query.atMs -UntilMs $until)
  for ($i = 0; $i -lt $lines.Count - 1; $i++) {
    if ((ConvertTo-JointRecoveryText ([string]$lines[$i].text)) -cnotmatch (Get-JointRecoveryWhoPattern $Channel)) { continue }
    $count = if ($Matches[1]) { [int]$Matches[1] } else { [int]$Matches[2] }
    $body = ConvertTo-JointRecoveryText ([string]$lines[$i + 1].text)
    $body = ($body -replace '^\[NovaChat\]\s*', '').Trim()
    if ($body -cnotmatch '^[A-Za-z0-9_ ]+(?:,\s*[A-Za-z0-9_ ]+)*$') { continue }
    $names = @($body -split ',\s*')
    if ($names.Count -ne $count) { continue }
    if (@($Members | Where-Object { $names -cnotcontains $_ }).Count -eq 0) { return $true }
  }
  return $false
}

function Test-JointRecoveryJoin {
  param($Result, [string]$Phase, [string]$Channel)
  $since = Get-JointRecoveryCheckpoint $Result $Phase
  if ($null -eq $since) { return $false }
  $query = @($Result.sent | Where-Object { $_.text -ceq "/nc join $Channel" -and $_.atMs -ge $since }) | Select-Object -First 1
  if (-not $query) { return $false }
  $pattern = Get-JointRecoveryJoinPattern $Channel
  $hits = @(Get-JointRecoveryChatRows $Result -SinceMs $query.atMs -UntilMs ($query.atMs + 20000) | Where-Object {
    (ConvertTo-JointRecoveryText ([string]$_.text)) -match $pattern
  })
  return $hits.Count -gt 0
}

function Test-JointRecoveryNoRejoin {
  param($Result, [string]$Channel)
  $selected = Get-JointRecoveryCheckpoint $Result 'recovery-selected'
  if ($null -eq $selected) { return $false }
  $joins = @($Result.sent | Where-Object { $_.text -match '^/(?:[a-z0-9_-]+:)?(?:nc|novachat)\s+join(?:\s|$)' })
  return $joins.Count -eq 1 -and $joins[0].text -ceq "/nc join $Channel" -and
    $null -ne $joins[0].atMs -and $joins[0].atMs -le $selected
}

function Test-JointRecoveryPlainSend {
  param($Result, [string]$Token, [string]$Phase)
  $since = Get-JointRecoveryCheckpoint $Result $Phase
  if ($null -eq $since) { return $false }
  return @($Result.sent | Where-Object { $_.text -ceq $Token -and $null -ne $_.atMs -and $_.atMs -ge $since }).Count -eq 1
}

function Test-JointRecoveryReceipt {
  param($Result, [string]$Token, [string]$Phase = '', [switch]$ExactlyOnce)
  $since = [double]::NegativeInfinity
  if ($Phase) {
    $since = Get-JointRecoveryCheckpoint $Result $Phase
    if ($null -eq $since) { return $false }
  }
  $rows = @(Get-JointRecoveryChatRows $Result -SinceMs $since | Where-Object {
    [regex]::IsMatch([string]$_.text, ('(?<![A-Za-z0-9_-])' + [regex]::Escape($Token) + '(?![A-Za-z0-9_-])'))
  })
  if ($ExactlyOnce) { return $rows.Count -eq 1 }
  return $rows.Count -gt 0
}

function Assert-JointRecoveryResults {
  param([int]$RoundIndex, [string]$RunId, $JavaRes, $BedrockRes,
    [string]$JavaUsername = 'E2ERecoverJava', [string]$BedrockUsername = 'E2ERecoverBed', [string]$Channel = 'global')
  $prefix = "round$RoundIndex/recovery"
  foreach ($role in @(@{ Name = 'java'; Result = $JavaRes; Kind = 'java' }, @{ Name = 'bedrock'; Result = $BedrockRes; Kind = 'bedrock' })) {
    $res = $role.Result
    $complete = $null -ne $res -and $res.runId -ceq $RunId -and $res.kind -ceq $role.Kind -and -not $res.error -and -not $res.timedOut -and (Test-JointBotRecording $res) -and @($res.assertions | Where-Object { -not $_.pass -and -not $_.diagnostic }).Count -eq 0
    New-JointAssertion "$prefix/$($role.Name)-completed" 'recording' $complete ("kind=$($res.kind); " + (Get-JointRecoveryFailureDetails $res)) 'recovery'
    $members = Test-JointRecoveryMembership $res 'backend-restored' @($JavaUsername, $BedrockUsername) $Channel
    New-JointAssertion "$prefix/restart-membership-$($role.Name)" 'post-restart /nc who' $members 'Both member names must occur in a fresh receiver-side member list, without a new JOIN.' 'recovery'
    if ($Channel -ne 'global') {
      $selected = (Test-JointRecoveryJoin $res 'recovery-joining' $Channel) -and
        (Test-JointRecoveryMembership $res 'recovery-selected' @($JavaUsername, $BedrockUsername) $Channel -Current)
      New-JointAssertion "$prefix/pre-selected-$($role.Name)" $Channel $selected 'Initial JOIN acknowledgement and argument-free /nc who must name the selected channel and both members.' 'recovery'
      $active = Test-JointRecoveryMembership $res 'backend-active' @($JavaUsername, $BedrockUsername) $Channel -Current
      New-JointAssertion "$prefix/restart-active-$($role.Name)" $Channel $active 'After backend reauthentication, argument-free /nc who must still name the selected channel and both members.' 'recovery'
      $phase = if ($role.Name -eq 'java') { 'bedrock-peer-active' } else { 'bedrock-active' }
      $active = Test-JointRecoveryMembership $res $phase @($JavaUsername, $BedrockUsername) $Channel -Current
      New-JointAssertion "$prefix/reconnect-active-$($role.Name)" $Channel $active 'Game reconnect contract: retain the selected channel without a player JOIN; resetting to the default is a failure, separate from backend reauthentication.' 'recovery'
      $noRejoin = Test-JointRecoveryNoRejoin $res $Channel
      New-JointAssertion "$prefix/no-rejoin-$($role.Name)" 'one initial player JOIN' $noRejoin 'The complete command recording must contain exactly one JOIN, before initial selection; backend replay is product behavior.' 'recovery'
    }
  }
  foreach ($pair in @(
    @{ Name = 'pre-java-to-bedrock'; Result = $BedrockRes; Sender = $JavaRes; Token = "$RunId-pre-java"; Phase = ''; SendPhase = 'recovery-selected'; ReceivePhase = 'recovery-selected' },
    @{ Name = 'pre-bedrock-to-java'; Result = $JavaRes; Sender = $BedrockRes; Token = "$RunId-pre-bedrock"; Phase = ''; SendPhase = 'recovery-selected'; ReceivePhase = 'recovery-selected' },
    @{ Name = 'restart-java-to-bedrock'; Result = $BedrockRes; Sender = $JavaRes; Token = "$RunId-restart-java"; Phase = 'backend-restored'; SendPhase = 'backend-active'; ReceivePhase = 'backend-active' },
    @{ Name = 'restart-bedrock-to-java'; Result = $JavaRes; Sender = $BedrockRes; Token = "$RunId-restart-bedrock"; Phase = 'backend-restored'; SendPhase = 'backend-active'; ReceivePhase = 'backend-active' },
    @{ Name = 'reconnect-java-to-bedrock'; Result = $BedrockRes; Sender = $JavaRes; Token = "$RunId-reconnect-java"; Phase = 'bedrock-restored'; SendPhase = 'bedrock-peer-active'; ReceivePhase = 'bedrock-active' },
    @{ Name = 'reconnect-bedrock-to-java'; Result = $JavaRes; Sender = $BedrockRes; Token = "$RunId-reconnect-bedrock"; Phase = 'bedrock-peer-restored'; SendPhase = 'bedrock-active'; ReceivePhase = 'bedrock-peer-active' }
  )) {
    $phase = if ($Channel -eq 'global') { $pair.Phase } else { $pair.ReceivePhase }
    $ok = Test-JointRecoveryReceipt $pair.Result $pair.Token $phase
    if ($Channel -ne 'global') { $ok = $ok -and (Test-JointRecoveryPlainSend $pair.Sender $pair.Token $pair.SendPhase) }
    New-JointAssertion "$prefix/$($pair.Name)" $pair.Token $ok 'Actual destination chat after its local phase boundary; non-default mode also requires the sender to use plain chat, without a channel override.' 'recovery'
  }
  $reconnect = Find-JointBotVerdict $BedrockRes 'bedrock-reconnect'
  $events = @($BedrockRes.received | Where-Object { $_.kind -ceq 'reconnect' -and $_.phase -ceq 'spawned' -and $_.protocol -ceq 'bedrock' -and $_.attempts -eq 1 })
  $ok = $BedrockRes.kind -ceq 'bedrock' -and $null -ne $reconnect -and $reconnect.pass -and $events.Count -eq 1
  New-JointAssertion "$prefix/bedrock-single-reconnect" 'real Bedrock spawn event' $ok "$($reconnect.evidence); spawn records=$($events.Count)" 'recovery'
  $ok = Test-JointRecoveryMembership $BedrockRes 'bedrock-restored' @($JavaUsername, $BedrockUsername) $Channel
  New-JointAssertion "$prefix/bedrock-membership" 'post-reconnect /nc who' $ok 'Fresh member list received by the reconnected Bedrock client.' 'recovery'
}

function Start-JointRecoveryBots {
  param([hashtable]$Plans, [hashtable]$Clients, [string]$WorkDir, [string]$RunId, [int]$TimeoutMs = 360000)
  $handoffs = Join-Path $WorkDir 'handoff'
  New-Item -ItemType Directory -Force -Path $handoffs | Out-Null
  if (@(Get-ChildItem -LiteralPath $handoffs -File | Where-Object { $_.Name.StartsWith("$RunId-") }).Count) { throw 'Refusing stale scenario handoffs for this runId.' }
  $bots = @()
  try {
    foreach ($role in $Plans.Keys) {
      $client = $Clients[$role]
      $resultPath = Join-Path $WorkDir "$role.results.json"
      if (Test-Path -LiteralPath $resultPath) { throw "Refusing stale scenario output: $resultPath" }
      $data = @{ role = $role; kind = $client.Kind; username = $client.Username; host = '127.0.0.1'; port = $client.Port
        mcVersion = $client.McVersion; platform = $client.Platform; resultsFile = $resultPath; handoffDir = $handoffs
        timeoutMs = $TimeoutMs; runId = $RunId; steps = @($Plans[$role]) }
      $planPath = Join-Path $WorkDir "plan-$role.json"
      [System.IO.File]::WriteAllText($planPath, ($data | ConvertTo-Json -Depth 30), (New-Object System.Text.UTF8Encoding $false))
      $process = Start-Bot $role $planPath $WorkDir (Join-Path $WorkDir "$role.stdout.log") (Join-Path $WorkDir "$role.stderr.log")
      if (-not $process) { throw "Could not start $role bot" }
      $bots += [PSCustomObject]@{ Role = $role; Process = $process }
    }
  } catch {
    Stop-JointRecoveryBots $bots
    throw
  }
  return $bots
}

function Stop-JointRecoveryBots {
  param([object[]]$Bots)
  foreach ($bot in $Bots) {
    if ($bot.Process -and -not $bot.Process.HasExited) { Stop-Process -Id $bot.Process.Id -Force -ErrorAction SilentlyContinue }
  }
}

function Invoke-JointRecoveryScenarios {
  param([int]$RoundIndex, [string]$RunId, [string]$WorkDir,
    [hashtable]$JavaClient, [hashtable]$BedrockClient,
    [Parameter(Mandatory = $true)][scriptblock]$StopBackend,
    [Parameter(Mandatory = $true)][scriptblock]$StartBackend,
    [Parameter(Mandatory = $true)][scriptblock]$WaitBackendLinks,
    [string]$Channel = 'global')
  $prefix = "round$RoundIndex/recovery"
  $bots = @()
  $startAttempted = $false
  $stopAttempted = $false
  try {
    if ($JavaClient.Kind -cne 'java' -or $BedrockClient.Kind -cne 'bedrock') { throw 'Recovery requires real Java and Bedrock clients.' }
    $plans = Get-JointRecoveryPlans $RunId $Channel
    $bots = @(Start-JointRecoveryBots $plans @{ Java = $JavaClient; Bedrock = $BedrockClient } $WorkDir $RunId)
    $handoffs = Join-Path $WorkDir 'handoff'
    if (-not (Wait-Handoff $handoffs $RunId 'recovery-ready' 90)) { throw 'Pre-restart bidirectional control did not finish.' }
    $stopAttempted = $true
    $stopped = & $StopBackend
    if (-not $stopped.Stopped -or [long]$stopped.ProcessId -le 0) { throw 'StopBackend must return {Stopped=true; ProcessId=oldPid} after the process exits.' }
    $startAttempted = $true
    $started = & $StartBackend
    if (-not $started.Ready -or [long]$started.ProcessId -le 0 -or $started.ProcessId -eq $stopped.ProcessId) {
      throw 'StartBackend must return {Ready=true; ProcessId=newPid} for a fresh process using the same config and database.'
    }
    if ((& $WaitBackendLinks) -ne $true) { throw 'Backend links did not authenticate within the parent callback deadline.' }
    New-JointAssertion "$prefix/backend-process-restarted" 'oldPid != newPid' $true "Stopped $($stopped.ProcessId); started $($started.ProcessId). Message and membership checks follow separately." 'recovery'
    Set-Handoff $handoffs $RunId 'recovery-backend-ready' 'ready'
    Wait-Bots $bots 180
    foreach ($bot in $bots) {
      if (-not $bot.Process.HasExited -or $bot.Process.ExitCode -ne 0) { throw "Recovery bot $($bot.Role) did not finish successfully." }
    }
  } catch {
    New-JointAssertion "$prefix/drive" 'backend restart + Bedrock reconnect' $false $_.Exception.Message 'recovery'
  } finally {
    Stop-JointRecoveryBots $bots
    if ($stopAttempted -and -not $startAttempted) { $null = & $StartBackend }
  }
  $java = Read-BotResult (Join-Path $WorkDir 'Java.results.json')
  $bedrock = Read-BotResult (Join-Path $WorkDir 'Bedrock.results.json')
  Assert-JointRecoveryResults $RoundIndex $RunId $java $bedrock $JavaClient.Username $BedrockClient.Username $Channel
}
