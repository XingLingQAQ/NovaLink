. (Join-Path $PSScriptRoot 'render-coordination.ps1')

function Get-JointRenderEffectsAssertionIds {
  param([int]$RoundIndex)
  foreach ($id in @('drive', 'source-completed', 'target-completed', 'watch-completed',
    'item-sent', 'item-target', 'item-watch', 'item-decision',
    'mention-sent', 'mention-target', 'mention-source', 'mention-watch', 'mention-decision',
    'dm-sent', 'dm-target', 'dm-source', 'dm-watch', 'dm-decision')) {
    "round$RoundIndex/render-effects/$id"
  }
}

function Get-RenderEffectsPlans {
  param([string]$RunId)
  if ($RunId -cnotmatch '^[A-Za-z0-9][A-Za-z0-9_.-]*$') { throw 'Invalid render-effects run ID.' }
  $plans = @{}
  foreach ($role in @('source', 'target', 'watch')) {
    $channel = if ($role -eq 'watch') { 'local' } else { 'global' }
    $steps = @(@{ action = 'wait'; ms = 2500 })
    if ($role -eq 'source') {
      $steps += @(@{ action = 'signal'; key = 're-source-online' },
        @{ action = 'handoffGet'; key = 're-prepared'; timeoutMs = 45000 })
    }
    $steps += @(@{ action = 'wait'; ms = 1500 }, @{ action = 'checkpoint'; key = 're-join' },
      @{ action = 'cmd'; text = "/nc join $channel" },
      @{ id = 're-join'; action = 'expectText'; pattern = "Joined channel $channel(?:\s|$)";
        sinceCheckpoint = 're-join'; timeoutMs = 20000; required = $true })
    foreach ($phase in @('item', 'mention', 'dm')) {
      if ($phase -eq 'dm' -and $role -eq 'watch') {
        $steps += @(@{ action = 'wait'; ms = 1500 }, @{ action = 'checkpoint'; key = 're-dm-join' },
          @{ action = 'cmd'; text = '/nc join global' },
          @{ id = 're-dm-join'; action = 'expectText'; pattern = 'Joined channel global(?:\s|$)';
            sinceCheckpoint = 're-dm-join'; timeoutMs = 20000; required = $true })
      }
      $before = "$RunId-re-$phase-$role-before"; $after = "$RunId-re-$phase-$role-after"
      $steps += @(@{ action = 'wait'; ms = 1500 }, @{ action = 'checkpoint'; key = "re-$phase-start" },
        @{ action = 'chat'; text = $before },
        @{ id = "re-$phase-before"; action = 'expectText'; pattern = ([regex]::Escape($before) + '$');
          sinceCheckpoint = "re-$phase-start"; timeoutMs = 15000; required = $true },
        @{ action = 'signal'; key = "re-$phase-$role-ready" },
        @{ action = 'handoffGet'; key = "re-$phase-issued"; timeoutMs = 45000 })
      if ($role -eq 'source') {
        $action = if ($phase -eq 'dm') { 'cmd' } else { 'chat' }
        $text = switch ($phase) {
          'item' { "[item] $RunId-item-trigger" }
          'mention' { "@E2ERenderTarget $RunId-mention-trigger" }
          'dm' { "/nc msg E2ERenderTarget $RunId-dm-trigger" }
        }
        $steps += @(@{ action = 'wait'; ms = 1500 }, @{ action = 'checkpoint'; key = "re-$phase-send" },
          @{ action = $action; text = $text }, @{ action = 'signal'; key = "re-$phase-sent" })
      }
      $steps += @(@{ action = 'handoffGet'; key = "re-$phase-observe"; timeoutMs = 45000 },
        @{ action = 'wait'; ms = 3000 }, @{ action = 'chat'; text = $after },
        @{ id = "re-$phase-after"; action = 'expectText'; pattern = ([regex]::Escape($after) + '$');
          sinceCheckpoint = "re-$phase-start"; timeoutMs = 15000; required = $true },
        @{ action = 'checkpoint'; key = "re-$phase-end" }, @{ action = 'signal'; key = "re-$phase-$role-done" })
    }
    $steps += @(@{ action = 'handoffGet'; key = 're-release'; timeoutMs = 45000 },
      @{ action = 'checkpoint'; key = 're-completed' }, @{ action = 'quit' })
    $plans[$role] = $steps
  }
  $plans
}

function Invoke-JointRenderEffectsScenarios {
  param([int]$RoundIndex, [string]$RunId, [string]$WorkDir, [int]$JavaPort, [int]$ProxyPort,
    [string]$McVersion, [ValidateSet('velocity', 'bungee')][string]$ProxyPlatform, [scriptblock]$PrepareSender)
  $bots = @(); $ok = $false; $evidence = ''; $work = ''
  try {
    if (-not $PrepareSender -or -not $WorkDir -or $JavaPort -le 0 -or $ProxyPort -le 0 -or -not $McVersion -or -not $ProxyPlatform) { throw 'Missing render-effects inputs.' }
    $plans = Get-RenderEffectsPlans $RunId
    $work = Join-Path $WorkDir ([guid]::NewGuid().ToString('N'))
    $handoffs = Join-Path $work 'handoff'
    $null = New-Item -ItemType Directory -Path $handoffs -Force
    foreach ($role in @('source', 'target', 'watch')) {
      $name = @{ source = 'E2ERenderSource'; target = 'E2ERenderTarget'; watch = 'E2ERenderWatch' }[$role]
      $port = if ($role -eq 'source') { $JavaPort } else { $ProxyPort }
      $planPath = Join-Path $work "plan-$role.json"
      $json = New-CrossServerPlan -role "render-effects-$role" -kind 'java' -username $name -port $port -mcVersion $McVersion `
        -resultsFile (Join-Path $work "$role.results.json") -handoffDir $handoffs -timeoutMs 300000 -runId $RunId -steps $plans[$role]
      $plan = $json | ConvertFrom-Json
      $plan | Add-Member -NotePropertyName captureMessageComponents -NotePropertyValue $true
      [IO.File]::WriteAllText($planPath, ($plan | ConvertTo-Json -Depth 30), (New-Object Text.UTF8Encoding $false))
      $bots += [PSCustomObject]@{ Role = $role; Process = (Start-Bot "render-effects-$role" $planPath $work `
        (Join-Path $work "$role.stdout.log") (Join-Path $work "$role.stderr.log")) }
    }
    if (-not (Wait-Handoff $handoffs $RunId 're-source-online' 35)) { throw 'Source did not join.' }
    $ack = & $PrepareSender 'E2ERenderSource' "$RunId-item-name"
    $ackText = if ($ack -is [string]) { $ack } else { [string]$ack.fixtureConsoleAck }
    if (-not $ackText.Contains('JointCustomEffectsBridge queued equip')) { throw 'PrepareSender returned no fixture console acknowledgement.' }
    Set-Handoff $handoffs $RunId 're-prepared' 'go'
    foreach ($phase in @('item', 'mention', 'dm')) {
      foreach ($role in @('source', 'target', 'watch')) {
        if (-not (Wait-Handoff $handoffs $RunId "re-$phase-$role-ready" 45)) { throw "$phase/${role}: missing before marker." }
      }
      Set-Handoff $handoffs $RunId "re-$phase-issued" 'go'
      if (-not (Wait-Handoff $handoffs $RunId "re-$phase-sent" 25)) { throw "${phase}: send failed." }
      Set-Handoff $handoffs $RunId "re-$phase-observe" 'go'
      foreach ($role in @('source', 'target', 'watch')) {
        if (-not (Wait-Handoff $handoffs $RunId "re-$phase-$role-done" 35)) { throw "$phase/${role}: missing after marker." }
      }
    }
    Set-Handoff $handoffs $RunId 're-release' 'go'
    Wait-Bots $bots 20
    foreach ($bot in $bots) { if (-not $bot.Process.HasExited -or $bot.Process.ExitCode -ne 0) { throw "$($bot.Role) did not exit successfully." } }
    $ok = $true; $evidence = "All three bots completed; fixture acknowledged preparation. Artifacts: $work"
  } catch { $evidence = "$($_.Exception.Message); artifacts: $work" }
  finally { foreach ($bot in $bots) { if (-not $bot.Process.HasExited) { Stop-Process -Id $bot.Process.Id -Force -ErrorAction SilentlyContinue } } }
  New-JointAssertion -Id "round$RoundIndex/render-effects/drive" -Token $RunId -Pass $ok -Evidence $evidence -Kind 'render-effects'
}

function Get-RenderEffectsAtMs {
  param($Row)
  $at = 0L
  if ([long]::TryParse([string]$Row.atMs, [ref]$at) -and $at -gt 0) { return $at }
  return $null
}

function Get-RenderEffectsWindow {
  param($Bot, [string]$RunId, [string]$Role, [string]$Phase)
  $rows = @($Bot.received); $indices = @{}
  foreach ($key in @('start', 'before', 'after', 'end')) {
    $hits = @()
    for ($i = 0; $i -lt $rows.Count; $i++) {
      if ($key -in @('start', 'end')) { $hit = $rows[$i].kind -ceq 'checkpoint' -and $rows[$i].key -ceq "re-$Phase-$key" }
      else { $hit = $rows[$i].kind -ceq 'messagestr' -and [string]$rows[$i].text -cmatch ([regex]::Escape("$RunId-re-$Phase-$Role-$key") + '$') }
      if ($hit) { $hits += $i }
    }
    if ($hits.Count -ne 1) { return @{ Valid = $false; Rows = @(); Evidence = "$key count=$($hits.Count)" } }
    $indices[$key] = $hits[0]
  }
  $first = Get-RenderEffectsAtMs $rows[$indices.before]; $last = Get-RenderEffectsAtMs $rows[$indices.after]
  $valid = $indices.start -lt $indices.before -and $indices.before -lt $indices.after -and $indices.after -lt $indices.end -and
    $null -ne $first -and $null -ne $last -and ($last - $first) -ge 3000 -and ($last - $first) -le 90000
  $inside = @()
  if ($valid) {
    for ($i = $indices.before + 1; $i -lt $indices.after; $i++) {
      $at = Get-JointBotRowAtMs $rows[$i]
      if ($null -eq $at -or $at -lt $first -or $at -gt $last) { $valid = $false }
      $inside += $rows[$i]
    }
  }
  @{ Valid = [bool]$valid; Rows = $inside; BeforeMs = $first; AfterMs = $last; Evidence = "ordered live markers=$valid; rows=$($inside.Count)" }
}

function Get-RenderEffectsComponentText {
  param($Node)
  if ($null -eq $Node) { return '' }
  if ($Node -is [string] -or $Node -is [ValueType]) { return [string]$Node }
  if ($Node -is [array]) { return (($Node | ForEach-Object { Get-RenderEffectsComponentText $_ }) -join '') }
  $parts = @()
  foreach ($key in @('text', 'extra', 'with', 'contents', 'value')) {
    $property = $Node.PSObject.Properties[$key]
    if ($null -ne $property) { $parts += Get-RenderEffectsComponentText $property.Value }
  }
  return ($parts -join '')
}

function Test-RenderEffectsHover {
  param($Node)
  if ($null -eq $Node -or $Node -is [string] -or $Node -is [ValueType]) { return $false }
  if ($Node -is [array]) { foreach ($child in $Node) { if (Test-RenderEffectsHover $child) { return $true } }; return $false }
  foreach ($property in $Node.PSObject.Properties) {
    if ($property.Name -cin @('hoverEvent', 'hover_event') -and $property.Value.action -ceq 'show_text') {
      $body = $null
      foreach ($key in @('contents', 'value', 'text')) {
        $field = $property.Value.PSObject.Properties[$key]
        if ($null -ne $field) { $body = $field.Value; break }
      }
      if ($null -eq $body) { continue }
      $json = $body | ConvertTo-Json -Depth 80 -Compress
      $plain = Get-RenderEffectsComponentText $body
      $plain = [regex]::Replace($plain, ([string][char]0x00a7 + '[0-9a-fk-or]'), '', 'IgnoreCase')
      if ($json -cmatch 'minecraft:diamond_sword' -and
        ($plain -match '(?i)count\s*[:=]\s*1(?![\w.])' -or $json -match '(?i)["\x27]count["\x27]\s*:\s*1(?![\w.])')) { return $true }
    }
    if (Test-RenderEffectsHover $property.Value) { return $true }
  }
  return $false
}

function Get-RenderEffectsResultAssertions {
  param([int]$RoundIndex, [string]$RunId, [string]$ProxyPlatform, $Recordings, $ProxyEvents, $ServerEvents, [bool]$Unique)
  $prefix = "round$RoundIndex/render-effects"
  $names = @{ source = 'E2ERenderSource'; target = 'E2ERenderTarget'; watch = 'E2ERenderWatch' }
  $usable = @{}; $uuids = @{}; $windows = @{}
  foreach ($role in @('source', 'target', 'watch')) {
    $bot = $Recordings[$role]
    $self = @($bot.received | Where-Object { $_.kind -ceq 'playerJoined' -and $_.data.player -ceq $names[$role] } |
      ForEach-Object { [string]$_.data.uuid } | Select-Object -Unique)
    $uuid = [guid]::Empty
    $ok = $Unique -and $null -ne $bot -and $bot.runId -ceq $RunId -and $bot.username -ceq $names[$role] -and
      $bot.kind -ceq 'java' -and -not $bot.error -and $bot.timedOut -eq $false -and $null -ne $bot.state -and
      $null -ne $bot.state.PSObject.Properties['kicked'] -and $bot.state.self -ceq $names[$role] -and -not $bot.state.kicked -and
      $self.Count -eq 1 -and [guid]::TryParse([string]$self[0], [ref]$uuid) -and $uuid -ne [guid]::Empty -and
      $bot.handoff.'re-release' -ceq 'go' -and @($bot.received | Where-Object { $_.kind -ceq 'checkpoint' -and $_.key -ceq 're-completed' }).Count -eq 1 -and
      @($bot.assertions | Where-Object { $_.pass -ne $true }).Count -eq 0 -and (Find-JointBotVerdict $bot 're-join').pass -eq $true
    if ($role -eq 'watch') { $ok = $ok -and (Find-JointBotVerdict $bot 're-dm-join').pass -eq $true }
    $channel = if ($role -eq 'watch') { 'local' } else { 'global' }
    $ok = $ok -and @($bot.sent | Where-Object { $_.text -ceq "/nc join $channel" }).Count -eq 1
    if ($role -eq 'watch') { $ok = $ok -and @($bot.sent | Where-Object { $_.text -ceq '/nc join global' }).Count -eq 1 }
    if ($role -eq 'source') { $ok = $ok -and $bot.handoff.'re-prepared' -ceq 'go' }
    $rows = @($bot.received)
    foreach ($join in (@(@{ key = 're-join'; channel = $channel; next = 're-item-start' }) + @(if ($role -eq 'watch') { @{ key = 're-dm-join'; channel = 'global'; next = 're-dm-start' } }))) {
      $startRows = @($rows | Where-Object { $_.kind -ceq 'checkpoint' -and $_.key -ceq $join.key })
      $endRows = @($rows | Where-Object { $_.kind -ceq 'checkpoint' -and $_.key -ceq $join.next })
      $fresh = $false
      if ($startRows.Count -eq 1 -and $endRows.Count -eq 1) {
        $start = [array]::IndexOf($rows, $startRows[0]); $end = [array]::IndexOf($rows, $endRows[0])
        $acks = @()
        for ($i = $start + 1; $i -lt $end; $i++) {
          if ($rows[$i].kind -ceq 'messagestr' -and [string]$rows[$i].text -cmatch ('Joined channel ' + [regex]::Escape($join.channel) + '(?:\s|$)')) { $acks += $rows[$i] }
        }
        $fresh = $start -lt $end -and $acks.Count -eq 1
      }
      $ok = $ok -and $fresh
    }
    foreach ($phase in @('item', 'mention', 'dm')) {
      $ok = $ok -and $bot.handoff."re-$phase-issued" -ceq 'go' -and $bot.handoff."re-$phase-observe" -ceq 'go'
      foreach ($marker in @('before', 'after')) {
        $verdicts = @($bot.assertions | Where-Object { $_.id -ceq "re-$phase-$marker" -and $_.pass -eq $true })
        $ok = $ok -and $verdicts.Count -eq 1
      }
    }
    $usable[$role] = [bool]$ok; $uuids[$role] = if ($self.Count -eq 1) { $self[0] } else { '' }
    New-JointAssertion -Id "$prefix/$role-completed" -Token $names[$role] -Pass $ok `
      -Evidence "unique workdir=$Unique; named Java recording, UUID, joins and completion=$ok" -Kind 'render-effects'
    foreach ($phase in @('item', 'mention', 'dm')) { $windows["$phase/$role"] = Get-RenderEffectsWindow $bot $RunId $role $phase }
  }
  $all = $usable.source -and $usable.target -and $usable.watch
  $sentAtMs = @{}
  foreach ($phase in @('item', 'mention', 'dm')) {
    $text = switch ($phase) {
      'item' { "[item] $RunId-item-trigger" }
      'mention' { "@E2ERenderTarget $RunId-mention-trigger" }
      'dm' { "/nc msg E2ERenderTarget $RunId-dm-trigger" }
    }
    $sourceWindow = $windows["$phase/source"]
    $sent = @($Recordings.source.sent | Where-Object { $_.text -ceq $text })
    $sendMarker = @($sourceWindow.Rows | Where-Object { $_.kind -ceq 'checkpoint' -and $_.key -ceq "re-$phase-send" })
    $sentOk = $all -and $sourceWindow.Valid -and $sent.Count -eq 1 -and $sendMarker.Count -eq 1
    if ($sentOk) {
      $t = Get-RenderEffectsAtMs $sent[0]; $start = Get-JointBotRowAtMs $sendMarker[0]
      $after = @($Recordings.source.received | Where-Object { $_.kind -ceq 'messagestr' -and [string]$_.text -cmatch ([regex]::Escape("$RunId-re-$phase-source-after") + '$') })
      $sentOk = $null -ne $t -and $null -ne $start -and $after.Count -eq 1 -and $t -ge $start -and $t -le (Get-JointBotRowAtMs $after[0])
    }
    New-JointAssertion -Id "$prefix/$phase-sent" -Token $text -Pass $sentOk -Evidence "one real player send inside source live window=$sentOk; count=$($sent.Count)" -Kind 'render-effects'
    $windows["$phase/sent"] = [bool]$sentOk
    $sentAtMs[$phase] = if ($sentOk) { $t } else { $null }
  }
  $itemToken = "$RunId-item-name"; $targetWindow = $windows['item/target']; $watchWindow = $windows['item/watch']
  $lines = @($targetWindow.Rows | Where-Object { $_.kind -ceq 'messagestr' -and ([string]$_.text).Contains($itemToken) })
  $components = @($targetWindow.Rows | Where-Object { $_.kind -ceq 'messageComponent' -and ([string]$_.text).Contains($itemToken) })
  $hover = $components.Count -eq 1 -and (Test-RenderEffectsHover $components[0].data.component)
  $allLines = @($Recordings.target.received | Where-Object { $_.kind -ceq 'messagestr' -and ([string]$_.text).Contains($itemToken) })
  $allComponents = @($Recordings.target.received | Where-Object { $_.kind -ceq 'messageComponent' -and ([string]$_.text).Contains($itemToken) })
  $itemChat = @($targetWindow.Rows | Where-Object { $_.kind -ceq 'messagestr' -and ([string]$_.text).Contains("$RunId-item-trigger") })
  $allItemChat = @($Recordings.target.received | Where-Object { $_.kind -ceq 'messagestr' -and ([string]$_.text).Contains("$RunId-item-trigger") })
  $pass = $windows['item/sent'] -and $targetWindow.Valid -and $lines.Count -eq 1 -and $components.Count -eq 1 -and $allLines.Count -eq 1 -and $allComponents.Count -eq 1 -and $lines[0].text -ceq $components[0].text -and $hover -and
    $itemChat.Count -eq 1 -and $allItemChat.Count -eq 1 -and $itemChat[0].text -cne $lines[0].text
  New-JointAssertion -Id "$prefix/item-target" -Token $itemToken -Pass $pass -Evidence "item lines=$($lines.Count); components=$($components.Count); companion chat window/run=$($itemChat.Count)/$($allItemChat.Count); diamond sword count=1 hover=$hover; $($targetWindow.Evidence)" -Kind 'render-effects'
  $watchLeaks = @($Recordings.watch.received | Where-Object { $_.kind -cin @('messagestr', 'messageComponent') -and (([string]$_.text).Contains($itemToken) -or ([string]$_.text).Contains("$RunId-item-trigger")) })
  $pass = $pass -and $watchWindow.Valid -and $watchLeaks.Count -eq 0
  New-JointAssertion -Id "$prefix/item-watch" -Token $itemToken -Pass $pass -Evidence "watch item leaks=$($watchLeaks.Count); $($watchWindow.Evidence)" -Kind 'render-effects'
  $mention = $windows['mention/target']
  $title = @($mention.Rows | Where-Object { $_.kind -ceq 'title' -and $_.data.type -ceq 'title' -and $_.data.text -ceq 'E2ERenderSource' })
  $subtitle = @($mention.Rows | Where-Object { $_.kind -ceq 'title' -and $_.data.type -ceq 'subtitle' -and ([string]$_.data.text).Contains('global') })
  $times = @($mention.Rows | Where-Object { $_.kind -ceq 'titleTimes' })
  $sound = @($mention.Rows | Where-Object { $_.kind -ceq 'sound' -and [string]$_.data.sound -in @('entity.experience_orb.pickup', 'minecraft:entity.experience_orb.pickup') })
  $bar = @($mention.Rows | Where-Object { $_.kind -ceq 'actionBar' -and ([string]$_.text).Contains('E2ERenderSource') -and ([string]$_.text).Contains('global') })
  $timingOk = $times.Count -eq 1 -and $times[0].data.fadeIn -eq 10 -and $times[0].data.stay -eq 40 -and $times[0].data.fadeOut -eq 10
  $extraOk = if ($ProxyPlatform -ceq 'velocity') { $sound.Count -eq 1 } else { $bar.Count -eq 1 }
  $mentionChat = @($mention.Rows | Where-Object { $_.kind -ceq 'messagestr' -and ([string]$_.text).Contains("$RunId-mention-trigger") })
  $allMentionChat = @($Recordings.target.received | Where-Object { $_.kind -ceq 'messagestr' -and ([string]$_.text).Contains("$RunId-mention-trigger") })
  $mentionOk = $windows['mention/sent'] -and $mention.Valid -and $title.Count -eq 1 -and $subtitle.Count -eq 1 -and $timingOk -and $extraOk -and $mentionChat.Count -eq 1 -and $allMentionChat.Count -eq 1
  New-JointAssertion -Id "$prefix/mention-target" -Token 'E2ERenderSource' -Pass $mentionOk -Evidence "chat window/run=$($mentionChat.Count)/$($allMentionChat.Count); title=$($title.Count); subtitle=$($subtitle.Count); timings=$timingOk; sound=$($sound.Count); actionBar=$($bar.Count); platform=$ProxyPlatform" -Kind 'render-effects'
  $watchMentionLeaks = @($Recordings.watch.received | Where-Object { $_.kind -cin @('messagestr', 'messageComponent') -and ([string]$_.text).Contains("$RunId-mention-trigger") })
  foreach ($role in @('source', 'watch')) {
    $window = $windows["mention/$role"]
    $effects = @($window.Rows | Where-Object {
      ($_.kind -ceq 'title' -and ($_.data.text -ceq 'E2ERenderSource' -or ([string]$_.data.text).Contains('global'))) -or
      ($_.kind -ceq 'sound' -and [string]$_.data.sound -in @('entity.experience_orb.pickup', 'minecraft:entity.experience_orb.pickup')) -or
      ($_.kind -ceq 'actionBar' -and ([string]$_.text).Contains('E2ERenderSource'))
    })
    $pass = $mentionOk -and $window.Valid -and $effects.Count -eq 0
    if ($role -eq 'watch') { $pass = $pass -and $watchMentionLeaks.Count -eq 0 }
    New-JointAssertion -Id "$prefix/mention-$role" -Token 'E2ERenderSource' -Pass $pass -Evidence "notification effects=$($effects.Count); watch trigger leaks=$($watchMentionLeaks.Count); $($window.Evidence)" -Kind 'render-effects'
  }
  $dmTargetOk = $false
  foreach ($role in @('target', 'source', 'watch')) {
    $window = $windows["dm/$role"]
    $lines = @($Recordings[$role].received | Where-Object { $_.kind -ceq 'messagestr' -and ([string]$_.text).Contains("$RunId-dm-trigger") })
    $windowLines = @($window.Rows | Where-Object { $_.kind -ceq 'messagestr' -and ([string]$_.text).Contains("$RunId-dm-trigger") })
    $expected = if ($role -eq 'watch') { 0 } else { 1 }
    $pass = $windows['dm/sent'] -and $window.Valid -and $lines.Count -eq $expected -and $windowLines.Count -eq $expected
    if ($role -ne 'watch' -and $pass) {
      $plain = [regex]::Replace([string]$windowLines[0].text, ([string][char]0x00a7 + '[0-9a-fk-or]'), '', 'IgnoreCase')
      $envelope = if ($role -eq 'source') { "You whisper to E2ERenderTarget: $RunId-dm-trigger" } else { "E2ERenderSource whispers to you: $RunId-dm-trigger" }
      $pass = $plain -ceq $envelope
    }
    if ($role -eq 'target') { $dmTargetOk = [bool]$pass }
    if ($role -eq 'watch') { $pass = $pass -and $dmTargetOk }
    New-JointAssertion -Id "$prefix/dm-$role" -Token "$RunId-dm-trigger" -Pass $pass -Evidence "whole recording=$($lines.Count); window=$($windowLines.Count); expected=$expected; role envelope and target control=$pass; $($window.Evidence)" -Kind 'render-effects'
  }
  $targetUuid = $uuids.target; $latest = $null; $decisions = @()
  foreach ($event in @($ProxyEvents)) {
    if ($event.playerId -cne $targetUuid) { continue }
    if ($event.event -ceq 'negotiated') { $latest = $event }
    if ($event.event -ceq 'decision') { $decisions += [PSCustomObject]@{ Event = $event; Latest = $latest } }
  }
  $peerEpochs = @($ServerEvents | Where-Object { $_.event -ceq 'negotiated' -and $_.playerId -ceq $targetUuid })
  $peerTimesValid = @($peerEpochs | Where-Object { $null -eq (Get-RenderEffectsAtMs $_) }).Count -eq 0
  foreach ($kind in @(@{ name = 'item'; id = 16; channel = 'global' }, @{ name = 'mention'; id = 18; channel = 'global' }, @{ name = 'dm'; id = 20; channel = '' })) {
    $hits = @($decisions | Where-Object { $_.Event.packetId -eq $kind.id })
    $sourceWindow = $windows["$($kind.name)/source"]; $targetWindow = $windows["$($kind.name)/target"]
    $valid = $all -and $windows["$($kind.name)/sent"] -and $sourceWindow.Valid -and $targetWindow.Valid -and $hits.Count -eq 1 -and $peerTimesValid
    $inPhase = $false; $peerCurrent = $false
    if ($valid) {
      $event = $hits[0].Event; $request = [guid]::Empty
      $at = Get-RenderEffectsAtMs $event; $proxyAt = Get-RenderEffectsAtMs $hits[0].Latest
      $inPhase = $null -ne $at -and $at -gt $sentAtMs[$kind.name] -and $at -lt $sourceWindow.AfterMs -and $at -lt $targetWindow.AfterMs
      $peer = $null; $peerAt = $null
      if ($null -ne $at) {
        foreach ($epoch in $peerEpochs) {
          $epochAt = Get-RenderEffectsAtMs $epoch
          if ($epochAt -le $at -and ($null -eq $peerAt -or $epochAt -ge $peerAt)) { $peer = $epoch; $peerAt = $epochAt }
        }
      }
      $peerCurrent = $null -ne $peer -and $event.session -ceq $peer.session
      $valid = $event.owner -ceq 'proxy' -and $null -ne $event.PSObject.Properties['channelId'] -and
        $event.channelId -is [string] -and $event.channelId -ceq $kind.channel -and
        $null -ne $proxyAt -and $proxyAt -le $at -and $event.session -ceq $hits[0].Latest.session -and $peerCurrent -and $inPhase -and
        [guid]::TryParse([string]$event.requestId, [ref]$request) -and $request -ne [guid]::Empty
    }
    New-JointAssertion -Id "$prefix/$($kind.name)-decision" -Token $targetUuid -Pass $valid -Evidence "packet=$($kind.id); channel='$($kind.channel)'; decisions=$($hits.Count); within sent/after bounds=$inPhase; latest Bukkit epoch=$peerCurrent; channel, current proxy/Bukkit epoch and proxy owner=$valid" -Kind 'render-effects'
  }
}

function Get-JointRenderEffectsAssertions {
  param([string]$RoundDir, [string]$RunId, [ValidateSet('velocity', 'bungee')][string]$ProxyPlatform, [int]$RoundIndex = 1)
  $run = "$RunId-r$RoundIndex-render-effects"; $root = Join-Path $RoundDir 'bots/render-effects'
  $names = @{ source = 'E2ERenderSource'; target = 'E2ERenderTarget'; watch = 'E2ERenderWatch' }
  $matches = @{ source = @(); target = @(); watch = @() }; $malformed = $false
  if (Test-Path -LiteralPath $root -PathType Container) {
    foreach ($dir in Get-ChildItem -LiteralPath $root -Directory) {
      $guid = [guid]::Empty
      if (-not [guid]::TryParseExact($dir.Name, 'N', [ref]$guid)) { continue }
      foreach ($file in Get-ChildItem -LiteralPath $dir.FullName -Filter '*.results.json' -File) {
        try { $bot = [IO.File]::ReadAllText($file.FullName, [Text.Encoding]::UTF8) | ConvertFrom-Json -ErrorAction Stop }
        catch { $malformed = $true; continue }
        foreach ($role in @('source', 'target', 'watch')) {
          if ($bot.runId -ceq $run -and $bot.username -ceq $names[$role]) { $matches[$role] += [PSCustomObject]@{ Bot = $bot; Dir = $dir.FullName } }
        }
      }
    }
  }
  $unique = -not $malformed -and $matches.source.Count -eq 1 -and $matches.target.Count -eq 1 -and $matches.watch.Count -eq 1
  if ($unique) { $unique = $matches.source[0].Dir -ceq $matches.target[0].Dir -and $matches.source[0].Dir -ceq $matches.watch[0].Dir }
  $recordings = @{ source = $null; target = $null; watch = $null }
  if ($unique) { foreach ($role in @('source', 'target', 'watch')) { $recordings[$role] = $matches[$role][0].Bot } }
  $proxyEvents = @(); $serverEvents = @()
  try {
    $proxyEvents = @(Read-JointRenderEvents (Join-Path $RoundDir "$ProxyPlatform/proxy.stdout.log"))
    $serverEvents = @(Read-JointRenderEvents (Join-Path $RoundDir 'bukkit/server.stdout.log'))
  } catch { $unique = $false }
  Get-RenderEffectsResultAssertions -RoundIndex $RoundIndex -RunId $run -ProxyPlatform $ProxyPlatform `
    -Recordings $recordings -ProxyEvents $proxyEvents -ServerEvents $serverEvents -Unique $unique
}
