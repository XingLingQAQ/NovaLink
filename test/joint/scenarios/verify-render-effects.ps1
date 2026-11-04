[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'render-effects.ps1')

$root = Join-Path ([IO.Path]::GetTempPath()) ('novalink-render-effects-' + [guid]::NewGuid().ToString('N'))
$work = Join-Path $root ('bots/render-effects/' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Force -Path $work, (Join-Path $root 'bukkit'), (Join-Path $root 'velocity'), (Join-Path $root 'bungee')
$run = 'contract-r1-render-effects'
$names = @{ source = 'E2ERenderSource'; target = 'E2ERenderTarget'; watch = 'E2ERenderWatch' }
$player = [guid]::NewGuid().ToString(); $session = [guid]::NewGuid().ToString()
$negotiated = @{ event = 'negotiated'; version = 1; server = 'bukkit'; playerId = $player; session = $session; atMs = 500 }
$events = @($negotiated)
foreach ($packet in @(16, 18, 20)) {
  $channel = if ($packet -eq 20) { '' } else { 'global' }
  $at = 1150 + (($packet - 16) / 2) * 10000
  $events += @{ event = 'decision'; version = 1; server = 'bukkit'; playerId = $player; session = $session;
    packetId = $packet; requestId = [guid]::NewGuid().ToString(); owner = 'proxy'; channelId = $channel; atMs = $at }
}

function Write-Events($Proxy, $Server) {
  $proxyLines = @($Proxy | ForEach-Object { '[INFO] [Debug] NOVA_RENDER_EVENT ' + ($_ | ConvertTo-Json -Compress) })
  $serverLines = @($Server | ForEach-Object { '[INFO] [Debug] NOVA_RENDER_EVENT ' + ($_ | ConvertTo-Json -Compress) })
  foreach ($platform in @('velocity', 'bungee')) {
    [IO.File]::WriteAllText((Join-Path $root "$platform/proxy.stdout.log"), ($proxyLines -join "`n"))
  }
  [IO.File]::WriteAllText((Join-Path $root 'bukkit/server.stdout.log'), ($serverLines -join "`n"))
}

function New-Recording([string]$Role, [string]$Platform) {
  $name = $names[$Role]; $id = if ($Role -eq 'target') { $player } else { [guid]::NewGuid().ToString() }
  $rows = New-Object System.Collections.ArrayList; $sent = New-Object System.Collections.ArrayList
  $assertions = New-Object System.Collections.ArrayList
  $null = $rows.Add(@{ kind = 'playerJoined'; atMs = 1; data = @{ player = $name; uuid = $id } })
  $null = $assertions.Add(@{ id = 're-join'; pass = $true })
  if ($Role -eq 'watch') { $null = $assertions.Add(@{ id = 're-dm-join'; pass = $true }) }
  $channel = if ($Role -eq 'watch') { 'local' } else { 'global' }
  $null = $rows.Add(@{ kind = 'checkpoint'; key = 're-join'; atMs = 10 })
  $null = $rows.Add(@{ kind = 'messagestr'; text = "Joined channel $channel"; atMs = 11 })
  $null = $sent.Add(@{ text = "/nc join $channel"; atMs = 10 })
  if ($Role -eq 'watch') { $null = $sent.Add(@{ text = '/nc join global'; atMs = 20000 }) }
  $base = 1000
  foreach ($phase in @('item', 'mention', 'dm')) {
    if ($Role -eq 'watch' -and $phase -eq 'dm') {
      $null = $rows.Add(@{ kind = 'checkpoint'; key = 're-dm-join'; atMs = ($base - 2) })
      $null = $rows.Add(@{ kind = 'messagestr'; text = 'Joined channel global'; atMs = ($base - 1) })
    }
    foreach ($marker in @('before', 'after')) { $null = $assertions.Add(@{ id = "re-$phase-$marker"; pass = $true }) }
    $null = $rows.Add(@{ kind = 'checkpoint'; key = "re-$phase-start"; atMs = $base })
    $null = $rows.Add(@{ kind = 'messagestr'; text = "$run-re-$phase-$Role-before"; atMs = ($base + 1) })
    if ($Role -eq 'source') {
      $null = $rows.Add(@{ kind = 'checkpoint'; key = "re-$phase-send"; atMs = ($base + 100) })
      $text = switch ($phase) { item { "[item] $run-item-trigger" }; mention { "@E2ERenderTarget $run-mention-trigger" }; dm { "/nc msg E2ERenderTarget $run-dm-trigger" } }
      $null = $sent.Add(@{ text = $text; atMs = ($base + 101) })
    }
    if ($Role -eq 'target' -and $phase -eq 'item') {
      $null = $rows.Add(@{ kind = 'messagestr'; text = "E2ERenderSource: [item] $run-item-trigger"; atMs = ($base + 199) })
      $line = "E2ERenderSource showed item [$run-item-name]"
      $component = @{ text = $line; hover_event = @{ action = 'show_text'; value = @{ extra = @(
        @{ text = 'minecraft:diamond_sword' }, @{ text = ' Count: ' }, @{ text = '1' }) } } }
      $null = $rows.Add(@{ kind = 'messagestr'; text = $line; atMs = ($base + 200) })
      $null = $rows.Add(@{ kind = 'messageComponent'; text = $line; data = @{ component = $component }; atMs = ($base + 200) })
    }
    if ($Role -eq 'target' -and $phase -eq 'mention') {
      $null = $rows.Add(@{ kind = 'messagestr'; text = "E2ERenderSource: @E2ERenderTarget $run-mention-trigger"; atMs = ($base + 199) })
      $null = $rows.Add(@{ kind = 'title'; data = @{ type = 'title'; text = 'E2ERenderSource' }; atMs = ($base + 200) })
      $null = $rows.Add(@{ kind = 'title'; data = @{ type = 'subtitle'; text = 'Mentioned you in channel global' }; atMs = ($base + 201) })
      $null = $rows.Add(@{ kind = 'titleTimes'; data = @{ fadeIn = 10; stay = 40; fadeOut = 10 }; atMs = ($base + 202) })
      if ($Platform -eq 'velocity') { $null = $rows.Add(@{ kind = 'sound'; data = @{ sound = 'entity.experience_orb.pickup' }; atMs = ($base + 203) }) }
      else { $null = $rows.Add(@{ kind = 'actionBar'; text = 'E2ERenderSource Mentioned you in channel global'; atMs = ($base + 203) }) }
    }
    if ($phase -eq 'dm' -and $Role -in @('source', 'target')) {
      $line = if ($Role -eq 'source') { "You whisper to E2ERenderTarget: $run-dm-trigger" } else { "E2ERenderSource whispers to you: $run-dm-trigger" }
      $null = $rows.Add(@{ kind = 'messagestr'; text = (([string][char]0x00a7) + '7' + $line); atMs = ($base + 200) })
    }
    $null = $rows.Add(@{ kind = 'messagestr'; text = "$run-re-$phase-$Role-after"; atMs = ($base + 4000) })
    $null = $rows.Add(@{ kind = 'checkpoint'; key = "re-$phase-end"; atMs = ($base + 4001) })
    $base += 10000
  }
  $null = $rows.Add(@{ kind = 'checkpoint'; key = 're-completed'; atMs = 40000 })
  $handoff = @{ 're-release' = 'go'; 're-prepared' = 'go' }
  foreach ($phase in @('item', 'mention', 'dm')) { $handoff["re-$phase-issued"] = 'go'; $handoff["re-$phase-observe"] = 'go' }
  @{ runId = $run; username = $name; kind = 'java'; timedOut = $false; state = @{ kicked = $false; self = $name };
    handoff = $handoff; sent = @($sent.ToArray()); received = @($rows.ToArray()); assertions = @($assertions.ToArray()) }
}

function Write-Recordings([string]$Platform) {
  foreach ($role in @('source', 'target', 'watch')) {
    $json = New-Recording $role $Platform | ConvertTo-Json -Depth 40
    [IO.File]::WriteAllText((Join-Path $work "$role.results.json"), $json)
  }
}
function Read-Gates([string]$Platform = 'velocity') {
  @(Get-JointRenderEffectsAssertions -RoundDir $root -RunId 'contract' -ProxyPlatform $Platform)
}
function Assert-Failed([string]$Id, [string]$Label) {
  $gate = @(Read-Gates | Where-Object { $_.id -ceq "round1/render-effects/$Id" })
  if ($gate.Count -ne 1 -or $gate[0].pass) { throw "$Label was accepted by $Id" }
}
function New-CrossServerPlan {
  param($role, $kind, $username, $port, $mcVersion, $resultsFile, $handoffDir, $timeoutMs, $runId, $steps)
  @{ role = $role; kind = $kind; username = $username; port = $port; mcVersion = $mcVersion; resultsFile = $resultsFile;
    handoffDir = $handoffDir; timeoutMs = $timeoutMs; runId = $runId; steps = $steps } | ConvertTo-Json -Depth 20
}
function Start-Bot($role, $planPath, $workDir, $stdout, $stderr) {
  $script:drivePlans[$role] = [IO.File]::ReadAllText($planPath) | ConvertFrom-Json
  [PSCustomObject]@{ HasExited = $true; ExitCode = 0 }
}
function Wait-Handoff($dir, $runId, $key, $timeout) { $true }
function Set-Handoff($dir, $runId, $key, $value) { }
function Wait-Bots($bots, $timeout) { }

try {
  $script:drivePlans = @{}
  $drive = @(Invoke-JointRenderEffectsScenarios -RoundIndex 1 -RunId $run -WorkDir (Join-Path $root 'drive') `
    -JavaPort 25565 -ProxyPort 25566 -McVersion '1.21.11' -ProxyPlatform velocity `
    -PrepareSender { param($username, $itemToken) if ($username -cne 'E2ERenderSource' -or $itemToken -cne "$run-item-name") { throw 'Wrong preparation arguments.' }; 'JointCustomEffectsBridge queued equip' })
  if ($drive.Count -ne 1 -or -not $drive[0].pass -or $script:drivePlans.Count -ne 3) { throw 'Mock drive failed.' }
  foreach ($role in @('source', 'target', 'watch')) {
    $plan = $script:drivePlans["render-effects-$role"]
    $port = if ($role -eq 'source') { 25565 } else { 25566 }
    if ($plan.captureMessageComponents -ne $true -or $plan.port -ne $port -or $plan.username -cne $names[$role]) { throw "Invalid $role plan." }
  }
  $sourceSteps = @($script:drivePlans['render-effects-source'].steps)
  foreach ($message in @("[item] $run-item-trigger", "@E2ERenderTarget $run-mention-trigger", "/nc msg E2ERenderTarget $run-dm-trigger")) {
    if (@($sourceSteps | Where-Object { $_.text -ceq $message -and $_.action -cin @('chat', 'cmd') }).Count -ne 1) { throw "Invalid send: $message" }
  }
  $noAck = @(Invoke-JointRenderEffectsScenarios -RoundIndex 1 -RunId $run -WorkDir (Join-Path $root 'no-ack') `
    -JavaPort 25565 -ProxyPort 25566 -McVersion '1.21.11' -ProxyPlatform velocity -PrepareSender { })
  if ($noAck.Count -ne 1 -or $noAck[0].pass) { throw 'Missing fixture acknowledgement accepted.' }
  Write-Events $events @($negotiated)
  $required = @(Get-JointRenderEffectsAssertionIds 1)
  if ($required.Count -ne 18 -or @($required | Select-Object -Unique).Count -ne 18) { throw 'Required gate inventory changed.' }
  foreach ($platform in @('velocity', 'bungee')) {
    Write-Recordings $platform
    $gates = @(Read-Gates $platform)
    if ($gates.Count -ne 17 -or @($gates | Where-Object { -not $_.pass -or $_.id -cnotin $required }).Count) {
      throw "$platform synthetic positive failed: $(($gates | Where-Object { -not $_.pass } | ForEach-Object { $_.id + ': ' + $_.evidence }) -join '; ')"
    }
  }
  Write-Recordings 'velocity'
  $targetPath = Join-Path $work 'target.results.json'
  $original = [IO.File]::ReadAllText($targetPath)
  $target = $original | ConvertFrom-Json
  $target.kind = 'bedrock'
  [IO.File]::WriteAllText($targetPath, ($target | ConvertTo-Json -Depth 40))
  Assert-Failed 'target-completed' 'Wrong kind'
  [IO.File]::WriteAllText($targetPath, $original)
  $target = $original | ConvertFrom-Json
  $target.timedOut = $true
  [IO.File]::WriteAllText($targetPath, ($target | ConvertTo-Json -Depth 40))
  Assert-Failed 'target-completed' 'Timeout'
  $target = $original | ConvertFrom-Json
  $target.received = @($target.received | Where-Object { $_.kind -cne 'playerJoined' })
  [IO.File]::WriteAllText($targetPath, ($target | ConvertTo-Json -Depth 40))
  Assert-Failed 'target-completed' 'Missing self UUID'
  $target = $original | ConvertFrom-Json
  $target.received += @($target.received | Where-Object { $_.kind -ceq 'messagestr' -and ([string]$_.text).Contains("$run-dm-trigger") })
  [IO.File]::WriteAllText($targetPath, ($target | ConvertTo-Json -Depth 40))
  Assert-Failed 'dm-target' 'Duplicate private message'
  [IO.File]::WriteAllText($targetPath, $original)
  $target = $original | ConvertFrom-Json
  $publicEcho = @($target.received | Where-Object { $_.kind -ceq 'messagestr' -and ([string]$_.text).Contains("$run-dm-trigger") })[0]
  $publicEcho.text = "<E2ERenderSource> $run-dm-trigger"
  [IO.File]::WriteAllText($targetPath, ($target | ConvertTo-Json -Depth 40))
  Assert-Failed 'dm-target' 'Public echo'
  [IO.File]::WriteAllText($targetPath, $original)
  $target = $original | ConvertFrom-Json
  $target.received = @($target.received | Where-Object { $_.kind -cne 'messagestr' -or -not ([string]$_.text).Contains("$run-mention-trigger") })
  [IO.File]::WriteAllText($targetPath, ($target | ConvertTo-Json -Depth 40))
  Assert-Failed 'mention-target' 'Missing mention chat'
  $target = $original | ConvertFrom-Json
  $target.received += @($target.received | Where-Object { $_.kind -ceq 'messagestr' -and ([string]$_.text).Contains("$run-mention-trigger") })
  [IO.File]::WriteAllText($targetPath, ($target | ConvertTo-Json -Depth 40))
  Assert-Failed 'mention-target' 'Duplicate mention chat outside phase'
  $target = $original | ConvertFrom-Json
  $target.received = @($target.received | Where-Object { $_.kind -cne 'messagestr' -or -not ([string]$_.text).Contains("$run-item-trigger") })
  [IO.File]::WriteAllText($targetPath, ($target | ConvertTo-Json -Depth 40))
  Assert-Failed 'item-target' 'Missing item companion chat'
  [IO.File]::WriteAllText($targetPath, $original)
  $watchPath = Join-Path $work 'watch.results.json'
  $watchOriginal = [IO.File]::ReadAllText($watchPath)
  $watch = $watchOriginal | ConvertFrom-Json
  $watch.received += @{ kind = 'messagestr'; text = "E2ERenderSource: $run-mention-trigger"; atMs = 45000 }
  [IO.File]::WriteAllText($watchPath, ($watch | ConvertTo-Json -Depth 40))
  Assert-Failed 'mention-watch' 'Watch mention chat leak outside phase'
  [IO.File]::WriteAllText($watchPath, $watchOriginal)
  foreach ($i in @(1, 2, 3)) {
    $wrong = @($events | ForEach-Object { @{} + $_ })
    $wrong[$i].channelId = if ($i -eq 3) { 'global' } else { 'local' }
    Write-Events $wrong @($negotiated)
    $name = @('item', 'mention', 'dm')[$i - 1]
    Assert-Failed "$name-decision" 'Wrong channel'
  }
  Write-Events ($events + $events[1]) @($negotiated)
  Assert-Failed 'item-decision' 'Duplicate decision'
  Write-Events $events @()
  Assert-Failed 'item-decision' 'Missing peer'
  $newSession = [guid]::NewGuid().ToString()
  $rotation = @{ event = 'negotiated'; version = 1; server = 'bukkit'; playerId = $player; session = $newSession; atMs = 600 }
  Write-Events (@($rotation) + $events[1..3]) @($negotiated, $rotation)
  Assert-Failed 'item-decision' 'Stale epoch'
  Write-Events $events @($negotiated, $rotation)
  Assert-Failed 'item-decision' 'Bukkit already rotated while proxy still uses old epoch'
  $outside = @($events | ForEach-Object { @{} + $_ })
  $outside[1].atMs = 6000
  Write-Events $outside @($negotiated)
  Assert-Failed 'item-decision' 'Unique decision outside phase'
  $missingTime = @($events | ForEach-Object { @{} + $_ })
  $missingTime[1].Remove('atMs')
  Write-Events $missingTime @($negotiated)
  Assert-Failed 'item-decision' 'Missing decision timestamp'
  Write-Events $events @($negotiated)
  $target = $original | ConvertFrom-Json
  $component = @($target.received | Where-Object { $_.kind -ceq 'messageComponent' })[0]
  $component.data.component.hover_event.value = @{ text = 'Count: 1' }
  $component.data.component.text += ' minecraft:diamond_sword'
  [IO.File]::WriteAllText($targetPath, ($target | ConvertTo-Json -Depth 40))
  Assert-Failed 'item-target' 'Item ID outside hover'
  [IO.File]::WriteAllText($targetPath, $original)
  $legacy = '{"extra":[{"hoverEvent":{"action":"show_text","contents":{"text":"minecraft:diamond_sword Count: 1"}}}]}' | ConvertFrom-Json
  if (-not (Test-RenderEffectsHover $legacy)) { throw 'Legacy hover contents rejected.' }
  $modernText = '{"hover_event":{"action":"show_text","text":{"text":"minecraft:diamond_sword Count: 1"}}}' | ConvertFrom-Json
  if (-not (Test-RenderEffectsHover $modernText)) { throw 'Modern hover text rejected.' }
  $other = Join-Path $root ('bots/render-effects/' + [guid]::NewGuid().ToString('N'))
  $null = New-Item -ItemType Directory -Path $other
  [IO.File]::WriteAllText((Join-Path $other 'copy.results.json'), $original)
  Assert-Failed 'target-completed' 'Duplicate recording'
  Write-Output 'Render-effects synthetic positive and negative checks passed (18 required, 17 recomputed).'
} finally {
  $resolved = (Resolve-Path -LiteralPath $root).Path
  if ($resolved -eq $root -and $root.StartsWith([IO.Path]::GetTempPath(), [StringComparison]::OrdinalIgnoreCase)) {
    Remove-Item -LiteralPath $resolved -Recurse -Force
  }
}
