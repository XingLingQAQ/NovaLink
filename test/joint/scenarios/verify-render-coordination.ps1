[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'render-coordination.ps1')
$root = Join-Path ([IO.Path]::GetTempPath()) ('novalink-render-contract-' + [guid]::NewGuid().ToString('N'))
$bots = Join-Path $root 'bots/custom-effects/contract-r1-custom-effects'
$null = New-Item -ItemType Directory -Force -Path $bots, (Join-Path $root 'bukkit'), (Join-Path $root 'velocity')
$events = @()
foreach ($role in @('target', 'observer')) {
  $player = [guid]::NewGuid().ToString()
  $session = [guid]::NewGuid().ToString()
  $name = if ($role -eq 'target') { 'E2ECustomTarget' } else { 'E2ECustomWatch' }
  $channel = if ($role -eq 'target') { 'global' } else { 'local' }
  $bot = @{ runId = 'contract-r1-custom-effects'; username = $name; kind = 'java'; timedOut = $false;
    state = @{ kicked = $null }; received = @(
      @{ kind = 'playerJoined'; data = @{ player = $name; uuid = $player } },
      @{ kind = 'checkpoint'; key = 'ce-completed' }
    ) }
  $bot | ConvertTo-Json -Depth 7 | Set-Content -Encoding UTF8 (Join-Path $bots "$role.results.json")
  $events += @{ event = 'negotiated'; version = 1; playerId = $player; session = $session; server = 'bukkit' }
  foreach ($packet in @(9, 3)) {
    $events += @{ event = 'decision'; version = 1; playerId = $player; session = $session; server = 'bukkit';
      requestId = [guid]::NewGuid().ToString(); packetId = $packet; channelId = $channel; owner = 'proxy' }
  }
}
function Write-RenderFixture($ProxyEvents, $ServerEvents) {
  $proxyLines = @($ProxyEvents | ForEach-Object { '[INFO] [Debug] NOVA_RENDER_EVENT ' + ($_ | ConvertTo-Json -Compress) })
  $serverLines = @($ServerEvents | ForEach-Object { '[INFO] [Debug] NOVA_RENDER_EVENT ' + ($_ | ConvertTo-Json -Compress) })
  [IO.File]::WriteAllText((Join-Path $root 'velocity/proxy.stdout.log'), ($proxyLines -join "`n"))
  [IO.File]::WriteAllText((Join-Path $root 'bukkit/server.stdout.log'), ($serverLines -join "`n"))
}
function Read-RenderFixture {
  @(Get-JointRenderCoordinationAssertions -RoundDir $root -RunId 'contract' -ProxyPlatform 'velocity')
}
Write-RenderFixture $events $events
$rows = @(Read-RenderFixture)
$required = @(Get-JointRenderCoordinationAssertionIds -RoundIndex 1)
if ($required.Count -ne 6 -or @($required | Select-Object -Unique).Count -ne 6 -or
    $rows.Count -ne 6 -or @($rows.id | Select-Object -Unique).Count -ne 6 -or
    @($rows | Where-Object { -not $_.pass -or $_.kind -cne 'render-coordination' -or $_.id -cnotin $required }).Count) {
  throw 'Complete synthetic peer evidence or required gates rejected'
}
Write-RenderFixture @($events[0], $events[3], $events[1], $events[2], $events[4], $events[5]) $events
if (@(Read-RenderFixture | Where-Object { -not $_.pass }).Count) { throw 'Interleaved player negotiations rejected' }
$rotated = @($events)
foreach ($negotiated in @($events | Where-Object event -eq negotiated)) {
  $next = @{} + $negotiated
  $next.session = [guid]::NewGuid().ToString()
  $rotated += $next
  foreach ($decision in @($events | Where-Object { $_.event -ceq 'decision' -and $_.playerId -ceq $next.playerId })) {
    $newDecision = @{} + $decision
    $newDecision.session = $next.session
    $newDecision.requestId = [guid]::NewGuid().ToString()
    $rotated += $newDecision
  }
}
Write-RenderFixture $rotated $rotated
if (@(Read-RenderFixture | Where-Object { -not $_.pass }).Count) { throw 'Confirmed epoch rotation rejected' }
$stale = @($rotated)
$stale += @{} + @($events | Where-Object event -eq decision)[0]
Write-RenderFixture $stale $rotated
if (@(Read-RenderFixture | Where-Object { $_.id -ceq 'round1/render-coordination/target-title' -and $_.pass }).Count) {
  throw 'Old epoch decision accepted alongside valid decisions'
}
$otherPacket = @{} + @($events | Where-Object event -eq decision)[0]
$otherPacket.packetId = 7
Write-RenderFixture (@($rotated) + @($otherPacket)) $rotated
$rows = @(Read-RenderFixture)
if (@($rows | Where-Object { $_.id -match '/target-(title|chat)$' -and $_.pass }).Count -or
    @($rows | Where-Object { $_.id -match '/observer-(title|chat)$' -and -not $_.pass }).Count) {
  throw 'Stale decision in another packet or player isolation mishandled'
}
$early = @(@{} + @($events | Where-Object event -eq decision)[0]) + $events
Write-RenderFixture $early $events
if (@(Read-RenderFixture | Where-Object { $_.id -ceq 'round1/render-coordination/target-title' -and $_.pass }).Count) {
  throw 'Decision before negotiation accepted'
}
Write-RenderFixture $rotated $events
if (@(Read-RenderFixture | Where-Object { $_.id -match '-(title|chat)$' -and $_.pass }).Count) {
  throw 'Unconfirmed rotated epochs accepted'
}
$duplicate = @($rotated | ForEach-Object { @{} + $_ })
$targetTitles = @($duplicate | Where-Object { $_.event -ceq 'decision' -and $_.playerId -ceq $events[0].playerId -and $_.packetId -eq 9 })
$targetTitles[1].requestId = $targetTitles[0].requestId
Write-RenderFixture $duplicate $rotated
if (@(Read-RenderFixture | Where-Object { $_.id -ceq 'round1/render-coordination/target-title' -and $_.pass }).Count) {
  throw 'Duplicate request accepted across epochs'
}
Write-RenderFixture $events @()
if (@(Read-RenderFixture | Where-Object pass).Count) { throw 'Missing Bukkit confirmation accepted' }
$wrong = @($events | ForEach-Object { @{} + $_ })
foreach ($event in $wrong) { $event.session = [guid]::NewGuid().ToString() }
Write-RenderFixture $events $wrong
if (@(Read-RenderFixture | Where-Object pass).Count) { throw 'Different sessions accepted' }
$unacknowledged = @($events | ForEach-Object { @{} + $_ })
foreach ($event in $unacknowledged | Where-Object event -eq decision) { $event.event = 'claim' }
Write-RenderFixture $unacknowledged $events
if (@(Read-RenderFixture | Where-Object { $_.id -match '-(title|chat)$' -and $_.pass }).Count) { throw 'Unacknowledged claims accepted' }
$wrongChannel = @($events | ForEach-Object { @{} + $_ })
foreach ($event in $wrongChannel | Where-Object event -eq decision) { $event.channelId = 'other' }
Write-RenderFixture $wrongChannel $events
if (@(Read-RenderFixture | Where-Object { $_.id -match '-(title|chat)$' -and $_.pass }).Count) { throw 'Wrong-channel decisions accepted' }
Write-Host '[SYNTHETIC PASS] Six render-coordination gates; confirmed rotations accepted; stale, early, unconfirmed, duplicate, missing peer, claim and wrong-channel controls rejected. No game servers started.'
