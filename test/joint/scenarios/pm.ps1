. (Join-Path $PSScriptRoot 'helpers.ps1')

function Get-PrivateMessagePlanSteps {
  param(
    [string]$RunId,
    [string]$TargetDisplayName = 'E2EExtTarget',
    [string]$OfflineName = 'NoSuchPlayer',
    [string]$SenderDisplayName = 'E2EExtSender',
    [string]$ObserverDisplayName = 'E2EExtWatch'
  )

  $tokens = @{
    Control = "$RunId-pm-control"
    Dm = "$RunId-pm-dm"
    Reply = "$RunId-pm-reply"
    Retarget = "$RunId-pm-retarget"
    Offline = "$RunId-pm-offline"
  }
  $offlineReceipt = "Player $OfflineName is not online"
  $sender = @()
  $target = @()
  $observer = @()
  foreach ($token in $tokens.Values) {
    $sender += @{ action = 'arm'; token = $token }
    $target += @{ action = 'arm'; token = $token }
    $observer += @{ action = 'arm'; token = $token }
  }
  $sender += @(
    @{ action = 'arm'; token = $offlineReceipt },
    @{ id = 'pm-absent-offline'; action = 'expectAbsent'; token = $tokens.Offline },
    @{ action = 'handoffGet'; key = 'pm-observer-ready'; timeoutMs = 45000 },
    @{ action = 'handoffGet'; key = 'pm-target-ready'; timeoutMs = 45000 },
    @{ id = 'pm-send-control'; action = 'cmd'; text = "/nc msg $ObserverDisplayName $($tokens.Control)" },
    @{ action = 'expect'; token = $tokens.Control; timeoutMs = 20000 },
    @{ action = 'handoffGet'; key = 'pm-control-received'; timeoutMs = 30000 },
    @{ id = 'pm-send-dm'; action = 'cmd'; text = "/nc msg $TargetDisplayName $($tokens.Dm)" },
    @{ action = 'expect'; token = $tokens.Dm; timeoutMs = 20000 },
    @{ id = 'pm-expect-reply'; action = 'expect'; token = $tokens.Reply; timeoutMs = 30000 },
    @{ id = 'pm-send-retarget'; action = 'cmd'; text = "/nc r $($tokens.Retarget)" },
    @{ action = 'expect'; token = $tokens.Retarget; timeoutMs = 20000 },
    @{ action = 'handoffGet'; key = 'pm-retarget-received'; timeoutMs = 30000 },
    @{ id = 'pm-send-offline'; action = 'cmd'; text = "/nc msg $OfflineName $($tokens.Offline)" },
    @{ id = 'pm-expect-offline'; action = 'expect'; token = $offlineReceipt; timeoutMs = 20000 },
    @{ action = 'handoffSet'; key = 'pm-offline-refused'; pattern = [regex]::Escape($offlineReceipt) }
  )
  $target += @(
    @{ id = 'pm-absent-control'; action = 'expectAbsent'; token = $tokens.Control },
    @{ id = 'pm-absent-offline'; action = 'expectAbsent'; token = $tokens.Offline },
    @{ action = 'handoffSet'; key = 'pm-target-ready'; pattern = 'spawned' },
    @{ id = 'pm-expect-dm'; action = 'expect'; token = $tokens.Dm; timeoutMs = 90000 },
    @{ id = 'pm-send-reply'; action = 'cmd'; text = "/nc r $($tokens.Reply)" },
    @{ action = 'expect'; token = $tokens.Reply; timeoutMs = 20000 },
    @{ id = 'pm-expect-retarget'; action = 'expect'; token = $tokens.Retarget; timeoutMs = 30000 },
    @{ action = 'handoffSet'; key = 'pm-retarget-received'; pattern = [regex]::Escape($tokens.Retarget) },
    @{ action = 'handoffGet'; key = 'pm-offline-refused'; timeoutMs = 30000 }
  )
  $observer += @(
    @{ id = 'pm-absent-dm'; action = 'expectAbsent'; token = $tokens.Dm },
    @{ id = 'pm-absent-reply'; action = 'expectAbsent'; token = $tokens.Reply },
    @{ id = 'pm-absent-retarget'; action = 'expectAbsent'; token = $tokens.Retarget },
    @{ id = 'pm-absent-offline'; action = 'expectAbsent'; token = $tokens.Offline },
    @{ action = 'handoffSet'; key = 'pm-observer-ready'; pattern = 'spawned' },
    @{ id = 'pm-expect-control'; action = 'expect'; token = $tokens.Control; timeoutMs = 90000 },
    @{ action = 'handoffSet'; key = 'pm-control-received'; pattern = [regex]::Escape($tokens.Control) },
    @{ action = 'handoffGet'; key = 'pm-offline-refused'; timeoutMs = 90000 }
  )
  return @{ Sender = $sender; Target = $target; Observer = $observer; Tokens = $tokens }
}

function Invoke-PrivateMessageScenarios {
  param(
    [int]$RoundIndex,
    [string]$RunId,
    [string]$TargetName = 'E2EExtTarget',
    [string]$OfflineName = 'NoSuchPlayer',
    $SenderRes,
    $TargetRes,
    $ObserverRes,
    [ValidateSet('lite', 'relay', '')][string]$Topology = '',
    [string]$BackendLog = '',
    [string]$SenderName = 'E2EExtSender',
    [string]$ObserverName = 'E2EExtWatch'
  )

  $prefix = "round$RoundIndex/pm"
  $plan = Get-PrivateMessagePlanSteps -RunId $RunId -TargetDisplayName $TargetName `
    -OfflineName $OfflineName -SenderDisplayName $SenderName -ObserverDisplayName $ObserverName
  $t = $plan.Tokens
  $healthy = {
    param($res)
    return [bool]((Test-JointBotRecording $res) -and -not $res.error -and -not $res.timedOut -and
      $res.handoff.'pm-offline-refused')
  }
  $command = {
    param($res, [string]$text)
    return @($res.sent | Where-Object { $_.text -ceq $text -and $null -ne $_.atMs }) | Select-Object -First 1
  }
  $receipt = {
    param($res, $sent, [string]$wording)
    if (-not $sent) { return $null }
    foreach ($row in @(Get-JointBotRows $res -Kind 'messagestr' -SinceMs $sent.atMs)) {
      $text = [regex]::Replace([string]$row.text, ([string][char]0x00A7 + '.|&[0-9a-fk-or]'), '')
      if ($text.Contains($wording)) { return $row }
    }
    return $null
  }
  $inbound = {
    param($res, [string]$name, [string]$token)
    foreach ($row in @(Get-JointBotRows $res -Kind 'messagestr' -TextContains $token)) {
      $text = [regex]::Replace([string]$row.text, ([string][char]0x00A7 + '.|&[0-9a-fk-or]'), '')
      if ($text.Contains("$name whispers to you: $token")) { return $row }
    }
    return $null
  }
  $negative = {
    param($res, [string]$id, [string]$token)
    $verdict = Find-JointBotVerdict $res $id
    return [bool]((& $healthy $res) -and $verdict -and $verdict.pass -and
      $verdict.token -ceq $token -and @(Get-JointBotRows $res -Kind 'messagestr' -TextContains $token).Count -eq 0)
  }

  $controlCmd = & $command $SenderRes "/nc msg $ObserverName $($t.Control)"
  $dmCmd = & $command $SenderRes "/nc msg $TargetName $($t.Dm)"
  $replyCmd = & $command $TargetRes "/nc r $($t.Reply)"
  $retargetCmd = & $command $SenderRes "/nc r $($t.Retarget)"
  $offlineCmd = & $command $SenderRes "/nc msg $OfflineName $($t.Offline)"
  $control = & $inbound $ObserverRes $SenderName $t.Control
  $controlEcho = & $receipt $SenderRes $controlCmd "You whisper to ${ObserverName}: $($t.Control)"
  $controlGood = [bool]((& $healthy $ObserverRes) -and (& $healthy $SenderRes) -and $control -and
    $controlEcho -and $dmCmd -and $controlEcho.atMs -le $dmCmd.atMs)
  New-JointAssertion -Id "$prefix/observer-positive-control" -Token $t.Control -Pass $controlGood `
    -Evidence "observer whisper=$($control.text); sender echo before next DM=$($controlEcho.text)" -Kind 'pm-control'

  $dm = & $inbound $TargetRes $SenderName $t.Dm
  $echo = & $receipt $SenderRes $dmCmd "You whisper to ${TargetName}: $($t.Dm)"
  $delivered = [bool]((& $healthy $TargetRes) -and (& $healthy $SenderRes) -and $dmCmd -and $dm -and $echo)
  New-JointAssertion -Id "$prefix/sender-delivery-confirmation" -Token $t.Dm -Pass ([bool]$echo) `
    -Evidence "sender echo after command: $($echo.text)" -Kind 'pm-delivery'
  New-JointAssertion -Id "$prefix/dm-delivered-to-target" -Token $t.Dm -Pass $delivered `
    -Evidence "sender command=$($dmCmd.text); target whisper=$($dm.text)" -Kind 'pm-delivery'
  New-JointAssertion -Id "$prefix/dm-not-leaked-to-observer" -Token $t.Dm `
    -Pass ($controlGood -and $delivered -and (& $negative $ObserverRes 'pm-absent-dm' $t.Dm)) `
    -Evidence "positive control=$controlGood; target delivered=$delivered; observer absence requires completed recording and armed verdict" -Kind 'pm-privacy'
  New-JointAssertion -Id "$prefix/control-not-leaked-to-target" -Token $t.Control `
    -Pass ($controlGood -and $delivered -and (& $negative $TargetRes 'pm-absent-control' $t.Control)) `
    -Evidence "observer received control=$controlGood; target received later DM=$delivered; target control absence checked" -Kind 'pm-privacy'

  $reply = & $inbound $SenderRes $TargetName $t.Reply
  $replyEcho = & $receipt $TargetRes $replyCmd "You whisper to ${SenderName}: $($t.Reply)"
  $replyGood = [bool]($delivered -and $reply -and $replyEcho -and $replyCmd -and $dm.atMs -le $replyCmd.atMs)
  New-JointAssertion -Id "$prefix/reply-delivered-to-sender" -Token $t.Reply -Pass $replyGood `
    -Evidence "target /nc r=$($replyCmd.text); echo=$($replyEcho.text); original sender=$($reply.text)" -Kind 'pm-delivery'
  New-JointAssertion -Id "$prefix/reply-not-leaked-to-observer" -Token $t.Reply `
    -Pass ($controlGood -and $replyGood -and (& $negative $ObserverRes 'pm-absent-reply' $t.Reply)) `
    -Evidence "positive control=$controlGood; reply delivered=$replyGood; observer absence checked" -Kind 'pm-privacy'

  $retarget = & $inbound $TargetRes $SenderName $t.Retarget
  $retargetEcho = & $receipt $SenderRes $retargetCmd "You whisper to ${TargetName}: $($t.Retarget)"
  $retargetGood = [bool]($replyGood -and $retarget -and $retargetEcho -and $retargetCmd -and $reply.atMs -le $retargetCmd.atMs)
  New-JointAssertion -Id "$prefix/sender-reply-delivered-to-target" -Token $t.Retarget -Pass $retargetGood `
    -Evidence "sender previously whispered to $ObserverName; latest /nc r echo=$($retargetEcho.text); target=$($retarget.text)" -Kind 'pm-delivery'
  New-JointAssertion -Id "$prefix/sender-reply-not-leaked-to-observer" -Token $t.Retarget `
    -Pass ($controlGood -and $retargetGood -and (& $negative $ObserverRes 'pm-absent-retarget' $t.Retarget)) `
    -Evidence "positive control=$controlGood; latest partner delivery=$retargetGood; old partner absence checked" -Kind 'pm-privacy'

  $offline = & $receipt $SenderRes $offlineCmd "Player $OfflineName is not online"
  $refused = [bool]((& $healthy $SenderRes) -and $offlineCmd -and $offline)
  New-JointAssertion -Id "$prefix/offline-refused-to-sender" -Token $t.Offline -Pass $refused `
    -Evidence "offline command=$($offlineCmd.text); refusal after command=$($offline.text)" -Kind 'pm-refusal'
  $offlineAbsent = $refused -and $controlGood -and $delivered
  foreach ($res in @($SenderRes, $TargetRes, $ObserverRes)) {
    $offlineAbsent = $offlineAbsent -and (& $negative $res 'pm-absent-offline' $t.Offline)
  }
  New-JointAssertion -Id "$prefix/offline-not-delivered-anywhere" -Token $t.Offline -Pass $offlineAbsent `
    -Evidence "explicit refusal=$refused; all three live recordings require armed offline absence verdicts" -Kind 'pm-privacy'
}
